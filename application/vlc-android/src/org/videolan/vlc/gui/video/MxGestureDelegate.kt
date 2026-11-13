/*
 * ***************************************************************************
 * MxGestureDelegate.kt
 * ***************************************************************************
 * MX Player style gesture layer for VLC for Android.
 *
 * Copyright (C) 2026  VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 * ***************************************************************************
 */
package org.videolan.vlc.gui.video

import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.ViewStubCompat
import androidx.core.content.ContextCompat
import org.videolan.libvlc.MediaPlayer
import org.videolan.tools.ENABLE_FASTPLAY
import org.videolan.tools.MX_DOUBLE_TAP
import org.videolan.tools.MX_GESTURE_MODE
import org.videolan.tools.MX_LOCK_GESTURE
import org.videolan.tools.MX_PINCH_ZOOM
import org.videolan.tools.MX_SEEK_PREVIEW
import org.videolan.tools.MX_SEEK_PREVIEW_MODE
import org.videolan.tools.MX_SWIPE_SEEK
import org.videolan.tools.Settings
import org.videolan.vlc.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Replaces the stock [VideoTouchDelegate] when MX mode is on.
 *
 * Behaviour differences from stock VLC:
 *  - horizontal drag seeks the main picture live (pause + seek) or previews a thumbnail in a small
 *    centred panel, depending on the preview mode setting
 *  - left half vertical drag = brightness, right half = volume (absolute from gesture start)
 *  - double tap on the outer quarters = stock jump, centre = play/pause (it reads the stock jump
 *    delay settings, so it always matches the native player)
 *  - long press = the player's own fast play (switch and speed live in the stock control
 *    settings), release restores the previous rate
 *  - pinch = aspect ratio, swipe up from the bottom edge = lock/unlock
 */
class MxGestureDelegate(
        private val player: VideoPlayerActivity,
        private val controls: MxControlsDelegate
) {

    companion object {
        private const val GESTURE_NONE = 0
        private const val GESTURE_SEEK = 1
        private const val GESTURE_BRIGHTNESS = 2
        private const val GESTURE_VOLUME = 3
        private const val GESTURE_LOCK = 4
        private const val GESTURE_PINCH = 5
        private const val TAP_TIMEOUT = 300L
        /** A press longer than this is not a tap, so releasing it must not toggle the controls. */
        private const val TAP_MAX_DURATION = 400L
        /** How long the finger has to rest before the stock fast play kicks in. */
        private const val FAST_PLAY_DELAY = 500L
        private const val PANEL_HIDE_DELAY = 700L
        private const val LOCK_SWIPE_THRESHOLD = 120f
        /** Do not re-decode the preview frame unless the target time moved by this much. */
        private const val PREVIEW_STEP = 700L

        /** Direct preview: the main picture itself jumps while dragging (pause + seek). */
        private const val SEEK_PREVIEW_SCREEN = "screen"
        /** Overlay preview: the picture stays put, a decoded thumbnail pops up in the centre. */
        private const val SEEK_PREVIEW_OVERLAY = "overlay"
        /** Time readout only, no frame preview. */
        private const val SEEK_PREVIEW_OFF = "off"
        /** Minimum gap between two live seeks, so a slow decoder is never flooded. */
        private const val SCREEN_SEEK_INTERVAL = 180L
        /** Tiny movements are not worth a seek; the exact position is applied on release anyway. */
        private const val SCREEN_SEEK_STEP = 400L

        /** Longest side of the decoded thumbnail, in pixels. */
        private const val PREVIEW_MAX_SIDE = 640

        /** Widest the thumbnail may be drawn, in dp (the panel's inner width). */
        private const val PREVIEW_MAX_WIDTH_DP = 200

        /** Tallest the thumbnail may be drawn, in dp — keeps a 9:16 clip from swallowing the panel. */
        private const val PREVIEW_MAX_HEIGHT_DP = 180
        private const val TAG = "MxGestureDelegate"
    }

    private val handler = Handler(Looper.getMainLooper())
    private val audioManager: AudioManager by lazy(LazyThreadSafetyMode.NONE) {
        player.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }
    private val touchSlop by lazy(LazyThreadSafetyMode.NONE) { ViewConfiguration.get(player).scaledTouchSlop }
    private val screenWidth by lazy(LazyThreadSafetyMode.NONE) { player.resources.displayMetrics.widthPixels }
    private val screenHeight by lazy(LazyThreadSafetyMode.NONE) { player.resources.displayMetrics.heightPixels }

    private var enabled = true
    private var allowSwipeSeek = true
    private var seekPreviewMode = SEEK_PREVIEW_SCREEN
    private var allowDoubleTap = true
    private var allowFastPlay = false
    private var allowPinchZoom = true
    private var allowLockGesture = true

    /**
     * Whether the orientation was already pinned before the screen lock was engaged, so unlocking
     * only releases the pin we added ourselves.
     */
    private var orientationLockedBeforeLock = false

    private var startX = 0f
    private var startY = 0f
    private var startTimePos = 0L
    private var targetTime = 0L
    private var length = 0L
    private var startVolume = 0
    private var maxVolume = 0
    private var startBrightness = 0.5f
    private var gesture = GESTURE_NONE
    private var moved = false
    private var multiPointer = false
    private var tapCount = 0
    private var downStamp = 0L
    private var fastPlayActive = false
    private var savedRate = 1f
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var lockedStartY = 0f
    private var accumulatedScale = 1f
    private var screenSeekActive = false
    private var screenSeekWasPlaying = false
    private var screenSeekLastTime = -1L
    private var screenSeekLastStamp = 0L

    private val panel: View = player.findViewById<ViewStubCompat>(R.id.mx_gesture_panel_stub).inflate()
    private val panelCard: View = panel.findViewById(R.id.mx_gesture_card)
    private val panelIcon: ImageView = panel.findViewById(R.id.mx_gesture_icon)
    private val panelTitle: TextView = panel.findViewById(R.id.mx_gesture_title)
    private val panelSubtitle: TextView = panel.findViewById(R.id.mx_gesture_subtitle)
    private val panelProgress: ProgressBar = panel.findViewById(R.id.mx_gesture_progress)
    private val previewImage: ImageView = panel.findViewById(R.id.mx_gesture_preview)

    /**
     * Seek preview frames are decoded with [MediaMetadataRetriever] on a private thread: the main
     * player keeps running untouched, and a slow decoder can never stall the gesture.
     */
    private val previewThread = HandlerThread("mx-seek-preview")
    private val previewHandler: Handler = Handler(previewThread.apply { start() }.looper)
    private var previewRetriever: MediaMetadataRetriever? = null
    private var previewRetrieverUri: String? = null
    private var previewBusy = false
    private var previewPendingTime = -1L
    private var previewRequestedTime = -1L
    private var previewGeneration = 0
    private var previewFrame: Bitmap? = null

    private val hidePanelRunnable = Runnable { hidePanel() }
    private val singleTapRunnable = Runnable {
        if (tapCount == 1) controls.toggle()
    }
    /**
     * Long press hands over to the player's own fast play instead of a private 2x mode, so the
     * stock "long press for fast play" switch and its speed setting keep working in MX mode.
     */
    private val fastPlayRunnable = Runnable {
        if (moved || multiPointer || gesture != GESTURE_NONE || player.isLocked ||
                !allowFastPlay || fastPlayActive) return@Runnable
        val service = player.service ?: return@Runnable
        savedRate = service.rate
        service.setRate(Settings.fastplaySpeed, false)
        fastPlayActive = true
        player.touchDelegate.showFastPlay()
        controls.hide(true)
    }

    private val scaleDetector by lazy(LazyThreadSafetyMode.NONE) {
        ScaleGestureDetector(player, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
                accumulatedScale = 1f
                return allowPinchZoom && !player.isLocked
            }

            override fun onScale(detector: ScaleGestureDetector): Boolean {
                accumulatedScale *= detector.scaleFactor
                return true
            }

            override fun onScaleEnd(detector: ScaleGestureDetector) {
                if (!allowPinchZoom) return
                val mediaPlayer = player.service?.mediaplayer ?: return
                val fill = accumulatedScale >= 1f
                mediaPlayer.videoScale = if (fill) MediaPlayer.ScaleType.SURFACE_FILL else MediaPlayer.ScaleType.SURFACE_BEST_FIT
                accumulatedScale = 1f
                showPanel(R.drawable.ic_player_ratio, player.getString(R.string.mx_zoom),
                        player.getString(if (fill) R.string.mx_zoom_fill else R.string.mx_zoom_fit), 100, 100)
                hidePanelDelayed()
            }
        })
    }

    init {
        panel.visibility = View.GONE
        reloadSettings()
    }

    fun isEnabled() = enabled

    fun isControlsVisible() = enabled && controls.isVisible()

    fun hideControls() = controls.hide()

    /** Short centred feedback used by the MX control buttons (ratio, rotation, …). */
    fun showHint(iconRes: Int, title: String) {
        showPanel(iconRes, title, "", 100, 100)
        hidePanelDelayed()
    }

    fun reloadSettings() {
        val prefs = Settings.getInstance(player)
        enabled = prefs.getBoolean(MX_GESTURE_MODE, true)
        allowSwipeSeek = prefs.getBoolean(MX_SWIPE_SEEK, true)
        seekPreviewMode = readSeekPreviewMode(prefs)
        allowDoubleTap = prefs.getBoolean(MX_DOUBLE_TAP, true)
        allowFastPlay = prefs.getBoolean(ENABLE_FASTPLAY, false)
        allowPinchZoom = prefs.getBoolean(MX_PINCH_ZOOM, true)
        allowLockGesture = prefs.getBoolean(MX_LOCK_GESTURE, true)
        controls.setMxEnabled(enabled)
        if (enabled) controls.show() else {
            hidePanel()
            // Turning MX gestures off must not leave the orientation pinned by the lock behind.
            if (player.isLocked) restoreOrientationAfterLock()
            player.isLocked = false
        }
    }

    /**
     * Older builds only had an on/off switch, so a switch that was turned off maps to "off" and
     * everybody else gets the new direct-preview default.
     */
    private fun readSeekPreviewMode(prefs: SharedPreferences): String {
        prefs.getString(MX_SEEK_PREVIEW_MODE, null)?.let { return it }
        return if (prefs.getBoolean(MX_SEEK_PREVIEW, true)) SEEK_PREVIEW_SCREEN else SEEK_PREVIEW_OFF
    }

    fun onTouchEvent(event: MotionEvent): Boolean {
        if (!enabled) return false
        // PiP / popup window: let the system and the stock popup buttons handle the taps.
        if (player.service?.isInPiPMode?.value == true) return false
        if (player.isLocked) {
            handleLockedTouch(event)
            return true
        }
        if (allowPinchZoom) scaleDetector.onTouchEvent(event)
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> handleDown(event)
            MotionEvent.ACTION_POINTER_DOWN -> {
                multiPointer = true
                cancelFastPlay()
                handler.removeCallbacks(singleTapRunnable)
                tapCount = 0
                gesture = GESTURE_PINCH
                hidePanel()
            }
            MotionEvent.ACTION_MOVE -> if (!multiPointer) handleMove(event)
            MotionEvent.ACTION_UP -> handleUp(event)
            MotionEvent.ACTION_CANCEL -> handleCancel()
        }
        return true
    }

    private fun handleDown(event: MotionEvent) {
        val now = System.currentTimeMillis()
        downStamp = SystemClock.elapsedRealtime()
        tapCount = if (now - lastTapTime < TAP_TIMEOUT &&
                abs(event.x - lastTapX) < touchSlop && abs(event.y - lastTapY) < touchSlop) {
            tapCount + 1
        } else 1
        handler.removeCallbacks(singleTapRunnable)
        startX = event.x
        startY = event.y
        moved = false
        multiPointer = false
        gesture = GESTURE_NONE
        val service = player.service
        length = service?.length ?: 0L
        startTimePos = player.time
        targetTime = startTimePos
        maxVolume = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
        startVolume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
        startBrightness = currentBrightness()
        screenSeekActive = false
        screenSeekWasPlaying = false
        resetPreview()
        if (tapCount == 1 && allowFastPlay) handler.postDelayed(fastPlayRunnable, FAST_PLAY_DELAY)
    }

    private fun handleMove(event: MotionEvent) {
        val dx = event.x - startX
        val dy = event.y - startY
        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            moved = true
            cancelFastPlay()
            handler.removeCallbacks(singleTapRunnable)
            tapCount = 0
        }
        if (!moved) return
        if (gesture == GESTURE_NONE) {
            gesture = when {
                abs(dx) > abs(dy) * 1.2f -> if (allowSwipeSeek) GESTURE_SEEK else GESTURE_NONE
                abs(dy) > abs(dx) * 1.2f -> when {
                    allowLockGesture && startY > screenHeight * 0.88f && dy < -touchSlop -> GESTURE_LOCK
                    startX < screenWidth / 2f -> GESTURE_BRIGHTNESS
                    else -> GESTURE_VOLUME
                }
                else -> GESTURE_NONE
            }
        }
        when (gesture) {
            GESTURE_SEEK -> updateSeekPreview(dx)
            GESTURE_BRIGHTNESS -> updateBrightness(dy)
            GESTURE_VOLUME -> updateVolume(dy)
            GESTURE_LOCK -> showPanel(R.drawable.ic_lock_player, player.getString(R.string.mx_locked),
                    player.getString(R.string.mx_release_to_lock), 100, 100)
        }
    }

    private fun updateSeekPreview(dx: Float) {
        val service = player.service ?: return
        if (length <= 0L || !service.isSeekable) {
            showPanel(R.drawable.ic_player_forward_10, player.getString(R.string.mx_unseekable), "", 0, 100)
            return
        }
        beginScreenSeek()
        val ratio = dx / screenWidth.toFloat()
        targetTime = (startTimePos + (ratio * length).toLong()).coerceIn(0L, length)
        val delta = (targetTime - startTimePos) / 1000L
        val sign = if (delta >= 0L) "+" else "-"
        val overlay = seekPreviewMode == SEEK_PREVIEW_OVERLAY
        showPanel(if (delta >= 0L) R.drawable.ic_player_forward_10 else R.drawable.ic_player_rewind_10,
                "${formatTime(targetTime)} / ${formatTime(length)}",
                "$sign${formatTime(abs(delta) * 1000L)}",
                if (length > 0L) ((targetTime * 100) / length).toInt() else 0, 100,
                previewMode = overlay, docked = seekPreviewMode == SEEK_PREVIEW_SCREEN)
        if (overlay) requestPreviewFrame(targetTime) else applyScreenSeek()
        controls.hide(true)
    }

    /**
     * Direct preview: pause the main player and seek it, so the picture on screen itself follows the
     * finger instead of being duplicated in a thumbnail. The playback state is restored on release.
     */
    private fun beginScreenSeek() {
        if (seekPreviewMode != SEEK_PREVIEW_SCREEN || screenSeekActive) return
        val service = player.service ?: return
        if (length <= 0L || !service.isSeekable) return
        screenSeekActive = true
        screenSeekLastTime = -1L
        screenSeekLastStamp = 0L
        screenSeekWasPlaying = service.isPlaying
        if (screenSeekWasPlaying) service.pause()
    }

    /** Throttled live seek: enough updates to feel continuous, few enough to keep the decoder calm. */
    private fun applyScreenSeek() {
        if (!screenSeekActive) return
        val now = SystemClock.elapsedRealtime()
        if (now - screenSeekLastStamp < SCREEN_SEEK_INTERVAL) return
        if (screenSeekLastTime >= 0L && abs(targetTime - screenSeekLastTime) < SCREEN_SEEK_STEP) return
        screenSeekLastStamp = now
        screenSeekLastTime = targetTime
        player.seek(targetTime, false)
    }

    /** Release: land exactly on the target and go back to playing if we paused it. */
    private fun finishScreenSeek() {
        if (!screenSeekActive) return
        screenSeekActive = false
        screenSeekLastTime = -1L
        player.seek(targetTime, false)
        if (screenSeekWasPlaying) player.play()
        screenSeekWasPlaying = false
    }

    /** Gesture cancelled: put the picture back where it came from. */
    private fun abortScreenSeek() {
        if (!screenSeekActive) return
        screenSeekActive = false
        screenSeekLastTime = -1L
        player.seek(startTimePos, false)
        if (screenSeekWasPlaying) player.play()
        screenSeekWasPlaying = false
    }

    private fun updateBrightness(dy: Float) {
        val delta = -dy / (screenHeight * 0.7f)
        val value = (startBrightness + delta).coerceIn(0.02f, 1f)
        val attributes = player.window.attributes
        attributes.screenBrightness = value
        player.window.attributes = attributes
        val percent = (value * 100).roundToInt()
        showPanel(R.drawable.ic_player_brightness, "$percent%", player.getString(R.string.mx_brightness), percent, 100)
        controls.hide(true)
    }

    private fun updateVolume(dy: Float) {
        val delta = -dy / (screenHeight * 0.7f)
        val value = (startVolume + delta * maxVolume).roundToInt().coerceIn(0, maxVolume)
        audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, value, 0)
        player.service?.setVolume(100)
        val percent = if (maxVolume > 0) value * 100 / maxVolume else 0
        showPanel(R.drawable.ic_player_volume, "$percent%", player.getString(R.string.mx_volume), percent, 100)
        controls.hide(true)
    }

    private fun handleUp(event: MotionEvent) {
        cancelFastPlay()
        if (fastPlayActive) {
            player.service?.setRate(savedRate, false)
            fastPlayActive = false
            player.touchDelegate.hideFastPlay()
        }
        when (gesture) {
            GESTURE_SEEK -> {
                if (allowSwipeSeek && length > 0L && player.service?.isSeekable == true) {
                    if (screenSeekActive) finishScreenSeek() else player.seek(targetTime, true)
                }
                controls.show()
            }
            GESTURE_LOCK -> setLocked(true)
            GESTURE_BRIGHTNESS, GESTURE_VOLUME, GESTURE_PINCH -> controls.show()
        }
        hidePanelDelayed()
        if (!moved && !multiPointer && SystemClock.elapsedRealtime() - downStamp <= TAP_MAX_DURATION) {
            lastTapTime = System.currentTimeMillis()
            lastTapX = event.x
            lastTapY = event.y
            if (tapCount >= 2) {
                tapCount = 0
                handler.removeCallbacks(singleTapRunnable)
                performDoubleTap(event.x)
            } else {
                handler.postDelayed(singleTapRunnable, TAP_TIMEOUT)
            }
        } else {
            tapCount = 0
            lastTapTime = 0L
        }
        gesture = GESTURE_NONE
        multiPointer = false
        resetPreview()
    }

    private fun handleCancel() {
        cancelFastPlay()
        if (fastPlayActive) {
            player.service?.setRate(savedRate, false)
            fastPlayActive = false
            player.touchDelegate.hideFastPlay()
        }
        abortScreenSeek()
        gesture = GESTURE_NONE
        multiPointer = false
        tapCount = 0
        hidePanel()
        resetPreview()
        controls.show()
    }

    private fun handleLockedTouch(event: MotionEvent) {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> lockedStartY = event.y
            MotionEvent.ACTION_UP -> {
                if (lockedStartY - event.y > LOCK_SWIPE_THRESHOLD) {
                    setLocked(false)
                } else {
                    showPanel(R.drawable.ic_lock_player, player.getString(R.string.mx_locked),
                            player.getString(R.string.mx_swipe_up_unlock), 100, 100)
                    hidePanelDelayed()
                }
            }
        }
    }

    /**
     * Lock or unlock the player. Locking also freezes the orientation the player has right now:
     * a portrait picture stays portrait even when the device is turned, and the pin is released
     * again on unlock (unless the user had pinned the orientation themselves beforehand).
     */
    fun setLocked(locked: Boolean) {
        if (locked) {
            orientationLockedBeforeLock = player.orientationMode.locked
            if (!player.orientationMode.locked) player.toggleOrientationLock()
        } else {
            restoreOrientationAfterLock()
        }
        player.isLocked = locked
        if (locked) {
            controls.hide(true)
            showPanel(R.drawable.ic_lock_player, player.getString(R.string.mx_locked),
                    player.getString(R.string.mx_swipe_up_unlock), 100, 100)
        } else {
            showPanel(R.drawable.ic_lock_player, player.getString(R.string.mx_unlocked), "", 0, 100)
            controls.show()
        }
        hidePanelDelayed()
    }

    /** Release the orientation pin we added on lock, unless it was already pinned before. */
    private fun restoreOrientationAfterLock() {
        if (player.orientationMode.locked && !orientationLockedBeforeLock) player.toggleOrientationLock()
    }

    /**
     * Same zones as the stock touch delegate: the outer quarters jump, the middle toggles playback.
     * The step comes from the stock "double tap jump" delay, so both layers always agree.
     */
    private fun performDoubleTap(x: Float) {
        if (!allowDoubleTap) return
        val delay = Settings.videoDoubleTapJumpDelay
        when {
            x < screenWidth / 4f -> jump(-delay)
            x > screenWidth * 0.75f -> jump(delay)
            else -> togglePlayPause()
        }
    }

    private fun jump(seconds: Int) {
        val service = player.service ?: return
        val total = service.length
        if (total <= 0L || !service.isSeekable) return
        val target = (player.time + seconds * 1000L).coerceIn(0L, total)
        player.seek(target, true)
        showPanel(if (seconds >= 0) R.drawable.ic_player_forward_10 else R.drawable.ic_player_rewind_10,
                "${if (seconds >= 0) "+" else "-"}${abs(seconds)} s",
                "${formatTime(target)} / ${formatTime(total)}",
                if (total > 0L) ((target * 100) / total).toInt() else 0, 100)
        hidePanelDelayed()
    }

    private fun togglePlayPause() {
        val service = player.service ?: return
        if (service.isPlaying) service.pause() else player.play()
        controls.show()
    }

    private fun showPanel(iconRes: Int, title: String, subtitle: String, progress: Int, max: Int,
                          previewMode: Boolean = false, docked: Boolean = false) {
        handler.removeCallbacks(hidePanelRunnable)
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        panelIcon.setImageDrawable(ContextCompat.getDrawable(player, iconRes))
        panelTitle.text = title
        panelSubtitle.text = subtitle
        panelProgress.max = max
        panelProgress.progress = progress.coerceIn(0, max)
        dockPanel(docked)
        // The seek panel trades the big icon for the frame thumbnail once the first one arrives.
        val showFrame = previewMode && previewFrame != null && !docked
        panelIcon.visibility = if (showFrame || docked) View.GONE else View.VISIBLE
        previewImage.visibility = if (showFrame) View.VISIBLE else View.GONE
    }

    /**
     * Direct preview is drawn in the main picture, so the panel moves to the top and drops its icon
     * to stay out of the way of what the user is scrubbing.
     */
    private fun dockPanel(docked: Boolean) {
        val params = panelCard.layoutParams as FrameLayout.LayoutParams
        val gravity = if (docked) Gravity.TOP or Gravity.CENTER_HORIZONTAL else Gravity.CENTER
        val top = if (docked) (16 * player.resources.displayMetrics.density).toInt() else 0
        if (params.gravity == gravity && params.topMargin == top) return
        params.gravity = gravity
        params.topMargin = top
        panelCard.layoutParams = params
    }

    private fun hidePanel() {
        panel.visibility = View.GONE
    }

    private fun hidePanelDelayed() {
        handler.removeCallbacks(hidePanelRunnable)
        handler.postDelayed(hidePanelRunnable, PANEL_HIDE_DELAY)
    }

    /** Drop any pending frame and hide the thumbnail; called when a gesture starts or ends. */
    private fun resetPreview() {
        previewGeneration++
        previewPendingTime = -1L
        previewRequestedTime = -1L
        previewFrame = null
        previewImage.setImageDrawable(null)
        previewImage.visibility = View.GONE
        panelIcon.visibility = View.VISIBLE
    }

    /**
     * Asks for a frame at [timeMs]. Requests are coalesced: while one decode is running only the
     * newest target is remembered, and small movements reuse the frame already on screen.
     */
    private fun requestPreviewFrame(timeMs: Long) {
        if (seekPreviewMode != SEEK_PREVIEW_OVERLAY) return
        if (previewRequestedTime >= 0L && abs(timeMs - previewRequestedTime) < PREVIEW_STEP) return
        previewRequestedTime = timeMs
        previewPendingTime = timeMs
        if (!previewBusy) loadPreviewFrame()
    }

    private fun loadPreviewFrame() {
        val requested = previewPendingTime
        if (requested < 0L) return
        previewPendingTime = -1L
        previewBusy = true
        val generation = previewGeneration
        previewHandler.post {
            val frame = decodePreviewFrame(requested)
            handler.post ui@{
                previewBusy = false
                // The gesture may have ended, or moved on to another media, meanwhile.
                if (generation != previewGeneration) return@ui
                if (frame != null) {
                    previewFrame = frame
                    if (gesture == GESTURE_SEEK) {
                        applyPreviewRatio(frame)
                        previewImage.setImageBitmap(frame)
                        previewImage.visibility = View.VISIBLE
                        panelIcon.visibility = View.GONE
                    }
                }
                if (previewPendingTime >= 0L) loadPreviewFrame()
            }
        }
    }

    private fun decodePreviewFrame(timeMs: Long): Bitmap? {
        val retriever = obtainRetriever() ?: return null
        val timeUs = timeMs * 1000L
        // Ask for an already downscaled frame where possible: a 4K keyframe is ~33 MB of pixels,
        // which is far too much to allocate on repeat for a 200 dp thumbnail.
        val scaled = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            try {
                retriever.getScaledFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                        PREVIEW_MAX_SIDE, -1)
            } catch (e: Throwable) {
                null
            }
        } else null
        val frame = scaled ?: try {
            retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        } catch (e: Throwable) {
            Log.w(TAG, "seek preview frame at ${timeMs}ms failed", e)
            null
        }
        val capped = frame?.let { downscale(it) } ?: return null
        return rotateForDisplay(retriever, capped)
    }

    /**
     * Portrait clips are often stored as landscape pixels plus a rotation matrix, and decoders
     * disagree about applying it when grabbing a frame. Rotate only when the frame still looks the
     * wrong way round compared with the metadata, so a clip is never rotated twice.
     */
    private fun rotateForDisplay(retriever: MediaMetadataRetriever, frame: Bitmap): Bitmap {
        val rotation = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)
                    ?.toIntOrNull() ?: 0
        } catch (e: Throwable) {
            0
        }
        if (rotation % 180 == 0 || frame.width <= 0 || frame.height <= 0) return frame
        val metaWidth = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
        } catch (e: Throwable) {
            null
        }
        val metaHeight = try {
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
        } catch (e: Throwable) {
            null
        }
        // The rotation swaps the axes, so the displayed orientation is the opposite of the stored one.
        val expectedPortrait = if (metaWidth != null && metaHeight != null && metaWidth != metaHeight) {
            metaHeight <= metaWidth
        } else true
        if (expectedPortrait == (frame.height > frame.width)) return frame
        return try {
            val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
            val rotated = Bitmap.createBitmap(frame, 0, 0, frame.width, frame.height, matrix, true)
            if (rotated !== frame) frame.recycle()
            rotated
        } catch (e: Throwable) {
            Log.w(TAG, "seek preview rotation failed", e)
            frame
        }
    }

    /**
     * Caps the longest side, not just the width: a portrait clip is tall, so scaling by width alone
     * would leave it several times bigger than a landscape one for the same thumbnail size.
     */
    private fun downscale(frame: Bitmap): Bitmap {
        val longest = maxOf(frame.width, frame.height)
        if (frame.width <= 0 || frame.height <= 0 || longest <= PREVIEW_MAX_SIDE) return frame
        val scale = PREVIEW_MAX_SIDE.toFloat() / longest
        val width = (frame.width * scale).toInt().coerceAtLeast(1)
        val height = (frame.height * scale).toInt().coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(frame, width, height, true)
            if (scaled !== frame) frame.recycle()
            scaled
        } catch (e: Throwable) {
            frame
        }
    }

    /**
     * Sizes the thumbnail to the frame's own aspect ratio instead of a fixed 16:9 box, so a 9:16
     * (Douyin style) or ultra-wide clip is shown in full rather than cropped to its centre.
     */
    private fun applyPreviewRatio(frame: Bitmap) {
        if (frame.width <= 0 || frame.height <= 0) return
        val density = player.resources.displayMetrics.density
        val maxWidth = (PREVIEW_MAX_WIDTH_DP * density).toInt()
        // Do not let a tall clip push the panel past the screen on short landscape displays.
        val availableHeight = (panel.height.takeIf { it > 0 } ?: screenHeight) * 0.45f
        val maxHeight = minOf((PREVIEW_MAX_HEIGHT_DP * density).toInt(), availableHeight.toInt())
                .coerceAtLeast((64 * density).toInt())
        val ratio = frame.width.toFloat() / frame.height.toFloat()
        var width = maxWidth
        var height = (width / ratio).toInt().coerceAtLeast(1)
        if (height > maxHeight) {
            height = maxHeight
            width = (height * ratio).toInt().coerceAtLeast(1)
        }
        val params = previewImage.layoutParams
        if (params.width != width || params.height != height) {
            params.width = width
            params.height = height
            previewImage.layoutParams = params
        }
    }

    /** One retriever per media, reused across gestures: opening the container is the slow part. */
    private fun obtainRetriever(): MediaMetadataRetriever? {
        val uri = currentMediaUri() ?: return null
        val key = uri.toString()
        previewRetriever?.let { if (previewRetrieverUri == key) return it }
        releaseRetriever()
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(player, uri)
            previewRetriever = retriever
            previewRetrieverUri = key
            retriever
        } catch (e: Throwable) {
            // Network streams and some containers allow no random access: keep the time-only panel.
            Log.i(TAG, "seek preview unsupported for $key")
            try {
                retriever.release()
            } catch (ignored: Throwable) {
            }
            null
        }
    }

    private fun currentMediaUri(): Uri? {
        val service = player.service ?: return null
        return try {
            service.playlistManager.getCurrentMedia()?.uri
        } catch (e: Throwable) {
            // Playlist manager not ready yet.
            null
        }
    }

    private fun releaseRetriever() {
        val retriever = previewRetriever ?: return
        previewRetriever = null
        previewRetrieverUri = null
        try {
            retriever.release()
        } catch (ignored: Throwable) {
        }
    }

    private fun cancelFastPlay() = handler.removeCallbacks(fastPlayRunnable)

    private fun currentBrightness(): Float {
        val value = player.window.attributes.screenBrightness
        return if (value in 0.01f..1f) value else 0.5f
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        previewGeneration++
        previewHandler.post { releaseRetriever() }
        previewThread.quitSafely()
        controls.release()
    }

    private fun formatTime(ms: Long): String {
        val total = (ms / 1000L).coerceAtLeast(0L)
        val hours = total / 3600L
        val minutes = (total % 3600L) / 60L
        val seconds = total % 60L
        return if (hours > 0L) {
            String.format("%d:%02d:%02d", hours, minutes, seconds)
        } else {
            String.format("%02d:%02d", minutes, seconds)
        }
    }
}

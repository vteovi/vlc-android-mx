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
import android.graphics.Bitmap
import android.media.AudioManager
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.util.Log
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.ViewConfiguration
import android.widget.ImageView
import android.widget.ProgressBar
import android.widget.TextView
import androidx.appcompat.widget.ViewStubCompat
import androidx.core.content.ContextCompat
import org.videolan.libvlc.MediaPlayer
import org.videolan.tools.MX_DOUBLE_TAP
import org.videolan.tools.MX_DOUBLE_TAP_JUMP
import org.videolan.tools.MX_GESTURE_MODE
import org.videolan.tools.MX_LOCK_GESTURE
import org.videolan.tools.MX_LONG_PRESS_SPEED
import org.videolan.tools.MX_PINCH_ZOOM
import org.videolan.tools.MX_SEEK_PREVIEW
import org.videolan.tools.MX_SWIPE_SEEK
import org.videolan.tools.Settings
import org.videolan.vlc.R
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Replaces the stock [VideoTouchDelegate] when MX mode is on.
 *
 * Behaviour differences from stock VLC:
 *  - horizontal drag previews a target time plus a frame thumbnail, and only seeks on release
 *  - left half vertical drag = brightness, right half = volume (absolute from gesture start)
 *  - double tap on left / right third = jump, centre = play/pause
 *  - long press = temporary 2x speed, release restores the previous rate
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
        private const val LONG_PRESS_TIMEOUT = 500L
        private const val PANEL_HIDE_DELAY = 700L
        private const val LOCK_SWIPE_THRESHOLD = 120f
        /** Do not re-decode the preview frame unless the target time moved by this much. */
        private const val PREVIEW_STEP = 700L
        private const val PREVIEW_MAX_WIDTH = 480
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
    private var allowSeekPreview = true
    private var allowDoubleTap = true
    private var allowLongPressSpeed = true
    private var allowPinchZoom = true
    private var allowLockGesture = true
    private var jumpSeconds = 60

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
    private var lastTapTime = 0L
    private var lastTapX = 0f
    private var lastTapY = 0f
    private var fastPlayActive = false
    private var savedRate = 1f
    private var lockedStartY = 0f
    private var accumulatedScale = 1f

    private val panel: View = player.findViewById<ViewStubCompat>(R.id.mx_gesture_panel_stub).inflate()
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
    private val longPressRunnable = Runnable {
        if (moved || multiPointer || gesture != GESTURE_NONE || player.isLocked || !allowLongPressSpeed) return@Runnable
        val service = player.service ?: return@Runnable
        savedRate = service.rate
        service.setRate(2f, false)
        fastPlayActive = true
        showPanel(R.drawable.ic_speed, player.getString(R.string.mx_fast_play), "2.0x", 100, 100)
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

    /** Jump interval used by double tap and the bottom bar buttons, in seconds. */
    val jumpInterval: Int get() = jumpSeconds

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
        allowSeekPreview = prefs.getBoolean(MX_SEEK_PREVIEW, true)
        allowDoubleTap = prefs.getBoolean(MX_DOUBLE_TAP, true)
        allowLongPressSpeed = prefs.getBoolean(MX_LONG_PRESS_SPEED, true)
        allowPinchZoom = prefs.getBoolean(MX_PINCH_ZOOM, true)
        allowLockGesture = prefs.getBoolean(MX_LOCK_GESTURE, true)
        jumpSeconds = prefs.getString(MX_DOUBLE_TAP_JUMP, "60")?.toIntOrNull() ?: 60
        controls.setJumpInterval(jumpSeconds)
        controls.setMxEnabled(enabled)
        if (enabled) controls.show() else {
            hidePanel()
            player.isLocked = false
        }
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
                cancelLongPress()
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
        resetPreview()
        if (tapCount == 1 && allowLongPressSpeed) handler.postDelayed(longPressRunnable, LONG_PRESS_TIMEOUT)
    }

    private fun handleMove(event: MotionEvent) {
        val dx = event.x - startX
        val dy = event.y - startY
        if (!moved && (abs(dx) > touchSlop || abs(dy) > touchSlop)) {
            moved = true
            cancelLongPress()
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
        val ratio = dx / screenWidth.toFloat()
        targetTime = (startTimePos + (ratio * length).toLong()).coerceIn(0L, length)
        val delta = (targetTime - startTimePos) / 1000L
        val sign = if (delta >= 0L) "+" else "-"
        showPanel(if (delta >= 0L) R.drawable.ic_player_forward_10 else R.drawable.ic_player_rewind_10,
                "${formatTime(targetTime)} / ${formatTime(length)}",
                "$sign${formatTime(abs(delta) * 1000L)}",
                if (length > 0L) ((targetTime * 100) / length).toInt() else 0, 100,
                previewMode = allowSeekPreview)
        requestPreviewFrame(targetTime)
        controls.hide(true)
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
        cancelLongPress()
        if (fastPlayActive) {
            player.service?.setRate(savedRate, false)
            fastPlayActive = false
        }
        when (gesture) {
            GESTURE_SEEK -> {
                if (allowSwipeSeek && length > 0L && player.service?.isSeekable == true) {
                    player.seek(targetTime, true)
                }
                controls.show()
            }
            GESTURE_LOCK -> setLocked(true)
            GESTURE_BRIGHTNESS, GESTURE_VOLUME, GESTURE_PINCH -> controls.show()
        }
        hidePanelDelayed()
        if (!moved && !multiPointer && !fastPlayActive) {
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
        cancelLongPress()
        if (fastPlayActive) {
            player.service?.setRate(savedRate, false)
            fastPlayActive = false
        }
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

    fun setLocked(locked: Boolean) {
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

    private fun performDoubleTap(x: Float) {
        if (!allowDoubleTap) return
        val third = screenWidth / 3f
        when {
            x < third -> jump(-jumpSeconds)
            x > third * 2f -> jump(jumpSeconds)
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
                          previewMode: Boolean = false) {
        handler.removeCallbacks(hidePanelRunnable)
        panel.visibility = View.VISIBLE
        panel.alpha = 1f
        panelIcon.setImageDrawable(ContextCompat.getDrawable(player, iconRes))
        panelTitle.text = title
        panelSubtitle.text = subtitle
        panelProgress.max = max
        panelProgress.progress = progress.coerceIn(0, max)
        // The seek panel trades the big icon for the frame thumbnail once the first one arrives.
        val showFrame = previewMode && previewFrame != null
        panelIcon.visibility = if (showFrame) View.GONE else View.VISIBLE
        previewImage.visibility = if (showFrame) View.VISIBLE else View.GONE
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
        if (!allowSeekPreview) return
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
                        PREVIEW_MAX_WIDTH, -1)
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
        return frame?.let { downscale(it) }
    }

    private fun downscale(frame: Bitmap): Bitmap {
        if (frame.width <= 0 || frame.width <= PREVIEW_MAX_WIDTH) return frame
        val height = (frame.height * PREVIEW_MAX_WIDTH / frame.width).coerceAtLeast(1)
        return try {
            val scaled = Bitmap.createScaledBitmap(frame, PREVIEW_MAX_WIDTH, height, true)
            if (scaled !== frame) frame.recycle()
            scaled
        } catch (e: Throwable) {
            frame
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

    private fun cancelLongPress() = handler.removeCallbacks(longPressRunnable)

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

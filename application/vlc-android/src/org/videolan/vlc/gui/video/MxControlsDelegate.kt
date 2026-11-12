/*
 * ***************************************************************************
 * MxControlsDelegate.kt
 * ***************************************************************************
 * MX Player style player controls for VLC for Android.
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

import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.os.Handler
import android.os.Looper
import android.support.v4.media.session.PlaybackStateCompat
import android.text.InputType
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.appcompat.widget.ViewStubCompat
import androidx.core.content.ContextCompat
import org.videolan.libvlc.MediaPlayer
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.gui.dialogs.JumpToTimeDialog
import kotlin.math.abs

/**
 * MX-style HUD: top title bar, right vertical quick actions, bottom timeline.
 *
 * This delegate is intentionally independent from [VideoPlayerOverlayDelegate]:
 * the stock VLC HUD relies on data binding that is only initialised when the
 * stock gesture delegate calls showOverlay(). Building a separate layer keeps
 * the two skins from fighting over the same views.
 */
class MxControlsDelegate(private val player: VideoPlayerActivity) {

    companion object {
        /** Speeds offered by the MX speed sheet. */
        private val SPEEDS = floatArrayOf(0.25f, 0.5f, 0.75f, 1f, 1.25f, 1.5f, 1.75f, 2f, 2.5f, 3f, 4f)

        /**
         * Short list used by the single tap cycle on the speed button: one tap = next common
         * speed, the full [SPEEDS] list stays behind a long press.
         */
        private val QUICK_SPEEDS = floatArrayOf(0.5f, 1f, 1.25f, 1.5f, 2f)

        /**
         * Preset order followed by the single tap cycle on the ratio button: auto, fit, fill,
         * 16:9, 4:3, 1:1, 9:16, 21:9, original. The custom ratio (index 9) is only reachable
         * through the long press menu.
         */
        private val RATIO_CYCLE = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7, 8)

        /** Sentinel used by the orientation menu to mean "follow the device sensor". */
        private const val ORIENTATION_AUTO = -1

        /** Accent used to flag an active quick action, matching the speed label colour. */
        private val ACTIVE_TINT = Color.rgb(0xFF, 0x8C, 0x00)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val root: View = player.findViewById<ViewStubCompat>(R.id.mx_player_hud_stub).inflate()

    private val topBar: View = root.findViewById(R.id.mx_top_bar)
    private val topActions: View = root.findViewById(R.id.mx_top_actions)
    private val bottomBar: View = root.findViewById(R.id.mx_bottom_bar)
    private val titleView: TextView = root.findViewById(R.id.mx_title)
    private val timeView: TextView = root.findViewById(R.id.mx_time)
    private val lengthView: TextView = root.findViewById(R.id.mx_length)
    private val speedView: TextView = root.findViewById(R.id.mx_speed)
    private val repeatView: ImageView = root.findViewById(R.id.mx_repeat)
    private val shuffleView: ImageView = root.findViewById(R.id.mx_shuffle)
    private val prevButton: ImageView = root.findViewById(R.id.mx_prev)
    private val nextButton: ImageView = root.findViewById(R.id.mx_next)
    private val rotateButton: View = root.findViewById(R.id.mx_rotate)
    private val seekBar: SeekBar = root.findViewById(R.id.mx_seekbar)
    private val playButton: ImageView = root.findViewById(R.id.mx_play)
    private val rewindLabel: TextView = root.findViewById(R.id.mx_rewind_label)
    private val forwardLabel: TextView = root.findViewById(R.id.mx_forward_label)

    private val bars by lazy(LazyThreadSafetyMode.NONE) { arrayOf(topBar, topActions, bottomBar) }
    private var mxEnabled = true
    private var tracking = false
    private var lastCustomRatio = "9:16"
    /** Step used by the rewind / forward buttons, kept in sync with the stock jump delay. */
    private var jumpDelay = 10
    private var repeatMode = PlaybackStateCompat.REPEAT_MODE_NONE
    private var shuffling = false
    private var ratioIndex = 0

    private val hideRunnable = Runnable { hide() }
    private val ticker = object : Runnable {
        override fun run() {
            if (root.windowToken == null) return
            updateProgress()
            root.postDelayed(this, 500L)
        }
    }

    init {
        seekBar.max = 1000
        titleView.text = mediaTitle()
        jumpDelay = Settings.videoJumpDelay
        refreshJumpLabels()
        bindButtons()
        root.postDelayed(ticker, 500L)
        // The action row is anchored under the title bar; re-fit it when the layout
        // (rotation, split screen, PiP) changes size.
        root.addOnLayoutChangeListener { _, _, _, _, bottom, _, _, _, oldBottom ->
            if (bottom != oldBottom) layoutTopActions()
        }
    }

    /**
     * Anchors the quick action row right under the title bar. Every MX button lives in that one
     * horizontal row now, so all this has to follow is the title bar height.
     */
    private fun layoutTopActions() {
        if (root.height <= 0) return
        val density = player.resources.displayMetrics.density
        val gap = (4 * density).toInt()
        val titleHeight = if (topBar.height > 0) topBar.height else (56 * density).toInt()
        val params = topActions.layoutParams as FrameLayout.LayoutParams
        val target = titleHeight + gap
        if (params.topMargin != target) {
            params.topMargin = target
            topActions.layoutParams = params
        }
    }

    /** The toolbar shows the media name, not the app label. */
    private fun mediaTitle(): CharSequence =
            player.service?.currentMediaWrapper?.title ?: player.title

    private fun bindButtons() {
        root.findViewById<View>(R.id.mx_back).setOnClickListener { player.finish() }
        root.findViewById<View>(R.id.mx_play).setOnClickListener { togglePlayPause() }
        prevButton.setOnClickListener { playPrevious() }
        nextButton.setOnClickListener { playNext() }
        // Same behaviour as the stock HUD buttons: tap = video jump delay, long press = long jump delay.
        root.findViewById<View>(R.id.mx_rewind).apply {
            setOnClickListener { seekBy(-jumpDelay) }
            setOnLongClickListener {
                seekBy(-Settings.videoLongJumpDelay)
                true
            }
        }
        root.findViewById<View>(R.id.mx_forward).apply {
            setOnClickListener { seekBy(jumpDelay) }
            setOnLongClickListener {
                seekBy(Settings.videoLongJumpDelay)
                true
            }
        }
        // "Go to time": the stock dialog behind the player options menu, promoted to a button.
        root.findViewById<View>(R.id.mx_jumpto).setOnClickListener {
            hide()
            showJumpToTime()
        }
        // Single tap cycles the presets (same idea as the stock VLC ratio toggle),
        // long press opens the full list including the custom ratio entry.
        root.findViewById<View>(R.id.mx_ratio).apply {
            setOnClickListener { cycleRatio() }
            setOnLongClickListener {
                showRatioMenu()
                true
            }
            contentDescription = player.getString(R.string.mx_ratio_long_press)
        }
        // Single tap rotates 90 degrees, long press picks an explicit orientation.
        rotateButton.apply {
            setOnClickListener { rotateScreen() }
            setOnLongClickListener {
                showOrientationMenu()
                true
            }
        }
        root.findViewById<View>(R.id.mx_repeat).setOnClickListener { cycleRepeatMode() }
        shuffleView.setOnClickListener { toggleShuffle() }
        root.findViewById<View>(R.id.mx_tracks).setOnClickListener {
            hide(true)
            player.overlayDelegate.showTracks()
        }
        root.findViewById<View>(R.id.mx_lock).setOnClickListener { player.mxGestureDelegate.setLocked(true) }
        speedView.setOnClickListener { cycleSpeed() }
        speedView.setOnLongClickListener {
            showSpeedMenu()
            true
        }
        // Popup (PiP / floating window) and the stock options sheet are still reachable in MX mode.
        root.findViewById<View>(R.id.mx_popup).setOnClickListener { switchToPopup() }
        root.findViewById<View>(R.id.mx_more).setOnClickListener {
            hide(true)
            player.showAdvancedOptions()
        }

        seekBar.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(bar: SeekBar?, progress: Int, fromUser: Boolean) {
                if (!fromUser) return
                val length = player.service?.length ?: return
                timeView.text = formatTime(length * progress / 1000L)
            }

            override fun onStartTrackingTouch(bar: SeekBar?) {
                tracking = true
                handler.removeCallbacks(hideRunnable)
            }

            override fun onStopTrackingTouch(bar: SeekBar?) {
                tracking = false
                val length = player.service?.length ?: return
                val progress = bar?.progress ?: return
                if (length > 0L) player.seek(length * progress / 1000L, true)
                show()
            }
        })
    }

    private fun togglePlayPause() {
        val service = player.service ?: return
        if (service.isPlaying) service.pause() else player.play()
        updateProgress()
        show()
    }

    /** Single tap on the speed button: step through the short list of common speeds. */
    private fun cycleSpeed() {
        val current = player.service?.rate ?: 1f
        val position = QUICK_SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        val next = if (position >= 0) QUICK_SPEEDS[(position + 1) % QUICK_SPEEDS.size]
        else QUICK_SPEEDS.firstOrNull { it > current } ?: QUICK_SPEEDS[0]
        applyRate(next)
    }

    /** MX Player style speed sheet: pick a rate from 0.25x to 4x. */
    private fun showSpeedMenu() {
        show()
        val current = player.service?.rate ?: 1f
        val checked = SPEEDS.indexOfFirst { abs(it - current) < 0.01f }
        val labels = SPEEDS.map { formatRate(it) }.toTypedArray()
        AlertDialog.Builder(player)
                .setTitle(R.string.mx_speed_pick)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    applyRate(SPEEDS[which])
                    dialog.dismiss()
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun applyRate(rate: Float) {
        player.service?.setRate(rate, true)
        speedView.text = formatRate(rate)
        player.mxGestureDelegate.showHint(R.drawable.ic_speed, formatRate(rate))
        show()
    }

    private fun formatRate(rate: Float): String {
        // 1.0x / 1.5x / 1.25x / 4.0x: two decimals, trailing zeros trimmed but keep one decimal.
        val text = String.format("%.2f", rate).trimEnd('0')
        return if (text.endsWith('.')) "${text}0x" else "${text}x"
    }

    /** Repeat off -> repeat one -> repeat all -> off, mirrored from the stock repeat option. */
    private fun cycleRepeatMode() {
        val service = player.service ?: return
        val next = when (service.repeatType) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> PlaybackStateCompat.REPEAT_MODE_ALL
            PlaybackStateCompat.REPEAT_MODE_ALL -> PlaybackStateCompat.REPEAT_MODE_NONE
            else -> PlaybackStateCompat.REPEAT_MODE_ONE
        }
        service.repeatType = next
        repeatMode = next
        updateRepeatIcon(next)
        val label = when (next) {
            PlaybackStateCompat.REPEAT_MODE_ONE -> player.getString(R.string.repeat_single)
            PlaybackStateCompat.REPEAT_MODE_ALL -> player.getString(R.string.repeat_all)
            else -> player.getString(R.string.repeat_none)
        }
        player.mxGestureDelegate.showHint(repeatIcon(next), label)
        show()
    }

    private fun repeatIcon(mode: Int) = when (mode) {
        PlaybackStateCompat.REPEAT_MODE_ONE -> R.drawable.ic_repeat_one
        PlaybackStateCompat.REPEAT_MODE_ALL -> R.drawable.ic_repeat_all
        else -> R.drawable.ic_repeat
    }

    private fun updateRepeatIcon(mode: Int) {
        repeatView.setImageDrawable(ContextCompat.getDrawable(player, repeatIcon(mode)))
        repeatView.contentDescription = player.getString(R.string.mx_repeat)
    }

    /**
     * Toggle playlist shuffle, the same switch the stock "shuffle" player option drives.
     * Shuffling needs at least three items, so the button is dimmed when it cannot be used.
     */
    private fun toggleShuffle() {
        val service = player.service ?: return
        if (!service.canShuffle()) {
            player.mxGestureDelegate.showHint(R.drawable.ic_player_shuffle,
                    player.getString(R.string.mx_shuffle_unavailable))
            show()
            return
        }
        service.shuffle()
        shuffling = service.isShuffling
        updateShuffleIcon(shuffling)
        val label = player.getString(if (shuffling) R.string.shuffle_on else R.string.shuffle)
        player.mxGestureDelegate.showHint(R.drawable.ic_player_shuffle, label)
        show()
    }

    private fun updateShuffleIcon(on: Boolean) {
        // Active state is flagged with the orange accent, the icon itself stays the same.
        shuffleView.imageTintList = ColorStateList.valueOf(if (on) ACTIVE_TINT else Color.WHITE)
        shuffleView.contentDescription = player.getString(R.string.mx_shuffle)
    }

    /**
     * Same as the stock "Play as popup" entry: playback moves to the resizable floating window
     * and the launcher comes back to the foreground. Without the last step the overlay is drawn
     * on top of our own activity, which looks like an in-app mini player instead of a popup.
     */
    private fun switchToPopup() {
        hide(true)
        player.switchToPopup(forceLegacyPopup = true)
        val startMain = Intent(Intent.ACTION_MAIN)
        startMain.addCategory(Intent.CATEGORY_HOME)
        startMain.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        player.startActivity(startMain)
    }

    /** Previous item of the playlist, with a hint when there is none. */
    private fun playPrevious() {
        val service = player.service ?: return
        if (!service.hasPrevious()) {
            player.mxGestureDelegate.showHint(R.drawable.ic_previous, player.getString(R.string.mx_no_previous))
            show()
            return
        }
        service.previous(false)
        show()
    }

    /** Next item of the playlist, with a hint when there is none. */
    private fun playNext() {
        val service = player.service ?: return
        if (!service.hasNext()) {
            player.mxGestureDelegate.showHint(R.drawable.ic_next, player.getString(R.string.mx_no_next))
            show()
            return
        }
        service.next()
        show()
    }

    private fun seekBy(seconds: Int) {
        val service = player.service ?: return
        val length = service.length
        if (length <= 0L) return
        val target = (player.time + seconds * 1000L).coerceIn(0L, length)
        player.seek(target, true)
        updateProgress()
        show()
    }

    /** Stock "go to time" dialog, the one the player options sheet exposes. */
    private fun showJumpToTime() {
        JumpToTimeDialog.newInstance().show(player.supportFragmentManager, "mx_time")
    }

    private fun updateProgress() {
        val service = player.service ?: return
        val title = mediaTitle().toString()
        if (titleView.text.toString() != title) titleView.text = title
        val rateLabel = formatRate(service.rate)
        if (speedView.text.toString() != rateLabel) speedView.text = rateLabel
        if (service.repeatType != repeatMode) {
            repeatMode = service.repeatType
            updateRepeatIcon(repeatMode)
        }
        // Shuffle can also be flipped from the stock options sheet, keep the icon in sync.
        if (service.isShuffling != shuffling) {
            shuffling = service.isShuffling
            updateShuffleIcon(shuffling)
        }
        // The stock jump delay can be changed in the settings while this screen is alive.
        if (jumpDelay != Settings.videoJumpDelay) {
            jumpDelay = Settings.videoJumpDelay
            refreshJumpLabels()
        }
        val length = service.length
        val position = player.time
        timeView.text = formatTime(position)
        lengthView.text = formatTime(length)
        if (!tracking && length > 0L) {
            seekBar.progress = ((position * 1000L) / length).toInt().coerceIn(0, 1000)
        }
        val icon = if (service.isPlaying) R.drawable.ic_pause_player else R.drawable.ic_play_player
        playButton.setImageDrawable(ContextCompat.getDrawable(player, icon))
        // Dim the playlist buttons when there is nothing to jump to.
        prevButton.alpha = if (service.hasPrevious()) 1f else 0.35f
        nextButton.alpha = if (service.hasNext()) 1f else 0.35f
        shuffleView.alpha = if (service.canShuffle()) 1f else 0.35f
    }

    fun show() {
        if (!mxEnabled) return
        // The player is a small PiP / popup window: no room for the full MX chrome.
        if (player.service?.isInPiPMode?.value == true) return
        // Make sure the stock VLC controls never stay visible underneath this layer.
        // Guarded by isShowing so the overlay delegate is not touched before it is used.
        if (player.isShowing) player.overlayDelegate.dismissForMxMode()
        handler.removeCallbacks(hideRunnable)
        bars.forEach { bar ->
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(150L).start()
        }
        updateProgress()
        // First show: the title bar was GONE, so its height is only known after this pass.
        root.post { layoutTopActions() }
        val delay = Settings.videoHudDelay * 1000L
        if (delay > 0L) handler.postDelayed(hideRunnable, delay)
    }

    fun hide(immediate: Boolean = false) {
        handler.removeCallbacks(hideRunnable)
        bars.forEach { bar ->
            if (immediate) {
                bar.alpha = 0f
                bar.visibility = View.GONE
            } else {
                bar.animate().alpha(0f).setDuration(150L).withEndAction {
                    bar.visibility = View.GONE
                }.start()
            }
        }
    }

    fun isVisible() = topBar.visibility == View.VISIBLE

    fun toggle() {
        if (isVisible()) hide() else show()
    }

    fun setMxEnabled(enabled: Boolean) {
        mxEnabled = enabled
        if (!enabled) hide(true)
    }

    /** Keeps the bottom bar labels in sync with the stock jump delay. */
    private fun refreshJumpLabels() {
        rewindLabel.text = jumpDelay.toString()
        forwardLabel.text = jumpDelay.toString()
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        root.removeCallbacks(ticker)
    }

    /**
     * Aspect ratio picker.
     *
     * Portrait ratios such as 9:16 are not part of libVLC's [MediaPlayer.ScaleType] enum, so fixed
     * ratios are applied through the aspect-ratio property combined with a fit-to-screen scaling.
     */
    private fun showRatioMenu() {
        show()
        AlertDialog.Builder(player)
                .setTitle(R.string.aspect_ratio)
                .setSingleChoiceItems(ratioLabels(), ratioIndex) { dialog, which ->
                    dialog.dismiss()
                    ratioIndex = which
                    applyRatioOption(which)
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /** Single tap on the ratio button: move to the next preset and show it as a hint. */
    private fun cycleRatio() {
        val position = RATIO_CYCLE.indexOf(ratioIndex)
        val next = RATIO_CYCLE[(position + 1) % RATIO_CYCLE.size]
        ratioIndex = next
        applyRatioOption(next)
    }

    private fun ratioLabels(): Array<String> = arrayOf(
            player.getString(R.string.mx_ratio_auto),
            player.getString(R.string.mx_ratio_fit),
            player.getString(R.string.mx_ratio_fill),
            "16:9", "4:3", "1:1", "9:16", "21:9",
            player.getString(R.string.mx_ratio_original),
            player.getString(R.string.mx_ratio_custom))

    private fun applyRatioOption(index: Int) {
        when (index) {
            0 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_BEST_FIT, null, player.getString(R.string.mx_ratio_auto))
            1 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, null, player.getString(R.string.mx_ratio_fit))
            2 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FILL, null, player.getString(R.string.mx_ratio_fill))
            3 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, "16:9", "16:9")
            4 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, "4:3", "4:3")
            5 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, "1:1", "1:1")
            6 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, "9:16", "9:16")
            7 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, "21:9", "21:9")
            8 -> setVideoRatio(MediaPlayer.ScaleType.SURFACE_ORIGINAL, null, player.getString(R.string.mx_ratio_original))
            else -> askCustomRatio()
        }
    }

    private fun setVideoRatio(scale: MediaPlayer.ScaleType, aspect: String?, label: String) {
        val service = player.service ?: return
        service.playlistManager.player.setVideoAspectRatio(aspect)
        service.mediaplayer.videoScale = scale
        player.mxGestureDelegate.showHint(R.drawable.ic_player_ratio, label)
        show()
    }

    private fun askCustomRatio() {
        val input = AppCompatEditText(player).apply {
            inputType = InputType.TYPE_CLASS_TEXT
            setSingleLine(true)
            hint = player.getString(R.string.mx_ratio_custom_hint)
            setText(lastCustomRatio)
            setSelection(lastCustomRatio.length)
        }
        val padding = (20 * player.resources.displayMetrics.density).toInt()
        val container = FrameLayout(player).apply {
            setPadding(padding, padding / 2, padding, 0)
            addView(input)
        }
        AlertDialog.Builder(player)
                .setTitle(R.string.mx_ratio_custom)
                .setMessage(R.string.mx_ratio_custom_message)
                .setView(container)
                .setPositiveButton(android.R.string.ok) { _, _ ->
                    val value = normalizeAspect(input.text?.toString().orEmpty())
                    if (value == null) {
                        player.mxGestureDelegate.showHint(R.drawable.ic_player_ratio,
                                player.getString(R.string.mx_ratio_invalid))
                    } else {
                        lastCustomRatio = value
                        setVideoRatio(MediaPlayer.ScaleType.SURFACE_FIT_SCREEN, value,
                                player.getString(R.string.mx_ratio_applied, value))
                    }
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    /** Accepts `w:h`, `w/h` and the full width colon, and returns it in the `w:h` form libVLC expects. */
    private fun normalizeAspect(raw: String): String? {
        val text = raw.trim().replace('：', ':').replace('/', ':')
        val parts = text.split(':')
        if (parts.size != 2) return null
        val width = parts[0].trim().toFloatOrNull() ?: return null
        val height = parts[1].trim().toFloatOrNull() ?: return null
        if (width <= 0f || height <= 0f) return null
        return "${parts[0].trim()}:${parts[1].trim()}"
    }

    /** Long press on the rotate button: pin portrait or landscape, or follow the device again. */
    private fun showOrientationMenu() {
        show()
        // Portrait / landscape use the sensor flavours: whichever way the phone is held is the
        // "right way up", so there is never an upside-down lock to escape from.
        val values = intArrayOf(
                ORIENTATION_AUTO,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT,
                ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE)
        val labels = arrayOf(
                player.getString(R.string.mx_rotation_auto),
                player.getString(R.string.mx_rotation_portrait),
                player.getString(R.string.mx_rotation_landscape))
        val checked = if (!player.orientationMode.locked) 0
        else values.indexOf(player.requestedOrientation).takeIf { it >= 0 } ?: 1
        AlertDialog.Builder(player)
                .setTitle(R.string.mx_orientation_menu)
                .setSingleChoiceItems(labels, checked) { dialog, which ->
                    dialog.dismiss()
                    applyOrientation(values[which], labels[which])
                }
                .setNegativeButton(android.R.string.cancel, null)
                .show()
    }

    private fun applyOrientation(value: Int, label: String) {
        if (value == ORIENTATION_AUTO) {
            // Release the lock so the activity follows the device again.
            player.orientationMode.locked = false
            player.requestedOrientation = player.getScreenOrientation(player.orientationMode)
        } else {
            player.setOrientation(value)
        }
        player.mxGestureDelegate.showHint(R.drawable.ic_player_rotate, label)
        show()
    }

    /** Rotate the activity between portrait and landscape, like MX Player's rotate button. */
    private fun rotateScreen() {
        val portrait = player.resources.configuration.orientation == Configuration.ORIENTATION_PORTRAIT
        // Sensor flavours: the side the phone is held on decides which way "landscape" faces.
        val next = if (portrait) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        else ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        player.setOrientation(next)
        val label = player.getString(if (portrait) R.string.mx_rotation_landscape else R.string.mx_rotation_portrait)
        player.mxGestureDelegate.showHint(R.drawable.ic_player_rotate, label)
        show()
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

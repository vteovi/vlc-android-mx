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

import android.content.pm.ActivityInfo
import android.os.Handler
import android.os.Looper
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

/**
 * MX-style HUD: top title bar, right vertical quick actions, bottom timeline.
 *
 * This delegate is intentionally independent from [VideoPlayerOverlayDelegate]:
 * the stock VLC HUD relies on data binding that is only initialised when the
 * stock gesture delegate calls showOverlay(). Building a separate layer keeps
 * the two skins from fighting over the same views.
 */
class MxControlsDelegate(private val player: VideoPlayerActivity) {

    private val handler = Handler(Looper.getMainLooper())
    private val root: View = player.findViewById<ViewStubCompat>(R.id.mx_player_hud_stub).inflate()

    private val topBar: View = root.findViewById(R.id.mx_top_bar)
    private val bottomBar: View = root.findViewById(R.id.mx_bottom_bar)
    private val sideBar: View = root.findViewById(R.id.mx_side_bar)
    private val titleView: TextView = root.findViewById(R.id.mx_title)
    private val timeView: TextView = root.findViewById(R.id.mx_time)
    private val lengthView: TextView = root.findViewById(R.id.mx_length)
    private val speedView: TextView = root.findViewById(R.id.mx_speed)
    private val seekBar: SeekBar = root.findViewById(R.id.mx_seekbar)
    private val playButton: ImageView = root.findViewById(R.id.mx_play)

    private val bars by lazy(LazyThreadSafetyMode.NONE) { arrayOf(topBar, bottomBar, sideBar) }
    private val speeds = floatArrayOf(1f, 1.25f, 1.5f, 2f)
    private var speedIndex = 0
    private var mxEnabled = true
    private var tracking = false
    private var lastCustomRatio = "9:16"

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
        bindButtons()
        root.postDelayed(ticker, 500L)
    }

    /** The toolbar shows the media name, not the app label. */
    private fun mediaTitle(): CharSequence =
            player.service?.currentMediaWrapper?.title ?: player.title

    private fun bindButtons() {
        root.findViewById<View>(R.id.mx_back).setOnClickListener { player.finish() }
        root.findViewById<View>(R.id.mx_play).setOnClickListener { togglePlayPause() }
        root.findViewById<View>(R.id.mx_rewind).setOnClickListener { seekBy(-10) }
        root.findViewById<View>(R.id.mx_forward).setOnClickListener { seekBy(10) }
        root.findViewById<View>(R.id.mx_ratio).setOnClickListener { showRatioMenu() }
        root.findViewById<View>(R.id.mx_rotate).setOnClickListener { rotateScreen() }
        root.findViewById<View>(R.id.mx_tracks).setOnClickListener { player.overlayDelegate.showTracks(); show() }
        root.findViewById<View>(R.id.mx_lock).setOnClickListener { player.mxGestureDelegate.setLocked(true) }
        speedView.setOnClickListener { cycleSpeed() }
        root.findViewById<View>(R.id.mx_more).setOnClickListener { player.overlayDelegate.showTracks(); show() }

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

    private fun cycleSpeed() {
        speedIndex = (speedIndex + 1) % speeds.size
        val rate = speeds[speedIndex]
        player.service?.setRate(rate, false)
        speedView.text = player.getString(R.string.mx_speed_value, rate)
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

    private fun updateProgress() {
        val service = player.service ?: return
        val title = mediaTitle().toString()
        if (titleView.text.toString() != title) titleView.text = title
        val length = service.length
        val position = player.time
        timeView.text = formatTime(position)
        lengthView.text = formatTime(length)
        if (!tracking && length > 0L) {
            seekBar.progress = ((position * 1000L) / length).toInt().coerceIn(0, 1000)
        }
        val icon = if (service.isPlaying) R.drawable.ic_pause_player else R.drawable.ic_play_player
        playButton.setImageDrawable(ContextCompat.getDrawable(player, icon))
    }

    fun show() {
        if (!mxEnabled) return
        // Make sure the stock VLC controls never stay visible underneath this layer.
        // Guarded by isShowing so the overlay delegate is not touched before it is used.
        if (player.isShowing) player.overlayDelegate.dismissForMxMode()
        handler.removeCallbacks(hideRunnable)
        bars.forEach { bar ->
            bar.visibility = View.VISIBLE
            bar.animate().alpha(1f).setDuration(150L).start()
        }
        updateProgress()
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
        val labels = arrayOf(
                player.getString(R.string.mx_ratio_auto),
                player.getString(R.string.mx_ratio_fit),
                player.getString(R.string.mx_ratio_fill),
                "16:9", "4:3", "1:1", "9:16", "21:9",
                player.getString(R.string.mx_ratio_original),
                player.getString(R.string.mx_ratio_custom))
        AlertDialog.Builder(player)
                .setTitle(R.string.aspect_ratio)
                .setItems(labels) { _, which -> applyRatioOption(which) }
                .show()
    }

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

    /** Rotate the activity by 90 degrees on every tap, like MX Player's rotate button. */
    private fun rotateScreen() {
        val next = when (player.orientationMode.orientation) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_REVERSE_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE -> ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT
            else -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
        player.setOrientation(next)
        val label = when (next) {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, ActivityInfo.SCREEN_ORIENTATION_REVERSE_PORTRAIT ->
                player.getString(R.string.mx_rotation_portrait)
            else -> player.getString(R.string.mx_rotation_landscape)
        }
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

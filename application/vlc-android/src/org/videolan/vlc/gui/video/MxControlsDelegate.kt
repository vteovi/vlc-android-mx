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

import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.ImageView
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.widget.ViewStubCompat
import androidx.core.content.ContextCompat
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
        titleView.text = player.title
        bindButtons()
        root.postDelayed(ticker, 500L)
    }

    private fun bindButtons() {
        root.findViewById<View>(R.id.mx_back).setOnClickListener { player.finish() }
        root.findViewById<View>(R.id.mx_play).setOnClickListener { togglePlayPause() }
        root.findViewById<View>(R.id.mx_rewind).setOnClickListener { seekBy(-10) }
        root.findViewById<View>(R.id.mx_forward).setOnClickListener { seekBy(10) }
        root.findViewById<View>(R.id.mx_ratio).setOnClickListener { player.resizeVideo(); show() }
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

    fun toggle() {
        if (topBar.visibility == View.VISIBLE) hide() else show()
    }

    fun setMxEnabled(enabled: Boolean) {
        mxEnabled = enabled
        if (!enabled) hide(true)
    }

    fun release() {
        handler.removeCallbacksAndMessages(null)
        root.removeCallbacks(ticker)
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

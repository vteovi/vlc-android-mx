/*
 * ************************************************************************
 *  PopupView.java
 * *************************************************************************
 *  Copyright © 2016 VLC authors and VideoLAN
 *  Author: Geoffrey Métais
 *
 *  This program is free software; you can redistribute it and/or modify
 *  it under the terms of the GNU General Public License as published by
 *  the Free Software Foundation; either version 2 of the License, or
 *  (at your option) any later version.
 *
 *  This program is distributed in the hope that it will be useful,
 *  but WITHOUT ANY WARRANTY; without even the implied warranty of
 *  MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 *  GNU General Public License for more details.
 *
 *  You should have received a copy of the GNU General Public License
 *  along with this program; if not, write to the Free Software
 *  Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *
 *  *************************************************************************
 */

package org.videolan.vlc.gui.view

import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Point
import android.util.AttributeSet
import android.util.DisplayMetrics
import android.view.*
import androidx.constraintlayout.widget.ConstraintLayout
import androidx.core.content.getSystemService
import androidx.core.view.GestureDetectorCompat
import org.videolan.libvlc.interfaces.IVLCVout
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.tools.CUSTOM_POPUP_ASPECT
import org.videolan.tools.CUSTOM_POPUP_HEIGHT
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.R

class PopupLayout : ConstraintLayout, ScaleGestureDetector.OnScaleGestureListener, View.OnTouchListener {

    private lateinit var screenSize: DisplayMetrics
    private var vlcVout: IVLCVout? = null
    private var windowManager: WindowManager? = null
    private var gestureDetector: GestureDetectorCompat? = null
    private var scaleGestureDetector: ScaleGestureDetector? = null
    private var scaleFactor = 1.0
    private var popupWidth: Int = 0
    private var popupHeight: Int = 0
    private var screenWidth: Int = 0
    private var screenHeight: Int = 0
    /** Width / height of the popup window. Pinch zoom and the video layout both keep it. */
    private var windowAspect = DEFAULT_ASPECT
    private var pinchStartWidth: Int = 0
    private var scaling = false

    private var initialX: Int = 0
    private var initialY: Int = 0
    private var initialTouchX: Float = 0.toFloat()
    private var initialTouchY: Float = 0.toFloat()
    private lateinit var mLayoutParams: WindowManager.LayoutParams

    constructor(context: Context) : super(context) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet) : super(context, attrs) {
        init(context)
    }

    constructor(context: Context, attrs: AttributeSet, defStyleAttr: Int) : super(context, attrs, defStyleAttr) {
        init(context)
    }

    fun setVLCVOut(vout: IVLCVout) {
        vlcVout = vout
        vlcVout!!.setWindowSize(popupWidth, popupHeight)
    }

    /*
     * Remove layout from window manager
     */
    fun close() {
        keepScreenOn = false
        windowManager!!.removeView(this)
        windowManager = null
        vlcVout = null
    }

    fun setGestureDetector(gdc: GestureDetectorCompat) {
        gestureDetector = gdc
    }

    /*
     * Update layout dimensions and apply layout params to window manager
     */
    fun setViewSize(requestedWidth: Int, requestedHeight: Int) {
        if (requestedWidth <= 0 || requestedHeight <= 0) return
        if (!::mLayoutParams.isInitialized) return
        val wm = windowManager ?: return
        if (screenWidth == 0 || screenHeight == 0) updateWindowSize()
        val size = fitOnScreen(requestedWidth, requestedHeight)
        popupWidth = size.x
        popupHeight = size.y
        if (popupHeight > 0) windowAspect = popupWidth.toFloat() / popupHeight
        containInScreen(popupWidth, popupHeight)
        mLayoutParams.width = popupWidth
        mLayoutParams.height = popupHeight
        wm.updateViewLayout(this, mLayoutParams)
        // The vout has to follow the size that is really applied, otherwise the rendered video
        // keeps the previous (unclipped) size and the picture looks off-centre.
        vlcVout?.setWindowSize(popupWidth, popupHeight)
    }

    /** Fits the request on screen and clamps it to the minimum size, always keeping the ratio. */
    private fun fitOnScreen(width: Int, height: Int): Point {
        var w = width.coerceAtLeast(1)
        var h = height.coerceAtLeast(1)
        if (screenWidth > 0 && screenHeight > 0) {
            val fit = minOf(1.0, screenWidth.toDouble() / w, screenHeight.toDouble() / h)
            if (fit < 1.0) {
                w = (w * fit).toInt().coerceAtLeast(1)
                h = (h * fit).toInt().coerceAtLeast(1)
            }
        }
        val minHeight = (MIN_POPUP_HEIGHT_DP * resources.displayMetrics.density).toInt()
        if (h < minHeight) {
            h = minHeight
            w = (h * windowAspect).toInt().coerceAtLeast(1)
        }
        return Point(w, h)
    }

    private fun init(context: Context) {
        windowManager = context.applicationContext.getSystemService()
        screenSize = DisplayMetrics().also { windowManager!!.defaultDisplay.getMetrics(it) }
        updateWindowSize()
        popupWidth = context.resources.getDimensionPixelSize(R.dimen.video_pip_width)
        popupHeight = context.resources.getDimensionPixelSize(R.dimen.video_pip_height)
        windowAspect = popupWidth.toFloat() / popupHeight.toFloat()
        val settings = Settings.getInstance(context)
        // Restore the ratio the user zoomed the last popup to. Deriving the width from the
        // 16:9 default instead turned every portrait video back into a landscape window.
        val storedAspect = settings.getFloat(CUSTOM_POPUP_ASPECT, -1f)
        if (storedAspect.isFinite() && storedAspect > 0f) windowAspect = storedAspect
        val customPopupHeight = settings.getInt(CUSTOM_POPUP_HEIGHT, -1)
        if (customPopupHeight != -1) {
            popupHeight = customPopupHeight
            popupWidth = (popupHeight.toFloat() * windowAspect).toInt()
        }
        // A stored size from a bigger screen (or another orientation) must not open off-screen.
        val initial = fitOnScreen(popupWidth, popupHeight)
        popupWidth = initial.x
        popupHeight = initial.y

        vlcVout?.setWindowSize(popupWidth, popupHeight)

        val params = WindowManager.LayoutParams(
                popupWidth,
                popupHeight,
                if (AndroidUtil.isOOrLater) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
                PixelFormat.OPAQUE)

        params.gravity = Gravity.BOTTOM or Gravity.START
        params.x = 50
        params.y = 50
        scaleGestureDetector = ScaleGestureDetector(context, this)
        setOnTouchListener(this)
        windowManager!!.addView(this, params)
        if (!isInEditMode) {
            mLayoutParams = layoutParams as WindowManager.LayoutParams
        }
    }

    private fun updateWindowSize() {
        val size = Point()
        windowManager!!.defaultDisplay.getSize(size)
        screenWidth = size.x
        screenHeight = size.y
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (windowManager == null)
            return false
        if (scaleGestureDetector != null)
            scaleGestureDetector!!.onTouchEvent(event)
        if (gestureDetector != null && gestureDetector!!.onTouchEvent(event))
            return true
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                initialX = mLayoutParams.x
                initialY = mLayoutParams.y
                initialTouchX = event.rawX
                initialTouchY = event.rawY
                updateWindowSize()
                return true
            }
            MotionEvent.ACTION_UP -> return true
            MotionEvent.ACTION_MOVE -> if (scaleGestureDetector == null || !scaleGestureDetector!!.isInProgress) {
                mLayoutParams.x = initialX + (event.rawX - initialTouchX).toInt()
                mLayoutParams.y = initialY - (event.rawY - initialTouchY).toInt()
                containInScreen(mLayoutParams.width, mLayoutParams.height)
                windowManager!!.updateViewLayout(this@PopupLayout, mLayoutParams)
                return true
            }
        }
        return false
    }

    override fun onScale(detector: ScaleGestureDetector): Boolean {
        if (!scaling || pinchStartWidth <= 0) return false
        scaleFactor = (scaleFactor * detector.scaleFactor).coerceIn(MIN_SCALE, MAX_SCALE)
        // Height comes from the locked ratio instead of being scaled separately: two independent
        // factors (fed by view sizes that the video layout may change mid-gesture) drifted a 9:16
        // window towards 16:9.
        val scaledWidth = (pinchStartWidth * scaleFactor).toInt().coerceAtLeast(1)
        val size = fitOnScreen(scaledWidth, (scaledWidth / windowAspect).toInt().coerceAtLeast(1))
        popupWidth = size.x
        popupHeight = size.y
        // Keep the accumulated factor in sync with what really fits, otherwise shrinking back
        // needs a long dead travel once the popup has hit the screen edges.
        scaleFactor = (popupWidth.toDouble() / pinchStartWidth).coerceIn(MIN_SCALE, MAX_SCALE)
        vlcVout?.setWindowSize(popupWidth, popupHeight)
        return true
    }

    override fun onScaleBegin(detector: ScaleGestureDetector): Boolean {
        // Prefer the window params: they are the size that was actually requested, while
        // View.width/height can still hold the previous layout values for one frame.
        val paramsSet = ::mLayoutParams.isInitialized
        pinchStartWidth = when {
            paramsSet && mLayoutParams.width > 0 -> mLayoutParams.width
            width > 0 -> width
            else -> popupWidth
        }
        val startHeight = when {
            paramsSet && mLayoutParams.height > 0 -> mLayoutParams.height
            height > 0 -> height
            else -> popupHeight
        }
        if (pinchStartWidth <= 0 || startHeight <= 0) return false
        // Lock the ratio the popup currently shows, so zooming a portrait window stays portrait.
        windowAspect = pinchStartWidth.toFloat() / startHeight
        scaleFactor = 1.0
        scaling = true
        return true
    }

    override fun onScaleEnd(detector: ScaleGestureDetector) {
        scaling = false
        // setViewSize round-trips the size through integers, so keep the ratio the gesture locked
        // instead of letting rounding creep the window back towards the default 16:9.
        val lockedAspect = windowAspect
        setViewSize(popupWidth, popupHeight)
        windowAspect = lockedAspect
        Settings.getInstance(context).apply {
            putSingle(CUSTOM_POPUP_HEIGHT, popupHeight)
            putSingle(CUSTOM_POPUP_ASPECT, windowAspect)
        }
        scaleFactor = 1.0
    }

    /** True while a pinch is in progress; the video layout must not resize the window then. */
    val isScaling: Boolean get() = scaling

    private fun containInScreen(width: Int, height: Int) {
        mLayoutParams.x = mLayoutParams.x.coerceAtLeast(0)
        mLayoutParams.y = mLayoutParams.y.coerceAtLeast(0)
        if (mLayoutParams.x + width > screenWidth)
            mLayoutParams.x = screenWidth - width
        if (mLayoutParams.y + height > screenHeight)
            mLayoutParams.y = screenHeight - height
    }

    companion object {
        private const val TAG = "VLC/PopupView"

        /** 240dp x 135dp, the default popup size, kept for the first launch. */
        private const val DEFAULT_ASPECT = 16f / 9f
        private const val MIN_SCALE = 0.2
        private const val MAX_SCALE = 8.0
        private const val MIN_POPUP_HEIGHT_DP = 60
    }
}

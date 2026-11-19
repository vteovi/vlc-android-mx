/*****************************************************************************
 * SystemBarsInsets.kt
 *
 * Copyright © 2026 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License as published by the Free
 * Software Foundation; either version 2 of the License, or (at your option)
 * any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or FITNESS
 * FOR A PARTICULAR PURPOSE. See the GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along with
 * this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.gui.helpers

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Resources
import android.os.Build
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnLayout

private const val TAG = "VLC/SystemBarsInsets"

/** Used when the platform does not expose any status bar height at all. */
private const val FALLBACK_STATUS_BAR_DP = 24

/**
 * Status bar height as declared by the platform resources.
 *
 * This is the value the framework uses to size the status bar window itself, so it stays correct
 * even on ROMs that report a wrong value through [WindowInsetsCompat].
 */
private fun Resources.statusBarHeight(): Int {
    val id = getIdentifier("status_bar_height", "dimen", "android")
    val height = if (id > 0) getDimensionPixelSize(id) else 0
    return if (height > 0) height else (FALLBACK_STATUS_BAR_DP * displayMetrics.density).toInt()
}

private fun View.findActivity(): Activity? {
    var context = this.context
    while (context is ContextWrapper) {
        if (context is Activity) return context
        context = context.baseContext
    }
    return null
}

/** Whether the status bar is currently hidden, in which case there is nothing to stay below. */
private fun View.isStatusBarHidden(): Boolean {
    if ((systemUiVisibility and View.SYSTEM_UI_FLAG_FULLSCREEN) != 0) return true
    val flags = findActivity()?.window?.attributes?.flags ?: 0
    if ((flags and WindowManager.LayoutParams.FLAG_FULLSCREEN) != 0) return true
    // From R on, visibility is reported accurately even when the inset itself is zeroed out.
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        val rootInsets = ViewCompat.getRootWindowInsets(this) ?: return false
        return !rootInsets.isVisible(WindowInsetsCompat.Type.statusBars())
    }
    return false
}

/**
 * Top inset to keep the content below the status bar.
 *
 * A few ROMs (MIUI/HyperOS builds are the usual suspects) deliver window insets with a zero
 * status bar inset while still drawing the status bar on top of the window, and never send an
 * update afterwards. The whole UI then starts at the very top of the screen: the toolbar slides
 * underneath the status bar and its action buttons (overflow menu, search...) become
 * unreachable, because the system bar window swallows the touch events.
 *
 * In that case fall back to the height the platform declares for the status bar.
 *
 * @param edgeToEdge whether the activity lays its content out behind the system bars
 */
fun View.resolveTopSystemBarInset(reportedTop: Int, edgeToEdge: Boolean): Int {
    if (reportedTop > 0) return reportedTop
    if (!edgeToEdge || isStatusBarHidden()) return reportedTop
    return statusBarInsetOrFallback(reportedTop)
}

/**
 * Same fallback, for callers that know by themselves whether the status bar is drawn over their
 * content. The video player is such a caller: it toggles the system bar with the chrome, so it
 * cannot rely on [isStatusBarHidden] (some ROMs also report the bar as invisible while drawing
 * it, which would swallow the fallback).
 */
fun View.statusBarInsetOrFallback(reportedTop: Int): Int {
    if (reportedTop > 0) return reportedTop
    val height = resources.statusBarHeight()
    Log.i(TAG, "Status bar inset reported as 0 while the bar is drawn, using $height px instead")
    return height
}

/**
 * Height the platform gives the status bar window. Use it as a floor for the reported inset: it is
 * the value the framework sizes the bar with, so a smaller reported inset cannot be the truth.
 */
fun View.declaredStatusBarHeight(): Int = resources.statusBarHeight()

/**
 * Safety net for the same vendor bug: the insets listener may never be called. Once the content
 * is laid out, check its real position and ask for a fresh insets dispatch when it still starts
 * underneath the status bar. Going through a new dispatch keeps the padding/margin logic in the
 * activity's own listener, so nothing can be applied twice.
 */
fun View.ensureTopSystemBarInset(edgeToEdge: Boolean) {
    if (!edgeToEdge || this !is ViewGroup) return
    doOnLayout { view ->
        val content = view as ViewGroup
        view.post {
            val missing = content.missingTopSystemBarInset()
            if (missing <= 0) return@post
            Log.i(TAG, "Content still ${missing}px under the status bar, requesting new insets")
            ViewCompat.requestApplyInsets(view.rootView)
        }
    }
}

/** How many pixels the content still has to move down to clear the status bar. */
private fun ViewGroup.missingTopSystemBarInset(): Int {
    if (isStatusBarHidden()) return 0
    val rootInsets = ViewCompat.getRootWindowInsets(this) ?: return 0
    val reportedTop = rootInsets.getInsets(
            WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
    ).top
    val expectedTop = resolveTopSystemBarInset(reportedTop, true)
    if (expectedTop <= 0) return 0
    val child = getChildAt(0) ?: return 0
    val location = IntArray(2)
    child.getLocationInWindow(location)
    return expectedTop - location[1]
}

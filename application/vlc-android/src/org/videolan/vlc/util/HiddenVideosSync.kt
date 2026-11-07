/*
 * *************************************************************************
 *  HiddenVideosSync.kt
 * **************************************************************************
 *  Copyright © 2026 VLC authors and VideoLAN
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
 *  ***************************************************************************
 */

package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.resources.AndroidDevices
import org.videolan.resources.util.getFromMl
import org.videolan.tools.AppScope
import org.videolan.tools.SHOW_HIDDEN_VIDEOS
import org.videolan.tools.Settings
import java.io.File
import java.util.Locale

/**
 * Exposes the videos stored in hidden folders (name starting with a dot) to the media library.
 *
 * The medialibrary scanner never walks hidden folders, so their videos can't reach the Video tab,
 * whatever the browser settings are. Rather than forcing a rescan, every video found there is
 * registered as an *external media* through Medialibrary.addMedia: it then behaves like any other
 * library item (video list, thumbnail, metadata, play queue) and can be dropped again with
 * Medialibrary.removeExternalMedia when the option is turned off.
 */
object HiddenVideosSync {

    private const val TAG = "VLC/HiddenVideosSync"

    /** How deep we look for hidden folders in the regular tree. */
    private const val SHALLOW_DEPTH = 3

    /** Deeper limit used inside `Android/media` and inside hidden folders themselves. */
    private const val DEEP_DEPTH = 9

    private const val MAX_DIRS = 20000
    private const val MAX_FILES = 3000

    /** Minimum delay between two background syncs. */
    private const val SYNC_INTERVAL = 30 * 60 * 1000L

    private val VIDEO_EXTENSIONS = setOf(
            "3g2", "3gp", "asf", "avi", "divx", "f4v", "flv", "m2ts", "m4v", "mkv", "mov",
            "mp4", "mpe", "mpeg", "mpg", "mts", "mxf", "ogm", "ogv", "rm", "rmvb", "ts",
            "vob", "webm", "wmv")

    @Volatile
    private var lastSync = 0L

    private class WalkEntry(val dir: File, val depth: Int, val limit: Int, val insideHidden: Boolean)

    /**
     * Runs a background sync when the option is enabled and the previous run is old enough to be
     * refreshed. Safe to call from any screen lifecycle callback.
     */
    fun syncIfNeeded(context: Context) {
        if (!Settings.getInstance(context).getBoolean(SHOW_HIDDEN_VIDEOS, false)) return
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL) return
        lastSync = now
        AppScope.launch(Dispatchers.IO) {
            runCatching { apply(context, true) }
                    .onFailure { Log.w(TAG, "Hidden videos sync failed", it) }
        }
    }

    /**
     * Registers ([enable] = true) or drops ([enable] = false) every video of the hidden folders.
     *
     * @return the number of media handled.
     */
    suspend fun apply(context: Context, enable: Boolean): Int = withContext(Dispatchers.IO) {
        val files = try {
            scanHiddenVideos()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to walk the storage", e)
            emptyList()
        }
        if (files.isEmpty()) return@withContext 0
        context.getFromMl {
            var count = 0
            for (file in files) {
                val mrl = Uri.fromFile(file).toString()
                try {
                    if (enable) {
                        if (addMedia(mrl, -1L) != null) count++
                    } else {
                        val media = getMedia(mrl) ?: continue
                        if (media.id > 0 && removeExternalMedia(media.id)) count++
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Cannot handle $mrl", e)
                }
            }
            count
        }
    }

    /** Lists the video files living inside hidden folders of every readable storage volume. */
    private fun scanHiddenVideos(): List<File> {
        val result = ArrayList<File>()
        val queue = ArrayDeque<WalkEntry>()
        for (path in AndroidDevices.externalStorageDirectories) {
            val root = File(path)
            if (root.isDirectory && root.canRead()) queue.add(WalkEntry(root, 0, SHALLOW_DEPTH, false))
        }
        var visitedDirs = 0
        while (queue.isNotEmpty() && result.size < MAX_FILES && visitedDirs < MAX_DIRS) {
            val entry = queue.removeFirst()
            val children = entry.dir.listFiles() ?: continue
            visitedDirs++
            for (child in children) {
                val hidden = child.name.startsWith(".")
                if (child.isDirectory) {
                    if (entry.insideHidden || hidden) {
                        // Inside a hidden folder everything below belongs to us: keep walking with
                        // the deep limit so nested sub folders are covered too.
                        if (entry.depth < DEEP_DEPTH) queue.add(WalkEntry(child, entry.depth + 1, DEEP_DEPTH, true))
                        continue
                    }
                    when (child.name) {
                        // App private data is not worth a walk, except Android/media which holds
                        // the WhatsApp/Telegram style folders (with hidden .Statuses subfolders).
                        "Android" -> queue.add(WalkEntry(File(child, "media"), entry.depth + 1, DEEP_DEPTH, false))
                        "LOST.DIR", ".thumbnails" -> Unit
                        else -> if (entry.depth < entry.limit) {
                            queue.add(WalkEntry(child, entry.depth + 1, entry.limit, false))
                        }
                    }
                } else if (entry.insideHidden && isVideo(child)) {
                    result.add(child)
                    if (result.size >= MAX_FILES) break
                }
            }
        }
        if (result.isNotEmpty()) Log.i(TAG, "${result.size} hidden video(s) found")
        return result
    }

    private fun isVideo(file: File): Boolean {
        val name = file.name
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return VIDEO_EXTENSIONS.contains(name.substring(dot + 1).lowercase(Locale.ROOT))
    }
}

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
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.resources.AndroidDevices
import org.videolan.tools.AppScope
import org.videolan.tools.SHOW_HIDDEN_VIDEOS
import org.videolan.tools.Settings
import org.videolan.tools.putSingle
import org.videolan.vlc.gui.helpers.MedialibraryUtils
import java.io.File
import java.util.Locale

/**
 * Makes the videos stored in hidden folders (name starting with a dot) available in the media
 * library.
 *
 * The scanner never walks hidden folders, so their videos can't reach the Video tab whatever the
 * browser settings are. Registering every single file with Medialibrary.addMedia is not enough:
 * such "external" media are not listed by the Video tab. Instead every hidden folder that holds
 * videos is registered as a library folder (the same mechanism as "add this folder to the media
 * library" in the browser), which gives real library items: type, thumbnail, metadata, grouping.
 *
 * The folders added here are remembered, so they can be dropped again when the option is turned
 * off, and so a rescan only refreshes them instead of walking the whole storage every time.
 */
object HiddenVideosSync {

    private const val TAG = "VLC/HiddenVideosSync"

    /** Preference holding the MRL of every folder we added to the library. */
    private const val REGISTERED_FOLDERS = "hidden_videos_folders"

    /** How deep we look for hidden folders in the regular tree. */
    private const val SHALLOW_DEPTH = 3

    /** Deeper limit used inside `Android/media` and inside hidden folders themselves. */
    private const val DEEP_DEPTH = 9

    private const val MAX_DIRS = 20000

    /** Minimum delay between two background syncs. */
    private const val SYNC_INTERVAL = 30 * 60 * 1000L

    private val VIDEO_EXTENSIONS = setOf(
            "3g2", "3gp", "asf", "avi", "divx", "f4v", "flv", "m2ts", "m4v", "mkv", "mov",
            "mp4", "mpe", "mpeg", "mpg", "mts", "mxf", "ogm", "ogv", "rm", "rmvb", "ts",
            "vob", "webm", "wmv")

    @Volatile
    private var lastSync = 0L

    /** [hiddenRoot] is the outermost hidden folder the entry belongs to. */
    private class WalkEntry(val dir: File, val depth: Int, val limit: Int, val hiddenRoot: File?)

    /**
     * Runs a background sync when the option is enabled and the previous run is old enough to be
     * refreshed. Safe to call from any screen lifecycle callback.
     */
    fun syncIfNeeded(context: Context) {
        if (!Settings.getInstance(context).getBoolean(SHOW_HIDDEN_VIDEOS, false)) return
        val now = System.currentTimeMillis()
        if (now - lastSync < SYNC_INTERVAL) return
        lastSync = now
        val appContext = context.applicationContext
        AppScope.launch(Dispatchers.IO) {
            runCatching { sync(appContext, true) }
                    .onFailure { Log.w(TAG, "Hidden videos sync failed", it) }
        }
    }

    /**
     * Adds ([enable] = true) or drops ([enable] = false) the hidden folders holding videos to the
     * media library.
     *
     * @return the number of folders handled.
     */
    suspend fun sync(context: Context, enable: Boolean): Int = withContext(Dispatchers.IO) {
        val prefs = Settings.getInstance(context)
        if (!enable) {
            val known = prefs.getStringSet(REGISTERED_FOLDERS, emptySet())?.toList().orEmpty()
            if (known.isEmpty()) return@withContext 0
            known.forEach { MedialibraryUtils.removeDir(it) }
            prefs.putSingle(REGISTERED_FOLDERS, emptySet<String>())
            return@withContext known.size
        }
        val medialibrary = Medialibrary.getInstance()
        if (!medialibrary.isInitiated) return@withContext 0
        val folders = try {
            scanHiddenFolders()
        } catch (e: Exception) {
            Log.w(TAG, "Unable to walk the storage", e)
            emptyList()
        }
        if (folders.isEmpty()) return@withContext 0
        // Folders already known by the medialibrary must not be registered twice.
        val inLibrary = medialibrary.foldersList.orEmpty()
                .map { it.trimEnd('/') }
                .toMutableSet()
        val registered = prefs.getStringSet(REGISTERED_FOLDERS, emptySet())
                .orEmpty().toMutableSet()
        var count = 0
        for (folder in folders) {
            val mrl = Tools.mlEncodeMrl(folder.absolutePath).trimEnd('/')
            if (inLibrary.contains(mrl)) {
                registered.add(mrl)
                continue
            }
            try {
                MedialibraryUtils.addDir(mrl, context)
                inLibrary.add(mrl)
                registered.add(mrl)
                count++
            } catch (e: Exception) {
                // Starting the parsing service may be refused (background restrictions).
                Log.w(TAG, "Cannot register $mrl", e)
            }
        }
        prefs.putSingle(REGISTERED_FOLDERS, registered)
        if (count > 0) Log.i(TAG, "$count hidden folder(s) added to the media library")
        count
    }

    /**
     * Lists the hidden folders containing videos on every readable storage volume.
     *
     * [AndroidDevices.externalStorageDirectories] only holds the *secondary* volumes: the primary
     * one (`/storage/emulated/0`) is filtered out on purpose there, so it has to be added back or
     * nothing at all is found on a regular phone.
     */
    private fun scanHiddenFolders(): List<File> {
        val result = LinkedHashSet<File>()
        val queue = ArrayDeque<WalkEntry>()
        storageRoots().forEach { root ->
            if (root.isDirectory && root.canRead()) queue.add(WalkEntry(root, 0, SHALLOW_DEPTH, null))
        }
        var visitedDirs = 0
        while (queue.isNotEmpty() && visitedDirs < MAX_DIRS) {
            val entry = queue.removeFirst()
            val children = entry.dir.listFiles() ?: continue
            visitedDirs++
            for (child in children) {
                val hidden = child.name.startsWith(".")
                if (child.isDirectory) {
                    if (entry.hiddenRoot != null || hidden) {
                        if (isIgnoredHidden(child.name)) continue
                        // Inside a hidden folder everything belongs to us: keep walking with the
                        // deep limit so nested sub folders are covered too.
                        if (entry.depth < DEEP_DEPTH) {
                            queue.add(WalkEntry(child, entry.depth + 1, DEEP_DEPTH,
                                    entry.hiddenRoot ?: child))
                        }
                        continue
                    }
                    when (child.name) {
                        // App private data is not worth a walk, except Android/media which holds
                        // the WhatsApp/Telegram style folders (with hidden .Statuses subfolders).
                        "Android" -> queue.add(WalkEntry(File(child, "media"), entry.depth + 1, DEEP_DEPTH, null))
                        "LOST.DIR", ".thumbnails" -> Unit
                        else -> if (entry.depth < entry.limit) {
                            queue.add(WalkEntry(child, entry.depth + 1, entry.limit, null))
                        }
                    }
                } else if (entry.hiddenRoot != null && isVideo(child)) {
                    // Register the outermost hidden folder, not the nested one holding the file.
                    result.add(entry.hiddenRoot)
                }
            }
        }
        if (result.isNotEmpty()) Log.i(TAG, "${result.size} hidden folder(s) with videos found")
        return result.toList()
    }

    /** Primary storage plus every other readable volume, without duplicates. */
    private fun storageRoots(): List<File> {
        val paths = ArrayList<String>()
        paths.add(AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY)
        paths.addAll(AndroidDevices.externalStorageDirectories)
        return paths.distinct().map { File(it) }
    }

    /** System caches: hidden, but they never hold anything worth indexing. */
    private fun isIgnoredHidden(name: String) = name == ".thumbnails" || name == ".nomedia" ||
            name.startsWith(".trash") || name.startsWith(".Trash") ||
            name.startsWith(".pending") || name == ".statusbar"

    private fun isVideo(file: File): Boolean {
        val name = file.name
        val dot = name.lastIndexOf('.')
        if (dot < 0 || dot == name.length - 1) return false
        return VIDEO_EXTENSIONS.contains(name.substring(dot + 1).lowercase(Locale.ROOT))
    }
}

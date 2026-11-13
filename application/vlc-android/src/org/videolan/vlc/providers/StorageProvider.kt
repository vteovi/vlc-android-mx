/*****************************************************************************
 * StorageProvider.kt
 *****************************************************************************
 * Copyright © 2018 VLC authors and VideoLAN
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston MA 02110-1301, USA.
 *****************************************************************************/

package org.videolan.vlc.providers

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import org.videolan.libvlc.interfaces.IMedia
import org.videolan.libvlc.util.MediaBrowser
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.resources.AndroidDevices
import org.videolan.tools.Settings
import org.videolan.tools.livedata.LiveDataset
import org.videolan.vlc.R
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.util.FileUtils
import java.io.File

class StorageProvider(context: Context, dataset: LiveDataset<MediaLibraryItem>, url: String?) : FileBrowserProvider(context, dataset, url, false, sort = Medialibrary.SORT_FILENAME, desc = false) {

    /**
     * The media library itself never indexes hidden folders, but this browser is also used by the
     * "Media library folders" screen, where the user has to be able to reach a hidden folder to
     * select it. So the hidden-files flag has to follow the display setting here too, instead of
     * being dropped unconditionally.
     */
    override fun getFlags(interact: Boolean): Int {
        var flags = if (interact) MediaBrowser.Flag.NoSlavesAutodetect else 0
        if (Settings.showHiddenFiles) flags = flags or MediaBrowser.Flag.ShowHiddenFiles
        return flags
    }

    override suspend fun browseRootImpl() {
        val storages = DirectoryRepository.getInstance(context).getMediaDirectories()
        val customDirectories = DirectoryRepository.getInstance(context).getCustomDirectories()
        // Framework labels ("SD card", "USB drive", …) for the mounted volumes, keyed by mount point.
        val labels = AndroidDevices.storageVolumeLabels(context)
        val storagesList = ArrayList<MediaLibraryItem>()
        val added = HashSet<String>()
        for (mediaDirLocation in storages) {
            if (mediaDirLocation.isEmpty()) continue
            // Drop any /Android/data/<pkg>/… suffix, the entry must be the volume root.
            val path = AndroidDevices.volumeRoot(mediaDirLocation)
            if (!added.add(path)) continue
            val file = File(path)
            if (!file.exists()) continue
            val readable = file.canRead()
            val label = storageLabel(path, file, labels)
            // A volume known to the framework is always listed, even when the app is still missing
            // the "All files access" permission: the entry then explains what has to be granted.
            if (!readable && !labels.containsKey(path)) continue
            val storage = Storage(Uri.fromFile(file))
            storage.name = if (readable) label
            else "$label · ${context.getString(R.string.mx_storage_need_permission)}"
            storagesList.add(storage)
        }
        customLoop@ for (customDir in customDirectories) {
            for (mediaDirLocation in storages) {
                if (mediaDirLocation.isEmpty()) continue
                if (customDir.path.startsWith(mediaDirLocation)) continue@customLoop
            }
            storagesList.add(Storage(customDir.path.toUri()))
        }
        dataset.value = storagesList
    }

    /** Storage entries are named after the volume, not after a random path segment. */
    private fun storageLabel(path: String, file: File, labels: Map<String, String>): String {
        if (AndroidDevices.EXTERNAL_PUBLIC_DIRECTORY == path) return context.getString(R.string.internal_memory)
        val uuid = file.name
        val description = labels[path]
        return when {
            description == null -> FileUtils.getStorageTag(uuid) ?: uuid
            description.contains("sd", ignoreCase = true) -> "${context.getString(R.string.mx_storage_sd)} ($uuid)"
            description.contains("usb", ignoreCase = true) -> "${context.getString(R.string.mx_storage_usb)} ($uuid)"
            else -> description
        }
    }

    private val sb = StringBuilder()
    override fun getDescription(folderCount: Int, mediaFileCount: Int): String {
        val res = context.resources
        sb.clear()
        if (folderCount > 0) {
            sb.append(res.getQuantityString(R.plurals.subfolders_quantity, folderCount, folderCount))
        } else sb.append(res.getString(R.string.nosubfolder))
        return sb.toString()
    }

    override suspend fun findMedia(media: IMedia) = media.takeIf { it.isStorage() }?.let { Storage(it.uri) }

    override fun computeHeaders(value: List<MediaLibraryItem>) {}
}

private fun IMedia.isStorage() = type == IMedia.Type.Directory

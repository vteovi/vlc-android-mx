/*
 * *************************************************************************
 *  StorageBrowserAdapter.java
 * **************************************************************************
 *  Copyright © 2015 VLC authors and VideoLAN
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
 *  ***************************************************************************
 */

package org.videolan.vlc.gui.browser

import android.content.Context
import android.net.Uri
import android.view.View
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.databinding.ViewDataBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.resources.AndroidDevices
import org.videolan.resources.util.canReadStorage
import org.videolan.tools.containsPath
import org.videolan.tools.removeFileScheme
import org.videolan.tools.sanitizePath
import org.videolan.tools.stripTrailingSlash
import org.videolan.vlc.MediaParsingService
import org.videolan.vlc.R
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.helpers.MedialibraryUtils
import org.videolan.vlc.gui.helpers.ThreeStatesCheckbox
import org.videolan.vlc.repository.DirectoryRepository
import org.videolan.vlc.util.getDescriptionSpan
import org.videolan.vlc.util.isSchemeFile
import java.io.File

class StorageBrowserAdapter(browserContainer: BrowserContainer<MediaLibraryItem>) : BaseBrowserAdapter(browserContainer) {

    private var mediaDirsLocation: MutableList<String> = mutableListOf()
    private lateinit var customDirsLocation: List<String>
    var bannedFolders: List<String> = listOf()
    private var updateJob : Job? = null
    /**
     * States the user just asked for, applied immediately and dropped as soon as the media library
     * reports the same thing back. Without this the list would show the old state until the
     * discovery service has finished.
     */
    private val pendingStates = HashMap<String, Boolean>()

    init {
        updateMediaDirs(browserContainer.containerActivity())
    }

    override fun onBindViewHolder(holder: ViewHolder<ViewDataBinding>, position: Int) {
        val vh = holder as MediaViewHolder
        vh.job = launch {
            var storage = getItem(position)
            val title = storage.title
            if (storage.itemType == MediaLibraryItem.TYPE_MEDIA) storage = Storage((storage as MediaWrapper).uri)
            val uri = (storage as Storage).uri
            var storagePath = if (uri.scheme.isSchemeFile()) uri.path ?: "" else Uri.decode(uri.toString())
            if (!storagePath.endsWith("/")) storagePath += "/"
            if (storage.title.isNullOrBlank()) storage.title = title
            vh.bindingContainer.setItem(storage)
            vh.bindingContainer.setIsTv(AndroidDevices.isTv)
            updateJob?.join()
            if (updateJob?.isCancelled == true) return@launch
            val hasContextMenu = customDirsLocation.contains(storagePath.stripTrailingSlash()) && !multiSelectHelper.inActionMode
            val checked = browserContainer.scannedDirectory || mediaDirsLocation.containsPath(storagePath)
            vh.bindingContainer.setHasContextMenu(hasContextMenu)
            val banned = MedialibraryUtils.isBanned(uri, bannedFolders)
            val bannedParent = isBannedByParent(uri)

            vh.bindingContainer.setIsBanned(banned)
            vh.bindingContainer.setIsBannedByParent(bannedParent)
            val libraryState = when {
                banned -> ThreeStatesCheckbox.STATE_UNCHECKED
                checked -> ThreeStatesCheckbox.STATE_CHECKED
                hasDiscoveredChildren(storagePath) -> ThreeStatesCheckbox.STATE_PARTIAL
                else -> ThreeStatesCheckbox.STATE_UNCHECKED
            }
            vh.bindingContainer.browserCheckbox.state = when (pendingStates[storagePath]) {
                true -> ThreeStatesCheckbox.STATE_CHECKED
                false -> ThreeStatesCheckbox.STATE_UNCHECKED
                else -> libraryState
            }
            if(AndroidDevices.isTv && !browserContainer.isRootDirectory) vh.bindingContainer.banIcon.visibility = View.VISIBLE
            // Tapping the box is the normal way to select a folder, at every depth: a folder below
            // an already scanned root is unselected by banning it.
            vh.bindingContainer.setCheckEnabled(!bannedParent)
        }
    }

    override fun onBindViewHolder(holder: ViewHolder<ViewDataBinding>, position: Int, payloads: MutableList<Any>) {
        if (payloads.isNotEmpty() && payloads[0] is CharSequence) {
            if (!MedialibraryUtils.isBanned((getItem(position) as Storage).uri, bannedFolders)) {
                (holder as MediaViewHolder).bindingContainer.text.visibility = View.VISIBLE
                holder.bindingContainer.text.text = (payloads[0] as CharSequence).getDescriptionSpan(holder.bindingContainer.text.context)
            }
        } else super.onBindViewHolder(holder, position, payloads)
    }

    /**
     * Manages the item visibility on focus changes
     *
     * @param position the item position
     * @param hasFocus true if the item has focus
     * @param bindingContainer the [BrowserItemBindingContainer] associated with the item
     */

    override fun itemFocusChanged(position: Int, hasFocus: Boolean, bindingContainer: BrowserItemBindingContainer) {
        if (!AndroidDevices.isTv) return
        if (browserContainer.isRootDirectory) return
        if (position < 0 || position > itemCount - 1) return
        val uri = (getItem(position) as Storage).uri.toString()
        val banned = MedialibraryUtils.isBanned(uri, bannedFolders)
        val context = bindingContainer.container.context
        val bannedParent = banned && !MedialibraryUtils.isStrictlyBanned(uri, bannedFolders)
        val alpha = when {
            banned || bannedParent -> 1F
            hasFocus -> 1F
            else ->0F
        }

        bindingContainer.banIcon.animate().alpha(alpha)
        bindingContainer.banIcon.setImageDrawable( ContextCompat.getDrawable(context, if (banned || bannedParent) R.drawable.ic_banned else R.drawable.ic_ban))
    }

    override fun onViewRecycled(holder: ViewHolder<ViewDataBinding>) {
        (holder as MediaViewHolder).apply {
            job?.cancel()
            job = null
        }
        super.onViewRecycled(holder)
    }

    private fun hasDiscoveredChildren(path: String): Boolean {
        for (directory in mediaDirsLocation) if (directory.startsWith(path)) return true
        return false
    }

    /** True when an ancestor is banned: the folder itself cannot be changed, only its parent can. */
    private fun isBannedByParent(uri: Uri) = MedialibraryUtils.isBanned(uri, bannedFolders) && !MedialibraryUtils.isStrictlyBanned(uri, bannedFolders)

    private fun uriOf(item: MediaLibraryItem): Uri? = when (item) {
        is Storage -> item.uri
        is MediaWrapper -> item.uri
        else -> null
    }

    private fun pathOf(uri: Uri): String {
        val path = if (uri.scheme.isSchemeFile()) uri.path ?: "" else Uri.decode(uri.toString())
        return if (path.endsWith("/")) path else "$path/"
    }

    /** Mirrors the state computed in [onBindViewHolder], used by the select all / invert actions. */
    private fun isSelected(item: MediaLibraryItem): Boolean {
        val uri = uriOf(item) ?: return false
        if (MedialibraryUtils.isBanned(uri, bannedFolders)) return false
        val path = pathOf(uri)
        return browserContainer.scannedDirectory || mediaDirsLocation.containsPath(path) || hasDiscoveredChildren(path)
    }

    fun allSelected(): Boolean = dataset.isNotEmpty() && dataset.all { isSelected(it) }

    /** Checks or unchecks every listed folder, as if each box had been tapped. */
    fun setAllChecked(checked: Boolean) = applyToAll { checked }

    /** Flips every listed folder. */
    fun invertSelection() = applyToAll { !isSelected(it) }

    private fun applyToAll(resolve: (MediaLibraryItem) -> Boolean) {
        // The onboarding wizard collects a preselection instead of real media library roots.
        if ((browserContainer.containerActivity() as? SecondaryActivity)?.isOnboarding == true) return
        var changed = false
        dataset.forEach { item ->
            val uri = uriOf(item) ?: return@forEach
            // Nothing can be done for a folder that is banned through one of its parents.
            if (isBannedByParent(uri)) return@forEach
            val newState = resolve(item)
            if (isSelected(item) == newState) return@forEach
            applyChecked(uri, pathOf(uri), newState)
            changed = true
        }
        if (changed) notifyItemRangeChanged(0, itemCount)
    }

    /**
     * Applies a checkbox state at any depth:
     * - plain folder -> added / removed as a media library root
     * - folder below an already scanned root -> banned / unbanned, since its parent covers it
     */
    private fun applyChecked(uri: Uri, path: String, checked: Boolean) {
        if (isBannedByParent(uri)) return
        pendingStates[path] = checked
        val delegate = browserContainer.getStorageDelegate() ?: return
        val rawPath = uri.path ?: uri.toString()
        if (checked) {
            if (MedialibraryUtils.isStrictlyBanned(uri, bannedFolders)) delegate.setFolderBanned(rawPath, false)
            else delegate.setFolderChecked(uri.toString(), true)
            return
        }
        when {
            mediaDirsLocation.containsPath(path) -> delegate.setFolderChecked(uri.toString(), false)
            hasDiscoveredChildren(path) -> removeRootsUnder(path)
            browserContainer.scannedDirectory -> delegate.setFolderBanned(rawPath, true)
        }
    }

    /** Unselecting a folder also has to drop the roots that were added deeper, if any. */
    private fun removeRootsUnder(path: String) {
        val key = path.sanitizePath().trimEnd('/')
        if (key.isEmpty()) return
        val delegate = browserContainer.getStorageDelegate() ?: return
        mediaDirsLocation
                .filter { val candidate = it.sanitizePath(); candidate != key && candidate.startsWith("$key/") }
                .forEach { delegate.setFolderChecked(Uri.fromFile(File(it.sanitizePath())).toString(), false) }
    }

    /** Drops the optimistic states the media library now agrees with. */
    private fun prunePendingStates() {
        val iterator = pendingStates.entries.iterator()
        while (iterator.hasNext()) {
            val (path, wanted) = iterator.next()
            if (mediaDirsLocation.containsPath(path) == wanted) iterator.remove()
        }
    }

    suspend fun updateListState(context: Context) {
        updateMediaDirs(context)
        updateJob?.join()
        if (updateJob?.isCancelled == false) {
            prunePendingStates()
            notifyItemRangeChanged(0, itemCount)
        }
    }

    fun updateMediaDirs(context: Context) {
        mediaDirsLocation.clear()

        updateJob = launch {
            val folders = if (!Medialibrary.getInstance().isInitiated) {
                MediaParsingService.preselectedStorages.toTypedArray()
            } else {
                withContext(Dispatchers.IO) { Medialibrary.getInstance().foldersList }
            }

            folders.forEach {
                mediaDirsLocation.add(Uri.decode(it.removeFileScheme()))
            }
            customDirsLocation = DirectoryRepository.getInstance(context).getCustomDirectories().map { it.path }
            prunePendingStates()
        }
    }

    override fun checkBoxAction(v: View, mrl: String) {
        val checkbox = v as? ThreeStatesCheckbox ?: return
        val delegate = browserContainer.getStorageDelegate() ?: return
        // The onboarding wizard keeps its own preselection logic.
        if ((browserContainer.containerActivity() as? SecondaryActivity)?.isOnboarding == true) {
            delegate.checkBoxAction(v, mrl)
            return
        }
        // The widget has already flipped its own state when the click reaches us.
        val checked = checkbox.state != ThreeStatesCheckbox.STATE_UNCHECKED
        if (checked && mrl.contains("file://") && !canReadStorage(v.context)) {
            // Let the delegate ask for the storage permission and reset the box.
            delegate.checkBoxAction(v, mrl)
            return
        }
        val uri = mrl.toUri()
        applyChecked(uri, pathOf(uri), checked)
    }
}

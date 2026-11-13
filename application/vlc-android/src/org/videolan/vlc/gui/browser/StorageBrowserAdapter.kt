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
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import androidx.databinding.ViewDataBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.resources.AndroidDevices
import org.videolan.resources.util.canReadStorage
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

    private companion object {
        const val TAG = "VLC/StorageBrowserAdapter"

        /**
         * How long an optimistic state survives without being confirmed by the media library.
         * After that the box falls back to what the library really knows, so a folder that could
         * not be added can never stay ticked (and therefore block the next select all).
         */
        const val PENDING_TIMEOUT = 20_000L

        /** Small gap between two media library calls of a bulk action, to keep the ML thread alive. */
        const val BULK_STEP_DELAY = 40L
    }

    private val mediaDirsLocation = mutableListOf<String>()
    private lateinit var customDirsLocation: List<String>
    var bannedFolders: List<String> = listOf()
    private var updateJob: Job? = null
    private var bulkJob: Job? = null

    private data class PendingState(val checked: Boolean, val at: Long)

    /**
     * States the user just asked for, applied immediately and dropped as soon as the media library
     * reports the same thing back. Without this the list would show the old state until the
     * discovery service has finished.
     */
    private val pendingStates = HashMap<String, PendingState>()

    val isBulkRunning: Boolean
        get() = bulkJob?.isActive == true

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
            vh.bindingContainer.setHasContextMenu(hasContextMenu)
            val banned = MedialibraryUtils.isBanned(uri, bannedFolders)
            val bannedParent = isBannedByParent(uri)

            vh.bindingContainer.setIsBanned(banned)
            vh.bindingContainer.setIsBannedByParent(bannedParent)
            vh.bindingContainer.browserCheckbox.state = stateOf(uri)
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

    // ---------------------------------------------------------------------------------------
    // Selection state
    //
    // The media library knows three things about a folder: it is a root by itself, it is inside a
    // root, or it contains a root. The list used to mix those up (a folder covered by its parent
    // was reported as selected without being a root), which made "select all" and "invert" fight
    // with the real state and eventually lose track of it. Everything below derives from these
    // three predicates, and the UI, "select all" and "invert" all go through [stateOf].
    // ---------------------------------------------------------------------------------------

    private fun keyOf(path: String): String = path.sanitizePath().trimEnd('/')

    /** This folder itself is a media library root. */
    private fun isLibraryRoot(path: String): Boolean {
        val key = keyOf(path)
        return key.isNotEmpty() && mediaDirsLocation.any { it == key }
    }

    /**
     * A parent folder is a library root, so this folder is inside the media library.
     * Before the folder list is loaded, an already scanned directory still implies that all of its
     * children are covered, which is what the fragment passed along when it was opened.
     */
    private fun isInsideLibraryRoot(path: String): Boolean =
            isCoveredByListedRoot(path) || (mediaDirsLocation.isEmpty() && browserContainer.scannedDirectory)

    /** Same as [isInsideLibraryRoot] but strictly from what the media library reported. */
    private fun isCoveredByListedRoot(path: String): Boolean {
        val key = keyOf(path)
        if (key.isEmpty()) return false
        return mediaDirsLocation.any { it.isNotEmpty() && key.startsWith("$it/") }
    }

    /** A child folder is a library root. */
    private fun hasLibraryChild(path: String): Boolean {
        val key = keyOf(path)
        if (key.isEmpty()) return false
        return mediaDirsLocation.any { it.startsWith("$key/") }
    }

    private fun pendingOf(path: String): Boolean? {
        val pending = pendingStates[path] ?: return null
        if (SystemClock.elapsedRealtime() - pending.at > PENDING_TIMEOUT) {
            pendingStates.remove(path)
            return null
        }
        return pending.checked
    }

    /**
     * The single source of truth for a checkbox: optimistically requested state first, then the
     * media library.
     */
    private fun stateOf(uri: Uri): Int {
        if (MedialibraryUtils.isBanned(uri, bannedFolders)) return ThreeStatesCheckbox.STATE_UNCHECKED
        val path = pathOf(uri)
        pendingOf(path)?.let { return if (it) ThreeStatesCheckbox.STATE_CHECKED else ThreeStatesCheckbox.STATE_UNCHECKED }
        return when {
            isLibraryRoot(path) -> ThreeStatesCheckbox.STATE_CHECKED
            // Covered by a parent root: it is in the library, so the box is simply ticked.
            isInsideLibraryRoot(path) -> ThreeStatesCheckbox.STATE_CHECKED
            // Only some children are library roots, hence the partial box.
            hasLibraryChild(path) -> ThreeStatesCheckbox.STATE_PARTIAL
            else -> ThreeStatesCheckbox.STATE_UNCHECKED
        }
    }

    private fun stateOf(item: MediaLibraryItem): Int {
        val uri = uriOf(item) ?: return ThreeStatesCheckbox.STATE_UNCHECKED
        return stateOf(uri)
    }

    private fun uriOf(item: MediaLibraryItem): Uri? = when (item) {
        is Storage -> item.uri
        is MediaWrapper -> item.uri
        else -> null
    }

    private fun pathOf(uri: Uri): String {
        val path = if (uri.scheme.isSchemeFile()) uri.path ?: "" else Uri.decode(uri.toString())
        return if (path.endsWith("/")) path else "$path/"
    }

    /** True when an ancestor is banned: the folder itself cannot be changed, only its parent can. */
    private fun isBannedByParent(uri: Uri) = MedialibraryUtils.isBanned(uri, bannedFolders) && !MedialibraryUtils.isStrictlyBanned(uri, bannedFolders)

    /**
     * True when nothing but "select all" is left to do: every listed folder is either selected or
     * skipped because its parent is banned.
     */
    fun allSelected(): Boolean {
        if (dataset.isEmpty()) return false
        var selectable = false
        for (item in dataset) {
            val uri = uriOf(item) ?: continue
            if (isBannedByParent(uri)) continue
            selectable = true
            if (stateOf(uri) == ThreeStatesCheckbox.STATE_UNCHECKED) return false
        }
        return selectable
    }

    // ---------------------------------------------------------------------------------------
    // Bulk actions
    //
    // Both act on the folders currently displayed in the list, and only on those: nothing is
    // applied to their children. A directory that is itself a library root is one single entry
    // here, exactly like when the user taps its box.
    // ---------------------------------------------------------------------------------------

    /** Checks or unchecks every folder displayed in the list. */
    fun setAllChecked(checked: Boolean) {
        if (isOnboarding()) return
        startBulk(true) { _ -> checked }
    }

    /** Flips every folder displayed in the list. */
    fun invertSelection() {
        if (isOnboarding()) return
        startBulk(false) { item ->
            val uri = uriOf(item)
            uri != null && stateOf(uri) == ThreeStatesCheckbox.STATE_UNCHECKED
        }
    }

    private fun refreshMenu() {
        try {
            browserContainer.containerActivity().invalidateOptionsMenu()
        } catch (ignored: Exception) {
        }
    }

    private fun toast(resId: Int, vararg args: Any) {
        val context: Context? = try {
            browserContainer.containerActivity()
        } catch (ignored: Exception) {
            null
        } ?: return
        try {
            Toast.makeText(context, context.getString(resId, *args), Toast.LENGTH_SHORT).show()
        } catch (ignored: Exception) {
        }
    }

    private fun isOnboarding() = (browserContainer.containerActivity() as? SecondaryActivity)?.isOnboarding == true

    private fun cancelBulk() {
        bulkJob?.cancel()
        bulkJob = null
    }

    /**
     * Applies [plan] to every displayed folder, one by one, and reports how many entries changed.
     *
     * @param notifyIfNoop show a message when the list already is in the requested state
     */
    private fun startBulk(notifyIfNoop: Boolean, plan: (MediaLibraryItem) -> Boolean) {
        cancelBulk()
        bulkJob = launch {
            try {
                updateJob?.join()
                // Judge on fresh data: a stale folder list made "select all" skip everything.
                refreshFromLibrary()
                val actions = collectActions(plan)
                if (actions.isEmpty()) {
                    if (notifyIfNoop) toast(R.string.mx_selection_nothing_to_do)
                    bulkJob = null
                    refreshMenu()
                    return@launch
                }
                val now = SystemClock.elapsedRealtime()
                for ((_, path, target) in actions) pendingStates[path] = PendingState(target, now)
                notifyItemRangeChanged(0, itemCount)
                // The menu title follows the selection, so it has to be refreshed right away.
                refreshMenu()
                for ((uri, path, target) in actions) {
                    performChecked(uri, path, target)
                    delay(BULK_STEP_DELAY)
                }
                // Give the discovery service a few chances to confirm, so the boxes settle quickly.
                for (attempt in 0 until 3) {
                    delay(if (attempt == 0) 500L else 1200L)
                    refreshFromLibrary()
                    if (pendingStates.isEmpty()) break
                }
                bulkJob = null
                refreshMenu()
                toast(R.string.mx_selection_applied, actions.size)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Bulk selection failed", e)
                bulkJob = null
                refreshMenu()
            }
        }
    }

    /** The list entries whose displayed state differs from [plan]. */
    private fun collectActions(plan: (MediaLibraryItem) -> Boolean): List<Triple<Uri, String, Boolean>> {
        val actions = ArrayList<Triple<Uri, String, Boolean>>()
        for (item in dataset) {
            val uri = uriOf(item) ?: continue
            // Nothing can be done for a folder that is banned through one of its parents.
            if (isBannedByParent(uri)) continue
            val target = plan(item)
            val current = stateOf(uri)
            val alreadyThere = when (current) {
                ThreeStatesCheckbox.STATE_CHECKED -> target
                ThreeStatesCheckbox.STATE_UNCHECKED -> !target
                // A partial folder has to be acted on in both directions.
                else -> false
            }
            if (alreadyThere) continue
            actions.add(Triple(uri, pathOf(uri), target))
        }
        return actions
    }

    /**
     * Applies a checkbox state at any depth:
     * - plain folder -> added / removed as a media library root
     * - folder covered by a parent root -> banned / unbanned, since its parent already covers it
     * - folder containing roots but not covered -> the child roots are dropped
     */
    private suspend fun performChecked(uri: Uri, path: String, checked: Boolean) {
        val delegate = browserContainer.getStorageDelegate() ?: return
        val rawPath = uri.path ?: uri.toString()
        if (checked) {
            // Read the folder list on the main thread: the media library refresh would otherwise
            // mutate it while we are looking at it.
            val covered = isCoveredByListedRoot(path)
            withContext(Dispatchers.IO) {
                // addDir() is a no-op on a banned folder, so the ban always has to be lifted first.
                val live = try {
                    Medialibrary.getInstance().bannedFolders().toList()
                } catch (e: Exception) {
                    Log.w(TAG, "Can't read the banned folders", e)
                    bannedFolders
                }
                val needsUnban = MedialibraryUtils.isStrictlyBanned(rawPath, live)
                if (needsUnban) delegate.setFolderBanned(rawPath, false)
                // Already covered by a parent root: lifting the ban is enough, adding it again as a
                // root would only trigger a useless rescan.
                if (!covered) delegate.setFolderChecked(uri.toString(), true)
            }
            return
        }
        when {
            isLibraryRoot(path) -> withContext(Dispatchers.IO) { delegate.setFolderChecked(uri.toString(), false) }
            isCoveredByListedRoot(path) -> withContext(Dispatchers.IO) { delegate.setFolderBanned(rawPath, true) }
            hasLibraryChild(path) -> removeChildRoots(path)
        }
    }

    /** Unselecting a folder also has to drop the roots that were added deeper, if any. */
    private suspend fun removeChildRoots(path: String) {
        val key = keyOf(path)
        if (key.isEmpty()) return
        val delegate = browserContainer.getStorageDelegate() ?: return
        val children = mediaDirsLocation.filter { it.isNotEmpty() && it != key && it.startsWith("$key/") }
        withContext(Dispatchers.IO) {
            // delay() is not allowed inside forEach's non-suspending lambda
            for (child in children) {
                delegate.setFolderChecked(Uri.fromFile(File(child)).toString(), false)
                delay(BULK_STEP_DELAY)
            }
        }
    }

    /** Drops the optimistic states the media library now agrees with. */
    private fun prunePendingStates() {
        val now = SystemClock.elapsedRealtime()
        val iterator = pendingStates.entries.iterator()
        while (iterator.hasNext()) {
            val (path, pending) = iterator.next()
            val actual = isLibraryRoot(path) || isCoveredByListedRoot(path)
            if (now - pending.at > PENDING_TIMEOUT || actual == pending.checked) iterator.remove()
        }
    }

    private suspend fun refreshFromLibrary() {
        updateMediaDirs(browserContainer.containerActivity())
        updateJob?.join()
        bannedFolders = withContext(Dispatchers.IO) {
            try {
                Medialibrary.getInstance().bannedFolders().toList()
            } catch (e: Exception) {
                Log.w(TAG, "Can't read the banned folders", e)
                bannedFolders
            }
        }
        prunePendingStates()
        notifyItemRangeChanged(0, itemCount)
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
                val key = keyOf(Uri.decode(it.removeFileScheme()))
                if (key.isNotEmpty()) mediaDirsLocation.add(key)
            }
            customDirsLocation = DirectoryRepository.getInstance(context).getCustomDirectories().map { it.path }
            prunePendingStates()
        }
    }

    override fun checkBoxAction(v: View, mrl: String) {
        val checkbox = v as? ThreeStatesCheckbox ?: return
        val delegate = browserContainer.getStorageDelegate() ?: return
        // The onboarding wizard keeps its own preselection logic.
        if (isOnboarding()) {
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
        val path = pathOf(uri)
        cancelBulk()
        pendingStates[path] = PendingState(checked, SystemClock.elapsedRealtime())
        launch { performChecked(uri, path, checked) }
    }
}

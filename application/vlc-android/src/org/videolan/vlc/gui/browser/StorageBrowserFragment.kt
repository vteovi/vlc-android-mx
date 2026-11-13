/*
 * *************************************************************************
 *  StorageBrowserFragment.java
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

import android.annotation.TargetApi
import android.content.DialogInterface
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.text.InputType
import android.view.Menu
import android.view.MenuItem
import android.view.View
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.widget.AppCompatEditText
import androidx.databinding.DataBindingUtil
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.Tools
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.media.MediaLibraryItem
import org.videolan.medialibrary.media.Storage
import org.videolan.tools.Settings
import org.videolan.vlc.R
import org.videolan.vlc.VlcMigrationHelper
import org.videolan.vlc.databinding.BrowserItemBinding
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.gui.helpers.MedialibraryUtils
import org.videolan.vlc.gui.helpers.ThreeStatesCheckbox
import org.videolan.vlc.gui.helpers.UiTools
import org.videolan.vlc.viewmodels.browser.TYPE_STORAGE
import org.videolan.vlc.viewmodels.browser.getBrowserModel
import java.io.File

const val KEY_IN_MEDIALIB = "key_in_medialib"

class StorageBrowserFragment : FileBrowserFragment(), BrowserContainer<MediaLibraryItem>, IStorageFragmentDelegate by StorageFragmentDelegate() {

    override var scannedDirectory = false
    private var snack: com.google.android.material.snackbar.Snackbar? = null
    private var alertDialog: AlertDialog? = null
    /** Last known value of the "All files access" grant, used to refresh after the settings trip. */
    private var lastStorageAccess: Boolean? = null
    // This fragment should never be displayed in cards. Forcing to false
    override var inCards: Boolean
        get() = false
        set(_) {}

    override val categoryTitle: String
        get() = getString(R.string.directories_summary)

    override fun createFragment(): Fragment {
        return StorageBrowserFragment()
    }

    override fun hasFAB(): Boolean {
        if (requireActivity() is SecondaryActivity) return false
        return super.hasFAB()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        adapter = StorageBrowserAdapter(this)
        (adapter as StorageBrowserAdapter).bannedFolders = Medialibrary.getInstance().bannedFolders().toList()
        val bundle = savedInstanceState ?: arguments
        if (bundle != null) scannedDirectory = bundle.getBoolean(KEY_IN_MEDIALIB)
        withContext(requireActivity())
        withAdapters(arrayOf(adapter as StorageBrowserAdapter))
    }

    @TargetApi(Build.VERSION_CODES.LOLLIPOP)
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (isRootDirectory && Settings.showTvUi) {
            snack = com.google.android.material.snackbar.Snackbar.make(view, R.string.tv_settings_hint, com.google.android.material.snackbar.Snackbar.LENGTH_INDEFINITE)
            if (VlcMigrationHelper.isLolliPopOrLater) snack?.view?.elevation = view.resources.getDimensionPixelSize(R.dimen.audio_player_elevation).toFloat()
        }
    }

    override fun setupBrowser() {
        viewModel = getBrowserModel(TYPE_STORAGE, mrl)
    }

    override fun onStart() {
        super.onStart()
        addRootsCallback()
        // Coming back from the "All files access" settings screen: SD cards may be readable now.
        val granted = Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()
        val previous = lastStorageAccess
        lastStorageAccess = granted
        if (previous != null && previous != granted) viewModel.browseRoot()
        snack?.show()
        lifecycleScope.launchWhenStarted { if (isAdded) (adapter as StorageBrowserAdapter).updateListState(requireContext()) }
        addBannedFoldersCallback { folder, _ ->
            (adapter as StorageBrowserAdapter).bannedFolders = Medialibrary.getInstance().bannedFolders().toList()
            adapter.dataset.forEachIndexed{ index, mediaLibraryItem ->
                if ("${Tools.mlEncodeMrl(((mediaLibraryItem as Storage).uri.toString()))}/" == folder) adapter.notifyItemChanged(index)
            }
        }
    }

    override fun onStop() {
        super.onStop()
        removeRootsCallback()
        snack?.dismiss()
        alertDialog?.let { if (it.isShowing) it.dismiss() }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_IN_MEDIALIB, scannedDirectory)
    }

    override fun onPrepareOptionsMenu(menu: Menu) {
        super.onPrepareOptionsMenu(menu)
        menu.findItem(R.id.ml_menu_custom_dir)?.isVisible = true
        menu.findItem(R.id.ml_menu_refresh)?.isVisible = false
        menu.findItem(R.id.ml_menu_add_playlist)?.isVisible = false
        updateSelectionMenu(menu)
    }

    /** Shows "select all" / "deselect all" depending on the current selection, plus "invert". */
    private fun updateSelectionMenu(menu: Menu) {
        val selectAll = menu.findItem(R.id.ml_menu_select_all) ?: return
        val invert = menu.findItem(R.id.ml_menu_invert_selection)
        val hasItems = isAdapterInitialized && adapter.itemCount > 0 && (activity as? SecondaryActivity)?.isOnboarding != true
        selectAll.isVisible = hasItems
        invert?.isVisible = hasItems
        if (!hasItems) return
        selectAll.setTitle(if ((adapter as? StorageBrowserAdapter)?.allSelected() == true) R.string.mx_deselect_all else R.string.mx_select_all)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        val storageAdapter = if (isAdapterInitialized) adapter as? StorageBrowserAdapter else null
        when (item.itemId) {
            R.id.ml_menu_custom_dir -> {
                showAddDirectoryDialog()
                return true
            }
            R.id.ml_menu_select_all -> {
                storageAdapter?.setAllChecked(storageAdapter.allSelected().not())
                requireActivity().invalidateOptionsMenu()
                return true
            }
            R.id.ml_menu_invert_selection -> {
                storageAdapter?.invertSelection()
                requireActivity().invalidateOptionsMenu()
                return true
            }
        }
        return super.onOptionsItemSelected(item)
    }


    override fun onCtxClick(v: View, position: Int, item: MediaLibraryItem) {}

    override fun onClick(v: View, position: Int, item: MediaLibraryItem) {
        val mw = (item as? Storage)?.let { MLServiceLocator.getAbstractMediaWrapper(it.uri) } ?: return
        mw.type = MediaWrapper.TYPE_DIR
        // A volume can be listed while the app still lacks "All files access": explain instead of
        // opening an empty folder.
        val path = mw.uri.path
        if (path != null && !File(path).canRead()) {
            askStoragePermission()
            return
        }
        browse(mw, scannedDirectory || (DataBindingUtil.findBinding<BrowserItemBinding>(v))?.browserCheckbox?.state == ThreeStatesCheckbox.STATE_CHECKED, createFragment(),if (isRootDirectory) "root" else if (currentMedia != null) currentMedia?.uri.toString() else mrl!!)
    }

    /** Points the user to the system "All files access" screen for this app. */
    private fun askStoragePermission() {
        val anchor = view ?: return
        val bar = com.google.android.material.snackbar.Snackbar.make(anchor,
                R.string.mx_storage_permission_hint,
                com.google.android.material.snackbar.Snackbar.LENGTH_LONG)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            bar.setAction(R.string.mx_storage_grant) {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                        Uri.fromParts("package", requireContext().packageName, null))
                try {
                    startActivity(intent)
                } catch (ignored: Exception) {
                    try {
                        startActivity(Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                    } catch (ignored2: Exception) {
                    }
                }
            }
        }
        bar.show()
    }

    override fun onLongClick(v: View, position: Int, item: MediaLibraryItem): Boolean {
        val uri = (item as? Storage)?.uri ?: return false
        val path = uri.path ?: return false
        val title = item.title
        lifecycleScope.launch {
            // Banning is the only way to exclude one folder from an already scanned parent, so it
            // stays on long press — with a feedback, it used to happen silently.
            val banned = withContext(Dispatchers.IO) {
                viewModel.toggleBanState(path)
                MedialibraryUtils.isStrictlyBanned(path, Medialibrary.getInstance().bannedFolders().toList())
            }
            if (isAdded) UiTools.snacker(requireActivity(), getString(if (banned) R.string.mx_folder_banned else R.string.mx_folder_unbanned, title))
        }
        return true
    }

    override fun onMainActionClick(v: View, position: Int, item: MediaLibraryItem) {}

    private fun showAddDirectoryDialog() {
        val context = activity
        val builder = AlertDialog.Builder(context!!)
        val input = AppCompatEditText(context)
        input.inputType = InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
        builder.setTitle(R.string.add_custom_path)
        builder.setMessage(R.string.add_custom_path_description)
        builder.setView(input)
        builder.setNegativeButton(R.string.cancel) { _, _ -> }
        builder.setPositiveButton(R.string.ok, DialogInterface.OnClickListener { _, _ ->
            val path = input.text.toString().trim { it <= ' ' }
            val f = File(path)
            if (!f.exists() || !f.isDirectory) {
                UiTools.snacker(requireActivity(), getString(R.string.directorynotfound, path))
                return@OnClickListener
            }

            lifecycleScope.launch(CoroutineExceptionHandler { _, _ -> }) {
                viewModel.addCustomDirectory(f.canonicalPath).join()
                viewModel.browseRoot()
            }
        })
        alertDialog = builder.show()
    }

    override fun containerActivity() = requireActivity()
    override fun getStorageDelegate(): IStorageFragmentDelegate? = this

    override val isNetwork = false
    override val isFile = true
}

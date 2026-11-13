/*****************************************************************************
 * VideoFoldersFragment.kt
 *****************************************************************************
 * Folder browsing for the video tab, MX Player style.
 *
 * The folder list is derived from the videos in the system index, so opening this tab never waits
 * for the VLC media library: the directories are simply the parents of the indexed video files, and
 * tapping one opens its videos (still served by the system index, see VideosProvider.folderPath).
 *
 * Folders list the videos they directly contain, like a file manager: a subfolder keeps its own row
 * instead of being merged into its parent.
 *****************************************************************************/

package org.videolan.vlc.gui.video

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.view.ActionMode
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.videolan.resources.KEY_FOLDER_PATH
import org.videolan.vlc.R
import org.videolan.vlc.gui.BaseFragment
import org.videolan.vlc.gui.SecondaryActivity
import org.videolan.vlc.interfaces.Filterable
import org.videolan.vlc.util.FastVideoIndex

class VideoFoldersFragment : BaseFragment(), Filterable {

    private var folderList: RecyclerView? = null
    private var emptyView: TextView? = null
    private var adapter: VideoFolderAdapter? = null
    private var filterQuery: String? = null
    private var loading = false

    override fun getTitle() = getString(R.string.mx_video_folders)

    override fun hasFAB() = false

    // Hosted by the video pager: don't let this tab decide the FAB visibility for the activity.
    override val isMainNavigationPoint = false

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View? {
        return inflater.inflate(R.layout.fragment_video_folders, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        folderList = view.findViewById(R.id.folder_list)
        emptyView = view.findViewById(R.id.folder_empty)
        val folderAdapter = VideoFolderAdapter { entry -> openFolder(entry) }
        adapter = folderAdapter
        folderList?.layoutManager = LinearLayoutManager(requireContext())
        folderList?.adapter = folderAdapter
        folderList?.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                // Same behaviour as the other lists: the pull-to-refresh is only armed at the top.
                swipeRefreshLayout.isEnabled = !recyclerView.canScrollVertically(-1)
            }
        })
        swipeRefreshLayout.setOnRefreshListener { load(true) }
        load(false)
    }

    override fun onStart() {
        super.onStart()
        // Coming back from a folder, or from the player: re-read the (cached) index.
        load(false)
    }

    private fun load(force: Boolean) {
        val ctx = context ?: return
        if (loading) {
            // A pass is already running: just drop the pull-to-refresh indicator, its result will
            // land in the list.
            swipeRefreshLayout.isRefreshing = false
            return
        }
        loading = true
        if (force) {
            FastVideoIndex.invalidate()
            swipeRefreshLayout.isRefreshing = true
        }
        val query = filterQuery
        lifecycleScope.launch {
            val folders = withContext(Dispatchers.IO) { FastVideoIndex.folders(ctx, query) }
            loading = false
            if (!isAdded) return@launch
            adapter?.submit(folders)
            emptyView?.visibility = if (folders.isEmpty()) View.VISIBLE else View.GONE
            swipeRefreshLayout.isRefreshing = false
        }
    }

    private fun openFolder(entry: FastVideoIndex.FolderEntry) {
        val intent = Intent(activity, SecondaryActivity::class.java)
        intent.putExtra(SecondaryActivity.KEY_FRAGMENT, SecondaryActivity.VIDEO_FOLDER_CONTENT)
        intent.putExtra(KEY_FOLDER_PATH, entry.path)
        startActivityForResult(intent, SecondaryActivity.ACTIVITY_RESULT_SECONDARY)
    }

    override fun onCreateActionMode(mode: ActionMode?, menu: Menu?) = false

    override fun onActionItemClicked(mode: ActionMode?, item: MenuItem?) = false

    override fun onDestroyActionMode(mode: ActionMode?) {}

    // Filterable: the toolbar search filters the folder names and paths.

    override fun getFilterQuery() = filterQuery

    override fun enableSearchOption() = true

    override fun filter(query: String) {
        filterQuery = query.takeIf { it.isNotEmpty() }
        load(false)
    }

    override fun restoreList() {
        filterQuery = null
        load(false)
    }

    override fun setSearchVisibility(visible: Boolean) {}

    override fun allowedToExpand() = true

    companion object {
        fun newInstance() = VideoFoldersFragment()
    }
}

class VideoFolderAdapter(private val onClick: (FastVideoIndex.FolderEntry) -> Unit) : RecyclerView.Adapter<VideoFolderAdapter.ViewHolder>() {

    private val items = ArrayList<FastVideoIndex.FolderEntry>()

    fun submit(folders: List<FastVideoIndex.FolderEntry>) {
        items.clear()
        items.addAll(folders)
        notifyDataSetChanged()
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.video_folder_item, parent, false)
        return ViewHolder(view, onClick)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) = holder.bind(items[position])

    override fun getItemCount() = items.size

    class ViewHolder(view: View, private val onClick: (FastVideoIndex.FolderEntry) -> Unit) : RecyclerView.ViewHolder(view) {
        private val icon: ImageView = view.findViewById(R.id.folder_icon)
        private val name: TextView = view.findViewById(R.id.folder_name)
        private val path: TextView = view.findViewById(R.id.folder_path)
        private val count: TextView = view.findViewById(R.id.folder_count)

        fun bind(entry: FastVideoIndex.FolderEntry) {
            name.text = entry.name
            path.text = entry.path
            count.text = itemView.context.getString(R.string.mx_folder_videos_count, entry.count)
            itemView.contentDescription = entry.name
            itemView.setOnClickListener { onClick(entry) }
            icon.setImageResource(R.drawable.ic_folder)
        }
    }
}

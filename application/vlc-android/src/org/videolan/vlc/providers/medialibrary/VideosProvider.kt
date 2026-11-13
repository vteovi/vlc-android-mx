/*****************************************************************************
 * VideosProvider.kt
 *****************************************************************************
 * Copyright © 2019 VLC authors and VideoLAN
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

package org.videolan.vlc.providers.medialibrary

import android.content.Context
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import org.videolan.medialibrary.interfaces.media.Folder
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.medialibrary.interfaces.media.VideoGroup
import org.videolan.tools.Settings
import org.videolan.vlc.media.getAll
import org.videolan.vlc.util.FastVideoIndex
import org.videolan.vlc.viewmodels.SortableModel

class VideosProvider(val folder : Folder?, val group: VideoGroup?, context: Context, model: SortableModel, val folderPath: String? = null) : MedialibraryProvider<MediaWrapper>(context, model){

    init {
        // A leftover "only favourites" state (it is persisted per sort key) would silently fall
        // back to the media library, so the fast list always starts from the full collection.
        // The stored value itself is kept, so turning the switch off restores the user's filter.
        if (folder == null && group == null && (folderPath != null || FastVideoIndex.isWanted())) onlyFavorites = false
    }

    override fun canSortByFileNameName() = true
    override fun canSortByDuration() = true
    override fun canSortByLastModified() = folder == null
    override fun canSortByInsertionDate() = group == null
    override val isVideoPermDependant = true

    /**
     * The flat "All videos" list comes from the system index; the folder and group views still
     * need the library itself, and so does the favourites filter (a favourite is a library flag).
     * A folder opened from the folder tab is scoped by path, which the library cannot answer
     * without a Folder object, so it always reads the system index too.
     */
    internal fun useFastScan() = folder === null && group === null && !onlyFavorites
            && (folderPath != null || FastVideoIndex.isWanted())

    override fun getTotalCount() = if (useFastScan()) FastVideoIndex.count(context, model.filterQuery, folderPath) else if (model.filterQuery == null) when {
        folder !== null -> folder.mediaCount(Folder.TYPE_FOLDER_VIDEO)
        group !== null -> group.mediaCount()
        else -> medialibrary.videoCount
    } else when {
        folder !== null -> folder.searchTracksCount(model.filterQuery, Folder.TYPE_FOLDER_VIDEO)
        group !== null -> group.searchTracksCount(model.filterQuery)
        else -> medialibrary.getVideoCount(model.filterQuery)
    }

    override fun getPage(loadSize: Int, startposition: Int): Array<MediaWrapper> {
        if (useFastScan()) {
            val fast = FastVideoIndex.page(context, sort, desc, model.filterQuery, loadSize, startposition, folderPath)
            model.viewModelScope.launch { completeHeaders(fast, startposition) }
            return fast
        }
        val list = if (model.filterQuery == null) when {
            folder !== null -> folder.media(Folder.TYPE_FOLDER_VIDEO, sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
            group !== null -> group.media(sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
            else -> medialibrary.getPagedVideos(sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
        } else when {
            folder !== null -> folder.searchTracks(model.filterQuery, Folder.TYPE_FOLDER_VIDEO, sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
            group !== null -> group.searchTracks(model.filterQuery, sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
            else -> medialibrary.searchVideo(model.filterQuery, sort, desc, Settings.includeMissing, onlyFavorites, loadSize, startposition)
        }
        model.viewModelScope.launch { completeHeaders(list, startposition) }
        return list
    }

    override fun getAll(): Array<MediaWrapper> = if (useFastScan()) {
        FastVideoIndex.all(context, sort, desc, model.filterQuery, folderPath)
    } else if (model.filterQuery == null) when {
        folder !== null -> folder.getAll(Folder.TYPE_FOLDER_VIDEO, sort, desc, Settings.includeMissing).toTypedArray()
        group !== null -> group.getAll(sort, desc, Settings.includeMissing, onlyFavorites).toTypedArray()
        else -> medialibrary.getVideos(sort, desc, Settings.includeMissing, onlyFavorites)
    } else {
        val count = getTotalCount()
        when {
            folder !== null -> folder.searchTracks(model.filterQuery, Folder.TYPE_FOLDER_VIDEO, sort, desc, Settings.includeMissing, onlyFavorites, count, 0)
            group !== null -> group.searchTracks(model.filterQuery, sort, desc, Settings.includeMissing, onlyFavorites, count, 0)
            else -> medialibrary.searchVideo(model.filterQuery, sort, desc, Settings.includeMissing, onlyFavorites, count, 0)
        }
    }
}

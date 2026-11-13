/*****************************************************************************
 * FastVideoIndex.kt
 *****************************************************************************
 * Instant video list, read from the system media index.
 *
 * Building the VLC media library means opening every single file with libVLC to learn its type,
 * duration and tracks. That costs a few hundred milliseconds per video, so on a big collection the
 * scan runs for minutes and the "Video" tab would fill up very slowly.
 *
 * Android already indexes every video on the device (it is what the gallery apps use), so we read
 * that index instead: one cursor returns the whole collection in a few hundred milliseconds, the
 * system generates the thumbnails on demand, and playing a video only needs its file path.
 *
 * This is now the normal source for the flat "All videos" list, not a stopgap: the media library
 * keeps its slow per-file pass in the background for favourites, groups and resume points, but
 * nothing waits for it any more. Videos the library already knows about are returned as the
 * library entry itself, so those features keep working, and the videos explicitly enabled from
 * hidden folders (which the system index never walks) are merged in from the library.
 *****************************************************************************/

package org.videolan.vlc.util

import android.content.Context
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import android.util.Log
import org.videolan.medialibrary.MLServiceLocator
import org.videolan.medialibrary.interfaces.Medialibrary
import org.videolan.medialibrary.interfaces.media.MediaWrapper
import org.videolan.tools.Settings
import java.io.File

object FastVideoIndex {

    private const val TAG = "VLC/FastVideoIndex"

    /**
     * Long enough to survive a scroll session without re-reading the index, short enough to pick
     * up a video copied to the device while the app is open.
     */
    private const val CACHE_TTL = 30_000L

    private val lock = Any()

    /**
     * Plain fields, not MediaWrapper: creating a wrapper is a JNI call, and doing it for a whole
     * collection before the first page is what made the list slow to appear. Only the visible page
     * is turned into wrappers now.
     */
    private data class Entry(val path: String, val title: String, val duration: Long, val modified: Long)

    private var cached: List<Entry>? = null
    private var cachedAt = 0L

    private val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED)

    /**
     * True when the flat video list should be served from the system index. It stays on by default
     * now that the media library no longer has to finish a scan before the list is usable.
     */
    fun isWanted(): Boolean = Settings.fastVideoScan

    fun count(context: Context, filter: String?): Int {
        return list(context, filter).size
    }

    fun page(context: Context, sort: Int, desc: Boolean, filter: String?, size: Int, start: Int): Array<MediaWrapper> {
        val entries = list(context, filter, sort, desc)
        if (start >= entries.size) return emptyArray()
        val end = (start + size).coerceAtMost(entries.size)
        return entries.subList(start, end).map { toWrapper(it) }.toTypedArray()
    }

    fun all(context: Context, sort: Int, desc: Boolean, filter: String?): Array<MediaWrapper> {
        return list(context, filter, sort, desc).map { toWrapper(it) }.toTypedArray()
    }

    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    private fun toWrapper(entry: Entry): MediaWrapper {
        val uri = Uri.fromFile(File(entry.path))
        // Reuse the media library entry when there is one: it carries the real id, and favourites,
        // groups and resume points are all stored against that id. Only videos the library does not
        // know yet get a synthetic wrapper.
        val known = try {
            val medialibrary = Medialibrary.getInstance()
            if (medialibrary.isInitiated) medialibrary.getMedia(uri) else null
        } catch (e: Exception) {
            Log.w(TAG, "Can't look up ${entry.path} in the media library", e)
            null
        }
        if (known != null && known.type == MediaWrapper.TYPE_VIDEO) return known
        val media = MLServiceLocator.getAbstractMediaWrapper(uri)
        media.setType(MediaWrapper.TYPE_VIDEO)
        media.setTitle(entry.title)
        media.setLength(entry.duration)
        media.setLastModified(entry.modified)
        return media
    }

    private fun list(context: Context, filter: String?, sort: Int = Medialibrary.SORT_DEFAULT, desc: Boolean = false): List<Entry> {
        val videos = source(context)
        val needle = filter?.takeIf { it.isNotEmpty() }
        val filtered = if (needle === null) videos else videos.filter { entry ->
            entry.title.contains(needle, true) || entry.path.contains(needle, true)
        }
        val sorted = filtered.sortedWith(comparator(sort))
        return if (desc) sorted.asReversed() else sorted
    }

    private fun comparator(sort: Int): Comparator<Entry> = when (sort) {
        Medialibrary.SORT_DURATION -> compareBy<Entry> { it.duration }
        Medialibrary.SORT_INSERTIONDATE, Medialibrary.SORT_LASTMODIFICATIONDATE -> compareBy<Entry> { it.modified }
        Medialibrary.SORT_FILENAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: Entry -> File(entry.path).name }
        else -> compareBy(String.CASE_INSENSITIVE_ORDER) { entry: Entry -> entry.title }
    }

    private fun source(context: Context): List<Entry> {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - cachedAt < CACHE_TTL) return it }
            val list = query(context)
            cached = list
            cachedAt = now
            return list
        }
    }

    private fun query(context: Context): List<Entry> {
        val result = ArrayList<Entry>()
        val seen = HashSet<String>()
        try {
            // No per-file File.exists() here: one stat call per video was the slowest part of the
            // whole query on a big or external storage. A path that the index still lists but that
            // is gone is caught when playback starts, which already checks MediaWrapper.isPresent.
            val selection = "${MediaStore.Video.Media.SIZE} > 0"
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, selection, null, null)?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIdx) ?: continue
                    if (path.isEmpty() || !seen.add(path)) continue
                    val title = cursor.getString(titleIdx) ?: File(path).nameWithoutExtension
                    result.add(Entry(path, title, cursor.getLong(durationIdx), cursor.getLong(dateIdx) * 1000L))
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't read the system video index", e)
        }
        if (Settings.showHiddenVideos) result.addAll(hiddenEntries(seen))
        return result
    }

    /**
     * The system index never walks folders whose name starts with a dot, so the videos the user
     * explicitly asked for with "show videos from hidden folders" have to come from the library.
     */
    private fun hiddenEntries(seen: MutableSet<String>): List<Entry> {
        val result = ArrayList<Entry>()
        try {
            val medialibrary = Medialibrary.getInstance()
            if (!medialibrary.isInitiated || !medialibrary.isStarted) return result
            for (media in medialibrary.getVideos(Medialibrary.SORT_DEFAULT, false, true, false)) {
                val path = media.uri.path ?: continue
                if (!path.contains("/.")) continue
                if (!seen.add(path)) continue
                result.add(Entry(path, media.title ?: File(path).nameWithoutExtension, media.length, media.lastModified))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't read the hidden videos from the media library", e)
        }
        return result
    }
}

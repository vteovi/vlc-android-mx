/*****************************************************************************
 * FastVideoIndex.kt
 *****************************************************************************
 * MX-style instant video list, read from the system media index.
 *
 * Building the VLC media library means opening every single file with libVLC to learn its type,
 * duration and tracks. That costs a few hundred milliseconds per video, so on a big collection the
 * first scan runs for minutes and the "Video" tab fills up very slowly.
 *
 * Android already indexes every video on the device (it is what the gallery apps use), so we read
 * that index instead: one cursor returns the whole collection in a few hundred milliseconds, the
 * system generates the thumbnails on demand, and playing a video only needs its file path.
 *
 * This list is only a stopgap: it is used while the media library is busy, and as soon as the
 * library is idle its own list (favourites, groups, resume points, hidden folders…) takes over.
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

    /** The system index doesn't move that fast: keep a page-worth of cursors off the hot path. */
    private const val CACHE_TTL = 5_000L

    private val lock = Any()
    private var cached: List<MediaWrapper>? = null
    private var cachedAt = 0L

    /** Set as soon as the fast list served something, used to know a switch back is needed. */
    @Volatile
    var serving = false
        private set

    private val projection = arrayOf(
            MediaStore.Video.Media.DATA,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.DATE_MODIFIED)

    /**
     * True while the media library still needs its slow per-file pass, so the instant list is the
     * best thing we can show.
     */
    fun isWanted(): Boolean {
        if (!Settings.fastVideoScan) return false
        val medialibrary = Medialibrary.getInstance()
        if (!medialibrary.isInitiated || !medialibrary.isStarted) return true
        if (medialibrary.isWorking) return true
        return medialibrary.videoCount == 0
    }

    fun count(context: Context, filter: String?): Int {
        serving = true
        return list(context, filter).size
    }

    fun page(context: Context, sort: Int, desc: Boolean, filter: String?, size: Int, start: Int): Array<MediaWrapper> {
        serving = true
        val videos = list(context, filter, sort, desc)
        if (start >= videos.size) return emptyArray()
        val end = (start + size).coerceAtMost(videos.size)
        return videos.subList(start, end).toTypedArray()
    }

    fun all(context: Context, sort: Int, desc: Boolean, filter: String?): Array<MediaWrapper> {
        serving = true
        return list(context, filter, sort, desc).toTypedArray()
    }

    fun invalidate() {
        synchronized(lock) { cached = null }
    }

    private fun list(context: Context, filter: String?, sort: Int = Medialibrary.SORT_DEFAULT, desc: Boolean = false): List<MediaWrapper> {
        val videos = source(context)
        val needle = filter?.takeIf { it.isNotEmpty() }
        val filtered = if (needle === null) videos else videos.filter { media ->
            val title = media.title ?: ""
            val path = media.uri.path ?: ""
            title.contains(needle, true) || path.contains(needle, true)
        }
        val sorted = filtered.sortedWith(comparator(sort))
        return if (desc) sorted.asReversed() else sorted
    }

    private fun comparator(sort: Int): Comparator<MediaWrapper> = when (sort) {
        Medialibrary.SORT_DURATION -> compareBy<MediaWrapper> { it.length }
        Medialibrary.SORT_INSERTIONDATE, Medialibrary.SORT_LASTMODIFICATIONDATE -> compareBy<MediaWrapper> { it.lastModified }
        Medialibrary.SORT_FILENAME -> compareBy(String.CASE_INSENSITIVE_ORDER) { media: MediaWrapper -> media.fileName ?: "" }
        else -> compareBy(String.CASE_INSENSITIVE_ORDER) { media: MediaWrapper -> media.title ?: "" }
    }

    private fun source(context: Context): List<MediaWrapper> {
        synchronized(lock) {
            val now = SystemClock.elapsedRealtime()
            cached?.let { if (now - cachedAt < CACHE_TTL) return it }
            val list = query(context)
            cached = list
            cachedAt = now
            return list
        }
    }

    private fun query(context: Context): List<MediaWrapper> {
        val result = ArrayList<MediaWrapper>()
        try {
            context.contentResolver.query(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, projection, null, null, null)?.use { cursor ->
                val dataIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATA)
                val titleIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
                val durationIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
                val dateIdx = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_MODIFIED)
                while (cursor.moveToNext()) {
                    val path = cursor.getString(dataIdx) ?: continue
                    val file = File(path)
                    // The index keeps entries for files deleted outside of it: drop them.
                    if (path.isEmpty() || !file.exists()) continue
                    val media = MLServiceLocator.getAbstractMediaWrapper(Uri.fromFile(file))
                    media.setType(MediaWrapper.TYPE_VIDEO)
                    media.setTitle(cursor.getString(titleIdx) ?: file.nameWithoutExtension)
                    media.setLength(cursor.getLong(durationIdx))
                    media.setLastModified(cursor.getLong(dateIdx) * 1000L)
                    result.add(media)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Can't read the system video index", e)
        }
        return result
    }
}

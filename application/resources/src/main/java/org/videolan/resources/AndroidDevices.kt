/*****************************************************************************
 * AndroidDevices.java
 *
 * Copyright © 2011-2014 VLC authors and VideoLAN
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
 */

package org.videolan.resources

import android.annotation.TargetApi
import android.content.Context
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Build.VERSION_CODES
import android.os.Environment
import android.os.storage.StorageManager
import android.os.storage.StorageVolume
import android.telephony.TelephonyManager
import android.util.Log
import android.view.InputDevice
import android.view.MotionEvent
import androidx.core.content.getSystemService
import androidx.core.net.toUri
import org.videolan.libvlc.util.AndroidUtil
import org.videolan.resources.util.getPackageInfoCompat
import org.videolan.tools.containsName
import org.videolan.tools.getFileNameFromPath
import org.videolan.tools.startsWith
import java.io.BufferedReader
import java.io.Closeable
import java.io.File
import java.io.FileReader
import java.io.IOException
import java.util.Locale
import java.util.StringTokenizer
import kotlin.math.absoluteValue

@TargetApi(VERSION_CODES.N)
object AndroidDevices {
    const val TAG = "VLC/UiTools/AndroidDevices"
    val EXTERNAL_PUBLIC_DIRECTORY: String = Environment.getExternalStorageDirectory().path
    val isPhone: Boolean
    val hasNavBar: Boolean
    val hasTsp: Boolean
    val isAndroidTv: Boolean
    val watchDevices: Boolean
    val isTv: Boolean
    val isAmazon = Build.MANUFACTURER == "Amazon"
    val isChromeBook: Boolean
    //Is the system PiP available on this device system
    val hasPiP: Boolean
    //Is PiP or custom PiP allowed on this device
    val pipAllowed: Boolean
    private val noMediaStyleManufacturers = arrayOf("huawei", "symphony teleca")
    val showMediaStyle = !isManufacturerBannedForMediastyleNotifications
    val hasPlayServices: Boolean

    //Devices mountpoints management
    private val typeWL = listOf("vfat", "exfat", "sdcardfs", "fuse", "ntfs", "fat32", "ext3", "ext4", "esdfs")
    private val typeBL = listOf("tmpfs")
    private val mountWL = arrayOf("/mnt", "/Removable", "/storage")
    val mountBL = arrayOf(EXTERNAL_PUBLIC_DIRECTORY, "/mnt/secure", "/mnt/shell", "/mnt/asec", "/mnt/nand", "/mnt/runtime", "/mnt/obb", "/mnt/media_rw/extSdCard", "/mnt/media_rw/sdcard", "/storage/emulated", "/var/run/arc")
    private val deviceWL = arrayOf("/dev/block/vold", "/dev/fuse", "/mnt/media_rw", "passthrough", "//")
    // Some devices should be considered as TVs
    // Amazon Echo 15 (AEOHY): has touch screen but main navigation is done by remote
    val forcedTVModels = arrayOf("AEOHY")

    /**
     * hasCombBar test if device has Combined Bar : only for tablet with Honeycomb or ICS
     */

    // skip if already in list or if type/mountpoint is blacklisted
    // check that device is in whitelist, and either type or mountpoint is in a whitelist
    val externalStorageDirectories: List<String>
        get() {
            return try {
                scanExternalStorageDirectories()
            } catch (t: Throwable) {
                // This getter runs on the cold start path (browser, storage provider). A device
                // specific failure here used to take the whole app down on first launch, so the
                // worst case is now "only the internal storage is listed".
                Log.w(TAG, "Unable to enumerate storage volumes", t)
                listOf(EXTERNAL_PUBLIC_DIRECTORY)
            }
        }

    private fun scanExternalStorageDirectories(): List<String> {
        var bufReader: BufferedReader? = null
        val list = ArrayList<String>()
        try {
            bufReader = BufferedReader(FileReader("/proc/mounts"))
            var line = bufReader.readLine()
            while (line != null) {

                val tokens = StringTokenizer(line, " ")
                val device = tokens.nextToken()
                val mountpoint = tokens.nextToken().replace("\\\\040".toRegex(), " ")
                val type = if (tokens.hasMoreTokens()) tokens.nextToken() else null
                if (list.contains(mountpoint) || typeBL.contains(type) || startsWith(mountBL, mountpoint)) {
                    line = bufReader.readLine()
                    continue
                }
                if (startsWith(deviceWL, device) && (typeWL.contains(type) || startsWith(mountWL, mountpoint))) {
                    val position = containsName(list, mountpoint.getFileNameFromPath())
                    if (position > -1) list.removeAt(position)
                    list.add(mountpoint)
                }
                line = bufReader.readLine()
            }
        } catch (ignored: IOException) {
        } finally {
            close(bufReader)
        }
        // Mount points may point inside a volume (e.g. /storage/1234-ABCD/Android), keep roots.
        val normalized = list.map { volumeRoot(it) }.distinct()
        list.clear()
        list.addAll(normalized)
        list.remove(EXTERNAL_PUBLIC_DIRECTORY)
        // Since Android 8 the app mount namespace no longer exposes removable volumes in
        // /proc/mounts, so ask the framework for one app specific directory per volume and
        // strip the /Android/data/<package>/files suffix to recover the volume root.
        addStorageVolumeRoots(list)
        return list
    }

    /**
     * Recovers the root of every mounted volume (SD card, USB OTG) the app can see.
     *
     * [Context.getExternalFilesDirs] returns one app specific directory per volume, and
     * [StorageManager.getStorageVolumes] gives the authoritative mount point. Both are normalised
     * through [volumeRoot] so `/storage/1234-ABCD/Android/data/<pkg>/files` never leaks into the
     * storage list as an entry called "Android".
     */
    private fun addStorageVolumeRoots(list: MutableList<String>) {
        val ctx = AppContextProvider.appContext
        try {
            ctx.getExternalFilesDirs(null).forEach { dir ->
                dir?.absolutePath?.let { addStorageRoot(list, it) }
            }
        } catch (ignored: Exception) {
        }
        // StorageVolume.getDirectory() only exists since Android 11 (API 30). On Android 8/10 it
        // throws NoSuchMethodError, which is an Error, so no catch clause would save us: it has
        // to be guarded. getExternalFilesDirs() above already covers those versions.
        if (Build.VERSION.SDK_INT >= VERSION_CODES.R) {
            storageVolumes(ctx).forEach { volume ->
                volume.directory?.absolutePath?.let { addStorageRoot(list, it) }
            }
        }
        // Last resort for volumes hidden from this app: /storage holds one entry per mounted
        // volume on every Android version.
        try {
            File("/storage").listFiles()?.forEach { child ->
                val name = child.name
                if (!child.isDirectory || name == "emulated" || name == "self" || name == "sdcard0" || name.startsWith("runtime")) return@forEach
                addStorageRoot(list, child.absolutePath)
            }
        } catch (ignored: Exception) {
        }
    }

    private fun addStorageRoot(list: MutableList<String>, rawPath: String) {
        val root = volumeRoot(rawPath)
        if (root.isEmpty() || root == EXTERNAL_PUBLIC_DIRECTORY) return
        if (list.any { volumeRoot(it) == root }) return
        // The same volume can be exposed as /mnt/media_rw/<uuid> and /storage/<uuid>: keep a
        // single entry, preferring the path the framework gives for browsing.
        val tag = root.substringAfterLast('/')
        val duplicate = list.indexOfFirst { it.substringAfterLast('/') == tag }
        if (duplicate >= 0) {
            if (!list[duplicate].startsWith("/storage/") && root.startsWith("/storage/")) list[duplicate] = root
            return
        }
        list.add(root)
    }

    /**
     * Normalises a path to the mount point of its volume, dropping any app specific suffix:
     * `/storage/1234-ABCD/Android/data/<pkg>/files` becomes `/storage/1234-ABCD`.
     */
    fun volumeRoot(path: String): String {
        val trimmed = path.trimEnd('/')
        val androidIndex = trimmed.indexOf("/Android")
        return (if (androidIndex > 0) trimmed.substring(0, androidIndex) else trimmed).trimEnd('/')
    }

    /** Removable volumes currently reported by the framework. */
    @TargetApi(VERSION_CODES.N)
    fun storageVolumes(ctx: Context = AppContextProvider.appContext): List<StorageVolume> {
        if (Build.VERSION.SDK_INT < VERSION_CODES.N) return emptyList()
        return try {
            (ctx.getSystemService(Context.STORAGE_SERVICE) as? StorageManager)?.storageVolumes.orEmpty()
        } catch (ignored: Exception) {
            emptyList()
        }
    }

    /**
     * Human readable label for every mounted volume, keyed by its normalised mount point,
     * for instance `"SD card (1234-ABCD)"`. Older devices simply return an empty map.
     */
    @TargetApi(VERSION_CODES.N)
    fun storageVolumeLabels(ctx: Context = AppContextProvider.appContext): Map<String, String> {
        val labels = HashMap<String, String>()
        storageVolumes(ctx).forEach { volume ->
            val uuid: String? = volume.uuid?.takeIf { it.isNotBlank() }
            // getDirectory() is API 30+; below that the mount point is /storage/<uuid> on every
            // version, and the uuid is the only thing the framework exposes anyway.
            val directoryPath: String? = if (Build.VERSION.SDK_INT >= VERSION_CODES.R) volume.directory?.absolutePath else null
            val path: String = directoryPath ?: uuid?.let { "/storage/$it" } ?: return@forEach
            val description = try {
                volume.getDescription(ctx)
            } catch (ignored: Exception) {
                null
            }
            if (description == null || description.isBlank()) {
                // No human readable name: the volume uuid is still better than a bare path.
                val label = uuid ?: return@forEach
                labels[volumeRoot(path)] = label
                return@forEach
            }
            labels[volumeRoot(path)] = if (uuid == null || description.contains(uuid)) description else "$description ($uuid)"
        }
        return labels
    }

    private val isManufacturerBannedForMediastyleNotifications: Boolean
        get() {
            if (!AndroidUtil.isMarshMallowOrLater)
                for (manufacturer in noMediaStyleManufacturers)
                    if (Build.MANUFACTURER.lowercase(Locale.getDefault()).contains(manufacturer))
                        return true
            return false
        }

    init {
        val devicesWithoutNavBar = HashSet<String>()
        devicesWithoutNavBar.add("HTC One V")
        devicesWithoutNavBar.add("HTC One S")
        devicesWithoutNavBar.add("HTC One X")
        devicesWithoutNavBar.add("HTC One XL")
        hasNavBar = !devicesWithoutNavBar.contains(Build.MODEL)
        val ctx = AppContextProvider.appContext
        val pm = ctx.packageManager
        hasTsp = pm == null || pm.hasSystemFeature("android.hardware.touchscreen")
        isAndroidTv = pm != null && pm.hasSystemFeature("android.software.leanback")
        watchDevices = isAndroidTv && Build.MODEL.startsWith("Bouygtel")
        isChromeBook = pm != null && pm.hasSystemFeature("org.chromium.arc.device_management")
        isTv = isAndroidTv || !isChromeBook && !hasTsp
        hasPlayServices = pm == null || hasPlayServices(pm)
        hasPiP = pm != null && pm.hasSystemFeature(PackageManager.FEATURE_PICTURE_IN_PICTURE)
        pipAllowed = hasPiP || (hasTsp && !AndroidUtil.isOOrLater)
        val tm = ctx.getSystemService<TelephonyManager>()
        isPhone = tm == null || tm.phoneType != TelephonyManager.PHONE_TYPE_NONE
    }

    fun showInternalStorage() = Build.BRAND !in arrayOf("Swisscom", "BouyguesTelecom") && Build.BOARD != "sprint"

    @TargetApi(VERSION_CODES.HONEYCOMB_MR1)
    fun getCenteredAxis(event: MotionEvent, device: InputDevice, axis: Int): Float {
        val range = device.getMotionRange(axis, event.source)

        // A joystick at rest does not always report an absolute position of
        // (0,0). Use the getFlat() method to determine the range of values
        // bounding the joystick axis center.
        if (range != null) {
            val flat = range.flat
            val value = event.getAxisValue(axis)

            // Ignore axis values that are within the 'flat' region of the
            // joystick axis center.
            if (value.absoluteValue > flat) {
                return value
            }
        }
        return 0f
    }

    fun canUseSystemNightMode(): Boolean {
        return Build.VERSION.SDK_INT > VERSION_CODES.P || Build.VERSION.SDK_INT == VERSION_CODES.P && "samsung" == Build.MANUFACTURER.lowercase(Locale.US)
    }

    private fun hasPlayServices(pm: PackageManager): Boolean {
        try {
            pm.getPackageInfoCompat("com.google.android.gsf", PackageManager.GET_SERVICES)
            return true
        } catch (ignored: PackageManager.NameNotFoundException) {
        }

        return false
    }

    fun isDex(ctx: Context): Boolean {
        if (!AndroidUtil.isNougatOrLater) return false
        return try {
            val config = ctx.resources.configuration
            val configClass = config.javaClass
            configClass.getField("SEM_DESKTOP_MODE_ENABLED").getInt(configClass) == configClass.getField("semDesktopModeEnabled").getInt(config)
        } catch (ignored: Exception) {
            false
        }

    }

    fun hasToForceTV(): Boolean {
        return Build.MODEL in forcedTVModels
    }

    object MediaFolders {
        private val EXTERNAL_PUBLIC_MOVIES_DIRECTORY_FILE: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES)
        private val EXTERNAL_PUBLIC_MUSIC_DIRECTORY_FILE: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC)
        private val EXTERNAL_PUBLIC_PODCAST_DIRECTORY_FILE: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PODCASTS)
        private val EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_FILE: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        private val EXTERNAL_PUBLIC_DCIM_DIRECTORY_FILE: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM)
        private val WHATSAPP_VIDEOS_FILE: File = File("$EXTERNAL_PUBLIC_DIRECTORY/WhatsApp/Media/WhatsApp Video/")
        private val WHATSAPP_VIDEOS_FILE_A11: File = File("$EXTERNAL_PUBLIC_DIRECTORY/Android/media/com.whatsapp/WhatsApp/Media/WhatsApp Video/")
        private val EXTERNAL_PUBLIC_SCREENSHOTS_DIRECTORY: File = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES + "/Screenshots")

        val EXTERNAL_PUBLIC_MOVIES_DIRECTORY_URI = getFolderUri(EXTERNAL_PUBLIC_MOVIES_DIRECTORY_FILE)
        val EXTERNAL_PUBLIC_MUSIC_DIRECTORY_URI = getFolderUri(EXTERNAL_PUBLIC_MUSIC_DIRECTORY_FILE)
        val EXTERNAL_PUBLIC_PODCAST_DIRECTORY_URI = getFolderUri(EXTERNAL_PUBLIC_PODCAST_DIRECTORY_FILE)
        val EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_URI = getFolderUri(EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_FILE)
        val EXTERNAL_PUBLIC_DCIM_DIRECTORY_URI = getFolderUri(EXTERNAL_PUBLIC_DCIM_DIRECTORY_FILE)
        val WHATSAPP_VIDEOS_FILE_URI = getFolderUri(WHATSAPP_VIDEOS_FILE)
        val WHATSAPP_VIDEOS_FILE_URI_A11 = getFolderUri(WHATSAPP_VIDEOS_FILE_A11)
        val EXTERNAL_PUBLIC_SCREENSHOTS_URI_DIRECTORY = getFolderUri(EXTERNAL_PUBLIC_SCREENSHOTS_DIRECTORY)

        private fun getFolderUri(file: File): Uri {
            return try {
                "file://${file.canonicalPath}".toUri()
            } catch (ignored: IOException) {
                "file://${file.path}".toUri()
            }
        }

        fun isOneOfMediaFolders(uri: Uri) = EXTERNAL_PUBLIC_MOVIES_DIRECTORY_URI == uri || EXTERNAL_PUBLIC_MUSIC_DIRECTORY_URI == uri || EXTERNAL_PUBLIC_PODCAST_DIRECTORY_URI == uri || EXTERNAL_PUBLIC_DOWNLOAD_DIRECTORY_URI == uri || EXTERNAL_PUBLIC_DCIM_DIRECTORY_URI == uri || WHATSAPP_VIDEOS_FILE == uri
    }

    fun close(closeable: Closeable?): Boolean {
        if (closeable != null)
            try {
                closeable.close()
                return true
            } catch (e: IOException) {
            }

        return false
    }
}

package com.nuvio.app.features.device

import android.Manifest
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.provider.Settings
import android.util.Log
import android.util.LruCache
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder

internal actual object DeviceVideoPlatform {
    private const val TAG = "SamplayDeviceVideos"
    private const val PREFS = "samplay_device_videos"
    private const val KEY_STATE = "state"

    private var appContext: Context? = null
    private var preferences: SharedPreferences? = null

    fun initialize(context: Context) {
        val app = context.applicationContext
        appContext = app
        preferences = app.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    }

    actual val isSupported: Boolean = true

    /** Android 14 lets the viewer allow only some videos; those count too. */
    fun videoPermissions(): Array<String> = when {
        Build.VERSION.SDK_INT >= 34 -> arrayOf(
            Manifest.permission.READ_MEDIA_VIDEO,
            Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
        )
        Build.VERSION.SDK_INT >= 33 -> arrayOf(Manifest.permission.READ_MEDIA_VIDEO)
        else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
    }

    actual fun hasPermission(): Boolean {
        val context = appContext ?: return false
        return videoPermissions().any {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }
    }

    actual fun isPartialAccess(): Boolean {
        val context = appContext ?: return false
        if (Build.VERSION.SDK_INT < 34) return false
        fun granted(permission: String) =
            ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
        return granted(Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED) &&
            !granted(Manifest.permission.READ_MEDIA_VIDEO)
    }

    actual suspend fun queryVideos(): List<DeviceVideo> = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext emptyList()
        if (!hasPermission()) return@withContext emptyList()
        readVideos(context, selection = null, selectionArgs = null)
    }

    private fun readVideos(context: Context, selection: String?, selectionArgs: Array<String>?): List<DeviceVideo> {
        val collection = if (Build.VERSION.SDK_INT >= 29) {
            MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL)
        } else {
            MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        }
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.VideoColumns.BUCKET_ID,
            MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME,
            MediaStore.Video.VideoColumns.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
        )
        val videos = ArrayList<DeviceVideo>()
        runCatching {
            context.contentResolver.query(
                collection,
                projection,
                selection,
                selectionArgs,
                "${MediaStore.Video.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
                val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
                val bucketIdColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.BUCKET_ID)
                val bucketNameColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.BUCKET_DISPLAY_NAME)
                val durationColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.VideoColumns.DURATION)
                val sizeColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
                val addedColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
                val widthColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
                val heightColumn = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
                while (cursor.moveToNext()) {
                    val id = cursor.getLong(idColumn)
                    val name = cursor.getString(nameColumn)?.takeIf(String::isNotBlank) ?: continue
                    videos += DeviceVideo(
                        uri = ContentUris.withAppendedId(collection, id).toString(),
                        name = name,
                        folderId = cursor.getString(bucketIdColumn).orEmpty(),
                        folderName = cursor.getString(bucketNameColumn).orEmpty(),
                        durationMs = cursor.getLong(durationColumn),
                        sizeBytes = cursor.getLong(sizeColumn),
                        addedEpochSec = cursor.getLong(addedColumn),
                        width = cursor.getInt(widthColumn),
                        height = cursor.getInt(heightColumn),
                        mediaId = id,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "Could not read the device's videos", it) }
        return videos
    }

    actual suspend fun canRead(uri: String): Boolean = withContext(Dispatchers.IO) {
        val context = appContext ?: return@withContext false
        val parsed = Uri.parse(uri)
        when (parsed.scheme?.lowercase()) {
            "file" -> parsed.path?.let { File(it).canRead() } == true
            "content" -> runCatching {
                context.contentResolver.openFileDescriptor(parsed, "r")?.use { true } ?: false
            }.getOrDefault(false)
            else -> false
        }
    }

    /** Name and size of one file (from «Open file» or another app's «Open with»). */
    fun describe(context: Context, uri: Uri): DeviceVideo {
        libraryVideo(context, uri)?.let { return it }
        val (name, size) = nameAndSize(context, uri)
        return DeviceVideo(
            uri = uri.toString(),
            name = name?.takeIf(String::isNotBlank) ?: uri.lastPathSegment?.substringAfterLast('/').orEmpty().ifBlank { "Video" },
            sizeBytes = size,
        )
    }

    private fun nameAndSize(context: Context, uri: Uri): Pair<String?, Long> {
        var name: String? = null
        var size = 0L
        when (uri.scheme?.lowercase()) {
            "file" -> uri.path?.let(::File)?.let {
                name = it.name
                size = it.length()
            }
            else -> runCatching {
                context.contentResolver.query(
                    uri,
                    arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE),
                    null,
                    null,
                    null,
                )?.use { cursor ->
                    if (cursor.moveToFirst()) {
                        val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                        val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                        if (nameColumn >= 0) name = cursor.getString(nameColumn)
                        if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) size = cursor.getLong(sizeColumn)
                    }
                }
            }
        }
        return name to size
    }

    /**
     * The same file as the library lists it, so a video keeps one resume point (and its folder)
     * whether it is opened from the library, «Open file» or another app.
     */
    private fun libraryVideo(context: Context, uri: Uri): DeviceVideo? {
        if (!hasPermission()) return null
        val scheme = uri.scheme?.lowercase()
        if (scheme != "content" && scheme != "file") return null
        val mediaUri = when {
            scheme != "content" -> null
            uri.authority == MediaStore.AUTHORITY -> uri
            Build.VERSION.SDK_INT >= 29 && DocumentsContract.isDocumentUri(context, uri) ->
                runCatching { MediaStore.getMediaUri(context, uri) }.getOrNull()
            else -> null
        }
        val video = if (mediaUri != null) {
            val id = runCatching { ContentUris.parseId(mediaUri) }.getOrDefault(-1L)
            if (id < 0L) return null
            readVideos(context, "${MediaStore.Video.Media._ID} = ?", arrayOf(id.toString())).firstOrNull()
        } else {
            sameFileInLibrary(context, uri)
        } ?: return null
        val readable = runCatching {
            context.contentResolver.openFileDescriptor(Uri.parse(video.uri), "r")?.use { true } ?: false
        }.getOrDefault(false)
        return video.takeIf { readable }
    }

    /**
     * Nuvio RS (2.0.3): a file manager's own link (FileProvider) or a file:// path names no library
     * entry, so a series episode opened that way had no folder and no next episode. The library's
     * file with the same path, or else the one file with the same name and size, is that file.
     */
    private fun sameFileInLibrary(context: Context, uri: Uri): DeviceVideo? {
        if (uri.scheme?.lowercase() == "file") {
            val path = uri.path?.takeIf(String::isNotBlank)
            if (path != null) {
                @Suppress("DEPRECATION")
                readVideos(context, "${MediaStore.MediaColumns.DATA} = ?", arrayOf(path)).firstOrNull()?.let { return it }
            }
        }
        val (name, size) = nameAndSize(context, uri)
        if (name.isNullOrBlank()) return null
        return readVideos(context, "${MediaStore.Video.Media.DISPLAY_NAME} = ?", arrayOf(name))
            .filter { size <= 0L || it.sizeBytes == size }
            .singleOrNull()
    }

    /**
     * SAMplay 2.0.3: the OpenSubtitles hash of a file on this device (its size plus the 64-bit
     * little-endian sums of its first and last 64 KiB), or null when it cannot be read.
     */
    fun localVideoHash(uri: String): Pair<String, Long>? {
        val context = appContext ?: return null
        return runCatching {
            val descriptor = context.contentResolver.openFileDescriptor(Uri.parse(uri), "r") ?: return null
            ParcelFileDescriptor.AutoCloseInputStream(descriptor).use { input ->
                val channel = input.channel
                val size = channel.size()
                if (size < HASH_CHUNK * 2) return null
                fun chunkSum(offset: Long): Long {
                    val buffer = ByteBuffer.allocate(HASH_CHUNK.toInt()).order(ByteOrder.LITTLE_ENDIAN)
                    var position = offset
                    while (buffer.hasRemaining()) {
                        val read = channel.read(buffer, position)
                        if (read < 0) error("Short read")
                        position += read
                    }
                    buffer.flip()
                    var sum = 0L
                    while (buffer.remaining() >= 8) sum += buffer.long
                    return sum
                }
                "%016x".format(size + chunkSum(0L) + chunkSum(size - HASH_CHUNK)) to size
            }
        }.getOrNull()
    }

    private const val HASH_CHUNK = 65_536L

    actual fun loadState(): String? = preferences?.getString(KEY_STATE, null)

    actual fun saveState(payload: String) {
        preferences?.edit()?.putString(KEY_STATE, payload)?.apply()
    }

    // Thumbnails: kept in memory, read a few at a time so scrolling stays smooth.
    private val thumbnailDispatcher = Dispatchers.IO.limitedParallelism(3)
    private val thumbnails = object : LruCache<String, Bitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: Bitmap): Int = value.byteCount
    }
    private val missingThumbnails = java.util.Collections.newSetFromMap(java.util.concurrent.ConcurrentHashMap<String, Boolean>())

    fun cachedThumbnail(uri: String): Bitmap? = thumbnails.get(uri)

    suspend fun loadThumbnail(context: Context, video: DeviceVideo): Bitmap? {
        thumbnails.get(video.uri)?.let { return it }
        if (video.uri in missingThumbnails) return null
        return withContext(thumbnailDispatcher) {
            thumbnails.get(video.uri)?.let { return@withContext it }
            val bitmap = readThumbnail(context, video)
            if (bitmap != null) thumbnails.put(video.uri, bitmap) else missingThumbnails += video.uri
            bitmap
        }
    }

    private fun readThumbnail(context: Context, video: DeviceVideo): Bitmap? {
        val uri = Uri.parse(video.uri)
        val system = systemThumbnail(context, video, uri)
        if (system != null && !system.isNearlyBlack()) return system
        // No system picture (a file another app shared), or the system took the video's black
        // opening frame: take a frame from further in.
        val frame = frameFromVideo(context, uri)
        return when {
            frame == null -> system
            system != null && frame.isNearlyBlack() -> system
            else -> frame
        }
    }

    private fun systemThumbnail(context: Context, video: DeviceVideo, uri: Uri): Bitmap? {
        if (Build.VERSION.SDK_INT >= 29) {
            return runCatching { context.contentResolver.loadThumbnail(uri, Size(384, 216), null) }.getOrNull()
        }
        if (video.mediaId < 0) return null
        @Suppress("DEPRECATION")
        return runCatching {
            MediaStore.Video.Thumbnails.getThumbnail(
                context.contentResolver,
                video.mediaId,
                MediaStore.Video.Thumbnails.MINI_KIND,
                null,
            )
        }.getOrNull()
    }

    /** A frame about a tenth of the way in (at most a minute); a later one when that is black too. */
    private fun frameFromVideo(context: Context, uri: Uri): Bitmap? = runCatching {
        MediaMetadataRetriever().run {
            try {
                setDataSource(context, uri)
                val durationMs = extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
                val first = frameAt((durationMs * 100L).coerceIn(1_000_000L, 60_000_000L))
                if (first != null && !first.isNearlyBlack()) return@run first
                val laterUs = (durationMs * 250L).coerceIn(2_000_000L, 300_000_000L)
                val later = if (durationMs * 1000L > laterUs) frameAt(laterUs) else null
                if (later != null && (first == null || !later.isNearlyBlack())) later else first
            } finally {
                release()
            }
        }
    }.getOrNull()

    private fun MediaMetadataRetriever.frameAt(atUs: Long): Bitmap? = runCatching {
        if (Build.VERSION.SDK_INT >= 27) {
            getScaledFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC, 384, 216)
        } else {
            getFrameAtTime(atUs, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
        }
    }.getOrNull()

    /** True for a frame that is black, or nearly so (a fade-in, a studio card's background). */
    private fun Bitmap.isNearlyBlack(): Boolean = runCatching {
        if (Build.VERSION.SDK_INT >= 26 && config == Bitmap.Config.HARDWARE) return@runCatching false
        if (width <= 0 || height <= 0) return@runCatching false
        var total = 0L
        var count = 0
        for (row in 0 until SAMPLE_ROWS) {
            val y = ((row * 2 + 1) * height / (SAMPLE_ROWS * 2)).coerceIn(0, height - 1)
            for (column in 0 until SAMPLE_COLUMNS) {
                val x = ((column * 2 + 1) * width / (SAMPLE_COLUMNS * 2)).coerceIn(0, width - 1)
                val pixel = getPixel(x, y)
                val red = (pixel shr 16) and 0xFF
                val green = (pixel shr 8) and 0xFF
                val blue = pixel and 0xFF
                total += (red * 299 + green * 587 + blue * 114) / 1000
                count++
            }
        }
        count > 0 && total / count < DARK_LUMA
    }.getOrDefault(false)

    private const val SAMPLE_ROWS = 8
    private const val SAMPLE_COLUMNS = 12
    private const val DARK_LUMA = 14L
}

@Composable
internal actual fun rememberDeviceVideoAccess(): DeviceVideoAccess {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        DeviceVideoRepository.ensureVideosLoaded()
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        DeviceVideoRepository.refresh()
    }
    val fileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        // Kept, so «Continue» can open the file again after the app restarts.
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        scope.launch {
            val video = withContext(Dispatchers.IO) { DeviceVideoPlatform.describe(context, uri) }
            if (video.uri != uri.toString()) {
                // Opened through its library entry, so the picker's grant is not needed.
                runCatching {
                    context.contentResolver.releasePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
            }
            DeviceVideoRepository.requestPlay(video)
        }
    }
    return remember(permissionLauncher, fileLauncher, context) {
        DeviceVideoAccess(
            requestPermission = {
                runCatching { permissionLauncher.launch(DeviceVideoPlatform.videoPermissions()) }
            },
            openSettings = {
                runCatching {
                    context.startActivity(
                        Intent(
                            Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            Uri.parse("package:${context.packageName}"),
                        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                }
            },
            openFile = {
                runCatching { fileLauncher.launch(arrayOf("video/*")) }
            },
        )
    }
}

@Composable
internal actual fun DeviceVideoThumbnail(video: DeviceVideo, modifier: Modifier) {
    val context = LocalContext.current
    val bitmap by produceState<ImageBitmap?>(
        initialValue = DeviceVideoPlatform.cachedThumbnail(video.uri)?.asImageBitmap(),
        video.uri,
    ) {
        if (value == null) value = DeviceVideoPlatform.loadThumbnail(context, video)?.asImageBitmap()
    }
    bitmap?.let {
        Image(
            bitmap = it,
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    }
}

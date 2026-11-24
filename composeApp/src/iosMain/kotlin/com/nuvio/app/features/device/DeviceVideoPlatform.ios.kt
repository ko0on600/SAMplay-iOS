package com.nuvio.app.features.device

import androidx.compose.foundation.Image
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.nuvio.app.samplay.IosFiles
import com.nuvio.app.samplay.IosPickers
import com.nuvio.app.samplay.IosPrefs
import com.nuvio.app.samplay.toByteArray
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.IO
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetImageGenerator
import platform.AVFoundation.AVURLAsset
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGSizeMake
import platform.CoreMedia.CMTimeMakeWithSeconds
import platform.Foundation.NSFileManager
import platform.Foundation.NSNumber
import platform.Foundation.NSSortDescriptor
import platform.Foundation.NSURL
import platform.Foundation.timeIntervalSince1970
import platform.Foundation.valueForKey
import platform.Photos.PHAccessLevelReadWrite
import platform.Photos.PHAsset
import platform.Photos.PHAssetCollection
import platform.Photos.PHAssetCollectionSubtypeAny
import platform.Photos.PHAssetCollectionTypeAlbum
import platform.Photos.PHAssetMediaTypeVideo
import platform.Photos.PHAssetResource
import platform.Photos.PHAssetResourceManager
import platform.Photos.PHAssetResourceRequestOptions
import platform.Photos.PHAuthorizationStatusAuthorized
import platform.Photos.PHAuthorizationStatusLimited
import platform.Photos.PHFetchOptions
import platform.Photos.PHImageContentModeAspectFill
import platform.Photos.PHImageManager
import platform.Photos.PHImageRequestOptions
import platform.Photos.PHImageRequestOptionsDeliveryModeHighQualityFormat
import platform.Photos.PHPhotoLibrary
import platform.Photos.presentLimitedLibraryPickerFromViewController
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationOpenSettingsURLString
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.DISPATCH_TIME_FOREVER
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue
import platform.darwin.dispatch_semaphore_create
import platform.darwin.dispatch_semaphore_signal
import platform.darwin.dispatch_semaphore_wait

/**
 * SAMplay iOS: the device library is the Photos app's videos (by album) plus the videos in the
 * app's own folder in the Files app ("On My iPhone › SAMplay", folders included). «Open file»
 * picks any video in Files; «Open in SAMplay» from another app plays it at once.
 */
@OptIn(ExperimentalForeignApi::class)
internal actual object DeviceVideoPlatform {
    const val PHOTOS_SCHEME = "ph://"
    private val prefs = IosPrefs("device_videos")
    private val videoExtensions = setOf(
        "mp4", "m4v", "mkv", "webm", "avi", "mov", "wmv", "flv", "ts", "m2ts", "mts", "3gp", "mpg", "mpeg", "ogv",
    )

    actual val isSupported: Boolean = true

    private fun status() = PHPhotoLibrary.authorizationStatusForAccessLevel(PHAccessLevelReadWrite)

    actual fun hasPermission(): Boolean = status().let { it == PHAuthorizationStatusAuthorized || it == PHAuthorizationStatusLimited }

    actual fun isPartialAccess(): Boolean = status() == PHAuthorizationStatusLimited

    actual suspend fun queryVideos(): List<DeviceVideo> = withContext(Dispatchers.IO) {
        val out = ArrayList<DeviceVideo>()
        runCatching { out += documentVideos() }
        if (hasPermission()) runCatching { out += photoVideos() }
        out.sortedByDescending { it.addedEpochSec }
    }

    private fun isVideoName(name: String) = name.substringAfterLast('.', "").lowercase() in videoExtensions

    /** Videos the viewer put in the app's Documents folder (the Files app shows it). */
    private fun documentVideos(): List<DeviceVideo> {
        val root = IosFiles.documentsDir
        val fm = NSFileManager.defaultManager
        val paths = fm.subpathsOfDirectoryAtPath(root, null)?.map { it.toString() }.orEmpty()
        return paths.filter { isVideoName(it) && !it.startsWith(".") }.mapNotNull { rel ->
            val path = "$root/$rel"
            val file = com.nuvio.app.samplay.File(path)
            if (!file.isFile) return@mapNotNull null
            val folder = rel.substringBeforeLast('/', "")
            DeviceVideo(
                uri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path",
                name = rel.substringAfterLast('/'),
                folderId = "files:$folder",
                folderName = if (folder.isEmpty()) "SAMplay" else folder.substringAfterLast('/'),
                durationMs = fileDurationMs(path),
                sizeBytes = file.length(),
                addedEpochSec = file.lastModified() / 1000,
            )
        }
    }

    private fun fileDurationMs(path: String): Long = runCatching {
        val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
        val d = platform.CoreMedia.CMTimeGetSeconds(asset.duration)
        if (d.isFinite() && d > 0) (d * 1000).toLong() else 0L
    }.getOrDefault(0L)

    private fun fetchOptions() = PHFetchOptions().apply {
        sortDescriptors = listOf(NSSortDescriptor.sortDescriptorWithKey("creationDate", ascending = false))
    }

    private fun photoVideos(): List<DeviceVideo> {
        // Each video goes in the first album (made by the viewer) that has it, else "Recents".
        val albumOf = HashMap<String, Pair<String, String>>()
        val albums = PHAssetCollection.fetchAssetCollectionsWithType(PHAssetCollectionTypeAlbum, PHAssetCollectionSubtypeAny, null)
        for (i in 0 until albums.count.toInt()) {
            val album = albums.objectAtIndex(i.toULong()) as? PHAssetCollection ?: continue
            val assets = PHAsset.fetchAssetsInAssetCollection(album, null)
            for (j in 0 until assets.count.toInt()) {
                val asset = assets.objectAtIndex(j.toULong()) as? PHAsset ?: continue
                if (asset.mediaType != PHAssetMediaTypeVideo) continue
                albumOf.getOrPut(asset.localIdentifier) { ("album:" + album.localIdentifier) to (album.localizedTitle ?: "Album") }
            }
        }
        val result = PHAsset.fetchAssetsWithMediaType(PHAssetMediaTypeVideo, fetchOptions())
        val out = ArrayList<DeviceVideo>(result.count.toInt())
        for (i in 0 until result.count.toInt()) {
            val asset = result.objectAtIndex(i.toULong()) as? PHAsset ?: continue
            val resource = PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource
            val name = resource?.originalFilename ?: "Video ${i + 1}"
            val size = runCatching { (resource?.valueForKey("fileSize") as? NSNumber)?.longLongValue }.getOrNull() ?: 0L
            val (folderId, folderName) = albumOf[asset.localIdentifier] ?: ("photos:recents" to "Photos")
            out += DeviceVideo(
                uri = PHOTOS_SCHEME + asset.localIdentifier,
                name = name,
                folderId = folderId,
                folderName = folderName,
                durationMs = (asset.duration * 1000).toLong(),
                sizeBytes = size,
                addedEpochSec = asset.creationDate?.timeIntervalSince1970?.toLong() ?: 0L,
                width = asset.pixelWidth.toInt(),
                height = asset.pixelHeight.toInt(),
            )
        }
        return out
    }

    private fun asset(uri: String): PHAsset? {
        if (!uri.startsWith(PHOTOS_SCHEME)) return null
        val r = PHAsset.fetchAssetsWithLocalIdentifiers(listOf(uri.removePrefix(PHOTOS_SCHEME)), null)
        return if (r.count > 0u) r.firstObject as? PHAsset else null
    }

    actual suspend fun canRead(uri: String): Boolean = withContext(Dispatchers.IO) {
        when {
            uri.startsWith(PHOTOS_SCHEME) -> hasPermission() && asset(uri) != null
            uri.startsWith("file:") -> NSURL.URLWithString(uri)?.path?.let { NSFileManager.defaultManager.isReadableFileAtPath(it) } == true
            else -> false
        }
    }

    actual fun loadState(): String? = prefs.getString("state")

    actual fun saveState(payload: String) = prefs.putString("state", payload)

    /**
     * The file mpv plays for a Photos video: the original in place when it can be read, else a
     * copy in the cache (made once, from Photos' own export, iCloud included).
     */
    fun playablePath(uri: String): String? {
        val asset = asset(uri) ?: return null
        val resource = PHAssetResource.assetResourcesForAsset(asset).firstOrNull() as? PHAssetResource ?: return null
        val ext = resource.originalFilename.substringAfterLast('.', "mov")
        val dir = IosFiles.cacheDir + "/device_videos"
        IosFiles.ensureDir(dir)
        val target = dir + "/" + asset.localIdentifier.replace('/', '_') + "." + ext
        if (IosFiles.exists(target) && com.nuvio.app.samplay.File(target).length() > 0) return target
        val semaphore = dispatch_semaphore_create(0)
        var ok = false
        val options = PHAssetResourceRequestOptions().apply { networkAccessAllowed = true }
        PHAssetResourceManager.defaultManager().writeDataForAssetResource(
            resource, toFile = NSURL.fileURLWithPath(target), options = options,
        ) { error ->
            ok = error == null
            dispatch_semaphore_signal(semaphore)
        }
        dispatch_semaphore_wait(semaphore, DISPATCH_TIME_FOREVER)
        return if (ok) target else null.also { IosFiles.delete(target) }
    }

    /** Called by the player: a URL mpv can open for [url] (Photos videos become files). */
    suspend fun resolveForPlayback(url: String): String {
        if (!url.startsWith(PHOTOS_SCHEME)) return url
        return withContext(Dispatchers.IO) {
            playablePath(url)?.let { NSURL.fileURLWithPath(it).absoluteString } ?: url
        }
    }

    /** A file another app opened with SAMplay (or the Files picker): kept in the app's folder. */
    fun describeFile(path: String): DeviceVideo {
        val file = com.nuvio.app.samplay.File(path)
        return DeviceVideo(
            uri = NSURL.fileURLWithPath(path).absoluteString ?: "file://$path",
            name = file.name.ifBlank { "Video" },
            sizeBytes = file.length(),
            durationMs = fileDurationMs(path),
        )
    }

    fun isVideoFile(path: String) = isVideoName(path)

    /** Moves a picked/received file into Documents/Opened (so «Continue» works after a restart). */
    fun keep(path: String): String {
        if (path.startsWith(IosFiles.documentsDir)) return path
        val dir = IosFiles.documentsDir + "/Opened"
        IosFiles.ensureDir(dir)
        var target = dir + "/" + path.substringAfterLast('/')
        if (IosFiles.exists(target)) {
            val stem = target.substringBeforeLast('.')
            val ext = target.substringAfterLast('.', "")
            target = "$stem-${com.nuvio.app.samplay.nowMs()}.$ext"
        }
        val fm = NSFileManager.defaultManager
        val moved = fm.moveItemAtPath(path, target, null) || fm.copyItemAtPath(path, target, null)
        return if (moved) target else path
    }

    // region thumbnails
    private val thumbs = HashMap<String, ImageBitmap>()

    fun cachedThumbnail(uri: String): ImageBitmap? = thumbs[uri]

    suspend fun loadThumbnail(video: DeviceVideo): ImageBitmap? = withContext(Dispatchers.IO) {
        thumbs[video.uri]?.let { return@withContext it }
        val bytes = runCatching {
            val image = if (video.uri.startsWith(PHOTOS_SCHEME)) photoThumb(video.uri) else fileThumb(video.uri)
            image?.let { UIImageJPEGRepresentation(it, 0.8)?.toByteArray() }
        }.getOrNull() ?: return@withContext null
        val bitmap = runCatching { org.jetbrains.skia.Image.makeFromEncoded(bytes).toComposeImageBitmap() }.getOrNull()
        if (bitmap != null) {
            if (thumbs.size > 300) thumbs.clear()
            thumbs[video.uri] = bitmap
        }
        bitmap
    }

    private fun photoThumb(uri: String): UIImage? {
        val asset = asset(uri) ?: return null
        var out: UIImage? = null
        val options = PHImageRequestOptions().apply {
            synchronous = true
            networkAccessAllowed = true
            deliveryMode = PHImageRequestOptionsDeliveryModeHighQualityFormat
        }
        PHImageManager.defaultManager().requestImageForAsset(
            asset, targetSize = CGSizeMake(480.0, 270.0), contentMode = PHImageContentModeAspectFill, options = options,
        ) { image, _ -> out = image }
        return out
    }

    private fun fileThumb(uri: String): UIImage? {
        val url = NSURL.URLWithString(uri) ?: return null
        val generator = AVAssetImageGenerator(asset = AVURLAsset(uRL = url, options = null))
        generator.appliesPreferredTrackTransform = true
        generator.maximumSize = CGSizeMake(480.0, 270.0)
        val cg = generator.copyCGImageAtTime(CMTimeMakeWithSeconds(5.0, 600), null, null)
            ?: generator.copyCGImageAtTime(CMTimeMakeWithSeconds(0.0, 600), null, null)
            ?: return null
        return try { UIImage.imageWithCGImage(cg) } finally { CGImageRelease(cg) }
    }
    // endregion
}

/** «Open in SAMplay» from the Files app or another app (Swift calls this with the file URL). */
fun samplayOpenIncomingFile(url: String): Boolean {
    val path = NSURL.URLWithString(url)?.path ?: return false
    if (!DeviceVideoPlatform.isVideoFile(path)) return false
    val accessed = NSURL.URLWithString(url)?.startAccessingSecurityScopedResource() == true
    val kept = try {
        DeviceVideoPlatform.keep(path)
    } finally {
        if (accessed) NSURL.URLWithString(url)?.stopAccessingSecurityScopedResource()
    }
    DeviceVideoRepository.requestPlay(DeviceVideoPlatform.describeFile(kept))
    return true
}

@Composable
internal actual fun rememberDeviceVideoAccess(): DeviceVideoAccess {
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        DeviceVideoRepository.ensureVideosLoaded()
    }
    return remember {
        DeviceVideoAccess(
            requestPermission = {
                if (DeviceVideoPlatform.isPartialAccess()) {
                    IosPickers.topViewController()?.let {
                        PHPhotoLibrary.sharedPhotoLibrary().presentLimitedLibraryPickerFromViewController(it)
                    }
                    DeviceVideoRepository.refresh()
                } else {
                    PHPhotoLibrary.requestAuthorizationForAccessLevel(PHAccessLevelReadWrite) { _ ->
                        dispatch_async(dispatch_get_main_queue()) { DeviceVideoRepository.refresh() }
                    }
                }
            },
            openSettings = {
                NSURL.URLWithString(UIApplicationOpenSettingsURLString)?.let {
                    UIApplication.sharedApplication.openURL(it, emptyMap<Any?, Any>(), null)
                }
            },
            openFile = {
                IosPickers.pickFile(listOf("public.movie", "public.audiovisual-content", "org.matroska.mkv", "public.item")) { path, _ ->
                    if (path != null) {
                        val kept = DeviceVideoPlatform.keep(path)
                        DeviceVideoRepository.requestPlay(DeviceVideoPlatform.describeFile(kept))
                    }
                }
            },
        )
    }
}

@Composable
internal actual fun DeviceVideoThumbnail(video: DeviceVideo, modifier: Modifier) {
    val bitmap by produceState(DeviceVideoPlatform.cachedThumbnail(video.uri), video.uri) {
        if (value == null) value = DeviceVideoPlatform.loadThumbnail(video)
    }
    bitmap?.let {
        Image(bitmap = it, contentDescription = null, modifier = modifier, contentScale = ContentScale.Crop)
    }
}

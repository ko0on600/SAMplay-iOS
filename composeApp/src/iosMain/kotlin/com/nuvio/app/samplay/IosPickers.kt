package com.nuvio.app.samplay

import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.Foundation.NSUUID
import platform.UIKit.UIApplication
import platform.UIKit.UIDocumentPickerDelegateProtocol
import platform.UIKit.UIDocumentPickerMode
import platform.UIKit.UIDocumentPickerViewController
import platform.UIKit.UIImagePickerController
import platform.UIKit.UIImagePickerControllerDelegateProtocol
import platform.UIKit.UIImagePickerControllerSourceType
import platform.UIKit.UINavigationControllerDelegateProtocol
import platform.UIKit.UIViewController
import platform.UIKit.UIWindow
import platform.UIKit.UIWindowScene
import platform.UIKit.UIImagePickerControllerImageURL
import platform.UIKit.UIImagePickerControllerOriginalImage
import platform.UIKit.UIImage
import platform.UIKit.UIImageJPEGRepresentation
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/** SAMplay iOS: system pickers (Files, Photos) and "save to Files", for the Android document launchers. */
@OptIn(ExperimentalForeignApi::class)
object IosPickers {
    // UIKit keeps its delegates weakly: hold the one in use.
    private var documentDelegate: NSObject? = null
    private var imageDelegate: NSObject? = null

    fun topViewController(): UIViewController? {
        val windows = UIApplication.sharedApplication.connectedScenes
            .mapNotNull { it as? UIWindowScene }
            .flatMap { scene -> scene.windows.mapNotNull { it as? UIWindow } }
        var controller = (windows.firstOrNull { it.isKeyWindow() } ?: windows.firstOrNull())?.rootViewController
            ?: UIApplication.sharedApplication.keyWindow?.rootViewController
        while (controller?.presentedViewController != null) controller = controller.presentedViewController
        return controller
    }

    private fun onMain(block: () -> Unit) = dispatch_async(dispatch_get_main_queue()) { block() }

    /**
     * Lets the user pick one file (any type by default). [onPicked] gets a private copy's path and
     * the original file name, or null when cancelled. Runs on the main thread.
     */
    fun pickFile(types: List<String> = listOf("public.item"), onPicked: (path: String?, name: String?) -> Unit) = onMain {
        val presenter = topViewController() ?: return@onMain onPicked(null, null)
        val picker = UIDocumentPickerViewController(documentTypes = types, inMode = UIDocumentPickerMode.UIDocumentPickerModeImport)
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                documentDelegate = null
                val url = didPickDocumentsAtURLs.firstOrNull() as? NSURL ?: return onPicked(null, null)
                val name = url.lastPathComponent ?: "file"
                val accessed = url.startAccessingSecurityScopedResource()
                try {
                    val dir = NSTemporaryDirectory().trimEnd('/') + "/picked-" + NSUUID().UUIDString
                    NSFileManager.defaultManager.createDirectoryAtPath(dir, true, null, null)
                    val target = "$dir/$name"
                    val ok = NSFileManager.defaultManager.copyItemAtURL(url, NSURL.fileURLWithPath(target), null)
                    onPicked(if (ok) target else url.path, name)
                } finally {
                    if (accessed) url.stopAccessingSecurityScopedResource()
                }
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                documentDelegate = null
                onPicked(null, null)
            }
        }
        documentDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /**
     * "Save as": the Files app's folder picker exports the file at [path] (named as it is).
     * [onDone] gets true when it was saved, false when cancelled or it failed.
     */
    fun exportFile(path: String, onDone: (Boolean) -> Unit) = onMain {
        val presenter = topViewController() ?: return@onMain onDone(false)
        val picker = UIDocumentPickerViewController(
            URLs = listOf(NSURL.fileURLWithPath(path)),
            inMode = UIDocumentPickerMode.UIDocumentPickerModeExportToService,
        )
        val delegate = object : NSObject(), UIDocumentPickerDelegateProtocol {
            override fun documentPicker(controller: UIDocumentPickerViewController, didPickDocumentsAtURLs: List<*>) {
                documentDelegate = null
                onDone(didPickDocumentsAtURLs.isNotEmpty())
            }

            override fun documentPickerWasCancelled(controller: UIDocumentPickerViewController) {
                documentDelegate = null
                onDone(false)
            }
        }
        documentDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** Picks a photo from the library; [onPicked] gets its JPEG/original bytes, or null. */
    fun pickImage(onPicked: (ByteArray?) -> Unit) = onMain {
        val presenter = topViewController() ?: return@onMain onPicked(null)
        val picker = UIImagePickerController()
        picker.sourceType = UIImagePickerControllerSourceType.UIImagePickerControllerSourceTypePhotoLibrary
        val delegate = object : NSObject(), UIImagePickerControllerDelegateProtocol, UINavigationControllerDelegateProtocol {
            override fun imagePickerController(picker: UIImagePickerController, didFinishPickingMediaWithInfo: Map<Any?, *>) {
                imageDelegate = null
                picker.dismissViewControllerAnimated(true, null)
                val bytes = runCatching {
                    (didFinishPickingMediaWithInfo[UIImagePickerControllerImageURL] as? NSURL)?.path?.let { IosFiles.readBytes(it) }
                        ?: (didFinishPickingMediaWithInfo[UIImagePickerControllerOriginalImage] as? UIImage)
                            ?.let { UIImageJPEGRepresentation(it, 0.92) }?.toByteArray()
                }.getOrNull()
                onPicked(bytes)
            }

            override fun imagePickerControllerDidCancel(picker: UIImagePickerController) {
                imageDelegate = null
                picker.dismissViewControllerAnimated(true, null)
                onPicked(null)
            }
        }
        imageDelegate = delegate
        picker.delegate = delegate
        presenter.presentViewController(picker, animated = true, completion = null)
    }

    /** Shows a short message (the Android Toast). */
    fun toast(message: String) {
        com.nuvio.app.core.ui.NuvioToastController.show(message)
    }
}

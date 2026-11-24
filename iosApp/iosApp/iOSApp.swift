import SwiftUI
import ComposeApp

@main
struct iOSApp: App {
    @UIApplicationDelegateAdaptor(OrientationLockAppDelegate.self) private var appDelegate

    var body: some Scene {
        WindowGroup {
            ContentView()
                .preferredColorScheme(.dark)
                .onOpenURL { url in
                    // SAMplay: «Open in SAMplay» for a video from Files or another app.
                    if url.isFileURL && DeviceVideoPlatform_iosKt.samplayOpenIncomingFile(url: url.absoluteString) {
                        return
                    }
                    AppUrlBridgeKt.handleAppUrl(url: url.absoluteString)
                }
        }
    }
}

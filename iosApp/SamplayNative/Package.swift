// swift-tools-version:5.8
// SAMplay iOS: whisper.cpp for "Create with AI" (listening). The xcframework is fetched by
// scripts/prepare-ios-dependencies.sh (whisper.cpp release b5907, checksum checked).
import PackageDescription

let package = Package(
    name: "SamplayNative",
    platforms: [.iOS("16.4")],
    products: [
        .library(name: "SamplayWhisper", targets: ["whisper"]),
    ],
    targets: [
        .binaryTarget(name: "whisper", path: "whisper.xcframework"),
    ]
)

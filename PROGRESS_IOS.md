# SAMplay iOS port — progress

Source: SAMplay 2.0.7 (Android, vc 173), Kotlin Multiplatform (Compose Multiplatform UI shared by
Android and iOS). The iOS app = the same shared code (`composeApp`) + `iosApp` (SwiftUI host, MPVKit
player), so UI and logic are the Android ones. Android-only SAMplay parts get iOS actuals in
`composeApp/src/iosMain` (androidMain untouched). Shims for Android APIs: `iosMain/.../samplay/`.

- Bundle id `com.sami.samplay`, universal (`TARGETED_DEVICE_FAMILY = 1,2`), all iPad orientations.
- CI: `.github/workflows/ios.yml` (macos-15, newest Xcode) -> `scripts/build_unsigned_ipa.sh`
  (unsigned build, junk stripped, arm64 thinned, ad-hoc signed inside-out) -> `scripts/verify_ipa.sh`.
- Runtime config comes from repo secret `SAMPLAY_LOCAL_PROPERTIES_BASE64` (never commit it).
- Branches: `main` = CI-verified; `wip-p2` = work in progress (pushed often, no CI on push).

## Phases
1. [CI pending] Shared app as-is for iOS + SAMplay branding/icons.
2. [Kotlin iosArm64 main+test compile OK locally (box); CI run pending] iOS ports of Android-only SAMplay features:
   - Google Drive settings sync (`features/reshaped/sync/*`, installed at startup by `SamplayStartup`).
   - AI subtitles: Google free translation, Pro AI (Claude/ChatGPT/Gemini/Grok; Claude via direct
     Messages API), saved translations (Documents/SAMplay/Subtitles, visible in Files), Pro key sync.
   - Listening ("Create with AI"): `AiSpeechEngine.bridge` (Swift whisper.cpp) — phase 3.
   - Subtitle file load (Files picker) / save (Files export, delay written in) — `SubtitleFileActions.ios.kt`.
   - OpenSubtitles hash (`VideoHashComputation.ios.kt`), WebDAV/PikPak poster picker + files.
   - Device videos: Photos videos by album + app Documents folder + «Open file» + «Open in SAMplay»
     (CFBundleDocumentTypes, `samplayOpenIncomingFile` from Swift onOpenURL); Photos videos played
     via original file / Photos export to cache.
   - Reshaped subtitle font picker (uses the shared font importer).
   - Audio delay on iOS (mpv `audio-delay`), remembered per output route (AVAudioSession), and
     Seekr seek-preview sprite decoding (skia).
3. Audio: whisper listening + VAD audio-sync fallback (FFmpeg from MPVKit) or documented limit.
4. Unit tests (iosSimulatorArm64Test, CI step after the IPA) for ported logic; final IPA; Drive + PC delivery.

## Known limits (iOS can't do the Android way)
- ML Kit offline translation -> online Google translation only.
- v32 migration -> Android only (no v32 on iOS).
- Floating window -> iOS Picture in Picture (already in iosApp).

## Not ported yet (big, not iOS-impossible)
- Audio-sync fallback from heard speech (Android `player/audiosync` ~6k lines + sherpa-onnx ASR/VAD).
  iOS keeps the reference-based AutoSync (shared code). Next phase if credits allow.

## Local checking tips (box)
- `/workspace/samplay-ios/kn/dumps/*.txt` = K/N 2.4.10 ios_arm64 API dumps. ObjC *category*
  properties (e.g. NSMutableURLRequest.HTTPMethod, NSURL.lastPathComponent,
  NSFileManager.temporaryDirectory, AVAudioSession.currentRoute) need an explicit
  `import platform.<Fw>.<name>`. `/workspace/samplay-ios/extcheck.py <files>` lists candidates.
- Properties redeclared in subclasses (UIImagePickerController.delegate,
  NSMutableURLRequest.timeoutInterval) only exist as setX(...) functions.

## Local Gradle compile on the box (works!)
Worktree `/workspace/samplay-ios/lc` (detached, local-only edits: gradle.properties has
kotlin.native.home=<macos K/N prebuilt>, kotlin.native.disableCompilerDaemon=true; build.gradle.kts
uses prebuilt cinterop klibs when SAMPLAY_LOCAL_KLIBS is set). Sync: `git -C lc stash; git -C lc
checkout --detach wip-p2; git -C lc stash pop`. Run:
`env NUVIO_IOS_DISTRIBUTION=full SAMPLAY_LOCAL_KLIBS=/workspace/samplay-ios/klibs ./gradlew
:composeApp:compileKotlinIosArm64 :composeApp:compileTestKotlinIosArm64 --no-configuration-cache -q`

## NEXT ACTION
Context shadowing is fixed (45511810899 got past it). That run failed on iOS 27.1 APIs
(UIHingeInteraction, reservedRegions, verticalBarEdge, toolbarVerticalBehavior) compiled
against the iPhoneOS 26.5 SDK — `#available` is runtime-only. Those blocks are now behind
`#if SAMPLAY_IOS_27_1_SDK` (off on CI). Follow the newest wip-p2 run. On green: verify_ipa,
Drive Xplaier/"SAMplay iOS", merge main, report.

## CI minutes warning
Jobs with timeout-minutes 120 stayed queued forever (account macOS minutes running low?);
with 50 they start. Every macOS run is precious: compile-check locally first (see above).
wip-p2 pushes now trigger CI (PROGRESS_IOS.md-only pushes don't).

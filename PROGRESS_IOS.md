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
Run 45429231561 hit the 50-min timeout in the Release Kotlin link (no errors). Now CI uses KOTLIN_FRAMEWORK_BUILD_TYPE=debug (fast link), tests opt-in. Check the newest wip-p2 run; fix Swift/link errors if any; when green + verify_ipa
OK, merge to main, upload IPA to Drive "SAMplay iOS", copy to PC, report.

Drive target: SAMplay files live in Drive folder "Xplaier" (id 1pBrJRyzZlsKZ9VY-moj4uExwSquc-Gl6); create subfolder "SAMplay iOS" there.

## CI minutes warning
Jobs with timeout-minutes 120 stayed queued forever (account macOS minutes running low?);
with 50 they start. Every macOS run is precious: compile-check locally first (see above).
wip-p2 pushes now trigger CI (PROGRESS_IOS.md-only pushes don't).

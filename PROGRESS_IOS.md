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
2. [code written, not yet compiled] iOS ports of Android-only SAMplay features:
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
3. Audio: whisper listening + VAD audio-sync fallback (FFmpeg from MPVKit) or documented limit.
4. Unit tests (iosSimulatorArm64Test) for ported logic; final IPA; Drive + PC delivery.

## Known limits (iOS can't do the Android way)
- ML Kit offline translation -> online Google translation only.
- v32 migration -> Android only (no v32 on iOS).
- Floating window -> iOS Picture in Picture (already in iosApp).

# SAMplay iOS port

Source: SAMplay 2.0.7 (Android, vc 173), a Kotlin Multiplatform app (Compose Multiplatform UI shared
by Android and iOS). The iOS app is the same shared code (`composeApp`) plus `iosApp` (SwiftUI host,
MPVKit player), so the UI and logic are the Android ones.

- Bundle id `com.sami.samplay` (same as the Android package), universal (`TARGETED_DEVICE_FAMILY = 1,2`),
  all orientations on iPad.
- CI: `.github/workflows/ios.yml` (macos-26) -> `scripts/build_unsigned_ipa.sh` -> `scripts/verify_ipa.sh`.
- Runtime service config (account backend, sync client, tracking ids) comes from the repo secret
  `SAMPLAY_LOCAL_PROPERTIES_BASE64` as `local.properties`. Never commit it.

## Phases
1. Build the shared app for iOS as-is, SAMplay branding and icons. 

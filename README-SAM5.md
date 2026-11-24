# NuvioRS-Merged 0.6.4-sam5 — source pack

- Version: 0.6.4-sam5 (versionCode 152)
- Package: com.nuvioreshaped.app
- Signing cert SHA-256: 94118f9e837cc75000844028c8e1b9ef5a61973b28b60edb977d89a214c362e4
- Full pack: includes signing/, .github/keystores, composeApp/libs, vendor/, libass-android, gradle wrapper, Docs. Excluded: .gradle/, build/, .kotlin/, local.properties (recreate with sdk.dir).
- Keystore passwords are not stored in this README.

## Changes
- sam1: Plugin stream headers (Referer/UA etc.) passed through to the player — fixes 403. PikPak auth stripped on cross-host CDN redirects.
- sam2/sam3: PikPak/WebDAV folder browse fix (video detection by extension, video/* or extensionless title; relative hrefs resolved; empty listings not cached).
- sam4: Faster PikPak playback start (quick probe + URL cache, no full prebuffer wait).
- sam5: PikPak quality picker — Original first plus all available transcodes via PikPak API (requires PikPak account login).

# Nuvio RS — merged build (arm32 + arm64 · WebDAV/PikPak)

Source: NuvioMobile-Reshaped `0.6.3-beta-autosync` (merged over `0.6.0` and the earlier `0.5.9` work; this build's versionCode 147). `git log` in this folder shows
every change on top of the supplied source (the first commit is the untouched baseline). Hikari was
embedded in earlier commits and removed again on request; it is no longer part of the app.

## 1. One APK for arm32 and arm64

The two release APKs (`NuvioRS-…-arm32.apk`, `NuvioRS-…-arm64.apk`) were byte-identical except for
`lib/armeabi-v7a/*` vs `lib/arm64-v8a/*` (same dex, resources and manifest; checked entry by entry).
`androidApp/build.gradle.kts` now builds a single APK with both ABIs
(`ndk.abiFilters = armeabi-v7a, arm64-v8a`, ABI splits off). x86/x86_64 (emulator only) are not
packaged.

## 2. WebDAV (PikPak) in Nuvio RS

PikPak's WebDAV screen (`PikPak.jpg`) is a web page inside the PikPak app
(`https://mypikpak.com/drive/app-extension/webdav`); the WebDAV service itself is `dav.pikpak.ai`
(universal) / `dav.mypikpak.com` (legacy) with a per-client username/password.

Nuvio RS has a **WebDAV** media-server type (Settings → Cloud → WebDAV · PikPak, or Content
discovery → Media servers → WebDAV). The sign-in sheet follows PikPak's dialog: WebDAV Enable,
Universal / Alternative / Legacy endpoints with copy buttons, the HTTP/HTTPS note, the client's
username and password, an Advanced section (HTTPS, port, folder), and a link to PikPak's WebDAV
settings page. It works with any WebDAV server (Nextcloud, Synology, Alist, rclone…).

* Browsing follows the drive exactly like the PikPak app: the root is one "Files" library, every
  folder is a folder tile that opens its own page (as deep as the drive goes), and videos are
  listed after the folders in natural name order (E2 before E10). `.srt/.ass/.vtt` next to a video
  are offered as subtitles.
* Playback first follows PikPak's redirect to the signed download link and plays that link
  directly, without the drive's credentials, so seeking is a plain byte-range request to the
  download server (Nuvio RS's player otherwise re-sent `Authorization` to the CDN on every seek).
* Files are matched to catalog titles by name (title + year, or title + SxxEyy, using parent
  folders such as `Show/Season 2/`), so a movie on the drive shows up as a stream for that movie.
* **Shows and auto-play.** A folder holding a show's episodes (S01E02, 1x02, "Episode 2", "- 02",
  "02 …", "الحلقة 2") or its "Season N" folders opens as a show page with the episode list, and
  the player goes on to the next episode by itself when one ends (Nuvio's own next-episode
  autoplay). Other folders still open as folders.
* **Posters.** Tiles and pages are matched by name on Cinemeta (Stremio's public catalogue, no
  key): show folders, single-film folders and video files get the poster, background, description
  and genres; episodes get their titles and stills. Ordinary folders keep the folder tile.
* **Arabic titles.** Arabic file and folder names are looked up on elCinema (السينما.كوم: same
  title, right kind — film or series — and a real poster), then on Cinemeta, which finds Arabic
  names through IMDb's alternative titles. Spelling variants (أ/إ/آ/ا, ة/ه, ى/ي) match.
* **Edit poster and info.** Long-press any PikPak tile → *Edit poster and info*:
  * paste a link from TMDB, IMDb, elCinema or TheTVDB (or a direct image link) → *Fetch*;
  * or search by name (Cinemeta + elCinema results with posters) and pick one;
  * or type it in: title, poster link (or *Choose from photos*), background, year, story, IMDb id.
  Saved per file/folder; tiles, pages, episode titles and subtitles use it. TMDB pages refuse
  apps, so a TMDB link uses TMDB's API when a TMDB key is set in Settings → TMDB, otherwise
  Wikidata to find the title's IMDb id (and its Arabic name).
* **Subtitles.** A matched file gets its IMDb id, so the installed subtitle add-ons plus
  OpenSubtitles load automatically in the player. The player's Subtitles panel has three new
  buttons: *Search online* (type a film or show, pick it and the episode, pick a subtitle),
  *Load file* (any `.srt/.ass/.vtt/.ssa` from the phone) and *Save subtitle* (saves the selected
  subtitle wherever you choose through Android's save dialog).
* A connection made with an earlier build keeps its old libraries until you tap "Refresh
  libraries" on its page (or remove and re-add it).
* Code: `composeApp/src/commonMain/.../features/servers/webdav/`, `.../settings/WebDavSignInSheet.kt`;
  tests in `composeApp/src/commonTest/.../servers/webdav/WebDavTest.kt`.

## 3. Floating window and background playback (as in PikPak's player)

* **نافذة عائمة / Floating window** pill at the start of the player's bottom row (and in the
  legacy layout's header): floats the video in Android's picture-in-picture window right away.
  The window can be dragged anywhere and tucked into the screen edge (system behaviour). The
  video itself shrinks into the window and grows back (the params carry the picture's rectangle
  as the source-rect hint), and the window has back 10 s · play/pause · forward 10 s buttons.
* **Headphones toggle** next to the time: background playback. On, leaving the app keeps the
  sound playing behind the media notification ("يتم تشغيل الصوت في الخلفية" toast), and tapping the
  notification returns to the player. Off (default), leaving the app floats the video as before.
  The choice is remembered.
* PikPak show folders always go on to the next episode (no need for the add-on "Auto-play next
  episode" setting). With background audio the next episode starts when you return to the app;
  in the floating window it starts by itself.
* **The app's own floating window** (`FloatingPlayerWindow.kt`). Some phones (Samsung One UI)
  would not let the system window tuck into the edge, so once "Display over other apps" is
  allowed the player floats in its own window instead, the same on every phone, like the
  iPhone's. Drag it and it springs to the nearest resting spot (corners, middle of the sides).
  Flick it at a side, or push it past the side, and it tucks into the edge leaving a tab with an
  arrow; tap or pull the tab to bring it back, slide it up and down to move it. Pinch to resize. Tap for ✕ (pause and close), ⤢ (back to the app), back 10 s,
  play/pause, forward 10 s and a progress line. Both engines (ExoPlayer and libmpv) hand their
  picture to the window's surface while the player screen is away, and take it back on return,
  keeping the play/pause state. The first tap on the pill opens the permission page; without the
  permission the system picture-in-picture is used as before. Opening one of the app's own
  screens (file picker, settings) does not float the video.
* Code: `features/player/PlayerFloatingModes.kt`, `PlayerControlActions.kt`,
  `androidMain/.../PlayerPictureInPictureManager.android.kt`, `PlayerPlatformEffects.android.kt`,
  `FloatingPlayerWindow.kt`, the surface hand-over in `PlayerEngine.android.kt`.

## 4. PikPak info shared with the TV, both ways

* **Through the drive itself, no account needed.** Both apps keep poster/details edits in
  `.nuvio-rs/info.json` at the drive's root (path → entry, with a timestamp; removals too; the
  newer edit wins). The phone reads it when the drive opens and with each folder (at most every
  20 s), the TV when its PikPak screen opens or on *Refresh*; each app writes it shortly after an
  edit. The `.nuvio-rs` folder is hidden from both apps' listings.
* **Gallery pictures go too.** A poster picked from the phone's photos is uploaded to
  `.nuvio-rs/posters/` and shared as `webdav-file:<path>`; each app downloads it once into its own
  files (in the background, so a slow download never holds a listing up).
* Also still in the profile settings blob of the Nuvio account (`features.nuvio_rs_webdav_info`,
  keyed `host|path`), which the TV reads as before.
* Code: `features/servers/webdav/WebDavDriveSync.kt`, `WebDavInfo.kt`, `WebDavPosterFiles*.kt`,
  `WebDavClient.kt` (GET/PUT/MKCOL).
* The floating window's motion is the user's own design (built on their phone, ported back into
  the source): closed-form springs on the Choreographer (`floating/WindowSpring.kt`,
  `SpringSolver.kt`), iPhone-like resting spots, throws and tucking (`floating/PipPhysics.kt`),
  numbers in `floating/MotionSpec.kt`. Tucked, the card hides and stops taking touches and a
  separate small tab window sits on the edge. The window waits for the screen to settle (the
  launcher turning back to portrait) before it shows.

## 4a. PikPak as a large grid or a large list

* Library › Servers (where PikPak and the other drives show) has a button in the header to switch
  between **شبكة كبيرة / Large grid** (big posters, two to a row on a phone held upright, more on
  wider screens) and **قائمة كبيرة / Large list** (a big picture beside the name, year and details).
  The choice is kept with the library's display settings. Up to 60 titles per library; "See all"
  opens the rest.
* Code: `features/library/LibraryServerContent.kt`, `LibraryDisplaySettings.kt` (`serverLayout`),
  the header button in `LibraryScreen.kt`.

## 4b. Louder videos

* **صوت أعلى للمقاطع / Louder videos** (Settings › Nuvio Reshaped, next to Volume boost; on by
  default): every video plays +6 dB louder. ExoPlayer applies it with the LoudnessEnhancer on the
  player's audio session (its limiter keeps loud scenes from clipping), together with the swipe
  boost. libmpv gets `af=lavfi=[volume=6dB,alimiter=limit=0.95:level=0]` when the player opens;
  the swipe boost still moves mpv's volume as before. Turning it off takes effect on the next video.
* The TV app does the same: each video starts at +6 dB amplification (soft-clipped), which can
  still be lowered to 0 in its Audio panel.
* Code: `features/player/volumeboost/VolumeBoost.kt`, `VolumeBoostAndroid.kt`,
  `settings/VolumeBoostSettings.kt`.

## 4c. Subtitles from the phone's files

* The player's subtitle panel has a **من ملفات الجهاز / From device files** button (it used to
  read "تحميل ملف"). It opens Android's file picker; the chosen subtitle shows under its language
  as "ملف من الجهاز / Device file" and plays right away.
* An Arabic file saved as Windows-1256 is converted to UTF-8 when it is loaded, so both libmpv and
  ExoPlayer show it right. The language comes from the file name ("Movie.ar.srt", "Movie.Arabic.srt",
  "pt-BR"; words such as "WEB" no longer count as languages) or else the script of the text.
* Code: `features/player/SubtitleFileActions.android.kt`, `SubtitleExtras.kt`
  (`subtitleLanguageFromFileName`, `subtitleLanguageFromText`).

## 5. Fixes

* In-app updater and speech-model mirror pointed at the old repository name
  (`NuvioMobile-AutoSync`); now `NuvioMobile-Reshaped`.
* In-app updater disabled for this merged build: upstream releases are signed with another key
  (Android refuses them over this build) and would drop the merge.
* All arm64 native libraries are 16 KB-page aligned (checked), as required by Android 15+ phones
  with 16 KB pages.
* Addon subtitles were listed twice with libmpv: once under their language and again as a
  "Built-in" track under "Unknown" named after the file. libmpv now remembers the tracks that
  `setSubtitleUri` adds (addon, online search and device-file subtitles) and leaves them out of
  the built-in list; an AutoSync copy of such a subtitle stays out too. Stream subtitles loaded with
  the video still show as built-in tracks. On ExoPlayer the same can only happen when the subtitle
  can't use the sidecar (e.g. ASS with libass); that track is now marked and left out as well.
  Code: `PlayerEngine.android.kt` (`addonSubtitleTrackIds`, `EXO_ADDON_SUBTITLE_TRACK_ID`).
* Addon logos using root-relative paths now resolve against the manifest's scheme and host.
  Plugin repositories now report scraper-file download failures and keep the last working scraper
  when a refresh hits a temporary failure, instead of silently dropping it. The 0.6.4 source had
  the same addon and plugin feature code, so these fixes were made directly in the merged source.

## Nuvio account

Sign-in, profiles and sync use the Nuvio server (Supabase). `local.properties` carries the same
public client values the shipped NuvioRS APK uses (`NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY`,
`NUVIO_SUPABASE_FALLBACK_URL`), so **Sign In** works with an existing Nuvio account. TMDB, Trakt,
MDBList, Seekr and the Google-Drive "Reshaped sync" keys are still empty: enter a TMDB key under
Settings → Integrations → TMDB Enrichment, or add the keys to `local.properties` and rebuild.

## Building

```
gradle :androidApp:assembleFullRelease -PreleaseMinifyEnabled=false -Pnuvio.app.versionName=0.6.0-beta-autosync-merged
```

`local.properties` holds the SDK path, the config values above and the release signing entries for
`signing/nuviors-merged.jks`. Keep that keystore: every future build must be signed with it to
install over this one.

## 0.6.4-sam1 (versionCode 148) — plugins 403 + PikPak CDN Auth

Same SAMplay v14 root fixes ported surgically into this Merged tree:
- `PlayerPlaybackNetworking`: no Auth in default request props; same-host-only Auth; pin Referer/Cookie on every hop; `Proxy.NO_PROXY`
- `PluginRuntime`: nested url.headers, behaviorHints.proxyHeaders, pipe `|Referer=`, array headers
- `WebDavClient.resolvePlayback`: Range then GET, more hops, Auth only on DAV host

Built with `-PreleaseMinifyEnabled=false`. Signed with `signing/nuviors-merged.jks`.

## 0.6.4-sam6 (versionCode 153) — import collections from a link

- `CollectionRepository.importFromInput`: a single http(s) link is downloaded with `httpGetText`, then validated and imported like pasted JSON; everything else is parsed as JSON
- `CollectionManagementScreen`: import runs off the main thread with a progress bar; Cancel stops it

Built with `-PreleaseMinifyEnabled=false`. Signed with `signing/nuviors-merged.jks`.

## 0.6.5-sam1 (versionCode 154) — on NuvioMobile-Reshaped 0.6.5-beta-autosync

Upstream source is on branch `upstream`, the sam1-sam6 work is `3b4a5fa`, the merge is `6090d84` on `rs-0.6.5`.

- PikPak grid: drive folders use the same column count as catalogue grids (`catalogGridColumnsForWidth`).
- Volume: "Louder videos" is now +10 dB, which reads as 200% (`LOUDER_GAIN_DB`); the bar still adds on top, limited by `MAX_OUTPUT_GAIN_DB`.
- PikPak start: `PlaybackRedirects` remembers where a signed link leads for 10 minutes and falls back to the link if the target stops answering; `ServerPlayback.warm` prepares the first file when the links screen opens and leaves two connections in the player's pool.
- Link start: `StreamPrefetcher` asks add-ons after 900 ms of rest on a details page or the first Continue Watching item; answers live 5 minutes. Drive items are never asked about. An add-on therefore learns the title id before the user taps Play.
- Themes: Glass, Aurora Glass, Nebula Glass, Midnight, Sunset, Mint. Glass themes add soft lights over the screens (`GlassLights`), and cards get a light edge when depth is off.
- AutoSync: on by default. A UTF-8 reading of a Windows-1256 sidecar is decoded again before it is timed. `RecognizerLoadGuard` stops the speech model loading where it would crash under translation.
- Fixes: status bar icons are always light; the home hero falls back to the poster when a backdrop address fails; subtitle and sidecar files are no longer listed as episodes when a server labels them video/*. ExoPlayer now reads Arabic Windows-1256 subtitle files correctly (`ReadableSubtitleParserFactory`); libmpv already did.

Tests: the full run before the ExoPlayer subtitle fix was 1962 run, 6 skipped, 6 failed; after it, `ReadableSubtitlesTest` and `features.servers.*` passed (115 tests). All failures are in `AndroidDownloadLifecycleTest` and `DownloadSubtitlesTest`, whose code this work did not change. They fail when a record is saved twice; the likely cause is `AtomicFile`'s rename not replacing a file on a Windows host. Not confirmed on another host.

Build: `dev-tools/gradle-nv65.ps1 ":androidApp:assembleFullRelease" "-PreleaseMinifyEnabled=false" "-Pnuvio.app.versionName=0.6.5-sam1"`. Quote each argument. Signed with `signing/nuviors-merged.jks`.

## 0.6.5-sam2 (versionCode 155)

- PikPak folders are never looked up by name any more (the folder "Sam" was shown as a 1973 film). The poster editor picks the kind by hand: Movie, Series (both looked up by link or search), or Section (`type = "folder"`, the value SAMplay writes): its own name and picture only. The kind travels to the TV through the drive file and the account like the other fields. A season folder takes its show from the parent folder's IMDb id.
- Saving in the editor is guarded, so a failed save shows a message instead of closing the app.
- Speed: picture downloads from the drive have their own lock, so they no longer hold up a folder opening; the drive edits are read while the folder lists; tiles wait at most 6 s for posters.

## 0.6.5-sam3 (versionCode 156)

- Fetch from an IMDb link: when Cinemeta has nothing (new or Arabic titles), the name, poster, year and kind come from IMDb's suggestion service. A link that is no film or show (the user's tt33994503 is a 2021 podcast episode) now says "not found" instead of keeping a bare IMDb id.

## 0.6.12-sam2 (versionCode 163) — from SAMplay 1.8.9

- Home: catalogs load through a sliding window of `HOME_CATALOG_FETCH_BATCH_SIZE` requests (`HomeRepository`) instead of fixed batches, so one slow add-on no longer holds back every row after it.
- AI subtitles, SAMplay's own version (`features/ai/*`, `AiSubtitleTools*.kt`, `com/sami/samplay/ai/WhisperNative.kt`, `src/full/jniLibs/arm64-v8a/libsamplay_whisper.so`). An "AI" chip in the subtitle panel opens:
  - translation on the phone (ML Kit) into the chosen language, retimed by AutoSync like any added subtitle;
  - "Pro AI" translation with the user's own key for Claude, ChatGPT, Gemini or Grok (`ProProviders.kt`). The model can be changed; a model the provider no longer has is replaced by its newest one. Keys stay in the app's settings; none is built in;
  - "Generate with AI": whisper speech-to-text of the video (arm64 only, the model downloads on first use), optionally followed by a translation.
- AutoSync and audio sync, from SAMplay 1.8.7: audio sync works with libmpv (`LibmpvAudioSync`, `AudioSyncSourceReader`), the drift watch re-syncs while playing and after seeks (`DriftGate`; manual timing wins), offsets that step mid-episode are aligned per level, every stage writes a diagnostics line (`Diag`), and low-memory devices get smaller buffers (`DeviceMemory`).
- PikPak / WebDAV:
  - Path names are fully %-encoded (`WebDavClient.encodeSegment`): "Show.DD+5.1.mkv" asked PikPak for "DD 5.1" and got 404.
  - An HTTP error while opening or playing a drive file asks the drive for a new link with growing waits (1, 2, 3, 4, 5, 6, 8, 10, 10, 10 s: `LinkRetry`), with "HTTP 404 — asking for a new link (3 of 10)" over the video. The failed link is dropped from the link cache (`WebDavClient.forgetPlaybackLink`). Other errors keep the old single retry.
  - The next episode skips the folder's other copies of the same episode or film (`DriveCopies`, used by `PlayerNextEpisodeRules`): "S01E02 360p" was followed by "S01E02 720p". Files that name no quality ("Part 1", "Part 2") both still play.
- Found in the emulator runs, fixed:
  - Switching the playback engine (by hand, or the automatic switch after an error) attached the selected add-on/drive subtitle again with its original timing: AutoSync's result is in the subtitle file it attached, not in the delay, so a subtitle synced on libmpv played 5 s late on ExoPlayer. The carried-over subtitle is synced again on the new engine; a hand-set delay is kept instead (`applyCarriedOverSubtitleSelection`).
  - A drive that hands back the same link (a WebDAV server without a CDN redirect, or PikPak giving the same signed URL) left the player on its error under "asking for a new link (1 of 10)": the source did not change, so nothing reopened it. The same link is now opened again on the current player (`retryServerSourceAfterError`).
  - AutoSync's reader of the video's built-in subtitle index kept an HTTP error (PikPak answering a file 404 for a while) as "this file has no index" for two minutes, so the sync that ran once the video played had no reference. An HTTP error is no longer remembered (`EmbeddedSubtitleTimelineLoader`).
- Not taken from SAMplay 1.8.9: the quality button (WebDAV gives one quality per file; PikPak's own transcodes need its private API) and the download waits.

Tests: `LinkRetryTest`, `DriveCopiesTest`, `WebDavTest.resolveEncodesPlusAndOtherReservedCharactersInNames`, `PlayerEngineSwitchTrackPreferenceTest.engineSwitch*`, `AutoSyncHttpIndexTest` (http error). Emulator: Sync Cases E01–E06 (late 2.5/4/5/8 s, early 3 s, a 2 s → 6 s step, an ASS file) synced right on libmpv and on ExoPlayer; a file named "DD+5.1" opens from a server that reads "+" as a space. Not tried on a real PikPak account.

## 0.6.12-sam3 (versionCode 164)

- Dolby Vision P8 and other files the device cannot decode: a decoder error (`[ERROR_CODE_DECODER_…]`, `[ERROR_CODE_DECODING_…]`, `LinkRetry.isDecoderError`) no longer asks a drive for a new link. PikPak signs every link anew, so the player kept asking for links without end and never moved to libmpv; it now goes straight to the automatic engine switch, or the error screen (`tryRefreshExpiredLinkAfterError`).
- PikPak folder empty ("لم يتم العثور على عناوين"): going back from a sub-folder page cleared the shared catalog state after the folder page under it had started loading. Closing a catalog page now clears only its own state (`CatalogRepository.release`, `shouldClearOnRelease`, `disposeRouteResources`).
- Drive files: names with a dot but no video extension ("الرسالة.1976", "فيلم.HD") and older formats (`.rm`, `.asf`, `.f4v`, `.ogm`, `.m2t`, `.tp`, `.mxf`…) are listed; only files that are surely no video stay out (`WebDavNames.isKnownNonVideo`, `NON_VIDEO_TYPES`). A stray space after the extension is ignored. An empty folder listing is asked once more after 700 ms before it is believed (`WebDavProvider.list`).
- Subtitles up to 300%: the size goes to 54sp (three times the 18sp default), shown as "54sp · 300%" in Settings and in the player (`SUBTITLE_FONT_SIZE_MAX_SP`, `subtitleFontSizePercent`); libmpv's largest size is 165 (`MPV_SUBTITLE_FONT_SIZE_MAX`).
- Crash log: Settings › Advanced › Crash log › Share crash log (`CrashRecorder`, `CrashReports`). Java crashes are written as they happen with their stack; native crashes, freezes (ANR) and the system closing the app on screen are read from Android's exit records (Android 11+) at the next start, with the readable part of the tombstone. Each record carries the formats that were playing and the file name; links are cut to their host. Kept on the device only; nothing is sent.
- Black screen with sound (MPEG-2 on phones): Android's own MPEG-2 decoder is enabled on TVs only, so ExoPlayer dropped the picture and played the sound with no error, and the engine switch never started. A file whose every video track has no decoder (`Tracks.undecodableVideoFormat`) now stops and reports `ERROR_CODE_DECODING_FORMAT_UNSUPPORTED`: the app decoders get a try, then the switch to libmpv. A track that only goes past a decoder's stated limits still plays on ExoPlayer.
- Checked on the emulator (14 formats, "Format Test" S01E01–E14 on a drive server with signed links): each episode went on to the next by itself with no crash. HEVC 10-bit (1080p, 4K HDR10, 4K60) and 8K HEVC failed on the emulator's ExoPlayer decoder and moved to libmpv, each episode asking the drive for one link (the old build asked 18 times for one file). AV1 10-bit on ExoPlayer shows broken colours on the emulator only, the same in the old build (the emulator's screen path reads 10-bit frames as 8-bit); libmpv plays it right.
- Checked on the emulator after the MPEG-2 change: S01E10 (MPEG-2 576p AC3) stopped on ExoPlayer with `NoVideoDecoder format=video/mpeg2`, tried the app decoders once, then moved to libmpv and showed the picture, with one link. Subtitles at 54sp · 300% on ExoPlayer: one line, white with outline, readable (Arabic SRT in Windows-1256). ASS subtitles keep their own sizes on both engines (libass on ExoPlayer, `sub-ass-override=no` on libmpv), as before; only plain text subtitles follow the size setting.
- Host tests: 2369, 10 fail, none from this change: 7 download/font tests that failed before, and 3 `AppUpdaterControllerTest` tests that expect an update dialog the fork turned off (`inAppUpdaterEnabled = false`, c76d893).

## 0.6.12-sam4 (versionCode 165) — audio sync on the phone

Reported on a phone: AutoSync kept the subtitle's timing and the audio sync never moved it, with "0 words recognised" and "English reference: none matched your subtitle's timing" (an Arabic SubDL subtitle).

- Recognition stopped as soon as no English file lined up with the chosen subtitle as a whole file (SAMplay's `setListening`, ported in sam2), and the recogniser only ran with a bridged reference of its own. The words were still needed: the other-files check (`SubtitleCandidatePool.fromRecognition`) places an English file by the words heard and then the chosen subtitle against it where they were heard, with no whole-file bridge. The TV keeps recognising, so only the phone fell back to speech detection alone. Now listening stops only when there is no English file at all; the engine also recognises while the pool holds English files (`AsrSyncEngine.setWordsUsedElsewhere`), and the pool runs for English files alone, with no other file in the subtitle's language (`startPool`; `SubtitleCandidatePool.update` tries the recognised reference before giving up for lack of candidates).
- The English lookup sent the id of a file on the user's server or drive (`srv1:<connection>:<private path>`) to the OpenSubtitles service, which only answers 404 to it. It now goes out for catalog ids only (`AudioSubtitleSyncController.englishLookupId`; the TV already skipped `webdav:` ids).
- English files are downloaded as AutoSync and the TV download them (`AutomaticSubtitleSync.downloadSubtitleBody`: any Accept, charset detected from the bytes, size cap, one 429 retry) instead of `httpGetTextWithHeaders` (Accept JSON, header charset or UTF-8).
- The ASS-size change started after sam3 was cancelled by the user and is not in this build (ASS subtitles keep their own sizes, as in sam3).
- Tests: `SubtitleCandidatePoolTest.chosenFileWithNoOtherFilesIsSyncedThroughTheRecognisedReference`, `poolWithNothingToTestStaysQuiet`, `AsrMatchingTest.speechIsRecognisedForTheOtherChecksWithoutABridgedReference`, `speechWaitsWhileNoWordsCanBeUsed`, `AudioSubtitleSyncTest.englishLookupNeverSendsAFileOnTheUsersServerOrDrive`; the three that check the change failed before it. Host tests: 2374, the same 10 failures as sam3 (7 download/font, 3 `AppUpdaterControllerTest`).
- Emulator (x86, so no speech recogniser there), a 12-minute clip with real English speech, Arabic subtitle 4 s late with the SubDL ad line: with an English file of the same cut, the bridge found -4.000 s; with an English file of another cut (40 s added, 25 s cut, lines merged) no bridge, and the new build kept that file for the word check ("testing 0 other ara subtitles and 1 English"). No lookup went out for the drive file (the old build sent its path). Speech detection alone reached -4.09 s after about 3 minutes, unchanged. The word route itself was not seen on a device.

# Clean Browser — plan

A small, private, safe Android browser with a full download manager and torrents.
Our own code from the ground up. MIT licence. Public since 0.1.0.

## Decisions (user, 2026-11-19)
- Own code, fresh repo, MIT (public from 0.1.0).
- Page engine: Android System WebView (patched by Google through the Play Store).
- Torrents: FrostWire jlibtorrent (MIT; libtorrent inside, BSD-3).
- Name: Clean Browser.
- Small app. Jetpack Compose. Simple but rich theme options. Compact, clean, minimal,
  professional, smooth light animations.
- Signed with the AOSP testkey, v1 + v2 + v3.
- Built-in app updater.

## Ground rules
- No telemetry, no crash upload, no accounts, no Google Play Services, no Firebase.
  CI fails the build if such a dependency appears.
- Minimal UI text: short labels, no help lines under settings.
- Size: arm64-v8a only, R8 + resource shrinking, no material-icons-extended (only the
  icons we use, as vectors). APK size is printed in every CI run.
  Target (estimate, to be measured): about 12–15 MB, of which the torrent engine is ~6 MB.

## Tech
Kotlin, Jetpack Compose + Material 3, coroutines/Flow, Room, DataStore, OkHttp.
No DI framework (plain constructors). minSdk 29 (Android 10). Package `com.banner.cleanbrowser`.
Test devices: Pocket FIT (Android 14, WebView 159), Pocket FIT Elite (Android 16).

## Look and feel
- Screens: Browser, Tabs, Downloads, Settings. One address bar, a tab-count button, one menu sheet.
- Theme: System / Light / Dark, AMOLED black, Material You colours (Android 12+) or an accent
  from a small preset palette, address bar top or bottom, text size.
- Animations: short spring/fade transitions (tab switcher, sheets, progress), follow the
  system "remove animations" setting.

## Phase 1 — Browser core
- Tabs (normal + private), address bar (URL or search), back/forward/reload, bookmarks,
  history (none in private tabs), find in page, desktop site, share.
- Ask before a page opens another app (intent links).
- Privacy:
  - Own ad/tracker blocker in `shouldInterceptRequest`. Lists (EasyList, EasyPrivacy) are
    downloaded by the app at runtime, not bundled (they are GPL-3 / CC BY-SA). First version:
    domain and path rules plus exceptions; element hiding later.
  - Third-party cookies off. Clear-on-exit switch. One-tap "clear everything".
  - Private tabs in a separate WebView profile where the phone's WebView supports it
    (androidx.webkit MULTI_PROFILE), otherwise no storage + wipe on close.
  - HTTPS-only: upgrade http://, warn before plain http.
  - Pop-ups blocked, autoplay off, camera/mic/location ask every time, JavaScript per site.
  - Drop the X-Requested-With app header where androidx.webkit allows it (check at build).
  - Search engines: DuckDuckGo, Brave, Startpage, Google, Bing, custom.
  - Note: WebView uses the system DNS. For encrypted DNS the user sets Android Private DNS.
    The downloader (OkHttp) can use DNS over HTTPS itself.

## Phase 2 — Download manager (own engine on OkHttp)
- Catches every browser download: URL, user agent, file name (Content-Disposition), type,
  cookies and Referer from the page, so logged-in downloads work. blob: and data: links
  through a small JS bridge. Form (POST) downloads are a known WebView gap: research item.
- Engine: several connections per file (auto 1 when the server has no Range support),
  resume after network loss / app kill / reboot (state in Room), If-Range + ETag/size check
  so a changed file is restarted instead of corrupted, retry with backoff.
- Checks: size, optional SHA-256/MD5 (pasted or from a .sha256 file next to the link).
- Queue: max parallel, Wi-Fi only, speed limit, start later.
- Where: Downloads folder or any folder via the system picker (incl. SD card); safe names,
  "name (1)" on clashes.
- Background: Android 14+ user-initiated data transfer jobs (no 6-hour cap);
  Android 10–13 foreground worker. Notification with pause / resume / cancel.
- Add link by hand, from clipboard, from Share; long-press link → Download; refresh an
  expired link from its page; open with another app (e.g. BFE for archives).
- Warn before opening APKs/executables. No auto-open.

## Phase 3 — Torrents (jlibtorrent)
- Library from FrostWire's GitHub release assets (Maven Central only has a 2018 build),
  arm64 only, SHA-256 checked against their SHA256SUMS.txt in CI.
- Magnet links and .torrent files (from pages, files, Share), choose files, sequential mode,
  DHT/PEX/LSD, encryption prefer/force, ratio/time seeding limits, speed caps, Wi-Fi only.
- "Only over VPN" switch: pauses torrents when the VPN drops.
- Same Downloads list as HTTP downloads, with filters.
- Open: long seeding in the background on Android 15+ (system time limits). Decide in phase 3.
- No torrent search built in.

## Updater
- Same idea as BannerCloud's Updater.kt: check GitHub releases, show notes, download,
  install through the system installer.
- Extra checks: the APK's SHA-256 must match the release, and its signing certificate must
  match the installed app's.
- The source repo is private, so the app cannot read its releases without a token.
  Open decision: a public releases-only repo (like Bannerlator-updates), or wait until public.

## Build, test, release
- GitHub Actions, manual dispatch. Re-sign with apksigner v1+v2+v3 and verify all three
  (as BannerCloud's build.yml does). Artifact = APK + sha256.
- Each phase: CI build → stage `/sdcard/Download/CleanBrowser-<topic>-<sha8>.apk` with hash
  check → ask before install → test on device, logcat checked for crashes.
- Test cases: tracker test pages, big file + kill app mid-download, airplane mode, reboot,
  server without Range, checksum; legal torrents (Linux ISOs, Blender open movies).
- No release or tag until the user says so.

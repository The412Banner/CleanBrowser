# Clean Browser — progress log

Newest first.

## Lessons learned (don't repeat these)
- 11-19: pre-allocation on exFAT SD = kernel zero-fill through FUSE, app process unkillable for hours -> only pre-allocate on internal storage; test big-file features on SD too.

## 2026-11-23 — Magnets from uTorrent/BitTorrent Web share links (feat/share-link-magnet 3def5445, DEVICE-PROVEN, merge awaits user go)
- Why: a utweb.rainberrytv.com/gui/share.html#link=magnet:… link did nothing useful. The page's own script shows "this device isn't supported" on Android/mobile/Linux and only offers its Add torrent button (window.open(magnet)) on desktop, so the browser never saw a magnet. In the Add download box the link started with https and was treated as a file.
- Built: Urls.magnetIn(url) = the magnet itself, or the `link` value (query or fragment) of a .../gui/share.html page when it decodes to magnet:?. Used in Engine.handleNavigation (link taps/redirects), Tabs.load (address bar, links from other apps; a fresh tab is left blank), Downloads.addUrl + Add download dialog (no file-name/checksum fields). Urls.toUrl keeps typed magnet: links instead of searching them. Other pages with magnets in their address (e.g. in-browser torrent players) are not touched.
- Tests: UrlsTest.magnets (fragment, query, localhost, non-share pages, non-magnet link value). Not run locally (no Kotlin toolchain here); CI 45227303612 runs them.
- CI 45227303612 GREEN (headSha 842984f9; testReleaseUnitTest ran, no tracking deps, v1+v2+v3 verified). APK CleanBrowser-0.1.0-842984f9.apk 14,688,225 B sha256 49bdf671…1b9f (differs from v0.1.0; dex contains the new code). Staged /sdcard/Download/CleanBrowser-share-link-magnet-842984f9.apk, hash same in container and via root.
- Device test: user said "yes install it" -> INSTALLED 12:32 EST (pm install Success; installed base.apk sha256 49bdf671…1b9f = staged; crash buffer 0 lines; app not running yet). Feature NOT tested yet: user to open a share link via address bar, link tap, other app, Add download.
- First user test 12:40 EST (842984f9): share link recognised (prompt named "Big Buck Bunny", added), but the torrent FAILED "Invalid magnet uri: invalid info-hash". downloads.db row 23 url = "magnet:?xt=urn:btih:dd8255ecdc7ca55\n  fb0bb..." -> the link was copied from a wrapped terminal line (line break + indent inside the hash); the Add download box kept it (only trimmed the ends).
- Fix 3def5445: Urls.unwrap drops line breaks + surrounding spaces from text starting with http(s):// or magnet: (toUrl, magnetIn, Downloads.addUrl). Other text is only trimmed. Tests UrlsTest.magnets (wrapped hash, wrapped share link) + wrappedLinks. Old failed row 23 must be deleted by the user (Retry reuses the broken link).
- 3def5445: CI 45230464015 GREEN (headSha 3def5445, testReleaseUnitTest ran, no tracking deps, v1+v2+v3). APK sha256 35fd7091…072b staged /sdcard/Download/CleanBrowser-share-link-magnet-3def5445.apk (hash same via root). User: "install it when ready".
- 3def5445 INSTALLED 12:45 EST (pm Success; installed base.apk sha256 35fd7091…072b = staged; crash buffer 0). Feature test pending (user).
- User test 12:47 EST on 3def5445 (same wrapped copy of the share link): torrent added + RUNNING (screenshot 17/276 MB, 2.3 MB/s, 54/58 peers). downloads.db row 24 url = clean magnet (no line break), status RUNNING, 95 MB done at check; old failed row 23 deleted by user; crash buffer 0. Entry point used not confirmed (likely Add download); address bar / link tap / other-app paths not yet proven.
- Open/Next: CI result -> stage APK in Download (hash host+device) -> user yes -> install -> test with a share link (address bar, link tap, Add download). Merge to main only after device test + user go. No release.
- Resume point: branch feat/share-link-magnet pushed (ls-remote == 842984f9); FIT has v0.1.0 (e9173900) installed.

## 2026-11-21 — fastlane store metadata (main bbe79855)
- Why: Orion's submit form auto-fills icon/screenshots only from fastlane paths (or README images; its README icon fallback skips any URL containing "banner", i.e. every The412Banner link).
- Built: fastlane/metadata/android/en-US/images/icon.png + phoneScreenshots/1-4.png (same files as docs/). Branch feat/fastlane-metadata fast-forwarded to main; backup ref refs/backup/20261121/main-before-fastlane-metadata = 6f682242 (created after the merge: first push failed on a zsh `$VAR:r` modifier; use `${VAR}:refs/...`).
- Checked: raw icon 200 image/png; contents API lists 1-4.png; each raw shot 200 image/png.
- Open/Next: user re-enters the repo URL in the Orion form to re-run detection.

## 2026-11-21 — Store listing images (main 6c145d1)
- Why: Orion Store submission needs a PNG icon and at least 3 direct screenshot links; repo only had logo.webp.
- Built: docs/images/icon.png (logo.webp padded to 384x384 PNG) + docs/screenshots/1-4.png (user's own device screenshots: start page, menu, download settings, torrent settings; town name on the weather card blanked). Branch feat/store-listing-images fast-forwarded to main; backup ref refs/backup/20261121/main-before-store-listing-images = f9794083. Raw links checked (200, image/png, same bytes).
- Open/Next: user finishes the Orion form in the app (Icon URL + 4 screenshot URLs); README still shows logo.webp, no screenshots section.

## 2026-11-20 — v0.1.0 RELEASED; source public (main)
- User: "make it a proper release + proper readme + public repo"; picked stable v0.1.0 and a fresh public repo.
- Work log cleaned of download names/device details; dev repo main merged (backup refs/backup/20261120/main-before-browser-core
  = b09637bd), code on main identical to the device-tested build e9173900. Old private repo renamed CleanBrowser-dev
  (private, full history). New public repo The412Banner/CleanBrowser = single commit 263738f9 (README rewritten).
- Release build CI 44729907086 (workflow_dispatch, headSha 263738f9) GREEN: tests, no tracking libs, v1+v2+v3 testkey.
- v0.1.0 published as Latest in CleanBrowser (tag -> 263738f9) and CleanBrowser-releases (tag -> 9aabbb3b, read by the
  updater). Asset CleanBrowser-0.1.0.apk 14,688,225 bytes, sha256 651cbfeba4a2e68e765a48b5e365f9cdeccb5e5f46883204a1a66f1cad1b1e55;
  public download re-verified; unauthenticated releases API returns v0.1.0 with APK + SHA256SUMS.
- Phone runs e9173900 (same code, versionName 0.1.0) -> updater shows up to date.
- Next: device tests of untested features; future work on feat/* branches in this public repo.

## 2026-11-20 — Detailed speed test results; temp-file fix e9173900 installed (feat/browser-core)
- Detailed test (03abd479, 05:38-05:41 EST, screen off, background FGS via deviceidle temp allowlist, no freeze):
  Download/App folder/No storage x 1/2/4/8, 2 passes. No-storage runs as slow or slower than Download (10.5-25 MB/s)
  -> storage is NOT the bottleneck; variance is network/server. Best 26.0 MB/s (218 Mbps) Download x 8.
  Hetzner returned HTTP 429 for the last 4 runs (rate limit). Storage-staging fix NOT built (data says no gain).
- e9173900: NullSink uses no file (Sink.truncate/force), startup cleanup of cbnull*.tmp; removed 8 leftovers on device.
  CI 44724221253 GREEN sha256 a11d29be9156004e2e8aa87e918f583f3c3c8c1674b043a702c18fdd4abc9f23.
  User: "fix the temp file and install it when ready" -> INSTALLED 06:08 EST (hash OK, crash log clean).

## 2026-11-20 — Speed: baseline + engine improvements + in-app speed test (feat/browser-core cc759943)
- User: downloads ~159-209 KB/s on 1 Gbps Fios. Verified: current download = a 100 GB file from the same file host,
  single stream (server refused ranges, parts NULL), DIRECT (no VPN/proxy, Wi-Fi only transport), Wi-Fi 1441-2401 Mbps.
  Told the user: the host caps it.
- Baseline (curl in proot, data discarded, Hetzner ash-speed 1GB.bin, ranges OK): 1 conn 22.8 MB/s, 4 conn 37.9 MB/s,
  8 conn 24.7 MB/s, 16 conn 28.5 MB/s (proot overhead -> lower bound).
- Built cc759943: 256 KB reads + 1 MB write batches (flush on cancel), OkHttp HTTP/1.1 only (h2 would multiplex all
  parts in one TCP stream) + ConnectionPool(32), connections up to 32 (split only if >4 MB left), torrent
  "Add more trackers" (ngosang/trackerslist best, daily cache) + Max connections (connections_limit), in-app Speed test
  (Settings > Downloads; also root-triggerable: am broadcast -n com.banner.cleanbrowser/.download.DownloadActions
  -a com.banner.cleanbrowser.SPEED_TEST; results in logcat tag CleanBrowserSpeed).
- CI 44714302753 (cc759943) GREEN, sha256 df728540f238bed19eef3f810686904d1453d4cfff805617b0ff7f77a4ea0da2.
- User (~05:00 EST): "yes install it and run the test" -> installing, then speed test via root broadcast
  (background, no screen takeover). Resume point: if the session dies, read logcat -s CleanBrowserSpeed.
- Installed cc759943 05:21 EST (hash OK). Background-triggered test FROZE after run 1: threads in do_freezer_trap =
  Android cached-app freezer (broadcast-started process, no job/FGS). Bug: SpeedTest must hold KeepAlive.
- Re-ran with the app in the foreground (screen takeover announced to root-33/root-d8, Termux restored 05:26):
  Download folder 1 conn 21.8 MB/s (183 Mbps), 8 conn 12.1, 16 conn 14.8, 32 conn 20.2; SD app folder 8 conn 20.4.
  Single 8 s samples (noisy). 1 conn ~= curl baseline (22.8); multi-conn to Download (FUSE) slower than 1 conn,
  SD app folder (own Android/data, likely bind-mounted, no FUSE) 8 conn 20.4 -> write path suspected. No crashes.
- Next: fix freeze; repeat tests (2/4 conns, 3 runs each, internal app folder) before changing defaults.
- 03abd479: speed test holds KeepAlive, detailed mode (2 passes x {Download, app folder, no storage} x {1,2,4,8}, 6 s),
  NullSink. CI 44719960639 GREEN sha256 f8f120d93e507eaada53e3d7e78579b398a662f5b88155b1f64f9acfc6b9656b.
  User: "yes go ahead and install it when ready" -> installing + detailed test (screen takeover ~3 min, announced).

## 2026-11-19 — INCIDENT: app stuck on splash after 0160c88f install (feat/browser-core)
- Symptom (user ~22:55 EST): opening the app shows only the logo.
- Cause (verified): old process 9540 is a zombie; 2 DefaultDispatcher threads in D state in ftruncate via
  fuse_setattr (lengths 28,542,946,768 and 76,157,055,712 bytes). A very large torrent (~224 GB) saving to the
  SD card app folder (exFAT, dirsync) with pre-allocation on: exFAT zero-fills on extend,
  ~9 MB/s through FUSE, 51-53 GB done, ~1.5 h left. ActivityManager: "refused to die while trying to launch ...
  cancelling the process start" -> new process never starts -> splash forever. UIDT job #7001 kept retrying.
- Done: cancelled job (cmd jobscheduler cancel -u 0 com.banner.cleanbrowser 7001); backed up downloads.db to
  /sdcard/Download/CleanBrowser-backup-20261119/; set the torrent PAUSED in the app DB (owner u0_a226, restorecon).
- Fix 4871b911: pre-allocate only on internal storage (Storage.preallocOk; SD/removable and non-primary SAF trees
  -> sparse), no sparse-write fallback, libtorrent allocate mode only on internal; torrent free-space check pauses.
- User picked "You stop it with root", but by the time I checked (11-20 ~04:35 EST) the zombie had ended on its own
  (zero-fill finished); no FUSE abort done. App opened normally since (pid 28057, no D-state threads, crash log clean).
- The torrent: PAUSED, real data 16.8 MB of 223.9 GB (0.007%); ~118 GB on the SD are pre-allocated zero files.
- Fix 4871b911: CI 44671357086 GREEN, sha256 4f6b9254af7aba85f74ae10f807f71d72db20d4a0a932624b428a409c8a97e38, staged
  /sdcard/Download/CleanBrowser-prealloc-fix-4871b911.apk; user said yes -> INSTALLED 11-20 04:37 EST (hash OK, old process closed cleanly).
- Lesson: pre-allocation must check the file system (exFAT/FAT zero-fill); never run it on SD cards.

## 2026-11-19 — Site icons installed (5016d17c); security picks 2/5/A in progress (feat/browser-core)
- Site icons: saved from pages (onReceivedIcon, not private tabs) + fetched from the site itself (apple-touch-icon,
  <link rel=icon>, favicon.ico; no cookies, via Net route; never a third-party icon service); Settings > Content.
  CI 44666472231 GREEN, sha256 1f6543e927d4ead561e96c1718bf8c39f0cdffc23464cf18d8fbac7a20d80818. User: install -> installing.
- Security review given (11-19 ~22:25): weak spots 1 public testkey, 2 WebRTC leak in Tor/proxy, 3 plaintext proxy/WG
  secrets, 4 no app lock, 5 private tabs screenshots/keyboard, 6 no cosmetic filtering, 7 Safe Browsing off (user choice).
  Passwords: A Android autofill (user's manager), B own vault, C passkeys (WEB_AUTHENTICATION feature exists).
- User picked "2 5 a". Coding: WebRTC block (auto with Tor/proxy, always, never), FLAG_SECURE + no Recents screenshot
  while private tabs open, incognito keyboard (Compose InterceptPlatformTextInput + CbWebView onCreateInputConnection),
  Passwords row (autofill service name, opens credential provider settings).
- CI 44667439885 (0160c88f) GREEN, APK 14.00 MB sha256 f5abbdfc691a0c5292635799d44c7478815b287fa50d33f997d9746cef3756ce.
  User: "yes install it when it's ready" -> installing /sdcard/Download/CleanBrowser-security-0160c88f.apk.
- NOT device-tested: WebRTC block, FLAG_SECURE, incognito keyboard (Gboard icon), autofill with the user's manager.
- Resume point: 0160c88f installed; no background jobs.

## 2026-11-19 — Start page sections, address autofill, new icon: installing c54923b4 (feat/browser-core)
- Built: 3d5f741c + c54923b4 (import fix). Start page (logo, clock, Open-Meteo weather, device info, bookmark +
  frequent tiles with long-press remove/hide, Blank option, Settings > Start page), history visits column (DB v2),
  inline address autofill + site suggestions (TopSites ~250, history, bookmarks; long-press hide/remove),
  new launcher icon (user image, adaptive, bg #051316) + in-app logo (drawable-nodpi/logo.webp).
- CI 44665336666 (c54923b4) GREEN, APK 13.98 MB sha256 1dd1bd6736d2ccb6a309ab78e7e2bc85482bc1f5d3620ca11dfca7a6ea890d08.
- User: go + install when ready -> installing from /sdcard/Download/CleanBrowser-startpage-c54923b4.apk.
- NOT device-tested yet: weather fetch, autofill with IME composition, DB upgrade 1->2 on the installed app.
- Resume point: app installed; check logcat for FATAL after first use (DB upgrade path especially).

## 2026-11-19 — Sheet outline fix installed; start page + autofill + new icon started (feat/browser-core)
- 21:47 screenshot: bottom-sheet outline drawn at the pre-slide position (line through the menu). Fix b3b2d002:
  sheet container transparent, own Surface carries shape + 1 dp outline + drag handle. CI 44663367898 GREEN,
  sha256 b2043ad75a6dbe0c9aac5622fdbd30158705c798ce3d9a8fe33668ecc3644670. User: "install the just built fix".
- User "go" (~21:55 EST): start page sections (clock/date, weather via Open-Meteo, device info, bookmarks,
  frequent sites; each toggle; long-press remove; Blank option), address autofill (inline + popular sites list),
  new app icon + in-app logo from /sdcard/Download/IMG_20261119_215427.jpg; install when ready.
- Resume point: b3b2d002 installed (if pm Success); next build = start page + autofill + icon.

## 2026-11-19 — UI feedback + pre-allocation, installing 781c4bb4 (feat/browser-core)
- User feedback (screenshots 21:29, 21:30 EST): needs a Home button; top-right menu button hard to see;
  menus/pop-ups need a thin outline; thin line between options. Asked for "create download file before
  starting" = pre-allocation (explained: helps writes + early space check, not network speed).
- Speed question (21:30): A download from a file host ran at ~0.93 MB/s. Verified: Wi-Fi 6 GHz,
  link 1441-1921 Mbps, RSSI -53..-64; no VPN (NOT_VPN, no tun); app settings all default (direct, no limit);
  saved state had parts=NULL = server answered the Range probe with 200 -> single connection, no resume.
  Cause = server-side per-connection cap (likely). User cancelled that download before the update.
- Fixes: 87abcfbc root Surface content colour (menu icon was default black), Home button (option, Back
  returns to the open page), CleanDialog/CleanSheet/CleanMenu with 1 dp outline, dividers in rows/menus/choices;
  781c4bb4 dlPreallocate (posix_fallocate, sparse fallback; libtorrent STORAGE_MODE_ALLOCATE via
  AddTorrentParams + async_add_torrent) + free-space check (NoSpace, not retried).
- CI 44662166718 (781c4bb4) GREEN, APK 13.82 MB sha256 19d0ce82977aedb776a676f44a95dac6c17c1b9b042b8c71d4d7b03c1bee2a12.
- Device: first build 39e370ae was used by the user (Google loaded, 26 blocked); crash buffer clean for the app.
- User: "install it when it's ready" -> installing 781c4bb4 from /sdcard/Download/CleanBrowser-ui-prealloc-781c4bb4.apk.
- Resume point: app installed; no background jobs of mine.

## 2026-11-19 — First full build GREEN, installing on the Pocket FIT (feat/browser-core 39e370ae)
- CI run 44659476781 (headSha 39e370ae == pushed): unit tests, release build, no-tracking check,
  v1+v2+v3 testkey (cert a40da80a…) all GREEN. APK CleanBrowser-0.1.0-39e370ae.apk 13.82 MB,
  sha256 6a917f6bbc53a6d7cc4f648138f7f336e02fadb4d76fc0400183d34ef8511502 (draft release ci-builds).
- Build fixes on the way: tor-android declares minCompileSdk 37 (code only uses old APIs, javap) -> AAR
  metadata check skipped; tor-android classes are Java 24 -> R8 8.13.25 on the buildscript classpath.
- Staged: /sdcard/Download/CleanBrowser-browser-core-39e370ae.apk, sha256 equal on host and device.
- User: "yes install it when it's ready" (11-19 ~21:20 EST) -> installing (pm install), new package
  com.banner.cleanbrowser (was not installed).
- Device test: NOT yet. Next: user tests; I check logcat for FATAL after their first use.
- Resume point: app installed (if pm said Success); nothing running from me; no background jobs.

## 2026-11-19 — Full build: browser + downloads + torrents + VPN (feat/browser-core, CI)
- Why: user said "build the entire thing and install it when you're done for me to test" (11-19 ~20:45 EST).
- User decisions this evening: public releases-only repo for the updater (source stays private);
  Safe Browsing off by default; Google default search with DuckDuckGo and others; every feature an option;
  small app, Compose, themes, clean animations; AOSP testkey v1+v2+v3; built-in updater;
  downloads must never be killed: silent Wi-Fi + wake lock, silent notifications on by default with an off switch;
  free VPN toggle with server/location choice + own VPN/proxy for browser and/or downloads.
- My VPN pick (told the user, open to override): Tor (free, exit-country picker) + own WireGuard configs
  (app-only tunnel) + own HTTP/SOCKS5 proxy. VPN Gate rejected: logs 3+ months incl. packets, needs GPL OpenVPN.
- Built so far (commits on feat/browser-core):
  - f1baf0d3 browser core; 3f946071 compile fixes -> CI 44657032849: unit tests + release build + v1/v2/v3
    check GREEN, APK 2.32 MB (browser only); upload-artifact FAILED = account Actions storage quota full.
  - ae93f386 download engine (multi-connection, If-Range resume, retries, checksum, queue, SAF folder,
    UIDT job on 14+, FGS before, wake + Wi-Fi locks, silent notifications), torrents (jlibtorrent 2.1.2.1 aar,
    sha256 pinned in tools/fetch-libs.sh), Tor (tor-android 0.4.9.17), WireGuard (tunnel 1.0.20260102),
    proxy routing (WebView ProxyController + OkHttp + libtorrent), CI uploads APK to a DRAFT release "ci-builds"
    in this private repo (not published, no tag) because the artifact quota is full.
- Device test: NOT device-tested. Nothing installed.
- Open: CI result of ae93f386; WebView socks5:// rule acceptance unverified; Android 10 storage path untested;
  POST form downloads not supported; cosmetic (element hiding) filters not supported.
- Resume point: check `gh run list -R The412Banner/CleanBrowser --branch feat/browser-core`; fix errors; then stage
  APK in /sdcard/Download/CleanBrowser-<topic>-<sha8>.apk (hash host+device) and ASK before installing.

## 2026-11-19 — Project start (main, planning)
- Why: user wants a simple, safe, private browser with a strong download manager and torrents.
- Decisions (user): own code, fresh private repo, MIT; Android System WebView; jlibtorrent;
  name Clean Browser; small app, Compose, simple but rich themes, clean animations;
  AOSP testkey v1+v2+v3; built-in updater.
- Built: repo with LICENSE, README, PLAN.md, this log. No app code yet.
- Checked live 11-19: GeckoView 160 (not used); jlibtorrent release/2.1.2.1 (11-13, MIT,
  Android arm64 jar 6.2 MB, GitHub assets only); libtorrent4j 2.1.0-39 (MIT, alternative);
  androidx.webkit 1.18.0; Pocket FIT WebView 159.0.8154.18 beta, Android 14;
  Android 15 caps dataSync foreground services at 6 h / 24 h; Android 14 has
  user-initiated data transfer jobs; EasyList/EasyPrivacy = GPL-3 or CC BY-SA (download at runtime).
- Open: go on phase 1; updater source for a private repo (public releases-only repo?);
  Safe Browsing default; default search engine.
- Resume point: nothing built or installed. Next = phase 1 scaffold on feat/browser-core after go.

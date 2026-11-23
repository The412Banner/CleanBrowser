<p align="center">
  <img src="app/src/main/res/drawable-nodpi/logo.webp" width="128" alt="Clean Browser logo">
</p>

<h1 align="center">Clean Browser</h1>

<p align="center">
A small, private Android browser with a full download manager, torrents and built-in VPN/proxy options.<br>
No accounts, no telemetry, no Google services.
</p>

<p align="center">
  <a href="https://github.com/The412Banner/CleanBrowser/releases/latest"><b>Download the latest release</b></a>
</p>

---

## Features

### Browser
- Tabs and private tabs (private tabs keep cookies and site data in a separate profile and wipe them when closed)
- Start page with clock, weather, device info, bookmarks and frequent sites — each section can be switched off, or choose a blank page, a search engine or your own home page
- Address bar autofill and site suggestions (from your history, bookmarks and a built-in list; nothing is sent while typing unless you turn on search suggestions)
- Site icons, bookmarks, history with search, find in page, desktop mode, pull to refresh
- New tab button next to the tab counter; tap the address bar on a page to copy or share its address
- Search engines: Google (default), DuckDuckGo, Brave Search, Startpage, Bing, Ecosia, Qwant, Mojeek or a custom one
- Themes: system, light, dark, pure black, wallpaper colours (Android 12+) or ten accent colours; address bar at the top or bottom

### Privacy and security
- Own ad and tracker blocker (EasyList and EasyPrivacy by default; uBlock filters, Fanboy Annoyances, Peter Lowe's list and StevenBlack hosts optional) with a per-page blocked counter
- HTTPS-only mode, third-party cookies off, Global Privacy Control signal
- Hides the app header and the WebView marker in the user agent; no speculative preloading; no ad attribution
- WebRTC leak blocking (automatic with Tor or a proxy, or always)
- Private tabs: no screenshots or Recents preview, incognito keyboard
- Camera, microphone and location ask every time; pop-ups and autoplay blocked; certificate errors cannot be bypassed
- Clear browsing data on quit (optional); no cloud backup of app data
- Passwords: works with your password manager through Android autofill (Google, Bitwarden, Proton Pass, 1Password …)
- Safe Browsing is off by default and can be switched on

### Download manager
- Several connections per file (up to 32); picks up where it stopped after a network drop, an app restart or a reboot (when the server supports resuming)
- Detects when a file changed on the server and restarts it instead of corrupting it
- Retries, queue, speed limit, Wi-Fi only, optional SHA-256 / MD5 check
- Save to the Download folder or any folder you pick (SD card included); pre-allocation on internal storage
- On SD cards (exFAT/FAT, no sparse files) big files are prepared in small steps before writing ("Preparing on SD card"), so the app can always be paused, closed or updated
- blob: and data: downloads, links from the clipboard or the Share menu
- Downloads keep running in the background (user-initiated data transfer job on Android 14+, foreground service before), with a silent progress notification and silent Wi-Fi and wake locks
- Built-in speed test

### Torrents
- Magnet links and .torrent files (from pages, files or the Share menu), and μTorrent / BitTorrent Web share links
- Choose files, sequential download, seeding limits, encryption, DHT / PEX / local peer discovery, UPnP
- Optional extra public trackers, connection limits, download and upload caps
- "Only over VPN" switch; free-space check before large torrents

### VPN and proxy
- **Tor** (free): built in, with an exit-country picker and "new identity"
- **WireGuard**: import your own server configs; the tunnel covers only Clean Browser, not other apps
- **Proxy**: your own HTTP or SOCKS5 proxy, with user name and password
- Choose what uses it: browser, downloads, torrents. Kill switch blocks traffic while the connection is down

### Updates
- Built-in updater reads [CleanBrowser-releases](https://github.com/The412Banner/CleanBrowser-releases), checks the APK's SHA-256 and its signing certificate before installing

## Install

1. Download `CleanBrowser-<version>.apk` from the [latest release](https://github.com/The412Banner/CleanBrowser/releases/latest).
2. Open it and allow installs from your browser or file manager when Android asks.
3. Optional: Settings → *Set as default browser*.

Requirements: Android 10 or newer, 64-bit ARM (arm64-v8a). Download size about 14 MB.

> **Signing:** release APKs are signed with the public AOSP test key (certificate SHA-256 `a40da80a…f5dc`).
> Anyone can sign an APK with this key, so only install Clean Browser from this repository or the in-app updater,
> which also checks the published SHA-256.

## Permissions

| Permission | Why |
|---|---|
| Internet, network state | Browsing, downloads, Wi-Fi-only rules |
| Camera, microphone, audio settings, location | Only when a site asks and you allow it |
| Notifications | Silent download progress |
| Foreground service, user-initiated jobs, wake lock | Keep long downloads running |
| Battery optimisation exemption | Only if you choose it in Settings → Downloads |
| Install packages | The built-in updater |
| Storage (Android 10 only) | Saving downloads to the Download folder |

No contacts, no phone, no SMS, no accounts.

## Data that leaves the phone

Only what you ask for: the pages you open and their requests (minus blocked ones), downloads, filter-list updates
(easylist.to, ublockorigin.github.io and the other lists you enable), weather for a rounded location (Open-Meteo,
only if you set a city), search suggestions (off by default), site icons fetched from the site itself, the public
tracker list (if enabled) and the update check (GitHub). No analytics, crash reports or identifiers.

## Build

The app is built by GitHub Actions (`.github/workflows/build.yml`): unit tests, release build, a check that no
tracking libraries are present, and v1 + v2 + v3 signature verification.

Locally (Android SDK, JDK 17):

```sh
bash tools/fetch-libs.sh        # downloads the jlibtorrent AAR and checks its SHA-256
./gradlew assembleRelease
```

Stack: Kotlin, Jetpack Compose (Material 3), Android System WebView, OkHttp, SQLite.

## Status

0.2.0. Used on a Pocket FIT (Android 14): browsing, ad blocking, tabs, menus, start page, downloads
(multi-connection speed test up to 26 MB/s), adding and downloading torrents, share links, torrents on the SD card
(preparing, pause, close and reopen). Not yet tested on a device: the new tab button and the address bar copy/share
buttons, multi-connection downloads to the SD card, password autofill, private-tab screenshot / keyboard protection,
weather, site icons, Tor, WireGuard, proxy routing and the updater.
Please report problems in [Issues](https://github.com/The412Banner/CleanBrowser/issues).

## Credits

- Torrents: [jlibtorrent](https://github.com/frostwire/frostwire-jlibtorrent) by FrostWire (MIT), built on [libtorrent](https://github.com/arvidn/libtorrent) by Arvid Norberg (BSD-3)
- Tor: [tor-android](https://github.com/guardianproject/tor-android) by the Guardian Project (BSD-3)
- WireGuard: [wireguard-android](https://git.zx2c4.com/wireguard-android) tunnel library (Apache 2.0)
- HTTP: [OkHttp](https://square.github.io/okhttp/) (Apache 2.0)
- Weather: [Open-Meteo](https://open-meteo.com) (data CC BY 4.0)
- Filter lists (downloaded at runtime, each under its own licence): EasyList / EasyPrivacy, uBlock Origin filters, Fanboy, Peter Lowe, StevenBlack
- Tracker list: [ngosang/trackerslist](https://github.com/ngosang/trackerslist) (downloaded at runtime)
- Icons: Material Icons (Apache 2.0)

## License

MIT — see [LICENSE](LICENSE).

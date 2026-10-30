# Surfer

**A self-hosted, server-side web browser for old Android devices.**

Old tablets and phones are often fine pieces of hardware, but their built-in
browsers can no longer open the modern web. Surfer runs a current Chromium in
a Docker container on a computer in your home. A small Android app shows a live
picture of the page and sends your touches and keystrokes back. It works like
Puffin or Opera Mini, but on your own machine, so your logins and browsing
never pass through anyone else's servers.

Version **0.2.0**. It is used daily on an Android 4.4.2 tablet, mostly for
reading and email.

---

## Features

- **The modern web on Android 4.4+, and even Android 1.6.** Pages are
  rendered by a current Chromium (156 at the time of writing). The main app
  needs Android 4.4 (API 19) or later; a second app runs on Android 1.6 to
  4.3 devices such as the Sony Ericsson Xperia X10 and X10 mini (see
  [Android 2.x devices](#android-2x-devices)).
- **Native-feeling touch.** Tap, scroll, fling and pinch-zoom are forwarded as
  real multi-touch events with their original timing, so scrolling momentum
  and gestures behave like a phone browser.
- **Real keyboard support.** Autocorrect, word suggestions and swipe typing
  work in page text fields, including rich editors such as webmail. The page
  shrinks above the keyboard and the field you type into is scrolled into
  view.
- **Sharp, efficient picture.** Only the parts of the screen that changed are
  sent. Small changes are sent losslessly; scrolling uses JPEG, which is
  replaced by a lossless picture once the page stops moving. The stream slows
  down on a slow network instead of falling behind.
- **Chrome-like URL bar.** A clear button, and a suggestion list while you type
  with what you typed, matching pages from your history and the search
  engine's suggestions.
- **Tabs**, back/forward, reload/stop, and pages that open new windows.
- **Page dialogs** (alert, confirm, prompt, "leave this page?") as native
  Android dialogs.
- **Layouts:** tablet, phone or desktop versions of sites.
- **Optional full-screen mode** that hides the status and navigation bars.

## Privacy and security

- **Your browsing state lives on your device.** Cookies, site storage
  (localStorage and sessionStorage), open tabs, settings and history are
  stored by the app, encrypted with AES-256-GCM using a key protected by the
  Android keystore. Changes are synced to the device in real time.
- **The server keeps nothing between sessions.** Each device gets its own
  Chromium with a temporary profile. When the device has been disconnected
  for 30 minutes (configurable), that browser is closed and its profile is
  deleted. On the next connection the app sends its saved state back.
- **No accounts.** Each app installation creates a random 256-bit device ID.
  That ID alone selects the device's browser session, so devices never see
  each other's data. The server keeps only a hash of it.
- **Encrypted connection.** The app talks to the server over TLS. The server
  generates its own certificate, and the app is built with that exact
  certificate pinned, so it refuses any other server.
- **Chromium's sandbox stays on.** The container runs as a non-root user with
  a seccomp profile (Docker's default plus the three calls Chromium's sandbox
  needs). No `--no-sandbox`, no privileged mode.
- **"Clear browsing data"** in the app menu wipes the session on the server
  and all saved state on the device.

> Surfer is designed for a **trusted home network**. Anyone who can reach the
> server's port can start a browsing session on it, although they cannot
> access other devices' data. Do not expose the ports to the internet.

URL bar search suggestions are fetched by the server from your search engine,
as Chrome does, without cookies. History matching happens on the device only.

## How it works

```
 Android app                                      Docker container
┌──────────────────────────┐   wss (TLS)      ┌────────────────────────────────┐
│ Picture of the page      │◄── screen updates┤ Surfer server (Node.js)        │
│ Touch, keyboard,         │─── input ───────►│  one Chromium per device,      │
│ native dialogs           │◄── state sync ──►│  temporary profile, sandboxed  │
│ Encrypted state store    │                  │                                │
└──────────────────────────┘                  └────────────────────────────────┘
```

The server drives Chromium through the DevTools protocol. The app is a plain
Java Android app without Google Play services or AndroidX, so it runs on very
old devices. It is built inside Docker, so you need neither Android Studio nor
a local Java installation.

[DESIGN.md](DESIGN.md) describes the design in full, including the protocol.

## Requirements

- A 64-bit computer (x86-64 or ARM64) that stays on while you browse, with
  [Docker](https://docs.docker.com/get-docker/) and Docker Compose. Linux, and
  Windows or macOS with Docker Desktop, all work.
- Enough memory for one Chromium per connected device. The container is
  limited to 6 GB by default.
- An Android device with Android 4.4 or later (or 1.6 to 4.3 with the
  [Android 2.x app](#android-2x-devices)), on the same network.

## Quick start

### 1. Get the code and start the server

```sh
git clone https://github.com/nickolasbossinas/surfer.git
cd surfer
cp .env.example .env        # optional: change ports and other settings
docker compose up -d --build
```

On first start the server creates its TLS certificate in the `surfer-data`
Docker volume. Check that it is running:

```sh
docker compose logs surfer
# ... Surfer 0.2.0 listening on https/wss port 8686 ...
```

### 2. Build the Android app

```sh
docker compose run --rm android-builder
```

The first build downloads the Android SDK and Gradle into the builder image,
which takes a few minutes. Later builds take seconds. The builder:

- bundles the server's certificate into the app;
- signs the app with a key created on the first build and kept in the
  `android-keystore` volume, so updates install over the previous version;
- writes the APK to the server, which offers it for download.

> **The APK belongs to your server.** It contains your server's certificate,
> so share the repository, not the APK. If the `surfer-data` volume is deleted,
> the server creates a new certificate. Rebuild and reinstall the app then.

### 3. Allow the ports through the firewall

The app connects to port **8686** (TLS WebSocket). Port **8687** (plain HTTP)
serves only the APK, so that old browsers without a trusted certificate can
download it. Both are configurable, see [Configuration](#configuration).

On Windows (Docker Desktop), run in an administrator PowerShell, using your
ports if you changed them:

```powershell
New-NetFirewallRule -DisplayName "Surfer" -Direction Inbound -Protocol TCP -LocalPort 8686,8687 -Profile Private -Action Allow
```

On Linux with a firewall such as ufw:

```sh
sudo ufw allow from 192.168.0.0/16 to any port 8686,8687 proto tcp
```

### 4. Install the app

Find the server's address on your network, for example with `ipconfig` on
Windows or `ip addr` on Linux. Then, on the Android device:

1. Allow installing apps from unknown sources (Settings → Security).
2. Open `http://<server-address>:8687/app.apk` in the device's browser and
   install the downloaded file.
3. Start **Surfer** and enter the server address, for example `192.168.1.20`.
   Add `:port` if you changed `SURFER_PORT` and built the app before that.

### Updating

```sh
git pull
docker compose up -d --build
docker compose run --rm android-builder
```

Then download and install the APK again from the same address. The update
installs over the old app and keeps your browsing data.

## Android 2.x devices

Devices older than Android 4.4, such as the Xperia X10 and X10 mini (Android 1.6 or 2.1), use a
separate app, **Surfer X10** (`android-x10/`). It works like the main app,
adapted to a small single-touch phone: the Menu key opens the menu (forward,
tabs, layout, full screen and more), and sites get their phone layout by
default.

Android 2.x can only use TLS 1.0, which the main port does not accept. The
server therefore has a second port for these devices, **off by default**. It
accepts TLS 1.0 with forward-secret ciphers only, and the app still pins the
server's certificate. Turn it on only if you use the app:

1. Add `LEGACY_PORT=8688` to `.env` and run `docker compose up -d`.
2. Build the app: `docker compose run --rm android-builder-x10`.
3. Allow port 8688 through the firewall (as in step 3 above).
4. On the device, allow unknown sources (Settings → Applications), open
   `http://<server-address>:8687/x10.apk` in the browser, install, and enter the
   server address.

To use a different port on your computer, set `SURFER_LEGACY_PORT` before
building the app; it becomes the app's default port.

## Configuration

All settings are optional. Put them in a `.env` file next to
`docker-compose.yml` (see [.env.example](.env.example)) and run
`docker compose up -d` again.

| Variable | Default | Meaning |
|---|---|---|
| `SURFER_PORT` | `8686` | Port the app connects to. The app's default port is taken from this when it is built. |
| `SURFER_INSTALL_PORT` | `8687` | Port that serves the APK. |
| `INSTALL_PORT` | `8687` | Set to `0` to turn the APK download off. |
| `LEGACY_PORT` | `0` | `8688` turns on the TLS 1.0 port for the [Android 2.x app](#android-2x-devices). |
| `SURFER_LEGACY_PORT` | `8688` | Port on your computer for that app; also its default port when built. |
| `IDLE_STOP_MIN` | `30` | Minutes without a connection before a device's browser is closed and its temporary profile deleted. `0` keeps it until the server stops. |
| `ACCEPT_LANGUAGE` | `en-US,en` | Languages requested from websites, most preferred first. Also used for search suggestions. |
| `IDLE_FORMAT` | `png` | Lossless format for still pictures: `png` or `webp`. |
| `DEBUG_CLIENT` | `1` | Serve a desktop debug client at `https://<server>:<port>/debug/`. |
| `LOG_LEVEL` | `info` | `debug`, `info`, `warn` or `error`. |
| `SURFER_MEM_LIMIT` | `6g` | Memory limit of the server container. |

The **search engine** is Google by default. Suggestions are supported for
Google, Bing and DuckDuckGo.

## Using the app

- **Menu (⋮):** new tab, tablet/phone/desktop layout, full screen, debug
  log, change server, clear browsing data.
- **Tabs button:** shows the number of open tabs; tap it to switch or close
  tabs.
- **URL bar:** type an address or a search. Tap a suggestion to open it, or
  its ↖ arrow to copy it into the URL bar and keep editing.
- **Back button:** closes the keyboard or the URL bar first, then goes back
  in the page history.
- **Debug log:** when on, the app sends diagnostics and crash reports to the
  server log (`docker compose logs surfer`).

## Troubleshooting

| Problem | What to check |
|---|---|
| The app cannot connect | Same network? Firewall rule for the port? `docker compose ps` shows the server running and healthy? |
| "The server's certificate does not match this app" | The server's certificate changed (the data volume was recreated). Rebuild the app and reinstall it. |
| The APK download does not start | Use `http://` (not https) and the install port, and check `INSTALL_PORT` is not `0`. |
| Chromium fails to start (sandbox errors in the log) | The host must allow unprivileged user namespaces. On Ubuntu 24.04 and later, AppArmor may restrict them for containers. |
| A website asks to prove you are not a robot | Some sites treat browsers in data centres or automated environments with suspicion. Solve the check once; the cookie is kept on the device. |

## Development

The server is TypeScript on Node.js 22. Its unit tests run locally with
`node_modules` only; everything else (Chromium, the Android SDK, Java) runs in
Docker.

```sh
cd server
npm install
npm test             # unit tests (vitest)
npm run typecheck
```

End-to-end probe against a running server (loads pages through the real
protocol and saves the received pictures):

```sh
node test/e2e.mjs ./out https://example.org
# VIEWPORT=601x910x1.33125   emulate a device (CSS width x height x pixel ratio)
# SWIPE=1                    test fling scrolling
# SYNC=1                     test cookie/storage sync through a session restart
# IME_TAP=x,y                tap a text field and type through the IME path
# SUGGEST=text               fetch URL bar suggestions
# SURFER_WS=wss://host:port/ws
```

App unit tests run in the builder (the X10 build also fails on any call
newer than Android 1.6):

```sh
docker compose run --rm android-builder build-apk testDebugUnitTest
docker compose run --rm android-builder-x10 build-apk testDebugUnitTest lintRelease
```

### Repository layout

```
docker-compose.yml     server and app builder services
.env.example           settings
DESIGN.md              design and protocol
server/                Node.js server (src/), debug client (debug/), tests (test/)
  seccomp-chromium.json  Docker's default seccomp profile plus Chromium's sandbox calls
android/               Android app (plain Java, minSdk 19)
android-x10/           Android 2.x app (plain Java, minSdk 4)
android-builder/       Docker image that builds and signs the APK
```

## Roadmap

- Native pickers for `<select>` and date/time inputs, long-press context menu,
  text selection and copy, clipboard paste.
- HTTP authentication dialogs.
- Bookmarks, downloads, audio.
- Video and file uploads.

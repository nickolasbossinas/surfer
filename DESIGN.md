# Surfer — Design

A self-hosted, server-side browser for old Android devices. A current Chromium
runs in a Docker container on a home computer. The device shows a live picture
of the page and sends touches and keystrokes back. Nothing leaves the home
network, and the browser state (cookies, site storage, tabs) belongs to the
device: it is stored there, encrypted, and the server only holds it while a
session runs.

## 1. Goals and scope

- **Primary client:** Android 4.4.2 tablet (API 19), native app. No compromises
  for the sake of other devices.
- **Secondary client:** Xperia X10 / X10 mini, Android 1.6+ (API 4). A separate app
  (`android-x10/`) that shares only the protocol (§4.2).
- **Server:** Docker on a 64-bit machine (x86-64 or ARM64). Windows with Docker
  Desktop (WSL2) and native Linux are both supported.
- **Network:** home LAN only, TLS on the WebSocket.
- **Identity:** no users or passwords. Each app install creates a random device
  ID; every device has its own browser state.
- **Usage:** mostly reading and email.
- **No Android Studio:** APKs are built in a Docker container.

| Feature | When |
|---|---|
| Page view, touch (tap, scroll, fling, pinch), URL bar, navigation | v1 |
| Keyboard with full IME (autocorrect, swipe typing) | v1 |
| Native pickers (select, date/time, colour), context menu, text selection | v1 |
| Tabs, clipboard, JS and HTTP-auth dialogs | v1 |
| Device identity, browser state stored on the device and synced live | v1 |
| X10 app (Android 1.6+) | v1 |
| Bookmarks, downloads, audio | later |
| Video (H.264), file uploads | much later |

Logins: Surfer stores no site passwords. Cookies persist (on the device), so
each site is logged into once.

## 2. Architecture

```
 Tablet app (Java, API 19)                        Docker container
┌──────────────────────────┐  wss://:8686/ws  ┌────────────────────────────────┐
│ FrameView + scroll       │◄── frames, sync ─│ Surfer server (Node 22, TS)    │
│   prediction             │                  │  sessions · sync · screencast  │
│ Touch, IME, native UI    │─── JSON ────────►│  input · IME · UI bridge       │
│ (pickers, menus, dialogs,│                  │        │ CDP (puppeteer-core)  │
│  selection, clipboard)   │                  │        ▼                       │
│ Encrypted state store    │                  │  Chromium per device, temporary│
└──────────────────────────┘                  │  profile (sandboxed)           │
        ▲ http://:8687/app.apk (install only) └────────────────────────────────┘
                                                  volume /data
```

`/data` layout:

```
/data/tls/server.key|crt    self-signed RSA-2048 certificate (generated)
/data/apk/surfer.apk        built app
```

No browser data is kept in `/data`. Running sessions use temporary profiles in
the container's `/tmp`, deleted when the session ends (and at server start, in
case of a crash).

```
```

## 3. Server

**Stack:** Node.js 22 LTS, TypeScript, `puppeteer-core`, `ws`. Base image
`node:22-trixie-slim` with Debian `chromium` and fonts (`fonts-noto`,
`fonts-noto-cjk`, `fonts-noto-color-emoji`).

### 3.1 Sessions and state sync

- **Device ID:** a 256-bit random secret created by the app on first start and
  sent in `hello`. The server keys sessions by its SHA-256 and never stores it.
  A different install (another tablet or phone) has a different ID and cannot
  reach this session.
- **One Chromium process per connected device**, with a temporary profile
  (`/tmp/surfer-profile-*`).
- **Start:** if no session runs for the device, the server asks for its saved
  state (`needRestore`). The device sends it (`restoreBegin`, one `restoreLocal`
  per origin, `restoreEnd`); the server then:
  1. restores cookies with `Storage.setCookies`;
  2. restores localStorage through a helper page that is never shown: for each
     origin it navigates there with every request intercepted (`Fetch`) and
     answered with an empty document, so nothing goes over the network, and
     writes the items with a script;
  3. opens the saved tabs; each tab first gets its sessionStorage the same way,
     then loads its page, and the helper history entries are dropped
     (`Page.resetNavigationHistory`).
- **Live sync to the device:**
  - **Cookies:** `Storage.getCookies` every second, diffed; changes go out as
    `cookies {set, removed}`. Chromium has no cookie change event, so the 1 s
    poll is the real-time path; it also catches cookies set by scripts.
  - **localStorage and sessionStorage:** `DOMStorage` events of every tab, sent
    as `storage` messages (sessionStorage with its tab).
  - **Tabs and settings:** from `tabs`, `tabUpdate` and `settings`.
  - While no device is connected, sync messages are queued and delivered on
    reconnect.
  - **Not synced:** IndexedDB and the HTTP cache (large, no change events), and
    storage of cross-site iframes that run in their own process.
- **Disconnect:** the session keeps running; the device can resume it. After
  `IDLE_STOP_MIN` minutes (default 30, `0` = never) without a connection, or on
  `forget` ("Clear browsing data"), Chromium is closed and the profile deleted.
- **One connection per device.** A new connection with the same ID takes over;
  the old one gets `sessionTakenOver` and is closed.
- New windows (`target=_blank`, `window.open`) become tabs.

### 3.2 Emulation (per tab)

- `Emulation.setDeviceMetricsOverride({width, height, deviceScaleFactor, mobile:true})`
  from the client's `viewport`.
- `Emulation.setTouchEmulationEnabled({enabled:true, maxTouchPoints:5})`.
- User agent: current Android Chrome, matching the server's Chromium major
  version.
  - `tablet` (default): no `Mobile` token.
  - `phone`: with `Mobile`.
  - `desktop`: desktop Linux UA, `mobile:false`.

### 3.3 Screen updates

Sending the whole screen for every change does not work: many pages animate
something small all the time (Wikipedia's pulsing bookmark dot made Chromium
emit ~24 full frames per second, 118 KB each, so the page never became still or
sharp). Surfer therefore sends only what changed, like a remote desktop.

- Chromium is started with `--force-device-scale-factor=<device dpr>` (from
  `hello.viewport`). Without it the screencast renders at CSS size while the
  page moves and only screenshots use the emulated pixel ratio.
- `Page.startScreencast({format:'png', maxWidth, maxHeight})` on the active tab:
  lossless frames at the device's physical resolution. PNG costs nothing in
  frame rate (53 fps measured, same as JPEG).
- Each frame is decoded (`sharp`/libvips) and compared with the previous one in
  32×32 tiles (`tiles.ts`). Changed tiles are merged into rectangles and sent:
  - more than 35 % of tiles changed (scrolling): one full-picture JPEG (q80);
  - a single rectangle over 15 % of the screen: JPEG;
  - everything else: lossless PNG (`IDLE_FORMAT=webp`: lossless WebP).
- **Sharpening:** tiles last sent as JPEG are resent losslessly once they have
  been unchanged for 300 ms, so the picture is pixel-exact whenever it is still,
  even while an animation runs elsewhere.
- The first picture of a tab (start, tab switch) comes from one
  `Page.captureScreenshot`, because Chromium only sends frames on change.
- **Flow control:** Chromium's frame ack is held while the client has two
  updates unacknowledged; frames that arrive while one is being processed are
  skipped (only the newest matters, since each update is computed against the
  last picture sent).
- The client applies updates strictly in order and acknowledges each after
  painting it.

### 3.4 Injected page script

`server/src/inject/page.js`, installed with
`Page.addScriptToEvaluateOnNewDocument({worldName:'__surfer', runImmediately:true})`
in every frame. It runs in an **isolated world**: it shares the DOM with the page
but the page's scripts cannot see or call it. It reports to the server through
`Runtime.addBinding({name:'__surferEmit', executionContextName:'__surfer'})`, and
the server calls its helpers (`__surferIme.state/select/selectAround/textAt/reveal`)
with `Runtime.evaluate` in the context of the frame whose field has focus.
Its jobs:

| Concern | Behaviour |
|---|---|
| Editable focus | Reports focus and blur for input, textarea and contenteditable, with the field's text, selection, composition range, input type, `enterkeyhint` and rect. |
| Edit state | Sends `editState` on `input` and `selectionchange` while a field is focused. For contenteditable, the text is the current block and the offsets are within that block. |
| Pickers | `<select>` and `input[type=date,time,datetime-local,month,week,color]`: blocks the default action (capture phase `pointerdown`/`mousedown`/`keydown`), overrides `showPicker`, and sends the options or value. |
| Context menu | On `contextmenu`, reports the link, image and selection under the point and prevents the browser's own menu. |
| Selection | On `selectionchange` (outside editable fields), reports the start and end caret rects and the selected text. |
| Clipboard | On `copy`/`cut`, reports the selected text. |
| Scroll target | On `touchstart`, finds the nearest scrollable ancestor under the finger. While it scrolls, reports its rect, scrollLeft/Top and id every animation frame. |

The server applies picker results by setting `.value` / `.selectedIndex` and
dispatching `input` and `change` (`bubbles:true`).

### 3.5 Input

- **Touch:** `Input.dispatchTouchEvent`. The client's `MotionEvent` time is
  converted to server time (clock offset from `ping`/`pong`) and passed as
  `timestamp`, so Chromium computes fling velocity correctly. Chromium then
  handles taps, inner-container scrolling, fling and pinch natively.
  - **Fallback:** if fling proves unreliable, the server animates
    `Input.dispatchMouseEvent({type:'mouseWheel'})` at 60 Hz with a decaying
    delta (`fling` message).
- **Ordering:** every edit operation from the device carries `seq`; operations
  run strictly in order per tab, and each `focus`/`editState` sent back carries
  the `seq` of the last operation applied. The device ignores states older than
  its latest edit, so fast typing is never undone by a stale state.
- **Coordinates:** the device sends CSS pixels of the screen; CDP input
  takes CSS pixels of the page and multiplies them by the page scale. The server
  divides by the page scale of the latest picture, so taps land right on pages
  shown zoomed out (no mobile viewport) or pinch-zoomed.
- **Keyboard and viewport:** while the keyboard is open the device reports a
  shorter viewport (the part above the keyboard); the server then scrolls the
  focused field to the middle of it if it is not fully visible, like Chrome. In
  an editor taller than the screen, the caret's paragraph is revealed instead.
- **IME mapping:**

| Android `InputConnection` | Message | CDP |
|---|---|---|
| `setComposingText` | `compose` | `Input.imeSetComposition` |
| `commitText` | `commit` | `Input.insertText` |
| `finishComposingText` | `finishCompose` | `Input.insertText` (current composition) |
| `deleteSurroundingText` | `deleteSurrounding` | script selects the range, then Backspace `dispatchKeyEvent` |
| `setSelection` | `setSelection` | script sets the selection |
| `setComposingRegion` | `setComposingRegion` | script selects the region, then `imeSetComposition` with the same text |
| `sendKeyEvent` | `key` | `Input.dispatchKeyEvent` `rawKeyDown`/`char`/`keyUp` |

- **Paste:** the script dispatches a synthetic `paste` event with a
  `DataTransfer` holding the text. If the page doesn't cancel it, the server
  calls `Input.insertText`.

### 3.6 Browser events

| Event | Handling |
|---|---|
| `Page.javascriptDialogOpening` | `dialog` to the client; `Page.handleJavaScriptDialog` with its reply |
| `Fetch.authRequired` (`Fetch.enable{handleAuthRequests:true}`) | `authRequired`; `Fetch.continueWithAuth` |
| Permissions | denied by default via `Browser.setPermission` |
| Downloads | `Browser.setDownloadBehavior({behavior:'deny', eventsEnabled:true})`, `notice` to the client |
| URL bar text that isn't a URL | search with the user's engine (default Google) |

**URL bar suggestions:** while the user types, the device sends `suggest`
(debounced 80 ms). The server fetches the search engine's OpenSearch suggest
service (Google, Bing and DuckDuckGo are known; others get none) without
cookies, cancels any older request, and answers `suggestions` with up to six
queries or sites. History suggestions come from the device itself (§4.1).

### 3.7 Settings

Per device: `layout` (`tablet`, `phone`, `desktop`) and `searchEngine` (a URL with
`%s`). The device stores them and sends them in `restoreBegin`; changes go
through `setting` and come back as `settings`.

### 3.8 Security

- Non-root user. Chromium sandbox **on**, using Docker's default seccomp
  profile plus `clone`/`unshare`/`setns` (`server/seccomp-chromium.json`),
  `init: true`, `shm_size: 1gb`. No `--no-sandbox`, no `--privileged`.
- The container has a compose `mem_limit` (`SURFER_MEM_LIMIT`, default 6g).
- Anyone on the home network can install the app and get their own, empty
  session (like Puffin: no approval). They cannot reach another device's
  session or data.
- **TLS:** on first start the server creates an RSA-2048/SHA-256 self-signed
  certificate in `/data/tls`. Node 22's defaults negotiate TLS 1.2 with
  `ECDHE-RSA-AES128-GCM-SHA256`, which Android 4.4 supports.
- **Install port 8687** (plain HTTP) serves only the APKs (`/app.apk`,
  `/x10.apk`), because old browsers can't open the self-signed HTTPS. Disable
  with `INSTALL_PORT=0`.
- **Legacy port (off by default, `LEGACY_PORT=8688`):** Android 2.x speaks TLS
  1.0 at most. This second listener accepts TLS 1.0 with only
  `DHE-RSA-AES128-SHA` and `DHE-RSA-AES256-SHA` (ephemeral Diffie-Hellman,
  so forward secrecy; 2048-bit group). OpenSSL 3 needs `@SECLEVEL=0` for TLS
  1.0. The main port keeps TLS 1.2+ only. The certificate is the same and is
  pinned by both apps, so a man in the middle cannot present another one.
- **Image formats:** the device lists what it decodes in `hello.caps.formats`;
  WebP is sent only to devices that list it (Android 2.x has no WebP).

### 3.9 Host notes

- **Windows (Docker Desktop):** allow inbound TCP 8686 and 8687 (and 8688 for
  the X10 app) in Windows
  Firewall on the Private profile, set the Wi-Fi to Private, and stop the
  laptop from sleeping while in use.
- **Linux:** nothing extra.
- **Both:** give the server a fixed LAN address (DHCP reservation).

## 4. Tablet app

Java, plain Android SDK, `minSdk 19`, `targetSdk 36`, no AndroidX.

- **Connection:**
  - OkHttp 3.12.13, the last version that supports 4.4.
  - An `SSLSocketFactory` wrapper forces `TLSv1.2`.
  - The `TrustManager` trusts only the certificate bundled in `res/raw`.
  - The hostname check is replaced by certificate equality, so a new server IP
    doesn't break the connection.
  - Reconnects with backoff and shows a status indicator.
- **Screens:** server setup (address only, first start) → browser.
- **Browser screen:**
  - `FrameView` below the top bar; system bars stay visible unless "Full screen"
    (immersive mode) is chosen in the menu. Updates are decoded on one background
    thread and applied on the main thread in order: a full-picture update is
    decoded into a reused bitmap (`inBitmap`, `inMutable=true`) and swapped in;
    smaller rectangles are drawn onto the current picture.
  - Top bar: back, forward, reload/stop, URL field (with a clear button while
    editing, like Chrome), tab count, menu.
  - URL suggestions, like Chrome's, over the page while typing: what was
    typed (go or search), up to 3 history matches and up to 5 search engine
    suggestions. The arrow on a row copies it into the URL field. History
    matches need the typed text to start the address (without scheme and
    `www.`) or every typed word to start a word of the title or address; they
    rank by frequency and recency.
  - Menu: new tab, layout mode, full screen, debug log, change server, clear
    browsing data.
  - Tab switcher.
- **Touch:** every `MotionEvent` becomes a `touch` message with all active
  pointers and the event time.
- **Scroll prediction:** while the finger moves, the app shifts the last frame
  locally to cover latency. If the page reported an inner scroll target, only
  that rectangle moves and the rest of the frame (for example a fixed header)
  stays still. Each new frame carries the scroll state it was taken at, so the
  remaining shift is the finger movement not yet reflected in the frame.
- **Keyboard:**
  - A custom `View` returns a `BaseInputConnection(view, true)` subclass whose
    `Editable` mirrors the focused field.
  - `editState` from the server updates the mirror and calls
    `InputMethodManager.updateSelection`.
  - A new field calls `restartInput`.
  - On `focus` the keyboard is shown; on `blur` it is hidden.
  - When the keyboard opens or closes, `viewport` is re-sent with the visible
    height. The window does not resize (`adjustNothing`), and Android reports no
    keyboard to such windows, so an invisible zero-width popup window with
    `adjustResize` measures where the keyboard starts. This also works in full
    screen. The URL bar's keyboard does not resize the page.
- **Native UI:**
  - `AlertDialog` lists for selects (single and multiple).
  - `DatePickerDialog` / `TimePickerDialog`.
  - A colour grid.
  - Context menu.
  - Selection handles with a copy/select-all bar.
  - JS dialogs.
  - HTTP-auth dialog.
- **Clipboard:**
  - `clipboardCopy` → `ClipboardManager`.
  - Paste (menu or context menu) → `paste`.
- **Debug:** `log` messages to the server when debug mode is on; crash stack
  traces are kept and sent on the next connection.

### 4.1 Saved browser state

- `StateStore` holds cookies, localStorage (per storage key), tabs with their
  sessionStorage, the active tab and settings. It applies the server's sync
  messages on its own thread and writes the changed parts 0.5 s later:
  `files/state/cookies`, `files/state/tabs`, `files/state/history`,
  `files/state/local/<sha1(key)>`.
- History (up to 2000 pages: address, title, visit count, last visit) is
  recorded from `tabUpdate` messages. It stays on the device and is never sent
  to the server; it only feeds URL suggestions.
  Writes are atomic (temp file + rename).
- **Encryption:** each file is AES-256-GCM. The AES key is random and stored
  wrapped by an RSA key in the Android keystore (hardware-backed on most
  devices). Without a working keystore the key is kept in the app's private
  preferences. If the keystore loses its key, the saved state is unreadable and
  is reset.
- On `needRestore` it sends everything back. `restoreLocal` messages are sent one
  origin at a time, waiting whenever OkHttp's send queue holds more than 4 MB
  (OkHttp closes the socket above 16 MB).
- "Clear browsing data" sends `forget`; the server wipes the session and answers
  `forgotten`, and the device deletes its files.

### 4.2 X10 app (Android 1.6+)

`android-x10/`, package `surfer.x10`, minSdk 4. A copy of the tablet app
adapted to Android 1.6 and 2.x; the tablet app is not constrained by it. Lint fails the
build on any platform call newer than API 4 (`NewApi`, `InlinedApi`); multitouch (API 5) is used only where the device has it.
aapt2 always writes UTF-8 string pools (Android 2.2+), which Android 2.1 cannot
parse; the builder rewrites them as UTF-16 (`android-builder/Utf16Pools.java`)
and signs the APK again, v1 only.

| Tablet app | X10 app | Why |
|---|---|---|
| OkHttp WebSocket, TLS 1.2 | own RFC 6455 client (`net/WebSocket.java`) over TLS 1.0, legacy port | no library supports 2.1; 2.1 has no TLS 1.2 |
| Certificate pinned through a trust store | trust manager comparing the certificate bytes | same guarantee, simpler on 2.1 |
| AES-256-GCM, key wrapped by the Android keystore | AES-256-CBC + HMAC-SHA256 (encrypt-then-MAC), keys in private preferences | no GCM, no keystore on 2.1 |
| Picture decoded into reused ARGB bitmaps | every update painted onto one RGB 565 picture | no `inBitmap` before 3.0; half the memory; the X10 screen is 16-bit |
| `adjustNothing` + popup window measuring the keyboard | `adjustResize`; in full screen the visible display frame | simpler where the window can resize |
| PopupMenu in the bar | options menu (hardware Menu key); toggles titled with what they do | no PopupMenu, no check marks in 2.x menus |
| Immersive full screen | `FLAG_FULLSCREEN` (status bar only) | no system UI flags |
| Bar: back, forward, reload, URL, tabs, menu | back, reload, URL, tabs; forward in the menu | 320 dp wide screen |
| WebP accepted | JPEG and PNG only | no WebP decoder |

The default layout is `phone`. The X10 has a single-touch screen, so there is
no pinch zoom; double-tap zoom works where the page allows zooming.

## 5. Protocol (version 1)

- One WebSocket: `wss://<host>:8686/ws`.
- Text messages are JSON `{"t":"<type>", ...}`; binary messages are frames.
- Coordinates are CSS pixels in the viewport. Times are milliseconds.

### 5.1 Handshake

```
C→S hello        {proto:1, app:"tablet", appVersion, deviceId, device:{model, sdk},
                  viewport?:{w,h,dpr}, caps:{maxTouchPoints, formats}}
S→C ready        {resumed:true, settings}         // a session is running: resume it
  or
S→C needRestore  {}                               // no session: send the saved state
C→S restoreBegin {cookies:[Cookie], tabs:[{url, session}], active, settings}
C→S restoreLocal {key, items}                     // one per origin
C→S restoreEnd   {}
S→C ready        {resumed:false, settings}
C→S viewport     {w, h, dpr}                      // on any change
S→C tabs         {tabs:[TabInfo], activeId}
```

`deviceId` is base64url, 22–128 characters. `Cookie` is Chromium's
`Network.Cookie` object as sent in `cookies` messages; `session` and storage
items map storage keys (e.g. `https://mail.google.com/`) to `{name: value}`.

`TabInfo = {id, title, url, loading, progress, canBack, canForward}`

`ping {t0}` → `pong {t0, server}` gives the round-trip time and the clock
offset (`server` is the server's `Date.now()`). The client sends a ping every
2 s and converts its event times to server-clock milliseconds before sending.

### 5.2 Client → server

| Type | Fields |
|---|---|
| `viewport` | `w, h, dpr` |
| `navigate` | `tabId, input` |
| `suggest` | `id, q` (URL bar text) |
| `back` `forward` `reload` `stop` | `tabId` |
| `tabNew` | `url?` |
| `tabClose` `tabActivate` | `tabId` |
| `touch` | `phase: start\|move\|end\|cancel`, `time` (server clock), `points:[{id,x,y,rx?,ry?,force?}]` — all active pointers; `end`/`cancel` carry none |
| `fling` | `x, y, vx, vy` (fallback only) |
| `wheel` | `x, y, dx, dy` (desktop debug client) |
| `key` | `phase: down\|up`, `key, code, keyCode, mods` (Alt 1, Ctrl 2, Meta 4, Shift 8) |
| `commit` | `text, cursor` |
| `compose` | `text, selStart, selEnd` |
| `finishCompose` | — |
| `deleteSurrounding` | `before, after` |
| `setSelection` | `start, end` |
| `setComposingRegion` | `start, end` |
| `paste` | `text` |
| `pickerReply` | `pickerId, cancel?, value?, selected?:[index]` |
| `contextAction` | `menuId, action: open\|openNewTab\|copyLink\|copyText\|copyImageUrl\|paste\|selectAll` |
| `selectionDrag` | `handle: start\|end, x, y` |
| `selectionAction` | `action: copy\|selectAll\|clear` |
| `dialogReply` | `dialogId, accept, text?` |
| `authReply` | `authId, cancel?, user?, password?` |
| `frameAck` | `frameId` |
| `setting` | `key, value` |
| `forget` | — (end and wipe the session; the device clears its state) |
| `log` | `level, msg` |
| `ping` | `t0` |

### 5.3 Server → client

| Type | Fields |
|---|---|
| `tabs` | `tabs, activeId` |
| `tabUpdate` | `TabInfo` |
| `focus` | `fieldId, inputType, multiline, enterKeyHint, rect` + edit state |
| `editState` | `fieldId, text, selStart, selEnd, compStart, compEnd` |
| `blur` | — |
| `suggestions` | `id, q, items:[{text, kind: search\|url}]` |
| `picker` | `pickerId, kind: select\|date\|time\|datetime-local\|month\|week\|color`, `multiple?, options?:[{label,value,selected,disabled,group?}], value?, min?, max?, step?` |
| `contextMenu` | `menuId, x, y, link?:{href,text}, image?:{src,alt}, selectionText?, editable` |
| `selection` | `active, text, start?:{x,y,h}, end?:{x,y,h}` |
| `clipboardCopy` | `text` |
| `dialog` | `dialogId, kind: alert\|confirm\|prompt\|beforeunload, message, defaultText?` |
| `authRequired` | `authId, origin, realm, scheme` |
| `notice` | `kind, text` |
| `settings` | `layout, searchEngine` |
| `cookies` | `set:[Cookie], removed:[{name, domain, path, partitionKey?}]` |
| `storage` | `area: local\|session, key, tabId? (session), op: set\|remove\|clear, name?, value?` |
| `forgotten` | — |
| `sessionTakenOver` | — |
| `error` | `code, message` |
| `pong` | `t0, server` |

### 5.4 Screen update (binary, little-endian)

A 60-byte header, then `rectCount` rectangles, each a 16-byte header followed
by its encoded image. The client paints each image at (x, y) onto its current
picture.

| Offset | Type | Field |
|---|---|---|
| 0 | u8 | message type = 1 |
| 1 | u8 | flags: bit 0 keyframe (new picture), bit 1 scroll target valid, bit 2 sharpening only |
| 2 | u16 | rectCount |
| 4 | u32 | frameId |
| 8 | u32 | tabId |
| 12 | u16 | picture width (physical px) |
| 14 | u16 | picture height (physical px) |
| 16 | f32 | page scale factor |
| 20 | f32 | scrollX |
| 24 | f32 | scrollY |
| 28 | f32 | offsetTop |
| 32 | f32 ×4 | scroll target rect x, y, w, h |
| 48 | f32 | scroll target scrollLeft |
| 52 | f32 | scroll target scrollTop |
| 56 | u32 | scroll target id |
| 60 | … | rectangles |

Rectangle:

| Offset | Type | Field |
|---|---|---|
| 0 | u16 ×4 | x, y, w, h (physical px) |
| 8 | u8 | format: 0 JPEG, 1 PNG, 2 WebP |
| 9 | u8 ×3 | reserved |
| 12 | u32 | image length |
| 16 | … | image bytes |

## 6. Building and installing the app

- **`android-builder` image:**
  - Eclipse Temurin JDK 17, Android cmdline-tools, `platforms;android-36`,
    `build-tools;36.0.0`.
  - Gradle 9.8.2, AGP 9.5.0.
  - `android.builtInKotlin=false`, Java 11 source, v1+v2 signing.
- **Build:** `docker compose run --rm android-builder` (tablet) or
  `docker compose run --rm android-builder-x10` (X10)
  1. copies `/data/tls/server.crt` into `res/raw/server.crt`;
  2. signs with the keystore in the `android-keystore` volume, which is reused
     so updates install over the old version (v1 signature with SHA-1 digests
     for API < 18, chosen by apksigner from minSdk);
  3. writes `/data/apk/surfer.apk` or `/data/apk/surfer-x10.apk`.
- **Install:** on the device, enable Settings → Security (Applications on 2.x)
  → Unknown sources once, then open `http://<server>:8687/app.apk` (tablet) or
  `http://<server>:8687/x10.apk` (X10) in the browser.

The certificate is generated by the server on first start, so the server must
run once before the first app build. If `/data/tls` is deleted, the app must be
rebuilt.

## 7. Repository layout

```
Surfer/
├─ DESIGN.md
├─ docker-compose.yml          services: surfer, android-builder, android-builder-x10
├─ server/
│  ├─ Dockerfile, package.json, tsconfig.json, seccomp-chromium.json
│  ├─ src/
│  │  ├─ main.ts               HTTPS + WS server, install server
│  │  ├─ protocol.ts           message types, frame header
│  │  ├─ config.ts, log.ts
│  │  ├─ tls.ts                certificate generation
│  │  ├─ connection.ts         one device connection: handshake, restore
│  │  ├─ session.ts            Chromium per device, temporary profile, tabs
│  │  ├─ sync.ts               cookie diff, DOM storage events
│  │  ├─ restore.ts            writing saved storage into a fresh browser
│  │  ├─ settings.ts           per-device settings
│  │  ├─ tab.ts                emulation, navigation, events
│  │  ├─ screencast.ts         incremental updates, sharpening, flow control
│  │  ├─ tiles.ts              tile diff and rectangle merging
│  │  ├─ input.ts              touch, keys, fling fallback
│  │  ├─ ime.ts                IME mapping
│  │  ├─ ui-bridge.ts          pickers, menus, selection, dialogs, auth
│  │  └─ inject/page.js        injected page script
│  ├─ debug/                   desktop debug client (same protocol)
│  └─ test/                    unit and integration tests
├─ android/                    tablet app (Gradle project): net/, view/, state/
├─ android-x10/                X10 app for Android 1.6+ (Gradle project, §4.2)
└─ android-builder/            Dockerfile, build.sh
```

## 8. Build phases

1. **Foundation:**
   - Server container with the sandbox (check `chrome://sandbox`), TLS,
     screencast and the debug client.
   - Device identity, browser state stored on the device and synced live,
     restore into a temporary profile, idle shutdown.
   - APK builder and install port.
   - App: setup, TLS connection, FrameView, touch, URL bar, navigation.
   - Measure fps, fling and long-press.
2. **Text input:** keyboard and full IME with state sync, viewport resize. Goal:
   write and send an email in Gmail with autocorrect.
3. **Native UI:** pickers, context menu, selection and copy, clipboard, JS and
   auth dialogs, notices.
4. **Smoothness:** scroll prediction per container, auto-hiding bar.
5. **Later:** bookmarks, downloads, audio. Much later: H.264 video,
   uploads.

## 9. Verification

- **Unit tests (vitest):** frame header, message validation, URL input, cookie
  diff, storage keys, settings. Android (JUnit): frame header, server URLs.
- **End-to-end probe** (`server/test/e2e.mjs`, acts as a device): page loads,
  fling (`SWIPE=1`), and the sync round trip (`SYNC=1`: browse, forget the
  session, restore from the synced state, check the cookie is back).
- **Integration tests** in the container against local test pages: pickers,
  inner scroll target, contenteditable IME sequence, dialogs, auth.
- **Security:** `chrome://sandbox` shows the namespace sandbox; the container runs
  as a non-root user and is not privileged.
- **TLS:** `openssl s_client -tls1_2` negotiates the GCM suite; the app refuses a
  different certificate.
- **On-device checklist after each phase:**
  - install from the install port;
  - Gmail inbox fling with a still header;
  - compose with swipe typing and autocorrect;
  - select and date pickers;
  - link menu, copy, paste;
  - rotate;
  - 5 tabs;
  - Wi-Fi drop and reconnect;
  - container restart keeps site logins and tabs (restored from the tablet);
  - "Clear browsing data" logs out of all sites;
  - a second device starts with its own empty state.

## 10. Measurements

Server side, measured 2026-10-29 on the Windows laptop (Docker Desktop, Chromium
156) with a 800×1280 CSS, DPR 1.5 viewport (1200×1920 px):

| Measurement | Result |
|---|---|
| Sandbox (`chrome://sandbox`) | Namespace layer 1, PID + network namespaces, seccomp-BPF with TSYNC: "adequately sandboxed". Only Yama non-broker ptrace protection is off (a host kernel setting). |
| Fling from raw touch events | Works: a 600 px flick scrolled 2458 px with momentum over ~2.2 s. The `fling` fallback is not needed. |
| Updates while scrolling (incremental pipeline, 601×910 @1.33) | 58 per second, full-picture JPEG ~64 KB each |
| Small animation (Wikipedia's pulsing dot) | ~1 KB lossless updates instead of 118 KB full frames |
| Load until the screen is still and fully sharp | ~1.7 s for Wikipedia / example.com |
| Screencast PNG vs JPEG from Chromium | Both 53 fps during a fling; PNG 424 KB vs JPEG 136 KB per frame (internal only) |
| TLS | TLS 1.2 with ECDHE-RSA-AES128-GCM-SHA256; ECDHE-RSA-AES128-SHA fallback also negotiates |
| Sync round trip (browse → forget → restore) | 8 cookies and Wikipedia's localStorage synced to the device and restored; httpbin sees the restored cookie |

Still to measure on the tablet: decode time per frame, real frames per second,
input-to-frame latency over Wi-Fi, RAM per session. At ~150 KB per frame, 37 fps
would need ~45 Mbit/s; the tablet's decode speed and Wi-Fi will set the real
rate, and flow control adapts to it.

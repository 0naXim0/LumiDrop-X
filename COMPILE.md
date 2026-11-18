# LumiDrop X — Compilation Guide

> **What's this?** LumiDrop X v1.0 is the LumiDrop rebrand of the WebShare X
> engine — the same battle-tested local-network file sharing app (web server at
> `:8888`, QR share, native peer discovery, transfer queue, Quick Play), with a
> new identity: package `com.lumidrop.x`, a new luminous-droplet launcher mark,
> and the Connected Devices card's empty state + device rows repaired. The
> purple dark theme is unchanged, and the P2P wire protocol is byte-identical,
> so old WebShare X peers still discover and transfer with LumiDrop X peers.

> Build time: ~5 minutes on a decent machine on first run (Gradle downloads deps).
> Subsequent builds: ~30 seconds.

---

## Prerequisites

| Tool | Version | Download |
|------|---------|----------|
| Android Studio | Hedgehog 2023.1+ or newer | https://developer.android.com/studio |
| JDK | 17 (bundled with Android Studio) | included |
| Android SDK | API 34 (auto-installed) | via Android Studio |

You do **not** need a physical Android device to build — but you need one to run it
(or an emulator with API 21+).

---

## Method 1 — Android Studio (Recommended, easiest)

### Step 1 — Open the project

1. Launch **Android Studio**
2. Click **"Open"** (not "New Project")
3. Navigate to the `LumiDropX/` folder and click **OK**
4. Wait for the Gradle sync to finish (bottom status bar says "Sync finished")
   - First sync downloads ~150 MB of dependencies — let it run

### Step 2 — Accept SDK licence (first time only)

If a yellow banner says *"Install missing SDK components"*, click **"Install"** and accept.

### Step 3 — Build the APK

#### Option A — Debug APK (for testing, no signing needed)
```
Menu → Build → Build Bundle(s) / APK(s) → Build APK(s)
```
Output: `app/build/outputs/apk/debug/app-debug.apk`

#### Option B — Release APK (for distribution)
```
Menu → Build → Generate Signed Bundle / APK → APK
```
Follow the wizard to create or use a keystore.  
Output: `app/build/outputs/apk/release/app-release.apk`

### Step 4 — Install on your device

**Via Android Studio:**
- Plug in your phone via USB, enable **USB Debugging** (Developer Options)
- Click the green ▶ **Run** button

**Via ADB (command line):**
```bash
adb install app/build/outputs/apk/debug/app-debug.apk
```

**Via file transfer:**
- Copy the APK to your phone and open it
- You may need to enable "Install from unknown sources" in Settings → Security

---

## Method 2 — Command Line (WSL2 / Linux / macOS)

### Prerequisites
- JDK 17 installed: `sudo apt install openjdk-17-jdk` (Ubuntu/Debian)
- Android SDK installed and `ANDROID_HOME` set

```bash
# Set ANDROID_HOME if not already set
export ANDROID_HOME=$HOME/Android/Sdk
export PATH=$PATH:$ANDROID_HOME/platform-tools

# Enter project directory
cd LumiDropX

# Make gradlew executable
chmod +x gradlew

# Build debug APK
./gradlew assembleDebug

# Output:
# app/build/outputs/apk/debug/app-debug.apk

# Install directly (device must be connected)
./gradlew installDebug
```

### Windows (PowerShell)
```powershell
cd LumiDropX
.\gradlew.bat assembleDebug
```

---

## Troubleshooting

### "Gradle sync failed" / can't download dependencies
- Check your internet connection
- If behind a proxy: **File → Settings → Appearance & Behavior → System Settings → HTTP Proxy**
- Try: `File → Invalidate Caches → Invalidate and Restart`

### "SDK location not found"
- Open Android Studio once to install the SDK, or:
- Create `local.properties` in the project root:
  ```
  sdk.dir=/path/to/your/Android/Sdk
  ```
  On Windows: `sdk.dir=C\:\\Users\\YourName\\AppData\\Local\\Android\\Sdk`

### "Minimum supported Gradle version is X"
- Open `gradle/wrapper/gradle-wrapper.properties`
- Update the `distributionUrl` to match the version Android Studio wants

### App installs but crashes immediately
- Check **Logcat** in Android Studio (filter by `com.lumidrop.x`)
- Most likely cause: storage permission not granted on Android 11+
  → Settings → Apps → LumiDrop X → Permissions → Files → Allow all

### QR code shows but browser can't connect
1. Make sure your PC/laptop is on the **same network** as the phone
   (connected to the phone's hotspot, or same Wi-Fi router)
2. Try the URL manually: `http://<ip>:8888`
3. Check phone's firewall — some Android ROMs block local HTTP servers
4. Try a different port by changing `PORT = 8888` in `MainActivity.kt`

### Upload doesn't save the file
- On Android 11+, MANAGE_EXTERNAL_STORAGE must be granted
- Go to **Settings → Apps → LumiDrop X → Permissions → Files and media**
  → select **"Allow management of all files"**

---

## Project Structure (for developers)

```
LumiDropX/
├── app/src/main/
│   ├── java/com/lumidrop/x/
│   │   ├── MainActivity.kt          ← App shell: bottom nav + fragment host
│   │   │                              + blackout overlay + floating transfer popup
│   │   ├── SendFragment.kt          ← "Send" tab (host dashboard, quick share, recent files)
│   │   ├── TransfersFragment.kt     ← "Transfers" tab (queue/active/completed)
│   │   ├── settings/
│   │   │   ├── SettingsFragment.kt  ← "Settings" tab (card-grouped settings)
│   │   │   ├── SettingsManager.kt   ← SharedPreferences-backed settings
│   │   │   └── WebUiSettingsActivity.kt  ← Advanced WebUI config (drill-down screen)
│   │   ├── ui/
│   │   │   ├── BottomNavBar.kt      ← Custom bottom nav (not MDC — see its own doc comment)
│   │   │   ├── Widgets.kt           ← Shared button/card/switch/icon builders
│   │   │   └── UiTokens.kt          ← Motion durations, dp() helpers
│   │   ├── media/
│   │   │   ├── ThumbnailShared.kt   ← Shared thumbnail-decode strategies (native + web)
│   │   │   └── ThumbnailEngine.kt   ← Native-side async thumbnail cache
│   │   ├── server/
│   │   │   ├── LumiDropServer.kt    ← NanoHTTPD server + all API routes
│   │   │   └── MimeTypes.kt         ← Extension → MIME type map
│   │   └── utils/
│   │       └── NetworkUtils.kt      ← IP address detection (incl. multi-interface)
│   ├── assets/
│   │   └── index.html               ← Complete web UI (single file)
│   └── res/
│       └── layout/
│           ├── activity_main.xml    ← Shell layout (nav bar + fragment container)
│           └── fragment_send.xml    ← Send tab's layout
```

Settings, including the HTTP port, are configured through the Settings tab in
the running app (SettingsManager persists them) — not by editing source and
rebuilding. Nice for iterating, but if you're changing a *default*, that
lives in SettingsManager.kt's `getPort()` etc.

## Changing the upload destination

Edit `LumiDropServer.kt`, in `apiUpload()`, change `Environment.DIRECTORY_DOWNLOADS`
to any other public directory constant — or just change it from the app's
own Settings tab (Storage & Paths card) without touching code at all.

---

## Key dependencies used

| Library | Purpose | Version |
|---------|---------|---------|
| NanoHTTPD | Embedded HTTP server | 2.3.1 |
| ZXing core | QR code generation | 3.5.3 |
| Kotlin Coroutines | Async ops | 1.7.3 |
| AndroidX AppCompat | UI compatibility, app theme | 1.6.1 |
| Material Components | *declared, not used* — see ui/UiTokens.kt's doc comment for why the Phase 15-17 redesign deliberately avoids Theme.MaterialComponents (a real API-21 crash history) | 1.11.0 |

All dependencies are pulled automatically by Gradle — no manual downloads needed.

---

## Pass 5 (LumiDrop X — full pass-4/5 feedback round)

**Identity**: package `com.lumidrop.x`, display name LumiDrop X everywhere
(launcher label, notifications, web client branding, PWA manifest, login
page). `versionCode` 40, `versionName` 1.0.0. This is the LumiDrop rebrand
of the WebShare X engine — the P2P wire protocol (discovery magic `whx1`,
ports, `/api/native/*` endpoints) is deliberately unchanged, so old
WebShare X peers keep discovering and transferring with LumiDrop X peers.

**What pass 5 fixes on top of the pass-3 baseline** (this build):

| Area | Fix |
|------|-----|
| Connected Devices card | Empty-state layout regression fixed (the list wrapper stayed visible with zero devices and split the card height — clipping the ring, hiding the text); device rows get a permanent accent "aurora" on the PARENT card only, drawn behind content |
| App picker | Launcher icons flattened to one small bitmap per app (off the main thread, cached) — no more adaptive-icon compositing / texture churn while scrolling |
| Recents dismiss | Swiping the app from Recents now fully stops server, transfers, discovery, Wi-Fi lock and notifications |
| Boot flicker | The server bakes the session's access mode into the page before serving it — a Transfer-Mode client never sees even one frame of the Full Access UI |
| iPad 4 / iOS 10.3 | A sub-pixel CSS keep-alive animation on the transfer dock keeps old WebKit's render loop scheduling frames — progress bars keep painting while the screen is untouched |
| Transfer previewer | A settled batch always collapses and disappears (settle watchdog) — no permanent "Done" tile; the completion toast remains |
| Last-file Waiting | Never-requested Quick-Share dispatches are detected and finalized after 25 s, and the web client silently re-fires the exact URL once — the file still arrives |
| Transfer Mode | Uses the same floating transfer dock as Full Access (the old in-card transfers list is gone) — one model, one renderer |
| Quick Play | The player picker is redesigned to the app's dark + purple design language (gradient brand hairline, icon chip, pill filename, accent-spine default card) |

**Resumability** (inherited from the final engine, verified present):
web-to-native uploads resume at byte level (`/api/upload/resume` + re-opened
transfer ids + partial files); browser downloads are served with full
`Range`/`ETag`/`If-Range` support so download managers and streaming players
resume cleanly; native-to-native pushes resume from the receiver's verified
byte offset. Stock iOS 10 Safari's own download UI does not byte-resume
downloads (a browser limitation, not a server one) — a re-tapped download
restarts from byte 0, which the server serves correctly.

**Release signing (bundled keystore)**: `keystore/lumidropx-release.jks`
(alias `lumidropx`, store/key password `lumidropx100`) ships in the source
archive. No `com.lumidrop.x` APK shipped before this build, so this key
defines the signing identity from day one — keep it to sign every future
LumiDrop X pass so updates install cleanly over this one. If Android refuses
to install over an older LumiDrop X APK, that older build was signed with a
different key — uninstall it first.

Build commands (JDK 17 + Android SDK 34 + Gradle 8.5):

```
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk   # platform 34 + build-tools 34.0.0
./gradlew assembleRelease
# -> app/build/outputs/apk/release/app-release.apk (signed, R8-minified)
```

`local.properties` is intentionally not shipped — Android Studio creates it
automatically; CLI builds use `ANDROID_HOME` as above.

**Version & archive conventions**: versionName stays `1.0.0`; source
archives continue numbering separately ("LumiDrop X v1.0 pass N.zip").

## Pass 6 (previewer gating, fade rework, Auto-Send engine, cancel propagation)

Pass 6 is the post-pass-5 feedback round, on top of the same tree
(`com.lumidrop.x`, versionCode **41**, versionName `1.0.0`, same keystore):

- **R-A — previewer only after acceptance (web)**: a targeted Quick-Share
  send pre-registers a PENDING "Waiting" row per client the moment the host
  taps Send, and those rows used to ride the client's own state payload —
  so the receiver's transfer previewer auto-expanded and listed incoming
  files as "Waiting" BEFORE any acceptance. `clientTransfersJson` now holds
  a PENDING Quick-Share row back while its queue item is still live and
  unconsumed by THAT client; the incoming-files list owns the undecided
  offer, and the previewer opens only once the client accepts (or its
  Auto-Accept does). Terminal and accepted-but-queued rows are untouched.
- **R-B — Connected Devices scroll-edge fades, reworked**: the old
  parent-card "aurora" (two 54dp purple bands, permanently visible, and
  rendered INVERTED — `angle="90"` is bottom-to-top in Android, not
  top-to-bottom) is gone. Replaced by two 18dp fades in the CARD'S OWN
  surface color (#22252C), correctly oriented (`angle="270"`), hosted
  inside the device-list wrapper and visible only while that edge actually
  has content past the rim (`canScrollVertically`), crossfaded in Kotlin.
- **R-C — Quick Play default-player picker**: the WebUI settings' picker is
  now the app's own `showLdxDialog` shell (icon+title card, scale+fade
  entrance) with proper option rows — leading glyph, label, trailing check,
  selected row tinted with the app accent — instead of a stock platform
  radio-list AlertDialog.
- **R-D — media picker Albums redesign**: album cards are full-bleed square
  photo covers (single radius, hairline stroke, elevation) with a bottom
  gradient scrim (`bg_album_scrim`) carrying the album name and
  "N items · size" ON the cover — the modern gallery album language.
  Selection keeps the photo cells' exact scrim + outline + check language.
- **R-E — no micro-scroll on Send**: the three main cards used to jiggle by
  a few dp because the content column exceeded the viewport by that much
  (devices-card minHeight floor + rounding), and a NestedScrollView exposes
  every excess as scroll range. A post-layout clamp now trims the bottom
  nav-clearance padding by exactly the small excess (pixel-identical on
  screen — the padding is the last element) so the common case fits the
  viewport with scroll range exactly zero; a large excess (queue cards,
  multi-URL rows) restores full clearance and normal scrolling. Both states
  converge in one pass and cannot oscillate.
- **R-F — Transfers tab pills**: Active/Received/Sent are now an
  icon+label LinearLayout centered as one unit (compound drawables pin to
  the content-box edge, which is what left the icons floating at the pill's
  left rim).
- **R-G — iPad 4 (iOS 10.3.3) incoming list**: the Transfer Mode
  incoming-files list is a BLOCK box on legacy iOS (old WebKit
  flex-shrink-crushes max-height+overflow flex children instead of
  scrolling them — the reported "squeeze"), and the name/button row gets
  `min-width:0` + `flex-shrink:0` fixes so the Get/Cancel buttons can never
  be crushed into zero-width slivers (globally — plain correctness).
- **R-H — cancel reaches every receiver instantly**: cancelling a WAITING
  row on the host now resolves the OFFER, not just the local row.
  Quick-Share: `cancelQuickShareForPeer` scopes the withdrawal to the
  cancelled client (its IP leaves the queue item's target set; other
  targeted clients keep theirs; the item drops when nobody is left) — no
  more Send-tab ghost or still-showing incoming file. Native pushes:
  `propagateCancelForTid` tears the whole push down on both ends (sibling
  rows cancelled, receiver's undecided incoming items declined via
  `cancelWholePush`), and "Cancel all" propagates the same way.
- **R-I — Auto-Send Files, rebuilt**: the old one-line "skip manual Send"
  toggle is now a real engine (`settings/AutoSendEngine.kt`): pick a folder
  (Settings → Transfer Engine), and every file in it is offered
  automatically to each device that connects — browsers via targeted Quick
  Share (one queue item, one pre-registered Waiting row per client,
  receiver's Auto-Accept decides), native apps via one manifest push per
  peer — with a persistent per-device ledger (filesDir JSON, pruned) so
  nothing is ever re-offered to a device that already received it.
  Triggers: new web session, native peer discovery, folder FileObserver,
  45s safety poll. Scope selector (All devices / Browsers / Native apps),
  sent-history line + Clear action in Settings. The master switch migrates
  the old `auto_send_share` value.

The build recipe is unchanged (JDK 17 + SDK 34 + Gradle 8.5 +
`keystore/lumidropx-release.jks`), except `versionCode 41`.

## Pass 7 (versionCode 42)

User feedback round 7 — Auto-Send remade, server-card URL system rebuilt,
combined network toggle, genuine refresh, web-side brand sweep, iPad 4
scrollbar, decluttered Settings.

- **Auto-Send, remade as the plain mirror of Auto-Accept**: the pass-6
  watched-folder engine (`settings/AutoSendEngine.kt`, its ledger, scope
  chips, FileObserver, session/discovery hooks) is DELETED. Auto-Send is
  now one boolean, applied at selection time
  (`SendFragment.stageOrSendItems` → `autoSendNow`): ON means anything
  picked from any Quick Share tile leaves INSTANTLY — browsers get one
  TARGETED Quick Share item per live client (their incoming list shows
  the offer; the receiver's own Auto-Accept decides; the host's Active
  tab pre-registers Waiting rows; the previewer still stays closed until
  acceptance), native peers get the whole batch via
  `sendOrQueueForPeer` (or visibly-waiting Pending rows that fire the
  moment a peer appears). While ON, the queue card never shows at all
  (no queued section) and anything staged before the flip flushes on the
  next tick. OFF is the classic stage-then-Send flow, untouched.
- **Server card — fixed height, one labeled address row**: the URL area
  is now a single constant-height row `[source chip][URL][chevron]`. The
  chip ALWAYS states which network the address is (Wi-Fi / Hotspot / LAN
  / Mobile Data) — one address or several, running or stopped; the
  chevron appears ONLY when more addresses exist and opens a labeled
  list of every running address (per-row Copy + tap-for-QR + Copy all).
  The old `multiUrlContainer` (one stacked row per interface — the thing
  that grew the card) is gone, XML and Kotlin. The all-toggles-off
  fallback also fixes a stale-"Stopped" status bug in the same branch.
- **QR modal — every address, labeled**: the QR dialog now carries one
  chip per enabled network; tapping a chip swaps the QR + URL text (with
  a tiny per-URL LRU bitmap cache so switching never re-encodes). Mobile
  Data shows its honest reachability caption while selected. Copy/Close
  actions on the modal itself.
- **Copy icon — multi-address aware**: one address copies instantly;
  several open a labeled chooser (tap a row to copy it, or Copy all).
- **Combined network toggle**: Settings' "Wi-Fi / LAN" + "Hotspot" rows
  are ONE "Hotspot / Wi-Fi / LAN" switch now (reads as on when either
  legacy key is on; saving writes both). `NetworkUtils` gates both local
  kinds by the same combined rule and PRIORITY-ORDERS all output:
  Hotspot → Wi-Fi/LAN → Mobile Data, so the card row, default QR and
  notification first line are always the most-reachable path.
- **Mobile Data, made honest and first-class**: served on the same
  0.0.0.0 socket as everything else; classified by real transport (not
  name heuristics); listed, QR-able and copyable like any other address;
  ordered last; and labeled with a direct-carrier-address caption (QR
  modal + a `?` note in every address list) explaining that other
  devices reach it only on some networks — the hotspot/Wi-Fi address is
  the guaranteed path. No more mystery-failing scans.
- **Genuine refresh (Connected Devices)**: the refresh button now FORCES
  convergence — `LumiDropServer.forceRefreshSessions()` bumps the state
  version (the SSE flush makes writing to a closed browser's socket
  fail, dropping its stream immediately) and one 3.5s probe window later
  terminates every session with no live stream and no touch (live XHR
  clients poll every 1s, live SSE clients are kept by their stream, so
  only genuinely-closed browsers can be pruned — a wrongly-pruned blip
  re-registers on its next request). Native side:
  `NativeDiscovery.forceExpireStalePeers()` drops peers 12s+ silent on
  the tap instead of greying through the 40s window, plus the immediate
  beacon. The control stays busy ~4s with checkpoint re-renders.
- **Web-side brand sweep**: the sidebar logo tile and the Quick-Play
  sheet's header mark are now the LumiDrop droplet (inline SVG, same
  glyph as the launcher icon and `/icon.png`); the old WebShareX bolt
  glyph is removed from the icon library entirely; XPlayer's card glyph
  is a neutral `play`; a `<link rel="icon">` was added beside the
  apple-touch/manifest entries.
- **iPad 4 / iOS 10.3.3 scrollbar**: the Transfer-Mode incoming list
  gets a persistent slim `-webkit-scrollbar` (accent thumb, quiet track)
  — old iOS draws transient overlay indicators that never show for this
  list; the same rules render identically on the Android client.
- **Settings declutter**: every explanation paragraph is gone; labels
  stand alone and each control that needs context carries a small (i)
  glyph opening an LdxDialog ("Got it" action). Affected: HTTP Port,
  Discovery Name, Hotspot/Wi-Fi/LAN, Mobile Data, Default access,
  Auto-accept, Auto-send, the fixed one-at-a-time line (now a quiet info
  row), Floating Popup, Keep screen on, Connection confirmation,
  Password, Upload Folder, the save note (one line).

Build recipe unchanged (JDK 17 + SDK 34 + Gradle 8.5 +
`keystore/lumidropx-release.jks`), `versionCode 42`. Wire protocol
(`whx1`, port 47624, `/api/native`, `lumidrop://`) untouched —
interops with every earlier pass and stock WSX nodes.

## Pass 8 (versionCode 43)

User feedback round 8 — transfer identity & cancellation overhaul, the
pause/reconnect/resume system, the Cloudflare-verified Internet-sharing
architecture, real sharing-toggle boundaries, and the UI fixes (full URLs,
fade reliability, save redesign, settings declutter, folder scroll, tab
auto-selection).

- **Duplicate Active-tab rows (the "18 rows for 9 files" report), root
  cause fixed**: Auto-Send used to run its native leg unconditionally, so a
  browser-targeted batch ALSO parked "Waiting for device" SEND rows for the
  same files — 9 real transfers plus 9 permanent ghosts. The native leg now
  only runs when it can contribute (a peer is connected, or nothing else
  took the batch). Parked batches now also fire when a BROWSER session
  appears (previously only a native peer's discovery drained them), and a
  new engine sweep resolves any stale PENDING Quick-Share row whose
  (item, client) identity already has a live transfer. Verified by JVM
  unit tests (`app/src/test/…/TransferManagerTest.kt`): exactly nine
  records through queue → accept → download → re-request → completion.
- **Receiver-side Cancel All actually cancels everything**: every incoming
  native push with live work gets ONE whole-push cancellation per sender
  session (scope=push), the sender tears its session down on receipt
  (stopping the dispatch loop — previously it kept streaming the rest of
  the batch into fresh rows), the receiver's own rows resolve CANCELLED
  with staging partials deleted, accepted-but-undelivered items flip to
  declined so late file POSTs are refused ("Not accepted"), and the
  upload-tombstone now covers native-push resurrections too. Receiver-side
  native-push rows are tagged Source.NATIVE_PUSH (they used to inherit
  PLAIN, which made per-row pause/cancel propagation and Cancel-All
  collection invisible to them).
- **Pause / reconnect / resume system**: a connection-loss failure now
  latches an `interrupted` flag — the native Active tab shows
  "Interrupted" (orange) with a Reconnect action for Quick-Share and
  native-push sends, an honest "Waiting for sender…" note for interrupted
  receives, and the web client's existing Interrupted label rides the same
  state. Partial data and resume metadata are preserved for the full
  10-minute window; byte-level resume re-opens the ORIGINAL transfer id
  (progress preserved — unit-tested); deliberate cancels are never labeled
  interrupted; completion still requires full byte coverage.
- **Large-batch (300-file) hardening**: native-push session indexes are
  released at session end (previously 3 × N entries per batch lingered
  until an unrelated later send tripped the 500-entry prune), folder group
  records are pruned when their last member goes, and the 300-file
  claim/complete cycle under 4-thread contention is unit-tested to drain
  completely with both concurrency gates returning to a balanced one-permit
  state. No single smoking-gun crash was identifiable from source alone
  (no logcat was available); the fixes bound every retained-object and
  scheduling path the brief listed.
- **Internet sharing — Cloudflare-verified managed-relay architecture**
  (replaces the decommissioned raw Mobile-Data sharing): the official
  `cloudflared` connector is glibc-linked and does not run on stock
  Android (bionic libc; W^X exec restrictions since Android 10), and
  remotely-managed tunnels need account-scoped tokens that must never ship
  in an APK — verified against Cloudflare's docs, repository (Apache-2.0)
  and community, October 2026. The delivered model is the spec's option 2:
  an in-app relay tunnel client (`remote/RelayTunnelClient.kt`,
  `remote/RemoteTunnelManager.kt`) that connects OUT (TLS, key-authed,
  bounded streams, capped reconnect backoff) to an operator-deployed relay
  — reference implementation `tools/relay/relay-server.js` — which the
  operator fronts with Cloudflare on their own infrastructure (the
  officially supported connector tier; deployment steps in TUNNEL.md).
  Real client IPs are preserved through the loopback bridge via a
  per-instance secret, so the app's own server enforces password /
  Transfer Mode / approval / kick rules on remote clients exactly as on
  LAN. Range requests, seeking, uploads and long transfers are
  byte-transparent. The remote URL appears (server card, QR, notification)
  only while the relay has genuinely accepted the tunnel. Direct
  carrier-address serving is refused with an honest message.
- **Sharing toggles are real boundaries**: LAN/hotspot off → addresses
  vanish everywhere, sessions that arrived via those subnets are closed
  server-side, new requests are refused (Host-header match OR same-/24
  subnet attribution), native discovery beacons stop; Internet off → the
  tunnel tears down fully and its URL disappears; BOTH off → the server
  itself stops (no invisible 0.0.0.0 listener). Enabling either never
  disturbs the other. Loopback (tunnel bridge) is exempt by design.
- **Full URLs everywhere (§2)**: the addresses/copy dialogs' URL rows are
  horizontally scrollable with selectable text — no middle-ellipsis ever;
  the QR modal's URL is selectable and pans instead of overflowing; the
  send-target picker rows wrap to two lines so the IP is never cut; the
  multi-address notification line now includes the port per address.
  App state and clipboard values were never truncated and remain so.
- **Connected-Devices fades**: recomputed on every layout change (a global
  layout listener on the wrapper — the old post() could run before the
  layout pass, which is why fades sometimes only appeared after the first
  manual scroll), and the bottom fade now sits flush with the parent
  card's edge (the wrapper extends into the card's bottom padding; the
  list's own bottom padding keeps the last row clear of the band).
- **Redesigned save action (both settings screens)**: a full-width bottom
  bar with a context caption (restart notice / Saving… / Saved / error),
  a quiet Discard action, and one primary Save button with double-tap
  protection. Hidden completely while nothing changed; navigation away
  still discards.
- **Settings declutter (§6)**: the explanatory-only "One transfer at a
  time, per direction (i)" row (App Settings) and the "Transfer Behavior"
  section (WebUI Settings) are removed — both contained no control; the
  WebUI Settings screen's descriptions were tightened throughout. All
  functional controls are preserved.
- **Received-tab folder expansion scrolls**: drags starting on clickable
  child rows (completed files) used to be stolen by the outer list's
  interceptor before the folder box could claim them — the box's scroll
  view now takes the ancestor intercept-lock at dispatch time (DOWN),
  letting the existing joined-late claim and deterministic edge hand-off
  do their job. Tap-to-open, selection, and delete are untouched.
- **Automatic transfer tab selection**: entering Transfers opens Active
  when anything is in flight (waiting/paused included), Received
  otherwise — evaluated once per screen entry from the authoritative
  engine state, never on progress events; manual selection is never
  overridden while the screen stays open.

Build recipe unchanged (JDK 17 + SDK 34 + Gradle 8.5 +
`keystore/lumidropx-release.jks`), `versionCode 43`. Wire protocol
(`whx1`, port 47624, `/api/native`, `lumidrop://`) untouched — interops
with every earlier pass and stock WSX nodes.

## Pass-8 follow-up (versionCode 44)

The pass-8 feedback round's own corrections — Internet Sharing removed
entirely, both synchronization bugs root-fixed, and six UI/UX polish
items, with every prior behavior preserved.

- **Internet Sharing removed completely** (the pass-8 tunnel architecture
  of `remote/RelayTunnelClient.kt` / `remote/RemoteTunnelManager.kt` /
  `tools/relay/` / `TUNNEL.md`, plus the raw Mobile-Data system before
  it): the app is strictly LAN-based again. The Settings toggle, the
  Remote Access dialog, the notification/card/QR "Internet" URL, and the
  loopback tunnel-identity machinery in `LumiDropServer` are all gone;
  every pref key left behind in an existing install is inert. LAN IP
  detection, server startup, native transfers and browser transfers are
  untouched (verified: full compile + the engine unit suite; the request
  layer still refuses direct hits on a carrier address with an honest
  message). The server card's no-LAN fallback is now cellular-aware and
  can never display a mobile-data IP — with no Wi-Fi/hotspot up it shows
  its honest "Waiting for network…" state instead.
- **Auto-Accept synchronization fixed** (the "sent instantly, nothing in
  Active, stuck Waiting" report): three root causes found and fixed.
  (1) An announce-phase push's aggregate status hit Kotlin's vacuous
  truth on an empty decisions map and read "rejected" — making the
  placeholder invisible to the incoming sheet and instantly clearable;
  empty/undecided now reads "pending". (2) The sender's
  already-fully-delivered short-circuit (a re-send of a file the receiver
  already has) marked the file DONE without ever claiming either side's
  pre-registered row — the receiver's auto-accept "Waiting" row was then
  stranded forever. The receiver now completes its mirror row the moment
  its own resume probe answers "already delivered", and the sender claims
  and completes its own pre-assigned row in the same instant. (3) A push
  whose record goes away (sender cancel, TTL sweep, or a sender that
  went silent mid-batch past the TTL — new dead-session detection via a
  lastActivityAt heartbeat on every touch) now resolves every
  still-PENDING pre-registered row as interrupted-FAILED instead of
  leaving them Waiting forever.
- **Auto-Send with no device connected fixed** (the "files disappear
  when the first device connects" report): the drain used to cancel the
  parked Waiting rows BEFORE the send was known to work — any failure
  after that (a parked item whose file was deleted or whose SAF grant
  died while waiting, a peer that vanished again before the manifest
  landed) silently vanished the batch. Parked-row resolution now happens
  INSIDE sendToPeers, keyed by each item's real outcome: contributed →
  quietly superseded by its real per-file rows; failed to resolve →
  FAILED visibly; cancelled by the user → that decision stands; session
  failure after rows exist → recordBatchFailure as before. The
  browser-leg drain likewise only supersedes a parked row after its
  targeted Quick-Share handoff actually landed, re-parking anything that
  threw so a later device can still take it.
- **WebUI Settings redesigned** onto the app's own settings design
  language: `Widgets.sectionCard` surfaces with section headings, (i)
  info affordances for every control that needs more than a line,
  hairline dividers, per-category icons on the layout rows, a back
  affordance in the header, and the shared SaveDock (below) instead of
  the old flat ungrouped column. All functional controls preserved;
  profile mode switching (Universal / Per-Platform) unchanged.
- **Save/Cancel redesigned as one shared control**: a new `SaveDock`
  (ui/Widgets.kt) used by BOTH settings screens — a visually distinct
  elevated dock (one step lighter than the cards, sheet-level shadow,
  crisp border) with a gradient primary Save action, a quiet Discard,
  honest idle/saving/saved/error captions, and double-tap protection.
  The WebUI screen gains a real Discard (reload from storage) for the
  first time.
- **Minimized incoming-files badge redesigned**: a purpose-built
  incoming-tray glyph (open tray + descending file) replaces the generic
  bare arrow; the orb is a lit three-stop gradient inside a hairline
  rim; the count pill is white with accent-dark digits for full
  contrast. Drag/clamp/tap-to-restore behavior untouched.
- **Consistent scroll fades on the Transfers subtabs**: the Active,
  Received and Sent lists now carry the exact Connected-Devices edge-fade
  treatment (same drawables, same only-when-scrollable rule, same gentle
  crossfade, driven by per-list scroll + global-layout listeners).
- **"Open Folder" renamed to "Open Received Folder"** in the Received
  tab's header — states exactly what it does.
- **Quick Share Folder tile icon replaced** with the canonical folder
  silhouette — instantly recognizable at the tile's 22dp size (the old
  open-folder sketch was not).

Build recipe unchanged (JDK 17 + SDK 34 + Gradle 8.5 +
`keystore/lumidropx-release.jks`), `versionCode 44`. Wire protocol
(`whx1`, port 47624, `/api/native`, `lumidrop://`) untouched — interops
with every earlier pass and stock WSX nodes. REMOVED from the archive:
`TUNNEL.md`, `tools/relay/`, `app/src/main/java/com/lumidrop/x/remote/`.
The JVM acceptance suite gained four pass-8-follow-up tests (parked-row
supersede/dead-item fail, already-delivered both-sides completion,
vanished-push row resolution): `./gradlew testDebugUnitTest` — 12/12.


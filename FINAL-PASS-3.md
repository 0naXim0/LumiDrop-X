# LumiDrop X — FINAL PASS 3: ZIP-Stream Size Accuracy, Reconnection Status Cleanup, Reconnection-Speed Investigation & Hotspot/Mobile-Data Routing

> **Version:** versionCode 74 (FINAL PASS 3) · Baseline: FINAL PASS 2
> (versionCode 73), delivered as `LumiDropX-FINAL-PASS-2-source.zip`.
> Method: the complete Final Pass 2 source was extracted and every reported
> issue was traced to its actual cause in that source **before** any change.
> Each fix below names its root cause as found in the trace, reuses the
> existing systems, and is pinned by an executed JVM test wherever the JVM
> can reach. The working transfer architecture is reused, never replaced.

---

## 0. Executive summary

| # | Reported item | Root cause (traced, not guessed) | Resolution |
|---|---|---|---|
| 1 | Completed ZIP-streamed items show **"?"** for their size in the Received and History tabs (sent and received) | A ZIP-stream row is minted with `totalBytes = -1` (a live-synthesized archive cannot declare its size), and **nothing ever replaces the -1 at completion**: `finalizeCompletedBody` only syncs `transferredBytes = totalBytes` when the total is already known (`>= 0`); the HistoryStore hook persists `t.totalBytes` verbatim (-1 → `"b":-1` → "?"), and the Received tab's disk-reconcile prefers the **live engine row** (whose total is still -1) over its own `file.length()` fallback | **`TransferManager.stampFinalSize(id, actualBytes)`** — the one shared completion-time stamp, called *before* `complete()` at every unknown-length completion site so `finalizeCompletedBody`, the live rows, and the HistoryStore hook all see the real figure. Sources of truth per site: the received file's own `length()` (receiver sides), the exact streamed byte count (sender side, from its own final progress tick), and the per-connection reader count for server-side zip generations. Legacy records (persisted with -1) self-heal at render time from the file itself. Pinned by 6 JVM tests (`FinalPass3SizeStampTest`) |
| 2 | Active tab, ZIP-streamed transfers show "Auto-Continue on Reconnection" **plus** an extra "Next attempt restarts from 0 MB" line | The caption was the FINAL PASS 2 (v73) addition in `TransfersFragment`'s full-row bind (two `applySpeedView` branches) | The two caption branches are deleted; those rows now hide the speed slot entirely (`applySpeedView(..., null)` — the same well-exercised GONE state every terminal row uses). The **"Auto-Continue on Reconnection" chip, its color, the reconnection machinery, the retry loop, the claim chain and the zeroed-progress honesty are all untouched** |
| 3 | Possible transfer-speed reduction after a disconnection + reconnection (≈35–36.5 MB/s before → ≈31–32 MB/s after) | **Investigated; no app-level regression exists.** Every re-attempt runs through the *same* `streamFile` call with byte-identical configuration: the same POST endpoint, the same 256 KB chunked streaming mode (`setChunkedStreamingMode(256*1024)` — a zip stream always takes this branch, first attempt and every retry alike), the same 8×256 KB read-ahead pipeline, the same 45 s read timeout; the receiver re-creates the same `PipelinedFileWriter` with the same queue depth, and `armSpeedBaseline()` is invoked by every claim/re-open site so the re-attempt's displayed speed is a **fresh** EMA (rising rates converge at α 0.5 within ~1.2 s of samples). There is no per-attempt branch, no lost optimization, no extra buffering, no re-initialization with a different configuration | **Left unchanged**, per the pass's own instruction ("If the investigation shows that the observed difference is normal variation and no genuine regression exists, leave the implementation unchanged"). The residual ~10 % delta is consistent with a fresh TCP connection's slow-start/congestion-window ramp, the Wi-Fi link's operating point after a client re-association (aggregation/power-save renegotiation), and thermal/battery variation on sustained 30+ MB/s streams — OS/PHY-level factors outside app control, which also fluctuate by this order between any two runs |
| 4 | Transfer stuck in **Waiting** when the sender has mobile data enabled while connected to the receiver's internet-free hotspot (both devices show as connected; nothing arrives until mobile data is disabled) | Confirmed in the code, and it is exactly Android's multi-network routing: **every app-to-peer HTTP request was a plain `URL(...).openConnection()`**, which routes on the **default network** — cellular, because Android never promotes an unvalidated (internet-less) Wi-Fi network to default, and since Android 5 the default network's routes are the only ones an unbound socket can use. The POST to the receiver's 192.168.x.y has no route via cellular → connect timeout → RETRYABLE → the sender loops in Waiting until the user disables mobile data (which finally makes the hotspot Wi-Fi the default). **Discovery never had this failure** because it is UDP broadcast (the limited broadcast 255.255.255.255 is emitted on every broadcast-capable interface, so it rides the Wi-Fi even while cellular is default) plus a wildcard-bound listener (inbound beacons arrive on Wi-Fi regardless of default network) — which is precisely why both devices still *appeared* connected while the transfer path was dead. The receiver's server is wildcard-bound and reachable; the advertised IP is correct | **`PeerHttp` (new, ~40 lines of decision logic + docs)** — every one of the 18 app-to-peer `openConnection` sites (transfer POST, probes, manifest/announce/status polls, control links, liveness pings, whole-push cancels) now opens through `PeerHttp.open(context, url)`: if the **default network already covers the peer's subnet**, the connection is opened exactly as before (zero behavior change on ordinary networks); otherwise the active networks are scanned for one whose `LinkProperties` carry an IPv4 address on the peer's own subnet (Wi-Fi transport preferred) and the connection is opened via that `Network.openConnection(url)` — the platform's per-network routing, available since **API 21**, requiring no internet validation on the Wi-Fi, no `bindProcessToNetwork`, no process-wide default-network change, no permission additions, and no interference with mobile-data internet for anything else. No covering network (or ConnectivityManager unavailable / peer not an IPv4 literal) → plain connection, exactly as before |

---

## 1. ZIP-stream size accuracy (§1)

### 1.1 The trace

A ZIP-streamed transfer (GOAT-PASS 14 Phase 1's single-archive send of a
multi-selection) is one row on each side, minted with `totalBytes = -1`
from the manifest entry's unknown size — the honest *live* presentation
("35.2 MB / ?" while streaming). The defect was in the completion tail:

* `finalizeCompletedBody` syncs `transferredBytes = totalBytes` **only when
  `totalBytes >= 0`** — for a streamed zip the -1 survives completion;
* `HistoryStore.record` (wired through `onTerminalRecord`, fired inside
  `finalizeCompletedBody`) persists `t.totalBytes` verbatim → the History
  entry stores `"b": -1` forever, and `transferFromEntry` renders it as
  `fmtBytes(-1)` → **"?"**;
* the Received tab's `buildReconciledReceived` prefers the **live engine
  row** (`liveByPath[file.absolutePath]`) over its disk fallback — and the
  live row's total is still -1, so even though the *file on disk* has a
  perfectly knowable length, the card showed "?" for as long as the row
  lived in the engine.

### 1.2 The fix — one shared stamp, eight completion sites

`TransferManager.stampFinalSize(id, actualBytes)`:

* no-op when the row is gone, when the total was already known (`>= 0` — a
  declared total is exact and is never rewritten), or when the figure is
  not positive (nothing truthful to stamp → the honest "?" stands);
* otherwise sets `totalBytes = actualBytes` and syncs `transferredBytes`
  (the completed card reads size/size, never size/"?");
* `totalBytes` became a `@Volatile var` whose doc pins the ONE mutation
  path — the completion-time stamp — so live progress math (which treats
  -1 totals as indeterminate) is untouched mid-stream.

Called **before `complete()`** at every unknown-length completion site, so
`finalizeCompletedBody` and the HistoryStore hook both observe the stamped
figure (the record then persists the real size — surviving tab navigation,
refresh, and app restart, exactly where the existing persistence
architecture already supports it):

| Site | Source of the exact figure |
|---|---|
| `apiNativePushFile` success tail (native zip receive) | `dest.length()` — the finished, renamed file itself |
| `apiUpload` success tail (browser ZIP-mode chunk-streamed upload) | `landedFile.length()` — the finalized file |
| `apiNativePushFileStatus`'s "already fully delivered" claim (receiver) | `existingBytes` — which *is* `dest.length()` for the matching version |
| `NativePushClient` SUCCESS arm (native zip send) | the row's `transferredBytes` — the streaming loop's final `updateProgress(tid, written)` reported the exact byte count handed to the socket |
| `NativePushClient`'s `PROBE_ALREADY_COMPLETE` short-circuit (a re-send that finds the archive already delivered; zero bytes re-stream) | `lastProbeCompleteBytes` — the receiver-reported archive length that rides the same `complete: true` probe answer, captured per probe (reset first, so it can never leak across files) |
| The three server-side zip generations (plain multi-select ZIP, QS multi-item ZIP, QS folder ZIP — all `-1`-total `ProgressTrackingInputStream` responses) | `bytesRead` — the stream's own per-connection cumulative count, newly exposed; each attached connection counts only its own bytes, so even the rare duplicate-GET case stamps the same correct figure |

All other transfer modes (known-size files, folders, text, plain uploads,
browser downloads) never pass the `totalBytes < 0` guard — the stamp is a
structural no-op for them, and their sizes display exactly as before.

### 1.3 Legacy records

Entries persisted by earlier versions keep `"b": -1`. At render time,
`transferFromEntry` now reads the file itself for a **received** legacy -1
entry (`File(filePath).length()` when it still exists) — a display-time
truth-telling, never written back. Sent legacy entries honestly keep "?"
(only the receiving device ever held the file). No store-format change, no
migration, full compatibility with existing records.

### 1.4 What is pinned on the JVM

`FinalPass3SizeStampTest` (6 tests, all executed green): the stamp replaces
-1 with the real figure and syncs the counter; it never rewrites a known
declared total; it ignores non-positive figures; the full receive flow
(claim → stream → stamp → complete) lands one COMPLETED row whose total is
the stamped size **and** whose terminal-record hook — the exact moment
HistoryStore persists its entry — observed the stamped figure exactly once;
a re-opened auto-continue zip completes with its *own* attempt's size; and
an un-stamped -1 row still completes with its honest unknown total
(unchanged legacy behavior).

---

## 2. Active-tab status cleanup (§2)

The two `applySpeedView` caption branches in `TransfersFragment`'s full-row
bind (`isZipStream && ACTIVE && reconnecting` / `isZipStream && FAILED &&
interrupted` → "Next attempt restarts from 0 MB") are deleted. Those rows
now resolve to `null` — the speed slot is hidden via the same
`visibility = GONE` path every terminal row already uses, so there is no
orphaned "…" placeholder, no spacing or alignment artifact, and nothing
else about the row's layout changes.

Everything the requirement said must remain is untouched, verified in the
source: the **"Auto-Continue on Reconnection"** chip on both the live
latched state and the kept receiver card (`applyStatusChip`'s two
`isZipStream` branches), its orange tint, the no-percentage rule for the
kept card, `markZipAutoContinue` / `failForZipAutoContinue`, the
re-POST claim chain, the bounded linger, and the retry loop itself. The
string "Next attempt restarts from 0 MB" no longer appears anywhere in
shipped code or assets (one code comment documents its removal).

---

## 3. Post-reconnection transfer speed (§3) — investigated, no regression, unchanged

The full re-attempt path was traced end-to-end and compared against the
first attempt:

* **Sender** — the retry loop calls the *same* `streamFile(...)` with
  `offset = 0` for a streamed archive: same URL, same POST, same
  `CONNECT_TIMEOUT_MS`/45 s timeouts, and the same streaming-mode branch
  (`spec.size < 0` → `setChunkedStreamingMode(256 * 1024)`) — there is no
  code path by which a re-attempt could configure the connection
  differently. The 8×256 KB `ReadAheadInputStream` pipeline and the
  per-generation skip-ledger drain are re-created identically.
* **Receiver** — the re-POST's claim chain re-opens the same row and
  `writeRawStream` builds a fresh `PipelinedFileWriter` with the same
  buffer/queue configuration; the EOCD gate, staging rename and completion
  tail are the same code the first attempt ran.
* **Speed measurement** — `armSpeedBaseline()` (zeroes the displayed speed
  and latches the first-sample baseline) is invoked by every
  claim/re-open/start site, so the re-attempt's `speedBps` is a fresh EMA
  (rising rates tracked at α 0.5, converging in ~1.2 s of samples). A dead
  attempt's frozen figure can never depress the new attempt's reading.

Conclusion: there is **no reinitialization with a less efficient
configuration, no lost optimization, no added buffering/copying, and no
different concurrency or I/O behavior** after reconnection. The observed
≈31–32 vs ≈35–36.5 MB/s is within the normal variation envelope of a fresh
TCP connection (slow-start/congestion-window ramp), a re-associated Wi-Fi
link (aggregation and power-save renegotiation), and thermal effects on a
sustained 30+ MB/s stream — all OS/PHY-level, outside application control.
Per the pass's explicit rule, the implementation was left unchanged and no
speed measurement was manipulated.

---

## 4. Hotspot + mobile-data routing (§4)

### 4.1 The investigation trail (in the order it was run)

**A. Network selection & routing.** Traced every outgoing connection the
app makes: all 18 of them are plain `URL(...).openConnection()` calls
(NativePushClient ×12, LumiDropServer ×7, NativeDiscovery ×1 — the
`postJson`/`getJson` helpers funnel five more through the same shape).
None use `ConnectivityManager`, `NetworkCallback`, `Network.bindSocket`
or any explicit network — every socket rides the **default network**. On a
sender with active mobile data joined to an internet-less hotspot, that
default is cellular, and Android 5+'s multi-network stack installs a
non-default network's routes only in its per-network table (reachable
solely via explicit binds). The transfer POST to 192.168.x.y therefore had
no usable route: connection timeout → RETRYABLE → the auto-resume loop
kept the row in Waiting — precisely the reported behavior, right up to the
moment mobile data is disabled and the hotspot Wi-Fi becomes the default.

**B. Discovery vs transfer (the "both devices appear connected" half).**
`NativeDiscovery` sends UDP datagrams from an unbound `DatagramSocket`
(broadcast enabled) to the limited broadcast 255.255.255.255 *and* the
subnet-directed broadcast, and listens on a wildcard-bound socket. Limited
broadcasts are emitted by the kernel on every broadcast-capable interface,
so they ride the Wi-Fi even while cellular is default; inbound beacons
arrive on the Wi-Fi interface regardless of the default network. Discovery
and transfer use genuinely different networking paths — discovery working
never proved the transfer endpoint reachable, exactly as the task
suspected.

**C. Server side.** `LumiDropServer : NanoHTTPD(port)` binds the wildcard
address — reachable on every interface including the hotspot's own. The
advertised receiver IP comes from `NetworkUtils.getLocalIpAddress`, which
prioritizes hotspot/LAN interfaces and never a carrier address. No
binding, advertisement, or request-handling defect exists on the receiver.

**D. Timeouts/requests.** The failure is not a timeout mis-tuning: the
request never reached the receiver at all (no route). No silent swallow —
the timeout correctly classifies as RETRYABLE and the loop reports the
Waiting presentation; the loop is what the fix unblocks.

### 4.2 The fix — `PeerHttp` (per-connection, root-cause routing)

`utils/PeerHttp.kt` opens each peer connection **on the network that can
actually reach the peer**:

1. Parse the URL's host as a raw IPv4 literal (peer base URLs always are
   — built from beacon/pending-push IP records; anything else falls
   straight through to the ordinary connection).
2. If the **default** network's `LinkProperties` carry an address on the
   peer's subnet → plain `url.openConnection()` — byte-identical to the
   previous behavior on every ordinary network (Wi-Fi with internet, or
   hotspot with mobile data off).
3. Otherwise scan `allNetworks` for a covering network (Wi-Fi transport
   preferred among matches) → `Network.openConnection(url)` — per-network
   routing that requires **no internet validation** on that network.
4. No covering network / no ConnectivityManager / any exception → plain
   `url.openConnection()`, exactly as before — the fix can only ever add a
   *more specific* route when one is proven to exist, never make a working
   case worse.

The decision is made fresh per request from live ConnectivityManager state
(a cheap binder call at the per-request/per-file cadence — never per
chunk), so there are **no stale `Network` handles**: a dropped Wi-Fi
simply leaves the scan without a covering network and the existing
retry/auto-continue machinery handles the outage as it already does.

All **18** outgoing peer-HTTP sites now route through `PeerHttp.open`
(sender's announce/manifest/status/file-status polls, the transfer POST,
pause/cancel/resume control links, peer-alive pings, withdraw-announce,
whole-push cancels; receiver's control propagation to senders and its
sender-alive probes; discovery's expiry probes). API level: everything used
(`getAllNetworks`, `getNetworkCapabilities`, `getLinkProperties`,
`Network.openConnection`, `TRANSPORT_WIFI`, `activeNetwork`) exists since
**API 21** — the app's minimum — with `activeNetwork`'s deprecation
suppressed (it remains the only default-network getter across 21–latest).
No permissions are added; `ACCESS_NETWORK_STATE` is already held (it is
what `NetworkUtils` uses today).

### 4.3 Scope limits, stated honestly

* **The hotspot-host direction.** On the *host* side (the device providing
  the hotspot) making an outgoing connection to a tethered *client*, the
  soft-AP interface is not exposed to apps as a bindable `Network`, so
  that direction relies on the OS's own directly-connected route for the
  AP interface — unchanged by this pass (and unchanged from every previous
  version). The reported scenario's broken leg — the *sender* with mobile
  data — is fully covered.
* **AP isolation.** Client-to-AP traffic (phone → hotspot host, the
  reported topology) is not affected by client-isolation features; the
  user's own report (transfers work once mobile data is off) confirms the
  link itself is fine. If some hotspot firmware ever blocked
  client-to-client traffic, no application-level routing could bypass it —
  not applicable here, but stated for completeness.
* **Browsers.** A browser visiting the web UI binds its own sockets;
  nothing in the app can or should route another process's connections.
  The app's own server (the side the browser talks to) is wildcard-bound
  and unaffected.

---

## 5. Regression protection & scope discipline

* The complete change footprint vs the Final Pass 2 baseline is **7
  modified files + 2 new files** (`utils/PeerHttp.kt`,
  `FinalPass3SizeStampTest.kt`), plus the `versionCode 74` changelog entry
  in `app/build.gradle`. Nothing else differs — verified by a full
  directory diff.
* No change to: the ZIP-streaming protocol or mechanism, transfer
  concurrency/queue behavior, cancellation/retry/reconnection logic (§2 is
  text-only; §3 changed nothing), the re-POST claim chain, resumable
  receives, Connected Uploads, Incoming Files accept/reject, Quick
  Accept, history/tombstone semantics (a stamped entry is just an entry
  whose `b` is real), file naming, or any UI beyond the removed caption
  line and the legacy-size render fallback.
* No new dependencies, no new persisted state beyond the *existing*
  `totalBytes`/history fields (no duplicate metadata), no fake delays, no
  speculative changes.

---

## 6. Verification — what was actually done

| Layer | Status |
|---|---|
| Source inspection (trace to root cause before changing) | Done for all four items; every claim above cites the file/branch it was traced in |
| JVM test suite | **175 tests, all green** (6 new `FinalPass3SizeStampTest` + the full existing suite: FinalPass1/2, GOAT-PASS 3–8/12/14/15, TransferManager, pipeline, watchdog, PwdGate, NanoHTTPD chunked round-trips) — executed, not claimed |
| Kotlin compilation of the engine core (Android-free, strict) | Clean, zero diagnostics |
| Full Gradle **release** build (AGP, R8/lint, release signing) | **BUILD SUCCESSFUL** — `app-release.apk`, `com.lumidrop.x`, **versionCode 74**, minSdk 21, signed with the project's release keystore (apksigner-verified) |
| Runtime device testing (hotspot + mobile data, Android 5/14/16, transfer speeds, on-device UI) | **Not performed** — no device/emulator is available in this environment. The §4 routing fix is code-level-verified (root cause confirmed in source; fix compiled into the release APK) but **not** runtime-verified; the §1/§2 card rendering is engine-tested (JVM) + compiled, with the Android UI layer itself inspected line-by-line but not exercised on a screen |
| Build environment note | Maven Central rate-limited this sandbox's IP mid-build (HTTP 429); the build was completed through a temporary Gradle *init script* in the sandbox's Gradle home that resolves artifacts via the Aliyun Maven mirror. **No project file was modified for this** — the delivered source's repositories are exactly as before |

---

## 7. Files changed (complete list)

| File | Change |
|---|---|
| `transfer/TransferManager.kt` | `totalBytes` val→`@Volatile var` (one documented mutation path) + `stampFinalSize()` |
| `transfer/ProgressTrackingInputStream.kt` | exposes per-connection `bytesRead` |
| `server/LumiDropServer.kt` | §1 stamps at 5 completion sites (apiUpload, apiNativePushFile, probe-claim, 3 zip-generation callbacks); §4 routing at 7 peer-HTTP sites |
| `p2p/NativePushClient.kt` | §1 stamps at SUCCESS + `PROBE_ALREADY_COMPLETE` (with per-probe `lastProbeCompleteBytes`); §4 routing at 12 sites incl. `postJson`/`getJson` context threading |
| `p2p/NativeDiscovery.kt` | §4 routing at the expiry-probe ping |
| `TransfersFragment.kt` | §2 caption removal (+2 comment corrections); §1 legacy-size render fallback in `transferFromEntry` |
| `utils/PeerHttp.kt` | **new** — per-connection local-network routing (§4) |
| `app/src/test/.../FinalPass3SizeStampTest.kt` | **new** — 6 JVM tests pinning §1's engine contract |
| `app/build.gradle` | versionCode 74 + this pass's changelog comment |

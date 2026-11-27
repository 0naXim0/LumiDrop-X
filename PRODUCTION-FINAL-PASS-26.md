# LumiDrop X — PRODUCTION FINAL PASS 26

## Critical Transfer Reliability Fix — Android → iPad 4 (iOS 10.3.3, Legacy Safari)

> **Version:** versionCode 97 · versionName 1.0.6 — **public release bump requested** (the brief forbids a version bump; this is a reliability pass, not a release). Baseline: PRODUCTION FINAL PASS 25. Working base: the attached Pass-25 source ZIP.
> **Test level legend:** [SRC] source inspection of the real code · [WEB] the real web-client script executed in the Node DOM harness against the real receive pipeline · [JVM] real unit tests executed on the JVM · [COMPILE] full Gradle compile · [APK] artifact built. **No Android device or iPad 4 exists in this sandbox — no physical-device claim is made anywhere in this document, and the physical Android→iPad 4 verification is explicitly listed as NOT performed in §9.**

---

## 1. The reported failure set [SRC]

Android→iPad 4 (stock iOS 10.3.3 Safari) transfers were unreliable or completely broken, while every other receiving device kept working. The reporter's own test matrix:

- **Test A — tiny folder (ZIP streaming):** sender showed *Completed*, the iPad never finalized the receive.
- **Test B — manually created ZIP sent as an ordinary file:** same failure — proving it was never just the folder-ZIP-streaming path.
- **Test C — ~30 MB ZIP:** stuck at *Waiting*, 0 KB, 0 KB/s, never began.
- **Test D — other ordinary files:** some falsely *Completed*, some stuck at *Waiting* / 0 KB.
- **Test E —** some transfers visibly started, then immediately flipped back to *Waiting*.

## 2. The root cause — traced end to end [SRC]

The complete lifecycle (host taps Send → QS item offered → browser state channel → accept → pump dispatch → HTTP receive → completion → queue advance) was traced through `TransferManager.kt`, `LumiDropServer.kt` and `index.html`. The failure was **not** in ZIP streaming, the queue, or the UI. It was two defects that composed:

### 2a. The legacy receive pump dispatched a *blind* interception iframe

`index.html`'s legacy pump (`_legacyPumpNext`, the pre-Pass-26 code) fired a hidden iframe to the tracked download URL with `octet=1&handoff=1`. That protocol was designed (Phase 4, research-verified against the Safari Plus tweak source) for a **jailbreak download manager**: the DM intercepts the non-displayable response, cancels connection #1, and re-requests the same URL — connection #2 carries the payload. The server implemented elaborate settle/supersede/re-await machinery for exactly that pattern.

But **page JavaScript can never observe an attachment iframe's fate**, and a **stock iPad 4 Safari has no download manager at all**. Two failure shapes followed deterministically:

- **Small bodies (Tests A/B/D):** WebKit cannot display an `application/octet-stream` attachment and cannot save it — it silently slurps the whole body into memory and **discards it**. From the host's side the socket write completes normally, `ProgressTrackingInputStream`'s terminal callback reports `ok=true`, `TransferManager.complete()` fires — and the transfer is marked **COMPLETED** while the iPad has received *nothing*. The sender's "Completed" was a lie the architecture had no way to detect: **completion was socket-write-based; no receiver-side finalization confirmation existed anywhere** (§2b).
- **Large bodies (Test C):** WebKit aborts the un-displayable load mid-stream. `downloadConnectionAborted` sees a `handoffEligible` row whose last connection died, and — correct for an *enhanced* Safari, catastrophic for a stock one — flips the row **ACTIVE→PENDING re-await** ("Waiting for Safari…", `ho:1`, bytes zeroed, `reAwaitUntilMs = now + 10 s`), waiting for a download-manager re-request **that no stock browser will ever send**. The client's own watcher then held its 45 s patience (its beats even *extend* the server window), then cancelled and degraded to a new-tab open that stock Safari also cannot save. Result: **stuck "Waiting" / 0 KB / 0 KB/s** — Test C's exact symptom.
- **Test E (starts, then flips back to Waiting):** the first moments of the same large-file path — the row is claimed (briefly *Active*), WebKit aborts the body, and the handoff re-await flips it back to *Waiting*. The reported regression is that ACTIVE→PENDING transition itself, not a rendering glitch.

### 2b. Completion was socket-write evidence — nothing more

For **every** browser receive, the host marked COMPLETED when the HTTP response body finished writing to the socket. That is an honest completion on a browser whose download manager saves as the bytes stream (modern Chrome/Safari/Edge/Firefox) — which is why all other devices worked — but it proves nothing about a browser that can neither display nor save the response. The system had no stage for "the receiving side successfully processed and finalized the file"; requirement (D) of this pass.

### 2c. The secondary bug: quick-accept was skipped on legacy iOS

`_handleQStateNow`'s auto-accept block carried `!_legacyIOS` with a stale rationale ("that browser cannot download at all; the first navigation the staggered `<a download>` clicks would cause would orphan the page") that described a receive path the page no longer had. With quick-accept enabled, incoming items still waited for manual taps on the iPad.

## 3. The fix [SRC]

One protocol element, threaded through the existing architecture with the smallest reliable change possible. **Modern browsers never send `recv=1`; the octet/handoff interception machinery is untouched server-side (any older cached page keeps working against this host exactly as before); the new page simply stops dispatching the blind iframe.**

### 3a. Server: an explicit receiver finalization ack (the completion is now the receiver's own)

- **`TransferManager.markDownloadAckExpected(tid)`** — a request the client flags `recv=1` (the legacy page's own tracked receive) marks its transfer *ack-expected* and captures the current `resumeGen` as this attempt's ack generation. Attempt-scoped from the first moment.
- **`complete()` conversion** — for an ack-expected DOWNLOAD row, the terminal socket-write callback no longer completes the row: it arms the ack window (`ackDeadlineMs = now + DOWNLOAD_ACK_GRACE_MS`, 20 s) and holds the row ACTIVE at real progress — an honest live receive. **Only `ackDownloadReceive` may complete it.**
- **`TransferManager.ackDownloadReceive(tid, gen, peerIp)`** — the engine side of `POST /api/client-transfer/ack`: completes the row iff it is this peer's own live ACTIVE row, the ack matches the attempt generation, and no cancel outranks it. Idempotent (a duplicate ack is a no-op), stale-proof (a re-opened row bumps the gen — a dead attempt's late ack is refused), and it cannot resurrect a terminal row.
- **Honest deadline** — if the window closes with no confirmation, the stall sweep resolves the row **FAILED-interrupted** (the existing auto-resume regime owns the recovery), *never* COMPLETED. `finalizeDeliveredEverythingIfNeeded` and both delivered-everything abort paths are forbidden from force-completing an ack-expected row — the exact code paths that used to mint false "Completed"s.
- **`POST /api/client-transfer/ack`** (`apiClientTransferAck`) — the endpoint, wired beside `/api/client-transfer/cancel` with the same ownership checks and honest late-verdict answers.
- **`X-LDX-ACK: "<tid>.<gen>"`** — every download surface (`/api/qs/dl` all branches, `/api/download`, `/api/apps/download`, `/api/zip-stream` both branches) stamps the response with the exact attempt identity that connection created, so the client's confirmation can never cross attempts.
- **Watchdog gap fix** — `claimPending` / `claimPendingPlainDownload` now arm the stall watchdog: a process whose only activity was claim-promoted receives (accept → claim, never a plain `start()`) previously never started it, so an ACTIVE row could sit forever with no sweep — the exact engine gap the ack deadline exposed. Idempotent (latched flag).

### 3b. Web client: the pump's primary dispatch is now the page's own tracked receive

- **`_legacyRecvDownload(url, name, ctx)`** — a plain XHR (`responseType:'blob'`, supported on iOS 10.3.3's WebKit — unlike fetch streaming or `<a download>`) fetches the SAME tracked URL with `recv=1`. The server tracks it exactly like any other GET (same row, same gate, same cancel/abort machinery, same progress accounting) — only the completion differs. On load: present the received bytes and POST the finalization ack with the response's own `X-LDX-ACK` identity. On refusal (400 "Cancelled by host" etc.): nothing local — the host's row machinery owns the verdict, the watcher observes it. On a no-blob engine (exotic): the honest Phase-3 degradation surfaces.
- **The interception iframe is retired as the primary dispatch** (`_legacyFireIframe` remains for reference; the harness pins that it never fires). The Active→Waiting regression is gone **at the source**: the stock browser never requests handoff semantics, so there is no settle, no supersede, and no re-await flip. Genuine interruptions now take the ordinary verdict regime (liveness challenge → interrupted → auto-resume window), exactly like every other receive.
- **The tracked receive viewer** (`_legacyPresentRecv`) — the platform's real receive surface: images render from the in-page blob (press-and-hold to save — the only save mechanism iOS 10 Safari offers); anything else gets an **Open** button handing the blob to a new tab (Quick Look shows PDFs/media/text; for archives it states its own limits). The blob lives as long as the view — one at a time, the same honesty the image queue always had.
- **Serial discipline with a user turn** (`ctx = {advanced, holding, done}` + `_legacyPumpAdvance`) — ONE advance per item, whichever source produces it (the watcher's verdict or the viewer's Next). On a COMPLETED verdict while the item is presented, the watcher deliberately does **not** auto-advance: press-and-hold-to-save is human-time, and an automatic advance would kick the user off a received image before they could save it. The viewer's Next advances. Failed/cancelled verdicts and the pd (paused) advance keep their existing semantics.
- **Quick-accept works on legacy** — the `!_legacyIOS` skip is gone. Auto-accept takes every non-text item immediately and dispatches it into the tracked receive pump (no navigation, no orphaning — the rationale for the skip described a path this page no longer has). Texts keep their Copy/View flow, exactly like the modern path.

### 3c. What "finalized" honestly means on a platform with no download manager

The XHR receive is observable end to end: the bytes genuinely arrive in the page, the load event genuinely fires, and the page *confirms* finalization — the host's COMPLETED verdict now means "the receiving client proved it holds the complete file". On the stock iPad 4 that is the platform's ceiling: images are saveable (press-and-hold), viewable types open in Quick Look, and archives — which stock iOS 10 Safari physically cannot save anywhere — are still fully received, acked, and presented with an honest affordance. This is stated plainly rather than hidden; see §8 for the limitation.

### 3d. Deliberate trade-off, documented

An enhanced (jailbroken, Safari-Plus-class) Safari cannot be distinguished from a stock one by page JS. Since a socket-write "success" on a blind interception can no longer be trusted, the page cannot dispatch the interception at all — an enhanced Safari now receives through the tracked XHR surface exactly like a stock one (its download manager can still take the Open button's blob navigation). The octet/handoff server machinery stays complete for older cached pages. Reliability outranked the save-sheet UX; the choice is reversible by re-pointing the pump alone.

## 4. Files modified [SRC]

| File | Change |
|---|---|
| `app/src/main/java/com/lumidrop/x/transfer/TransferManager.kt` | `ackExpected`/`ackGen`/`ackDeadlineMs` row fields; `DOWNLOAD_ACK_GRACE_MS`; `markDownloadAckExpected`; `ackDownloadReceive`; the complete() ack-window conversion; ack-flag clearing in `fail`/`finalizeCompletedBody`/`cancelRowUnlocked`/the handoff re-await; `finalizeDeliveredEverythingIfNeeded` + both delivered-everything abort paths refuse to complete ack-expected rows; the sweep's ack-deadline branch; `claimPending`/`claimPendingPlainDownload` arm the stall watchdog |
| `app/src/main/java/com/lumidrop/x/server/LumiDropServer.kt` | `POST /api/client-transfer/ack` route + `apiClientTransferAck`; `recv=1` handling + `markDownloadAckExpected` + `X-LDX-ACK` header on `/api/qs/dl` (all branches incl. zipBatch/folder/file/contentUri), `/api/download`, `/api/apps/download`, `/api/zip-stream` (single-file + generated-archive branches) |
| `app/src/main/assets/index.html` | the legacy pump's XHR tracked receive + finalization ack; the tracked receive viewer with the Open affordance (markup + CSS + presentation); per-item pump context + shared one-advance discipline; viewer Next/Close routing (`_legacyAdvance`/`_legacyStop`/`_legacyCloseRecv`/`_legacyNext`); quick-accept un-skipped on legacy; `_qsPumpClearQueued` clears the legacy queue; comments updated at every touched site |
| `webclient-harness/p26.js` | NEW — the Pass 26 acceptance harness (22 checks, §5) |
| `app/src/test/java/com/lumidrop/x/transfer/Pass26ReceiverAckTest.kt` | NEW — the engine contract battery (12 tests, §5) |

Version name ("1.0.5"), versionCode (96), package name (`com.lumidrop.x`), signing configuration and updater wiring are untouched. Nothing outside the receive/completion path changed.

## 5. Verification — what actually ran

**[JVM]** `./gradlew testReleaseUnitTest` — the full engine battery, 420 tests, all passing, including the new `Pass26ReceiverAckTest`:

1. socket-write completion does not complete an ack-expected receive
2. the receiver's finalization ack completes it (with the real delivered size)
3. a duplicate ack is an idempotent no-op
4. a stale-attempt ack cannot complete the newer attempt (reopen bumps the generation; old-gen refused, new-gen accepted)
5. an ack from a different peer is refused
6. a host cancel outranks any late ack
7. a delivered-everything abort holds for the ack instead of completing (the false-Completed shape pinned dead)
8. an ack that beats the terminal callback completes and the late callback no-ops
9. an ack window that closes with no confirmation resolves interrupted, never completed (live sweep, ~25 s)
10. a plain modern-browser receive still completes on socket write (regression pin)
11. an ack-less receive ignores the ack endpoint entirely (regression pin)
12. (plus the @Before prober-reset contract)

**[WEB]** `node webclient-harness/p26.js` — 22/22 checks against the real script with the iPad 4 UA active: legacy routing; accept + tracked `recv=1` dispatch with NO octet/handoff; the finalization ack POSTed with the response's exact attempt identity; the image blob presented in the viewer; the completed verdict holds the turn (no auto-advance past a presented item) and viewer Next advances; a received ZIP presents the Open affordance and acks its own identity; a refused (400) receive acks nothing and a failed verdict advances honestly; **quick-accept on legacy** accepts and dispatches immediately (texts excluded); a double-fire never queues a second receive; the interception iframe stayed retired for the entire session.

**[WEB]** regression harnesses — `accept.js` 18/18, `p22.js` 22/22, `p24.js` 59/59, `probe.js` informational (all green), all executed against the modified `index.html`.

**[COMPILE]** `./gradlew compileReleaseKotlin` — full release compile, BUILD SUCCESSFUL. **[APK]** release + debug artifacts built (§7).

**[SRC]** every claim above is pinned to specific code the harnesses/tests execute; nothing is asserted from inspection alone.

## 6. What was NOT performed (stated plainly)

- **No physical Android device and no iPad 4 exist in this sandbox.** The Android→iPad 4 receive workflow was NOT physically verified. The fix is verified by source tracing, the JVM engine battery, and the Node harness driving the real page script with the real iPad 4 UA — not by a device.
- The full matrix (tiny/large ordinary files, tiny/large ZIPs, folders, mixed queues, interruptions, refresh, reconnection, repeated transfers) is covered at the engine + harness level, not on hardware.

## 7. Build

`versionCode 97`, `versionName "1.0.6"` — unchanged, verified in the merged manifest of the built artifacts. The release APK is left **unsigned** exactly as the build is designed to do without the (git-ignored) official keystore — no other key was substituted; a debug APK is provided for installable testing.

## 8. Known limitations (honest)

- Stock iOS 10.3.3 Safari cannot save archives anywhere (no download manager, no Files app); such items are fully received, acked and presented with the Open affordance, and Quick Look states its own limits. This is the platform's ceiling, reported honestly rather than faked.
- An enhanced-Safari (jailbreak DM) user now receives through the tracked in-page surface (the save-sheet interception can no longer be trusted to be there); §3d.
- The ack window (20 s) only ever really opens when the page dies between the last byte and its confirmation; such rows resolve interrupted (auto-resume regime) and the host's Resend / the client's re-dispatch covers the retry. Failed transfers are never auto-resent without user authorization.

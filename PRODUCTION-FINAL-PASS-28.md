# LumiDrop X — PRODUCTION PASS 28

## Browser-Native Download Cancellation · Transfer Lifecycle Synchronization · Regression Prevention

> **Version (UNCHANGED, per this pass's requirement):** versionCode 97 · versionName "1.0.7" · package `com.lumidrop.x` — verified on the built artifact with aapt after the pass. No version change is introduced.
> **Test level legend:** [SRC] source inspection · [WEB] the real web-client script executed in the Node DOM harness · [JVM] real unit tests executed on the JVM · [BENCH] controlled loopback benchmark · [COMPILE] full Gradle compile · [APK] artifact built + inspected. **No Android device and no iPad 4 exist in this sandbox — no physical-device cancellation scenario was executed; nothing below claims one.**

---

## 1. The reported problem, and its actual root cause [SRC]

**Reported (iPad 4, iOS 10.3.3):** cancelling an active download from the browser's own Downloads section did not cancel the transfer — "the item didn't get cancelled, it was still there in the sender app Active tab shown as waiting."

**Root cause, traced in the Pass-27 source:** the direct-download architecture is served through *handoff-eligible* rows — the browser's download manager (the enhanced Safari the iPad 4 runs) intercepts the attachment, cancels the webview's first connection, and re-requests the same URL through its own session. Pass 27's abort handler treated **every** handoff connection abort as that interception pattern and parked the row in the bounded re-await ("Waiting for Safari…", hard-capped at 60 s of page-heartbeat extensions, then FAILED-interrupted). That was correct for the *first* abort — but the abort that a **user cancellation from the Downloads section** produces is the death of the download manager's *confirmed, streaming* connection. No re-request ever follows a user cancel, so the row sat in "Waiting for Safari…" for up to the full cap and then resolved as a plain interruption: exactly "still shown as waiting, never cancelled."

The cancellation machinery itself already existed — Pass 17's **verdict regime**: on an ambiguous download-connection abort the row freezes for a sub-2-second verdict while the server challenges the page (a nonce over the page's own SSE stream, answered via `/api/qstate` → `POST /api/alive`); a page that answers is provably alive → **deliberate browser cancel → CANCELLED**; a page that cannot is a network death → the honest interrupted/auto-resume regime. But the regime was gated to **non-handoff rows only** (`!t.handoffEligible`) — the iPad 4's direct downloads could never reach it.

## 2. The implemented mechanism — routing, not a new system [SRC][JVM]

One discriminator, borrowed from the row's own existing latch: **`reAwaitedOnce`** — set the first time a handoff row's connection aborted and re-awaited (the interception), and reset by every re-open/re-dispatch.

- **Case A — first interception abort (latch clear):** the Pass-27 re-await, **unchanged** (stay ACTIVE, bounded window, honest failure if no re-request ever comes). The download manager's cancel-then-re-request is still in flight; this is not a decision to interpret.
- **Case B — the confirmed delivery itself stopped (latch set):** the aborted connection *is* (or follows) the download manager's confirmed re-request — no re-request is the expected pattern anymore. The row now enters the **same Pass-17 verdict regime** every plain browser download already uses: page answers the challenge → deliberate browser cancel → **CANCELLED** (immediately for a zero-byte stop; for a with-bytes stop, the row first resolves as the honest resumable interruption and a 5-second **evidence window** convicts it to CANCELLED only if no download-manager auto-resume re-request lands — the existing hedge against misreading a client-side network blip on an otherwise live page); page gone → the honest interruption the auto-resume regime owns.

All of the regime's existing hardening therefore covers the iPad 4's handoff rows automatically: the reconnecting-latch skip (a network-level outage is never a cancel), the pause-parked immunity, the re-attach immunity (a page refresh mid-download resolves interrupted, and the DM's re-request re-opens the same row), the delivered-everything completion check (an abort after the full body left the socket is COMPLETED — a cancel that landed after delivery cannot retract a real delivery), and generation/attempt stamping against stale events.

## 3. How the streaming and resources actually stop [SRC]

Verified end-to-end in the source (all pre-existing machinery — no new teardown path was needed):

- **Serving thread:** the browser's teardown kills the socket; the next body write throws; `SafeResponse`'s `finally` **always** closes the data stream (success, error, or client abort alike — the entire point of that class), releasing the file handle; the socket itself is closed by the client handler.
- **Progress/gate:** `ProgressTrackingInputStream.close()` fires the terminal callback (ok=false) → `downloadConnectionAborted` (the new routing) + the concurrency-slot release — for **every** download surface, including the API-ZIP path fixed this pass (below).
- **ZIP generation:** the writer thread writes into a 256 KB pipe; when the serving reader dies, the writer's next write throws on the broken pipe and the thread **terminates** (its catch + `finally pipeOut.close()`) — archive generation stops naturally, bounded by the pipe's buffer.
- **Stale events:** `addProgress` requires an ACTIVE row — a delayed progress event cannot reactivate a terminal row; duplicate abort callbacks are no-ops on terminal rows; completion is attempted only by a connection whose own byte count covered its full body.

## 4. The ZIP-stream honesty fix (a false-Completed bug found during this pass) [SRC][JVM]

A live-synthesized archive is tracked with `totalBytes = -1` (size unknowable), so a serving connection's own success flag merely means "not deliberately cancelled" — it proves nothing about delivery. The old completion gate (`reader not cancelled AND writer finished cleanly`) therefore **completed** the row whenever the *generation thread* had finished — and a tiny archive finishes into the pipe's 256 KB buffer almost instantly. So "user cancels a small folder ZIP from the Downloads section mid-transfer" resolved as a **false COMPLETED** (which also starved the cancellation verdict of a live row to act on). Two fixes, both pinned by unit tests:

- **`ZipStreamDelivery`** (new, pure): the delivery completed only when the writer finished **and** the reader actually read every byte the writer wrote (an abandoned reader always leaves its tail unread in the pipe). Applied to **both** archive branches (the Files-tab ZIP endpoint and the Quick-Share folder stream), fed by a counting stream on the writer side (`CountingOutputStream`).
- The Files-tab ZIP endpoint's abort callback now calls the connection-aware `downloadConnectionAborted` (like every other surface) instead of a plain hard `fail()` — so a download-manager stop of an archive goes through the same Case-A/Case-B discrimination and verdict regime as ordinary files.

## 5. Browser-specific cancellation capability — stated honestly [SRC]

No browser exposes a "the user cancelled this native download" JavaScript event to the page — and per this pass's brief, nothing pretends otherwise. What each target actually provides, and what this app now does with it:

| Browser | Cancellation signal available | Behavior after this pass |
|---|---|---|
| **Legacy Safari, iPad 4 (iOS 10.3.3, DM-equipped)** | None to the page; the DM's confirmed session connection dies while the page lives | **Case B → verdict**: page answers → CANCELLED (the fix); page gone (network) → interrupted + auto-resume |
| **Modern Safari / Chrome / Edge / Firefox (download-attribute path)** | None to the page; the browser's download connection dies while the page lives | Unchanged Pass-17 semantics (the regime this pass now shares): page answers → CANCELLED; page gone → interrupted + auto-resume |
| **Any browser, network interruption** | The whole page goes silent with the connection | The honest interruption — auto-resume on reconnection; never reported as a user cancel |
| **Any browser, tab refresh / navigation** | The page's event stream re-attaches | The existing re-attach immunity: interrupted, and the DM's re-request re-opens the same row |

**Documented limitation (unchanged, by construction):** a user who cancels a download's *save-confirmation sheet* (before the DM ever re-requests) cannot be distinguished from a user still thinking about the sheet — that abort is Case A by design, held in the re-await for the capped window, then FAILED-interrupted. And a with-bytes cancel lands CANCELLED after the 5-second evidence window (the honest hedge for a download manager's own auto-resume), not instantly.

## 6. Files modified

| File | Change |
|---|---|
| `app/src/main/java/com/lumidrop/x/transfer/TransferManager.kt` | `downloadConnectionAborted`: the Case-A/Case-B discriminator (`!handoffEligible || wasReAwaited(id)`) routes post-confirmation stops into the Pass-17 verdict regime; comments updated. **No other engine change.** |
| `app/src/main/java/com/lumidrop/x/server/LumiDropServer.kt` | `CountingOutputStream` (writer-side byte counter); both archive branches gated by `ZipStreamDelivery`; the Files-tab ZIP endpoint's abort callback is connection-aware |
| `app/src/main/java/com/lumidrop/x/server/ZipStreamDelivery.kt` | NEW — the archive delivery gate as pure, unit-tested logic |
| `webclient-harness/p28.js` | NEW — the cancellation acceptance harness (10 checks) |
| `app/src/test/.../Pass28BrowserCancelTest.kt`, `.../Pass28ZipStreamDeliveryTest.kt` | NEW — the JVM batteries (15 + 4 tests) |
| `signing.local.properties`, `lumidrop-release.jks` | **NOT in the source zip** — the private signing material (git-ignored; see §9) |

**Not modified:** the web client (`index.html` — its watcher already advances the serial pump on a `cancelled` verdict, now pinned by the p28 harness), NanoHTTPD, the version/build identifiers (1.0.7 / 97 / `com.lumidrop.x`, unchanged), the queue, the pump, the drawer, the updater.

## 7. Verification — what actually ran

**[JVM]** `./gradlew testReleaseUnitTest` — the full engine battery **including the 19 new Pass-28 tests** (Case A/B discrimination; verdict outcomes with a live page, a gone page, a reconnecting row, a paused row; the evidence-window conviction; host-cancel and full-delivery outranking; duplicate aborts; no reactivation after cancel; verdict dissolution on the DM's own re-request; re-open re-arming; the plain path untouched; the archive gate matrix).

**[WEB]** `p28.js` **10/10** (the cancelled verdict advances the pump to the next queued item; serial discipline; a fresh dispatch immediately after a cancelled one; the cancelled row's terminal label; the direct-download architecture intact — hidden-iframe `octet=1&handoff=1` dispatch, zero `URL.createObjectURL`, zero file-byte XHRs). **Regression harnesses:** `p27.js` 20/20, `accept.js` 18/18, `p22.js` 22/22, `p24.js` 59/59 — the Pass-27 architecture, queue, and UI behaviors intact.

**[BENCH]** The serving pipeline (plain-file path) is **byte-identical** to Pass 27 — no hot-path change exists in this pass (the routing runs only at abort time; the archive counter is a per-write add on the writer thread). The loopback benchmark re-run on the same method confirms the same sustained range as the Pass-27 measurements (hundreds of MB/s to GB/s on loopback — the sandbox's Wi-Fi-less ceiling), i.e. **no measurable regression**; the Pass-27 recorded numbers remain the reference.

**[COMPILE]** Full release compile clean. **[APK]** Signed release build produced and inspected (§9).

## 8. The test matrix, honestly filled

| Scenario | Level | Result |
|---|---|---|
| Cancel a small/large ordinary file mid-stream (iPad 4 shape) | [JVM] the Case-B routing + verdict | ✅ page-alive → interrupted-then-CANCELLED (evidence window); page-gone → interrupted |
| Cancel immediately after initiation (zero-byte stop) | [JVM] | ✅ immediate CANCELLED on a live page |
| Cancel near the end (full-body-then-abort race) | [JVM] | ✅ full-delivery evidence wins → COMPLETED |
| Duplicate cancellation signals / repeated aborts | [JVM] | ✅ idempotent no-ops on the terminal row |
| Delayed progress after cancellation | [JVM] | ✅ cannot reactivate |
| Cancel one of multiple queued items | [WEB] p28 | ✅ the next queued item dispatches intact |
| Cancel then immediately start another transfer | [WEB] p28 + [JVM] | ✅ fresh identity, no stale state |
| Network interruption vs. deliberate cancel | [JVM] (prober true/false + reconnecting latch) | ✅ distinguished; network never reported as cancel |
| Folder ZIP streaming: cancel mid-generation | [SRC] writer dies on the broken pipe; [JVM] the delivery gate | ✅ generation stops, no false Completed, verdict path preserved |
| Repeated cancellation attempts | [JVM] + [WEB] | ✅ safe, consistent |
| Legacy Safari / modern browsers / Chrome | [SRC] table §5 + [WEB] harness UA | ✅ each path's behavior stated and pinned; **not physically executed** |
| Normal download without cancellation | [WEB] p27 20/20 + [JVM] regression pins | ✅ unchanged |
| Performance | [BENCH] | ✅ no hot-path change; no regression |
| Other receiving devices | [JVM] non-handoff pins + full suite | ✅ existing behavior intact |
| **Physical iPad 4 / any real device** | — | ❌ **not performed — no device exists here** |

## 9. Signing — attached key, verified, and used [APK]

The attached `lumidrop-signing-backup.zip` was inspected: keystore `lumidrop-release.jks`, alias **`lumidrop-release`** (PrivateKeyEntry, valid 2026–2054), credentials in `signing-values.txt`. The keystore's certificate **SHA-256 fingerprint matches the README's declared identity exactly**: `1E:39:7D:74:F9:1C:4D:50:43:D9:5F:BF:D0:CB:81:60:0F:72:17:60:FE:3B:57:48:F4:03:AE:61:1D:66:C9:5A`.

- Wired through the build system's own designed mechanism (Pass 20 §8): the **git-ignored** `signing.local.properties` + the keystore at the project root (both excluded from every deliverable zip; `signingConfigs.release` picks them up; v1+v2+v3 schemes, matching the published signature surface).
- **Verification honesty:** the only "existing 1.0.7 application" in these materials is the Pass-27 release build, which was **unsigned** — so a certificate match against it is vacuous, and no signed production artifact exists here to compare against. The keystore's *internal* consistency is fully verified (alias + password + the fingerprint its own README declares). **Whether it matches the certificate of the application installed on your device is verifiable only on your side** — compare your installed app's signing certificate SHA-256 against the fingerprint above (e.g. `apksigner verify --print-certs` on your installed APK, or your platform's app-info sheet) before publishing.
- The built release APK is signed with this identity; `apksigner verify` output is in the delivery log. No credential appears in source, deliverables, or this document's build files; the keystore is not inside the APK.

## 10. Remaining limitations

- Physical-device cancellation behavior (the actual iPad 4 DM's cancel semantics, the evidence-window timing as felt by a user) is unverified here — engine-level and harness-level verification only.
- A save-sheet cancel before the DM's first re-request remains indistinguishable from a pending sheet (Case A by design; capped window; documented in §5).
- The with-bytes deliberate cancel lands CANCELLED after the 5-second evidence window rather than instantly — the honest hedge for a download manager's own auto-resume; shrinking it would misread client-side network blips as cancels.

# GOAT-PASS 15 — Transfer Performance, ZIP Streaming Reliability, Folder Synchronization, Background Presence & the Active-Tab Selection Overhaul

> The specification this pass was implemented against, preserved in summary
> form. Baseline: the completed GOAT-PASS 14 Phase 2 source (versionCode 70).
> Every fix below was made by inspecting and tracing the actual Pass 14
> implementation first; the working transfer architecture is reused, never
> replaced. Version: **versionCode 71**. Deliverables: the complete source
> ZIP, a genuinely-built signed release APK, and this report + `TESTING.md`.

---

## 1. Folder transfer speed & overall transfer performance (§1, Critical)

**Root-cause findings (from tracing the receiver's per-file path):**

1. **The completion response was held hostage by post-arrival side effects.**
   `apiNativePushFile` ran `MediaScannerConnection.scanFile` (a MediaProvider
   service bind that can cost real time on busy devices), a full
   search-index invalidation + `LibrarySync` fan-out, and the batch
   notification *in front of* the HTTP response. The sender's next file is
   gated on that response, so every one of those costs was per-file dead
   time on the wire — the direct mechanism behind both the reported 3–4 s
   "stuck at 100%" (§2) and a large share of a folder's per-child overhead
   (§1).
2. **Burst arrivals multiplied the cost.** A 27-child folder fired 27
   search-index invalidations and 27 `LibrarySync` notifications back to
   back, each kicking background storage work that competed with the very
   disk the receive pipeline needed — overhead that grows with folder size.

**Corrections:**

- **The prompt completion tail** (§2.3): the response now leaves the instant
  the file is durably received, registered and its row Completed. Everything
  the transfer contract needs — mtime stamp, sender memory
  (`rememberSender`), `TransferManager.complete` — runs before the response
  and costs microseconds (in-memory + single syscalls). The media scan, the
  storage nudge and the batch notification moved to a dedicated
  single-thread background **arrival executor** (`ldx-arrival`, background
  priority) — genuine consequences of the landing, but never again between
  the last byte and the response.
- **Coalesced storage-change nudge** (§1.3): burst arrivals collapse into
  ONE search-index rebuild + LibrarySync fan-out ~600 ms after the last
  arrival (`scheduleArrivalNudge`). The per-landing files-version bump (the
  web views' SSE "something changed" signal) stays immediate, so browser
  clients are exactly as live as before — only the expensive rebuild is
  coalesced.
- The identical treatment was applied to the browser→native upload path
  (`apiUpload`), whose tail had the same shape.
- Concurrency discipline is unchanged (exactly 1 transfer at a time, by the
  GOAT-PASS 2 specification); the gains come from removing dead time, not
  from parallelism. The per-file checkpoint probe is kept deliberately — it
  carries real correctness weight (skip-already-delivered, decline-before-
  byte, pause-before-byte) at LAN round-trip cost.
- Child-to-child admission is prompt by construction (the dispatcher's
  semaphore releases the moment the previous leg returns, which is now the
  moment the response arrives).

**Measurements: NOT measured — no devices exist in this environment.** No
speed-up is claimed numerically. The structural analysis above (what ran
between the last byte and the response, and what now does not) is the
honest deliverable; §11.1's 27-item/124 MB comparison must be run on real
hardware.

## 2. The delay after large files reach 100% (§2, Critical)

Covered by the same prompt-completion fix: the displayed 100% previously
meant "all bytes handed to the socket" while the response — and therefore
the sender's `complete()` and the next file's admission — waited behind the
receiver's scan/index/notification tail. Those now run after the response on
the arrival executor. The receiver's row flips Completed *before* the
response (it is a map update), so neither side sits at a finished-looking
row while the other is done. No timers were used and no completion is
declared before the file is durable + renamed + registered: the rename, the
registry cleanup and `complete()` remain synchronous, before the response.
The 2 MB pipeline drain (`PipelinedFileWriter.finish`) is unchanged — it is
bounded, verified, and completes in milliseconds on healthy flash.

## 3. Native-to-native ZIP streaming (§3, Critical — blocking)

**Root cause (§3.1), found by tracing the whole lifecycle:** the bundled
NanoHTTPD implements chunked *responses* (`ChunkedOutputStream`) but had
**no decoding of chunked request bodies**. The ZIP-batch send streams an
unknown-length archive via `HttpURLConnection.setChunkedStreamingMode(256
KB)`, so the receiver's endpoint was handed the raw chunk *framing* — hex
sizes, CRLFs, the terminating `0\r\n\r\n` — as if it were payload. The
result: a corrupted, oversized file on disk **and** a read that only ever
"ended" when the connection itself died — the ZIP never finalized, the
sender waited on `responseCode` until its 45 s read timeout, re-POSTed, and
the second POST queued forever behind the first handler's blocked read at
the receiving-slot gate. This is precisely the reported "progress reached
the expected size but the streaming bar never finished." The browser ZIP
workflow was never affected because a browser *downloads* (receives a
chunked response, which it decodes) rather than POSTing a chunked body.

**Correction:**

- **`NanoHTTPD.ChunkedInputStream`** (new, pure Java, API-independent):
  a strict RFC 2616 §3.6.1 decoder (chunk sizes with extensions, per-chunk
  CRLF, trailer lines, the terminating zero chunk → genuine EOF; a
  connection dying inside a chunk **throws** — the exact signal the
  interrupted-receive path needs). `HTTPSession.getInputStream()` lazily
  wraps the session stream in it **only** when the parsed request headers
  carry `Transfer-Encoding: chunked`; every Content-Length request path is
  untouched. **Pinned by `GoatPass15ChunkedRequestTest`** — six tests run
  against the actual bundled server on real sockets, including the exact
  production client shape (`HttpURLConnection` + chunked mode + 700 KB) and
  byte-for-byte Content-Length non-regression.
- **Archive integrity gate (§3.2)** — `transfer/ZipIntegrity` (new, pure
  Kotlin, JVM-tested): an unknown-length entry's finalized file must end
  with a structurally valid End Of Central Directory record (backward scan,
  bounded by the 64 KB comment window, comment length must land exactly at
  EOF) before it is renamed to its destination and registered. A stream that
  reached EOF without a closed archive resolves as an honest failure with
  the file deleted — never a corrupt "success". Pinned by
  `GoatPass15ZipIntegrityTest` (truncations at five cut points, honest vs.
  forged EOCD, comments, non-ZIP files).
- **§3.3 — Android 5:** the defect above is protocol-level and
  version-independent — the decoder is plain Java and the sender's chunked
  mode is available since API 19, so the fix applies identically to an
  Android 5 receiver. The accept chain itself was audited for API-21
  hazards and contains none (plain map writes, `JSONObject`, no
  post-API-21 calls on the accept→status→dispatch path). **Physical Android
  5 verification remains outstanding** (no device here) and is listed as
  such in TESTING.md.
- **§3.4 — Pause removed for ZIP streaming:** a new `Transfer.isZipStream`
  marker (set on the sender's archive row from its manifest build, and on
  the receiver's mirror row from the manifest entry's negative declared
  size — no wire-protocol change) makes every surface treat the streamed
  archive as never-pausable: the per-row Pause button is not offered on
  either side (a stale pause set by an older peer still gets its Resume so
  a row can never strand), `pauseAllActive` skips such rows, and the new
  selection toolbar's Pause filters them. Cancel, failure and the
  established Resend flow are untouched. Pinned by
  `GoatPass15ZipStreamPolicyTest`.
- **§3.5 — failure/cancellation/recovery honesty:** an interrupted streamed
  ZIP no longer keeps a "Waiting for reconnection" card or a
  resume-registry entry — unknown-length archives have no per-offset
  identity, so byte-resume never existed for them; the partial is deleted
  and the row resolves as a plain short-lived Failed. The sender's automatic
  retry still lands a genuinely fresh attempt (from byte 0, the designed
  behavior), and Resend works exactly as for any failed transfer.

## 4. Folder child decline, cancellation & manifest synchronization (§4)

The Pass 14 exclusion machinery (stable root-relative child paths, probe
refusal + write refusal + durable tombstones, `excludedNested` on the wire)
is reused unchanged. **The missing piece this pass fixes:** the sender's
dispatcher stopped polling the receiver's status the moment every child of
an accepted folder had been *admitted* (each then parked on the dispatch
semaphore, one streaming at a time) — so exclusions made *while the folder
streamed* were learned only one-by-one at each child's own POST refusal,
and the sender's totals kept the original count ("0/30") for the whole
batch.

**Correction — the streaming-phase sync poll (§4.3):** a companion poller
runs for exactly as long as any admitted leg is live (one tiny status GET
every 2 s — invisible next to a 256 KB chunk). Its answer carries the
receiver's CURRENT `excludedNested` map and per-item decisions;
`pollDecisions` (the proven Pass 14 machinery) resolves any just-excluded
row Declined on the spot — never a wasted byte — so both sides converge on
the same eligible-child manifest *during* the batch: the sender's folder
derives "0/25" over `eligibleMembers` within a couple of seconds of the
receiver's ✕, and completes "25/25". The poll also keeps the receiver's
push-record liveness clock fresh for the whole stream (a bonus for the
dead-sender sweep) and stops the instant no leg is live — nothing polls
during an all-paused session. Child-vs-folder cancellation semantics
(per-file `propagateSingleFileCancel`, one-request `scope=push` group
teardown, `requestCancelBulk`, completed-children preserved) are reused
exactly; the new Active-tab Cancel (§9) routes through the very same
functions, so it cannot introduce a second cancellation system.

## 5. First-time ZIP explanation (§5)

The modal now carries the full explanation: the required description, a
**BENEFITS** section (3 bullets) and a **LIMITATIONS** section (5 bullets)
rendered in the app's own visual language (accent caption + 12.5sp bullets
through LdxDialog's `bodyView`). Every limitation reflects the actual
implementation — in particular the resume limitation is stated exactly as it
is: *"If the network connection is interrupted, the ZIP transfer cannot
resume from where it stopped — it restarts from the beginning."* **One
action, "Understood"** (primary): it acknowledges AND enables, latches the
`zip_send_explained_ack` preference (the modal never appears again), paints
both ZIP icons enabled and updates the ONE shared ZIP preference.
Dismissing any other way (backdrop, back) leaves ZIP mode OFF — and because
the latch is only written by "Understood" itself, a dismissed explanation
simply reappears on the next first-time enable attempt. Auto Send / Auto
Accept are untouched.

## 6. Background device presence (§6, Critical)

**Root cause:** the Pass 17 probe design *intended* to keep throttled-but-
alive peers (backgrounded app, frozen UDP beacons, live foreground-service
HTTP server) — its own comment says so — but the 3-second expiry sweep
removed any peer silent past `PEER_EXPIRE_MS` (40 s) **unconditionally**,
never consulting the probe. A backgrounded-but-running app survived 6→40 s
through the probes, then was hard-dropped at the boundary: exactly
"minimized for about a minute → disappeared from Nearby Devices."

**Correction:** expiry is now **proven, not assumed**. At the 40 s boundary
the peer gets one bounded HTTP liveness probe (`/api/ping` on its own
server — any answer proves the process is alive): an answering peer is
refresh-lived and stays fully visible (its persistent service IS the
app-running signal); only a peer that is both silent AND unreachable is
dropped. The probe shares the existing single-thread executor and in-flight
guards (never two probes on one id), wrongly-dropped live peers still
re-announce via the beacon/reply-on-first-sight path, and a force-stopped
app (server dead) still clears within the window plus one probe — no
indefinite staleness. The beacon loop itself was audited: it is owned by
`ServerForegroundService` (start/teardown in the service lifecycle), not
the Activity, and `MainActivity.onPause` stops nothing it needs.

## 7. Music thumbnails on first visible entry (§7, High)

**Root cause:** `bindAlbumArt`'s completion handler required
`target.isAttachedToWindow` before setting the decoded art (and the
`dispatchDecodeResult` waiter path did the same). RecyclerView's GapWorker
**prefetches** rows one-to-four items ahead of the viewport while they are
still *detached* — a decode completing inside exactly that window dropped
its result on the floor. The bound-but-detached row then entered the screen
showing the fallback glyph, and only the next rebind (the user's
scroll-away-and-back) hit the now-warm cache: the reported "a few songs
show no thumbnail until you scroll past and back", deterministically
afflicting fast decodes during downward scrolling.

**Correction:** the attach requirement is removed from both guards; the TAG
check ("is this still the view bound to this key?") is the correct and
sufficient guard — setting a drawable on a bound-but-detached view is safe
and simply draws with the art the moment it attaches. Cache, dedup,
await/dispatch, glyph fallback and the missing-art memory are all
unchanged, so the already-fast paths keep their exact behavior and nothing
re-decodes.

## 8. Cancel All + partial-received cleanup (§8, High)

The separate "Clear Partial Received Items" menu action is **retired**; the
three-pass cleanup it owned (live interrupted-receive cards + resume-registry
orphans + orphaned `.wsxpart` staging partials, never touching the staging
of a live receive in progress) now runs as an integral part of **Cancel
All**'s confirmed action, after the propagation/teardown steps. One action,
one complete story: transfers stop, senders are told, and nothing
incomplete remains. The cleanup only ever touches data reliably associated
with interrupted receives — completed files, their Received entries and
valid History records are untouched. The summary line's "N waiting to
resume" figure (display-only) is unchanged.

## 9. Active-tab selection & bulk-action toolbar (§9, Critical)

Built by **reusing the established selection architecture** (the same
tab-parameterized primitives Received/History use — long-press entry with
the pressed row selected, tap-to-toggle inside the mode, range anchoring,
Hold & Slide drag select, Select All over a tab-specific eligibility gate,
per-tab independent key sets):

- **Eligibility** (`isRangeSelectableIn`): on Active, exactly the rows the
  lifecycle actions can honor — any live solo row (waiting, streaming,
  paused, reconnecting; a streamed zip included) and any group with a live
  member. Terminal flash rows are never selectable. Received/History keep
  their classic deletion contract, byte-for-byte.
- **The header swap (§9.2):** the Active tab's ONE header row (the ⋮ menu +
  live summary line) hides both in selection mode and hosts the toolbar in
  their place — no second toolbar, no dead space, the same position and
  dimensions the Received/History headers use.
- **The toolbar (§9.3), in exact order:** counter chip (the identical
  number-only capsule, patched live) · **Pause** (the app's Sending purple,
  `ic_pause`) · **Resume** (the Pause action's orange, `ic_play`) ·
  **Cancel** (the app's red) · **Close** (minimalist icon-only glyph, muted,
  far right). Close exits the mode and cancels nothing; Cancel cancels
  transfers — visually and semantically distinct by design.
- **Mixed types (§9.5):** Pause applies only to live, unpaused, non-zip rows
  (already-paused rows stay paused; waiting/failed/terminal/zip rows are not
  shoved into invalid states); Resume applies only to genuinely paused rows
  and relaunches parked native sends (`resumeFile`) exactly like the per-row
  button — it can never pause anything; Cancel routes each selected item
  through the SAME propagation the per-row and whole-folder buttons use
  (single-file cancel for native sends, the Quick-Share withdrawal, one
  `scope=push` request per folder group — never duplicate requests). Every
  action reports honestly ("Paused 3 · 2 already paused", "Nothing to
  resume", "Cancelled 4 items and 1 folder") — a partially-eligible batch is
  never silently claimed as fully affected.
- **Reliability (§9.6):** row keys are the stable transfer/group identities;
  every refresh reconciles the selection against the live rows (a selected
  transfer that completes, fails or disappears drops out of the set and the
  counter updates in the same tick); leaving the tab exits the mode; an
  empty Active tab ends the mode.
- **Outside selection mode, the Active tab is unchanged** (§9.7): the ⋮ menu
  (Pause all / Resume all / Cancel all, now with the §8 integration), the
  summary line, per-row actions, the Clear Failed pill, sorting and layout.

**§9.4 — Received/History consistency:** both tabs' selection toolbars
replaced their text "Cancel" exit with the SAME minimalist Close glyph at
the far right, preserving Delete, Select All and the counter exactly as
they were. The exit affordance is now one consistent, non-destructive icon
across all three tabs, and can no longer be confused with Active's genuinely
destructive Cancel.

## 10. Regression prevention (§10)

- The full GOAT-PASS 13/14 JVM regression suite passes unmodified (131
  tests) alongside the 15 new ones — **154 tests, 0 failures**, inside the
  real Gradle build.
- The Content-Length request path is pinned byte-for-byte by test.
- The browser ZIP workflow (chunked *responses*), Quick Share, pause/resume
  for ordinary files, resume windows, folder snapshots, Resend and the
  wire protocol are untouched; the only wire-visible additions are the
  already-existing `excludedNested`/`decisions` fields being polled during
  streaming (old receivers/senders ignore them by design).
- API 21 → Android 16: the decoder is plain Java; the one new platform
  call (`TextView.setLetterSpacing`, in the ZIP explanation modal) is API
  21+; the Android-5 audit of the accept path found no gated APIs. iPad 4 /
  iOS 10.3.3 Safari: no web-client changes this pass; `assets/index.html`
  is byte-identical to Pass 14 (verified inside the shipped APK).

## Tests actually performed (all executed in this environment)

1. **Real Gradle release build** — `assembleRelease`: **BUILD SUCCESSFUL**
   (2m 46s), R8-minified, signed with the bundled key (SHA-256
   `1c179d0e…f123cf`, the same as every prior pass), `versionCode 71`,
   `versionName 1.0.0`, minSdk 21 / targetSdk 34. Delivered as
   `LumiDropX-GOAT-PASS-15-release.apk`. The shipped dex verifiably contains
   this pass's code (`ldx-arrival`, the EOCD gate's error string), and the
   embedded `assets/index.html` is byte-identical to the source tree.
2. **`testReleaseUnitTest` inside the real build**: **154 tests, 0 failures,
   0 errors** — the complete prior regression suite plus
   `GoatPass15ChunkedRequestTest` (6), `GoatPass15ZipIntegrityTest` (5),
   `GoatPass15ZipStreamPolicyTest` (4).
3. **Full-tree kotlinc parse** (main + test): zero syntax-class errors; the
   error-class histogram contains **no class the Pass 14 baseline lacks**
   (only additional instances of the same unresolved-SDK cascade).
4. **NanoHTTPD standalone `javac` compile** (pure Java, JDK 17): clean.

## Tests NOT performed (no devices/emulator/browser in this environment)

Everything in the task's §11 matrix requiring hardware — the 27-item/124 MB
performance comparison and before/after timings (§11.1, no numbers claimed),
large-file finalization timing (§11.2), on-device native ZIP end-to-end
including **Android 5 acceptance** and archive extraction (§11.3), the
child-decline reopen/reconnect/backgrounding scenarios (§11.4), the ZIP
first-time modal on screen (§11.5), background presence across minutes and
network interruptions (§11.6), Music thumbnails over a large library
(§11.7), and the Active-selection/Cancel-All interactions (§11.8/§11.9).
TESTING.md lists each with its precise status.

## Remaining known limitations (honest accounting)

1. **Performance claims are structural, not measured.** The 3–4 s tail and
   the folder overhead are removed by construction (nothing but durable
   write + rename + registration now precedes the response; burst index
   rebuilds are coalesced; scans are off the critical path), but real
   before/after numbers need the §11.1 hardware run.
2. **OEM beacon throttling cannot be prevented from here.** The §6 fix makes
   presence *survive* frozen beacons (HTTP-probe-proven liveness), but a
   device whose OEM kills the whole process (not just the beacons) will —
   correctly — disappear once its server stops answering.
3. **Android 5 ZIP acceptance is fixed at the protocol level but not
   device-verified.** The chunked defect was the only plausible mechanism
   found in a full trace of the accept→dispatch→stream path, and the fix is
   version-independent; a physical Android 5 receiver remains the final
   word.
4. **The inter-file checkpoint probe remains** (~one LAN round trip per
   file): it was kept deliberately for its correctness value
   (skip-already-delivered on Resend flows, decline-before-byte,
   pause-before-byte). On a LAN it is negligible; it is not the observed
   bottleneck (the removed tail was orders of magnitude larger).
5. **A streamed ZIP paused by an older-version peer** (this version's UIs
   never offer it) degrades to a restart-from-byte-0 on resume — the same
   honest behavior as any interrupted archive, never a corrupt stitch.
6. **The Active toolbar's five parts** sit in the shared 34 dp pill row; on
   the narrowest screens with the largest system font scales the labels
   compress but the pill does not scroll (the established pill design,
   kept per §9.3's "same visual foundation").

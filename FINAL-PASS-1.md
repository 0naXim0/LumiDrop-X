# LumiDrop X — FINAL PASS 1: Transfer-System Stabilization Report

> **Version:** versionCode 72 (FINAL PASS 1) · Baseline: GOAT-PASS 15
> (versionCode 71), delivered as `LumiDrop X GOAT-PASS 15 source.zip`.
> Method, per the pass's Core Engineering Rules: the complete Pass 15
> source was extracted and the sender-to-receiver lifecycle of every
> affected transfer type was traced **before** any change; each fix below
> names its root cause as found in that trace, reuses the existing
> systems, and is pinned by an executed automated test wherever the JVM
> can reach. The working transfer architecture is reused, never replaced.

---

## 0. Executive summary of the four root causes

| # | Reported symptom | Root cause (traced, not guessed) | Fix |
|---|---|---|---|
| 1 | Declined children stay in the incoming sheet until close/reopen; sender keeps "0/30" instead of "0/27" while Waiting; later self-corrects "2/30"→"6/27" | **(a)** the receiver's re-render gate (`contentSignature`) never carried the per-child exclusion map, so the ✕ changed the rendered data without changing the gate; **(b)** the sender's exclusion matcher stripped the top-level prefix from its relPath and compared it against wire entries that (per the receiver's own writer) still **carry** the prefix — nothing ever matched, so poll-driven Declined resolution never fired and every exclusion was learned only at that child's own POST refusal | (a) exclusions folded into the signature — same-tick re-render, expansion + scroll preserved; (b) one shared matcher (`ChildExclusions.isExcludedOnWire`) accepting both wire shapes — pinned by 8 JVM tests |
| 2 | Receiver stuck at "1/9", "2/9", "8/9" while sender reaches "9/9 Completed" | The receiver's refusals to body-bearing POSTs answered with the connection kept **alive** (`jsonErr`), leaving unread body bytes on pooled keep-alive sockets — the next request's header parse swallowed them as garbage, cascading into probe/POST failures the sender rode through with retries that looked exactly like "stuck, then inconsistently continues" | Every body-bearing early return in `apiNativePushFile` now closes its connection (`jsonErrClose` — same status, same error body the sender's classifier matches on, only `Connection: close` added) |
| 3 | Native-to-native ZIP stalls at the final byte count (~21.6 MB), loading forever, never Completed | **(a)** `HTTPSession` served every keep-alive request on a connection but cached its lazily-built body stream across requests — a second body-bearing request on the pooled socket got the first request's stream: after a chunked POST an **already-EOF** stream (receiver "completes" 0 bytes → EOCD gate refuses → retry forever), after a Content-Length POST the **raw** stream with chunk framing in it (read only "ends" when the connection dies). **Reproduced on the real server** (see §10.1). (b) the receiver's 5 s socket read timeout aborted legitimately slow live-archive senders mid-body | (a) the body stream is re-derived per request (`execute()` clears it; `getInputStream()` re-wraps from THIS request's headers) — reproduced-failing → passing A/B; (b) transfer endpoints raise their connection's read window to 45 s (`extendSocketReadTimeout`) — matches the sender's own read timeout and 3× the 15 s stall watchdog, so dead connections still die bounded |
| 4 | ZIP interruption leaves rows loading; sender's ZIP item hides Resend | A streamed archive that exhausted its retry window resolved `fail(interrupted = true)` — the chip read "Auto-Resume on Reconnection" (a lie: nothing auto-resumes a restart-from-byte-0 archive) and the Resend action is offered only for non-interrupted rows | Streamed-ZIP terminal states resolve as plain **Failed** with the Resend action, exactly like the receiver's mirror row |

Section §5 (password manager), §6 (toolbar), §7 (Cancel All) and the §8
audit each have their own root-cause sections below.

---

## 1. Folder child decline & instant sender/receiver synchronization (§1)

**Root cause (a) — the receiver's frozen sheet.**
`ReceivingModal.updateIncomingRequests` re-renders the live sheet only when
`contentSignature(pushes, uploads)` changes. That signature carried each
push's identity, phase, decisions, entry count and byte total — but **not**
`excludedNestedByTopLevel`, the set the ✕ writes. The rendered rows are
built from the exclusion-**filtered** child list, so a ✕ changed what the
rows show without changing what the gate compares: identical signature →
no re-render → the declined children stayed on screen until the sheet was
dismissed and rebuilt (the reported "disappear only after closing and
reopening"). **Fix:** the exclusion map is folded into the signature (a
stable, sorted per-top-level join). The ✕'s `updateIncomingRequests()` call
now re-renders on the same tick; `renderIncomingRows`' own gate already
carried the filtered children, and the rebuild path preserves each expanded
folder box (`setExpandedInstant` from `expandedIncomingFolderKey`) and its
scroll offset — so the sheet answers the ✕ **in place**, no reopen needed.

**Root cause (b) — the sender that never learned.**
The receiver's incoming sheet passes `"$top/$childPath"` into
`excludeNativePushNestedPath`, so the wire's `excludedNested` values are
full paths (`"Photos/IMG_02.jpg"`) — while the documented form, and the
sender's matcher, assumed top-relative entries (`"IMG_02.jpg"`): the sender
stripped the prefix from its own relPath and compared against entries that
still carried it. **No exclusion ever matched.** The Pass-15 sync poll
(the machinery that carries `excludedNested` on every status tick, running
the whole time the folder waits and streams) was delivering the truth to a
matcher that could not read it — so the sender's pre-registered rows never
resolved Declined, the folder's derived totals kept the original count
("0/30") for the entire batch, and each exclusion surfaced only at that
child's own POST refusal, one at a time — the exact "shows 2/30, later
corrects itself to 6/27". **Fix:** one shared pure matcher,
`ChildExclusions.isExcludedOnWire(relPath, byTop)`, accepts **both** shapes
(the documented relative form and the full-path form every receiver of this
codebase emits); `NativePushClient.isExcludedNow` now delegates to it. The
sender's defer loop resolves an excluded child Declined within one
~700 ms poll tick — **before acceptance** (the loop polls the whole time
items are pending) and during streaming (the Pass-15 companion poller) —
so the sender's Waiting card derives "0/27" over `eligibleMembers` the
moment the receiver's ✕ lands, and no stale total can appear later because
the group's totals are always derived fresh from member statuses
(`eligibleMembers`/`groupProgressPct`/`groupStatus`), never cached.

**Stable identity & persistence (§1.5)** — unchanged by design and
re-verified: children are identified by root-relative paths end-to-end
(manifest → `uploadRequestId "pushId::relPath"` → tombstones); declined
children stay declined across sheet minimize/restore (the exclusion set
lives on the push record, not the view), across re-render (the rebuilt rows
filter through the same set), and across reconnect (the set survives; the
sender's next poll re-reads it). Duplicate ✕ taps are idempotent (a set
add). The original sender files are never touched. No `isExcludedNow`
match can over-reach: prefix siblings ("Subfolder2" vs "Subfolder"),
other top-levels' sets, and empty/null maps are all pinned as never
excluding (§10.2).

---

## 2. Folder transfers stalling at intermediate child progress (§2)

**Root cause — poisoned keep-alive connections.** For each child the
sender makes a probe GET then a body-bearing POST, over `HttpURLConnection`'s
pooled connections. `apiNativePushFile` answered several refusals with
plain `jsonErr` — HTTP 400, **connection kept alive** — *while the POST's
full body was still inbound*. Those unread bytes sat on the socket, and the
session's next request-header parse consumed them as garbage: the follow-up
probe or re-POST on that pooled connection failed in ways that surfaced as
probe-unreachable → backoff → re-POST → refusal cycles. On the receiver
this reads as "nothing appeared to happen" while children sit unresolved;
on the sender as retries that eventually complete or fail — with the
side's states disagreeing for seconds at a time and sometimes resolving
late ("in some cases the receiver later appeared to continue").

**Fix:** every early return in `apiNativePushFile` that answers before the
body is consumed now returns `jsonErrClose` — the *same* status, the same
error body the sender's classifier keys on ("Not accepted", "Cancelled by
host", …), with `Connection: close` added so the client drops the socket
instead of reusing a poisoned one. (The endpoints that answer *after*
fully consuming a declared-length body keep plain `jsonErr` — their
connections are exactly aligned.) Combined with the §3 wire fixes, the
entire class of connection-history-dependent failures collapses: each
child's POST is answered on a clean stream, and the sender advances only
on the receiver's genuine per-child 200 — which the receiver only sends
after `writeRawStream` finished, the staging file was renamed, and
`TransferManager.complete(tid)` flipped the row (the Pass-15 prompt-tail
contract, unchanged and re-verified in the trace).

**Per-child lifecycle re-verified end-to-end (§2.2):** initialization
(pre-registered PENDING row) → acceptance (`registerPendingTransfersFor`)
→ probe (checkpoint/decision gate) → POST (receiving-slot gate →
`PipelinedFileWriter` staging write → durable `finish`) → rename →
`complete(tid)` **before** the 200 → sender `SUCCESS` → `complete(tid)` on
its own row → semaphore release admits the next child. The receiver can
now never sit unresolved while the sender completes: every 200 the sender
counted is produced by a handler that completed the receiver's row first.

---

## 3. Native-to-native ZIP transfers stalling at the final byte count (§3)

**Root cause (a) — the keep-alive body-stream cache (the decisive one).**
One `HTTPSession` object serves *every* request that rides a keep-alive
connection (`DefaultServerRunnable.run`'s `execute()` loop), but the
Pass-15 `ChunkedInputStream` wrap was built lazily **once per session** and
cached in a field. A second body-bearing request on the same pooled socket
received the *first* request's stream:

* after a **chunked** POST (the streamed archive): a stream already at its
  decoder EOF — the receiver "completed" an **empty** body; for an
  unknown-length archive `writeRawStream`'s `completed` check passes with
  zero bytes, the rename lands an empty file, the EOCD gate refuses it
  ("Incomplete archive received"), the sender classifies the 500 as
  RETRYABLE and re-streams the whole archive — onto a connection pool that
  keeps handing it poisoned sockets: the exact "progress reaches the
  expected size but the streaming bar never finishes", recurring and
  connection-history-dependent;
* after a **Content-Length** POST (an ordinary file that rode the same
  pooled socket): the **raw** socket stream with the chunk framing still
  in it — the receiver consumed framing bytes as payload and the read only
  ever "ended" when the connection itself died.

**Reproduced on the real bundled server before any fix**
(§10.1): a first chunked POST of 5 MB succeeded; the second on the same
pooled connection read `eof:0` and the sender died with "Error writing
request body". **Fix:** `execute()` clears the cached body stream at the
top of every request, so `getInputStream()` re-derives the wrap — chunked
or raw — from **that request's own parsed headers**. The same scenario now
round-trips 21.6 MB byte-for-byte.

**Root cause (b) — the 5-second socket read timeout vs. the live archive
generator.** The receiver's `SOCKET_READ_TIMEOUT` (5 s) is right for API
chatter and idle keep-alives, but a live-synthesized archive sender
(`FileTreeNode.ZipBatchNode` — a pipe-fed `ZipOutputStream` walking SAF
sources) can legitimately pause mid-body for seconds on a slow
ContentResolver open. The receiver's false "Read timed out" deleted the
partial, failed the row, and fed the sender's restart-from-byte-0 loop.
**Fix:** the two body-streaming endpoints (`apiNativePushFile`,
`apiUpload`) raise *their own connection's* read window to 45 s via the
new `IHTTPSession.extendSocketReadTimeout` (a small, safe addition to the
bundled NanoHTTPD: the session now holds its socket; the call is a
best-effort `setSoTimeout`, no-op when unavailable). 45 s equals the
sender's own `readTimeout` and 3× the engine's 15 s stall watchdog — a
genuinely dead connection still dies bounded (verified: a sender dying
mid-chunk terminates the receiver's read in ~0.4 s on a hard close), while
a healthy-but-slow sender is never falsely aborted (verified: a 21.6 MB
chunked POST with a 7 s mid-body stall completes).

**§3.3 finalization order — re-verified in the trace and pinned by the
round-trip test:** decoded-EOF (never "byte count reached") →
`pipe.finish(trimTo)` (durable, 30 s bounded, honest failure on wedge) →
rename → **EOCD structural gate** (`ZipIntegrity.endsWithCentralDirectory`)
→ staging meta/registry cleanup → `rememberSender` → `complete(tid)` →
200 → sender `SUCCESS` → both rows Completed. A 21.3 MB real archive was
streamed through the patched server, passed the gate, **opened as a real
`ZipFile`, and every entry extracted byte-identical** (§10.4) — the
§3.5 "actually inspect or extract" mandate, executed.

**§3.4 browser vs native differences — resolved:** the browser ZIP
workflow *downloads* a chunked **response** (decoded by the browser, never
touched by this bug); the native sender *uploads* a chunked **request**
onto pooled sockets — which is why only native-to-native stalled, and why
the Pass-15 fix (correct for a fresh connection) did not close it. The
browser workflow is byte-identical in this pass (no web-client change; the
response path is untouched).

---

## 4. ZIP failure state & exact-recipient Resend (§4)

**§4.1 failure states:** an interrupted streamed archive already resolves
on the receiver as an honest short-lived Failed (partial deleted, EOCD-
gate failures likewise) — kept from Pass 15 and re-verified. **The sender
half was the defect:** on retry-window exhaustion and on SESSION_GONE it
marked the row `fail(interrupted = true)`, which (a) rendered the
"Auto-Resume on Reconnection" chip for an archive that by design can only
restart from byte 0 (nothing will auto-resume — the window is closed), and
(b) hid the Retry/Resend action (offered only for non-interrupted FAILED
rows). **Fix:** both terminal arms resolve a streamed-ZIP row as a **plain
Failed** — the honest state, with the Resend action visible.

**§4.2 Resend semantics — traced and re-verified, no change needed:** the
sender's Retry calls `NativePushClient.retryFile(tid)`, which re-launches
`sendOneFile` with the **original session** (same peer = the exact
intended recipient, same logical batch), re-probes, and streams a **fresh
`ZipBatchNode` generation** over the same frozen snapshot roots — the
original logical ZIP identity and contents, rebuilt live, never stitched
onto the failed attempt's partial (which the receiver deleted). A Resend
to a swept/restarted receiver honestly resolves SESSION_GONE (its probe
answers "Unknown session"), never a silent ghost. If the new attempt fails
again, the row returns to Failed with Resend available — verified by
trace and by the existing GoatPass15ZipStreamPolicyTest engine pins.

**§4.3 receiver-side:** a fresh re-POST after failure is refused nothing —
the push record's decision stays "accepted", the claim chain's `start()`
fallback re-attaches the row to the same folder group
(`groupIdByTopLevel`), no duplicate incoming item appears, and the failed
row auto-dismisses on its own schedule. No resumability is claimed for a
streamed archive on either side (Pass 15 §3.5's honesty, unchanged).

**§4.5 — resumability:** correctly **not** claimed anywhere: unknown-length
archives have no per-offset identity; every retry is from byte 0, and the
ZIP-mode explanation modal keeps telling the user exactly that.

---

## 5. Android Password Manager prompt (§5)

**What was already there:** the single password field in the app
(`WebUiSettingsActivity` — there is **no WebView anywhere in this app**;
`MainActivity` hosts three native Fragments, confirmed by full-source
search) has carried `importantForAutofill = IMPORTANT_FOR_AUTOFILL_NO`
since Pass 14. The prompt still appeared "after saving the password
settings or when leaving the settings screen" — the autofill framework's
SAVE trigger firing on the activity transition.

**What this pass adds (both standards-based, both scoped to this one
screen):**

1. **Window-level exclusion** — `onCreate` marks the activity's decor view
   `IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS`: the entire view tree of
   *this settings window* is excluded from both fill and save autofill
   sessions. This is the strongest supported lever above the view level,
   and it addresses exactly the reported trigger: the save snapshot
   composed when leaving the screen can no longer see any credential-like
   field. The field-level `NO` stays as belt-and-braces.
2. **Session cancel on exit** — `onPause` calls
   `AutofillManager.cancel()`: if a save session was somehow already open
   against this window (an OEM timing variation), it is retired before the
   save UI can be offered. Documented, supported API; never throws;
   scoped to this activity leaving the foreground.

Everything else is untouched: password entry, the eye toggle, save /
change / disable, blank-field-means-keep semantics, authentication, every
other input in the app, and the OS-wide autofill setting. No fake fields,
no deceptive form structures, no global autofill disabling.

**Honest limits (per §5.3, stated rather than hidden):** (1) suppression
on a physical device has **not** been verified in this environment — there
is no emulator or handset here, and the platform's actual behavior (in
particular Google Play Services' and OEM keyboards' own password managers,
e.g. Samsung Pass, which are known to run their own flows) must be
observed on hardware; (2) an OEM keyboard shipping its own password
manager can still offer its own prompt — **no application API can control
those**. The browser-side login page already carries the full
standards-based posture from prior passes (autocomplete="off", a
non-login field name, no form-submission semantics) with the same honest
caveat: engines may override site instructions.

---

## 6. Active tab selection toolbar — no cropping on narrow screens (§6)

Pause, Resume **and** Cancel are now **icon-only** segments: the same
professional glyphs the per-row buttons and the bulk menu use
(`ic_pause`, `ic_play`, `ic_close`) in their established colors — Pause
keeps the Sending purple, Resume keeps the Pause action's orange, Cancel
keeps the app's red — with **actions byte-identical** (`bulkPauseSelected`
/ `bulkResumeSelected` / `bulkCancelSelected`). Icon-only segments widen
their horizontal padding (13 dp vs 9 dp) so the tap targets stay
comfortable inside the same 34 dp pill; `contentDescription` carries the
semantics the labels dropped. The counter chip (the one element whose
text must stay readable), the separators, and the minimalist far-right
Close keep their exact Pass-15 designs. With three labels removed, the
five-segment pill fits the narrowest screens at the largest font scales
without cropping, overlap or scrolling — the pre-emptive Cancel-to-icon
conversion of §6.3 applied since even without it the pill would still
have been tight at max font scale. Semantics are unchanged: Close still
exits the mode and cancels nothing.

---

## 7. Cancel All for "Auto-Resume on Reconnection" items (§7)

**Root cause:** the bulk menu's enabled condition was
`live.isNotEmpty()` over PENDING/ACTIVE rows — a receiver-side kept-for-
resume recovery card is **FAILED+interrupted**, so an Active tab holding
only such items greyed out Cancel All (and the selection gate
`isRangeSelectableIn` excluded them from individual selection and Select
All the same way). These are incomplete operations the engine can
genuinely cancel — the exclusion was simply wrong.

**Fixes (eligibility + lifecycle, not a forced button):**

1. **`anyStoppable`** now also counts `autoResumeReceiverRows()` (the one
   authoritative population — both phases: FAILED+interrupted recovery
   cards and live ACTIVE+reconnecting legs) — Cancel All stays enabled
   whenever there is anything it can genuinely stop.
2. **`isRangeSelectableIn("active")`** admits FAILED+interrupted rows
   (Solos and Groups with any such member): individually long-pressable,
   tap-togglable, drag-selectable, counted by the counter chip, and
   included in Select All — they are cancellable, so they are selectable.
3. **The bulk Cancel action** (`bulkCancelSelected`) routes each selected
   kept-for-resume row through the **same lifecycle Cancel All uses**
   (performClearPartialReceived pass 1): `requestCancel` (the
   authoritative cancellation system — resolves the row, writes the
   durable tombstone), staging partial + identity sidecar + registry
   entry deleted, the discard receipt that makes the sender **ask**
   before re-sending, `dismissResumable` — mirrored for Group members.
4. **Cancel All's action** already runs that same pass for every
   auto-resume row after its propagation/teardown steps (the Pass-15 §8
   integration, unchanged) — it was merely unreachable through the
   disabled button; with (1) it is reachable again.

**No resurrection (§7.4):** the durable tombstone refuses the sender's
in-flight re-POST and answers its next probe "cancelled" — the sender
resolves CANCELLED and stops; `isCancelAuthoritative` keeps the verdict
alive for the whole window, so no stale reconnect callback can restart a
cancelled item. Completed files, Received entries and valid History
records are untouched by any of these paths (the cleanup only ever
touches data reliably associated with the cancelled operations). The
cancellation survives reconnect and app restart by the same tombstone +
discard-receipt contract that already existed.

---

## 8. Deep investigation — related systems audit (§8)

* **8.1 state consistency** — group/row states are derived, never stored
  (`groupStatus`/`eligibleMembers` over live member statuses), so a stale
  event cannot "restore" an outdated total: the reported "2/30 → 6/27"
  late correction was the broken matcher (§1), not a stored aggregate.
  Terminal states stay immutable under `requestCancel`/`fail` guards
  (re-verified in source).
* **8.2 identity & correlation** — per-child identity is the composite
  `"pushId::relPath"` (pre-registration, claim chain, tombstones, discard
  receipts, control links); the resume-generation guard keeps an old leg
  from failing/parking a row a newer leg owns; a Resend attempt cannot be
  touched by its predecessor's callbacks (the `resumeGenOf` checks at
  every writeRawStream turn). Unchanged, re-verified.
* **8.3 progress correctness** — reaching a byte count never implies
  finalization: the receiver's unknown-length completion requires decoded
  EOF **plus** a structurally valid EOCD before rename/registration (and
  now actually reaches that code on pooled connections — §3). The
  `groupProgressPct` denominator is recomputed over eligible members each
  render.
* **8.4 resources** — the receiving pipeline's exactly-once slot release
  (`slotReleaseId` captured at acquisition, released in `finally`, rekeyed
  on claim-chain divergence), `PipelinedFileWriter`'s bounded 30 s drain
  with honest failure, the stall watchdog's permit force-release, and the
  arrival executor's coalesced side effects were all re-traced and are
  unchanged. The new 45 s read window holds one worker thread per dead
  connection at worst — bounded, daemon-threaded, and strictly more
  generous than the sender's own tolerance.
* **8.5 background/reconnection** — the Pass-15 sync poller keeps the
  receiver's push record liveness fresh during streams; the dead-sender
  sweep, auto-resume scheduling, and cancelled-transfer exclusion from
  future resume attempts are unchanged and were re-traced end-to-end.

**No security or connection restriction was weakened** (password gate,
Transfer Mode, Full Access, kick — untouched).

---

## 9. Regression prevention (§9)

Every listed behavior was preserved by construction — the fixes are
surgical (one cached-field reset, one matcher, one signature field, six
`jsonErr`→`jsonErrClose` conversions, two terminal-arm branches, two
autofill calls, toolbar segment construction, two eligibility
predicates); no transfer architecture, wire protocol (beyond
`Connection: close` on refusals — read by no existing peer as anything
but "drop the socket"), naming, or storage path changed. Compatibility:
all new code is API-21-safe (`extendSocketReadTimeout` is plain Java;
`importantForAutofill`/`AutofillManager` are API-26-guarded; no new
dependencies). The web client (`assets/index.html`) is **byte-identical**
to Pass 15 — the browser ZIP workflow, iPad 4 / iOS 10.3.3 posture, and
every web surface are untouched.

---

## 10. Verification — what was actually executed (§10/§11)

**Environment:** Linux container, JDK 17.0.9 (Temurin) + JRE 21, Android
SDK 34 (platform + build-tools 34.0.0, licenses accepted), Gradle 8.5 /
AGP 8.2.2 / Kotlin 1.9.22, JUnit 4.13.2. **No physical device, emulator,
or browser is available.**

### 10.1 Executed — root-cause reproduction on the REAL server (Java harness)

* **Before the fix:** a first chunked POST (5 MB, production
  `HttpURLConnection` shape) succeeded; the **second on the same pooled
  connection read `eof:0`** — the server handler saw an empty body and
  the sender died with "Error writing request body". A 21.6 MB chunked
  POST with a 6 s mid-body stall was killed by the receiver at ~6 s
  (`SocketTimeoutException` — the 5 s window), the exact
  restart-from-byte-0 mechanism. *(ZipStallRepro, run against the
  unmodified Pass-15 NanoHTTPD.)*
* **After the fix (same harness, patched server):** two consecutive
  chunked POSTs on one pooled connection both decode byte-for-byte; the
  21.6 MB stalled sender is carried through; fixed-length → chunked
  transitions decode; a sender dying mid-chunk terminates the receiver's
  read in ~0.4 s. *(ZipStallVerify — all scenarios pass.)*

### 10.2 Executed — new JVM test classes (13 tests, all passing)

Run with the **real production code compiled from the patched tree**
(Kotlin 1.9.22 + JUnit 4.13.2):

* `FinalPass1ExcludedWireShapesTest` (8) — both wire shapes, direct/
  subfolder/depth-3 removals, prefix-sibling and cross-top-level
  non-matches, the reported 30→27 arithmetic, empty/null maps. **All 8
  pass** against the new `isExcludedOnWire`.
* `FinalPass1KeepAliveChunkedTest` (4) — against the **bundled
  NanoHTTPD on real sockets**: a second chunked request over one raw
  keep-alive connection decodes fully (the eof:0 poison); a chunked
  request after a fixed-length request decodes fully (the raw-framing
  poison); a 7 s mid-body stall (> the server's own 5 s socket timeout)
  survives the endpoint-raised window; Content-Length bodies remain
  byte-for-byte. **All 4 pass on the patched server; all 4 fail on the
  pristine Pass-15 server** (the A/B pin).
* `FinalPass1ZipRoundTripTest` (1) — a real ~21.3 MB `ZipOutputStream`
  archive (10 entries incl. a directory entry and a text entry,
  NO_COMPRESSION — the ZipBatchNode shape) streamed with the production
  chunked client shape through the patched server to disk: passes the
  EOCD gate, **opens as a real ZipFile, every entry extracts
  byte-identical, no extra entries, exact byte count**. **Passes.**

Also re-run and passing: `GoatPass14ChildExclusionsTest` (6) and
`GoatPass15ZipIntegrityTest` (5) — the prior regression pins for the
machinery this pass touched.

### 10.3 Executed — full-tree Kotlin syntax check + baseline A/B

kotlinc 1.9.22 over all 78 `.kt` files (main + test), no-SDK classpath:
**zero syntax-class errors**; the error-class histogram contains **no
class the pristine Pass-15 baseline lacks** (same unresolved-SDK cascade,
identical classes; e.g. `unresolved reference` 12,410 = 12,410 baseline).

### 10.4 Executed — real Gradle build & engine test suite

* **`assembleRelease` (R8-minified, signed with the bundled
  `lumidropx-release.jks`): SUCCESS** — `app-release.apk` (3.69 MB),
  `versionCode='72' versionName='1.0.0' sdkVersion='21' targetSdk='34'`,
  signer SHA-256 `1c179d0e…f123cf` — **the same key as every prior pass**
  (installs as an update). Delivered as `LumiDropX-FINAL-PASS-1-release.apk`.
  The shipped dex verifiably contains this pass's code
  (`extendSocketReadTimeout`, `isExcludedOnWire`), and the embedded
  `assets/index.html` is **md5-identical** to the source tree (the web
  client is untouched, exactly as claimed in §9). Two genuine type errors
  (the read-window constant declared Long against an Int parameter) were
  caught by this real build and fixed before it went green.
* **`testReleaseUnitTest` inside the real build: 167 tests, 0 failures,
  0 errors** (JUnit XML aggregate over all 21 classes) — the complete
  GOAT-PASS 3–15 regression set (154 tests, including
  `GoatPass15ChunkedRequestTest`'s six pins against the modified
  NanoHTTPD — the Content-Length/chunked contract is unregressed) **plus
  this pass's 13 new tests** (§10.2). Build environment: Gradle 8.5 /
  AGP 8.2.2 / Kotlin 1.9.22, JDK 17.0.9, Android SDK 34.

### 10.5 NOT performed (no devices/emulator/browser here) — honest list

1. **§10.1's 30-item folder decline scenario on hardware** — the
   immediate UI removal and the sender's "0/27 while Waiting" are
   code-verified (the same-tick re-render; the poll-driven matcher with
   both shapes pinned by test), but the end-to-end interaction needs two
   devices.
2. **§10.2's nine-item folder completion run** — the connection-poison
   class is fixed and pinned at the wire level, but the full
   sender/receiver session needs hardware.
3. **§10.3's 21.6 MB device-to-device ZIP** — pinned at every layer the
   JVM reaches (wire decode, stall survival, EOCD gate, extraction,
   byte-exact round trip); the physical transfer remains unverified.
4. **§10.4's interruption → Failed → Resend cycle on devices** — traced
   and engine-pinned; not physically exercised.
5. **§10.5's Password Manager suppression** — not observable without an
   Android 14+ device with Google Play Services; the platform-level
   limits are stated in §5.
6. **§10.6/§10.7's on-screen toolbar and Cancel-All interactions** —
   built per the established toolbar architecture; not rendered here.
7. **Android 5 (API 21) behavior** — no gated API was introduced; the
   new paths are plain Java/Kotlin; physical verification outstanding.

---

## 11. Remaining known limitations (honest accounting)

1. **Device-level verification is outstanding** — every fix is
   root-caused, code-traced, and pinned by every automated check this
   environment can run (§10.1–10.4), but the final word on the §10 matrix
   belongs to hardware.
2. **OEM password managers** (Samsung Pass, vendor keyboards) run their
   own flows no app API can suppress — the §5 fix covers the platform's
   AutofillFramework surfaces only.
3. **The inter-file checkpoint probe** (~one LAN round trip per file)
   remains by design for its correctness value (skip-already-delivered,
   decline-before-byte, pause-before-byte).
4. **A genuinely wedged disk writer** still resolves a receive as an
   honest failure after the bounded 30 s drain — by design; no fabricated
   completions exist.
5. **Streamed ZIPs remain restart-from-byte-0** after interruption — the
   honest contract, unchanged, and stated in the first-time modal.

---

## 12. Files changed (complete list)

| File | Change |
|---|---|
| `fi/iki/elonen/NanoHTTPD.java` | per-request body-stream reset; session socket + `extendSocketReadTimeout` (interface + impl + constructor chain) |
| `server/LumiDropServer.kt` | `TRANSFER_BODY_READ_TIMEOUT_MS` (45 s); read-window raises in `apiNativePushFile`/`apiUpload`; six body-bearing early returns converted to `jsonErrClose` |
| `transfer/ChildExclusions.kt` | `isExcludedOnWire` — the one shared both-shapes wire matcher |
| `p2p/NativePushClient.kt` | `isExcludedNow` delegates to the shared matcher; streamed-ZIP terminal states resolve plain Failed (Resend offered) |
| `ui/ReceivingModal.kt` | `contentSignature` carries the exclusion map (immediate ✕ re-render) |
| `settings/WebUiSettingsActivity.kt` | window-level autofill exclusion + `AutofillManager.cancel()` on pause |
| `TransfersFragment.kt` | icon-only Pause/Resume/Cancel toolbar segments; Cancel-All eligibility + selection + bulk-cancel lifecycle for Auto-Resume rows |
| `app/src/test/…/FinalPass1ExcludedWireShapesTest.kt` | new — 8 tests |
| `app/src/test/…/FinalPass1KeepAliveChunkedTest.kt` | new — 4 tests |
| `app/src/test/…/FinalPass1ZipRoundTripTest.kt` | new — 1 test |
| `versionCode` | 71 → 72 (FINAL PASS 1) |

Everything else in the project is byte-identical to the Pass-15 baseline
(verified: `assets/index.html` and all untouched sources match the
delivered ZIP's checksums).

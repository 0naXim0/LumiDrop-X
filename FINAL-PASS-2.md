# LumiDrop X — FINAL PASS 2: Transfer Continuity, Connection Isolation, Filesystem Actions, Selection Reliability & Modal UI Refinement

> **Version:** versionCode 73 (FINAL PASS 2) · Baseline: FINAL PASS 1
> (versionCode 72), delivered as `LumiDropX-FINAL-PASS-1-source.zip`.
> Method, per the pass's Non-negotiable engineering rules: the complete
> Final Pass 1 source was extracted and every reported issue was traced to
> its actual cause in that source **before** any change; each fix below
> names its root cause as found in the trace, reuses the existing systems,
> and is pinned by an executed JVM test wherever the JVM can reach. The
> working transfer architecture is reused, never replaced.

---

## 0. Executive summary of the eight root causes

| # | Reported symptom | Root cause (traced, not guessed) | Fix |
|---|---|---|---|
| 1 | Interrupted native-to-native ZIP: receiver instantly Failed, sender kept "Sending", reconnect re-sent from 0 onto a SECOND incoming card beside the lingering Failed one, and the sender briefly flashed Failed+Resend after the new transfer completed | Three separate defects: (a) the receiver resolved the severed streamed-ZIP row as a **plain short-lived Failed** (GOAT-PASS 15 §3.5's honesty fix), so the re-POST's claim chain (`findInterruptedUploadId` requires FAILED+interrupted) could not adopt it and minted a fresh row — the duplicate; (b) the sender's row latched the byte-resume reconnecting presentation whose chip reads "Auto-Resume on Reconnection" over progress that no longer means anything (a streamed archive always restarts from 0); (c) the ZipBatchNode's skip ledger is shared across **generations** — an entry skipped during a severed attempt survived into the *next* attempt's SUCCESS check and flipped a genuinely complete re-stream into a plain Failed-with-Resend the moment the receiver answered 200 | (a) `TransferManager.failForZipAutoContinue` — the kept, prune-immune, bounded "Auto-Continue on Reconnection" card the claim chain re-opens **in place** (one card, many cycles); (b) `markZipAutoContinue` — the sender's latched presentation with progress deliberately zeroed; (c) the skip ledger is drained at each generation start, so an attempt's verdict is its own. Pinned by 8 JVM tests (`FinalPass2ZipAutoContinueTest`) |
| 2 | Rare "accepted but nothing starts" right after app start / first connect | A provable lost-accept window in `apiNativePushRequest`: the placeholder's `preDecisions` snapshot was taken **before** the new manifest record replaced it, so an accept landing between snapshot and replacement wrote its decision into a dead object — the incoming row re-appeared undecided, the receiver never registered its PENDING rows, and the sender sat "Waiting" until its 2-minute decision deadline | The snapshot + install now run inside ONE critical section on the same monitor all four placeholder-accept/decline paths resolve-and-write under; an accept can no longer fall between them. The post-manifest decision path stays outside the monitor (post-manifest records are never replaced) and keeps its no-op-on-repeat idempotency via an explicit `transitioned` flag |
| 3 | Rename / Create New Folder modals tiny, squeezed, button labels cropped with "…" | The shared `showLdxDialog` card sized itself `WRAP_CONTENT`, so its width resolved from the narrowest intrinsic child (the header title) while the input and the 50/50-weight action row squeezed into the remainder; the action buttons were `maxLines = 1` + `ellipsize END` with 6dp side padding | The card now carries an explicit professional width (every available pixel up to a 380dp cap); action labels wrap to two centered lines instead of ellipsizing; the shared input field gained a 48dp minimum height and 15sp text. Fixed at the shared source — the Files sheet's Rename, the Music sheet's Rename (same shared component) and Create New Folder all receive it, as does every other LdxDialog in the app |
| 4 | Copying an empty folder says "Copied 1 item" but pastes nothing; Cut could even delete the source and create no destination | `FileOps.plan` walked **files only** — a destination directory was only ever created as the parent of a landing FILE. An empty folder (or a folder whose every child is empty / excluded / hidden) pasted as nothing while `ok++` still counted it, and the cross-volume move's cleanup deleted the source with no destination created | The plan now carries directories (`Plan(files, dirs)`); both `copyBatch` and `moveBatch`'s streaming phase create the pick's landed root plus every planned subdirectory **before** any file streams. Pinned by 9 JVM tests running the real engine (`FinalPass2EmptyFolderOpsTest`) |
| 5 | The Files sheet's floating paste bar covers the last item | The paste/op bars are bottom-pinned FrameLayout overlays on the list's container while the list's bottom padding was a fixed 10dp — whatever the bar covered was unreachable | `updateListBottomPad()`: the list's bottom padding grows to the bar's measured height (+ margins) whenever one is visible (with a pre-layout fallback and a post-layout re-run) and returns to rest when neither is; `clipToPadding` was already false, so the content now scrolls fully clear of the bar |
| 6 | Selected items' checkmark indicator is replaced by a thumbnail that finishes loading after the selection | The thumbnail job was only cancelled in the **unselected** branch of the row bind; a row selected while its job was in flight kept the stale job running, and its completion guard (`icon.tag == thumbTag`) still matched — the late bitmap overwrote the check/dash chip | The bind cancels the holder's in-flight job and clears the tag **before** either state branch: the selection indicator is now authoritative over any late load, in both directions. No observer, no polling |
| 7 | Native peer pops the browser "Connection request" modal after minimize/reopen despite the Pass-13 fix | Pass-13 exempted `/api/native/*` — but the native surface is **two** route families: NativeDiscovery's expiry probes, NativePushClient's send-liveness probes and the receiver's upload-liveness probes all hit `/api/ping`, which the Connection Confirmation gate still treated as unknown browser traffic and registered a ConnRequest for (the fresh probe is typically the first request to land after a service restart — hence "after reopening the app") | `/api/ping` is now classified native-only in the confirmation gate (`isNativeProbeApi`), the same request-level classification Pass-13 established. The web client never calls it (its liveness answer is `/api/alive`, which stays fully gated), so no browser security is weakened; the password gate is unchanged (a 401 pong still proves liveness to the prober) |
| 8 | Hold & Slide: sliding immediately after the initial hold-selection selects nothing — "the system appears to require an additional delay or a later movement event" | Two defects in the shared `attachDragSelect`: (a) the arm-cancel test was **omnidirectional** while a vertical RecyclerView only claims a drag on **vertical** slop — an early predominantly-horizontal move toward the next cell (exactly the reported gesture) cancelled the arm, then the platform's own long-press (commonly **400 ms**, not the assumed 500) fired on the un-intercepted child and selected the initial row with **no drag armed behind it**; (b) the hard-coded 440 ms arm loses that race on every 400 ms device | (a) The arm now cancels on **vertical-axis** slop only — every ordinary scroll is exactly as safe as before (a scroll always crosses vertical slop), while a horizontal-reaching hold keeps its arm; (b) the arm delay is derived from `ViewConfiguration.getLongPressTimeout() − 60 ms` (clamped to [200, 440]) so the gesture always preempts the row's own long-press — "exactly one owner" as GOAT-PASS 11 §12 intended. The arm also resolves its initiating row from the **live finger position** through the gap-tolerant, exclusion-aware resolver, and applies it immediately (the first movement is never lost). One shared fix covers Files, Media, Music and Apps |

Plus the requested extra: **Auto Send / Auto Accept renamed Quick Send /
Quick Accept** across every user-visible surface — the header toggles'
content descriptions, the on/off toasts, the "Quick-sending to N devices…"
toast, the Settings rows/chips/descriptions, the browser client's
"Quick-accept from host" toggle label, and the ZIP setting's description
wording. Internal identifiers (`isAutoSendEnabled`, `auto_accept_uploads`
preference key, historical pass-name comments) are deliberately unchanged —
renaming persisted keys would silently orphan users' saved settings.

---

## 1. ZIP streaming — seamless auto-continue on reconnection (§1)

### 1.1 The state flow, end to end

**Attempt 1, interrupted.** The receiver's `writeRawStream` errors out when
the connection dies; the `!ok` tail of `apiNativePushFile` reaches the
`declaredLen < 0` branch. Where Final Pass 1 resolved a plain Failed (honest
about the archive, but wrong about the *operation* — the sender's auto-resume
loop was still live and provably going to re-POST), the row now resolves
through `TransferManager.failForZipAutoContinue(tid)`:

* `FAILED` + `interrupted` — the exact pair the re-POST's claim chain keys
  on, so **the same logical card is the only card**;
* **not** registered in `resumableRowIds` — the partial is deleted (an
  unknown-length archive has no byte-resume identity), so the card is immune
  to `pruneResumableRows` (which reaps kept rows whose backing partial
  vanished) and never implies a "Resuming N%" checkpoint;
* progress, `resumedFromBytes` and speed zeroed — the waiting card shows no
  dead attempt's bytes as live progress;
* bounded: scheduled auto-removal at `AUTO_CONTINUE_ZIP_LINGER_MS`
  (11 min = the sender's 10-minute window + the same margin the sender's own
  stall cap adds), so a sender that never comes back cannot leave the card
  waiting forever — the state is never indefinite (§1.3).

On the **sender**, the retry loop's `RETRYABLE` arm now calls
`markZipAutoContinue(tid)` for `isZipStream` rows: the row stays `ACTIVE`
with the `reconnecting` latch (every lifecycle guard keeps working) while its
counters are zeroed. The UI keys the distinct wording on the existing
`isZipStream` marker — the chip reads **"Auto-Continue on Reconnection"**
(never "Auto-Resume…", which would promise byte-resume) on both the live
latched state and the kept receiver card, and the caption line under the bar
reads **"Next attempt restarts from 0 MB"** on both sides. No percentage is
appended to the kept ZIP card (its progress is deliberately 0).

**Reconnection, attempt N.** The sender's probe (`/api/native/push/file-status`)
answers `existingBytes: 0` for an unknown-size entry whose destination does
not exist (GOAT-PASS 14 Phase 1 §1.8, unchanged) → the loop re-POSTs from
offset 0. On the receiver, the claim chain runs:
`findPendingUploadId` (consumed) → `findPausedActiveUploadId` (null) →
`retireStaleActiveUploadForRequest` (returns false for a non-ACTIVE row; when
the old leg is *still* nominally ACTIVE it now retires a streamed ZIP into
the same auto-continue state instead of the byte-resume keep) →
`findInterruptedUploadId` **finds the kept row** → `reopenInterrupted-
UploadByExactId` re-opens it under the **original id** (and, for `isZipStream`
rows, zeroes both counters so the fresh attempt's first progress tick owns
the bar from 0 — never "Resuming N%") → the fresh-start block deletes the
(useless) staging file and writes the new meta → the archive streams.
**One card, in place; a second, third, further interruption cycles resolve to
the same card again** — `reopenForResume` preserves the row's
`uploadRequestId`, so `findInterruptedUploadId` keeps matching (pinned by
`a second interruption cycle reuses the same card again`).

**Completion.** `complete(tid)` on both sides once the receiver's decoded-EOF
→ `pipe.finish` → rename → EOCD gate → 200 chain finishes (Final Pass 1's
prompt-tail contract, unchanged). The transient Failed-with-Resend flash is
gone at its source: the skip ledger is drained at each generation start in
`streamFile`, so only entries skipped by *this* attempt's writer can fail
*this* attempt's SUCCESS check (an archive whose own stream genuinely skipped
a source still resolves honestly Failed — the §1.5 honesty, now correctly
scoped).

### 1.2 Interruption vs genuine failure (§1.3)

`failForZipAutoContinue` is reached **only** from the two
"attempt severed, sender's own auto-retry provably still scheduled" sites:
the `!ok` interrupted-stream tail and the stale-leg retirement. Every other
terminal path is untouched and still honest: EOCD-gate refusal and local
source failures resolve plain **Failed**; the sender's retry-window
exhaustion and SESSION_GONE keep Final Pass 1's plain **Failed with Resend**
for streamed ZIPs; explicit cancels keep the CANCELLED resolution (the
auto-continue state refuses to overwrite a deliberate cancel — pinned by
test); declines keep DECLINED. The kept card sweeps itself out after the
bounded window, and the row is a member of `autoResumeReceiverRows()` the
whole time, so Cancel All / selection can end it early exactly like the
ordinary recovery cards.

### 1.3 Identity, stale callbacks and duplicate protection (§1.4)

One logical ZIP operation = one sender pre-registered SEND row (the
single manifest entry, `isZipStream` marked at `startPendingSend`) + one
receiver mirror row (pre-registered at accept, same marker) — the entire
re-POST cycle rides those two ids through the existing
claim/reopen/`resumeGenOf` generation machinery, which already prevents an
old leg's `!ok` tail from failing a row a newer leg owns (`legResumeGen`
check, unchanged and re-verified). Reconnection cannot dispatch a second
send leg (no reconnect-driven re-dispatch exists — the single `sendOneFile`
retry loop owns every attempt), duplicate re-POSTs on a freshly-streaming
leg are refused by `retireStaleActiveUploadForRequest`'s 2.5 s freshness
window, and the recipient identity is the session's frozen peer — a
reconnection can never reroute the archive (both sides' claim chains key on
`"$pushId::$relPath"`, not on filenames).

---

## 2. Rare incoming-transfer initialization failure (§2)

The complete accept chain (sheet tap → `acceptNativePushItem` →
`registerPendingTransfersFor` → the sender's progressive dispatch) was
re-traced. The sender's dispatch is poll-driven and deadline-bounded
(2-minute `STATUS_TIMEOUT_MS` → honest Failed); the receiver's accept is
synchronous on the server object; the session coroutine is armored end-to-end
(Pass-22 #6, re-verified). The one provable hole where an accept can be
**silently lost** was the placeholder→manifest replacement race described in
§0/2 above — exactly the "devices have just connected, app just started"
window where the manifest lands hot on the announce's heels and the user
taps a "Preparing…" row. The fix closes the race at the source with one
monitor shared by the manifest replacement and all four placeholder decision
paths; the heavier post-manifest work (dir pre-creation, row registration)
still runs outside the monitor, so acceptance latency and throughput are
unchanged. The pre-registered-row/claim-chain/`expireStalePending` backstops
(5-minute honest FAILED for a genuinely dead session) were re-traced and
kept as-is — they bound every other stuck-Waiting shape the trace found.

---

## 3. Rename and Create New Folder modals (§3)

Fixed at the **shared source** (`showLdxDialog` + `Widgets.inputField`), so
the Files sheet's Rename, the Music sheet's Rename (the same shared
implementation, verified), Create New Folder, and every other small modal in
the app receive the same correction:

* **Card width**: explicit — `min(screen − 2×28dp margins, 380dp)`. On the
  narrowest phones the card is ~304dp wide; the input breathes and the two
  action buttons split ~150dp each. On tablets/landscape the cap keeps it a
  focused dialog. Height remains content-driven.
* **Button labels never crop**: two centered lines are the intentional
  responsive floor for a genuinely narrow screen (labels **wrap**, they are
  never ellipsized); horizontal padding raised 6→10dp for comfortable tap
  targets; text size unchanged (14sp). With the new width both labels fit on
  one line on every normal screen.
* **Input comfort**: 48dp minimum height, 15sp text.
* Everything else — header, icon badge, dim, entrance animation, keyboard
  handling (`SOFT_INPUT_ADJUST_RESIZE` + auto-focus, which already covered
  these dialogs), validation, prefill/selection, pin-follows-rename,
  folder-count refresh, reveal-scroll to the new folder — is byte-identical.

No second Rename implementation was introduced (verified: both sheets call
`showLdxDialog`; the Music sheet's dialog already shared it).

---

## 4. Empty-folder Copy / Cut / Paste (§4)

`FileOps` is the one engine behind both the Files sheet and the Music
sheet's clipboard operations. The plan walker now collects directories
(`Plan(files, dirs)`) alongside files — every surviving directory of every
pick, root-relative, hidden/exclusion rules identical to the files' — and
both operations materialize the destination tree (landed root + every
planned subdirectory) **before** any file streams. Consequences, all pinned
by the 9 executed tests: one empty folder lands as the empty folder; several
empty folders land together; empty *nested* folders survive inside a mixed
copy; a folder whose every child is excluded still lands as the folder;
hidden children are skipped while the visible hierarchy lands; pasting into
the same directory stays collision-suffixed ("Empty (1)", "Empty (2)") and
never overwrites; a same-volume cut of an empty folder is the atomic rename
it always was; the streaming move phase creates the destination **before**
the source cleanup can delete anything — the reported data-loss shape (source
gone, nothing created) is unreachable; a cancelled run never counts an
unreached pick as succeeded and leaves no partial behind.

---

## 5. Files sheet — the paste interface no longer covers the last item (§5)

`updateListBottomPad()` is the single writer of the list's bottom padding:
resting 10dp; bar-height + margins whenever the paste bar **or** the
copy/cut progress bar is visible (measured live, with an 84dp-equivalent
fallback for the frame a bar just flipped visible and a `post {}` re-run so
the real measurement lands); back to rest when neither is. With
`clipToPadding = false` (already the configuration), the final item scrolls
fully clear of the bar and stays clickable; no space is reserved when
nothing is showing; the sheet's height and scrolling behavior are otherwise
unchanged. The visibility owners (`paintChrome`, `startOpUi`, `endOpUi`, the
paste bar's Cancel) all route through it.

---

## 6. Thumbnail selection indicators stay synchronized (§6)

Root cause and fix are one line each in `onBindViewHolder`: the in-flight
thumbnail job is now cancelled and its tag cleared **before** either state
branch runs, so (a) a row selected while its thumbnail was loading can never
be overwritten by the late bitmap, (b) a row deselected re-arms a fresh load
exactly as before, and (c) recycled rows keep their existing cancel
behavior. The selection state (`folderState`/`fileState`, derived from the
authoritative pick maps on every bind) remains the single source of truth —
no duplicated per-thumbnail state, no observer, no polling. Items still
loading, failed loads, cache-restored loads and rerendered rows all render
the chip, because the chip is bound from the live selection state on every
bind.

---

## 7. Native-to-native connections never enter the browser confirmation workflow (§7)

The native HTTP surface is exactly two route families (verified by full
grep of `NativePushClient`/`NativeDiscovery`): `/api/native/*` (exempted by
Pass-13) and `/api/ping` (the liveness probe route — **not** exempted until
this pass). Every probe class — NativeDiscovery's expiry refreshes,
`isSendPeerAlive`'s stall probes, `isNativePushSenderAlive`'s receiver-side
twin — hits `/api/ping` on the peer's server; with "Ask before accepting new
connections" on, the first probe after a server (re)start registered a
ConnRequest and the host saw the browser modal for a device that is not a
browser client at all. The fix is the same request-level classification
Pass-13 established: `isNativeProbeApi` exempts `/api/ping` from the
**Connection Confirmation gate only**. The password gate is untouched (a
401 pong still proves liveness — that is all a prober reads), browser
security is untouched (the web client's liveness answer is `/api/alive`,
which stays fully gated; page loads and every other endpoint still register
/ respect the decision), and no other lifecycle pathway exists for a native
peer to reach the gate. Browser flows keep both gates exactly as before.

---

## 8. Hold & Slide recognizes movement immediately after the initial selection (§8)

One shared fix in `attachDragSelect` (the single gesture handler behind
Files, Media, Music and Apps — verified all four call sites):

1. **Axis-aware arm cancel.** The pending arm is cancelled only when the
   movement exceeds touch slop on the **scrollable (vertical) axis** — the
   same condition under which the RecyclerView itself claims a scroll. The
   old omnidirectional test killed the arm on early *horizontal* movement
   (the exact "slide toward the next item" gesture in a grid) while the
   platform's own long-press still fired on the un-intercepted child: the
   initial row selected with no drag armed, and nothing crossed afterwards
   was selected. Ordinary scrolling is exactly as protected as before — a
   scroll always crosses vertical slop.
2. **Platform-derived arm delay.** The arm fires at
   `ViewConfiguration.getLongPressTimeout() − 60 ms` (clamped to [200, 440]
   ms; Range mode uses the exact platform timeout). On the many devices
   whose real timeout is 400 ms, the hard-coded 440 ms armed **after** the
   row's own long-press had already fired — two owners racing on the gesture
   this handler exists to own. The arm's `child.cancelLongPress()` now
   genuinely preempts it, restoring GOAT-PASS 11 §12's "exactly one owner".
3. **The first movement is never lost.** The arm resolves its initiating
   row from the **live finger position** via the gap-tolerant,
   exclusion-aware `positionUnder` (a press that began in a gap between rows
   now arms on the nearest eligible row instead of silently nothing), and
   applies it — plus the row currently under the finger — at arm time.

Normal taps (a tap's UP cancels the arm), ordinary long-presses, pointer
identity (single-pointer stream; a second finger ends the gesture cleanly),
cancellation, dedupe (`lastPosition`) and the edge auto-scroll are unchanged.

---

## 9. Quick Send / Quick Accept rename (the pass's added instruction)

Every user-visible "Auto Send" / "Auto Accept" label is now "Quick Send" /
"Quick Accept": the Nearby Devices header toggles' content descriptions, the
toggle toasts, the "Quick-sending to N devices…" toast, the Settings'
Transfer Engine rows (titles, chips, descriptions — including the ZIP
setting's "a Send tap or a Quick-Send" wording), the browser client's
"Quick-accept from host" toggle, and the ZIP-mode icon's documentation.
Verified by a source-wide string-literal sweep (zero remaining user-visible
occurrences). Persisted preference keys, internal identifiers and
historical pass-name comments are deliberately unchanged so existing
installations keep their settings; the ZIP-mode first-time explanation and
the Add/Send quick-share verb logic are untouched.

---

## 10. Cross-system regression audit (§9)

* **Transfer/connection systems** — every §1 change rides the existing
  claim/reopen/generation machinery (no new identity model, no wire changes);
  ordinary file/folder transfers never enter the ZIP branch (`declaredLen <
  0` is the sole key); Final Pass 1's keep-alive, read-window, exclusion and
  prompt-tail contracts are untouched; the 167-test Final Pass 1 regression
  suite passes in full (§11).
* **Filesystem operations** — `FileOps`' collision, containment, cancellation
  and durability semantics are unchanged (the plan's new `dirs` list only
  *adds* directory creation before files); the 9 new tests pin the empty and
  nested cases alongside the mixed ones.
* **Selection and gestures** — §6's fix is scoped to the row bind (both
  state directions verified in code); §8's is scoped to the shared gesture
  and preserves the Range mode and excluded-region paths byte-for-byte.
* **Connection request security** — the browser gate's logic for every
  non-probe route is byte-identical; only the native probe route was
  classified out (the web client provably never calls it).

---

## 11. Verification — what was actually executed

**Environment:** Linux container, JDK 17.0.21 (Temurin, for the build) +
JDK 21 runtime, Android SDK 34 (platform + build-tools 34.0.0, licenses
accepted), Gradle 8.5 / AGP 8.2.2 / Kotlin 1.9.22, JUnit 4.13.2. **No
physical device, emulator, or browser is available.**

### 11.1 Executed — automated tests (real production code, real engine)

* **`testReleaseUnitTest`: 184 tests, 0 failures, 0 errors** (JUnit XML
  aggregate over all 23 classes) — the complete Final Pass 1 regression set
  (167 tests, including the GOAT-PASS 3–15 engine pins, the chunked-request
  and ZIP-integrity suites, and the Final Pass 1 round-trip) **plus this
  pass's 17 new tests**:
  * `FinalPass2ZipAutoContinueTest` (8) — the kept auto-continue card's
    state; the claim chain finding and re-opening the SAME row with zeroed
    progress; a second interruption cycle reusing the same card; prune
    immunity; Cancel-All reachability; the ordinary kept row's unchanged
    byte-checkpoint contract (control); a deliberate cancel never entering
    the state; the sender's latched presentation with zeroed progress.
  * `FinalPass2EmptyFolderOpsTest` (9) — the real FileOps engine over real
    temp directories: one/multiple empty folders, nested empty dirs, mixed
    content, all-excluded children, hidden children, same-directory
    collision suffixing, same-volume cut, and a mid-stream cancellation
    that never counts an unreached pick.

### 11.2 Executed — real Gradle build

* **`assembleRelease` (R8-minified, signed with the bundled
  `lumidropx-release.jks`): BUILD SUCCESSFUL** — `app-release.apk`
  (3.69 MB), `versionCode='73' versionName='1.0.0' sdkVersion='21'
  targetSdk='34'`, signer SHA-256 `1c179d0e…f123cf` — the same key as every
  prior pass (installs as an update). The shipped dex contains this pass's
  code (`failForZipAutoContinue`, the "Auto-Continue on Reconnection"
  wording, "Quick Accept"), and the embedded `assets/index.html` is
  md5-identical to the source tree. `lintVitalRelease` ran as part of the
  build.
* Build environment notes, disclosed: Maven Central rate-limited this
  session's dependency downloads (HTTP 429); resolution completed through
  bounded retries with no artifact substitutions.

### 11.3 Performed — source-level tracing (all root causes above)

Every §0 root cause was located in the Final Pass 1 source before its fix
was written: the ZIP claim chain (`apiNativePushFile` →
`findInterruptedUploadId` → `reopenInterruptedUploadByExactId`), the
skip-ledger sharing on `ZipBatchNode`, the preDecisions snapshot ordering,
the LdxDialog width resolution, the file-only `FileOps.plan`, the overlay
paste bar vs fixed list padding, the uncancelled in-flight thumbnail job,
the `/api/ping` gate path, and the gesture's slop-axis/timeout mismatches.

### 11.4 NOT performed (no devices/emulator/browser here) — honest list

1. **§11's ZIP reconnection scenario on hardware** — the state machine,
   claim-chain reuse, zeroed progress, prune immunity and bounded life are
   engine-pinned (§11.1), but the end-to-end two-device interruption,
   reconnect and archive-integrity cycle needs hardware.
2. **§2's accepted-but-never-started scenario on hardware** — the lost-accept
   window is closed at the source and the flow is traced; the race's
   end-to-end non-reproducibility (it was rare by report) can only be
   observed on devices.
3. **§3–§6, §8's on-screen interactions** — dialog sizing, button wrapping,
   paste-bar clearance, thumbnail timing and the gesture feel are built per
   the established architecture and every reachable code path was traced,
   but none were rendered or touched here.
4. **§7's native-vs-browser matrix on hardware** — the classification is
   code-verified (full URL-surface grep; the web client never calls
   `/api/ping`), but the minimize/reopen/reconnect cycle and a real browser
   client's confirmation need a device.
5. **iPad 4 / iOS 10.3.3** — no legacy Safari available; the web client's
   changes this pass are two label strings only (no API, CSS or JS-behavior
   change), so its established compatibility posture is unaffected.

---

## 12. Remaining known limitations (honest accounting)

1. **Device-level verification is outstanding** for every on-device
   scenario in §11.4 — the same standing limitation as every prior pass in
   this environment.
2. **A streamed ZIP still restarts from byte 0** on every attempt — the
   deliberate, documented contract (unknown-length archives have no
   per-offset identity), now stated in the row's own caption instead of only
   the first-time modal.
3. **The receiver's kept auto-continue card sweeps after 11 minutes**
   without a re-POST even in the (unlikely) case the sender's window is
   still live — it matches the sender's own 11-minute stall cap, and the
   manual Resend path remains the recovery after it.
4. **§2's fix closes the one provable lost-accept race**; a WiFi-level
   accept-packet loss is indistinguishable from an undecided host and
   honestly resolves via the sender's 2-minute deadline, as designed.
5. **Two-line action buttons** are the responsive floor for screens so
   narrow that two labels cannot share a row; no known device in the
   Android 5–16 support range requires it, but it is the honest fallback if
   one ever does.

---

## 13. Files changed (complete list)

| File | Change |
|---|---|
| `transfer/TransferManager.kt` | `AUTO_CONTINUE_ZIP_LINGER_MS`; `failForZipAutoContinue` (receiver's kept card); `markZipAutoContinue` (sender's latched presentation); `retireStaleActiveUploadForRequest` routes streamed ZIPs into the auto-continue state; `reopenInterruptedUploadByExactId` zeroes a re-opened ZIP's progress |
| `p2p/NativePushClient.kt` | the retry loop latches the ZIP-specific presentation; the skip ledger is drained at each archive-generation start (per-attempt verdicts) |
| `server/LumiDropServer.kt` | the interrupted-streamed-ZIP branch resolves the kept auto-continue card; the placeholder preDecisions snapshot + manifest install are atomic with all four decision paths (the lost-accept race); `/api/ping` classified native-only in the Connection Confirmation gate |
| `TransfersFragment.kt` | "Auto-Continue on Reconnection" chip (both live and kept ZIP states, no % on the kept card) + the "Next attempt restarts from 0 MB" caption |
| `ui/FileOps.kt` | the plan carries directories; both copy and streaming-move create the destination tree before any file streams |
| `ui/FilesPickerSheet.kt` | `updateListBottomPad` (paste/op bars reserve list space); the selection indicator is authoritative over in-flight thumbnails (cancel + tag-clear before either state branch) |
| `ui/MediaPickerSheet.kt` (`attachDragSelect`, shared by Files/Media/Music/Apps) | platform-derived arm delay (min 60 ms head start, clamped); vertical-axis-only arm cancel; arm under the live finger via the gap-tolerant resolver; immediate apply at arm |
| `ui/LdxDialog.kt`, `ui/Widgets.kt` | professional modal width (explicit, capped 380dp); two-line never-ellipsized action labels; 48dp/15sp input field |
| `SendFragment.kt` | Quick Send / Quick Accept toasts; "Quick-sending…" wording |
| `settings/SettingsFragment.kt` | Quick-send / Quick-accept settings rows, chips and descriptions |
| `res/layout/fragment_send.xml`, `res/drawable/ic_zip_mode.xml` | Quick Send / Quick Accept content descriptions / docs |
| `assets/index.html` | the browser client's "Quick-accept from host" toggle label (two strings; no behavioral change) |
| `app/src/test/…/FinalPass2ZipAutoContinueTest.kt` | new — 8 tests |
| `app/src/test/…/FinalPass2EmptyFolderOpsTest.kt` | new — 9 tests |
| `versionCode` | 72 → 73 (FINAL PASS 2) |

Everything else in the project is byte-identical to the Final Pass 1
baseline.

**Deliverables:** this source ZIP (complete project), `LumiDropX-FINAL-PASS-2-release.apk`
(versionCode 73, successfully compiled and signed as described in §11.2), and this report.

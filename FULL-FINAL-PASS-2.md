# LumiDrop X — FULL FINAL PASS 2

## Transfer continuity, first-transfer reliability, selection-state recovery, filesystem actions & UI refinement

Base: the FULL FINAL PASS 1 source (its complete selection-performance work and the
#13 badge counters are preserved byte-for-byte unless named below).

This pass is a reliability-focused engineering pass: every change below fixes a
traced root cause or restores explicitly requested behavior. No unrelated system
was redesigned.

---

## 1. THE FILES SELECTION VISUAL REGRESSION (CRITICAL) — root cause & fix

### The traced root cause (source-level, confirmed)

FULL FINAL PASS 1 introduced the selection-only payload rebind
(`SelStatePayload` → `bindSelectionChrome`) and the Hold & Slide drag fast path
(`dragApplyRow`). Two facts combined into the reported "reversed" visuals:

1. The payload rebind painted from the **`shown` list snapshot**
   (`bindSelectionChrome(holder, shown[position])`).
2. The drag fast path deliberately does NOT touch `shown` during the gesture —
   it updates a separate `dragRowsWork` list and dispatches
   `notifyItemChanged(pos, SelStatePayload)` per crossed row.

So every Hold & Slide rebind painted the row's **pre-gesture state**. The
thumbnail "flicker" was the stale NONE-state paint relaunching a thumbnail job
on a row that was actually selected. At gesture end, `dragGestureEnd` assigned
`shown = work` and ran the full DiffUtil — which compares work-vs-live and
finds **no difference for crossed rows** (work already holds the post-gesture
states), so it dispatches nothing: the stale paint is never corrected. Each
Hold & Slide interaction therefore left every touched row's visual exactly one
gesture behind its logical state — select showed nothing, deselect showed
selected. (The Music sheet never had this bug because its Rows are mutated in
place; the Transfers tabs read their LIVE selection sets; the Media/App grids
read live state in their payload rebinds. Files was the only stale-read.)

### The fix (single source of truth, not a visual workaround)

`bindSelectionChrome` now derives the row's state from the **live selection
model** — the same `fileState`/`folderState` reads the model's own verdicts
use — on BOTH bind paths (payload and full):

```kotlin
val liveState = if (rIn.entry.isDir) folderState(rIn.entry.file) else fileState(rIn.entry.file)
val r = if (liveState == rIn.state) rIn else Row(rIn.entry, liveState, rIn.pinned)
```

The Row snapshots remain exactly what they were — **change-detection** inputs
deciding which `notifyItemChanged` calls to dispatch — but they are never the
paint authority. A rebind dispatched by any path, running at any time relative
to any snapshot update, now paints the truth by construction. The drag fast
path, the gesture-end reconcile and the surgical repaint are all unchanged —
they were never the defect; the paint's data source was.

**Files:** `ui/FilesPickerSheet.kt` (§2).

---

## 2. THE FIRST-TRANSFER INTERMITTENT FAILURE (CRITICAL) — investigation & fixes

### What was traced (source-level, the complete lifecycle)

Announce → manifest POST (idempotent, 3 retries) → per-item decision polling
(every 700 ms) → progressive dispatch → per-file probe (resume identity +
refusals) → streamed POST → auto-resume loop (backoff, re-probe, in-place row
re-open) — plus the receiver's placeholder-accept atomicity (FINAL PASS 2 §2
era), repeat-manifest guard, TTL/dead-sender sweeps, claim-chain fallbacks,
receiving-slot FIFO gate with zombie preemption, and the sender's
orphan-row backstop. **Every accept→register→claim path already self-heals**;
the remaining holes found were on the sender's decision-wait side:

### Confirmed hole A — the decision wait was a wall clock, not liveness

`STATUS_TIMEOUT_MS` (2 minutes, from manifest time) expired the wait even
while the receiver kept answering "pending". A receiver whose user simply had
not answered yet — most reachable exactly on the FIRST transfer of a session,
when the receiving user is not yet engaged (just connected, restored from
Recents, notification unseen) — had its late accept land on a sender that had
already given up: sender rows FAILED, the receiver's accepted-but-unstreamed
rows lingered for the 10-minute dead-sender sweep, and re-sending "worked
perfectly". This matches all three reported situations (immediate send after
connect; send after Recents restore; first transfer of a session) without
assuming any of them.

**Fix:** the wait now closes only on sustained UNREACHABILITY (no successful
poll for 2 minutes — byte-identical for a gone receiver) or a generous
absolute cap (30 minutes). A reachable-but-undecided receiver keeps the wait
alive; no delay was added anywhere (a healthy receiver still answers the
first poll in ~700 ms).

### Confirmed hole B — an abandoned decision-wait left the receiver hanging

When the wait did close, the sender resolved its own rows but never told the
receiver: its push record and any accepted-but-unstreamed pre-registered rows
lingered up to 10 minutes (the dead-sender sweep), reading as the reported
"accepted, nothing happens".

**Fix:** both dispatch flows (progressive and legacy) now withdraw the push on
the receiver (`/api/native/push/cancel?id=` — the same endpoint the
empty-manifest path uses) the moment the decision wait closes. The receiver
resolves the record and every still-pending pre-registered row immediately;
both sides agree on "this send is over". No new transfer can be minted from a
withdrawn push (the file POSTs and probes resolve against the record).

### Confirmed hole C — the manifest retry envelope vs a mid-restart receiver

A receiver restored from Recents (process recreated, service + NanoHTTPD bind
still coming up) can refuse connections for a window exceeding the old
3-attempt / 1.5 s envelope — the sender failed the whole batch exactly when
the user had "reopened the app and sent immediately", while a re-send moments
later worked.

**Fix:** the envelope is 4 attempts with progressive settles (1.5 s → 3 s →
5 s), all AFTER a failed attempt — a healthy receiver still answers the very
first POST with zero added latency; a restarting one gets a genuine window to
come up before the honest failure stands.

**Files:** `p2p/NativePushClient.kt` (§4; new `STATUS_TIMEOUT_ABSOLUTE_MS`,
`decisionWaitExpired`/`notePollOk`, `withdrawPush(session, reqId)`, retry
envelope).

---

## 3. THE RECEIVED BADGE — original auto-landing behavior restored

FULL FINAL PASS 1's "genuine visit only" hold (an automatic landing kept the
Received tab's badge alive until a real visit) is **removed entirely**, per the
explicit correction:

- Entering the Received tab — automatically (the screen's entry default) or by
  a pill tap — consumes the unseen-Received badge, exactly as before that
  change.
- Entering History consumes its own counters the same way.
- `updateTabBadges`' periodic clear no longer consults any hold (none exists).
- The hold fields, their settle-on-departure logic, and the now-unused
  `userInitiated` parameter are gone.

The badge **increment** side is untouched (it was already correct): the
Received counter is raised at both engine chokepoints for both native-push and
browser (`/api/upload`) completions, and the History counter (its own
independent count) is raised for completions in BOTH directions at the same
chokepoints. The independent settings toggles ("Received tab new-items badge"
/ "History tab new-items badge", default ON, persisted) gate only visibility.

**Files:** `TransfersFragment.kt` (§3).

---

## 4. HOLD & SLIDE — the swept span is never lost

`applyUnder` resolved only the finger's current point, so two consecutive
ACTION_MOVE events sitting several rows apart (input batching, a frame drop,
or the very first movement right after the arm fires) skipped everything
strictly between them — the reported "first movement is missed / items are
skipped during fast movement".

The shared gesture now walks the straight segment from the previously APPLIED
point to the current one, sampling it finer than half the smallest visible
cell, and applies every distinct row the segment resolves to:

- In a list: exactly the rows crossed.
- In a grid: exactly the cells the diagonal geometrically passes through —
  never the whole bounding rectangle, so no un-crossed cell is ever selected.
- Idempotent SET operations + the existing dedupe make any sampled repeat a
  no-op; the terminal UP's `includeEndpoint` contract is preserved (endpoint
  applied last, unconditionally).
- The interpolation origin is set at ARM time to the finger's live position
  and reset with the gesture — a stale point can never seed the next span.

Because every surface (Files, Media, Music, Apps, the transfer tabs) shares
`attachDragSelect`, one fix covers them all. This does not interfere with the
Files visual fix (§1): the drag fast path's payload rebinds now paint from the
live model. Scroll safety is unchanged (vertical-slop arm cancellation, edge
auto-scroll arming after genuine movement).

**Files:** `ui/MediaPickerSheet.kt` (`attachDragSelect`, §8).

---

## 5. ZIP AUTO-CONTINUE ON RECONNECTION — verified already complete

Traced end-to-end before touching anything; the existing implementation
already satisfies this pass's requirements and was **not** modified:

- **Sender:** a streamed archive whose connection dies latches
  `markZipAutoContinue` — the row STAYS on the same logical card (ACTIVE,
  "Auto-Continue on Reconnection", progress zeroed: a restart-from-0 archive
  must not display dead bytes as live progress).
- **Receiver:** the interrupted leg resolves through `failForZipAutoContinue`
  — same row, FAILED+interrupted marker, zeroed progress, no resumable
  registration, bounded 11-minute life — and the re-POST's claim chain
  (`findInterruptedUploadId` → `reopenInterruptedUploadByExactId`) re-opens
  THAT row in place; never a second card.
- **Restart, never byte-resume:** an unknown-size archive resumes at
  `offset = probed.coerceIn(0, spec.size.coerceAtLeast(0))` = **0 by
  construction**; the receiver deletes the interrupted archive's staging file
  (`deleteStagingFor`) so no partial can ever be appended to. The receiver's
  "already complete" short-circuit requires the genuine destination file plus
  the EOCD integrity gate — a finished archive is never re-streamed, and no
  incomplete one is ever completed.
- **Genuine failures** (retry window exhausted at 10 minutes, session gone,
  EOCD refusal, explicit cancel) resolve plain FAILED/Cancelled with the
  manual Resend action intact.
- Stale-attempt protection: the receiver's per-leg generation snapshot keeps
  a superseded leg from failing/parking/discarding the newer leg's transfer.

No byte-level resume is claimed for streamed ZIPs — the implementation
restarts the same logical transfer from the beginning, and says so on the
card.

---

## 6. NATIVE-TO-NATIVE vs THE CONNECTION REQUEST MODAL — verified at the source

Every HTTP surface a native peer can touch on another device was enumerated
across the whole codebase: `/api/native/push/{announce,request,status,file-
status,file,control,cancel}` and `/api/ping` (discovery expiry probes,
send-liveness probes, upload-liveness probes). **All of it is exempt** from
the browser-only Connection Confirmation gate (`isNativePeerApi` /
`isNativeProbeApi`), so no native-peer request can register a ConnRequest —
the modal cannot originate from native-to-native traffic in this source. The
gate also exempts nothing else: browsers keep both gates (confirmation +
password) exactly as before, and approvals are per-IP, per-server-session by
design. If a modal is observed right after the host's app is reopened, the
requesting client is a browser (or an external player) whose approval was
reset by the host's service restart — the intended browser behavior — not the
native peer.

---

## 7. RENAME & NEW FOLDER MODALS — the comfort pass

Both modals (and every other small modal in the app) share `showLdxDialog` +
`Widgets.inputField`, so the refinement applies to Files Rename, Music Rename,
New Folder and the device-rename dialog at once:

- Card: explicit width up to a **420 dp** cap (was 380), 24 dp backdrop
  gutters (was 28) so small screens gain card width, deeper padding rhythm
  (22/22/22/20), header/body margins 16/18 dp.
- Input: **52 dp** minimum height, 16/14 dp padding, unchanged 15 sp text — a
  roomy native input, never a squeezed sliver.
- Buttons: 12/14 dp padding, a guaranteed **48 dp touch target**, 10 dp
  inter-button gap, two-line wrap floor (labels are never ellipsized).
- Entrance: an input dialog animates **alpha only** — the simultaneous window
  resize (keyboard) + scale transform was the "glitchy" entrance; confirmation
  dialogs keep the full scale+fade.
- Keyboard: unchanged and verified (auto-focus, `SOFT_INPUT_STATE_VISIBLE |
  ADJUST_RESIZE`).
- Rename validation (both sheets): unchanged, plus a new
  **duplicate-extension typo guard** — "photo.jpg.jpg" trims to one trailing
  extension (the success toast shows the final name, so the correction is
  never silent). Extension-aware base-name pre-selection, collision refusal,
  storage-error honesty and immediate listing refresh are all preserved.

**Files:** `ui/LdxDialog.kt`, `ui/Widgets.kt`, `ui/FilesPickerSheet.kt` (§7A),
`ui/MusicPickerSheet.kt` (§7A).

---

## 8. EMPTY-FOLDER COPY / CUT / PASTE — verified already complete

Traced before touching anything; the implementation (an earlier pass's §4)
already treats an empty directory as a real filesystem item and was **not**
modified:

- The plan carries DIRECTORIES (root-relative) alongside files; the pick's
  root and every planned subdirectory are created up-front at the destination,
  so an empty folder — or one whose every child is empty/excluded/hidden —
  lands as the real empty directory it is, nested structure included.
- `copyBatch` counts a directory pick as OK (`files.isNotEmpty() ||
  p.root.isDirectory`); a same-volume cut is one atomic rename (empty
  directories included); the cross-volume fallback creates the destination
  tree, streams files, deletes each source FILE only after its copy landed,
  and removes the emptied source tree bottom-up.
- Destination conflicts land as "name (1)" (never overwrite); the clipboard
  is consumed only on a non-cancelled result; a cancelled copy deletes its
  partial file and a cancelled move never deletes a source whose copy did
  not fully land.

---

## 9. PASTE BAR / OP BAR vs THE LAST ITEM — bottom-anchor compensation

The list's bottom padding already grows to the visible bar's measured height
(+ margins, re-measured once laid out, `clipToPadding = false`), which made
the last row *reachable*. The residual defect: a list already scrolled to its
end kept the last row visually UNDER the freshly-appeared bar until the user
scrolled again. When the pad grows and the list is at (or within one row of)
its bottom, the content is now scrolled up by exactly the delta, so the
bottom row lands clear of the bar immediately. A shrinking pad needs no
compensation (the freed space is just scrollable room). No excess blank
space is reserved when no bar is visible (resting pad stays 10 dp).

**Files:** `ui/FilesPickerSheet.kt` (`updateListBottomPad`, §5).

---

## 10. WHAT WAS *NOT* CHANGED (regression safety)

- The FULL FINAL PASS 1 performance work in full: lenCache, breadcrumb guard,
  payload rebinds, drag fast paths, coalesced header recomputes, incremental
  totals, app-picker index/size caches, transfer-tab row payloads, the
  once-per-gesture reconciliation — all preserved (the §2 fix changes only
  the paint's data source, not the paths or their costs).
- The badge counters, their chokepoints and the independent settings toggles.
- The ZIP streaming mechanism, transfer engine, receiving slots, resume and
  reconnect machinery (this pass only *observed* them).
- The gesture's arm timing, slop handling, edge auto-scroll curve, endpoint
  apply, exclusion regions — untouched; the interpolation is additive.
- The WebUI (no hold-and-slide selection exists browser-side; the gesture is
  a native surface, so no Safari-compat surface was affected).

## 11. VERIFICATION — what was actually done

- **Source-code inspection**: every fix above was traced to its root cause in
  the live source before any edit (the diffs against the FULL FINAL PASS 1
  baseline isolate each change).
- **Full-tree syntax check (kotlinc 1.9.22, baseline-compared)**: all 85
  Kotlin sources compile with **zero syntax-class errors** and **zero new
  error classes** versus the pristine baseline histogram (the no-SDK
  unresolved-reference noise is identical in shape to the baseline's).
- **Real Gradle build (performed)**: `assembleRelease` (JDK 17, Android SDK
  34, the project's release keystore) — **BUILD SUCCESSFUL**; the signed
  release APK (3.7 MB, `apksigner`-verified against the LumiDrop X release
  certificate) is delivered alongside this source. The build compiled the
  exact modified tree (source checksums verified against the pristine
  baseline: only the seven files in §12 differ).
- **NOT performed** (no device or emulator exists in this environment):
  runtime two-device transfer tests, Recents-restore timing tests, Hold &
  Slide feel, thumbnail stability, badge visibility. The first-transfer
  analysis is source-level; the liveness/withdraw/retry fixes remove the
  identified failure modes by construction, but the intermittent itself can
  only be confirmed gone on real hardware, repeated per the task's own
  scenario matrix.

## 12. FILES CHANGED

| File | Changes |
|------|---------|
| `ui/FilesPickerSheet.kt` | §2 live-state paint authority in `bindSelectionChrome`; §5 bottom-anchor scroll compensation; §7A rename typo guard |
| `ui/MediaPickerSheet.kt` | §8 swept-span interpolation in `attachDragSelect.applyUnder` + origin tracking |
| `TransfersFragment.kt` | §3 badge-hold removal (auto-landing consumes the Received badge again) |
| `p2p/NativePushClient.kt` | §4 liveness-aware decision deadline, withdraw-on-abandon, wider manifest retry envelope |
| `ui/LdxDialog.kt` | §7 comfort pass (width/padding/margins/buttons/alpha-only input entrance) |
| `ui/Widgets.kt` | §7 inputField comfort (52 dp / 16-14 padding) |
| `ui/MusicPickerSheet.kt` | §7A rename typo guard (shared pattern) |

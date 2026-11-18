# LumiDrop X — FULL FINAL PASS 3

## ZIP Streaming Selection Compatibility, Incoming Request Reliability, Text Clipboard Workflow, Transfer Queue Recovery & Received/History UI Refinement

> **Version:** versionCode 75 · Baseline: FINAL PASS 2 (versionCode 74),
> delivered as `LumiDropX-FULL-FINAL-PASS-2-source.zip` (the FULL FINAL
> PASS 1/2 work — selection performance, badge counters, first-transfer
> reliability, filesystem actions, ZIP size accuracy, PeerHttp routing —
> is preserved; nothing in this pass replaces it).
> Method: the complete Final Pass 2 source was extracted and every reported
> issue was traced to its actual cause in that source **before** any change.
> Each fix names its root cause as found in the trace, reuses the existing
> systems, and the whole app compiles with the project's own toolchain
> (JDK 17 + Gradle 8.5 + AGP): `assembleRelease` BUILD SUCCESSFUL,
> `testReleaseUnitTest` **190 tests, 0 failures**, signed APK produced and
> verified (apksigner). No emulator/physical-device testing was performed —
> see §Verification for the honest boundary.

---

## 0. Executive summary

| # | Reported item | Root cause (traced, not guessed) | Resolution |
|---|---|---|---|
| 1 | Sent **ZIP-streaming History items can't be Hold & Selected or Range-selected** (individual click works) | `isRangeSelectableIn`'s deletion contract for the Received/History tabs requires `filePath != null`. The sender's streamed-ZIP row is minted from a live-synthesized `ZipBatchNode` whose `localPath` is **null** (the archive never exists on the sender's disk), so the row failed the gate. The gate is consulted ONLY by Range/Hold&Slide entry paths — a plain tap inside selection mode never asks it, which is exactly why individual selection worked while the other two methods skipped the row. (The same defect class GOAT-PASS 13 §7 fixed for text messages.) | The gate now passes `isZipStream` rows too; and the marker is persisted (`HistoryStore.Entry`, JSON key `"z"`) so a restarted app's History tab keeps eligibility, with `renderHistoryList`'s `transferFromEntry` restoring it onto the row |
| 2 | **Rare incoming-request delivery failure**: receiver hears the notification, the Incoming Items sheet never shows the item, sender stuck Waiting; cancel + resend works | `sweepStalePendingNativePushes`' still-pending branch keyed **purely off `createdAt`** (fixed at construction, never refreshed) — a perfectly live sender polling every 700 ms (each poll refreshes `lastActivityAt`, but nothing looked at it on this branch) still had its **undecided** record swept at the 3-minute `NATIVE_PUSH_TTL`. The notification plays at the announce (a different, earlier code path), the sheet reads the swept map, and the sender's decision polls then read `"rejected"` for a record that no longer exists. The user cancels and resends — the fresh push id gets a fresh `createdAt`, lands inside every TTL, and works: **exactly the reported repro** | The still-pending branch now ALSO requires sustained sender silence (`now - lastActivityAt > NATIVE_PUSH_DEAD_SENDER_MS`, 10 min). A reachable-but-undecided push lives as long as its sender keeps answering — matching the sender's own liveness-aware decision wait. A genuinely gone sender (10 min of total silence) is swept exactly as before |
| 3 | Web receiver: **texts need a direct Copy** beside View; Accept All should combine texts into one clipboard payload | View was the only consumer; bulk Accept auto-downloaded `.txt` files | `copyQSText(name)` — copies through the app's one compatibility core (`_copyTextExact`: modern API where secure, `execCommand` fallback everywhere else, legacy iOS included), consumes the item **only after a successful copy**. Accept All joins all texts with `"\n\n"` — the exact separator the native receiver's `acceptAllNativePush`/`precreateNativePushDirs` use — and files continue through the existing pump. `.txt` download now happens only via the viewer's own Download button |
| 4 | Waiting requests **eventually auto-fail** in the sender's Active tab | Two time-alone mechanisms could flip a still-waiting row to FAILED: (a) the receiver-side pending-push sweep above (the sender's rows then resolve as declined/failed), and (b) `expireStalePending`'s generic 5-minute staleness sweep, which measured "peer download activity" — an **undecided** (never-requested) offer has no such activity, so it auto-failed at 5 min while the user stared at the chip | (a) fixed by the liveness key in item 2; (b) the generic sweep now exempts `requestedAtMs == 0` Quick-Share rows — pending acceptance is not a transfer failure. Genuinely dead sessions are still caught by the dead-sender sweep (10 min), the orphaned native-SEND sweep (15 min) and the accepted-but-never-requested sweep (25 s) — all preserved |
| 5 | Failed items are **automatically re-sent** to the browser receiver | Two mechanisms did this: the server's `sweepFailedQuickShare` (un-consumed the failed IP and re-inserted the chip at the queue HEAD) and the client's bounded auto-retry (re-fired the URL up to 3×) | **Both removed.** A failed item stays Failed until the user explicitly resends. The one deliberate re-offer path (`retryQuickShareForIp`) is untouched |
| 6 | Resend semantics: button naming, pill presence, immediacy | Per-row button said "Retry" (it actually resends); pill required 3 failures; pill visibility rode the 2 s render tick | Renamed **Resend** (both QS and native rows); pill appears from the **first** eligible failure (threshold 3→1); pill gains a **Resend Failed** segment beside Clear Failed, routing every eligible row through its own existing per-row mechanism (QS → `retryQuickShareForIp`; native → `retryFile`) — no second protocol |
| 7 | Received/History cards: **Done pill** wastes height; metadata placement | The status chip rendered "Done" for every completed row; the detail line sat on its own full-width row with a 22 dp inset | Completed rows hide the chip (solo + group); the title and its metadata now form one vertical flex column beside the thumbnail — device + time-ago directly under the filename, smaller and muted, both lines ellipsized |
| 8 | Received tab needs **Share** and **Rename** | Only tap-to-open existed | Per-card ⋮ menu (the shared `showPickerOptionsMenu` component, compact + end-anchored): **Share** via the same FileProvider grant `openFile` uses (ACTION_SEND, real filename + MIME), **Rename** through the same improved modal the Files/Music sheets use (extension-preserving selection, duplicate-extension guard, collision/`/` validation, fs-refusal honesty) with provenance-registry follow-through so the row survives the rename |
| 9 | Web text viewing modal: long text **pushes buttons off-screen**; Cancel is pointless | `.text-ov .doc-viewer-body{overflow:visible}` disabled the card's only scroller, and the card was capped at 50vh — long `<pre>` content overflowed the card and pushed the footer out | Three-region flex card (capped 82vh/600px): header (title + ✕), body = the only scroller (`flex:1;min-height:0;overflow-y:auto` — `min-height` is what makes a flex child genuinely shrinkable on old WebKit), always-visible footer (Download + Copy). **Cancel removed** — ✕ and scrim-tap already close |
| 10 | Text items' sender-side waiting presentation | Investigated: broadcast text sends mint **no PENDING row by design** — `recordTextTransfer` completes the row at dispatch, and the queue card's Live row is the waiting presentation until the receiver accepts (targeted sends DO pre-register). Recorded as a design limitation with the trace, not silently changed late in the pass | Documented (§12 below); no behavioral change |
| 11 | Full Access bulk buttons "All"/"Clear" ambiguous | Labels | **Accept All** / **Decline All** — same styling, same actions |

---

## 1. §2 — ZIP-streaming History items support ALL selection methods

**The trace.** A ZIP-batch send mints its sender-side row in
`NativePushClient.sendToPeers` (line ~948):

```kotlin
val tid = TransferManager.startPendingSend(spec.relPath, spec.size, gid,
    session.peer.ip, session.peer.displayName, spec.node.localPath,
    zipStream = spec.size < 0L)
```

For the streamed archive the spec is `FileSpec(zipName, -1L, archiveNode, 0L, null)`
where `archiveNode` is a `FileTreeNode.ZipBatchNode` — whose `localPath` is
`null` **by design** (the archive is synthesized on the fly; nothing is ever
written to the sender's disk). So the completed row carries
`filePath == null`.

`TransfersFragment.isRangeSelectableIn` (non-active tabs) required:

```kotlin
item.transfer.status == Status.COMPLETED &&
    (item.transfer.isTextMessage || item.transfer.filePath != null)
```

Range selection (`longPressSelection`) and Hold & Slide (`attachDragSelect`)
both consult this predicate; a plain tap inside selection mode does NOT —
the exact reported asymmetry.

**The fix.**

1. The gate now passes `isZipStream` rows:
   `(... || item.transfer.isZipStream || item.transfer.filePath != null)`.
   One authoritative predicate — ZIP rows use the same selection state, the
   same handlers and the same visual feedback as every other History row;
   no separate mechanism.
2. The flag is persisted: `HistoryStore.Entry` gains `isZipStream`
   (JSON `"z": 1`), written by `record()` from `t.isZipStream`, restored by
   `fromJson` (`optInt("z", 0) == 1` — absent on every legacy record), and
   `renderHistoryList.transferFromEntry` sets it back onto the rebuilt row —
   so eligibility, the no-Pause rule and the auto-continue chip wording all
   survive a restart.

**Not affected:** the Active tab's contract (live rows), text rows, group
rows, Select All / Deselect All (they iterate the same predicate), and the
receiver's mirror row (it has a real `filePath` and was never excluded).

---

## 2. §3 — The rare "sound heard, sheet never shows" (CRITICAL)

**The trace.** The notification is the Android system notification sound of
the `CH_NATIVE_PUSH` channel, posted by `ServerForegroundService` at the
**announce** (and at the manifest when no announce preceded it). The sheet
reads `pendingNativePushes` — registered by the same endpoints, atomically
(`synchronized(pendingNativePushes)`). Registration and notification are
therefore consistent at write time.

The hole was the **sweep**. `sweepStalePendingNativePushes` ran on every
status poll / file POST, and its "nobody responded at all" branch tested
only:

```kotlin
val idleTooLong = now - p.createdAt > NATIVE_PUSH_TTL   // 3 minutes
if (idleTooLong && (p.status == "pending" || ...)) toRemove.add(p)
```

`createdAt` is fixed at construction and never refreshed — the branch never
asked whether the sender was *alive*. A live sender polls the decision
endpoint every 700 ms; each poll refreshes `p.lastActivityAt`, but nothing
on this branch read it. So a receiver whose user simply hadn't answered for
3 minutes had the still-pending record — the sheet row, the pre-decisions,
everything — swept out. The sender's next poll read `"rejected"` (unknown
id), its rows resolved failed/declined, and it sat Waiting until the user
cancelled and resent. The fresh push's fresh `createdAt` then lived inside
every TTL — which is precisely why the **second attempt always worked**.

**The fix** (one line of condition, full comment in source):

```kotlin
val idleTooLong = now - p.createdAt > NATIVE_PUSH_TTL &&
    now - p.lastActivityAt > NATIVE_PUSH_DEAD_SENDER_MS   // 10 min
```

A still-pending push is only "abandoned" when its sender has also been
silent for the full dead-sender window — the same liveness standard the
sender's own decision wait has used since FULL FINAL PASS 2. Pending
acceptance is not a transfer failure, however long the receiver's user
takes. The genuinely-dead-sender path (accepted push, 10 min of silence)
is untouched, as are the in-flight guard and the placeholder TTL.

---

## 3. §4/§5 — The web text clipboard workflow

**Direct Copy beside View** (`copyQSText`): looks the item up by its
server-minted name (never through an HTML attribute — multi-line text is
safe), copies the complete original through `_copyTextExact`, and only on
success runs the exact consumption sequence `viewQSText` uses (accept →
mark dispatched → local filter → render). A failed clipboard write shows
its own error toast and consumes nothing. Rendered in **both** surfaces —
the Full Access chip row and the Transfer Mode list row — beside View,
with the same styling the Get control uses.

**Accept All combines texts into ONE clipboard payload**: texts are
collected in queue order, joined with `"\n\n"` — the *exact* separator the
native receiver's own multi-message copy uses (`acceptAllNativePush` and
`precreateNativePushDirs` both `joinToString("\n\n")`) — and copied through
the same core. Files continue through the bounded pump untouched (one
`/api/quickshare/accept` round trip for the whole batch, as before). On
copy failure the texts are **not** consumed: they stay for individual
View/Copy, with an honest toast. The legacy-iOS branch shares the same
combined-copy half (its clipboard fallback is identical to the popup's own
Copy) and keeps its download-manager behavior for files. The per-text
`.txt` auto-download is gone entirely; a `.txt` now exists only through
the viewer's own Download button.

---

## 4. §6 — Waiting requests never auto-fail on time alone

The audit of every time-based transition (all values, all sites, in the
subagent report preserved in this pass's notes) found exactly two that
could fail a still-waiting item:

1. **Receiver-side pending-push sweep** — fixed in §2 above (liveness key).
2. **`expireStalePending`'s generic 5-minute staleness sweep** — measured
   "the peer's last download activity", but an offer the browser client
   hasn't accepted yet has *no* download activity by definition, so a
   targeted send's waiting row auto-failed at 5 minutes while the user
   stared at the chip. The sweep now exempts
   `source == QUICK_SHARE && requestedAtMs == 0L` rows — an undecided
   offer resolves only through a decision (accept claims it; decline/
   host-cancel resolve it; the 25-second never-requested sweep still
   covers the accepted-but-dispatch-dropped case).

Everything time-based that remains is a legitimate bound on a *dead*
session: dead-sender sweep (10 min), orphaned native-SEND rows (15 min),
accepted-never-requested (25 s), handoff grace (10 s), placeholder
manifest TTL (5 min, actionable rows throughout), stall watchdog (active
transfers only).

---

## 5. §7/§8 — Failed transfers: honest state, explicit recovery

**Removed (§7):** the server's `sweepFailedQuickShare` re-arm sweep (the
automatic re-offer of a failed item to the browser — un-consume + reinsert
at queue HEAD) and the client's bounded auto-retry (re-firing the failed
URL up to 3×). A failed item now stays Failed on the sender's Active tab
until the user acts. `qsRearmedKeys` stays declared (the fresh-send budget
resets still reference it); the one deliberate re-offer path,
`retryQuickShareForIp`, is untouched.

**Resend semantics (§8):**
- Per-row button renamed **Resend** (QS offers and native sends alike) —
  it says what it does.
- The failed pill appears from the **first** eligible failure
  (`clearFailedThreshold` 3 → 1) and the nudge-driven render lands it
  within ~120 ms of the state change (the `onUiStateNudge` path already
  calls `renderCurrentTab` on every `fail()`).
- The pill gains a **Resend** segment beside Clear Failed (hairline
  divider, same capsule language): every eligible row routes through its
  own existing per-row mechanism — QS → `retryQuickShareForIp(name, ip)`
  (re-offers to the ORIGINAL recipient), native → `NativePushClient.
  retryFile(tid)` (re-queues the same session leg) — then the row is
  dismissed into the normal Waiting workflow. Rows whose recipient is
  genuinely gone are reported ("can't be resent"), never silently kept
  and never duplicated. `TransferManager.resumableRowIdsPublic()` exposes
  the kept-for-resume set read-only so the bulk filter is the exact
  exclusion `eligibleFailedCount` applies (interrupted/kept-for-resume
  rows are somebody's reconnect story — never bulk-resent).

---

## 6. §9/§10 — Received/History cards & the Received ⋮ menu

**Cards (§9):** completed rows render no status chip (solo and group —
"Done" added height without information). The title row is restructured:
thumbnail, then a **vertical text column** (weighted `LinearLayout`, never
absolute positioning) holding the filename over the metadata line
(device + time-ago, 10.5sp, muted, single-line, end-ellipsized). Long
names middle-ellipsize; small screens narrow the column. `tv_detail`
keeps its tag — every existing bind path (`updateRow`, payload rebinds,
the ago ticker) finds it inside the column by tag search exactly as
before.

**⋮ menu (§10):** completed received FILE rows (Received tab only) carry
a 30 dp ⋮ at the end of the title column. Click and long-press are
consumed (never the card's open action, never selection entry). The menu
is the app's shared `showPickerOptionsMenu` — compact, end-anchored,
clamped below its anchor:
- **Share** — the same FileProvider grant `openFile` uses, as ACTION_SEND
  with the real filename and MIME type, through the system chooser.
- **Rename** — the same improved modal the Files/Music sheets use:
  base-name preselected (extension preserved unless deliberately
  changed), duplicate-extension typo guard (`photo.jpg.jpg` → one
  `.jpg`), blank/`.`/`..`/`/` validation, collision refusal (never
  auto-suffixed), storage-refusal honesty. After a successful rename the
  engine row, `ReceivedRegistry` (new path recorded, old path forgotten —
  new `forgetPath` helper) and `ReceivedFileMeta` follow the file, and
  the reconcile re-renders immediately (`lastReceivedScanAtMs = 0`).

Text rows and History rows keep their existing presentations (History's
own rename/share stays out of scope per the spec — its rows are the
durable log, not the filesystem).

---

## 7. §11 — The web text viewing modal

Root cause: the old `.text-ov .doc-viewer-body{overflow:visible}` override
(a deliberate old-WebKit text-selection fix) removed the card's only
scroller, and the card was capped at 50vh — a long `<pre>` overflowed the
flex column and pushed the footer's buttons off-screen.

The modal is now a self-contained three-region card:
`.text-popup-card` (82vh/600px cap) → header / `.text-popup-body`
(`flex:1 1 auto; min-height:0; overflow-y:auto` — the min-height is what
makes a flex child genuinely shrinkable on old WebKit) / pinned footer
(Download + Copy). Copy and Download are visible and tappable for
arbitrarily long messages. **Cancel is removed** — the ✕ and the scrim
tap already close without side effects (the item was consumed at open,
exactly as before). The text-selectability override (no transform, no
momentum scrolling on the selectable container) is preserved — the modal
itself never scrolls as a whole; only its body does.

---

## 8. §12 — Text items' Active-tab presentation (investigated, documented)

Trace result: a **targeted** text send pre-registers a PENDING row per
recipient (`preRegisterQuickShareSends` via `claimOrStartPending`) and
lives the normal Active-tab workflow. A **broadcast** text send
(`addTextToQuickShare`) deliberately mints **no PENDING row**: the row is
completed at dispatch (`recordTextTransfer` — delivery confirmation for
text is the receiver's Copy action, recorded on the receiver), and the
queue card's Live row is the waiting presentation until a client takes
it. Changing this late in the pass (minting PENDING rows for broadcast
texts, with all the re-arm/cancel/consume interactions) would be a
redesign of the broadcast-offer lifecycle, not a fix — recorded here as
the design limitation it is, with the full trace, so a future pass can
decide deliberately.

---

## 9. §13 — Full Access bulk labels

`All` → **Accept All**, `Clear` → **Decline All** (same buttons, same
styling, same actions — the labels were ambiguous about what they do).

---

## 10. Regression audit (what was checked, not changed)

- **Selection**: the §2 gate change affects ONLY the received/sent tabs'
  completed-row contract; the Active tab's lifecycle contract, text rows,
  groups, Select All/Deselect All (same predicate), the Files/Media/
  Music/Apps sheets, Hold & Slide and the drag-select eligibility all
  trace through unchanged code paths.
- **Sweep changes**: the pending-push liveness key cannot extend a
  genuinely dead session (the 10-minute silence bound is the same one the
  accepted-push branch uses); the QS-offer exemption can't leak — an
  accepted offer sets `requestedAtMs > 0` on the first byte-request and
  re-enters every existing bound.
- **Removed re-arm sweep**: `qsRearmedKeys` budget resets preserved; no
  other caller of `zeroByteFailedDownloadPeersFor` existed (verified).
- **§7 client change**: the failed-chip re-arm path (dispatched-flag
  release) still runs, so a *deliberate* resend's chip remains gettable.
- **Web**: both inline script blocks parse (`new Function` syntax check);
  the modal's z-index layering, the TM bulk row's visibility writer, and
  the legacy-iOS branch paths re-verified by reading.
- **Build**: `assembleRelease` BUILD SUCCESSFUL; `testReleaseUnitTest`
  **190 tests, 0 failures, 0 errors** (all 24 test classes, including the
  Final Pass 1/2/3 and GOAT-pass regression suites); the APK is signed
  with the project keystore and contains the updated `assets/index.html`.

---

## 11. Verification — the honest boundary

| Layer | Status |
|---|---|
| Source-level tracing of every reported issue to its root cause | Done (each section above names the traced cause) |
| Static analysis / compile | `compileReleaseKotlin` + `assembleRelease` clean; JS syntax verified |
| Unit tests (JVM) | 190/190 pass |
| APK build + signature | Built, signed, verified (apksigner), web asset confirmed inside |
| Emulator / physical device | **Not performed in this sandbox** — all runtime claims above are source-trace + build-level only |

Runtime scenarios that still need a device: the §2 selection matrix over a
real streamed-ZIP history row, the §3 repro across a real 3+ minute
undecided wait, clipboard behavior on the legacy iOS targets, and the
Received ⋮ menu against a real FileProvider grant.

---

## 12. Files changed

| File | Sections |
|---|---|
| `app/src/main/java/com/lumidrop/x/TransfersFragment.kt` | §2 gate; §8 Resend rename + pill (threshold, Resend Failed, eligibility filter); §9 chip hiding + title-column restructure; §10 ⋮ attach + Share/Rename |
| `app/src/main/java/com/lumidrop/x/transfer/HistoryStore.kt` | §2 `isZipStream` persistence (record/fromJson/toJson) |
| `app/src/main/java/com/lumidrop/x/server/LumiDropServer.kt` | §3/§6 pending-push sweep liveness key; §7 re-arm sweep removal |
| `app/src/main/java/com/lumidrop/x/transfer/TransferManager.kt` | §6 undecided-offer exemption; §8 `resumableRowIdsPublic()` |
| `app/src/main/java/com/lumidrop/x/transfer/ReceivedRegistry.kt` | §10 `forgetPath` |
| `app/src/main/assets/index.html` | §4 `copyQSText` + Copy buttons (both surfaces); §5 combined-payload Accept All; §7 client auto-retry removal; §11 modal layout + no Cancel; §13 labels |
| `app/build.gradle` | versionCode 75 + pass notes |

Every change is annotated in-source with `FINAL PASS 3 (§…)` comments
carrying the root-cause trace, per the project's documentation discipline.

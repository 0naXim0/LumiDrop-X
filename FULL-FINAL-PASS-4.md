# LumiDrop X — FULL FINAL PASS 4

## Deep Reliability Correction, ZIP Streaming Selection, Incoming Request Integrity, Text Workflow Unification & Web UI Refinement

> **Version:** versionCode 76 · Baseline: FINAL PASS 3 (versionCode 75, the
> uploaded `LumiDropX-FULL-FINAL-PASS-3-source.zip` — verified byte-identical
> to the delivered source by checksum before any work started).
> Method: corrective audit first. Three independent deep traces (incoming-
> request lifecycle; text workflow + web clipboard; web modal + native cards)
> plus a manual §2/§6/§7/§8 audit, each pinned to file:line in the ACTUAL
> Pass 3 source — **then** fixes at the traced root causes.
> Build: `assembleRelease` + `testReleaseUnitTest` **202 tests, 0 failures**
> (190 pre-existing + 12 new regression tests), signed APK built and verified.

---

## 0. Why FINAL PASS 3 was insufficient — the honest audit

| § | What Pass 3 claimed | What the Pass 4 audit actually found |
|---|---|---|
| §10 modal | "The body is the only scroller (flex:1 + overflow-y:auto + min-height:0)" | The rules existed but were **INERT**: the legacy composer override `.text-ov .doc-viewer-body{overflow:visible}` (index.html:997, specificity 0,2,0) beat the popup's `.text-popup-body{overflow-y:auto}` (0,1,0) in the cascade on every engine. The scroller never scrolled; long text painted through the transparent footer. **The fix was present in the file and did nothing.** |
| §3 incoming | "A live sender's undecided push never ages out" | True for the **manifest-ready** branch only. The **placeholder** branch (announce→manifest window) still removed purely on `createdAt` age (5-min TTL) — and ZIP streaming's manifest build (recursive tree walk + per-file stream validation) is the longest sender-silent window of any send: by protocol design the sender transmits NOTHING between announce and the request POST, so `lastActivityAt` was frozen and a healthy sender's offer was swept mid-build. Also found: a **triplicated merge artifact** in the Pass 3 edit (comment + condition ×3), the sweep's removal loop ran **unsynchronized** against decision writers, and a lost record answered `"rejected"` — indistinguishable from a real decline, so the sender could never self-heal. |
| §9 text | Documented as a "design limitation" (§12 of the Pass 3 report) | The actual root cause is two omissions, not a design constraint: `addTextToQuickShare` was the ONE queue path that never called `preRegisterQuickShareSends` (files/folders/ZIP batches all do), and `apiQuickShareAccept`'s text branch skipped TransferManager entirely. Unification was always possible; Pass 3 chose not to attempt it. |
| §4/§5 clipboard | "Consumes only after a successful copy" | `_copyFallback` discarded `document.execCommand('copy')`'s **boolean return** — a denied clipboard returns `false` without throwing, and the old code consumed the item and showed "✓ Copied" anyway. Plus an 800 ms success/timeout race discarded a late genuine success in Accept All. |
| §2 ZIP selection | "ZIP-stream rows select like every other row" | Correct for the **native leg** (sender rows minted by `startPendingSend`). But the **browser leg** (ZIP batch via Quick Share to a browser) minted rows through `claimOrStartPending`, which had no `zipStream` parameter — those rows were born unmarked, failed the `isZipStream`-keyed gate, and reproduced the exact Hold & Slide/Range asymmetry on the other leg. Pass 3 never tested that leg. |
| §12 ⋮ menu | "A small ⋮ appended at the end of the title row" | It was appended as a **third line under the metadata** (inside the vertical title column), left-aligned — consuming exactly the card height the pass removed. Worse: the button was only ever **added** (null-guarded), never removed on rebind — a recycled card kept a stale ⋮ whose closure referenced the **previous** transfer (share/rename acting on a different file than displayed). |

---

## 1. §2 — ZIP-streaming History selection (both legs now)

**Native leg**: already fixed in Pass 3 (the `isZipStream` gate in
`isRangeSelectableIn` + `HistoryStore` persistence) — verified present and
correct, regression-pinned by the existing suites.

**Browser leg (new)**: `preRegisterQuickShareSends` gained a `zipStream`
parameter, `addZipBatchToQuickShare` passes `zipStream = true`, and the flag
rides `claimOrStartPending` → `startPending` → the `Transfer` constructor.
A browser-leg streamed-archive row is now born marked, so the completed
row passes the same selection gate, gets the no-Pause presentation, and —
with the Pass 3 `HistoryStore` `"z"` persistence — survives restarts.
Selection semantics are untouched: the SAME predicate, handlers, and visual
state for every row; the marker only makes the row *eligible*, exactly as
the native leg's rows already were.

Pinned by `FinalPass4ZipStreamMarkerTest` (3 tests).

## 2. §3 — Incoming-request integrity (the ZIP repro's true fixes)

1. **Placeholder sweep liveness** (the ZIP root cause): the placeholder
   branch now requires sustained sender silence —
   `createdAt > PLACEHOLDER_TTL && lastActivityAt > DEAD_SENDER (10 min)` —
   mirroring the Pass 3 manifest-ready fix. A dead sender still resolves
   promptly; a live one, however slow its ZIP build, keeps its offer.
2. **Manifest-window keep-alive (sender)**: `sendToPeers` now runs a gentle
   poll (`/api/native/push/status?v=4&id=<sessionId>`, every 15 s) for
   exactly the manifest-build window — the one protocol interval with zero
   sender traffic. The receiver bumps `lastActivityAt` for it like any
   poll, so the liveness conjunct is *satisfiable* during the build. The
   keep-alive cancels the moment the manifest is built (try/finally).
3. **Atomic sweep removal**: the removal loop now re-checks each candidate
   under `synchronized(pendingNativePushes)` — the same monitor the
   decision writers and the placeholder replacement hold — so a decision
   landing mid-sweep can no longer be destroyed by a removal evaluated
   against pre-decision state.
4. **`"unknown"` vs `"rejected"` + heal-once**: a new receiver answers
   `"unknown"` (only for `v=4` pollers — old senders keep the legacy
   answer and byte-identical behavior) when it has no record of an id. The
   sender treats that as a lost record and re-announces + re-POSTs the
   manifest **once** (both endpoints are idempotent by id); a second
   `"unknown"` resolves as the old "rejected" — the honest outcome of a
   genuinely unrecoverable loss. No more mislabeled "declined" for a
   receiver that never got to decide.
5. **Merge artifact removed**: the triplicated Pass 3 comment + condition
   deduplicated to the intended single form.
6. Verified NOT defects (kept): registration-before-notification ordering
   in both endpoints, the Pass 2 atomic placeholder swap, decline/cancel
   end-to-end, the repeat-manifest idempotency guard.

Pinned by `FinalPass4PendingPushSweepTest` (4 tests: window relationships,
keep-alive cadence, both branch predicates).

**§6 (waiting never auto-fails)**: the Pass 3 protections verified present;
the one new hole the §9 work would have opened (see §4 below) closed by
exemption rather than by removing any sweep — genuine network failure
handling (stall watchdogs, dead-session bounds, handoff grace) is untouched.

## 3. §7/§8 — no automatic resend; Resend Failed

Verified in the audit: the server's re-arm sweep and the client's bounded
auto-retry are both gone (Pass 3), the pill's Resend routes every eligible
row through its own per-row mechanism, per-row **Resend** renamed, the pill
shows from the first failure, and `resumableRowIdsPublic` keeps
kept-for-resume rows out of bulk resend. **No Pass 4 changes needed** —
recorded as verified-correct rather than re-touched.

## 4. §9 — Text Quick Share / Paste joined the normal Active-tab lifecycle

The unification the audit traced, implemented on the existing architecture
(no parallel state):

- **Targeted text** (specific browsers): `addTextToQuickShare` now
  pre-registers one PENDING row per target (the same
  `claimOrStartPending` call files/folders/ZIP batches make) and stamps it
  as a message row (`markQuickShareRowAsText`). Waiting lives in the
  **sender's Active tab** from the instant Send is tapped; the immediate
  `recordTextTransfer` for the targeted case is gone (it would double-write
  History against the completion hook).
- **Consume completes**: `apiQuickShareAccept`'s text branch now resolves
  the row — `completeQuickShareText` for a pre-registered row, or
  `claimAndCompleteQuickShareText` for a **broadcast** (no row until a
  client accepts, exactly like a broadcast file; one message card per
  delivery, never two).
- **Decline/cancel already wired**: `declinePendingQuickShare` /
  `cancelLiveQuickShareByName` key on `qsItemName`, which text rows now
  carry — decline resolves DECLINED, queue teardown resolves CANCELLED,
  with zero changes to those paths.
- **The sweep trap closed**: the 25 s never-requested sweep and the generic
  5-minute staleness sweep both exempt `isTextMessage` rows — a text row's
  delivery IS the consume, so `requestedAtMs` legitimately stays 0 forever;
  without the exemption every text row would have auto-failed ~25 s after
  queueing.
- **Consumers verified intact** (audit checklist): queue ordering,
  receiver-drawer hold-back, Send-tab queue-card filter (`hasLiveEntry` —
  targeted texts now leave the card exactly like files), Accept All,
  auto-accept exclusion of texts, notifications, `inFlightQS` shields,
  tombstones, and both Resend paths (text rows now carry the
  `qsItemName` they always lacked, so QS text resend works for the first
  time).

Pinned by `FinalPass4TextLifecycleTest` (5 tests).

## 5. §4/§5 — Web text clipboard (honesty fixes)

- `_copyFallback` now honors `execCommand('copy')`'s boolean: `false`
  → the error toast, **nothing consumed** (both Direct Copy and Accept All
  were consuming on failed copies before).
- The Accept All 800 ms timeout no longer sets the reported flag — a late
  genuine success consumes exactly like a prompt one; `afterTexts` is
  idempotent.
- Verified correct from Pass 3 (no changes): Direct Copy in both modes
  (Full Access chip row + Transfer Mode list row), the `"\n\n"` combined
  Accept All payload matching the native receiver's exact join, text
  download only via the viewing modal's Download button, auto-accept
  excluding texts.

## 6. §10 — The text viewing modal (the cascade fix)

One CSS change closes both reported symptoms: the composer's legacy
override is narrowed to
`.text-ov .doc-viewer-body:not(.text-popup-body){overflow:visible}` — the
composer (whose textarea is its own scroller) keeps its old-WebKit
text-selection fix verbatim, and the popup's body now actually owns its
scroll chain (`flex:1; min-height:0; overflow-y:auto` finally wins the
cascade). The footer additionally gets the card's own background +
bottom-radius so text can never visually bleed through the action region.
Three regions (header+X / scrollable body / pinned Download+Copy), no
Cancel, all old-iOS/old-Android-safe selectors (`:not()` with a simple
argument is supported on every target). X and scrim-tap close without
consuming.

## 7. §11–§13 — Received/History cards, ⋮ menu, labels

- **Done pills / metadata**: verified correct from Pass 3 (chip GONE for
  completed rows solo+group both tabs, metadata under the filename beside
  the thumbnail, ellipsized) — no changes needed.
- **⋮ menu**: the stale-on-recycle bug fixed (the button is now removed on
  every non-selection rebind before the qualifying branch re-adds it fresh
  with THIS bind's transfer), and the placement fixed properly: the shell
  now wraps `tv_detail` in a horizontal `detail_row`, so the ⋮ sits
  **beside the metadata at the row's end** — no third line, no extra card
  height, filename keeps the full weighted width. Group rows intentionally
  remain without the menu (folder rename/share is a filesystem operation
  over many files — out of the spec's FILE-row scope; noted as a known
  limitation).
- **Labels**: Accept All / Decline All verified in both surfaces; no
  user-visible "All"/"Clear" remains.

## 8. Verification — the honest boundary

| Layer | Result |
|---|---|
| Corrective audit of Pass 3 vs its claims | Done — every gap in §0 traced to file:line |
| Compile | `compileReleaseKotlin` BUILD SUCCESSFUL |
| Unit tests (JVM) | **202/202 pass** — 190 pre-existing regression suites (all prior passes still green) + 12 new (text lifecycle 5, zipStream marker 3, sweep contract 4) |
| APK | Built, signed, apksigner-verified, `assets/index.html` embedded |
| Emulator / physical device | **Not performed in this sandbox.** All runtime claims are source-trace + build-level. |
| Web JS syntax | Both inline blocks parse (Node `new Function`) in the prior pass's check; this pass's JS edits are return-value/flag-only |

**Still needing a device:** the §3 repro across a real 5+ minute ZIP
manifest build (the keep-alive + liveness window), §2 selection matrix on a
real browser-leg streamed-ZIP history row, §9's full sender/receiver text
workflow, the §10 modal on old iOS Safari hardware, and the ⋮ menu's
FileProvider grant against a real share target.

**Known limitations (unchanged or new):**
- `pendingNativePushes` remains in-memory: a receiver process death still
  loses undecided offers (the sender's `"unknown"`→heal now recovers it
  instead of mislabeling it, but a persistence sidecar — the audit's R5 —
  was judged too large to add safely this pass).
- Group (folder) Received rows have no ⋮ (file-scope decision, above).
- The `"unknown"` answer requires a `v=4` sender; mixed-version peers keep
  the old behavior by design.

## 9. Modified files

| File | Changes |
|---|---|
| `app/src/main/assets/index.html` | §10 cascade fix (`:not(.text-popup-body)` narrowing) + footer background/radius; §4/§5 `_copyFallback` boolean handling; Accept All late-success race |
| `app/src/main/java/com/lumidrop/x/server/LumiDropServer.kt` | §3 placeholder sweep liveness + atomic removal + dedupe; `"unknown"` status answer; §2 `preRegisterQuickShareSends` zipStream; §9 `addTextToQuickShare` pre-registration + accept-branch completion |
| `app/src/main/java/com/lumidrop/x/transfer/TransferManager.kt` | §2 `zipStream` through `claimOrStartPending`/`startPending`; §9 `completeQuickShareText`/`claimAndCompleteQuickShareText`/`markQuickShareRowAsText` + both sweep exemptions |
| `app/src/main/java/com/lumidrop/x/p2p/NativePushClient.kt` | §3 manifest-window keep-alive (15 s, try/finally scoped); `rePostManifest` + heal-once on `"unknown"` |
| `app/src/main/java/com/lumidrop/x/TransfersFragment.kt` | §12 ⋮ stale-on-recycle removal + `detail_row` placement (shell + bind) |
| `app/build.gradle` | versionCode 76 + pass notes |
| `app/src/test/.../FinalPass4TextLifecycleTest.kt` | NEW — 5 tests |
| `app/src/test/.../FinalPass4ZipStreamMarkerTest.kt` | NEW — 3 tests |
| `app/src/test/.../FinalPass4PendingPushSweepTest.kt` | NEW — 4 tests |

Every change carries an in-source `FINAL PASS 4 (§…)` comment with the
root-cause trace, per the project's documentation discipline.

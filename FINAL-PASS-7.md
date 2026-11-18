# LumiDrop X — FINAL PASS 7

## Native Text-Selection Reconstruction, Incoming-Transfer Reliability & Received/History Card Corrections

> **Version:** versionCode 79 · Baseline: FINAL PASS 6 (versionCode 78, the
> uploaded `LumiDropX-FINAL-PASS-6-source.zip`).
> Build: **standalone JVM suite 210/210 pass; Gradle `testReleaseUnitTest`
> 220/220 pass, 0 failures; `assembleRelease` BUILD SUCCESSFUL** — signed,
> apksigner-verified APK produced (see the verification-honesty table for
> exactly what was and was NOT runtime-tested).

---

## PART 1 — Text Sender: the input architecture was RECONSTRUCTED

### Root cause, and why six previous passes failed

The Text Sender modal is (and since GOAT-PASS 8 has been) a **fully native
editing dialog** — not a WebView, not an HTML textarea. The field is an
editable `EditText`; the web composer is a separate browser surface used
only from browsers.

Six passes (GP7→FP6) progressively hardened a **custom editing window**: a
full-screen `AppCompatDialog` on the app's own transparent
`Theme_LumiDropX_TransparentModal` (parented on `Theme.AppCompat.NoActionBar`),
a `MATCH_PARENT` window, a custom clickable backdrop, and (GP14P2) a
themed-context view chain where every view was minted from the dialog's own
context. Each pass fixed a real interference layer — theme ActionMode
overlay, scrollable ancestors, custom listeners, immersive residue, the IME
extract gate (`IME_FLAG_NO_EXTRACT_UI`, FP6, which closed a genuine AOSP
`Editor.extractedTextModeWillBeStarted()` cancellation path) — and the
Android 14 selection toolbar still failed on the reporting device.

The layer **no previous pass ever changed** was the window scaffold itself.
Every fix rebuilt the field inside the same nonstandard shell. Two findings
closed the case this pass:

1. **The app contains its own control experiment.** `LdxDialog` — the
   Rename / New Folder / Rename-device dialogs — is a plain
   `android.app.Dialog` on `Theme_Translucent_NoTitleBar` with a plain
   framework `EditText` minted from the activity's context… and native
   long-press selection demonstrably **works** in those dialogs on the same
   device that fails in the Text Sender. The only architectural differences
   between the two surfaces were exactly the custom layers above.
2. **AppCompat wraps the floating-toolbar path.** Verified in the appcompat
   1.6.1 bytecode (`AppCompatDelegateImpl$AppCompatWindowCallback`):
   `onWindowStartingActionMode(callback, TYPE_FLOATING)` routes through
   `startAsSupportActionMode` → `SupportActionModeWrapper` layers even for
   floating modes when native-action-mode handling is enabled. A plain
   framework `Dialog` skips that wrapping entirely — the platform's own
   `DecorView` hosts the `FloatingActionMode`/`FloatingToolbar` popup
   directly, the environment OEM floating-toolbar implementations are
   actually QA'd against.

### The reconstruction (window scaffold only — everything else preserved)

- `android.app.Dialog(requireContext(), android.R.style.Theme_Translucent_NoTitleBar)`
  — **exactly LdxDialog's recipe** (was: `AppCompatDialog` + custom theme).
- Views minted from the **activity's context** (was: the dialog's themed
  context) — same chain as the working rename inputs.
- The field is a **plain framework `android.widget.EditText`** (was
  `AppCompatEditText` from the dialog context) — the same class the working
  fields use; `IME_FLAG_NO_EXTRACT_UI` retained verbatim.
- Window chrome reproduced exactly as before: `FLAG_DIM_BEHIND` 0.55,
  `SOFT_INPUT_STATE_VISIBLE | SOFT_INPUT_ADJUST_RESIZE`, bar colors +
  explicit navigation-bar show.
- **Unchanged:** the card design and pinned-height keyboard geometry, the
  six-line internal-scrolling field, the character counter, the Auto-Send
  verb actions, the verbatim-multiline send path (`stageOrSendItem` /
  `PreviewItem`), the entrance/exit animations, the bounded dismiss, the
  focus+IME-open post. One source of truth for the text; no duplicate
  fields; no clipboard code; no custom selection UI anywhere.

### What was actually tested (PART 1 — the honest boundary)

- ✅ Compiles (`assembleRelease` BUILD SUCCESSFUL); the reconstruction is
  source-traced against the working dialog's recipe line by line.
- ❌ **NOT runtime-verified — no Android 14 device or emulator exists in
  this sandbox.** The full interaction matrix (long-press → toolbar, Select
  All, Copy, Paste, Cut, partial selection, handle drags, post-edit
  selection, keyboard-open resilience, post-send, modal-reopen) remains
  **outstanding physical-device verification**. The pass's confidence rests
  on the strongest available evidence — the same scaffold demonstrably
  working in this very app on the reporting device class — but the report
  does not claim device confirmation.

## PART 2 — Incoming-transfer reliability: the notification-without-sheet race

### Root cause (presentation half — traced end to end)

The event path was already sound: `apiNativePushAnnounce` registers the
push in the authoritative `pendingNativePushes` map **and** posts the
notification; `ReceivingModal`'s unkillable 500ms tick re-derives the sheet
from that state. Notification and sheet share one registration — so a
"notification but no sheet" failure had to live in **presentation**. It
does:

`BottomSheet.show()` starts its slide-up entrance **inside
`root.post {}`**: `root.translationY` begins at 400dp (fully off-screen)
and reaches 0 only when the posted runnable's `animate()` completes. A
**window-focus transition in that window** — precisely what happens on
first launch when the app returns from the ALL-FILES permission settings
screen, or on any app switch — can cancel the posted runnable or its
animation. The result: the dialog window stays attached
(`isShowing == true`) while its content sits 400dp below the screen and the
dim scrim never faded in. Every subsequent tick takes the in-place update
branch, which checks only `isShowing` — so the sheet is **never
re-presented**: the notification fired, the sheet stayed invisible, the
sender's row sat Waiting. This matches the repro exactly (fresh install →
permission grant → immediate send, intermittent on focus-transition timing).

### The fix — authoritative-state reconciliation, not more event firing

1. **Visual-state reconciliation in the tick** (`ReceivingModal`): the
   stale-sheet guard now also detects a **stranded** sheet —
   `entranceSettled && rootTranslationY ≥ 100dp && sheet age > 1s` (a
   healthy 220ms entrance always lands inside the grace; upward drags are
   negative and can never trip it). A stranded sheet is dismissed, its
   state cleared (including `dismissedIncomingSignature` — a stranded sheet
   was never a deliberate dismissal), and the normal build path re-presents
   it **on the same tick**. Supporting accessors were added to
   `BottomSheet` (`rootTranslationY`, `entranceSettled`, `markEntranceRan()`
   wired into the entrance runnable), with per-sheet birth-time tracking
   (identity-keyed, cleared on dismiss/stop — no leaks).
2. **Immediate reconciliation on resume** (`start()`): `onResume` — the
   single most important moment, exactly when the app returns from the
   permission screen or a notification tap — now runs the reconciliation
   body once immediately instead of waiting up to 500ms for the first tick.
   Safe by construction: the build path is only reachable with zero live
   sheets, and the `total == 0` branch tears down instead of building.
3. **Already-sound layers re-verified, untouched:** the registration-before-
   ack protocol (`apiNativePushAnnounce` registers then acks), the TTL
   sweepers' liveness conjuncts (a live sender never ages out — FP3/FP4's
   fixes), idempotent identity by push id, the single-modal guarantee, and
   the notification⇄sweep agreement.

### What was actually tested (PART 2)

- ✅ The stranded-sheet predicate's full decision matrix is pinned by the
  new JVM tests (fresh sheet, mid-entrance, settled, stranded, upward-drag
  — five cases, all green in both suites).
- ✅ Registration/idempotency/TTL behavior covered by the existing
  220-test Gradle suite (all green).
- ❌ **Not physically tested:** the actual focus-transition race on a
  device (first-install send-during-permission-grant), background/foreground
  cycles on hardware, and network disconnect/reconnect during a pending
  request. No two-device setup exists in this sandbox; these remain
  device-verification items.

## PART 3 — Received metadata "invisible": a CONTRAST bug, not a data bug

The data flow was verified intact end to end: `buildReconciledReceived`
populates `totalBytes` (file length), `finishedAt`
(`ReceivedRegistry.receivedAt`, mtime fallback), and `updateRow` binds
`tv_detail = detailLine(...)` on every bind — real `fmtBytes` +
`fmtTimeAgo` values were being set the whole time.

The bug was **visual**: FP6 had set the metadata line to `text_muted`
(#676E77) at 10.5sp. On the cards' actual surface — `bg_secondary`
(#2A2E37, via `bg_btn_secondary`) — that computes to a **2.64:1** contrast
ratio (WCAG relative-luminance formula, verified by test): below even the
3:1 large-text floor, effectively invisible at 10.5sp. The user's "I see
nothing" was exactly right.

**Fix:** the metadata line now uses `text_secondary` (#8F97A3) — **4.61:1**
on the same surface (WCAG AA; 5.33:1 on `bg_card`), one size step up
(11sp). It stays a quiet gray clearly subordinate to the 13sp
`text_primary` title. Both computed ratios are pinned by tests so a future
palette tweak can't silently reintroduce the invisibility. No placeholder
values, no fabricated data — the same real timestamps and sizes, finally
legible.

## PART 4 — History: every individual X removed, no residual gap

- The History record-✕ (FP6 had scoped it to non-ZIP rows) is **gone for
  all item types** — files, folders, media, documents, text messages,
  ZIP-streamed, received, sent, failed, completed.
- The one remaining ✕ that could reach a History card — the interrupted-
  receive **partial-discard** control on FAILED rows (a transfer-lifecycle
  action with its own confirmation modal) — is now scoped to the **Active
  tab**, where its Pause/Resume/Resend siblings live. History never
  renders it.
- **No empty gap:** the action band is built per-row from the qualifying
  actions alone — with no ✕ to add, no width is reserved (verified in the
  builder: rows without actions contribute an empty, zero-width band).
  Card geometry otherwise untouched (FP6's compact shell).
- **Removal paths preserved:** long-press Hold & Select, range / Hold &
  Slide, bulk Delete Selected, Clear History — all untouched, as are the
  Active tab's live-transfer controls and the Received tab's ⋮ menu.
  (The audit confirmed every remaining `ic_close` in the tab is either a
  modal's own close button, the selection action-bar's Cancel, or the
  Active-tab lifecycle controls.)

## PART 5 — History vs Received metadata: distinct by construction

Verified in source and pinned by tests: History cards render
**"Received from [Device]" / "Sent to [Device]"** (FP6's `history` flag,
real `peerName` from the records, never invented) plus time-ago and size;
Received cards render **time-ago + size only** (the peer segment is
deliberately hidden there, FP5's rule); Active rows keep the compact
From/To forms. Same shared card rhythm, distinct information contracts.

## Files modified

| File | Changes |
|---|---|
| `SendFragment.kt` | PART 1: the dialog scaffold reconstruction (plain `Dialog` + `Theme_Translucent_NoTitleBar` + activity-context views + plain `EditText`); FP6's `IME_FLAG_NO_EXTRACT_UI` and all field properties/animations/send flow preserved |
| `ui/ReceivingModal.kt` | PART 2: stranded-sheet visual reconciliation in the tick (`isSheetVisuallyStranded` + grace tracking + signature reset), immediate reconciliation in `start()`, birth-time map lifecycle (dismiss/stop) |
| `ui/BottomSheet.kt` | PART 2: read-only `rootTranslationY` / `entranceSettled` accessors + `markEntranceRan()` wired into the entrance runnable |
| `TransfersFragment.kt` | PART 3: metadata line `text_muted`→`text_secondary`, 10.5sp→11sp (the contrast root cause); PART 4: History record-✕ removed entirely + interrupted-discard ✕ scoped to the Active tab |
| `app/build.gradle` | versionCode 79 + the pass notes |
| `test/.../FinalPass7ReliabilityContractTest.kt` | NEW — 10 tests: the contrast math (old color proven sub-3:1, new color proven AA on both surfaces), the stranded-sheet predicate's full decision matrix, the History-X tab contract |

## Verification honesty

| Layer | Status |
|---|---|
| Source-code inspection & root-cause tracing (all parts) | Done — including the appcompat bytecode check (PART 1) and the WCAG luminance math (PART 3) |
| Compilation | **BUILD SUCCESSFUL** (both changed modules) |
| Standalone JVM suite | **210/210 pass** (incl. the 10 new FP7 tests) |
| Gradle `testReleaseUnitTest` | **220/220 pass, 0 failures** |
| Gradle `assembleRelease` | **BUILD SUCCESSFUL** — signed APK, apksigner-verified, versionCode 79 |
| Emulator testing | **Not performed — no emulator in this sandbox** |
| Physical-device testing | **Not performed — no Android 14 device available.** PART 1's full interaction matrix (long-press/Select All/Copy/Paste/Cut/handles), PART 2's first-install race and lifecycle scenarios, and the on-screen legibility of PART 3 remain **outstanding device verification**. |

## Remaining limitations

- PART 1's confidence is architectural (same-scaffold control experiment
  inside this app) plus the FP6 AOSP gate fix — final confirmation needs
  the device checklist run once.
- PART 2's reconciliation makes the presentation failure self-healing
  within ~1s, but if an OEM window transition can cancel the entrance in a
  way that ALSO kills the rebuilt sheet's entrance, the loop would still
  recover on the next tick (each rebuild is a fresh window); this
  bounded-retry property is by construction, not yet device-proven.
- The `Theme_LumiDropX_TransparentModal` style remains defined but is now
  unused by the composer (kept: harmless, and documents the history).

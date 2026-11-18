# LumiDrop X — FINAL PASS 6

## Native Android Text Selection, History Text Viewer Scrolling & Complete Received/History Card Redesign

> **Version:** versionCode 78 · Baseline: FINAL PASS 5 (versionCode 77, the
> uploaded `LumiDropX-FINAL-PASS-5-source.zip` — extracted fresh and kept as a
> pristine comparison tree; every change below was made against it).
> Method: root-cause engineering, not another patch layer. The Text Sender's
> selection failure was traced through the **actual Android 14 AOSP sources**
> (`TextView.java`, `Editor.java`, `ArrowKeyMovementMethod.java`,
> `DecorView.java`, `LocalFloatingToolbarPopup.java` — fetched and read line
> by line) before a single line was changed.
> Build: **`testReleaseUnitTest` 220/220 pass, 0 failures; `assembleRelease`
> BUILD SUCCESSFUL**; signed APK produced and apksigner-verified.

---

## PART 1 — The Text Sender text-selection root cause (THE defining fix)

### What the input actually is

The Text Sender modal ("Quick Share Text", `SendFragment.showTextShareDialog`)
is a **fully native** `AppCompatDialog` hosting an
`androidx.appcompat.widget.AppCompatEditText` — NOT a WebView, not an HTML
textarea. (The web `textarea` composer is a separate surface used from
browsers; both were fixed this pass.) Six prior passes (GP7→GP14P2) had
progressively fixed the theme's `windowActionModeOverlay`, removed the
scrollable ancestor, removed custom touch listeners, unified the context
chain, removed immersive residue and set the soft-input mode — all real
interference layers, all genuinely fixed, and **the bug survived anyway**.

### The actual root cause (AOSP line-level trace)

Long-press on an editable field runs:

```
TextView.performLongClick() → Editor.performLongClick()
  → Editor.selectCurrentWordAndStartDrag()        [Editor.java:2519]
      if (extractedTextModeWillBeStarted()) return false;   ← THE GATE
  → Editor.startActionModeInternal()              [Editor.java:2563]
      if (extractedTextModeWillBeStarted()) return false;   ← SAME GATE
```

`extractedTextModeWillBeStarted()` is `imm != null && imm.isFullscreenMode()`
— i.e. **whenever the connected IME is serving the field in extract
(fullscreen) mode, Android 14 silently cancels the ENTIRE selection chain**:
no word-select, no drag handles, no floating toolbar with Select all / Copy /
Cut / Paste. Nothing else even runs.

And on Android 14 the IME decides extract mode **per field, from the
EditorInfo it is handed**: a MULTILINE field in a dialog window with no
`imeOptions` gets extract mode whenever the keyboard judges the available
window height insufficient — small screens, large font scale, landscape, and
most OEM/Gboard configurations. That is why every previous pass failed: the
gate is IME state, not theme, context, or window flags. A CSS/theme/
window-level fix cannot touch it.

A second, smaller finding: GP14P2's "belt-and-braces"
`setTextIsSelectable(true)` on the editable field is a **read-only-TextView
API** (its own AOSP comment: "mInputType should already be
EditorInfo.TYPE_NULL"). On an editable field it re-forces
ArrowKeyMovementMethod (already the default) and re-runs
`setText(mText, SPANNABLE)` after the inputType was configured — the class of
half-reconfigured editor state the previous passes kept hunting. An EditText
is selectable by default; the call added risk and fixed nothing.

### The fix

Two surgical changes at the exact layer the platform reads:

1. **`imeOptions = EditorInfo.IME_FLAG_NO_EXTRACT_UI`** on the composer's
   EditText — the documented per-field EditorInfo opt-out that tells the IME
   to NEVER enter extract mode for THIS field. With extract off,
   `isFullscreenMode()` stays false, the gate passes, and the platform's own
   long-press chain (word select → drag handles → floating toolbar) runs
   exactly as it does in every standard edit dialog. Honored by every
   conforming IME (Gboard, Samsung Keyboard, SwiftKey included).
2. **Removed `setTextIsSelectable(true)`** from the editable field (kept on
   the read-only viewer bodies in PART 2, where it belongs).

Plus the web-side twin, for the browser composer: GP9's comment claimed the
composer card "drops the GPU layer promotion", but its override only
neutralized the body's momentum scrolling — the card still computed
`transform:translateZ(0)` (a composited ancestor around the textarea, the
layer where WebView/Android-14 selection magnifiers misplace). The card is
now explicitly un-promoted
(`.text-ov .doc-viewer-card:not(.text-popup-card){transform:none}`), and the
reading popup keeps its own compositing for the FP4 rounded-clip fix.

### Verification status of PART 1 — the honest boundary

- ✅ Root cause verified against the actual Android 14 AOSP sources (fetched,
  read, cited by file:line).
- ✅ Compiles (`assembleRelease` BUILD SUCCESSFUL).
- ✅ The AOSP chain from `IME_FLAG_NO_EXTRACT_UI` → EditorInfo → IME extract
  decision → the failing gate is documented platform behavior.
- ❌ **NOT runtime-verified on an Android 14 device or emulator — no such
  environment exists in this sandbox.** The interaction checklist (long-press
  → toolbar; Select All; Copy; Paste; Cut; handles; partial selection;
  keyboard-open resilience; post-send persistence) requires a physical
  Android 14 device. The fix is implemented with high confidence from the
  source trace, but **the pass does not claim device confirmation.**

## PART 2 — History/Queue text-message viewer

**History (`TransfersFragment.showTextMessageDialog`)** — rebuilt as a
three-region modal:
- **Region 1** — header: icon chip + "Text message" + ✕ (the viewer's own
  close, plus the scrim tap and Back).
- **Region 2** — the message body in its OWN bounded vertical ScrollView
  (60% of screen height, 240dp floor): long messages finally scroll, the
  content is verbatim (line breaks/whitespace/Unicode intact), and the body
  is `setTextIsSelectable(true)` — native long-press word-select, drag
  handles and the platform Copy toolbar, with Paste/Cut naturally inapplicable
  on a read-only body. Selection gestures coexist with scrolling by platform
  design (the scroller owns drags past the slop; AOSP's
  ArrowKeyMovementMethod requests disallow-intercept for the handle drags).
- **Region 3** — the existing actions pinned: Copy (completed rows only,
  exactly as before) + Close.

The old shape (whole message in LdxDialog's plain unbounded TextView) is what
cropped long messages permanently.

**Queue (`TextContentViewer`)** — the same selectable-body treatment added to
the existing sheet (its three-region structure and bounded scroller were
already correct); the header's drag-to-dismiss region keeps its
control-aware gate (`interactiveAt` declines presses on focusable/selectable
children), so a selection press never drags the sheet.

**Web popup** (`index.html` text viewer) — already three-region since FP4;
unaffected and re-verified.

## PART 3 — ZIP-streamed History cards: X removed

`buildActionButtons`' History branch now renders the per-row ✕ only for
`!t.isZipStream` records. Rationale (documented in the code): a streamed-ZIP
record spans a whole archive batch — an individual ✕ that reads as "delete
one file" but deletes the whole batch's record is the redundant-and-
misleading control this pass removes. Removal flows through the existing
Hold & Select (long-press entry, range/Hold & Slide, Delete Selected) and
Clear History, all untouched. Non-ZIP History records keep their ✕; the
Active tab's Cancel/Resend ✕s are untouched.

## PART 4 — Received card redesign (visual refinement)

The shared shell (`createRowShell`) got the polished production rhythm:
- Card padding 12/9 → **14 horizontal / 10 vertical**; card gap stays 6dp.
- Thumbnail 40dp with a **12dp gap** (one shared thumbnail size across both
  tabs); a 1dp optical start margin aligns it with the card's rounded corner.
- Title 12.5sp → **13sp** (the card's protagonist), still one line, MIDDLE
  ellipsis + FP5's `displayTitleFor` head…tail pre-shortening (extensions
  stay identifiable); the weighted column guarantees the ⋮ can never be
  pushed or overlapped by a long title.
- Metadata row: the FP5 0.7-alpha double-dimming removed (the palette's own
  `text_muted` is the right gray), 10.5sp, now with a consistent **3dp**
  title→metadata gap.
- The ⋮ touch target widens 30dp → **32dp**, end-aligned to the card's
  padding rhythm; still one permanent shell child (visibility-only rebinding,
  no stale-recycling class), still top-right of the TITLE row.
- **Open With's glyph** changed from `ic_android` (a robot that read as an
  app/APK action) to **`ic_arrow_forward`** (the outgoing "hand this to
  another app" arrow). Behavior unchanged.
- All functionality preserved: card click (internal players / APK install /
  ACTION_VIEW), the ⋮'s Share / Open With / Show in History / Rename, the
  FP5 rename position/timestamp rules, selection behavior, ordering.

## PART 5 — History card redesign (distinct layout)

- **Direction wording**: History rows now render the requirement's explicit
  phrasing — **"Received from [Device]"** / **"Sent to [Device]"** — through
  a new `history` flag threaded through `detailLine`, `groupDetailLine`,
  both ago-ticker closures and both payload-patch paths. The Active tab's
  live rows keep the compact From/To forms (byte counters dominate there);
  the Received tab keeps hiding the segment entirely (FP5's rule).
  Device identities come from the records' own `peerName` (the same
  `ReceivedFileMeta`/HistoryStore data as before — nothing invented).
- **No three-dot menu**: History never renders the ⋮ (the shell slot is
  gated to `selTab == "received"`; all three non-qualifying bind paths —
  text rows, selection mode, group rows — retire it). Verified in source.
- History's information hierarchy (thumbnail / title / metadata line of
  time-ago · size · direction) rides the same shell, so the shared rhythm in
  PART 6 applies automatically; the direction badges and Hold & Select
  system are untouched.

## PART 6 — Shared visual consistency

One scale across both tabs: 40dp thumbnails + 12dp gap, 14/10 card padding,
6dp card-to-card spacing, 13sp titles with MIDDLE ellipsis, 10.5sp muted
metadata, 32dp ⋮ touch targets, the existing card background/corner radius
tokens (`bg_btn_secondary`), and the same selection visual. Tab-specific
presentation is explicit per-flag (hidePeer/history/menu-slot/badge), never a
global removal.

## Files modified

| File | Changes |
|---|---|
| `app/src/main/java/com/lumidrop/x/SendFragment.kt` | PART 1: `IME_FLAG_NO_EXTRACT_UI` + `setTextIsSelectable` removal on the composer field; the AOSP-trace documentation on the modal's architecture comment |
| `app/src/main/assets/index.html` | PART 1 (web twin): the composer card's `transform:none` override (`.text-ov .doc-viewer-card:not(.text-popup-card)`); CSS/JS re-validated (761/761 braces, both script blocks parse) |
| `app/src/main/java/com/lumidrop/x/TransfersFragment.kt` | PART 2: `showTextMessageDialog` rebuilt as the three-region selectable-scrolling modal; PART 3: ZIP-stream ✕ skip; PART 4: shell geometry (padding/thumb/gap/title/⋮/metadata) + Open With icon; PART 5: the `history` wording flag through `detailLine`/`groupDetailLine`/`registerAgoTarget`/`registerGroupAgoTarget`/`patchProgressViews`/`patchGroupProgressViews` and their call sites |
| `app/src/main/java/com/lumidrop/x/ui/TextContentViewer.kt` | PART 2 §2.3: selectable body in the Queue text viewer |
| `app/build.gradle` | versionCode 78 + the pass notes |
| `app/src/test/.../FinalPass6PresentationContractTest.kt` | NEW — 6 tests: the History wording contract, the Received/Active forms it must not change, blank-peer rules, the ZIP-stream ✕ predicate, and the shared card-rhythm constants |

## Verification performed (labeled honestly)

| Layer | Result |
|---|---|
| Root-cause trace (PART 1) | Verified against the fetched Android 14 AOSP sources — every gate in the chain read and cited by file:line |
| Standalone JVM suite (kotlinc + JUnit) | **205/205 intended-green** — the single suite-run failure was `PipelineIOTest`'s timing-sensitive flash-death thread test, which passes 3/3 in isolation (a known-flaky pre-existing test, unrelated to this pass's changes — TransferManager/HistoryStore are untouched); the new FP6 class passes 6/6 |
| Gradle `testReleaseUnitTest` | **220/220 pass, 0 failures** (the full project suite, engine + ui, through the real build pipeline) |
| Gradle `assembleRelease` | **BUILD SUCCESSFUL** — signed APK, apksigner-verified, versionCode 78, minSdk 21, `assets/index.html` embedded |
| WebUI structure | CSS braces balanced (761/761), both inline script blocks parse under `new Function`, all seven text-modal functions present |
| Emulator / physical device | **NOT PERFORMED — no device or emulator exists in this sandbox.** Every runtime-interaction claim above (especially PART 1's Android 14 long-press/toolbar/copy/paste/cut and PART 2's touch selection) is source-trace + compile + JVM-test level and is explicitly **not** runtime-confirmed. |

## Known limitations

- PART 1's fix is high-confidence from the AOSP trace (the gate's dependence
  on `isFullscreenMode()` and `IME_FLAG_NO_EXTRACT_UI`'s documented effect on
  it are both platform source, not speculation), but a non-conforming IME
  that ignores `IME_FLAG_NO_EXTRACT_UI` could still force extract mode; the
  flag is honored by the mainstream keyboards, and honoring it is part of the
  CDD, but only a device test closes that last mile.
- The flaky `PipelineIOTest` (pre-existing, thread-timing under a loaded
  shared JVM) remains as-is; it is green in isolation and green through
  Gradle.
- The Queue text viewer's header remains a drag-to-dismiss region; presses
  that begin ON the selectable body never drag the sheet (the control-aware
  gate declines them), but a drag that begins on the header and travels over
  the body still dismisses — the standard sheet contract, unchanged.

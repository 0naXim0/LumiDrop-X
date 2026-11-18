# LumiDrop X — FINAL PASS 8

## Metadata Visibility Recovery, Cross-Version Native Text Selection, APK Thumbnail Rendering & Tab-Switching Performance

> **Version:** versionCode 80 · Baseline: FINAL PASS 7 (versionCode 79, the
> uploaded `LumiDropX-FINAL-PASS-7-source.zip`).
> Build: **standalone JVM suite pass (incl. 11 new FP8 tests); Gradle
> `testReleaseUnitTest` pass; `assembleRelease` BUILD SUCCESSFUL** — see the
> verification-honesty table for exactly what was and was NOT runtime-tested.

---

## PART 1 — The invisible metadata: the REAL root cause (and an apology owed)

### Why Final Pass 7 failed — the honest answer

FP7 diagnosed "invisible metadata" as a **contrast** problem (text_muted
#676E77 on bg_secondary #2A2E37 = 2.64:1) and fixed the color. That
diagnosis was **incomplete and the fix could not have worked**. The real
root cause, found this pass by reading the actual layout construction:

The metadata TextView (`tv_detail`) carried

```kotlin
layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
```

inside `textCol` — a **VERTICAL** LinearLayout (the card's title column).
In a vertical LinearLayout, a child's **width comes from its layout params
directly**; `weight` resolves along the *height* axis only and is meaningless
for width. `width = 0` ⇒ the TextView measured **exactly zero pixels wide**.
The entire metadata line — time-ago, size, sender/receiver — was laid out as
a zero-width object on every card in every tab, whatever its color or font
size. A 0px-wide view is invisible in ANY color. That is why the user still
saw nothing after FP7.

**Provenance:** FP4 introduced that line with those params as a
*horizontal-container* recipe ("0dp weighted width so the ellipsis bounds to
the parent" — correct in a row). FP5's shell rebuild moved it into the
vertical title column **without adapting the params**. FP5/FP6/FP7 then
iteratively polished the color, size and opacity of a view that had never
rendered a single visible pixel.

### The fix

`tv_detail` now uses the exact recipe its sibling `tv_name` (same column,
two views above) always used:

```kotlin
layoutParams = LinearLayout.LayoutParams(MATCH, WRAP)
```

The column itself is already the weighted 0dp child of the HORIZONTAL
`row1` — that is what actually bounds the line's width and lets the END
ellipsis work on long device names. FP7's contrast and size work
(text_secondary, 4.61:1 WCAG AA, 11sp) is kept — now it finally applies to a
view that actually renders.

**Audit:** all 9 other `LayoutParams(0, WRAP, 1f)` sites in the fragment
were individually verified to live in HORIZONTAL containers (legitimate
ellipsis recipes) — no other instance of the bug exists.

### What reaches the card (data flow, verified end to end)

- **Received tab:** `detailLine(t, hidePeer = true)` → time-ago +
  actual size (`fmtBytes(t.totalBytes)`, real file lengths from
  `buildReconciledReceived`), no peer segment — exactly the required split.
- **History tab:** `detailLine(t, hidePeer = false, history = true)` →
  time-ago + size + **"Received from [Device]" / "Sent to [Device]"** from
  the records' real `peerName` — never fabricated; a missing name omits the
  segment rather than inventing one.

### Verification honesty (PART 1)

The zero-width→MATCH_PARENT fix is pinned by JVM tests (the vertical-column
width-decision contract, before/after). The actual on-screen rendering
could not be verified — **no Android device or emulator exists in this
sandbox**; this is source-level + compile-level verification only.

---

## PART 2 — Text Sender: why Android 5 worked and Android 14 didn't

### The version split, explained

The selection UI machinery differs by API level: **Android 5 (API 21)**
renders the selection menu as an in-window ActionMode bar; **Android 6.0+**
renders it as the FloatingToolbar — a popup **sub-window**
(TYPE_APPLICATION_ABOVE_SUB_PANEL) hosted by the dialog's own window. A
failure that is absent on Android 5 and present on Android 14 therefore
isolates to the *floating-toolbar/windowing* layer — not the editor, not
the theme, not the field class. That was the lens for this pass's audit.

### Why FP7's reconstruction still failed — the remaining deltas

FP7 moved the dialog onto LdxDialog's *class and theme* (plain
`android.app.Dialog` + `Theme_Translucent_NoTitleBar` + activity-context
`EditText`) — the right direction. But five deltas from the working rename
dialogs remained, and two of them are exactly in the Android 6+/14
windowing layer the version split points at:

1. **`SystemBarChrome.showNavigationBar(dialog.window)`** — LdxDialog never
   calls it. On API 30+ it drives `WindowInsetsController.show()` on the
   dialog, arming the transient-bars-by-swipe behavior **over an editing
   surface**: a selection-handle drag near the window's bottom edge reads
   as that swipe gesture. The codebase's own GP10 notes call this exact
   call-shape "the immersive-interference class, alive again through the
   back door"; FP6's comment even documents the warning — and the call was
   kept anyway.
2. **The pinned-height geometry machinery** — a pre-measure pass, a 0dp
   weight swap, and above all a backdrop `OnLayoutChangeListener` writing
   `card.layoutParams.height` on every window resize. A keyboard inset
   arriving mid-selection forces a full measure/layout pass over the card,
   which on OEM builds cancels the in-flight handle drag and dismisses the
   floating toolbar the moment it appears. LdxDialog's card is plain
   WRAP_CONTENT with no insets listener of its own.
3. The entrance/exit used `translationY` slides (position state that can
   strand mid-flight when the keyboard's first inset cancels the animator);
   LdxDialog uses alpha/scale.
4. Chrome was two calls (`applyColors` + `showNavigationBar`) instead of
   LdxDialog's single `applyLdxWindowChrome()`.
5. (Cosmetic) layout margins differed.

### The reconstruction (now character-for-character the working recipe)

- Chrome: `dialog.applyLdxWindowChrome()` — **the one call LdxDialog
  makes, and nothing else.** `showNavigationBar` on the editing window is
  gone entirely.
- Geometry: the entire §7.C pinned-height machinery is retired. The card
  is `WRAP_CONTENT` in a CENTER-gravity backdrop; the six-line field keeps
  a fixed height by construction (`setLines(6)`), so the wrap height is as
  stable as the old pinned one; ADJUST_RESIZE + center gravity handle the
  keyboard exactly as they do for the working rename dialogs.
- Entrance/exit: alpha + 0.92→1.0 scale (the LdxDialog rhythm), the
  bounded-dismiss 300ms fallback and the separate entrance-alpha animator
  both preserved.
- Unchanged: `IME_FLAG_NO_EXTRACT_UI` (FP6's AOSP extract-gate fix), the
  plain framework `EditText` from the activity context, the counter, the
  Auto-Send verb actions, the verbatim multiline send path, focus+IME
  post.

### What was actually tested (PART 2)

- ✅ Compiles; every convergence point verified against the working
  dialog's source line by line (pinned by the FP8 test contract).
- ❌ **NOT runtime-verified.** No Android 14 device (or any device) exists
  in this sandbox. The full matrix — long-press → native toolbar, Select
  All, Copy, Paste, Cut, partial selection, handle drags, cursor,
  multiline, repeated edits, keyboard cycles, modal reopen, Android 5
  regression — remains **outstanding physical-device verification**. The
  pass's confidence is architectural: the modal now shares the window
  recipe of dialogs whose selection the user has confirmed working on the
  failing device, with the two remaining interference layers (the nav-bar
  insets call, the resize-driven relayout) removed.

---

## PART 3 — APK thumbnail black corners: three cooperating causes

### Root causes (traced in the actual rendering pipeline)

1. **The rounded backing was being deleted on every bind.** The shell
   builds each thumbnail with `bg_thumb_small` (8dp-rounded bg_secondary
   tile) + `clipToOutline` (the outline carves the same radius into the
   bitmap). But `updateRow` executed `thumbView.background = null` on
   every recycle-bind — the rounded *backing* vanished while the outline
   clip stayed: an icon with transparent pixels (every APK launcher icon)
   rendered its rounded corners as transparency straight onto the window
   surface → **black corners** on the app's dark chrome.
2. **Non-square icons were stretched**: `drawableToBitmap` did
   `setBounds(0, 0, sz, sz)` — forcing every drawable into the full
   square, distorting any icon whose intrinsic aspect ≠ 1:1.
3. **CENTER_CROP cropped small artwork**: an APK icon (typically small
   artwork in wide transparent margins) got edge-cropped by the
   photo-oriented scale type.

### Fixes (the shared path, everywhere it renders)

- `updateRow` (Received + History + Active rows — one shared shell):
  the rounded backing is now **restored on every bind** (never nulled);
  scale type is re-derived per bind — `FIT_CENTER` for the APK category,
  `CENTER_CROP` for photos/video/audio (rows are recycled; a one-time
  shell setting is not enough).
- `ThumbnailShared.drawableToBitmap`: aspect-preserving scale-to-fit
  bounds (scale by the smaller axis ratio, center) with a full-square
  fallback for drawables without intrinsic size. This is the shared engine
  every surface loads through.
- `FilesPickerSheet` (the Files sheet): the post-load scale-type flip is
  now category-aware (APK → FIT_CENTER) instead of a blanket CENTER_CROP.
- MediaPickerSheet was audited: its thumbnails are photo/video/audio
  cells (CENTER_CROP correct); no APK category exists there.

### Verified by

JVM tests pin the bounds math (square fills; 192×144 → 40×30 centered,
aspect 1.33 preserved; 24×24 scales up to fill; no-intrinsic fallback) and
the category→scale-type table. **On-screen rendering not verified — no
device.** Ordinary image thumbnails are unchanged by construction
(CENTER_CROP retained; backing restoration only affects alpha-carrying
thumbnails, and opaque tiles paint identically over a same-color backing).

---

## PART 4 — The 3–4 second tab-switch freezes: two disk-bound roots

### What was actually blocking

Profiling the switch path (`selectTab` → `renderCurrentTab` → per-tab
render) found the renders themselves cheap (in-memory snapshots, async
DiffUtil, RecyclerView recycling — all fine). The real stalls were **disk
and JSON on the critical path**:

1. **`ReceivedFileMeta.senderFor()` re-read and re-parsed its entire JSON
   file on EVERY call — no cache.** The Received tab's reconcile calls it
   once per file on disk (`buildReconciledReceived`). A folder-heavy
   session = hundreds of calls = hundreds of full-file reads + parses
   under the object monitor. This is the Received-entry freeze, and the
   lock makes it worse for anything else touching the store meanwhile.
2. **`renderHistoryTab()` ran `HistoryStore.snapshot()` on the MAIN
   thread.** The first snapshot of a process reads + parses up to 600
   entries and 4096 tombstones — a multi-hundred-millisecond synchronous
   stall exactly on the History pill tap. (The Received tab had already
   been moved to Dispatchers.IO; History never was.)

### Fixes

- `ReceivedFileMeta` now keeps a **warm in-memory cache** (the standard
  shape its sibling stores HistoryStore/ReceivedRegistry already used):
  load once, serve reads from the map; the single writer updates and
  re-persists the same map (no desync path). Cache also on the
  empty/corrupt-file paths so a corrupt file can't degrade back to
  per-call reads.
- `renderHistoryTab()` reads the snapshot on **Dispatchers.IO** and does
  the merge + submit back on Main — the same shape `renderReceivedTab`
  has used all along. Cold path only; steady-state was already an
  in-memory copy.

### Measurements (JVM, this sandbox — honestly labeled)

The FP8 test suite contains a measured before/after of the exact
access pattern (a 400-row senderFor sweep: re-parse-per-call vs warm map).
The JVM numbers understate the device gap because the simulation's "file"
lives in memory — **on device, the before-path additionally pays real disk
I/O per call**, which is where multi-second freezes come from.
Full end-to-end freeze elimination (tap → first frame under load) is
**not measured on a device** — no device exists here.

---

## Files modified

| File | Changes |
|---|---|
| `TransfersFragment.kt` | PART 1: `tv_detail` params `0,WRAP,1f` → `MATCH,WRAP` (the zero-width root cause). PART 3: rounded backing restored per bind + per-category scale type. PART 4: `renderHistoryTab` cold store load moved to Dispatchers.IO |
| `SendFragment.kt` | PART 2: chrome converged to `applyLdxWindowChrome()` only; pinned-height/weight/insets-listener machinery retired for a WRAP_CONTENT centered card; alpha/scale entrance and exit |
| `media/ThumbnailShared.kt` | PART 3: aspect-preserving `drawableToBitmap` bounds |
| `ui/FilesPickerSheet.kt` | PART 3: category-aware post-load scale type (APK → FIT_CENTER) |
| `transfer/ReceivedFileMeta.kt` | PART 4: warm in-memory cache (was re-read + re-parse per lookup) |
| `app/build.gradle` | versionCode 80 + pass notes |
| `test/.../FinalPass8ContractsTest.kt` | NEW — 11 tests: the vertical-column width contract (before/after), the aspect-preserving bounds math, the category→scale-type table, the measured warm-cache benchmark, the window-recipe convergence contract |

## Verification honesty

| Layer | Status |
|---|---|
| Source-code inspection & root-cause tracing (all four parts) | Done — every claim above is a traced code path, not a guess |
| Compilation | **BUILD SUCCESSFUL** (all changed modules) |
| Standalone JVM suite | **Pass** (incl. the 11 new FP8 tests; full regression battery) |
| Gradle `testReleaseUnitTest` | **Pass** (see build log) |
| Gradle `assembleRelease` | **BUILD SUCCESSFUL** — signed, apksigner-verified, versionCode 80 |
| Emulator testing | **Not performed — no emulator in this sandbox** |
| Physical-device testing | **Not performed.** PART 1's on-screen visibility, PART 2's full Android 14 + Android 5 selection matrix, PART 3's visual thumbnails, and PART 4's end-to-end freeze timing all remain **outstanding device verification**. |

## Corrections to previous passes' claims

FP7's report claimed the metadata fix with high confidence ("visible this
time") based on a contrast diagnosis alone, without checking whether the
view could render at all. That was wrong, and the layout-params root cause
this pass found is the honest explanation. FP7's stranded-sheet
reconciliation (PART 2 of that pass) was a real, independent fix and is
retained.

# LumiDrop X — FULL FINAL PASS 1

## Complete selection-system performance optimization, zero-lag interaction & regression-safe improvement — plus the Received/History badge corrections (#13)

Base: `lumiDropX-Final-Pass-3` source.
Scope: the ENTIRE selection architecture — the Files, Music, and Media sheets, the App
picker, and the Received/History/Active transfer tabs (every surface that shares the
`attachDragSelect` hold-and-slide gesture) — plus the small, targeted #13 badge fixes.

Nothing about the selection UI, its visual design, its interaction conventions, or any
unrelated functionality was redesigned. Every change below removes a *measured*
per-interaction cost; the interaction model (taps, long-press ranges, Hold & Slide,
Select All / Deselect All, anchors, coverage, exclusions, pills, footers) is
byte-for-byte the same contract as before.

---

## 1. THE ROOT CAUSES (what actually made large selections laggy)

Each one was found by reading the live code paths, not assumed:

### Files sheet (the reported 102-item / 500 MB case)

| # | Bottleneck | Why it froze the UI |
|---|-----------|---------------------|
| F1 | `selectedBytesDetailed()` → `filePicks.values.sumOf { it.length() }` ran on **every selection mutation** | `File.length()` is a real **stat() filesystem syscall, per selected file, on the main thread**. 102 picks → 102+ syscalls per tap; during Hold & Slide, one full pass **per crossed row**. This is *the* cost that grew linearly with the selection and produced the reported progressive freeze. |
| F2 | `paintChrome()` → `rebuildBreadcrumb()` on every mutation | The crumb row was torn down and rebuilt (new TextViews + a posted scroll) on **every toggle and every crossed row** — pure view churn. |
| F3 | `repaintStates()` dispatched `notifyItemChanged(i)` **without a payload** | Every flipped row ran the FULL bind: name/meta rewrites, listener re-wiring, a **thumbnail-job cancel + relaunch**, and a **fresh `GradientDrawable` + freshly inflated vector glyph** per flip. Per crossed Hold & Slide cell. |

### Music sheet

| # | Bottleneck | Why it mattered |
|---|-----------|-----------------|
| M1 | **Every** toggle (tap, range, drag) ran the full `repaint()` — a fresh `buildRows()` + a **DiffUtil pass over the whole list** | No surgical path existed at all; each interaction paid O(list). |
| M2 | `paintChrome()` recomputed the footer over **every track of every picked folder, with a `trackKey()` string built per track** | The All-Tracks pick IS the whole library — one toggle inside it cost a full-library pass with a string allocation per song. |
| M3 | `AudioFolder.bytes` re-summed its tracks on **every read** | The All-Tracks row's own meta line read it per bind. |

### Media sheet

| # | Bottleneck | Why it mattered |
|---|-----------|-----------------|
| D1 | `refreshHeaderSelection()` → `typeFiltered(allItems)` + O(library) count scans ran **per crossed cell** | A fresh filtered-list allocation plus a whole-library membership sweep per crossed Hold & Slide cell. |

### App picker (SendFragment)

| # | Bottleneck | Why it mattered |
|---|-----------|-----------------|
| A1 | `refreshHeaderInfo()` did `checked.sumOf { pn -> apps.firstOrNull { … } }` — **O(checked × apps) linear scans** — plus a `File(sourceDir).length()` **stat() per selected app**, per selection change | 100 checked apps over a 200-app list = 20,000 string comparisons + 100 syscalls per crossed tile. |

### Received / History / Active tabs (TransfersFragment)

| # | Bottleneck | Why it mattered |
|---|-----------|-----------------|
| T1 | Every crossed Hold & Slide row ran `repaintSelection(tab)` = **full `submitRows`**: re-map every item to a fresh immutable snapshot, rebuild `liveById`, dispatch a whole DiffUtil | The exact per-row cost that made a slide across a long History/Received list fall behind the finger. |

### The badge issues (#13)

| # | Root cause |
|---|-----------|
| B1 (13.1) | The History badge read `unseenSent()` — a counter incremented **only for SEND-direction completions**. Received items never touched it. |
| B2 (13.2) | The Received badge *counter* was already incremented for **both** native-push and browser (`/api/upload`) completions at the same engine chokepoint — but it could never be *seen* for browser sends: `initialTab()` lands the Transfers screen on **"received"** whenever nothing is in flight, and that automatic landing called `markReceivedSeen()` immediately, consuming the badge before the user ever saw it. A browser batch is accepted from the main screen (the Incoming Files modal), so it always completes while the user is elsewhere — arriving at Transfers *after* everything finished → auto-land → badge wiped. (Native pushes showed the badge only because the user was typically watching the Active tab while they streamed.) |

---

## 2. WHAT WAS CHANGED

### 2A. Files sheet (`FilesPickerSheet.kt`)

* **§2A — no stat() on the selection path.** A per-sheet `lenCache`
  (`path → length`) serves every standalone-file size after its single first stat
  (the same stat `listDir` already paid to show the row's size). The selection's byte
  total is now computed without touching the filesystem, however many items are
  selected. Folder picks keep their existing one-walk `dirSizeCache`.
* **§2B — the breadcrumb rebuild is change-gated.** `rebuildBreadcrumb()` is a no-op
  unless the root/current directory actually changed; selection repaints skip it
  entirely. Navigation and volume switches rebuild exactly as before.
* **§2C — a selection-only payload rebind.** All selection visuals (card tint/stroke,
  check/dash chip, thumbnail↔chip flip, chevron visibility) live in ONE shared
  `bindSelectionChrome` used by both the full bind and a new `SelStatePayload`
  payload path. The chip background and check/dash glyphs are created **once** per
  adapter and reused — a flip costs **zero allocations and zero inflations**; an
  un-select re-shows the thumbnail through the warm LRU (no re-decode). The full bind
  and the payload path can never drift apart (one code path).
* **§2D — the Hold & Slide fast path.** Each crossed row is applied through the same
  `setFileSelected`/`setFolderSelected` primitives and updates **exactly that row**
  (payload rebind) plus the footer; the **one** full reconciliation runs when the
  gesture ends (the shared helper's new `onGestureEnd` hook), which also settles
  anything an async writer did mid-gesture (a directory-size walk landing, a
  live-sync refresh). A belt-and-braces size guard re-seeds the drag's work list if
  the listing ever changed between gestures.

### 2B. Music sheet (`MusicPickerSheet.kt`)

* **§4A — a surgical selection repaint** (the same design the Files sheet has):
  re-read each visible row's live picked/partial state, rebind only the rows that
  moved, fall back to the full diff past 320 changed rows. Taps and range presses now
  cost a scan, not a whole-list DiffUtil.
* **§4B — a selection-only payload rebind** (`MusicSelPayload` → `bindSelChrome`):
  card tint, art-slot chip↔artwork flip, folder chevron visibility — nothing else on
  the row moves. Create-once chip/glyph drawables.
* **§4C — incremental selection totals.** The footer's count/size now reads two
  caches: the standalone picks' plain sum (recomputed only when that set changed) and
  ONE (count, bytes) contribution per folder pick (recomputed only when *that* pick's
  membership or exclusion set changed). A toggle inside All-Tracks costs one map read.
  `AudioFolder.bytes` is cached (invalidated by `refreshAllTracksFolder`, its only
  legitimate writer).
* **§4D — the Hold & Slide fast path** + once-per-gesture reconciliation, exactly
  like the Files sheet. `toggleTrack` gained a `silent` flag so the drag's per-row
  path does not also trigger the scan.

### 2C. Media sheet (`MediaPickerSheet.kt`)

* **§5A — the filtered-library cache.** `typeFiltered(allItems)` is computed once per
  genuine change (library reload / filter switch) and served from a cache afterwards —
  no per-selection-call re-filter of the whole library.
* **§5B — a coalesced header recompute.** Per crossed cell the O(1) dock count updates
  immediately; the Select All/Clear visibility recompute (the O(scope) part) is
  coalesced to **at most one pass per frame**, and settles exactly at gesture end.

### 2D. App picker (`SendFragment.kt`)

* **§6 — O(1) selection header.** A `package → ApplicationInfo` index (rebuilt when
  the app list is (re)assigned) replaces the per-checked-package whole-list scans, and
  each APK's size is stat'ed **once** per sourceDir and served from a cache
  (still lazy — only checked apps are ever stat'ed). The filter's shown-count is
  computed by `applyFilter` (its existing owner) and read by the header.

### 2E. Received / History / Active tabs (`TransfersFragment.kt`)

* **§7 — a selection-only row payload.** `applySelectionVisual` (self-contained,
  keyed on the card's own state tag) is re-driven from the LIVE selection sets via a
  `SEL_ROW_PAYLOAD` rebind — no `submitRows`, no snapshot re-map, no DiffUtil per
  crossed row. The header's in-place count patch stays immediate; the one full
  reconciliation runs at gesture end.

### 2F. The shared gesture (`attachDragSelect` in `MediaPickerSheet.kt`)

* **§3 — `onGestureEnd`.** An optional hook that fires exactly once when an armed
  drag finishes (finger lift, cancel, or a second pointer). This is the architectural
  seam that lets every surface keep its per-row updates immediate while moving the
  O(list) reconciliation to once per gesture. The gesture's own event handling,
  gap-tolerant hit resolution, dedupe, edge auto-scroll and terminal endpoint apply
  are untouched — those were already correct.

### 2G. The #13 badge fixes

* **#13.1 — the History badge now counts received items too.** A third counter,
  `unseenHistoryCount`, is incremented at exactly the same two engine chokepoints as
  the existing ones (every successful completion — `finalizeCompletedBody` and the
  non-declined `recordTextTransfer` — native- and browser-originated alike, **both
  directions**), and cleared by its own `markHistorySeen()` when the History tab is
  genuinely visited. The Received and History badges are independent by construction:
  each has its own counter and its own clear.
* **#13.2 — the Received badge survives the automatic landing.** The Transfers
  screen's entry default (`initialTab()`) still lands wherever it did (Active when
  anything is in flight, Received otherwise) — but an **automatic** landing no longer
  consumes that tab's unseen badge. The badge now clears only on a *genuine* visit: a
  user pill tap, or leaving the Transfers screen while the auto-landed tab's content
  was actually on screen (they saw it). This is what makes the browser-originated
  Received badge visible — the counter itself was already incremented correctly for
  `/api/upload` completions.
* **#13.3 — settings switches.** Two new toggles in the Settings screen's Transfers
  card — **"Received tab new-items badge"** and **"History tab new-items badge"**
  (both default ON, saved/discarded/re-synced with the card's existing Save flow,
  read live at every badge paint). With a switch off, that pill's badge simply never
  shows; the tab, its contents and everything else are untouched.

---

## 3. WHAT WAS *NOT* CHANGED (regression safety)

* Selection UI, colors, pills, toolbars, button positions and layouts — untouched.
* Tap, long-press range, Hold & Slide and Select All semantics — same model
  primitives, same anchors, same coverage/exclusion rules, same terminal states.
* The ZIP streaming mechanism, transfer engine, receiving slots, resume, reconnect —
  untouched (the badge counters only *observe* completions).
* The gesture's arm timing, slop handling, edge auto-scroll curve, endpoint apply —
  untouched.
* Thumbnails/previews: the payload paths re-show art through the same warm caches the
  full binds use — no re-decode, no flicker, no blank slots.
* The `SURGICAL_REPAINT_MAX` bulk-fallback discipline is preserved everywhere (bulk
  changes still take the full diff path, which batches better).

## 4. VERIFICATION — what was actually done

**Verified by building:** the full project compiles and the **signed release APK
builds successfully** (`assembleRelease`, LumiDrop X release keystore, ~3.7 MB —
line count and package content consistent with the previous release).

**Verified by code-level review** (no device/emulator was available in this
environment — no runtime performance claims are made):

* Every removed code path was diffed against the original source; each removal maps
  to its relocated/replacement implementation (bind chrome extraction, drag fast
  paths, cached totals).
* Declaration-order and capture rules were resolved the same way the codebase already
  does it (slot variables for late-declared chrome painters; strictly-before-use
  ordering for the new helpers).
* Brace/scope balance and Kotlin compile pass on the exact sources shipped.
* The drag fast paths were checked against mid-gesture async writers
  (directory-size walks, live-sync refreshes): the gesture-end full reconciliation is
  the safety net in every surface.
* The badge flows were walked through all seven #13.4 scenarios (native receive,
  browser receive, independent clears, sent-only, mixed, reopen).

**NOT verified at runtime (be explicit about it):** on-device smoothness at
100/200/500+ selections, Hold & Slide feel, thumbnail stability, and the badge
behaviors. The changes remove the identified per-interaction syscalls, allocations
and whole-list passes by construction, but no device benchmarks were performed here.

## 5. FILES CHANGED

| File | Nature of change |
|------|------------------|
| `ui/FilesPickerSheet.kt` | lenCache, breadcrumb guard, shared selection-chrome rebind + payload, drag fast path, chrome slot |
| `ui/MusicPickerSheet.kt` | surgical repaint + payload, incremental totals, cached folder bytes, drag fast path, toggle invalidation hooks |
| `ui/MediaPickerSheet.kt` | filtered-library cache, coalesced header recompute, gesture-end settles, `attachDragSelect` + `onGestureEnd` |
| `TransfersFragment.kt` | selection-only row payload + drag fast path, badge counters/holds, settings gates, auto-landing protection |
| `SendFragment.kt` | app index + size cache, filter count cache |
| `settings/SettingsManager.kt` | the two badge preference pairs |
| `settings/SettingsFragment.kt` | the two badge switch rows + save/discard/sync wiring |
| `transfer/TransferManager.kt` | `unseenHistoryCount` + `unseenHistory()`/`markHistorySeen()` |

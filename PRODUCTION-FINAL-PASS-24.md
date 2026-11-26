# LumiDrop X — PRODUCTION FINAL PASS 24

## Unified Selection Mode Refinement · Persistent Thumbnail Visibility · Minimal Selection Indicators · Cross-Screen UX Consistency

> **Version:** versionCode 96 · versionName 1.0.5 · Baseline: PRODUCTION FINAL PASS 23 (versionCode 95). Working base: the attached P23 source ZIP.
> **Test level legend used throughout:** [SRC] source inspection of the actual Pass-23 code · [WEB] the real web-client script executed in the Node DOM harness against the REAL renderers and selection functions · [JVM] real unit tests executed on the JVM · [COMPILE] full Gradle compile · [APK] release artifact built, signed and verified. No Android device or real browser exists in this sandbox — every claim below is labeled with what actually ran, and no device claim is made.

---

## 1. The audit — every selection-enabled surface, mapped [SRC]

The complete selection system is the Web Client's (`app/src/main/assets/index.html`): one shared state (`S.sel` keyed by stable item identity — file path, or `apk:`+package for apps — plus `S._si` info and `S.selMode`), one shared interaction layer (`toggleSel` / `clearSel` / `selectAllVisible` / `updateSelectionUI`), rendered per tab by keyed/virtualized builders whose `build`/`patch` both derive the `.sel` class from `S.sel` by identity. **The native Android UI has no selection mode** (audited: no selection state or checkmark UI in any Kotlin screen), and **the Transfers page's Received/History tabs have no selection mode** (audited: no `S.sel` usage in any transfer renderer — their rows are progress/status rows).

| Surface | Item markup | Identity key | Selected treatment before this pass |
|---|---|---|---|
| Images tab (grid + its search-filtered views) | `.img-cell` | path | **18px purple ✓-circle painted ON the thumbnail** (`.img-cell.sel::after`) + dark circle on EVERY cell in selection mode (`.sel-mode .img-cell::before`) |
| Files tab — grid view | `.grid-cell` | path | **18px purple ✓-circle painted ON the cell** (`.grid-cell.sel::after`) |
| Files tab — list view + storage-wide search | `.file-item` | path | container tint + ✓ in a dedicated leading column |
| Videos tab | `.media-row` | path | container tint + ✓ in the action slot |
| Music tab | `.track-row` | path | **no container tint** + ✓ in the action slot |
| Docs tab | `.media-row` | path | container tint + ✓ in the action slot |
| Apps tab | `.media-row` | `apk:`+pkg | container tint + ✓ in the action slot |

Selection mode is entered via the toolbar **Select** control (sort-bar pill on media tabs, `Select` button on Files) — there is no long-press entry by existing design (long-press on media is the platform's own callout; that behavior is unchanged this pass). `toggleSel` toggles one item by identity; `selectAllVisible` flips the whole visible list and re-labels to "Deselect All"; the counter is derived live from `Object.keys(S.sel).length` — one source of truth.

## 2. Root cause of the thumbnail-replacement complaint [SRC]

The grid surfaces drew their selection indicator as a pseudo-element **on top of the thumbnail**: `.img-cell.sel::after` / `.grid-cell.sel::after` rendered an 18px circle with a ✓ glyph at the image's corner, and `.sel-mode .img-cell::before` rendered a translucent dark circle at the same corner of **every** cell while selection mode was active. On a 4-to-a-row grid cell (~80–150px), that badge covers a meaningful corner of the photo — the user's reported "the thumbnail gets replaced with a checkmark": with visually similar images you could no longer tell WHICH photo was selected, and during multi-select every thumbnail carried corner clutter. The row surfaces had the opposite problem — no thumbnail treatment at all (only container tint + a check glyph in the action slot), and Music rows lacked even the container tint — so the app had two different selection languages, neither of them the thin-outline idiom of a polished product.

## 3. The unified selection visual system [SRC→WEB]

**The design** — one language across all seven surfaces: the original thumbnail or icon is never replaced, never covered by a glyph; selection is a **thin 2px accent ring** that hugs each thumbnail/icon container's own 7px corner radius, plus the existing subtle purple container tint where it already existed (rows), plus the existing dedicated check column (the action-slot affordance — functional, occupies no thumbnail space — retained). Implementation is **CSS-only**:

- The three on-thumbnail badge rules are **deleted outright** (`.img-cell.sel::after` badge, `.grid-cell.sel::after` badge, `.sel-mode .img-cell::before`, and the obsolete `.img-cell.sel::before{display:none}`).
- The ring is an absolutely-positioned, pointer-transparent overlay with `border:2px solid var(--acc)` and the container's own `border-radius:7px` on `.img-cell.sel::after`, `.grid-cell.sel::after`, `.file-item.sel .file-thumb-wrap::after`, `.file-item.sel .file-icon-box::after`, `.media-row.sel .media-thumb::after`, `.media-row.sel .file-icon-box::after`.
- `.file-icon-box` gains `position:relative` (the ring's positioning anchor; it contained only static content).
- `.track-row.sel` joins the container-tint family (`rgba(145,114,246,.1)`, same value as `.file-item.sel` / `.media-row.sel`). A row that is both playing and selected keeps every non-conflicting `.playing` rule (the ♪ indicator and accent track number); the two states remain visually distinct.

**Why an overlay border and not `outline`/`box-shadow` on the container:** an `outline` does not follow `border-radius` on legacy WebKit (iOS 10.3 — the oldest supported device), and an inset `box-shadow` on the container paints *below* its children — the absolute-positioned child image would cover it. The overlay pseudo-element composites over the image, follows the exact radius, adds zero layout (out of flow, `box-sizing:border-box`), never intercepts taps (`pointer-events:none`), and only paints when the `.sel` class is present. Purple-on-dark and purple-on-bright thumbnails both read (the accent `#9172F6` against either), and the container tint + check column carry the rest — §10's non-color-only distinction.

**What deliberately did NOT change:** the DOM, the JavaScript, the selection state model, `toggleSel`/`selectAllVisible`/`updateSelectionUI`, the keyed/virtualized builders, the dual-render action-slot idiom, the counter, and every existing action. The visual layer moved from badges to ring on the same zero-lag class-toggle path — selecting an item still only flips a class; no re-render, no re-fetch, no rebuild ever happens for a selection change.

## 4. State synchronization and identity — verified by executing the real code [WEB]

`webclient-harness/p24.js` (59 checks, all green) drives the REAL `renderImages`/`renderVideos`/`renderMusic`/`renderDocs`/`renderApps`/`renderFiles` (grid AND list views) and the REAL click paths (`imgClick`, `vidRowClick`, `musicRowClick`, `docsRowClick`, `appsRowClick`, `fileItemClick`, `gridItemClick`, `toggleSel`, `selectAllVisible`, `clearSel`, `updateSelectionUI`):

- **Thumbnail preservation (Tests A/B/D/F/I):** selecting and deselecting never swaps cell/row markup (A6), never rebuilds the cell node (A5), and **`thumbUrl` is never called during selection operations** — enter, 21 selects, select-all, deselect-all, exit measured zero thumbnail URL builds (B1); a same-list re-render reuses the same cell nodes through the vl pool (B3) and the selected node keeps its ring (B4).
- **Identity (Tests H, §7):** selection keys are the item's stable path (or `apk:`+package on Apps — C7); a full re-sort re-render re-keys every unit and the ring follows the ITEM, not the position (A13); the vl slot-key mapping resolves every rendered cell to its item (A1b/D1b).
- **Counter/state sync (Tests E/G, §6's regression-prevention list):** counter == `S.sel` size == DOM `.sel` count across rapid all-by-one selection (E1), interleaved deselection (E2), select-all/deselect-all (A10–A12, the counter covers the whole filtered list while the virtualized window shows its part — A11), and every "counter changed but visuals didn't" failure mode is asserted impossible: deselecting one removes exactly that row's ring and tint (C3), clearing leaves zero stale traces anywhere (A9/B2/E3).
- **Coexistence:** a Music row that is playing AND selected shows both states with the ♪ indicator intact (C4); the video play badge, album art, file-type icons and every existing action button behavior are untouched.

## 5. Performance findings [WEB+SRC]

The change is CSS-only; there is no new JS, no new DOM nodes, no new event work. Selection remains one class-list toggle + one counter update — measured in the harness: zero thumbnail re-requests (B1), zero node rebuilds (A5/B3), so no image decode, no network, no layout shift (the ring overlay is out of flow; the container's geometry is untouched), no allocations on selection. Rapid multi-select of every rendered cell then interleaved deselection stays exactly consistent with the counter and the state map (E1/E2/E3). The one new paint cost is a 2px border ring per SELECTED cell only — nothing paints on unselected cells now (the old design painted a dark circle on every cell during selection mode; the new design paints less).

## 6. Test plan results (§13) — what ran, honestly

| Test | Method | Result |
|---|---|---|
| A — Files thumbnail preservation (grid + list, image/folder/doc/audio items) | [WEB] real renderers + real clicks, markup/identity assertions | PASS |
| B — Music artwork/preservation (rows carry no artwork by design; audio-bar art untouched) | [WEB+SRC] | PASS |
| C — Received items | [SRC] audited: no selection exists on Received | n/a (nothing to preserve — nothing regressed) |
| D — History | [SRC] audited: no selection exists on History | n/a |
| E — Rapid interaction | [WEB] E1–E3 | PASS |
| F — Long-press entry | [SRC] no long-press entry exists by design (toolbar Select control); unchanged | PASS (unchanged) |
| G — Select all / deselect all | [WEB] A10–A12 + C-suite | PASS |
| H — Sorting/filtering identity | [WEB] A13 (re-sort) + search paths reuse the same builders | PASS |
| I — Thumbnail loading | [WEB] B1/B3 + the &v=size-mtime stable-URL system untouched | PASS |
| J — Performance (large lists, rapid scrolling, multi-select) | [WEB] 40-item grid battery; the vl windowing system untouched | PASS |
| Full web regressions | [WEB] accept.js 18/18, p22.js 22/22 | PASS |
| Full JVM battery | [JVM] 409/409 × (release+debug) | PASS |

**[Honest limit]** No physical Android device or real browser was available: the ring's pixel appearance was verified by source inspection, CSS-rule pins and executed-renderer assertions, not visually on a screen. The device-side checklist: select/deselect on each tab, long-press platform menus unaffected, small-screen (3-column) grid ring appearance.

## 7. Android and legacy-device compatibility [SRC+APK]

- The native app is untouched except `versionCode 96` / `versionName 1.0.5`; API 21–36 compatibility is structurally unchanged (no new dependencies, no manifest change — the JVM provider/manifest pins still pass).
- Legacy Web Client (iPad 4 / iOS 10.3.3): the ring uses only absolute positioning, `border`, `border-radius`, `pointer-events:none` and `box-sizing` — all supported on that engine for years; no `outline`-radius, no `:focus-visible`, no modern selectors. The prefixed-property discipline of the codebase is respected (the code emits its own prefixes where the codebase convention requires them; these properties need none on the supported set).
- **Artifact-level verification [APK]:** `aapt dump badging` → `com.lumidrop.x` vC96/1.0.5; the signing certificate SHA-256 is the official identity `1e397d74…1d66c95a` (v1+v2+v3 schemes verified — installs as an update over every 1.0.1+ installation); and the selection fix is provably INSIDE the built asset — the compiled `assets/index.html` was extracted from the signed APK and scanned (the old on-thumbnail badge rules are absent; every ring rule, the track tint and the icon-box anchor are present), then all three harnesses were re-run against that extracted built asset: **p24.js 59/59, accept.js 18/18, p22.js 22/22.**

## 8. Files changed

**Modified**
- `app/src/main/assets/index.html` — the PASS 24 selection CSS block: on-thumbnail badge rules deleted; unified 2px accent ring on `.img-cell.sel` / `.grid-cell.sel` / row-thumb containers; `.file-icon-box` + `position:relative`; `.track-row.sel` container tint. No JavaScript, no DOM, no markup changes.
- `app/build.gradle` — versionCode 96 / versionName 1.0.5 + this pass's comment block.

**Added**
- `webclient-harness/p24.js` — the 59-check selection acceptance battery (executes the real script; drives the real renderers, click paths, select-all, sort, rapid, node-reuse and thumbnail-preservation proofs).
- `PRODUCTION-FINAL-PASS-24.md` — this document.

## 9. Remaining limitations (honest)

1. No physical-device / real-browser visual verification (see §6) — all functional and structural assertions ran against the real client code in the DOM harness; the visual result was pinned by exact CSS rule checks.
2. Music rows carry no artwork by existing design (album art lives in the audio bar and the album strip) — "keep artwork visible" on Music is therefore vacuously satisfied and pinned by the C2–C4 checks (titles, artist, duration, ♪ indicator, tint).
3. Received/History selection does not exist; if a future pass adds it, it must follow the same ring language.
4. The ring's exact stroke width (2px CSS ≈ 1.5–2dp at typical densities) was chosen per the brief's recommended range against the existing 7px radii and 42/78px thumb boxes; it is a CSS constant, trivially tunable in one place if device testing asks for more weight.

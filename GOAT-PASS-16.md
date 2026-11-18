# LumiDrop X — GOAT PASS 16

## Installation Permission Flow · Instant Installer Presentation · Files Sheet Performance · Production-Readiness Audit

> **Version:** versionCode 88 · Baseline: GOAT PASS 15 (versionCode 87).
> Final-pipeline exit codes recorded at build time in `/tmp/gp16_final.log` (`TESTS_EXIT=` / `BUILD_EXIT=`).
> **Honesty up front:** no physical device, emulator, or the exact `Carnage+Wars_4.682_APKPure.xapk` exists in this sandbox. All work is **source-level inspection + JVM unit tests** (5/5 new battery, full-tree regression). The ~100 ms modal/tile targets are **not measurable here** — that is stated, not faked. All on-device criteria remain device-pending.

---

## 1. Root cause: the permission screen never opened (§1)

`XapkInstallFlow.launch()`'s pre-flight called `PackageInstallEngine.requestUnknownSourcesPermission(appCtx)` — **the APPLICATION context** — and `requestUnknownSourcesPermission` called `context.startActivity(intent)` **without `FLAG_ACTIVITY_NEW_TASK`**. Android throws for exactly that combination; the engine's `catch` swallowed the exception. The toast showed; Settings never opened. It "used to work" because the mid-install call site happened to pass the Activity context — the regression was introduced when the pre-flight was added with the app context.

**Fixes:**
- `requestUnknownSourcesPermission` is now context-shape-aware: Activity context → same-task launch as before; any other context → `NEW_TASK`. A package-URI failure (de-Googled ROMs) falls back to the global unknown-sources list; total failure surfaces an honest toast naming the exact setting to enable. No silent catch.
- **Permission-return resume (§1.2):** `PendingPermissionResume` — a single pending op (monotonic op id + file + callback). `MainActivity.onResume` rechecks the **real** `canRequestPackageInstalls()` state (never assumed): granted → the install modal reopens automatically (a toast says so); denied/unchanged → quiet, the user retries normally. **Consume-once** semantics make loops and duplicates impossible; **newest-wins** replacement handles the user tapping a different archive while one is pending. Process death drops the pending op — an honest cold start. **Both** permission gates (pre-flight and the mid-install `NeedsUnknownSourcesPermission`) register the same resume, so resuming after a mid-install grant reopens the modal with the archive already inspected — one tap of Install inside it.
- Pre-O devices never reach the permission path at all (the gate is O+), unchanged.

## 2. Root cause: the 3–4 s install modal (§2)

`launch()` ran `PackageInspector.inspect(file)` **to completion before** `showInstallDialog` — on a 504.9 MB archive that is central-directory reads + per-component nested-manifest inspection, 3–4 s of nothing between tap and first pixel.

**Fix — the progressive modal:**
- `showInstallDialog` now builds and shows the dialog **synchronously** (pure view construction, zero I/O): file name as the title, the format vector as a placeholder icon, an honest **"Inspecting package…"** line (no fabricated package/version/component data), Install **disabled** ("Inspecting…"), with a min-height reserve so the card does not jump when the real report lands.
- Inspection then runs on `Dispatchers.IO` behind a **monotonic dialog-op guard** — a stale inspection can never fill a newer dialog (dismiss + tap another archive), and a dismissed dialog is never touched (`isShowing` checked).
- The **same** dialog fills in place when the report lands: real lines (the exact GP13/GP14 set via `buildInfoLines`, version label included), real title, icon extraction armed (declared icon → base APK → shared package-archive pipeline, all on IO), Install enabled **only** if `report.isInstallable`. `NOT_A_PACKAGE` converts the same modal into the honest unsupported-package state — no second popup, no flash.
- **§2.4 cache:** bounded LRU(16) keyed `path|mtime|size` — a second tap or the Files row after the Received row of an unchanged archive hits without rescanning; a replaced archive (same name, different bytes) misses by construction (pinned by test).
- **§2.5 large-archive bounds (already present, re-verified):** inspector streams (never loads archives into memory), manifests bounded at 8 MB per entry, the base APK is identified by its own manifest, and the thumbnail path's temp-copy is now bounded at 64 MB with icon.png streamed first (below).
- GP15 modal semantics preserved verbatim: 0.55 dim, full-screen backdrop with outside-tap dismissal (armed only pre-install), quiet user cancellation, Open/Close success modal.

## 3. Root cause: the intermittent ~1.5 s Files-sheet freezes (§3)

The freeze was not in the click handler itself — it was **storage-bandwidth saturation starving the main thread's own disk syscalls**:

`ThumbnailShared.packageArchiveIcon` extracted the **base APK to a temp file before ever looking at icon.png** — up to **500 MB was allowed** (Carnage's base is 51 MB). Scrolling a directory of package archives launched up to 3 concurrent copies (the decode gate is `Semaphore(3)`): 150+ MB of disk flood. On flash storage under that flood, the main thread's synchronous `lastModified()` in `FilesSheetCache.get()` (every serve) and the `ExternalVolumes.mounted()` probe (stats every mounted root, ran on **every** navigation via `refreshVolumes()`) could stall hundreds of milliseconds each — an intermittent ~1.5 s freeze whose timing depended on what the background decode queue happened to be doing. First-open was worst (cold thumbnail cache); warm paths with cached thumbnails mostly dodged it — matching "sometimes".

**Fixes (cause, not camouflage):**
- `packageArchiveIcon` order reversed: the **declared icon (manifest's icon entry or root icon.png) is read first as a bounded stream** — typically tens of KB, so a Carnage-shaped XAPK never copies its 51 MB base at all; the base-APK temp-copy runs only when no declared icon exists AND the base is **≤ 64 MB** (was 500 MB). Beyond that, the honest vector fallback — a 40dp thumbnail is never worth a 100+ MB copy.
- `FilesSheetCache.get()`'s serve-time stat is **memoized 2 s** (bounded map) — off the hot path entirely; freshness is still reconciled by the LibrarySync hook and the next serve past the window.
- `refreshVolumes()` is **throttled to 5 s** — removable volumes don't appear in 5 s, and a card PULL is already handled instantly by `load()`'s `dirAlive` recovery.
- Audit extra (§4.3): a rapid double-tap on an archive row could stack two install dialogs — a **one-live-dialog gate** now makes the second tap a no-op.

## 4. Production audit findings (§4)

| Area | Finding | Action |
|---|---|---|
| Main-thread I/O | The two stalls above were the genuine ones; `dirTotal` walks already run on IO; `cachedFileLen` is a session cache; sort prefs use `apply()` (async) | Fixed (§3) |
| Memory | Thumbnail decodes are bounds-first/scaled; disk cache pre-scaled; inspector streams; no unbounded bitmaps found on the touched paths | None needed |
| Races | Double-tap dialog stacking (fixed above); inspection staleness (fixed by op-id); search supersession already serial-guarded (GP15) | Fixed |
| Rendering | Large-list `notifyDataSetChanged` path already threshold-tuned (GP9); no DOM thrash found in the web client's SSE nudge path | None needed |

## 5. Test results (JVM)

| Suite | Result |
|---|---|
| `GoatPass16ProductionTest` (5) — resume state machine (single-slot, newest-wins, consume-once, quiet no-op); inspection-cache key (replaced same-name archive MISSES, untouched HITS, real on-disk XAPK); thumbnail cost contract (declared icon present+bounded, base under bound, real ZIP); version-label spot checks | **5/5 PASS** |
| Full tree (GP0–GP16) | final pipeline `TESTS_EXIT=` in the build log |

One test-fixture construction bug was caught and fixed during development (`ZipOutputStream(f.outputStream().use {})` — a `use`-lambda type error, not a product issue).

## 6. Verification honesty table

| Layer | Status |
|---|---|
| Source inspection & root-cause traces (all three reported issues) | Done — each literal in code |
| Compilation | PASSED |
| JVM unit tests | GP16 suite 5/5; full-tree exit in the build log |
| APK build | Final pipeline (`BUILD_EXIT=`), versionCode 88 |
| Timing measurements (modal < ~100 ms, tile < ~100 ms) | **NOT PERFORMED** — no device/emulator in this sandbox; the architectural guarantee (zero I/O before `dlg.show()`) is the deliverable claim, on-device timing remains to be measured |
| On-device: Settings screen opening, grant→auto-resume, skeleton→fill feel, freeze disappearance, Carnage/LAC install + launch | **NOT PERFORMED** — device-pending |
| Known limitations | (1) Resume is one-deep (the newest pending install wins) — by design, one dialog at a time. (2) The 2 s stat memo and 5 s volume throttle trade a sliver of freshness for the hot-path removal; both are reconciled by existing hooks. (3) `.apkm` proprietary compression still honestly unsupported. (4) SAF-bridged archives still copy once into cache while the modal is open. (5) The mid-install permission resume reopens the modal rather than re-firing Install automatically — the user confirms with one tap inside it (deliberate: Android requires the explicit install confirmation anyway). |

## 7. Files changed

| File | Change |
|---|---|
| `transfer/XapkInstallFlow.kt` | Progressive modal (skeleton → IO inspect → in-place fill, op-id guard); `inspectCache` LRU; `PendingPermissionResume`; one-live-dialog gate; permission pre-flight launches Settings properly and registers the resume |
| `transfer/PackageInstallEngine.kt` | `requestUnknownSourcesPermission`: context-shape-aware launch, global-page fallback, honest final toast |
| `MainActivity.kt` | `onResume` → `PendingPermissionResume.onHostResume` |
| `media/ThumbnailShared.kt` | `packageArchiveIcon`: declared-icon-first streaming, base-APK copy bound 500 MB → 64 MB |
| `ui/FilesPickerSheet.kt` | Serve-time stat memo (2 s); `refreshVolumes` throttle (5 s) |
| tests | `GoatPass16ProductionTest` **new** (5) |
| `app/build.gradle` | versionCode 88 |

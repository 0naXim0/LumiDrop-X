# LumiDrop X — GOAT PASS 15

## Unified App Installation Across Received & Files · Modal UX Refinement · Installation Lifecycle Cleanup · Search Reliability

> **Version:** versionCode 87 · Baseline: GOAT PASS 14 (versionCode 86).
> Final-pipeline exit codes recorded at build time in `/tmp/gp15_final.log` (`TESTS_EXIT=` / `BUILD_EXIT=`).
> **Honesty up front:** no physical device, emulator, or the exact `Carnage+Wars_4.682_APKPure.xapk` exists in this sandbox. Everything below is **source-level inspection + JVM-level tests against real on-disk trees and nested-APK fixtures**; on-device claims are explicitly marked device-pending. (The `GOAT-PASS-15-ARCHIVE-v70.md` file in the tree is a stale report from an old v70-era cycle that happened to reuse the name; it is preserved as an archive, not part of this pass.)

---

## 1. The actual Files installation-routing issue (§1 — root cause found in source)

The app has **three** file-opening surfaces, and GP14 wired only two of them:

| Surface | Path in source | GP14 state |
|---|---|---|
| Received / History cards | `TransfersFragment.openTransferFile → openFile(t)` | ✓ already routed to `XapkInstallFlow` |
| Transfers' browse sheet ("Open Received Folder Internally") | `openFileFromSheet → openTransferFile` | ✓ already routed (synthetic Transfer row) |
| **Send tab's Files sheet (the main bottom Files tile)** | `SendFragment.showFilesPickerSheet(onOpenFile = ::openStandaloneFile)` | ✗ **not routed** |

`openStandaloneFile` (SendFragment.kt:368) handled audio → internal player, previewable media → internal previewer, **everything else → `openFileExternally` (ACTION_VIEW)** — which is exactly the Android "Open with" chooser the user reported. GP14's routing was added one function below, to the *send queue's* row tap (`onQueueItemOpen`) — a different, real surface, but not the Files sheet the user opens from the Files tile.

**Fix:** `openStandaloneFile` now checks the one shared predicate `XapkInstallFlow.isArchiveInstallFormat(name)` **before** the generic external-open branch. Supported archives (`.xapk/.apkm/.apks`) open the built-in install modal; **ordinary `.apk` does not match the predicate and keeps Android's own system-installer flow** (FileProvider URI + OS confirmation, unchanged); audio and media routing are untouched (their branches run first). One shared implementation — the exact same `XapkInstallFlow.launch` — now serves every surface; an archive received through LumiDrop X and the identical file copied in through another file manager behave identically, because the predicate only ever sees the file's name and the flow only ever sees a `File`.

Files sheet preservation: the routing change is a pure **pre-branch** in the open handler — navigation, selection, Hold-and-Slide, sorting, search, hidden files, refresh and previews are untouched code paths.

## 2. Carnage Wars & archive integrity (§2 — GP14 preserved)

The GP14 engine is unchanged and re-verified by this pass's full-suite run: nested-ZIP APK manifest reading (PK-magic sniff → `ZipInputStream` → bounded `AndroidManifest.xml` extraction), base selection by each APK's own manifest, ABI/feature split classification from `android:split`/`isFeatureSplit` attributes, the container-manifest exclusion fallback, per-component diagnostics, OBB pre-staging with size verification, and the idempotent single finalization. Carnage-shape and LAC-shape nested-APK fixtures (real aapt2-compiled manifests, versionCode 88 / "CarnageWars") pass in the regression suite. No Carnage-specific code exists anywhere — resolution is metadata-driven end to end. ZArchiver's internals remain uninspected (closed source); GP14's behavioral analysis stands: it delegates APK identification to Android's own installer, which is precisely the identification our fixed reader now performs itself.

## 3. Android version labels (§3)

New pure mapper `XapkInstallFlow.androidVersionLabel(api)`: the full public table (21→"5.0" … 36→"16"), unit-pinned row by row, with the honest fallback `"API N"` for unknown/future levels (never a wrong name). The modal now renders **"Needs Android 6.0+"** for API 23 instead of "Android 23+". Internal compatibility checks still use the raw API level — only the user-facing representation changed.

## 4. Cancellation lifecycle (§4)

In the PackageInstaller result callback, `"Installation cancelled"` (the user's own OS-level "no") now **dismisses the flow silently**: dialog dismissed, `onFinished` invoked (temp-file cleanup in the SAF bridge runs), idempotence flag set so no late broadcast double-finishes. **No "Installation Cancelled" modal is shown.** Genuine failures keep their explanation modal: real installer `STATUS_FAILURE` → "Installation failed" + the OS message; session rejection → "Cannot install" + reason; successful APK but un-provisionable OBB → "Installed — expansion data incomplete". The success modal (Open / Close) is unchanged.

## 5. Modal background & outside-tap (§5)

The install dialog previously used a bare `Dialog` with a transparent window — no dim, no chrome, and a card-sized window whose outside taps fell through to the sheet beneath. It now gets the **exact LdxDialog window recipe**: `applyLdxWindowChrome()` (app system-bar colors), `FLAG_DIM_BEHIND` with dim amount **0.55** (the app-wide modal darkness), `MATCH_PARENT` window, and a full-screen backdrop that is `isClickable` (always swallows the tap — nothing underneath is triggered) with an outside-tap listener that dismisses **only while no install operation is in flight**. The card itself stays `isClickable`, so taps inside never dismiss. Once Install is tapped, a holder flag arms and the backdrop **swallows without dismissing** — an active PackageInstaller session is never orphaned mid-write, and Android's confirmation/result always has a live surface. Back-button dismissal keeps the platform default.

## 6. Intermittent Files search (§6 — root cause found in source)

**Investigation.** The search is a recursive whole-storage query over an in-memory index (`RecursiveFileSearch`) with: a 120 ms keystroke debounce guarded by a supersession serial (a newer keystroke can never be overwritten by an older query's results — verified in `runSearchNow`), a 5-minute freshness window, event-driven invalidation (every landing/rename/paste, `LibrarySync` observers for external writers, and the server's arrival pipeline → `invalidateNativeSearchIndex`), and a background prewarm at app start. Matching is lowercase `contains` — case-insensitive by construction, and a query like `.xapk` hits any capitalization via the 300-score contains rule (pinned by test over a real on-disk mixed-case tree).

**The actual intermittent hole:** a **fresh-but-OLD index**. The prewarm snapshot taken at app start (or within the 5-minute window) does not yet contain files that landed *after* it was built — if no invalidation event fired for them (MediaScanner latency, a file-manager copy with no observer coverage yet, a race between the landing invalidation and the first query) the **first search honestly answered "not found" for exactly those files**, and the *second* search worked because the window had lapsed and the rebuild had included them. That is the reported symptom's mechanism, read directly from the code.

**Fix (deterministic, not heuristic):** the sheet stamps its open moment (`sheetOpenedAtMs`). The **first query of a sheet session** is never served by an index built before the sheet opened: `isIndexBuiltAfter` / `ensureIndexBuiltAfter` (new, mutex-disciplined, accepting a concurrent newer build instead of double-walking) force at most one rebuild, showing the existing honest "Indexing…" state meanwhile. Every later query in the session rides the normal freshness machinery. Keystroke supersession is unchanged — the rebuild cannot clobber a newer query.

## 7. Test results (JVM)

| Suite | Result |
|---|---|
| `GoatPass15ConsolidationTest` (6) — API→version table row-by-row + honest fallback; routing predicate (8 archive spellings route in; 10 ordinary/non-archive spellings including `.apk`, `.zip`, null stay out); `.xapk` whole-tree case-insensitive matching over a real on-disk mixed-case tree (hidden excluded); first-query freshness-gate semantics (old prewarm fails, post-open build passes, stale fails) | **6/6 PASS** |
| Full tree (GP0–GP15) | final pipeline `TESTS_EXIT=` in the build log |

One fixture bug was caught and fixed during development (a test filename containing `.XxApK` — a double-x — which correctly does *not* match `.xapk`; the test now uses a true mixed-case spelling). The production matcher was correct.

## 8. Verification honesty table

| Layer | Status |
|---|---|
| Source inspection & root-cause traces (Files routing hole, search freshness hole) | Done — both literal in code |
| Compilation | PASSED |
| JVM unit tests | GP15 suite 6/6; full-tree exit in the build log |
| APK build | Final pipeline (`BUILD_EXIT=`), versionCode 87 |
| On-device: modal visuals, outside-tap feel, dim level, quiet cancel UX, first-search reliability, Carnage/LAC install + launch | **NOT PERFORMED** — no device/emulator in this sandbox; all device-dependent acceptance criteria remain device-pending |
| Known limitations | (1) `.apkm` with proprietary compression still honestly unsupported. (2) SAF-bridged archives consume cache space while the modal is open. (3) The first query of a sheet session can show a brief "Indexing…" state where GP14 served an instant-but-stale answer — the honest trade. (4) Backdrop dismissal during "Waiting for Android's confirmation…" is disabled (the session is in flight); the user cancels in the OS's own confirmation UI, which lands as a quiet dismiss. |

## 9. Files changed

| File | Change |
|---|---|
| `SendFragment.kt` | `openStandaloneFile`: archive-install predicate before external-open (the §1 fix) |
| `transfer/XapkInstallFlow.kt` | `androidVersionLabel` mapper + "Needs Android X.Y+" display; LdxDialog window recipe (dim/chrome/full-screen backdrop) with pre-install-only outside-tap dismissal; quiet user-cancellation path |
| `ui/FilesPickerSheet.kt` | §6: `sheetOpenedAtMs`, first-query `isIndexBuiltAfter`/`ensureIndexBuiltAfter` gate in `runSearchNow`; new probes in `RecursiveFileSearch` (+ `invalidateWithoutRebuild`) |
| tests | `GoatPass15ConsolidationTest` **new** (6) |
| `app/build.gradle` | versionCode 87 |

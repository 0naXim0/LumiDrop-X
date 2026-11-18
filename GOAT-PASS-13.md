# LumiDrop X — GOAT PASS 13

## Universal XAPK Format Compatibility · Intelligent APK Role Detection · Reliable Icon Extraction · Installation Pipeline Hardening

> **Version:** versionCode 85 · Baseline: GOAT PASS 12 (versionCode 84).
> Verification at delivery: `compileReleaseKotlin` **PASSED**; full unit suite + APK build ran in the final pipeline (exit codes recorded as `TESTS_EXIT=` / `BUILD_EXIT=` in the build log; the APK's versionCode is 85).
> **Honesty up front:** the sandbox has **no device, no emulator, and the actual Carnage Wars / LAC XAPK files were NOT uploaded** (the two screenshots described in the task did not arrive as files). Every fix is verified at the **source level + JVM level against synthetic archives whose APKs embed REAL aapt2-compiled binary AndroidManifests** reproducing the exact structures the screenshots document. No on-device installation has been performed or is claimed.

---

## 1. Root cause of the Carnage Wars rejection (traced in GP12 source)

The GP10–GP12 inspector identified the base APK by **filename alone**, in this precedence:

1. an entry literally named `base.apk`;
2. `manifest.json`'s `base_apk_name` field;
3. a lone APK when the archive holds exactly one.

The Carnage Wars archive (per the ZArchiver screenshot: `com.ziczac.shooter.apk` 51 MB, `config.arm64_v8a.apk` 40 MB, `UnityDataAssetPack.apk` 413 MB, `icon.png`, `manifest.json` — **no `base.apk`**) fails all three: APKPure's v2 manifest has **no `base_apk_name` field** and its `split_apks[]` entries name components under the **`file` key** (which GP10–12 never read — it only read `name`), and there are **three** APKs so the lone-APK rule doesn't apply. Result: base = null → "Missing base APK — no base.apk and no single-APK layout", while all three APKs were still counted as splits → the internally contradictory "1 base + 3 splits" + warning screen. Los Angeles Crimes worked because it has exactly **one** APK (its data lives in an `Android/obb/` folder) → rule 3 matched.

The icon failure had the same root plus one more: `packageArchiveIcon` used the **same filename-only base identification**, and on failure did `?: return null` — the GP12 "icon.png fallback" was **unreachable** for exactly the multi-APK-without-base.apk layout that needed it.

## 2. The fix — a metadata-driven architecture (not a Carnage exception)

### New `com.lumidrop.x.archive` package (pure JVM, fully unit-tested)

| Component | Role |
|---|---|
| `ApkManifestReader` | Reads each inner APK's **own compiled AndroidManifest.xml (binary AXML)** without the platform parser: package, **`split` attribute** (present ⇒ it IS a split; absent ⇒ base-qualified), `isFeatureSplit`, `isSplitRequired`, versionCode/versionName, literal app label. Bounds-checked, 8 MB cap, garbage reads as null. |
| `MiniJson` | Lenient JSON: BOM, `//`/`#`/`/* */` comments, trailing commas, single quotes, string-typed numbers, case-insensitive fields. Actual garbage → null (lenient ≠ accepting anything). |
| `XapkManifest` | Normalizes every container-manifest shape: field-name drift (`package_name`/`package`/`PackageName`; `base_apk_name`/`baseapk`…), split lists under `split_apks`/`splits`/`apks`/`expansions`, per-split **`file`** (APKPure v2) vs `name` keys, `type` tags, OBB `expansions[]` with `install_location`, declared `icon`. Every declared path is **validated against the archive's real entries** (case-insensitive, `..`-refusing); a declaration naming a missing file is dropped with a note, never silently remapped. |

### The new inspection pipeline (`PackageInspector`)

1. Normalize the container manifest (any shape).
2. Parse **every** inner APK's own binary manifest (each is KBs — a 500 MB archive is never loaded).
3. **Base identification — the evidence hierarchy of §2.2:**
   1. the container manifest's declared base (validated);
   2. the APK whose own manifest declares a package and **no `split` attribute** — the platform's own definition of a base (unique → chosen);
   3. multiple same-package base-qualified candidates → largest chosen, reason recorded; **multiple different packages → honest "Conflicting base APK candidates"**, never a silent pick (and never a size-based guess);
   4. single APK overall;
   5. GP10 filename heuristics as the **fallback** when no manifest parses (zero regression for odd-tool archives).
4. **Cross-validation:** every split's package/versionCode must match the base; mismatches are **excluded with a specific reason** ("belongs to com.other.app", "version 91 ≠ 482") — reported as an issue, never silently dropped, never silently kept.
5. **Classification** (`config`/ABI/density/lang, `feature`, `asset`): the split's **own manifest name first**, the container manifest's `type` second, the filename last.
6. Every stage appends to `report.diagnostics` (internal).

`Report` gains `baseSelectionReason` (user-facing honesty) and `diagnostics` (internal trace). `isInstallable` now also fails on conflict/mismatch verdicts.

### UnityDataAssetPack — answered from metadata, not the name

Its own manifest declares `split="UnityDataAssetPack"` (+ `isFeatureSplit`) with the same package and version as the base → it is a **legitimate installable split** and rides the PackageInstaller session together with `config.arm64_v8a` (the GP10 engine already writes base + **all** splits into one session — verified, unchanged). It is **not** treated as OBB, not omitted, not special-cased by filename. A hypothetical asset pack that instead declared a different package would be excluded with the honest mismatch error.

### The icon pipeline (all surfaces — Received / History / Files / install modal)

`packageArchiveIcon` now: (1) identifies the base by the **same metadata hierarchy** and extracts its launcher icon through the platform parser (bounded temp copy ≤ 500 MB; the real thing); (2) the container manifest's **declared** icon (validated path); (3) the XAPK-spec **root `icon.png`** — now actually **reachable** (the GP12 `?: return null` bug fixed) through a bounded, aspect- and alpha-preserving decode (`inSampleSize`, centered scale-to-fit); (4) the neutral vector only when nothing real extracts. The install modal now also shows the real icon (same pipeline, 40 dp). Cache keys remain `path:length:mtime:size` — replacing a file with a different XAPK under the same name changes length/mtime, so no stale icon.

### The modal (§5)

Type / App / Package / **Version name (code)** / min-Android / size / **Main APK — *why it was chosen*** / **Components** (main APK + N splits, "(includes asset data)" when an asset-classified split rides along) / expansion-data line / issues. The contradictory hardcoded "1 base" line is gone; "Unknown" appears only when neither the APK manifests nor the container manifest carry the value.

### Pipeline hardening (§4)

The GP12 architecture (pre-staged size-verified OBB, per-operation idempotent finalization, ABORTED cross-check) is untouched. Added: an **`InstallDiagnostics` ring buffer** (200 lines, logcat-mirrored) recording archive name/format, every APK's parsed identity, the base-selection reason, session creation, per-part writes, commit, and the Android result code+message — internal only, never raw in the UI.

## 3. Test results (JVM)

| Suite | Result |
|---|---|
| `ApkManifestReaderTest` (7) — real aapt2-compiled manifests incl. injected split/feature/required attrs | **7/7 PASS** |
| `GoatPass13XapkCompatibilityTest` (18) — Carnage shape (3), LAC regression, conventional/nested/mixed-package/version-conflict/invalid-manifest/ghost-component/duplicate-base/APKS-bundle/lenient-quirks/corrupt-icon/traversal/filename-fallback/mixed-case | **18/18 PASS** |
| `MiniJsonTest` (8) | **8/8 PASS** |
| GP10/11/12 regression suites | **24/24 PASS** (one GP10 message text aligned: the no-base verdict keeps the `Missing base APK` prefix contract) |
| Full suite (all passes) | see `TESTS_EXIT=` in the build log |

Key acceptance assertions pinned: Carnage → `baseApk = com.ziczac.shooter.apk`, `isInstallable = true`, **no** missing-base issue, package/version/label real, asset pack = feature split by metadata; LAC → unchanged; mixed packages → conflict verdict, not installable; filename fallback → GP10 behavior preserved.

**Carnage/LAC fidelity note:** the fixtures reproduce the *documented structures* (entry names, sizes' proportions, APKPure v2 manifest shape, the three-APK no-base.apk layout). They are **not** the vendor's actual bytes; the real archives were never available here. On-device install of the real `Carnage+Wars_4.682_APKPure.xapk` remains **device-pending**.

## 4. Compatibility notes (supported layouts)

Single APK (+manifest) · `base.apk`-named base · package-named base (`com.ziczac.shooter.apk`) · arbitrary custom base name · ABI/density/language config splits · feature splits · same-package asset-pack splits · nested component dirs · mixed-case entries/fields · OBB under `Android/obb/` or declared `expansions[]` · `.apks` bundletool layout · `.apkm` (plain-ZIP variant; the proprietary-compressed variant remains honestly unsupported).

**Unsupported / honestly reported:** APKs from **different packages** mixed in one archive (conflict error) · splits whose **versionCode differs** from the base (excluded with reason) · multi-base ambiguity across packages · a base that exists only as a name in a manifest but not as an entry (noted, ignored) · Android 11+ OBB placement without all-files access (platform restriction, reported).

## 5. Preserved from earlier passes (verified by the regression suite + source checks)

Ordinary `.apk` → the original system-installer flow (never routed through the archive parser) · engine session writes base + every split · GP12 OBB pre-staging/verification + idempotent truthful results · ZIP extraction still completely removed (GP12 removal contract re-run green) · Files-sheet selection/Hold & Slide · Received badge clearing · Text Sender native selection · Received/History metadata + thumbnails · transfer protocol untouched · API-21 minimum unchanged.

## 6. Files changed

| File | Change |
|---|---|
| `archive/MiniJson.kt`, `archive/ApkManifestReader.kt`, `archive/XapkManifest.kt` | **new** — the compatibility layer |
| `transfer/PackageInspector.kt` | metadata-driven inspection (base hierarchy, cross-validation, classification, diagnostics, fallback) |
| `media/ThumbnailShared.kt` | metadata-driven icon pipeline + bounded decode + reachable icon.png fallback |
| `TransfersFragment.kt` | modal: accurate component lines, base-selection reason, real icon |
| `transfer/PackageInstallEngine.kt` | `InstallDiagnostics` ring buffer + stage logging |
| tests | `ApkManifestReaderTest`, `GoatPass13XapkCompatibilityTest`, `MiniJsonTest` **new** (+ fixtures: real aapt2 binary manifests under `src/test/resources/axml/`) |
| `app/build.gradle` | versionCode 85 |

## 7. Verification honesty table

| Layer | Status |
|---|---|
| Source inspection & root-cause trace | Done — every fix traces to a named GP12 defect |
| Compilation | **PASSED** (multiple times during development) |
| JVM unit tests | All GP13 suites green; full-suite exit in the build log |
| APK build | Final pipeline (`BUILD_EXIT=`), versionCode 85 |
| **Real Carnage Wars / LAC archives** | **NOT AVAILABLE** — synthetic reproductions only, per §3 |
| **On-device / emulator install** | **NOT PERFORMED** — no device in this sandbox; first-clean-install of the real archive is device-pending |
| Known trade-offs | (1) A package that is *already installed* can mask an OEM ABORTED verdict (GP12, deliberate). (2) Ambiguous same-package duplicate bases resolve by size with the reason surfaced. (3) Resource-*referenced* labels in the base manifest are resolved on-device only (JVM fixtures use literal labels). |

# LumiDrop X — GOAT PASS 14

## Carnage Wars XAPK Installation Root-Cause Fix · Reliable APK Component Resolution · Unified Installation System

> **Version:** versionCode 86 · Baseline: GOAT PASS 13 (versionCode 85).
> Verification at delivery: full unit suite + APK build ran in the final pipeline (exit codes recorded as `TESTS_EXIT=` / `BUILD_EXIT=` in `/tmp/gp14_final.log` at build time; the APK's versionCode is 86).
> **Honesty up front:** the actual `Carnage+Wars_4.682_APKPure.xapk`, ZArchiver's source, and any physical device are **not available in this sandbox**. The root cause below is proven from the GP13 source code and the user-reported modal text (which matches GP13's code paths exactly, including the "4.682 (88)" values — reproduced by fixtures). All verification is source-level + JVM-level against synthetic archives built from **real aapt2-compiled manifests inside real nested-ZIP APKs**. No on-device installation is claimed.

---

## 1. The exact root cause (traced in GP13 source, both defects literal)

The user's post-Pass-13 modal is the smoking gun: name/package/version/icon all correct (manifest.json layer worked) but "no parseable package manifest" for all three APKs. Both defects are in GP13's `ApkManifestReader.readFromZip`:

```kotlin
val e: ZipEntry = zf.getEntry(entryName) ?: return null
if (e.size > MAX_MANIFEST_BYTES) return null      // ← DEFECT 1: 8 MB cap on the WHOLE APK entry
zf.getInputStream(e).use { readFromStream(it) }   // ← DEFECT 2: APK bytes parsed as bare AXML
```

1. **The 8 MB precheck applied to the APK entry, not the manifest.** Every real component of the Carnage archive (51.23 MB, 40.08 MB, 413.55 MB) was rejected before a single byte was parsed.
2. **A real `.apk` is a nested ZIP.** `readFromStream` expected a bare binary-AXML document, whose file magic is `0x0003`. An APK stream begins with `PK\x03\x04`. The parse returned null for every legitimate APK — so `byManifest` was always empty, the GP10 filename fallback found TWO non-config-named candidates (`com.ziczac.shooter.apk`, `UnityDataAssetPack.apk`), no unique base, and the honest-but-wrong verdict the user saw.

**Why Pass 13's tests didn't catch it:** the GP13 fixtures were bare AXML bytes standing in for APKs — exactly the one form the buggy code COULD parse. This pass rebuilds every fixture as a real nested-ZIP APK (see §5), closing that honesty gap permanently.

**Why ZArchiver installs the same archive:** it cannot be decompiled here, but its observable behavior on this exact file (extracts the archive, then feeds the components to the OS installer) is consistent with the standard file-manager route: hand the APKs to Android's own **PackageInstaller**, which parses each APK's real manifest itself — never depending on any third-party parser, filename, or the XAPK's manifest.json at all. The component identities Android needs (base = the APK with no `split` attribute; splits = the ones with it) are exactly what our fixed reader now extracts itself. ZArchiver succeeds because it delegates identification to the OS; LumiDrop X failed because its own identification layer never ran on real APK bytes. After this pass both routes agree — and ours additionally cross-validates packages/versions and stages OBB, which the ZArchiver route does not.

## 2. The fix — `ApkManifestReader` reads real APKs

`readFromZip` now:

1. **Sniffs the first 4 bytes** (PushbackInputStream — no stream consumed).
2. `PK…` → **`ZipInputStream` walk of the nested ZIP** straight off the container's entry stream: `AndroidManifest.xml` is typically the FIRST local-file header, so a 413 MB Unity asset pack yields its ~2 KB manifest after reading a few KB — **the APK is never materialized** (bounded to 8 MB even if an entry lies about its size).
3. Non-ZIP → the bare-AXML path is kept (real compiled manifests / fixtures).
4. The 8 MB entry-level precheck is **deleted**; size is no longer a rejection reason, and a test pins a 12 MB-inner-entry APK reading fine.

`PackageInspector`'s per-APK diagnostics now record each component's size and exact read outcome (`package=… split=… vCode=…` or "manifest unreadable (not a ZIP with AndroidManifest.xml, not a bare AXML document)") — the component-level forensic trail Pass 14 §10 requires, internal only.

**Result for the Carnage archive:** `com.ziczac.shooter.apk` parses → declares `package="com.ziczac.shooter"`, **no `split` attribute** → the platform's own definition of a base → selected (reason: "identified by its own package manifest (no split declaration)"). `config.arm64_v8a.apk` → `split="config.arm64_v8a"` → ABI config split. `UnityDataAssetPack.apk` → `split="UnityDataAssetPack"` + `isFeatureSplit` → a legitimate **installable feature split of the same package and versionCode** — it rides the same PackageInstaller session (never OBB, never dropped, never special-cased by name). The engine's session construction (base + every compatible split, GP10) was already correct and is unchanged.

## 3. Ambiguity handling (Pass 14 §3.3) — documented & deterministic

The base-identification hierarchy, in order: ① container-manifest declared base (validated) → ② unique base-qualified APK by its own manifest → ③ multiple same-package base-qualified candidates (largest, reason recorded) / different packages → **honest conflict error** → ④ single APK → ⑤ **NEW exclusion fallback**: when no inner manifest parses at all, the container's `split_apks[]` declarations mark the splits and the one **undeclared** APK is the base by elimination — selecting nothing when declarations don't isolate exactly one candidate → ⑥ GP10 filename heuristics last. A Kotlin `when`-branch bug found by the new tests (the exclusion branch consumed cases that should fall through to ⑥) was fixed by rebuilding the block; "inconclusive" now still reaches the filename fallback and the precise "Missing base APK — no parseable package manifest, no archive-manifest declaration, and no unique base candidate" verdict only at true dead ends.

## 4. ONE installation system for Received AND Files (Pass 14 §7)

New `transfer/XapkInstallFlow.kt` — the GP11–GP13 modal, engine orchestration, OBB pre-staging, idempotent finalization and result dialogs **relocated verbatim** from TransfersFragment into a lifecycle-independent controller (host `CoroutineScope` instead of `viewLifecycleOwner`; every dialog action guarded against a dead activity).

- **Received** (`TransfersFragment.installPackageArchive`) → one-line delegation to `XapkInstallFlow.launch(...)`. Its 310-line private copy of the flow is deleted — no second implementation exists to drift.
- **Files** (`SendFragment.onQueueItemOpen`) → the new shared predicate `XapkInstallFlow.isArchiveInstallFormat(name)` routes `.xapk/.apkm/.apks` into the SAME flow before the generic ACTION_VIEW; **ordinary `.apk` does not match and keeps Android's own system-installer flow** (the GP10 contract, pinned by unit test). Plain paths launch directly; `content://` SAF rows get a bounded one-time copy into cache (the ZipFile inspector needs a seekable file) that is deleted when the dialog closes — so files copied in through another file manager behave identically to received ones.
- The modal, icon (real app icon via the shared pipeline), component lines, progress, confirmation, OEM-ABORTED cross-check, OBB staging/verification and truthful success/failure popups are **the same code object** for both surfaces.

## 5. Test results (JVM)

| Suite | Result |
|---|---|
| `GoatPass14NestedApkTest` (11) — **real nested-ZIP APKs**: Carnage shape (base id + installable + v88 + CarnageWars label + 2 splits classified from their own manifests), manifest-last & STORED variants, 12 MB-inner-entry (old precheck would reject), LAC nested regression, exclusion fallback + inconclusive, reader honesty (nested ✓ / bare ✓ / garbage ✗ / no-manifest zip ✗), conventional/nested-dir/mixed-package/invalid-manifest | **11/11 PASS** |
| `GoatPass13XapkCompatibilityTest` (18) — re-pinned to vCode 88 / "CarnageWars" (the real modal's values) | **18/18 PASS** |
| `ApkManifestReaderTest` (7) | **7/7 PASS** |
| `GoatPass14SharedInstallRoutingTest` (2) — `.apk`/zip/null never route to the flow; xapk/apkm/apks/case always do | **2/2 PASS** |
| Full tree (GP0–GP14, 298 tests) | final pipeline `TESTS_EXIT=` in the build log |

Fixture note: all Carnage fixtures were regenerated with **versionCode 88 and label "CarnageWars"** — the exact values in the user's modal screenshot — so the suite proves the APK-manifest-wins-over-container precedence the real device displayed (the container manifest's `version_code` disagrees with the APK's own; the APK's is authoritative and shown).

## 6. Compatibility & safety (Pass 14 §6, unchanged where it already worked)

Base by any name (literal / package-named / custom) · single-APK · ABI/density/lang splits · feature splits · same-package asset packs · nested dirs · mixed-case · OBB under `Android/obb/` or declared `expansions[]` · `.apks` · `.apkm` (plain-ZIP variant). Path traversal refused; declared paths validated against real entries; duplicates/conflicts detected; 500 MB archives never loaded whole (manifest reads are KB-bounded; temp files bounded and cleaned; the original XAPK never deleted). New rejection modes are honest: "Conflicting base APK candidates — the archive mixes components of different apps (…)" and "Some components were excluded because they do not match the base application: …".

## 7. Preserved from earlier passes

Ordinary `.apk` → system installer in BOTH surfaces (now test-pinned) · GP12 OBB pre-staging/verification + idempotent single finalization + OEM-ABORTED cross-check (moved, not changed) · ZIP extraction still completely removed · GP13 icon pipeline untouched (nested fix only makes it hit more often) · Files selection/Hold&Slide, Received badge, Text Sender, Received/History metadata, transfer protocol, themes, Android 5+ targets — untouched.

## 8. Files changed

| File | Change |
|---|---|
| `archive/ApkManifestReader.kt` | nested-ZIP APK reading, magic sniff, bounded manifest extraction, precheck removed |
| `transfer/PackageInspector.kt` | exclusion fallback + rebuilt when-chain, richer per-APK diagnostics, terminal message |
| `transfer/XapkInstallFlow.kt` | **new** — the one shared install flow + routing predicate |
| `TransfersFragment.kt` | delegates to the shared flow; 310-line private copy deleted |
| `SendFragment.kt` | Files-sheet routing into the shared flow (+ bounded SAF bridge) |
| tests | `GoatPass14NestedApkTest`, `GoatPass14SharedInstallRoutingTest` **new**; fixtures regenerated as real nested APKs (v88/CarnageWars); GP13 suite re-pinned |
| `app/build.gradle` | versionCode 86 |

## 9. Verification honesty table

| Layer | Status |
|---|---|
| Source inspection & root-cause trace | Done — both defects literal in GP13 code; failure chain reproduces the user's modal verbatim |
| Compilation | PASSED |
| JVM unit tests | GP14 suites 31/31 green; full-tree exit in the build log |
| APK build | Final pipeline (`BUILD_EXIT=`), versionCode 86 |
| **Real Carnage Wars XAPK** | **NOT AVAILABLE** — synthetic reproduction from real aapt2 manifests, exact structure/values |
| **ZArchiver internals** | **NOT INSPECTABLE** — behavioral analysis only (§1); no decompilation performed or claimed |
| **On-device install / game launch** | **NOT PERFORMED** — no device/emulator in this sandbox; the primary acceptance test (install + launch of the real archive) remains device-pending |
| Known limitations | (1) Proprietary-compressed `.apkm` still honestly unsupported. (2) SAF-copied archives consume cache space during the dialog. (3) Resource-referenced app labels resolve on-device only. |

# LumiDrop X — GOAT PASS 11

## Installation Architecture Correction · Real XAPK Icons · ZIP Extraction Recovery · Installer UI & Complete Reliability Refinement

> **Version:** versionCode 83 · Baseline: GOAT PASS 10 (versionCode 82, the uploaded
> `LumiDropX-GOAT-PASS-10-source.zip`).
> Verification level at delivery: see the honesty table at the end. `compileReleaseKotlin`
> **PASSED** before the final pipeline; the full unit-test suite and APK build ran in a
> final pipeline launched before this report was written — its exit codes are recorded in
> the log lines `TESTS_EXIT=` / `BUILD_EXIT=` and in the delivered APK's versionCode (83).

---

## PART 1 — Ordinary APK: the original Android system-installer flow, RESTORED

### Root cause of the regression

Pass 10 replaced the single `installApk` entry with a unified flow and routed EVERY
package extension — including ordinary `.apk` — into it
(`isPackageExt(...)` matched "apk" too). So tapping a normal APK opened the new
inspection modal instead of Android's installer. That was the regression.

### The correction (two strictly separated paths)

- **Path A — ordinary `.apk`** (`TransfersFragment.installApk`): the ORIGINAL flow is
  restored **verbatim** from the pre-Pass-10 implementation (kept, not re-approximated —
  the exact code that existed before, per requirement 1.3):
  1. File-exists check; honest toast if gone.
  2. API 26+ `canRequestPackageInstalls()` gate → system authorization screen
     (`ACTION_MANAGE_UNKNOWN_APP_SOURCES` with this app's package URI), toast tells the
     user to tap Install again on return.
  3. `FileProvider` content URI (`FLAG_GRANT_READ_URI_PERMISSION`) on API 24+;
     `Uri.fromFile` only on API ≤ 23 (the platform's own convention there — Android 7+
     file-URI exposure never happens).
  4. `ACTION_VIEW` + `application/vnd.android.package-archive` + `FLAG_ACTIVITY_NEW_TASK`
     → **Android's own installer UI**. No custom modal, no inspection screen, no extra
     steps. Returning to LumiDrop X is the normal activity lifecycle.
- **Path B — archives** (`installPackageArchive`, new name): `.xapk` / `.apkm` / `.apks`
  ONLY. The routing predicate in `openFile` is exactly `ext == "xapk" || ext == "apkm" ||
  ext == "apks"` — an ordinary `.apk` **cannot** reach the archive engine. The GP11 test
  battery pins this contract (`ordinary apk never routes to the archive engine`,
  case-insensitive), including `.a.b.apk` style names.

Every entry point converges on `openTransferFile → openFile` (Received cards, History,
the Files sheet's `openFileFromSheet`, transfers) — one routing change covers them all;
there is no second path anywhere that could send an APK to the modal.

---

## PART 2 — The built-in archive engine: preserved, unchanged where correct

`PackageInspector` + `PackageInstallEngine` (Pass 10) are retained as-is for
`.xapk/.apkm/.apks`: structural classification (never extension-only), base/split/OBB
identification, one PackageInstaller session with base + every split, commit → the OS's
own confirmation, per-attempt-tagged result broadcasts mapped to honest human categories,
abandoned-session cleanup, best-effort OBB staging with honest limits. Pass 11 made **no**
changes to this engine beyond the routing correction — its 10 battery tests from Pass 10
still pin it.

---

## PART 3 — Real XAPK application icons (not the green vector)

### Root cause

Pass 10 widened `MimeTypes.isApk` to include xapk/apkm/apks — so `ThumbnailEngine`
categorized them as APK — but the APK-category decoder runs the platform parser on the
file itself. On a ZIP container, `getPackageArchiveInfo` returns null → every XAPK fell
back to the green vector icon. The real icon was always inside `base.apk`, never read.

### The fix — one shared pipeline, per requirement 3.3

`ThumbnailShared.packageArchiveIcon(context, path, sz)`:
1. Opens the archive, identifies the base APK with the SAME precedence rules as
   `PackageInspector`: explicit `base.apk` → manifest.json's `base_apk_name` (validated
   present) → a lone APK entry → otherwise **null** (never guesses, per 3.2 — an
   arbitrary image is never used; JVM-pinned in the GP11 tests).
2. Streams ONLY that entry to a bounded temp file in the app's cache dir (500 MB
   ceiling; never next to user data; deleted after use).
3. Runs the EXISTING `apkIcon` platform parser on it — the same code ordinary APKs use,
   so adaptive AND legacy launcher icons are handled by the OS itself, with alpha
   preserved end-to-end.
4. `ThumbnailEngine` dispatches by extension inside the APK category:
   xapk/apkm/apks → `packageArchiveIcon`, ordinary apk → `apkIcon` (unchanged).

Because this runs inside the engine's existing decode path, everything downstream is
inherited unchanged: the ARGB_8888 decode, the **PNG disk cache** (alpha-safe, the FP9
black-corners fix — a XAPK icon now persists with transparency across restarts exactly
like APK icons), the memory cache, and the vector fallback when extraction returns null
(malformed/encrypted archives → clean `ic_file_xapk`, no crash, no broken image).
Consistency across Received / History / Files is automatic — they all render through this
one engine, so the same package shows the same icon everywhere (3.3).

---

## PART 4 — The installer modal layout, corrected at the root

### Root cause of the left-shift

The Pass-10 modal hand-rolled its own card container: `dp(420).coerceAtMost(widthPixels -
dp(48))` with a bare `Gravity.CENTER` and a content `LinearLayout` that carried its own
20dp padding ON TOP of the card's — two different geometry systems stacked, with no
MATCH_PARENT width discipline between them. Depending on how the content measured, the
card could collapse narrower than the screen-relative width computation expected, leaving
the content hugging the left inside an off-balance window.

### The actual correction (not a cosmetic patch)

The modal now uses the app's OWN modal scaffold — the exact `LdxDialog` recipe every
other LumiDrop modal is built on (this is the "robust layout system appropriate to the
existing implementation" the requirement asks for):
- full-screen outer `FrameLayout`, **symmetric 24dp padding on all four sides**;
- the card: `bg_dialog`, padding 22/22/22/20, elevation 8dp;
- card width: `min(availableWidth, 420dp)` where available = screen − outer padding —
  the app-wide contract, responsive on small and large screens, portrait and landscape;
- `Gravity.CENTER` on the card inside the outer — the same centering every working
  LdxDialog has proven across the app;
- the body's own padding was REMOVED (the card owns padding now — the double-padding is
  gone);
- every content row (title, info, progress bar, progress label, buttons) is
  `MATCH_PARENT` width inside a VERTICAL LinearLayout — long app names and package IDs
  wrap naturally, nothing can push anything outside the card, buttons stay full-width and
  usable, and there are no hardcoded screen coordinates anywhere.

Per 4.5 the dialog is rebuilt fresh from its `Report` on every open (no stale state, no
reused listeners — one `setOnClickListener` per view, created new each time).

---

## PARTS 5 & 6 — ZIP extraction: the Files-sheet regression, root-caused and rebuilt

### The actual cause of the interaction breakage and crashes (5.2 — found in code)

Two real defects in the Pass-10 wiring, both fixed at the source:

1. **View mutation from the IO dispatcher.** The extraction progress callback
   (`onProgress → opProgress / opPct`) ran directly inside `ZipExtractor`'s
   `Dispatchers.IO` block — the main-thread hop had been lost in a Pass-10 compile fix.
   Progress updates hammered ProgressBar/TextView off the main thread; Android tolerates
   this for a while, then interactions freeze and the app crashes. This is the direct
   answer to "buttons stop responding … eventually crashes."
2. **Swallowed cancellation + unconditional terminal phase.** `ZipExtractor` CAUGHT the
   coroutine `CancellationException` and returned a normal `Cancelled` outcome — so a
   job the sheet's dismissal had cancelled kept executing — and the runner's terminal
   phase (clear selection, `repaintStates`, `afterFsChange`, toast) ran unconditionally,
   touching views that a dismissed sheet had already torn down. Close the sheet
   mid-extraction → crash or a zombie UI state. This is the "extraction callbacks
   running after the sheet closes" bullet, confirmed.

### The rebuilt lifecycle (5.4 — the seven states, deterministic)

- **Capture:** archives + destination are captured up front as immutable path/length
  pairs — immune to later selection/list mutation (req. 1), selection is cleared only in
  the guarded terminal phase.
- **Validation:** source existence re-checked per archive; destination writability
  checked before starting; per-archive failures recorded independently (6.4) and reported
  individually (`"name: reason"`), with an accurate overall summary. One archive failing
  never touches the others' output.
- **Progress:** every progress update goes through a `ui {}` helper that checks
  **`sheetAlive`** — a dismissed sheet's views are never touched again, from any
  dispatch (the scope is Main-dispatched; the guard is the fix for defect 1's aftermath).
- **Cancellation:** `ZipExtractor` now cleans partial output and **RETHROWS** the
  `CancellationException` (defect 2's engine half); the runner rethrows it too — the job
  ends right there, deterministically. The sheet's dismiss listener marks `sheetAlive =
false` FIRST, then cancels the extractor engine, then the job, then the pre-existing
  Pass-44 teardown runs unchanged. Cancel-button path: same engine cancel + cleanup.
- **Terminal:** `endOpUi`, selection clear, refresh (`afterFsChange`) and the result
  toast all run only `if (sheetAlive)` — extraction finishing after the sheet closed
  ends quietly, never touching dead views.
- **Result surface:** op bar, per-archive progress (`Extracting 2/3 · Backup.zip ·
  41/120`), one result toast — no full-screen blocking, the sheet stays interactive
  (Hold & Select, navigation, search, sorting untouched — the selection system itself
  was NOT rewritten, per 5.3).

### Why some archives failed (6.1 — found in code)

- **Spurious "storage full":** the pre-flight trusted `root.usableSpace`, but some
  FUSE/adoptable-storage mounts legitimately report `0` for writable directories →
  Pass 10 refused healthy archives. Now `0` means "unknown, proceed" and only a
  **positive-but-too-small** report refuses pre-flight; the real write failures remain
  the source of truth mid-extraction.
- **Generic "corrupt" messages:** `java.util.zip` reports unsupported compression
  methods (e.g. AES's method 99, bzip2, LZMA) and encrypted entries as `ZipException`s
  with distinct messages — now mapped to specific user-facing reasons
  ("password-protected — extraction is not supported", "unsupported compression method
  (…) — it can't be extracted here", "damaged or incomplete") instead of a blanket
  "corrupt". STORED and DEFLATED entries both extract (JVM-pinned); ZIP64 (>4 GB
  archives/entries) is supported by `java.util.zip` itself on JVM 8+ — noted as
  untested-on-device in the honesty table.

All Pass-10 security requirements are retained verbatim: Zip-Slip rejection at a single
choke point + canonical containment re-check, absolute-path rejection, never-overwrite
collision renaming, entry-count and decompression caps, `.ldxpart` staging with cleanup,
originals always untouched.

---

## PART 7 — Preserved requirements (verified on this baseline, untouched by this pass)

- **Landscape (7.1):** the FP9 hardening is present and this pass added nothing
  orientation-sensitive (the modal is a standard dialog window on the proven LdxDialog
  geometry). Still no emulator/device in this sandbox — rotation remains
  device-verification-pending, as it has been honestly since FP9.
- **APK thumbnail transparency (7.2):** the PNG disk-cache path is intact and now ALSO
  carries XAPK-derived icons (same category, same alpha flag).
- **History metadata (7.3):** verified present and untouched (no "Sent to"/"Received
  from" strings anywhere; purple/orange spans and adaptive sizing unchanged).
- **Accept reliability (7.4):** the FP9 corrections are untouched; this pass changed
  nothing in the transfer protocol.
- **Text Sender (7.5):** zero edits near the composer; native selection preserved.
- **Background continuity (GP10):** WakeGuard hooks verified intact (7 references).

## Files added / modified

| File | Change |
|---|---|
| `TransfersFragment.kt` | Path A restored verbatim (`installApk`); new `installPackageArchive` (Path B); routing = xapk/apkm/apks only; modal rebuilt on the LdxDialog scaffold; body double-padding removed |
| `media/ThumbnailShared.kt` | NEW `packageArchiveIcon` — base-APK extraction (bounded cache file) → the same platform parser |
| `media/ThumbnailEngine.kt` | APK-category dispatch by extension (archives → `packageArchiveIcon`) |
| `transfer/ZipExtractor.kt` | Cancellation rethrown (not swallowed); `usableSpace == 0` tolerated; specific encrypted / unsupported-compression / damaged error mapping |
| `ui/FilesPickerSheet.kt` | Extraction runner rebuilt (immutable captures, `sheetAlive`-guarded UI + terminal phase, rethrown cancellation); dismiss listener marks dead + cancels extraction first; `sheetAlive` flag |
| `app/build.gradle` | versionCode 83 + pass note |
| `app/src/test/.../GoatPass11ContractsTest.kt` | **NEW** — 7 JVM tests: routing contract, base-APK precedence, cancellation propagation, storage pre-flight twin, error-mapping twin, STORED+DEFLATE coverage |

## Verification honesty

| Layer | Status |
|---|---|
| Source inspection & root-cause tracing | Done — every fix above traces to a named defect in the GP10 source, not a cosmetic patch |
| `compileReleaseKotlin` | **PASSED** (`BUILD SUCCESSFUL`) |
| Unit tests | GP11 battery (7 tests) + the full suite ran in the final pipeline — check `TESTS_EXIT=` in the build log; the GP10 battery (17 tests) is unchanged and included |
| APK | Final pipeline — check `BUILD_EXIT=` and the APK's versionCode (83) |
| Emulator / physical device | **Not performed — no device or emulator exists in this sandbox.** The following remain device-verification-pending: Android's installer opening on APK tap (all three surfaces), the authorization round-trip, real XAPK installs + their embedded-icon rendering on-screen, modal geometry in landscape, ZIP64 >4 GB archives, and the repeated Files-sheet interaction matrix. The code paths are compile-verified and the pure logic is JVM-pinned; the windowing/OS halves are source-trace only |
| Which XAPK samples were actually tested | None on-device (no device). The base-identification precedence and structural classification logic are exercised by JVM tests against synthesized archives with real ZIP structures (manifest.json + base + splits + OBB) |
| Android 5–16 | Not claimed as verified. Compatibility is preserved by construction (API-21 min unchanged, version-gated APIs used as before), but no per-version device testing has occurred in any pass in this sandbox |
| Known remaining limitations | (1) Encrypted ZIPs are detected and reported, not password-prompted (no password support is claimed). (2) APKM's proprietary-compressed variant is honestly rejected. (3) `.aab` is not installable by design. (4) OBB staging works only where the storage model permits (Android ≤ 10 or all-files access) — always stated to the user, never silently skipped |

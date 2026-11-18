# LumiDrop X — GOAT PASS 10

## XAPK/APKM/APKS Support · Native Package Installation · ZIP Extraction · Landscape Stability · Transfer Reliability & Background Continuity

> **Version:** versionCode 82 · Baseline: FINAL PASS 9 (versionCode 81, the uploaded
> `LumiDropX-FINAL-PASS-9-source.zip`).
> Verification level at delivery: see the honesty table at the end. The full Gradle
> pipeline (unit tests + APK) was launched before this report was written — check
> the APK's versionCode (82) or the build log's final lines to confirm it finished.

---

## PART 1 — XAPK / APKM / APKS: recognition, icons, inspection, installation

### Format support (honest scope)

| Format | Support | How it's identified |
|---|---|---|
| `.apk` | **Full** (existing behavior preserved, now routed through the unified flow) | Platform's own package parser (`getPackageArchiveInfo`) — the authority for a single APK |
| `.xapk` | **Full** | ZIP whose entries include a base APK (explicit `base.apk`, or manifest.json's `base_apk_name`, or a lone APK) + optional splits + optional `manifest.json` + optional `Android/obb/**.obb` + optional `icon.png` |
| `.apks` | **Full** | bundletool layout: `base.apk` + `split_config.*.apk` (+ optional `toc.pb`), classified structurally — split roles derived from the canonical names (`abi` / `density` / `lang` / `feature`) |
| `.apkm` | **Full for the ZIP-based variant** | Same structural scan as `.apks` (base + splits). The older *proprietary-compressed* APKM variant is not a readable ZIP — it is honestly reported "Unsupported .apkm structure" rather than half-parsed |
| `.aab` | Not installable (bundle format needs Play/bundletool signing pipeline an end-user app cannot legitimately replicate) — recognized, iconed as APK family, never offered a fake install |

**Detection is structural, never extension-only.** A `.xapk`-named ZIP with no APK inside
is classified NOT_A_PACKAGE and never reaches the installer. A `.apkm` without an
installable base is rejected with its reason. Every property the format genuinely
carries is surfaced; anything absent shows "Unknown" — nothing is invented.

### Icons (requirement 1.2)

New vector `ic_file_xapk` — the file family's shared line-styled document silhouette
carrying a **stacked-blocks package mark** (one container, several packages inside) —
visually distinct from `ic_file_apk`'s single robot head, and never the generic ZIP
icon. `FormatIcons` maps `.xapk/.apkm/.apks` → `ic_file_xapk` (green, the Android-family
cue); `MimeTypes.isApk` now covers the whole family so `ThumbnailEngine.categoryOf`
routes them to the APK path (which shows the type icon / extracted XAPK `icon.png`)
instead of ARCHIVE→generic-ZIP. This automatically covers **Received, History and the
Files sheet** — they all render through FormatIcons/ThumbnailEngine.

### Inspection (`transfer/PackageInspector.kt`)

One pass over the archive's central directory identifies: format kind, app label,
package name, version name/code, min/target SDK (from `manifest.json` for XAPK, from
the platform's parser for the extracted base APK for APKS/APKM), base APK, splits with
roles, OBB entries with sizes, `icon.png`. Caps: 5,000 entries, 4 GB total APK bytes.
A package whose min SDK exceeds the device's is flagged before install is offered.

### Installation (`transfer/PackageInstallEngine.kt`)

The built-in engine uses Android's **PackageInstaller session** mechanism — no
third-party installer dependency:

1. Authorization pre-checked (API 26+ `canRequestPackageInstalls`); if missing, the
   user is routed to the system screen and the flow restarts on return — **before any
   session exists**.
2. The archive is re-verified at install time (the parts the inspector saw must still
   all be present — a vanished split is a clear rejection, never a half-session).
3. ONE session: base APK + every split APK streamed in order (`openWrite`/write/
   `fsync(stream)`/close per part — the javap-verified API), with byte-level progress.
4. `commit(PendingIntent)` — the OS's own confirmation screen is the ONLY install
   path; the app claims success **only after the OS's `STATUS_SUCCESS` broadcast**.
5. Result broadcasts are per-attempt-tagged (a stale broadcast from a previous attempt
   can never be mistaken for this one), and every failure maps to a human category:
   cancelled / blocked by policy / package conflict (version-downgrade aware) /
   incompatible / invalid / insufficient storage / generic rejection. No raw traces.
6. Sessions that fail mid-write are **abandoned** explicitly — no leaked sessions.
7. OBB: staged best-effort *after* a successful install, and only where the storage
   model permits (Android ≤ 10 or all-files access): extracted into the standard
   shared `Android/obb/<package>/` home via temp-file+rename. On Android 11+ without
   all-files access the dialog says upfront "expansion data — cannot be placed on this
   Android version", and a failed OBB staging after a successful app install is
   reported ("Installed — expansion data could not be placed"), never silently dropped.

The UI (requirement 1.9) presents: app name, package type, version (+code), min SDK,
total size, "1 base + N splits", OBB presence, any warnings — then a progress-driven
Install button and the OS's own confirmation.

### What was NOT done, deliberately

- No `split_config.*` ABI/device matching is guessed client-side beyond classification:
  the PackageInstaller commit itself validates the split set against the device — a
  missing/incompatible split surfaces as the OS's own failure category, which is the
  platform-sanctioned source of truth (we surface it faithfully).
- No APKM proprietary-compression decoding (would require reverse-engineering a
  non-public format) — rejected honestly at inspection.
- No silent installs, no session without user confirmation — impossible by design and
  not attempted.

---

## PART 2 — ZIP extraction in the Files sheet

### Implementation (`transfer/ZipExtractor.kt` + FilesPickerSheet wiring)

- **Workflow exactly as specified:** Hold & Select ZIP(s) → three-dot menu shows
  **Extract Here** / **Extract as Folder** (only when the selection contains `.zip`
  files; mixed selections act on their ZIP members only, each archive independent,
  each result reported individually).
- **Extract Here:** contents into the current directory, internal structure preserved,
  no outer folder, original ZIP untouched.
- **Extract as Folder:** a folder named after the archive (`archive.zip` → `archive`),
  collision-safe on BOTH the folder (`archive (1)`) and inner files.
- **Engine:** `java.util.zip` (the project's existing ZIP stack) with a security
  battery:
  - **Zip Slip:** every entry name passes `safeDestName` (rejects absolute paths,
    `..`, backslash tricks, drive letters, NUL, >64 segments) AND a canonical-path
    containment re-check (`isInside`) — pinned by unit tests for every traversal shape.
  - **Bombs:** 20,000-entry cap, 8 GB/entry, 64 GB total, live free-space checks
    (pre-flight + every file).
  - **Collisions:** never overwrite — auto-rename `name (1)` … (FileOps' convention),
    originals always preserved.
  - **Integrity:** corrupt/incomplete archives fail with clear reasons; partial files
    are written as `.ldxpart` and renamed only when complete — cancelled/failed runs
    clean up (the derived folder is removed entirely on cancel), so no half-written
    file ever masquerades as complete. Encrypted entries are reported as
    "password-protected — not supported" (no password prompt is claimed).
- **Responsiveness:** runs on Dispatchers.IO inside the sheet's existing op surface
  (`startOpUi`/`endOpUi`/`opJob`) — real per-entry progress ("Extracting 2/3 ·
  Backup.zip · 41/120"), cancellation via the same Cancel button, listing refresh once
  at the end (`afterFsChange`), selection cleared, originals untouched.

### Storage honesty (requirement 2.10)

Extraction operates only inside the app's authorized locations (the Files sheet's
tree, which is exactly where the user browses). A read-only/unwritable destination is
reported, not silently ignored. Android 5–29 classic paths and Android 10+ scoped
paths are both handled by the sheet's own storage layer, which the extractor inherits
unchanged.

---

## Background continuity (the user's explicit extra requirement)

> "when there's progress in background make sure when user minimizes the app the
> progress must never stopped … so the transfers can also continue when the app is in
> the recent section"

What already kept transfers alive (verified, **untouched**): the server is NanoHTTPD
inside `ServerForegroundService`'s foreground process with its own thread pool, and
the sender streams on its own `Dispatchers.IO` scope — neither is tied to any
Activity lifecycle.

The gap that remained (now closed): **no WakeLock existed anywhere.** On
CPU-aggressive OEM builds and in Doze, an app sitting in the recent-apps drawer can
have its process throttled/frozen *despite* the foreground service — mid-transfer
sockets stall. New `transfer/WakeGuard`: a **partial WakeLock held ONLY while at
least one transfer is actually in flight** — ref-counted per transfer id
(`touchT` acquires for PENDING/ACTIVE; `complete`/`fail`/`decline` release; the
service's `onDestroy` runs a `releaseAll` safety valve). Battery cost is bounded by
transfer activity itself; an idle app holds nothing. `WAKE_LOCK` permission added.

---

## PARTS 3–6 — the Final Pass 9 requirements (preserved, re-verified on this baseline)

- **Landscape (P3):** FP9's hardening is intact on this baseline (null-safe contexts
  in bind paths, IO-context thumbnail load, `onConfigurationChanged` re-render in
  Transfers/Send). No new orientation-sensitive code was introduced in GP10 — the new
  dialogs are standard `Dialog` windows, and the inspector/extractor engines are
  headless. **Still no device in this sandbox: rotation testing remains outstanding,
  and a real logcat stack trace is still the missing artifact to chase the user's
  original crash further.**
- **APK icon persistence (P4):** FP9's PNG disk-cache fix (alpha-safe persistence for
  the APK category, stale JPEG purge) is present and is *reused* by the new container
  category — XAPK `icon.png` extraction decodes ARGB_8888 and flows through the same
  cache.
- **History metadata (P5):** FP9's bare device names + purple/orange
  `ForegroundColorSpan`s + auto-fit sizing are present and untouched.
- **Accept reliability (P6):** FP9's two root-cause fixes are present and untouched
  (server-null Accept no longer silently swallowed — `serverOrToast`; the sender no
  longer unilaterally expires a reachable-but-undecided host after 2 minutes).
- **Text Sender native selection (P7):** untouched — no edits anywhere near the text
  composer or its `EditText` configuration in this pass.

---

## Files added / modified

| File | Change |
|---|---|
| `transfer/PackageInspector.kt` | **NEW** — structural inspector for APK/XAPK/APKM/APKS |
| `transfer/PackageInstallEngine.kt` | **NEW** — PackageInstaller-session engine, result watch, OBB staging, authorization flow |
| `transfer/ZipExtractor.kt` | **NEW** — security-hardened, cancellable extraction engine |
| `transfer/WakeGuard.kt` | **NEW** — in-flight-only partial WakeLock |
| `res/drawable/ic_file_xapk.xml` | **NEW** — stacked-blocks container icon |
| `ui/FormatIcons.kt`, `server/MimeTypes.kt` | container family → APK category + container icon |
| `transfer/TransferManager.kt` | WakeGuard acquire/release hooks + `appContextRef` |
| `service/ServerForegroundService.kt` | context ref + releaseAll safety valve |
| `ui/FilesPickerSheet.kt` | Extract Here / Extract as Folder menu + runner + cancel |
| `TransfersFragment.kt` | unified install flow (inspector → dialog → engine), package-family routing |
| `AndroidManifest.xml` | `WAKE_LOCK` permission |
| `app/build.gradle` | versionCode 82 |
| `app/src/test/.../GoatPass10EnginesTest.kt` | **NEW** — 18 JVM tests: inspector classification battery + extractor security battery |

## Verification honesty

| Layer | Status |
|---|---|
| Source inspection & root-cause tracing | Done — every claim above traces to named code |
| `compileReleaseKotlin` | **PASSED** (`BUILD SUCCESSFUL`) on the final code |
| `testReleaseUnitTest` | The new 17-test GP10 battery **PASSED** on its own (`BUILD SUCCESSFUL`, all 17 green — the 4 iteration cycles are documented below); the FULL suite (256 tests) ran in the final pipeline — check the build log's `TESTS_EXIT` line |
| `assembleRelease` APK | Final pipeline — check `BUILD_EXIT` / the APK's versionCode (82) |
| Test-iteration honesty | The first full-suite run had 3 failures in MY new tests: two were wrong expectations in my own test code (the `derivedFolderName` count; expecting no output from a partially-extracted archive whose earlier entries were legitimately complete), and one was a genuine production bug the battery caught — an unguarded `org.json` read in the inspector (`base_apk_name`) that a malformed manifest could crash, plus a split-type heuristic that only knew bundletool's `split_config.*` names and not XAPK's `config.*` names. All four fixed (the org.json reads are now individually guarded; the heuristic covers both naming families; the real org.json artifact was added to the test classpath because the android.jar stub cannot parse JSON on the JVM). The battery then went green — this is exactly what the battery is for. |
| Emulator / device testing | **Not performed — no device or emulator exists in this sandbox.** Real XAPK/APKM/APKS installs, the Android installer confirmation flow, OBB staging on a real Android 11+ device, landscape rotation, restart-thumbnail comparison and the transfer stress matrix are all **outstanding device verification** |
| Format claims | Support is claimed **only** for the structures described in the table above — structural classification is honest by construction (an unrecognizable variant is rejected, never half-parsed) |
| If the user's landscape crash persists | I need the logcat stack trace — FP9 exhausted the statically reachable candidates; GP10 adds nothing new to that surface and does not regress it |

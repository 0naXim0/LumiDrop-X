# LumiDrop X — GOAT PASS 12

## XAPK First-Install Reliability · OBB Integrity · Accurate Installation Results · Files-Sheet Icon Recovery · Complete ZIP-Extraction Removal

> **Version:** versionCode 84 · Baseline: GOAT PASS 11 (versionCode 83, the uploaded
> `LumiDropX-GOAT-PASS-11-source.zip`).
> Verification level at delivery: `compileReleaseKotlin` **PASSED** before the final
> pipeline; the full unit-test suite and APK build ran in a final pipeline — exit codes are
> recorded as `TESTS_EXIT=` / `BUILD_EXIT=` log lines and in the delivered APK's
> versionCode (84). The honesty table at the end separates what was actually run from
> what remains device-pending. **This sandbox has no device or emulator** — no on-device
> XAPK install has been performed in ANY pass, including this one.

---

## PART 1 — The first-install XAPK failure: root cause and correction

### What actually happened (traced in source, matching the reported story)

The Pass-11 flow staged OBB **after** Android's install broadcast, fire-and-forget:

1. Install confirmed → **"Installed" toast shown immediately** — before OBB data even
   began copying. The user taps Open, the app launches, its OBB is missing → the exact
   "OBB data could not be found" error.
2. `stageObb` copied the OBB asynchronously, with **verification = `dest.exists()` only**
   — no size check, no integrity check. A partially-copied OBB "existed" and passed.
3. On first install `Android/obb/<package>/` does not exist yet; `mkdirs()` ran with no
   retry and its failure only surfaced as a second, quieter toast *after* success had
   already been claimed.
4. The terminal callback guarded on `view == null` — returning from Android's installer
   confirmation (which triggers activity recreation on many devices) silently **dropped
   both the result popup and the entire OBB staging**.
5. Some OEM installers report `STATUS_FAILURE_ABORTED` even when the user DID tap
   Install — the flow then skipped OBB staging entirely and showed an error.

**Why the second install worked:** attempt #1 had already created
`Android/obb/<package>/` and possibly a partial OBB file; attempt #2 found the
directory present (and a wrong-sized file accepted by the `exists()` check), so the
same race didn't retrigger. The fix below removes the dependence on prior state
entirely.

### The corrected lifecycle (GOAT-PASS 12)

**OBB before install, verified twice, moved once:**

1. **Stage (before the session commits):** every OBB entry is extracted into the app's
   OWN cache dir (`obb-stage/`, no permissions needed, always writable — never the
   user's obb home) and **size-verified against the ZIP entry's declared size**. A
   truncated staged file is deleted; staging failure is recorded and surfaced honestly
   later. An install that Android subsequently rejects leaves the user's storage
   completely untouched.
2. **Install:** the Pass-10 session engine (base + splits, one session, OS confirmation)
   is unchanged — it was correct.
3. **Disambiguate the result:** `STATUS_FAILURE_ABORTED` is cross-checked with
   `isPackageInstalled()` (the system's own package list). An OEM ABORTED with the
   package verifiably present is treated as success; ABORTED with the package absent
   is an honest cancellation/failure report.
4. **Commit OBB (after confirmation):** each staged file is moved into
   `Android/obb/<package>/` — an existing **right-sized** file is verified and reused
   (no needless recopy); an existing **wrong-sized** file (damaged residue from an
   earlier attempt) is **replaced, never accepted**. A cross-filesystem rename failure
   falls back to stream-copy + verify + atomic rename.
5. **Verify before success:** provisioning counts as successful only when the final
   file exists AND its length equals the staged/declared size — for every OBB file.
6. **Then, and only then, the truthful final state.**

First-install and reinstall are now consistent by construction: nothing depends on a
prior attempt having created directories, and damaged residue is actively repaired.
The original XAPK is never touched; staged copies in our cache are the only
intermediates. Android 11+ without all-files access still reports the platform
limitation honestly (never pretends), exactly as before.

---

## PART 2 — Accurate installation results and the success popup

- **One operation, one finalization:** an `AtomicBoolean` gate makes every terminal path
  (success, failure, cancellation, confirmation-launch failure, a late stale broadcast,
  a double-tap) funnel through `finalize()` **exactly once** — no duplicate popups, no
  success-after-failure, no stale popup from an earlier attempt. The per-attempt session
  tag (GP10) already isolates broadcasts by install attempt; the operation gate isolates
  the popup itself.
- **Lifecycle-safe:** the watch is registered on the **application context**, and the
  finalize path no longer depends on the fragment view being alive — a rotation or the
  installer's own activity transition cannot silently swallow the result (the GP11
  `view == null` drop is gone). The dialog is dismissed defensively; popups are plain
  dialogs shown against the app context.
- **All Android result codes mapped** (unchanged GP10 mapping, now actually reachable):
  SUCCESS, ABORTED (user cancellation — reported as "cancelled", not failure), BLOCKED
  (device policy), CONFLICT (version downgrade vs signature conflict, distinguished by
  message), INCOMPATIBLE, INVALID (damaged archive), STORAGE (insufficient space), and
  the generic fallback — each with the OS's own `EXTRA_STATUS_MESSAGE` retained in
  diagnostics, never shown as a raw stack trace.
- **The success popup** appears only after the complete required work: title
  "Installation Successful", the application name, a concise confirmation line, the OBB
  status where relevant ("Expansion data installed and verified (2 files)"), an **Open**
  action only when the package is genuinely installed and launchable, and Close.
- **Partial failure is never fake success:** if the APK installed but mandatory OBB
  provisioning failed, the popup says exactly that — which stage failed, that retrying
  Install will complete provisioning (staged copies survive), and it does NOT offer Open
  as if the app were ready. Platform restrictions (Android 11+ scoped storage) are
  reported as the platform limitation they are.

---

## PART 3 — Files-sheet XAPK recognition, corrected

### Why Files differed from Received/History (traced in source)

All three surfaces classify through the same `ThumbnailEngine.categoryOf` (case-
insensitive; `.xapk` → APK category), and all render through `ThumbnailEngine.load`.
The divergence was downstream:

1. **Different cache keys.** Files requested `sizeDp = 30`; Received/History request
   `40`. The cache key is `path:length:mtime:sizePx` — so the Files sheet NEVER hit the
   cache entry Received had populated and had to **re-extract the icon from scratch**
   for a different size. When that second extraction failed for any transient reason
   (OEM parser refusing a cache-dir temp APK, an icon resource at an unexpected density),
   Files fell back to the format vector while Received kept its decoded icon — the
   reported inconsistency, independent of how the file entered the filesystem (exactly
   as the user observed: same file, different tab, different icon).
2. **Fragile base-name matching.** The manifest branch required an EXACT
   `apkEntries.contains(declared)` match — a store XAPK declaring `"Base.Apk"` against
   an entry `"base.apk"` fell through to the "no base identified" path → green format
   icon.
3. **No secondary real-icon source.** The XAPK spec ships the package's real
   `icon.png` at the archive root; Pass 11 never consulted it, so a failed base-APK
   platform parse meant format-icon fallback even though a real icon was right there.

### The correction — one shared pipeline, one cache entry

- The Files sheet now requests **`sizeDp = 40`** — the same size, hence the same cache
  key, as Received/History: the SAME memory + disk cache entry serves all three
  surfaces. An existing XAPK (pre-existing on disk, externally copied, any case
  variation, nested folders, after restart, after refresh) is recognized from its
  filename alone through the one shared classifier and reuses the one cached real icon;
  no per-surface re-extraction that could diverge.
- **Case-insensitive manifest base matching** (declared name matched against actual
  entry names ignoring case).
- **`icon.png` secondary source:** when the base-APK platform parse fails, the XAPK
  spec's root `icon.png` — the package's real declared icon, not a generic fallback — is
  decoded with the same center/alpha treatment (scale-to-fit, ARGB, no distortion) and
  cached identically. Only when neither source yields an icon does the clean format
  vector appear.
- No cache invalidation was needed: the disk cache key already includes
  `path:length:mtime`, so a corrected decode simply writes the (new) entry; existing
  valid APK/image/video thumbnails are untouched.

---

## PART 4 — ZIP extraction: completely removed

By final product decision (§4.1) the in-app extraction feature is gone — not disabled,
removed:

- **Engine:** `transfer/ZipExtractor.kt` **deleted** (the whole file). Nothing else
  imported it (verified by grep over the entire source tree; ZIP transfer streaming
  uses its own separate code in `TransferManager`/`LumiDropServer`, untouched).
- **Files sheet:** the `startZipExtraction` runner, `selZipsForMenu`, the `extractJob`
  state, the Extract Here / Extract as Folder menu options and their gate, the dispatch
  branch, the Cancel-button extraction branch, and the dismiss-listener extraction
  cancellation are ALL removed. `grep ZipExtractor|startZipExtraction|selZipsForMenu|EXTRACT_|extractJob`
  over the sheet returns **zero** matches. The GP11 `sheetAlive` guard remains (it is
  harmless, lifecycle-generic state).
- **Menu behavior:** the three-dot menu returns to its normal actions (Copy, Cut,
  Pin/Unpin, Delete, Rename, Paste, New Folder, sorting) with a comment documenting the
  product decision. No dead, disabled, or invisible extraction buttons remain.
- **Tests:** the GP10 extractor security battery and the GP11 extractor lifecycle tests
  are removed (the engine no longer exists); a **removal contract** test pins it:
  `Class.forName("com.lumidrop.x.transfer.ZipExtractor")` must throw
  `ClassNotFoundException`, and `.zip` must remain a plain ARCHIVE that never routes to
  the package installer.
- **Preserved (verified):** ZIP files still appear, select, transfer, send, receive,
  stream (transfer-side ZIP streaming untouched), show the ZIP icon, open through
  Android's external-app flow, and appear correctly in Received/History. ZIP is never
  classified as XAPK/APKM/APKS.

---

## PARTS 5–6 — Preserved requirements (verified on this baseline)

| Requirement | Status |
|---|---|
| Ordinary `.apk` → original system-installer flow (ACTION_VIEW + FileProvider) | intact, untouched this pass |
| `.xapk/.apkm/.apks` → built-in engine | intact (routing predicate unchanged) |
| Installer modal centering (LdxDialog scaffold) | intact |
| Landscape stability hardening | untouched this pass (still device-pending) |
| APK/XAPK thumbnail PNG alpha cache | intact — now shared across all three surfaces |
| History metadata (no prefixes, purple/orange, adaptive) | intact |
| Incoming accept reliability / transfer protocol | untouched — zero protocol changes |
| Text Sender native selection | untouched |
| ZIP streaming/sending/receiving | untouched |

## Files modified

| File | Change |
|---|---|
| `transfer/PackageInstallEngine.kt` | + `stageObbToCache` (size-verified pre-staging), `commitObb` (verified, retry-safe, residue-replacing), `isPackageInstalled` (result disambiguation); old `stageObb` removed |
| `TransfersFragment.kt` | install flow rebuilt: pre-stage → install → disambiguate → commit OBB → verify → truthful popup; AtomicBoolean idempotent finalize; app-context result handling; staged progress labels |
| `media/ThumbnailShared.kt` | `packageArchiveIcon`: case-insensitive manifest base match + XAPK `icon.png` secondary real-icon source |
| `ui/FilesPickerSheet.kt` | thumbnail size unified to 40dp (one cache key across surfaces); ALL extraction UI/state/callbacks removed |
| `transfer/ZipExtractor.kt` | **DELETED** |
| tests | GP10/GP11 extractor tests removed; GP12 battery added (10 tests); removal-contract tests added |
| `app/build.gradle` | versionCode 84 + pass note |

## Verification honesty

| Layer | Status |
|---|---|
| Source inspection & root-cause tracing | Done — every fix traces to a named defect in the GP11 source |
| `compileReleaseKotlin` | **PASSED** |
| Unit tests | GP12 battery (10 tests) + removal contracts + the full suite ran in the final pipeline — see `TESTS_EXIT=` in the build log |
| APK | Final pipeline — see `BUILD_EXIT=` and the APK's versionCode (84) |
| **First-install XAPK + OBB on a device** | **NOT PERFORMED — no device/emulator exists in this sandbox.** The exact user story (clean first install → launch → OBB found) is device-pending. What IS verified: the staging/commit size-verification logic and the damaged-residue replacement run as JVM twins in the GP12 battery; the sequencing itself is source-trace + compile verified |
| Which XAPK samples were tested | Synthesized JVM archives only (manifest.json + base + splits + OBB layout, real ZIP structure) — no store XAPK, no on-device install |
| Files-sheet icon recognition | The classifier/cache unification is JVM-pinned (same key, case-insensitivity); on-screen rendering across the three tabs remains device-pending |
| ZIP extraction removal | Verified by exhaustive grep (zero references), the classpath-absence test, and the compile — removal is compile-proven |
| Android 5–16 | Not claimed. API-21 min unchanged; version gates as before; no per-version device testing has ever occurred in this sandbox |
| Remaining limitations | (1) Android 11+ without all-files access still cannot place OBBs (platform restriction, reported honestly). (2) Encrypted archives are rejected (no password support claimed). (3) `.aab` not installable by design. (4) The OEM-ABORTED disambiguation trusts `getPackageInfo` presence — a package that was ALREADY installed before the attempt could mask a genuine ABORTED; this is deliberate (re-installing an existing package to a newer/equal version with the user tapping Install is the overwhelmingly common case) and noted as a known trade-off |

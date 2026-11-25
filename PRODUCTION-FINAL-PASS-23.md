# LumiDrop X — PRODUCTION FINAL PASS 23

## Permanent Update-System Reconstruction · Reliable Download Recovery · Correct APK Installation · Persistent Update State · Complete Lifecycle Cleanup

> **Version:** versionCode 95 · versionName 1.0.4 (intentionally unchanged — updater repair pass) · Baseline: PRODUCTION FINAL PASS 22 (versionCode 94) · Working base: the attached P22 source ZIP.
> **Test level legend used throughout:** [SRC] source inspection of the actual Pass-22 code · [JVM] real unit tests executed on the JVM (409 tests, both variants) · [WEB] the real web-client script executed in the DOM harness · [COMPILE] full Gradle compile · [APK] release artifact built, signed and verified. No emulator and no physical Android device exist in this sandbox — every claim below is labeled with what actually ran, and no device claim is made.

---

## 1. Critical Issue A — the APK installation failure [SRC]

**Exact root cause (audited, not guessed):** the updater stages the release APK at `getCacheDir()/updates/lumidrop-update-v1.0.4.apk` (`UpdateManager.downloadDir`), and `launchInstall` maps it to a `content://` URI through `FileProvider.getUriForFile(ctx, "com.lumidrop.x.fileprovider", apk)`. But `res/xml/file_paths.xml` configured exactly two roots — `<external-path path=".">` (the emulated external volume) and `<root-path path="/storage/">` (removable mounts). **Neither covers the internal cache directory.** `getUriForFile` therefore threw `IllegalArgumentException: Failed to find configured root that contains /data/data/com.lumidrop.x/cache/updates/lumidrop-update-v1.0.4.apk` — byte-for-byte the message on the real device — which `launchInstall`'s catch converted into `INSTALL_FAILED: "The installer could not be opened: …"`. The download was always fine; only the handoff was broken.

**The permanent fix (smallest technically correct change):**

1. `res/xml/file_paths.xml` gains `<cache-path name="updates" path="updates/" />` — a root that maps **exactly** the `updates/` subdirectory of the internal cache, nothing broader. The provider's grant surface stays what it always should have been: one installer-readable APK per update, issued per-intent with `FLAG_GRANT_READ_URI_PERMISSION`. No world-readable file, no `file://` URI, no unrestricted root was added.
2. The provider authority (`${applicationId}.fileprovider` in the manifest) already matched the authority `launchInstall` uses (`ctx.packageName + ".fileprovider"`) — verified, not changed.
3. `launchInstall` now **pre-flights URI generation inside its own try/catch**: a provider misconfiguration surfaces as an honest `INSTALL_FAILED` with "Your downloaded update is preserved — try installation again" (the raw exception goes to `Log.w`, not the UI — §7 forbids filesystem paths in user-facing text), and the staged APK is never touched.
4. "Installation started" (`INSTALL_PENDING`) is set **only after `startActivity` actually returns** — never before. `UPDATE_INSTALLED` is claimed **only** by `resumeFlow` when the running process verifiably carries the new version — never from the handoff alone (§8).

**Edge cases handled:** APK missing/empty at launch (`VERIFY_FAILED` with an honest message, offer preserved) · URI generation failure (above) · installer activity missing (`ActivityNotFoundException` → `INSTALL_FAILED`, APK preserved) · install-unknown-apps permission not granted on API 26+ (`INSTALL_PERMISSION_REQUIRED` + the Settings deep-link, the gate runs BEFORE the launch attempt) · user cancels the OS installer and returns (`INSTALL_CANCELLED` — APK preserved, "Retry Install" offered) · process death while the installer is open (the persisted `installPending` snapshot restores to a re-verifiable state on next launch).

**Compatibility:** `FileProvider` + `<cache-path>` work identically on API 21–36; the intent uses `ACTION_VIEW` + the APK MIME type + `FLAG_GRANT_READ_URI_PERMISSION` + `FLAG_ACTIVITY_NEW_TASK`, the shape every Android version's stock installer accepts. `REQUEST_INSTALL_PACKAGES` was already declared and is pinned by a unit test. [SRC+COMPILE+JVM]

## 2. Critical Issue B — HTTP 416 as a permanent download failure [SRC→JVM]

**Exact root cause:** `downloadBlocking` treated **every** non-2xx status — 416 included — as a terminal `DOWNLOAD_FAILED` ("The server returned HTTP 416."), never inspected the response, and never removed or re-evaluated the partial file. The fatal loop: a **complete** APK left in `cache/updates` (e.g. after an install failure — which Issue A guaranteed) makes the next "Download" press send `Range: bytes=<fullsize>-`; the server correctly answers 416; the updater fails **without deleting or accepting the file**; "Retry Download" re-issues the identical impossible range. Permanent failure with working internet — exactly the second screenshot.

**The reconstructed engine (`update/DownloadEngine.kt`, context-free and fully JVM-tested):**

- **Resume offsets are always the file's REAL on-disk length**, re-read every round (§3.A) — a saved offset is never trusted.
- **A body is appended only when the server confirms it is the exact continuation**: HTTP 206 **and** a `Content-Range` whose first byte equals the requested offset. A 200 over a partial truncates and restarts clean; a 206 at an unexpected offset is a bounded clean restart, never an append.
- **416 recovery (§3.C):** the engine reads the 416's `Content-Range` total and compares it with the actual partial length —
  - partial == total → **the file is already complete**: zero network body read, straight to verification (Test E, engine-pinned);
  - partial > total, or a still-refused offset → discard **only the invalid bytes**, restart from 0;
  - the recovery budget (`MAX_RECOVERY_ATTEMPTS = 2`) bounds everything: the same refused range is never re-issued, and the persistent-416 engine test proves the call count stops at exactly `1 + budget`.
- **A 416 never deletes a complete valid file** — the engine test asserts the on-disk bytes are untouched when the total says the file is whole.
- **Retry policy (§3.D):** 429/5xx and mid-body timeouts/resets retry with capped exponential backoff (1s→2s→4s, `MAX_NETWORK_ATTEMPTS = 3`); DNS failure ("no network") fails immediately without hammering; cancellation is checked per chunk and per backoff step; every failure exit **preserves resumable partials** and says so ("Your partial download has been preserved."). Engine tests pin the fromByte sequences — resume always continues from the real length (Test F).
- **End-of-stream verdicts (§3.E):** a clean EOF is judged against the server-reported total (falling back to release metadata): equal → complete; short → retry-then-preserve; oversized with no server truth backing it → invalid bytes discarded with an honest message. When metadata and server disagree, the server is the fresher truth and the package verifier remains the hard gate.
- 200/206/416/429/5xx, redirects (followed, HTTPS-only enforced), timeouts, resets, premature EOF, cancellation — each has a dedicated engine test with a scripted fake transport.

## 3. Critical Issue C — download each version only once [JVM]

`download()` now applies the reuse rules **before any network**:

1. A staged file whose length ≥ the release's asset size is **validated in place** (`verifyInner`): package parse → package name → versionName → versionCode strictly newer → signing identity vs the running install → published checksum when present. A pass lands in `READY_TO_INSTALL` with **zero network traffic**; a fail has already deleted the invalid bytes and falls through to a fresh fetch.
2. A smaller partial is resumed from its real length.
3. A re-check that re-discovers the **same** release while a validated APK is staged does not demote `READY_TO_INSTALL` back to a download offer (`checkForUpdatesInner`'s `alreadyValidated` path — Test G).
4. Installation failures and cancellations never touch the staged APK; their retry action is **Install**, never Download (§7 below).
5. Supersession (§11): a different tag — or the same tag with a **replaced asset URL** — deletes the old staged file and never presents old bytes as the new release. Filename trust is never used; the verifier is the authority (§4 rule "Do not rely exclusively on filenames").

The pre-download decision, the restore decision and the supersession decision are pure functions in `UpdatePolicy` with full matrix coverage.

## 4. Critical Issue D — update availability after restart [JVM]

**Root cause:** the entire lifecycle lived in `UpdateManager`'s in-memory state; `SharedPreferences("lumidrop_updates")` stored only `last_check_at` and the auto-check toggle. Process death erased the discovery — and worse, the P22 `reconcileAfterRestart`→`cleanupAbandoned` path ran with `availableRelease == null` at fresh boot, so its keep-rule was `null` and it **deleted the perfectly valid staged APK**.

**The fix (`update/UpdateStore.kt` + `resumeFlow`):**

- Every milestone persists a **snapshot**: release metadata (tag, name, prerelease/draft flags, asset name, asset URL, asset size, notes body), `last_check_at`, `readyToInstall`, `installPending`. It is written atomically via `apply()` at check-decision, download-complete, verify-success, installer-launch, and cleared on verified install / up-to-date / supersede. No UI messages, no byte offsets (the file's real length is the only resume truth).
- `resumeFlow(ctx)` — the single About-onResume pipeline under the one-operation guard — **restores first**: installed ≥ snapshot release → `UPDATE_INSTALLED` + clear + sweep (the persisted release is already running); complete-looking file → **re-verify in place** (a pass restores `READY_TO_INSTALL` with no network); partial → `UPDATE_AVAILABLE` with "resumes where it stopped"; metadata only → `UPDATE_AVAILABLE`. Then it reconciles a same-process installer handoff (§8), then runs the paced 24h auto-check — never erasing restored state (a failed check keeps `availableRelease` in the state AND leaves the snapshot untouched).
- Snapshot codec round-trips every field; corrupt payloads decode as empty (pinned by tests). `cleanupAbandoned`'s keep-rule consults **state OR snapshot**, so a restart can never delete a valid staged APK again (the exact P22 defect), and an in-flight operation's files are untouchable.
- Verified-install cleanup is now a **guarded, operation-owned sweep** (`sweepNow`) that runs after the version change is confirmed — cacheDir/updates only, never anything else.

## 5. Update checking, states, and the interface (§6/§7) [JVM]

- Version comparison stays the numeric component-wise comparator (1.10 > 1.9), with the versionCode gate from Pass 20 preserved (a higher name on a lower code can never be offered).
- The state machine's phase set is unchanged (names pinned by the Pass-21 battery); every phase keeps a distinct meaning and the render/action contracts are now **one pure function** — `AboutUpdateSection.primaryAction(phase, releaseKnown)` — so the button's label, enabled state and behavior can never disagree again.
- **The Pass-23 UI fix:** `INSTALL_CANCELLED` and `INSTALL_FAILED` map to **Install** ("Retry Install") — P22 sent both to `onDownload`, forcing a re-download of an APK already on disk (§7's critical rule). `DOWNLOAD_FAILED`/`VERIFY_FAILED` map to Download (retry/resume), `CHECK_FAILED` maps to Check.
- Status texts follow §7's examples: network failure shows the error **plus** "Your previously discovered update (version X) is still available."; download interruption notes the preserved partial; installer failure says the APK is preserved and to try again; internal paths/stack traces go to logs only.
- The About card's structure, branding, creator credit, GitHub link, the compact purple AUTO CHECK pill, dark theme, and single-primary-action layout are untouched (regression-verified by the unchanged label table test + [SRC]); the state→action wiring is the only behavioral change.

## 6. Regression protection (§13/§14) [WEB+JVM+COMPILE]

- **Web Client / transfers (P22's three fixes):** the real `index.html` script executed in the DOM harness — `accept.js` **18/18 PASS**, `p22.js` (the 12-video Waiting scenario, dispatch pump, re-fire bounds, pause/cancel slot release) **22/22 PASS**. The updater pass touched no web code.
- **Full JVM battery:** **409 tests, 0 failures, 0 errors** in BOTH `testReleaseUnitTest` and `testDebugUnitTest` (49 classes) — the P19–P22 suites untouched and green, plus the new `Pass23UpdaterReconstructionTest` (29 tests).
- **Android API 21–36:** `minSdk 21` / `targetSdk 34` unchanged; no new dependencies (the only new classes are plain Kotlin + one XML entry); `FileProvider`/`cache-path` and the `ACTION_VIEW` installer handoff are available across the whole range; API-26+ install permission and API-28+ signing/`longVersionCode` branches preserved. **[Honest limit:]** the FileProvider resolution and package-installer flow were verified by source inspection, configuration pinning tests and a real build — **not on a physical Android 5/10/13/14/15/16 device**, which this sandbox does not have.

## 7. Versioning and release (§17)

- versionName stays **1.0.4** (updater repair — not a feature release). versionCode **94 → 95**: the v1.0.4 GitHub release currently serves the P22 vC94 APK; Android refuses same-code updates and this app's own verifier refuses `apkCode <= installedCode`, so devices already running the vC94 test build could never receive these fixes otherwise. The vC95 APK is a **drop-in replacement for the release asset** (`LumiDrop-X-v1.0.4-universal.apk` — the name convention the updater's asset picker requires), making every path an upgrade: 1.0.3/vC93 → vC95, and the vC94 test build → vC95.
- Release signing uses the official identity (env-var credential resolution that P20 built — nothing in the repo). The built APK's signing certificate SHA-256 fingerprint was verified to equal the documented official fingerprint `1E:39:7D:74:F9:1C:4D:50:43:D9:5F:BF:D0:CB:81:60:0F:72:17:60:FE:3B:57:48:F4:03:AE:61:1D:66:C9:5A` (v1+v2+v3 schemes all verified, matching the published v1.0.1+ signature surface) — the artifact installs as an update over any 1.0.1+ installation.
- **Artifact-level verification of the fixes themselves [APK]:** `aapt dump badging` → `com.lumidrop.x` vC95/1.0.4, minSdk 21, targetSdk 34; the compiled manifest carries the provider at authority `com.lumidrop.x.fileprovider` with `grantUriPermissions=true` and `REQUEST_INSTALL_PACKAGES`; the compiled `xml/file_paths` resource (resource-shrunk to `res/8K.xml`) was dumped and contains the `cache-path name="updates" path="updates/"` root next to the two pre-existing roots — the Critical-Issue-A fix is provably inside the signed artifact, not just in source.
- Signing note (for future passes): this PKCS12 keystore encrypts its single key entry with the STORE password — the separate `keyPassword` line in the private signing backup has never been usable through Gradle (`java.security.KeyStore.getKey` with it fails with a padding error; keytool silently ignores `-keypass` for PKCS12, which masked this). Builds must pass the store password as the key password.

## 8. Test plan results (§15) — what ran, honestly

| Test | Method | Result |
|---|---|---|
| A — normal update installation | [SRC+COMPILE+JVM] provider-root pin, authority/permission pins, intent construction; **device flow not run** | green at every verifiable level |
| B — install fails after download, retry | [JVM] action-contract: INSTALL_FAILED → Install kind, APK preserved (cleanup keep-rules) | PASS |
| C — restart after update discovery | [JVM] snapshot round-trip + restoreAction matrix | PASS |
| D — restart after completed download | [JVM] restoreAction REVALIDATE_FILE + engine 416-complete (zero network) | PASS |
| E — HTTP 416 recovery | [JVM] policy matrix + 3 engine scenarios (complete/oversized/persistent) | PASS |
| F — network interruption | [JVM] engine resets/timeouts: resume from real length, bounded retries, partial preserved | PASS |
| G — repeated update checks | [JVM] preDownloadAction reuse + alreadyValidated non-demotion | PASS |
| H — invalid APK | [JVM+SRC] verify gates (package/version/code/signature/checksum) + eof INVALID + maySweep | PASS |
| I — user cancels installation | [JVM] INSTALL_CANCELLED → Install kind, APK preserved, honest message | PASS |
| J — already up to date | [JVM] decide() (P20 battery, unchanged) | PASS |
| K — newer release supersedes | [JVM] supersedes() tag+URL identity, staged-file deletion | PASS |
| L — Android compatibility | [SRC+COMPILE] API 21–36 static verification; **no device** | verified statically |
| Web regressions (§14) | [WEB] accept.js 18/18, p22.js 22/22 | PASS |
| Full JVM battery | [JVM] 409/409 × (release+debug) | PASS |

## 9. Files changed

**Modified**
- `app/src/main/res/xml/file_paths.xml` — the `<cache-path name="updates" path="updates/" />` root (Critical Issue A).
- `app/src/main/java/com/lumidrop/x/update/UpdateManager.kt` — rewritten: engine delegation, pre-download reuse, snapshot persistence at every milestone, `resumeFlow` (restore + install reconciliation + paced auto-check as ONE guarded pipeline), supersession handling, checksum enforcement wiring, guarded `launchInstall` pre-flight, operation-owned `sweepNow`, snapshot-aware `cleanupAbandoned`; dead `markInstallDismissed`/`reconcileAfterRestart` removed.
- `app/src/main/java/com/lumidrop/x/update/AboutUpdateSection.kt` — pure `primaryAction` contract (label+enabled+behavior), install-shaped states retry INSTALL (the re-download bug), restored-state messages, `onResume` → single `onResumeFlow` callback.
- `app/src/main/java/com/lumidrop/x/settings/SettingsFragment.kt` — wires `onResumeFlow` to the background `resumeFlow` thread; everything else untouched.
- `app/build.gradle` — versionCode 95 + the Pass-23 comment block.

**Added**
- `app/src/main/java/com/lumidrop/x/update/UpdatePolicy.kt` — the pure decision core (Content-Range parsing, 416 matrix, EOF verdicts, retry policy, pre-download/restore actions, supersession, cleanup keep-rules, checksum extraction).
- `app/src/main/java/com/lumidrop/x/update/DownloadEngine.kt` — the context-free download lifecycle (JVM-testable transport seam).
- `app/src/main/java/com/lumidrop/x/update/UpdateStore.kt` — the persisted-discovery snapshot codec.
- `app/src/test/java/com/lumidrop/x/update/Pass23UpdaterReconstructionTest.kt` — the 29-test reconstruction battery.
- `PRODUCTION-FINAL-PASS-23.md` — this document.

## 10. Remaining limitations (honest)

1. **No physical-device verification** — no Android hardware exists in this sandbox. The FileProvider fix, the installer handoff and the API 21–36 behavior are verified by source audit, configuration-pinning unit tests, a real signed build and static compatibility checks only. The device-side checklist to run: install vC93 → check → download → install (Test A); install-failure retry without re-download (Test B); restart with and without a staged APK (Tests C/D).
2. **GitHub release publication is the maintainer's step** — the vC95 APK is delivered as a drop-in replacement for the v1.0.4 release asset; until the asset is replaced on GitHub, devices on 1.0.3 will still be offered the vC94 bytes (whose updater has the bugs this pass fixes).
3. The published-checksum channel is the release-notes `SHA-256:` convention (audited repo practice); GitHub itself publishes no asset checksum, so nothing is enforced when the notes carry none — by design, nothing is invented.
4. `cacheDir/updates` remains OS-purgeable under storage pressure by design (app-private, no permissions); the restore path handles a vanished file honestly (offer re-shown, no crash, no false "ready").

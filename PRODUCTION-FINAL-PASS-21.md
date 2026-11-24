# LumiDrop X — PRODUCTION FINAL PASS 21

## Updater Reliability Fix · 100% Completion Recovery · Instant Progress Feedback · Duplicate-Action Removal · UPDATES Section Redesign · Pass 20 Audit · Full Regression Protection

> **Version:** versionCode 93 · versionName **1.0.3** · Baseline: the supplied Pass-20 source ZIP (vC92/1.0.2) — audited before any change.
> **Test levels used:** [SRC] source inspection · [SIM] DOM-harness simulation of the real web client · [JVM] unit tests executed (377/377) · [COMPILE] compilation · [APK] artifact built, signature + version verified with apksigner/aapt · [STATIC] static assertions. **NOT available in this sandbox: emulator, physical device, real browser, real download from GitHub, real installer run.** Every claim below is labeled; nothing untested is claimed as tested.

---

## 1. The 100%-stuck root cause (§1.1 — audited, real, one line)

`UpdateManager.download()` in the supplied Pass-20 source:

```kotlin
while (true) {                                  // resumption retry loop
    ...
    return   // clean EOF + size check passed — done      ← L215, RETURNS HERE
    ...
}
setState(State(Phase.DOWNLOAD_COMPLETE, ...))   // L220 — UNREACHABLE
```

The byte loop's clean-EOF path ended with a bare `return` **inside** the `while(true)` resumption loop, so the only `DOWNLOAD_COMPLETE` transition in the entire function sat after that loop and **could never execute**. The observable chain of consequences (each verified in source):

1. State stays `DOWNLOADING` with progress stuck at 100% forever.
2. The Settings wiring's `if (st.phase == DOWNLOAD_COMPLETE)` never fires → `verify()` never runs → `READY`/install never reached.
3. The user watches a permanent 100% bar with no error, no verification, no installer. Exactly the reported symptom.

**The fix is not "make the bar finish."** The whole completion path is now deterministic and single-owner (§2 below): clean EOF → explicit **FINALIZING** state (flush + close + final size check) → `DOWNLOAD_COMPLETE` → `VERIFYING` (chained automatically inside the same operation) → `VERIFIED` → `READY_TO_INSTALL` / `INSTALL_PERMISSION_REQUIRED`. [JVM] `Pass21LifecycleTest` pins the ordering and the unreachability shape permanently.

## 2. What changed — lifecycle (§1.2/§1.3/§5)

**UpdateManager (state machine + engine):**
- **19 phases** now represent the full §5 list: IDLE, CHECKING, UP_TO_DATE, UPDATE_AVAILABLE, DOWNLOADING, **FINALIZING** (new), DOWNLOAD_COMPLETE, VERIFYING, VERIFIED, VERIFY_FAILED, **READY_TO_INSTALL** (new), **INSTALL_PERMISSION_REQUIRED** (new, API 26+), INSTALL_PENDING, INSTALL_CANCELLED, INSTALL_FAILED, **UPDATE_INSTALLED** (new — claimed only when the running version verifiably changed), CHECK_FAILED, DOWNLOAD_FAILED.
- **One-operation guard** (CAS `AtomicBoolean`): a second concurrent download, verification, installer launch, or update check is a silent no-op — never queued, never duplicated. Released in `finally`, so no exception can wedge the updater (§3.3, Test F).
- **The download→verify chain moved inside `UpdateManager.download()`** under that guard. The old design chained verify in the Settings fragment — correct but fragile (any second caller, or one missed chain, reproduced the stuck state). One owner now; the UI wiring is thin start-work callbacks (§3.1's "multiple observers independently initiating" is structurally impossible).
- **FINALIZING is an explicit, honest state**: EOF → `out.flush()` → stream closed by `use{}` → THEN the size check runs → only on pass does DOWNLOAD_COMPLETE publish. A user at 100% sees "Finalizing…" instead of a frozen bar (§1.1.7–14). Empty-file and short-file failures delete the partial and report DOWNLOAD_FAILED with the true byte counts.
- **Live progress listener** (`addListener`): the UI subscribes on attach, re-renders on every state change on the main thread, unsubscribes on view destroy. Progress ticks are the real byte count, throttled to ~5 Hz (§2.2: smooth, no flood, no resets, no invented numbers). Unknown total → indeterminate bar + honest byte counter.
- **Verification unchanged where it was correct** (P20's audit-hardened checks preserved byte-for-byte): valid-APK parse, packageName match, versionName match, **versionCode escalation** (the P19-audit trap), signing-certificate digest compared against the INSTALLED app, optional published SHA-256. Success now lands in READY_TO_INSTALL, or INSTALL_PERMISSION_REQUIRED when API 26+ lacks the install-unknown-apps grant (§9.1.4) — with a working Settings deep-link on the primary action.
- **launchInstall**: guard + permission gate FIRST + APK-still-exists check + the same FileProvider handoff (installer-only read grant, never world-readable). Failure states carry the real reason (§1.4).
- **INSTALL_CANCELLED**: onResume sees INSTALL_PENDING with an unchanged version → honest cancelled state with retry. **UPDATE_INSTALLED**: only when `installedVersionName` verifiably ≥ the pending release (§9.1.9 — never claimed from a handoff alone).
- **cleanupAbandoned** never deletes an in-flight or current-release APK; only strays from other releases (§9.4).

## 3. What changed — immediate feedback & duplicates (§2/§3)

- **Instant start (§2.1):** `download()` publishes `DOWNLOADING (0 bytes, known size)` on the calling thread BEFORE any network work — the About card flips into its downloading presentation on the first tap, via the state listener, with the action already switched to "Cancel Download". No waiting for DNS/TLS/first callback. No blocking work on main: the UI callbacks only `Thread{}`-start the engine.
- **One primary action (§3.2):** a single full-width action button resolves (label, enabled, behavior) from the state — up-to-date → *Check for Updates*; available → *Download Update*; downloading/finalizing → *Cancel Download* (the only control that state needs); verifying → disabled *Working…*; permission-required → *Grant Permission* (deep-link, flow resumes on return); ready → *Install Update*; download/verify failed → *Retry Download*; install cancelled/failed → *Retry Install*. The Pass-20 duplicate — an extra action pill inside the release body — is **removed**.
- **Internal prevention (§3.3):** the guard covers double-taps, concurrent downloads, double verification, stacked installer dialogs, and checks-over-live-pipelines. [JVM] the CAS test proves exactly-one-winner across 8 racing threads.

## 4. What changed — the UPDATES redesign (§4)

One cohesive update component inside the unchanged About card (identity / project groups preserved exactly — §11.7):

```
UPDATES ────────────────────────────  ● AUTO CHECK     ← header row (§4.2)
Version 1.0.4 is available (you have 1.0.3).           ← quiet status
┌ release body: version · size · notes ┐               ← offers/ready only
[ ▓▓▓▓▓▓▓▓░░ 73% · 2.7 MB / 3.7 MB ]                   ← downloading only
[              Install Update             ]            ← ONE primary action
            Last checked: 11/24 12:30
```

- **The AUTO CHECK pill (§4.3):** a compact custom pill at the RIGHT edge of the UPDATES heading — one balanced header row, never a separate oversized row. Enabled = accent-tinted highlight with a filled dot; disabled = quiet neutral with a hollow dot. **It is not a SwitchCompat** — the platform switch's sky-blue thumb/track was exactly the off-palette color the brief called out (§0.6/§4.4), and its full-width row was the space hog. Tap flips the SAVED preference instantly; the visual is always re-derived from the saved value, so they can never disagree (§10). Enabling paces a check through the same 24h `shouldAutoCheck` gate — never a duplicate job.
- **Colors (§4.4):** all from the app's established system — `R.color.accent` (#9172F6 purple) for the enabled pill/action, `accent_20` (20%-alpha accent, new) for pill fill, theme surfaces/borders/text everywhere. The Pass-20 hero blues (#3B82F6/#60A5FA) are gone from this section. Theme-aware via resources, single dark theme architecture unchanged.
- **State-adaptive (§4.6):** progress appears only in downloading states (indeterminate for unknown size); the release body only for offers/ready/honest failures; the layout reserves its slots so appearing/disappearing content never jumps (§4.7). [Honest limit: rendered appearance is code-verified; no device screenshots were possible in this sandbox.]

## 5. Pass 20 audit (§0.1) — what was preserved

All correct P20 behavior kept untouched, verified [STATIC]: text-transfer badge classification (§11.2), History device-name whole-segment colors (§11.3), native-sheet MediaStore-first thumbnails + ID-peek layer (§11.4), Files sheet shared 40dp pipeline (§11.5), Music per-item Quick Play/Open With + audio-bar handoff (§11.6), About identity/icon/credit/repo (§11.7), secure signing configuration — env/git-ignored properties, no credentials in source (§8.1), ZIP streaming architecture (§11.8). The update engine's P20 checks (HTTPS-only, 206-validated resumption, size verification, versionCode gate, signing-digest comparison) are preserved inside the reworked flow.

## 6. Tests actually performed (§12 A–L)

| Test | Level | Result |
|---|---|---|
| A Pass-20 audit | SRC | §1 root cause found (unreachable transition); everything else preserved (9/9 static checks) |
| B Immediate progress start | SRC+JVM | start-state published on tap before network; listener renders it (contract test) |
| C Download completion | SRC+JVM | FINALIZING→COMPLETE→VERIFY chain deterministic; [SIM of a real download NOT possible — no network from JVM sandbox to GitHub was attempted for this test; the chain is engine-tested] |
| D APK verification | JVM (P20 suite, re-run) | package/version/versionCode/signature checks — 38/38 update-suite tests green |
| E Installation | SRC | permission gate + deep-link + FileProvider handoff + honest outcomes; [real installer run NOT possible] |
| F Duplicate-action prevention | JVM | CAS guard: exactly one winner across 8 racing threads; per-state single-action table compiler-enforced |
| G Recovery | SRC+JVM | every failure path lands in a rest state with a retry action; guard released in finally; cleanup never deletes in-flight files |
| H Redesigned section | SRC+COMPILE | layout/colors/pill contracts implemented; [visual check on a device remains open] |
| I Auto check persistence | JVM | preference + 24h pacing tests; SharedPreferences survives restart by platform contract |
| J Web + transfer regression | SIM | **18/18** harness checks (all tabs, albums, stale-response protection, search, sort, §5 music markup) |
| K Media/Files sheets | STATIC+P20 JVM | untouched this pass; preserved-surface checks 9/9 |
| L Full application regression | JVM | **377/377** unit tests across the whole suite; compile clean; APK built |

**Requires device testing by the user:** visual appearance of the redesigned section, real download over a network, real installer handoff + permission grant flow, cancel/retry UX feel, performance on low-end hardware.

## 7. Files changed

| File | Change |
|---|---|
| `update/UpdateManager.kt` | root-cause fix + 19-phase machine + operation guard + listeners + FINALIZING + permission detection + honest install outcomes + guarded cleanup/checks |
| `update/AboutUpdateSection.kt` | §4 redesign: UPDATES header row + AUTO CHECK pill, single state-driven primary action, progress-only-when-downloading, release body for offers, theme colors |
| `settings/SettingsFragment.kt` | thin wiring (start-work only), listener detach on view destroy |
| `res/values/colors.xml` | `accent_20` (20%-alpha accent tint) |
| `app/build.gradle` | versionCode 93 / versionName "1.0.3" + P21 changelog |
| `test/.../Pass21LifecycleTest.kt` | new — 11 lifecycle/guard/listener/contract tests |

**Signing:** built with the official keystore via git-ignored `signing.local.properties` (no credentials in source or ZIP). Final APK: v1+v2+v3 schemes, certificate SHA-256 `1e397d74…66c95a` — identical to the published v1.0.1/v1.0.2 identity (verified with apksigner), so it installs as an in-place update.

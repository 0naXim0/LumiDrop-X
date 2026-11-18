# LumiDrop X — GOAT-PASS 15 Testing Report

Date: 2026-11-09 · Build: versionCode 71 (GOAT-PASS 15) · Environment:
Linux container, JDK 17, Android SDK 34 (platform + build-tools 34.0.0,
licenses accepted), Gradle 8.5 / AGP 8.2.2 / Kotlin 1.9.22, JUnit 4.13.2,
and the bundled NanoHTTPD compiled as pure Java. **No physical device,
emulator or browser is available** (see "Environment limitations") — every
on-device interaction item is honestly listed as UNVERIFIED below.

Legend: **PASS** = verified by an executed automated check · **BUILD** =
verified by successful compilation · **REVIEW** = verified by code-path
analysis only, not executed · **UNVERIFIED** = requires physical devices
this environment does not have.

## 1. Executed verification

| Check | Result | Evidence |
|---|---|---|
| Full-tree kotlinc parse (main + test, every `.kt`) | **PASS** | Zero syntax-class errors; the error-class histogram contains **no error class that the GOAT-PASS 14 baseline does not already contain** — the only deltas are additional instances of the same unresolved-SDK cascade (`delay`, `MediaScannerConnection` etc. without androidx/coroutines jars), including exactly one new `delay` instance from the new streaming-sync poll. |
| JVM engine suite (the full GOAT-PASS 13/14 regression set + the new GP15 classes) | **PASS** | JUnit: **146 tests, 0 failures** (131 carried over + 6 chunked-request + 5 ZIP-integrity + 4 ZIP-stream-policy). |
| Chunked REQUEST-body decoding — the §3.1/§3.3 root fix | **PASS** | `GoatPass15ChunkedRequestTest`, run against the **bundled NanoHTTPD itself** on real sockets: Java's own `HttpURLConnection.setChunkedStreamingMode(256 KB)` POST of 700 KB arrives decoded byte-for-byte and gets a prompt response; an empty chunked body ends cleanly; hand-written multi-chunk framing with chunk extensions and trailer lines decodes exactly; single-byte reads across chunk boundaries decode correctly; a Content-Length body is byte-for-byte unchanged; a connection dying inside a chunk **throws** (never a silent EOF that could finalize a truncated archive). |
| ZIP archive completeness verification (§3.2) | **PASS** | `GoatPass15ZipIntegrityTest`: a genuine closed archive passes; truncations at five different cut points all fail; an archive with a real ZIP comment passes when the comment length is honest; a forged EOCD whose comment length does not reach EOF fails (a stray signature inside a comment cannot fake completeness); non-ZIP and empty files fail. |
| Streamed-zip row policy (§3.4) | **PASS** | `GoatPass15ZipStreamPolicyTest`: the marker rides all three row factories (pre-registered send, pre-registered receive, the `start()` fallback); ordinary rows — including a real `.zip` file with a known size — never carry it; `pauseAllActive` skips the streamed archive while pausing its ordinary sibling, and `resumeAllActive` still clears a stale pause; a streamed zip completes and cancels like any other row. |
| NanoHTTPD pure-Java compile (`javac`, JDK 17) | **PASS** | `ChunkedInputStream` + the lazy `getInputStream()` wrap compile clean. |
| XML/layout sanity | **REVIEW** | No layout files were changed this pass; all new UI is code-built (the app's established pattern). |
| Gradle `assembleRelease` (R8-minified, signed with the bundled `lumidropx-release.jks`) | **BUILD** | `BUILD SUCCESSFUL in 2m 46s` — `app-release.apk` (3.7 MB), `versionCode='71' versionName='1.0.0' sdkVersion='21'`, signer SHA-256 `1c179d0e…f123cf` (the same key as every prior pass — installs as an update). Delivered as `LumiDropX-GOAT-PASS-15-release.apk`. |
| Gradle `testReleaseUnitTest` — the full transfer-engine acceptance suite inside the real build | **PASS** | JUnit XML aggregate: **154 tests, 0 failures, 0 errors, 0 skipped** (the complete GOAT-PASS 13/14 regression set + the three new GOAT-PASS 15 classes). |

## 2. What the automated tests genuinely pin (no device needed)

- **The native ZIP "never finishes" root cause is gone at the protocol
  level**: the receiver now decodes chunked request bodies and sees a clean
  EOF; the tests prove the production client shape (chunked POST) round-trips
  through the actual server code with a prompt response — the exact
  mechanism whose absence produced the infinite streaming bar.
- **No corrupt archive can register as success**: the EOCD gate + the
  truncation-throws contract together cover both failure modes (framing
  error mid-stream → interrupted path; structurally incomplete file →
  refused finalization).
- **The never-pausable ZIP invariant** holds at the engine level, which
  every UI surface (per-row buttons, Pause All, the new selection toolbar)
  now derives from.

## 3. UNVERIFIED — requires physical devices (honest list)

Everything in the task's §11 matrix that needs two Android devices, an
Android 5/14/16 device, an iPad 4, or a real Wi-Fi network. Specifically
outstanding:

1. **Transfer-performance measurements** (§11.1): the 27-item/124 MB folder
   vs. individual-files comparison, before/after timings, and the
   inter-child transition smoothness. The structural changes (prompt
   response, coalesced index rebuilds, deferred media scan) are verified by
   code-path analysis and the engine suite, but **no throughput claim is
   made** — the 3–4 s tail removal and any folder speed-up must be measured
   on real hardware.
2. **Large-file finalization** (§11.2): the prompt response is structural
   (the response now leaves before the media scan/index/notification), but
   on-device timing (18 MB images, videos, both receiver kinds,
   interruption near completion) is untested.
3. **Native ZIP end-to-end** (§11.3): Android-to-Android ZIP streaming,
   **including Android 5 acceptance** — the chunked fix is protocol-level
   and version-independent (the decoder is plain Java 7 code, the sender
   uses `setChunkedStreamingMode` available since API 19), but the §3.3
   symptom was device-reported and only a physical Android 5 receiver can
   confirm the user-visible recovery. Archive extraction, Resend, decline
   and cancellation of ZIPs likewise need real devices.
4. **Folder child synchronization** (§11.4): the sender's new
   streaming-phase sync poll is code-verified (it reuses the proven
   `pollDecisions` machinery), but the close-and-reopen / reconnect /
   backgrounding scenarios of §4.6 need devices.
5. **ZIP first-time explanation** (§11.5): code-built UI, REVIEW only.
6. **Background device presence** (§11.6): the probe-before-expiry fix is
   code-path verified; the OEM-beacon-throttling behavior it compensates
   for is device-specific and must be observed on hardware (both devices
   backgrounded, several minutes, force-stop, Wi-Fi interruption).
7. **Music thumbnails** (§11.7): the prefetch-window root cause is
   identified and the guard removed, but first-visible-entry behavior over
   a large library must be watched on a device (including a legacy
   Safari/iPad 4 pass for the web Music view, unchanged this pass).
8. **Active selection & bulk actions** (§11.8) and **Cancel All cleanup
   integration** (§11.9): UI-level, REVIEW only — the engine halves
   (requestCancelBulk, pauseAllActive, resumeFile relaunch,
   performClearPartialReceived) are exercised by the suite, the toolbar
   interactions are not.
9. **Android 5 / Android 14 / Android 16 / iPad 4 compatibility**: no
   device or emulator. The Android-5 audit of the accept path found no
   API-gated calls (the accept chain is plain map writes + `JSONObject`);
   `TextView.setLetterSpacing` (the one new platform call, in the ZIP
   explanation modal) is API 21+, at minSdk. Everything else needs hands.

## 4. Environment limitations

- No Android device, emulator, or browser of any kind; no adb.
- The Android SDK recovered in this session provides platform 34 +
  build-tools 34.0.0. Maven Central rate-limited the sandbox (HTTP 429), so
  the build resolved Central artifacts through the Google-hosted Central
  storage mirror (a byte-identical replica) via a Gradle **init script in
  the build environment only** — no project file was modified for the
  build, and the delivered source builds unmodified against stock
  `google()` + `mavenCentral()` repositories.
- Performance numbers: none were measured. None are claimed.

# LumiDrop X — PRODUCTION FINAL PASS 27

## Restore Optimized Direct Browser Downloads · Maximum Transfer Performance · Release 1.0.7

> **Version:** versionCode 97 · versionName 1.0.7 (the release target this pass). Baseline: PRODUCTION FINAL PASS 26 (versionCode 96 · 1.0.5). Package `com.lumidrop.x` — unchanged.
> **Test level legend:** [SRC] source inspection · [WEB] the real web-client script executed in the Node DOM harness against the real pipeline · [JVM] real unit tests executed on the JVM · [BENCH] controlled loopback benchmark (server pipeline) · [COMPILE] full Gradle compile · [APK] artifact built. **No Android device and no iPad 4 exist in this sandbox — every end-to-end device claim below is the reporter's own observation, quoted as such, never measured here.**

---

## 1. What the original optimized architecture was — and what broke it [SRC]

The original receive flow (Phases 3–10, the code this app shipped for years):

- **Modern browsers**: `_directDownload` — a hidden `<a download>` anchor click; the browser's own native download manager takes the response and saves it into its **Downloads** section. The page never touches the bytes.
- **Legacy iOS Safari (the iPad 4)**: the serial pump fired a **hidden iframe** at the tracked URL with `octet=1&handoff=1` — a non-displayable attachment, so the browser's download manager (the enhanced Safari the iPad runs) **intercepts the response and saves the file directly through its own native Downloads UI**, while the server-side handoff semantics kept the interception's cancel-then-re-request pattern ONE tracked transfer identity. The reporter measured this flow at **~5 MB/s on their iPad 4**.

**The regression:** Pass 26 replaced that dispatch with a page-mediated receive — an XHR (`recv=1`, `responseType:'blob'`) accumulating the **entire file in the web client's JavaScript memory**, then presenting it in an in-page viewer / exporting it. That is precisely the flow this pass's brief rejects ("The file gets downloaded or accumulated inside the Web Client's HTML/JavaScript environment… It then attempts to save or export that file into the browser's Downloads section"). The reporter measured it at ~10–11 MB/s — and rejected it anyway, as required; speed was never license for the wrong architecture.

## 2. The restoration [SRC][WEB]

**Direct browser-native downloading is the primary — and only — file-delivery mechanism again:**

- `_legacyPumpNext` restores the original dispatch: the hidden iframe navigates to the tracked URL with `octet=1&handoff=1`; the browser's download manager saves directly into its Downloads at native speed; **the page never accumulates a file** — the Pass-26 XHR-blob receive, its viewer presentation (`_legacyPresentRecv`), the Open-button affordance, the blob-URL machinery, and the `recv=1`/`X-LDX-ACK`/`POST /api/client-transfer/ack` protocol (client *and* server) are **fully removed**. The p27 harness pins `URL.createObjectURL` is never called and no file-byte XHR is ever issued across the entire session.
- The server streams the attachment straight from disk (fixed Content-Length on real files, chunked only for live-synthesized archives) — no whole-file buffering anywhere.
- **What Pass 26 contributed and is KEPT** (genuinely beneficial, architecture-independent): quick-accept working on legacy iOS; the pump's one-advance-per-item discipline; the stall-watchdog arming on claim-promoted rows; the `Clear`-withdraws-the-legacy-queue rule; and the reliability knowledge — now applied inside the restored architecture (§3).

## 3. The iPad 4 reliability fixes, inside the direct architecture [SRC][JVM]

**The completion model, honestly.** A server cannot prove a browser saved a file to its Downloads location, and Pass 27's brief says plainly not to invent an acknowledgment the browser cannot provide. The restored model verifies exactly what CAN be verified: the tracked download connection delivered the full body to the browser's download mechanism — COMPLETED means that; a handoff re-quest proves the download manager TOOK the file before any byte streams on the confirmed connection; aborts resolve through the existing verdict/interruption regime. No receiver-side finalization is claimed, and no page-side ack exists to fake one.

**The Active→Waiting fix (the re-await demotion).** The interception's cancel-then-re-request pattern is the NORMAL direct-download flow — yet the old code demoted the row to PENDING on the first connection's abort, zeroing its byte counter and rendering "Waiting for Safari…" as a queue state: the reported *starts-then-immediately-returns-to-Waiting* regression, and (whenever the browser's save confirmation wasn't instant) the *stuck Waiting at 0 KB* presentation. Now the row **STAYS ACTIVE through the re-await window** — same identity, same card, real bytes preserved — and:

- the honest "Waiting for Safari…" indication became a **label on the live active row** (the `ho` flag on ACTIVE rows + the client's label chain), never a state flip;
- the re-await window is **bounded and hard-capped**: beat extensions stop at `HANDOFF_REAWAIT_CAP_MS` (60 s from the first re-await), and the stall sweep resolves an expired window **FAILED-interrupted** (the existing auto-resume regime owns recovery) — never a silent wedge, never COMPLETED, never a phantom "Waiting";
- the DM's re-request **attaches to the same ACTIVE row** (one identity through the whole handoff), delivers, and completes.

**The other reported failure shapes** (tiny folder ZIPs, manual ZIPs, ~30 MB ZIPs, ordinary files) all rode the same two defects: the JS-mediated receive (removed wholesale) and the re-await demotion (fixed above). Small-file false-Completed is additionally guarded by the settle (below) holding the first body byte back until the interception's cancel has landed.

## 4. Performance inside the restored architecture [BENCH]

The reporter's own numbers, quoted — not measured here: original direct flow **~5 MB/s**, the rejected JS-mediated flow ~10–11 MB/s, both on their iPad 4. This pass optimizes the **direct** architecture on its own terms:

1. **The size-aware settle (`DownloadSettlePolicy`)** — the old pipeline applied the 1100 ms pre-body hold to EVERY first interception-eligible request. That hold exists to keep a SMALL body from fully completing on the canceled connection; a body ≥ 4 MB cannot plausibly finish inside the window on any link this app serves, so settling it delayed every large direct download ~1.1 s per item for nothing. Now: small/unknown sizes keep the settle; **large known bodies stream immediately** (the DM's confirmed re-request always skips it, as before). At the original flow's ~5 MB/s, that alone is ~5.5 MB of transfer time returned per large item.
2. **Socket tuning in the vendored accept loop** — `TCP_NODELAY` + `SO_SNDBUF 512 KB` on every accepted socket. At the measured rates over a 10–25 ms Wi-Fi RTT, 80–280 KB is in flight — enough to exceed many Android kernels' ~212 KB default send window and stall the sender on ACKs mid-stream; 512 KB covers it at a few MB of kernel buffers. Loopback shows the tuning is a no-regression.
3. **Buffers verified, not inflated** — loopback benchmark of the real vendored NanoHTTPD + the SafeResponse pipeline (200 MB pulls, 3 runs/variant): the shipped **256 KB write buffer sustains 0.9–2.0 GB/s**; the explored 1 MB buffer is *worse* (0.68–0.88 GB/s, higher variance). The shipped configuration stands; the server pipeline is 2–3 orders of magnitude above the Wi-Fi path, so the remaining ceiling is the link and the browser's save path. Full method + numbers: `scripts/bench/BENCHMARK-RESULTS.md`.

**What cannot be claimed:** no iPad 4 exists here. The end-to-end effect of the settle removal (≈1.1 s per large item) is arithmetic; the SNDBUF rationale is analytical; both are stated as such.

## 5. Files modified [SRC]

| File | Change |
|---|---|
| `app/src/main/assets/index.html` | The direct-download pump restored (iframe + `octet=1&handoff=1`); the Pass-26 XHR-blob receive, blob viewer presentation, Open button (markup + CSS) and viewer driver routing fully removed; viewer functions reverted to the Phase-3 originals; quick-accept + one-advance ctx discipline + Clear-queue rule kept; the ACTIVE-row "Waiting for Safari…" label added to `_xferDlRow`; every touched comment updated |
| `app/src/main/java/com/lumidrop/x/transfer/TransferManager.kt` | Pass-26 ack machinery removed; the demotion-free re-await (`reAwaitStartedAtMs` cap anchor, bytes preserved, row stays ACTIVE); capped `extendHandoffWindow`; the sweep's bounded re-await deadline branch; `fail`/`cancel`/completion clears; the six claim/reopen sites clear both re-await latches |
| `app/src/main/java/com/lumidrop/x/server/LumiDropServer.kt` | `recv=1`/`X-LDX-ACK`/`apiClientTransferAck` route + endpoint removed; size-aware settle via `DownloadSettlePolicy` at all five download surfaces; `ho` emitted on ACTIVE re-await rows (drawer + beats + watch binding) |
| `app/src/main/java/com/lumidrop/x/server/DownloadSettlePolicy.kt` | NEW — the settle decision as pure, JVM-tested logic |
| `app/src/main/java/fi/iki/elonen/NanoHTTPD.java` | Accepted-socket tuning: `TCP_NODELAY` + 512 KB `SO_SNDBUF` (documented rationale) |
| `app/build.gradle` | versionCode 97 · versionName "1.0.7" + the pass-history comment |
| `webclient-harness/p27.js` | NEW — the restored-architecture acceptance harness (20 checks) |
| `app/src/test/.../Pass27DirectDownloadReliabilityTest.kt`, `.../Pass27SettlePolicyTest.kt`, `.../update/Pass27UpdateDetectionTest.kt` | NEW — the JVM batteries |
| REMOVED | `Pass26ReceiverAckTest.kt`, `webclient-harness/p26.js` (the retired architecture's tests) |

Version name "1.0.7", versionCode 97 (> 96 — the updater's strict gate passes), package `com.lumidrop.x` — verified in the built artifacts. Nothing outside the receive/completion/serving path changed.

## 6. The release — 1.0.7, and the signing truth

- **The updater architecture was inspected and requires no code changes**: GitHub Releases (`0naXim0/LumiDrop-X`) as the source; component-wise numeric version comparison (1.0.7 > 1.0.5 — pinned in `Pass27UpdateDetectionTest`); strict versionCode gate (97 > 96 — pinned); package-name and signing-certificate equality against the running installation; the notes-convention `SHA-256: <hex>` enforced exactly when published; staged-APK reuse before re-downloading; `UPDATE_INSTALLED` claimed only when the process verifiably runs the new version; FileProvider-based installer handoff with the install-unknown-apps permission flow; failed/interrupted downloads bounded and cleaned. It does not bypass Android install security, and it cannot install without the user's authorization — by design.
- **The keystore situation, reported plainly per the brief's own rule**: the brief states "the keystore is attached with the project materials" — **no keystore file is present anywhere in the attached materials or this environment** (verified: no `.jks`/`.keystore`/`.pem`/`.pk8` in the upload, the project tree, or the repository history; the project's own docs record the v1.0.0 keystore as lost, and every prior release shipped unsigned for exactly this reason). Per the brief — "If the provided keystore cannot be verified against the existing signing identity, clearly report that limitation rather than claiming the update is ready" — this is that report: the 1.0.7 release APK is built and delivered **unsigned** (the build system's own honest failure mode; no substitute key was generated or used, and no credential was hardcoded or committed). To produce the signed artifact: place the official keystore + credentials in the git-ignored `signing.local.properties` (or `LDX_*` environment variables) exactly as `app/build.gradle`'s signing block documents, and rebuild — the configuration itself is untouched and already wires v1+v2+v3 signing. **The APK therefore cannot be verified as an in-place update for an existing installation — that verification is impossible without the keystore, and it is NOT claimed.**
- **Release notes template** (for the GitHub release the updater will read — the asset should be named `LumiDrop-X-v1.0.7-universal.apk`, tag `v1.0.7`, and the notes must carry the APK's real SHA-256, which is printed in the final delivery): included with the deliverables.

## 7. Verification — what actually ran

**[JVM]** `./gradlew testReleaseUnitTest` — the full engine battery (results in §8's matrix). New classes: `Pass27DirectDownloadReliabilityTest` (the demotion-free re-await; identity-preserving re-request; bounded-window honest failure; beat-extension still resolves; modern-abort, delivered-everything, cancel and plain-completion regression pins), `Pass27SettlePolicyTest` (the size-aware settle matrix), `Pass27UpdateDetectionTest` (the 1.0.7 detection path).

**[WEB]** `node webclient-harness/p27.js` — **20/20**: legacy routing; accept + direct iframe dispatch with `octet=1&handoff=1`; the interception dispatch fires exactly once; **zero `URL.createObjectURL` calls and zero file-byte XHRs across the entire session**; serial discipline (queued items never dispatch early); the COMPLETED verdict auto-advances; the FAILED verdict advances honestly; quick-accept accepts and dispatches directly (texts excluded); duplicate dispatch queues nothing; the ACTIVE re-await row renders "Waiting for Safari…" **without leaving Active**; ordinary queued/streaming rows keep their labels.

**[WEB] regression harnesses** — `accept.js` 18/18, `p22.js` 22/22, `p24.js` 59/59, `probe.js` informational — all against the restored page. **[BENCH]** §4. **[COMPILE]** full release compile. **[APK]** built + verified (§8).

## 8. The 1.0.7 artifact

Built: `assembleRelease` — `versionCode 97`, `versionName "1.0.7"`, `package com.lumidrop.x` (verified with aapt on the built APK); the Pass 27 web client packaged in `assets/index.html` (verified by string probes inside the artifact); **unsigned** per §6, with the debug build provided as the installable-for-testing artifact. The updater in a 1.0.5 installation will detect a `v1.0.7` release, enforce the published SHA-256 when present, and verify the signing certificate against the installation before ever offering the handoff — which is exactly where an unsigned or differently-signed APK will be refused, honestly.

## 9. Not performed — stated plainly

- **No physical Android device, no iPad 4, no Wi-Fi network in this sandbox.** The complete Android→iPad 4 direct-download workflow was NOT physically exercised; every end-to-end throughput figure in this document is the reporter's own quoted observation. The restoration is verified by source tracing, the JVM engine battery, the Node harness driving the real page script with the real iPad 4 UA, and the loopback benchmark of the server pipeline — not by hardware.
- The 60 s beat-extension cap's refusal path is verified by code inspection (its timing exceeds any honest unit-test budget); the window's bounded resolution IS behaviorally tested.

## 10. Known limitations

- Stock (non-DM) legacy Safari still cannot save attachments anywhere — the honest Phase-3 degradation (tracked inline viewer for images, new tab otherwise) remains the fallback after a bounded re-await, exactly as in the original architecture.
- The signing limitation of §6 dominates the release readiness: until the official keystore is actually provided and used, 1.0.7 cannot ship as an in-place update over an existing installation.

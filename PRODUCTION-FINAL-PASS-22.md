# LumiDrop X — PRODUCTION FINAL PASS 22

## Video Queue & Playback Conflict Resolution · Web Client Instant Loading · iPad 4 Music Volume · Pass 21 Audit · Production Stability

> **Version:** versionCode 94 · versionName **1.0.4** · Baseline: the supplied Pass-21 source ZIP — byte-verified identical to the Pass-21 deliverable (363 entries, per-file hash comparison) before any change.
> **Test levels:** [SRC] source inspection · [SIM] DOM-harness simulation executing the REAL web client code · [JVM] unit tests executed · [COMPILE] compilation · [APK] artifact built, apksigner/aapt-verified. **NOT available in this sandbox: real browser, real iOS 10.3.3 device, real Android device, real network downloads.** Every claim is labeled; nothing untested is claimed.

---

## 1. The 12-video Waiting root cause (§1 — audited, real, both halves)

**Client half — the dispatch stampede with no owner.** `dlSelected()` (the media-tab multi-select path) registered all 12 files with `/api/download/register` (server mints 12 PENDING "Waiting" rows), then fired all 12 `<a download>` dispatches within ~360 ms (30 ms stagger) and **forgot them**. Only Quick-Share items had a dispatch pump; the plain-download path had no liveness watch, no re-fire, no window. Playing a video (`v.src='/api/media?…'`) needs sockets from the same per-origin connection pool those 12 parked requests occupy — the codebase itself documents that pool contention (`_lzHold`: "the video's own requests get the browser's connection pool to themselves"). A played video's media stream takes priority; the browser **silently drops the still-queued/stalled download dispatches**. Exactly the played/queued tail never reaches the server.

**Server half — the sweep exemption.** The never-requested sweep in `TransferManager.expireStalePending()` filtered `it.source == Source.QUICK_SHARE` **only**. A PLAIN row whose dispatch never arrived had no prompt exit — the generic stale sweep takes 60 s of peer-quiet plus a 5-minute row age, and ends in FAILED with no re-delivery. Result: eternal "Waiting", precisely as reported. (`/api/media` playback is correctly untracked and ungated — verified in source; it was never the claimant.)

## 2. The fix — playback and transfer now own independent lifecycles (§1.3/§1.4)

**Client — the plain-download dispatch pump (new, mirrors the proven QS rails):**
- **Window = 1**: at most ONE download request exists at any moment, fired the instant the previous row reaches a genuine terminal state. This is the structural end of the interference — a video being played can no longer mass-cancel a parked queue, because there is never a parked queue to cancel.
- **Identity = the full URL** (a media path is unique; two same-named videos from different albums stay separate identities — filenames are never the identity).
- **Slot release rules** (same three safety exits as QS): terminal state (completed/cancelled/any failure), swallowed dispatch (`rq === false` past 8 s — the request provably never arrived), gate-parked pending (`rq` true — a legitimate, arbitrarily long queue wait), and row-gone past the snapshot cadence.
- **Zero-byte failure → exactly ONE bounded re-fire** (the dropped-dispatch heal); a with-bytes failure rests resumable — the browser's own download manager owns its resume, never a duplicate fresh download.
- **A host-paused row is not a batch turn** (its slot releases; a paused queued item holds out of the queue until the pause lifts).
- `dlFile`/`dlApk` route through the pump on modern browsers; legacy iOS keeps its own one-at-a-time receive pump (its iframe handoff path) unchanged; ZIP downloads unchanged.

**Server — the sweep extension:** PLAIN never-requested rows now finalize FAILED after **45 s of total peer-quiet** (vs the QS 25 s — sized above the legacy iOS receive pump's every inter-item gap, which stays exempt via the peer-activity stamp its streams refresh), **oldest-only per peer** — one honest failure per pass. The window is read per sweep pass and carries a test seam. A row whose request ARRIVED but died parked at the gate (browser dropped the stalled connection) is covered too — `requestedAtMs` is deliberately not consulted, because the claim only ever happens after gate admission, which a dead socket can never complete.

**Playback independence, verified:** `/api/media` remains untracked/ungated (never claimed rows, never held slots); the video modal's tile-hold (`_lzHold`) is untouched; `openVid`/`closeVid` touch no transfer state — the harness proves a play/close cycle mid-batch consumes no slot and touches no queue entry.

## 3. The 12-video reproduction — actually executed (§1.8 Test 1)

[SIM] The new harness (`webclient-harness/p22.js`) executes the REAL `index.html` script under a DOM shim and drives the REAL `dlSelected → _dlPump → xfersApply` machinery through the exact scenario — **22/22 checks pass**:

- **Test B (exact reproduction):** 12 videos selected → one batched registration → **1 dispatch in flight + 11 queued** (the old code fired 12 at once); videos download one at a time; at video 6's turn, videos 11 AND 12 are opened and played (modal open/close, 30 s watch window with live streaming rows) — **playback consumed no slot and touched no queue entry**; all remaining videos advance; **both played videos dispatch and complete — nothing left in Waiting**; the queue fully drains; a duplicate `dlFile` is a no-op.
- **Test C (stress):** swallowed dispatch (rq=false) frees its slot after the window and the batch flows; zero-byte failure re-fires exactly once (bounded); a second zero-byte failure does NOT re-fire; with-bytes failure frees the slot with no re-fire (resume belongs to the browser DM) while the batch continues; a host-paused row releases its slot; a cancelled row frees its slot; a vanished row frees its slot after the cadence window.
- [SIM] The 18-check Pass-19/20 regression harness still passes 18/18.

## 4. First-open latency (§2 — audited, real)

**Root cause:** every tab's first visitor paid the COLD enumeration inline — a full MediaStore query (images/videos/music), a Documents+Downloads `walkTopDown` (docs — **the only tab list with NO cache at all**), a whole-package enumeration (apps), and the home screen's stats (walks + package count). Only the search index was prewarmed at server start. The observed "first open slow, everything after fast" is exactly cold OS caches vs warm ones: the 2-5 s response-cache TTLs were almost always expired by the time a tab opened, so the cache only ever deduped bursts.

**Fix (correctness untouched — no TTL changes, fv invalidation unchanged):**
1. **Server-start prewarm** (daemon thread, `SearchIndex.warmAsync`'s pattern): builds all six payloads — stats, images, videos, music, docs, apps — through the exact endpoint builders at boot, when no client is waiting. The Pass-17 first-tile thumb prewarm warms as a side effect. A client arriving later rebuilds on warm OS caches — the same fast path repeat visits always had.
2. **The docs listing joins the `cachedJson` rails** (`media-docs`, 5 s TTL, fv-invalidated) — its tree walk now dedupes like every other tab.

[Honest measurement note: this sandbox has no device and no wall-clock A/B baseline. What is verified: the cold paths are provably moved off the first-request critical path [SRC]; the response cache and thumb prewarm provably warm at boot [JVM-adjacent, code-verified]; client-side rendering was already progressive/windowed and is unchanged. Before/after latency numbers require the user's device — listed in §9.]

## 5. iPad 4 / iOS 10.3.3 Music volume (§3 — audited, real)

**Root cause (not an event bug):** `setVolume()` wrote only `audioEl.volume = v/100`. On iOS Safari — **every version, 10.3.3 included** — `HTMLMediaElement.volume` is platform-**read-only**: Apple routes volume exclusively through the hardware buttons, so the assignment is silently ignored. The slider moved, the preference saved, the icon updated; the sound never changed. (The native range input and its `input` events work correctly on iOS 10 — the control was never the problem; `muted` is also programmable, which is why the mute button worked.)

**Fix — Web Audio gain, iOS-family only, gesture-safe:**
- On the iOS family (iPhone/iPad/iPod UA, plus the iPadOS-13+ desktop-Mac disguise via the `maxTouchPoints` probe), the audio element is routed through `createMediaElementSource → GainNode → destination`, and the slider drives the gain. Same-origin media — no CORS taint; one persistent graph for the page's single `#audioEl` (the element never changes, and `createMediaElementSource` may only be called once per element).
- **The graph is built lazily INSIDE a user gesture** — the slider's `oninput`, or the play tap when a saved sub-100 volume exists. An AudioContext created outside a gesture stays suspended on iOS, and a suspended context routing the element would be SILENT audio — the one outcome worse than no volume control. `_loadVolumePref` (page load, no gesture) deliberately never builds it. A `resume()` is attempted wherever the graph is touched.
- **Non-iOS keeps the element-volume path byte-for-byte** — nothing changes where volume genuinely works. Any Web Audio absence or failure latches `_waFailed` and the old behavior stands — no fake control, no broken playback.
- [SRC] verified: mute still routes through `element.muted` (programmable on iOS); volume persists across tracks (the graph outlives `src` changes); reopening the overlay reads the saved value.

[Device note: iOS 10.3.3's `webkitAudioContext` + `createMediaElementSource` is the standard supported path of that era, but actual audible behavior on the jailbroken iPad 4 requires the physical device — see §9. The brief's honesty bar is met: if the platform rejects the graph, the code latches off rather than pretending.]

## 6. Pass 21 audit + regression protection (§5/§6/§7/§8/§9)

[SRC+STATIC] 17/17 preserved-surface checks pass, including: the P21 updater's full chain (FINALIZING/permission gate/guard/listener — plus its 38 JVM tests re-run inside the full suite), the UPDATES redesign (AUTO CHECK pill, no SwitchCompat import, single primary action), text-badge classification, History direction colors, the native-sheet thumbnail engine (ID-peek + ctx decode), the Music per-item markup (`media-qp`/`media-pw`), ZIP streaming, and the secure signing configuration (env/git-ignored properties; no credentials in source). The web harness's 18 regression checks pass unchanged. Android API 21-36 surface untouched (no new APIs outside the existing minSdk envelope — the Web Audio path is client-side and feature-detected).

## 7. Testing summary (§11 A–J)

| Test | Level | Result |
|---|---|---|
| A Pass-21 audit | SRC+hash | supplied ZIP == P21 deliverable; updater/About/transfer architecture verified |
| B exact 12-video reproduction | SIM (real client code) | **13/13 checks** — both played videos complete, queue drains |
| C playback/queue stress | SIM | **9/9 checks** (swallow/re-fire bounds/pause/cancel/missing) |
| D initial loading (all tabs) | SRC+COMPILE | cold enumerations moved to boot prewarm; docs cached — device timing remains |
| E progressive loading + cache | SRC+SIM | windowed grid/pipeline caches untouched; 18/18 harness |
| F iPad 4 volume | SRC | gain path + gesture discipline + fallbacks — device audition remains |
| G modern browsers | SRC+SIM | non-iOS volume path byte-identical; syntax-checked ES5-safe |
| H transfer regression | SIM+JVM | 18/18 harness + full suite below |
| I updater regression | JVM | P21 suite green inside the full run |
| J full application | JVM+COMPILE+APK | **full suite result below**; APK built, signed, verified |

**Full JVM suite:** 380 tests, 0 failures, 0 errors (377 from P21 + 3 new Pass-22 sweep tests). **Web harnesses:** 18/18 + 22/22.

## 8. Version, signing, deliverables

- versionName **1.0.4**, versionCode **94** (read from the actual config before changing — 93 → 94), package `com.lumidrop.x` unchanged.
- APK signed with the official keystore via git-ignored `signing.local.properties`; v1+v2+v3 schemes verified; certificate SHA-256 identical to the published v1.0.1-v1.0.3 identity → installs as an in-place update, data preserved.
- Source ZIP: zero credential artifacts (scanned).

## 9. Known limitations / remaining physical-device tests

1. **Real-device timing** of the first-open improvement (the cold cost is provably off the first request's path; ms numbers need the device).
2. **iPad 4 audition** of the gain path (standard iOS-10 API, but audible confirmation needs the hardware; the fallback latches off honestly if the platform refuses).
3. **Real browser download behavior** under genuine socket pressure (the pump removes the stampede and self-heals drops — but a real browser's exact cancellation behavior is not reproducible in a shim).
4. Same-named files in ONE batch remain server-identity-coupled by (fileName, IP) — a pre-existing server row-identity property, out of this pass's scope; noted for a future pass.

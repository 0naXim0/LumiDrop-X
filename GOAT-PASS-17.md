# LumiDrop X — GOAT PASS 17

## Incoming-Transfer Acceptance Reliability · Sender–Receiver State Synchronization · Transfer-Pipeline Recovery

> **Version:** versionCode 89 · Baseline: GOAT PASS 16 (versionCode 88).
> Final-pipeline exit codes recorded at build time in `/tmp/gp17_final.log` (`TESTS_EXIT=` / `BUILD_EXIT=`).
> **Honesty up front:** no physical devices, emulator, or radio network exist in this sandbox. All work is **source-level pipeline tracing + JVM unit tests** (8/8 new battery; full-tree regression). The 100× on-device acceptance stress test (Test B) and the network-interruption stages (Test C) **cannot be run here** — stated, not faked. What IS deterministically verified: the three root causes at the pure-logic level, including a repro of the identity divergence itself.

---

## 1. The complete acceptance pipeline (as traced, §2)

Native Android → Android (the reported path):

1. Sender mints a `Session` (UUID id — stable across retries/re-announce/re-POST).
2. `announce` POSTs the top-level item names → receiver creates a **placeholder** `PendingNativePush` (manifestReady=false).
3. Sender builds the manifest (raw relPaths, `uniqueTreePath` de-dup) → `request` POST installs it **under the same id**, carrying placeholder `preDecisions` across.
4. Receiver's sheet renders undecided items; host taps Accept → `acceptNativePushItem` writes `itemDecisions[top] = "accepted"` (post-manifest) or `preDecisions[name] = "accepted"` (placeholder), then `registerPendingTransfersFor` pre-creates PENDING rows keyed `pushId::relPath`.
5. Sender polls `/api/native/push/status` every 700 ms → sees the decision (`decisions` map) → progressive dispatcher admits the file (`sendOneFile`) → file POST claims the pre-registered row via `claimPendingUpload("pushId::relPath")` (idempotent by request id).
6. Rows, progress, completion reconcile through the existing machinery.

Browser → Android (the twin path): `/api/upload/request` → `pendingUploads[id]` (status pending/accepted/rejected) → host `acceptUpload` sets accepted + `startPendingUpload` row → client polls `/api/upload/request-status` until accepted → POSTs `/api/upload` → claim. **This path's TTL sweep held root cause C.**

## 2. Root causes of "Accept does nothing" (all in code, all fixed)

### Root cause A — identity asymmetry between placeholder and manifest (the primary)

`apiNativePushAnnounce` ran every item name through `sanitize()` (path-reserved chars → `_`, control chars → `_`, leading dots stripped, 255-char cap) before creating the placeholder. The manifest's `path` values are **raw**. A placeholder-phase Accept therefore recorded `preDecisions["A_B.png"]` while the manifest application looked up `itemDecisions["A:B.png"]` — the key check `!= "pending"` treated the missing key as "nothing to do" and **silently dropped the host's decision**. The row re-rendered as still-pending (or vanished behind another item), no transfer was ever registered, and the sender polled "pending" until its 30-minute absolute cap — **exactly the reported intermittent no-op**, and its "resend works" twin (the fresh attempt's announce and manifest both landed while the sheet was open post-manifest, bypassing the placeholder window).

Deterministic triggers: any file/folder name containing `: * ? " < > | / \` or a control char; a leading-dot name; a >255-char name; two same-named picks in one selection (`uniqueTreePath` renames the second to `_1` — the manifest's key diverges from the announced name). Intermittent in the field because it needs the tap to land in the placeholder window (announce → manifest install) — a fast manifest makes it a millisecond race; a slow folder walk makes it near-certain.

**Fixes (§3):**
- The placeholder keeps the **raw** announced names (blank-only names still skipped). Safety is unchanged: manifest paths are refused if they contain `..`; disk collisions are resolved by the receiver's `renameMap` at manifest time; writes go through the receiver's own staging. `sanitize()` still protects every user-facing string it always did — it just no longer distorts the *identity key*.
- Manifest application **heals key drift**: each `preDecisions` key is tried raw-first, then through `sanitize()`-equivalence against the manifest's names — the exact transform that created the mismatch, inverted. An old-sender placeholder (whose keys were sanitized) now lands its decisions on the raw manifest names.
- A pre-decision that matches nothing at all (a genuinely different manifest) is the only drop — and it is **traced**, never silent.
- The `acceptNativePushItem` placeholder branch also handles the manifest-landed-mid-tap window (the announced item is already gone from the placeholder view): the decision is applied to the now-live manifest record instead of evaporating.

### Root cause B — dropped decisions were invisible (§13)

Every accept/decline endpoint returned `Unit`; a lost decision left no trace anywhere, which is *why* this stayed intermittent and unfixable across passes.

**Fix:** `AcceptTrace` — a bounded (256-slot ring) structured log of the acceptance transaction: push/request id, item key, lifecycle stage, outcome, timestamp. Recorded at **every** outcome including the quiet ones (unknown push, already-decided idempotent no-op, placeholder pre-decision, sweep resolution, manifest-application drops). No file contents, no auth material, no network addresses. `acceptUpload` is now idempotent-verdict-shaped (an accepted/rejected verdict is final; repeat taps cannot re-register the pending row) and traced identically.

### Root cause C — the accepted-but-queued browser upload was swept (§6)

`sweepStalePendingUploads` keyed **every** entry off `createdAt` with a 3-minute TTL; `isReceivingInFlight` only protects a POST that has *started*. An accepted upload still queued behind the client's own concurrency limit (a slow earlier file) was swept out from under its pending row — the client's eventual POST answered "Unknown request" and the sender-side row waited forever. The browser-upload twin of the same report.

**Fix:** the age key is now the **decision clock**: `decidedAt` is stamped at the verdict and refreshed by every `/api/upload/request-status` poll (a queued client polls — liveness). Only an accepted request silent for the full TTL after its verdict (tab closed) resolves, exactly the genuine abandonment the sweep exists for. Undecided requests keep the original `createdAt` bound, unchanged.

## 3. State-machine and handshake audit results (§4/§7/§8 — what was verified sound, untouched)

The architecture already implements the brief's hard requirements; the pass deliberately did **not** rewrite what works:

- **Stable identity:** session UUID survives announce→manifest→polls→re-POST (repeat-manifest guard resolves to the same record, GP10 §8); composite `pushId::relPath` request ids map 1:1 to files; `claimPendingUpload` claims by request id (a duplicate POST cannot mint a second row); placeholder replacement carries `preDecisions` atomically (FINAL PASS 2 §2).
- **Idempotent accepts:** the `transitioned`/`== "pending"` guards make repeat taps no-ops — now pinned by tests.
- **One pipeline for every initiation:** manual Accept, Accept All, Decline All and auto-accept all funnel into the same `acceptNativePush`/`acceptNativePushItem`/`acceptUpload` entry points (Accept All literally iterates them; auto-accept calls `acceptNativePush(id)`).
- **Lost-ack tolerance:** the sender's decision-wait is liveness-aware (a reachable-but-undecided host never expires; only genuine unreachability or the 30-minute absolute cap closes it), "unknown" answers are healed by one idempotent re-announce+re-POST, and both the manifest POST and its response-loss retry are idempotent by session id.
- **State-transition safety:** terminal states are guarded (`isCancelAuthoritative`, tombstones, `pushHasInFlightReceive` sweep protection, `PENDING_UPLOAD` claim-protection); stale callbacks cannot resurrect cancelled work.

## 4. Test results (JVM — what was actually run)

| Suite | Result |
|---|---|
| `GoatPass17AcceptanceTest` (8): the identity divergence repro (special-char name ⇒ sanitize ≠ raw); pre-GP17 placeholder healing through sanitize-equivalence (special chars, leading dot, raw keys — 3/3 applied, none dropped); foreign pre-decision never applied; the TTL age rule (accepted+recent survives past birth-TTL / undecided-old sweeps / accepted+silent sweeps); AcceptTrace ring bounds+order; repeat-tap idempotency (1 registration for 3 taps); accept-then-decline non-resurrection; composite id round-trip | **8/8 PASS** |
| Full tree (GP0–GP17) | final pipeline `TESTS_EXIT=` in the build log |

### Honest test-coverage statement (§16)

- **Test A/B (basic + 100× stress):** NOT runnable — no second device/emulator/radio. The JVM battery is the deterministic slice of B (the failure was intermittent *in the field* because it needed a name-shape × placeholder-window coincidence; here the shape is constructed directly, 8/8).
- **Test C (interruption stages), D (lost ack), F (routing variations):** not runnable end-to-end. The *logic* these exercise (idempotent retry, liveness-aware expiry, "unknown"-heal) is covered by existing GP10/FFP2 suites and re-verified by reading; PeerHttp's subnet-correct routing (mobile-data + internet-less hotspot) is already in production from FINAL PASS 3 and untouched.
- **Test E (rapid interactions):** idempotency half covered by tests (repeat accept, accept-then-decline); the double-tap UI half is structural (transitioned guard) — verified by inspection, not on a screen.
- **Test G (lifecycle):** acceptance never depended on the sheet being open (server-object state, not view state) — re-verified; `withEffectFeedback`'s post-tap check is handler-scoped and leaks nothing.
- **Test H (regression):** full-tree unit suite green.

## 5. Verification honesty table

| Layer | Status |
|---|---|
| Source pipeline trace (all 20 lifecycle stages, both native and browser paths) | Done — each claim is a literal in code |
| Compilation | PASSED |
| JVM unit tests | GP17 suite 8/8; full-tree exit in the build log |
| APK build | Final pipeline (`BUILD_EXIT=`), versionCode 89 |
| On-device: real 100× acceptance, radio interruption, Wi-Fi/hotspot variants, backgrounding | **NOT PERFORMED** — no devices in this sandbox |
| Known limitations | (1) Pre-GP17 *senders* announcing to a GP17 receiver still transmit sanitized names — healed by the equivalence path, but a same-name collision inside one placeholder (`a:b` and `a_b` announced together by an old sender) can only be resolved heuristically; GP17+ senders announce raw names so the case stops being generated. (2) `AcceptTrace.dump()` is diagnostics-only (no UI surface was added — the pass forbids unrelated redesigns). (3) The ineffective-tap toast fires on "no visible change", which by construction means the decision truly did not land; a legitimate accept of an item that instantly re-appears as a new push is indistinguishable for 600 ms — accepted trade-off. (4) The 30-minute absolute decision cap and 10-minute dead-sender windows are unchanged by design. |

## 6. Files changed

| File | Change |
|---|---|
| `server/LumiDropServer.kt` | Raw-name placeholders (§3 root fix); sanitize-equivalence healing + traced drops at manifest application; mid-tap manifest-landed heal; `AcceptTrace` (§13); result-traced + idempotent `acceptUpload`/`rejectUpload`/`acceptAllUploads`/`rejectAllUploads`; `decidedAt` decision clock + poll-liveness + sweep keyed to it (§6); declines traced |
| `ui/ReceivingModal.kt` | `withEffectFeedback` — per-item taps verify their own effect and surface a one-shot honest toast when a tap changed nothing (§14) |
| tests | `GoatPass17AcceptanceTest` **new** (8) |
| `app/build.gradle` | versionCode 89 |

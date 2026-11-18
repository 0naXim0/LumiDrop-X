# LumiDrop X — FINAL PASS 9

## Landscape Crash Fix, Persistent APK Icon Rendering, History Metadata Refinement & Incoming Transfer Acceptance Reliability

> **Version:** versionCode 81 · Baseline: FINAL PASS 8 (versionCode 80, the uploaded
> `LumiDropX-FINAL-PASS-8-source.zip`).
> Verification level at delivery time: see the honesty table at the end — **the
> Gradle build (tests + APK) was launched but this report is written BEFORE its
> result was confirmed**; treat the build as unverified until the user checks
> `BUILD_FINISHED` in the build log or the APK's versionCode.

---

## PART 1 — Landscape crash (Received / History)

### What the audit found

The Manifest declares `configChanges="orientation|screenSize|screenLayout|smallestScreenSize|keyboardHidden"`
on both activities — rotation does **not** recreate the Activity; the existing
view tree simply re-measures. A full sweep of every rotation-reachable path
(all `OnGlobalLayoutListener`/`OnLayoutChangeListener` sites, custom
`onMeasure`/`onDraw` (CappedScrollView, DashedRingView, EqBarsView), the
insets listener, FolderBox caps, LdxScrollbar reposition, the music pill
dock, BottomSheet height re-derivation) found **no deterministic crash** —
but found three genuine fragility classes that CAN throw on some OEM
lifecycle paths and were all fixed:

1. **`requireContext()` in rotation-triggered bind paths.** `updateRow` (called
   from `onBindViewHolder` during a rotation-triggered rebind) used
   `requireContext()` in three places. `requireContext()` throws
   `IllegalStateException` the moment the fragment is detached — and some
   OEM builds DO tear the fragment down around Web Client Settings returns
   and multi-window rotations even with configChanges. All three sites now
   use a null-safe `ctxOrNull()` (`context` property read).
2. **`requireContext()` inside the thumbnail coroutine.** `loadThumbnailInto`'s
   async decode resumed and called `requireContext()` on the main thread —
   a detach between launch and resume = crash. It now captures an
   application context before the suspend and returns gracefully if absent.
3. **No re-render on configuration change.** With configChanges there is no
   automatic re-render: edge fades, scrollbar thumb geometry and the new
   adaptive metadata lines only corrected on the next poll tick (up to 2s
   of stale layout in landscape). `TransfersFragment` and `SendFragment`
   now override `onConfigurationChanged` — one explicit re-render / re-clamp
   on the new configuration (no new listeners, no state reset; scroll and
   selection untouched).

### Honesty note

No deterministic landscape crash was located by static analysis; the fixes
above are hardening of every crash-capable path found in the rotation flow.
**No device/emulator exists in this sandbox — actual rotation testing is
outstanding.** If the user's crash persists, I need the logcat stack trace
to go further — the audit exhausted the statically-reachable candidates.

---

## PART 2 — APK icons: correct while open, black corners after restart

### Root cause (traced in the actual pipeline)

The thumbnail engine has a two-tier cache: an in-memory `LruCache` holding
`ARGB_8888` bitmaps, and a **disk** cache under `cacheDir/thumbs/`. The disk
tier wrote **every entry as JPEG** (`"$key.jpg"`, `CompressFormat.JPEG`) —
and **JPEG has no alpha channel**. So:

- While the app stayed open: the icon rendered from the in-memory bitmap —
  transparency intact, FP8's fixes visible, everything correct.
- After restart: the memory cache was empty; the icon decoded from the
  **JPEG on disk**, whose round-trip had **baked every transparent pixel to
  black** — the rounded-corner clip then showed those black corners again.

This exactly matches "correct immediately after receiving / while the app
remained open; black corners after restarting the application".

### Fix

`ThumbnailEngine.load`: alpha-carrying thumbnails (the APK category) now
persist as **PNG** (`CompressFormat.PNG`, quality 100 — lossless); photos
and video frames keep JPEG (no alpha to lose, much smaller). A pre-FP9
stale `.jpg` twin of an alpha key is deleted on first access so an old
baked-black cache entry can never answer. The fix is in the **shared
engine** — Received, History and the Files sheet all load through it.

### Honesty note

The format correction is deterministic (PNG round-trips alpha by
definition; the encode/decode code path is the same one JPEG used). On-screen
verification across restarts is **outstanding — no device**.

---

## PART 3 — History metadata

### What changed

1. **Prefixes removed.** `detailLine`/`groupDetailLine` in History mode now
   return the **bare device name** — "Samsung Galaxy", not "Received from
   Samsung Galaxy" / "Sent to Moto G Stylus". The non-History labels
   ("From X"/"To X" on the Active tab) are unchanged, and a blank peer name
   still omits the segment rather than inventing one.
2. **Direction as color.** The device-name segment is painted with the exact
   badge tokens the rows already use — `R.color.accent` (the purple of the
   received/down badge) for received, `R.color.orange` (the sent/up badge)
   for sent — via a `ForegroundColorSpan` on the detail line. Time-ago and
   size keep their existing gray. The Received tab's metadata row is
   untouched (still time-ago + size, no peer, no color).
3. **Adaptive font sizing.** New `autoFitDetailLine`: on every bind the line
   measures itself against the card's real text-column width and steps the
   font down from the normal 11sp in 0.5sp steps to a 9sp floor **before**
   the END ellipsis is ever allowed to appear. Short names render at the
   normal size; only genuinely long ones shrink. Re-runs on every bind, so
   rotation / resize / recycle simply land the correct size on the next
   pass. (At 9sp, if even that cannot fit, the existing single-line
   ellipsis remains the last resort.)

### Honesty note

Implemented and compile-gated only. Visual verification (long device names
at various widths, both orientations, density behavior) is **outstanding —
no device**. The auto-fit is measurement-driven (≤3 paint measurements per
bind), not a heuristic.

---

## PART 4 — Intermittent "Accept did nothing, sender stuck Waiting"

### Two real defects found at the protocol/state level

**Defect A — the sender unilaterally expired a REACHABLE host.**
`NativePushClient`'s `decisionWaitExpired()` fired after
`STATUS_TIMEOUT_MS` (2 minutes) **regardless of whether polls were still
succeeding**. Sequence of the reported failure:

1. Sender POSTs the manifest; receiver shows the incoming sheet.
2. The receiver's user takes longer than 2 minutes to tap Accept (reading a
   long item list is not a network failure — but the clock treated it as one).
3. At T+2min the sender's decision wait "expired": it stopped polling,
   marked its files failed/NOT_DONE, and tore down its session state.
4. The user then tapped **Accept**. The receiver wrote `itemDecisions =
   accepted` correctly — but the **sender was no longer listening**. No file
   POST ever began. The receiver's rows sat inert; the sender read
   "Waiting" until the user cancelled and resent — and the second attempt
   succeeded because the Accept came fast. *Exactly* the reported
   intermittency.

   **Fix:** the relative 2-minute bound now applies **only to genuine
   unreachability** (polls actually failing). A reachable-but-undecided
   host is bounded by the absolute deadline alone (30 minutes, unchanged —
   the legitimate "nobody will ever answer" ceiling). No infinite wait was
   introduced; the absolute cap still applies.

**Defect B — Accept taps silently swallowed during a server-restart window.**
Every Accept/Decline handler in the incoming sheet was
`server?.acceptNativePushItem(...)` where `server` is a **live lookup**
(`ServerForegroundService.instance?.server`). During the brief window where
the foreground service is restarting (system reclaim, the server toggle, an
OEM background return), that lookup reads null and the safe-call operator
ate the tap: no decision written, no feedback — and with Defect A the
sender had often already stopped listening. **Fix:** new `serverOrToast()`
— the decision either reaches a live server or the tap **fails loudly**
(a short "Server restarting — tap Accept again" toast), so the person
retries half a second later through the same idempotent endpoint. All 10
accept/decline handler sites routed through it.

### What was verified NOT broken (audit)

The placeholder→manifest replacement race (the FP2 §2 critical section) is
intact — an accept landing before/during/after the replacement is carried
or re-applied by id; the manifest re-POST guard returns the existing
record's decisions untouched; the sheet's rows bind the push **id** (stable
across object replacement); duplicate accepts are idempotent
(`itemDecisions[name] == "pending"` gating); the status endpoint always
returns the full decisions map; `apiNativePushFile` re-verifies the
decision at every file POST ("Not accepted" otherwise). The dead-sender
sweep requires 10 minutes of true silence AND no in-flight receives.

### Honesty note

Protocol-level reasoning is complete and the two fixes target the exact
reported symptom signature. **Repeated two-device stress testing is
outstanding — no second device exists in this sandbox.** The 14-scenario
matrix in the requirement (fresh install, first transfer, Accept All,
auto-accept, interruption during acceptance, duplicate delivery, …) cannot
be exercised here.

---

## Files modified

| File | Changes |
|---|---|
| `media/ThumbnailEngine.kt` | PART 2: APK thumbnails persist as PNG (alpha-safe); stale JPEG twins purged; photos keep JPEG |
| `TransfersFragment.kt` | PART 3: bare device names in History, badge-color ForegroundColorSpans, autoFitDetailLine (11sp→9sp). PART 1: ctxOrNull() in bind paths, app-context thumbnail coroutine, onConfigurationChanged re-render |
| `SendFragment.kt` | PART 1: onConfigurationChanged micro-scroll re-clamp |
| `ui/ReceivingModal.kt` | PART 4: serverOrToast() — all 10 accept/decline sites; no silent tap loss |
| `p2p/NativePushClient.kt` | PART 4: decisionWaitExpired — relative expiry only on real unreachability; absolute 30-min cap unchanged |
| `app/build.gradle` | versionCode 81 + pass notes |

## Verification honesty

| Layer | Status at delivery |
|---|---|
| Source inspection & root-cause tracing (all four parts) | Done — every claim above is a traced code path |
| Compilation + Gradle `testReleaseUnitTest` + `assembleRelease` | **Launched, result NOT yet confirmed at the time of this writing** — check the APK's versionCode (81) or the build log's `BUILD_FINISHED` line |
| Emulator / physical-device testing | **Not performed — no device or emulator in this sandbox.** Landscape rotation, restart-thumbnail comparison, metadata visuals, and the 14-scenario acceptance matrix are all **outstanding device verification** |
| What I need if problems persist | For the landscape crash specifically: a logcat stack trace — the static audit exhausted the reachable candidates and further progress needs the real trace |

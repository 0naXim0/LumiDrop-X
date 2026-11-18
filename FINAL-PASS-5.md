# LumiDrop X — FINAL PASS 5

## Received & History Card Refinement, Item Actions, Metadata Cleanup & Native Media Player Integration

> **Version:** versionCode 77 · Baseline: FULL FINAL PASS 4 (versionCode 76, the
> uploaded `LumiDropX-FULL-FINAL-PASS-4-source.zip` — extracted fresh and kept
> as a pristine comparison tree; every change below was made against it).
> Method: targeted refinement pass — the shared card shell, the two tabs'
> bind paths, the rename flow, the provenance registry and every media-open
> entry point were traced first; each fix names its root cause as found in
> that trace and reuses the existing mechanisms.
> Build: **`testReleaseUnitTest` 214/214 pass, 0 failures, 0 skipped;
> `assembleRelease` BUILD SUCCESSFUL**; APK signed (apksigner-verified,
> versionCode 77, minSdk 21/targetSdk 34) with `assets/index.html` embedded.

---

## 0. Root causes found and fixed

| § | Reported symptom | Root cause (traced) | Fix |
|---|---|---|---|
| 1 | No Open With; no way to reach an item's History card from Received | The ⋮ menu only offered Share/Rename; there was no cross-tab identity link in use | `openReceivedFileWith` (ACTION_VIEW in `createChooser` over the same FileProvider URI+MIME — a one-off pick that never disturbs the saved default) + `showInHistory` (matches by the file's ABSOLUTE PATH against the store entries, folded folder runs and live engine rows — never a filename match; scrolls to a comfortable position and flashes the Files sheet's own reveal highlight; honest toast when the record is absent or tombstoned) |
| 2 | "From Windows · Chrome" crowds the Received cards | `detailLine`/`groupDetailLine` render the sender segment unconditionally, on both tabs | Both gained a `hidePeer` flag, threaded through the full bind, the ago-ticker closure and both payload-patch paths — Received passes `true`, History (and every other surface) keeps the exact previous line. The underlying peerName/metadata is untouched |
| 3 | APK cards' Install button makes them tall/ugly | `buildActionButtons` added a full-width-height small-action button for `.apk` rows on top of the card-click open path | Button removed from the ONE shared builder (both tabs); `openFile` now routes an `.apk` tap straight to `installApk` — the same permission-gated installer handoff the button ran (O+ `canRequestPackageInstalls` gate, API-21-safe `file://` on 5.x/6.x) |
| 4 | Renaming moves the item to the top and resets its age to "0s" | `ReceivedRegistry.record()` stamped **now** unconditionally; the reconcile sorts by that stamp and the "[Time] ago" label renders from it, so the renamed path was re-birthed | `record()` gained an `atMs` parameter (0 = legacy "now"); the rename reads the ORIGINAL stamp before forgetting the old path and records the new path WITH it (a nested member's preserved stamp never drags its directory's stamp backward). The engine row's `finishedAt` is never touched |
| 5 | Long titles truncate awkwardly | tv_name's MIDDLE ellipsis is width-driven (good) but a 90-char name renders as a mostly-elided sliver | `displayTitleFor`: ≤48 chars verbatim; longer names pre-shortened to "first 30 … last 15" so the extension/suffix stays identifiable; the width-measured ellipsis still handles the actual fit. Presentation only — the real filename is untouched everywhere else |
| 6 | ⋮ sat in the gray metadata row | The FP4 shell wrapped tv_detail in a `detail_row` and appended the ⋮ to IT | The ⋮ is a permanent SHELL slot at the END of row1 (the title's own row, top-right of the card); the `detail_row` wrapper is retired (tv_detail returns to the plain Pass-3 shape). Visibility + listeners are re-set per bind — a recycled card can never carry a stale button, at zero add/remove cost |
| 7 | Purple Received badge clutters Received cards | The direction badge rendered on every tab by design | Hidden (`GONE`) on the Received tab only — solo and group rows both; History and Active keep the full badge system. No layout hole (GONE views take no space in the FrameLayout stack) |
| 8 | Media from Received/History opens externally | The card click called `openFile` (ACTION_VIEW) directly — the app's OWN previewer/music controller were only wired into the Send tab | New `openTransferFile` router on the card click (and folder children + the Transfers browse sheet): images/videos → the Media Picker's preview overlay via its existing queue-preview entry (single item, Uri.fromFile — the previewer's own path-first decode); audio → the shared `MusicPlayback` controller with the exact Track shape `openStandaloneFile` builds (MediaStore enrichment first, file-shaped fallback). Everything else (documents/APKs/archives; SVG/ICO deliberately) keeps `openFile`. No second player implementation anywhere |

## 1. What changed, file by file

| File | Changes |
|---|---|
| `app/src/main/java/com/lumidrop/x/TransfersFragment.kt` | §6 shell ⋮ slot + detail_row retirement; §7 badge GONE on Received (solo + group); §2 hidePeer threading (detailLine, groupDetailLine, registerAgoTarget, registerGroupAgoTarget, patchProgressViews, patchGroupProgressViews, payload bind); §5 displayTitleFor + Received-only use; §1 menu (4 actions) + openReceivedFileWith + showInHistory/revealPendingHistoryRow/flashHistoryRow + renderHistoryTab reveal consumption + tombstone guards; §3 Install removal + openFile→installApk APK routing; §4 rename stamp preservation; §8 openTransferFile + playAudioFile + card/child/sheet click rewiring |
| `app/src/main/java/com/lumidrop/x/transfer/ReceivedRegistry.kt` | §4 `record(context, path, atMs = 0)` — preserved-stamp recording with a directory-stamp monotonic guard |
| `app/build.gradle` | versionCode 77 + the pass notes |
| `app/src/test/.../FinalPass5RenameStampTest.kt` | NEW — 4 tests (ordering preservation, the regression contrast, the ago-label math, the exact rename call sequence) |
| `app/src/test/.../FinalPass5DisplayAndMediaRouteTest.kt` | NEW — 8 tests (title truncation boundaries/extension survival, media classification for preview/music/external, SVG/ICO exclusion, family disjointness) |

## 2. Verification checklist (requirement by requirement)

| Requirement | Status | How verified |
|---|---|---|
| Every Received card's ⋮ in the top-right of the title row | ✅ | Source: shell builds the slot at the end of row1; bind sets visibility+listeners per row. Gradle compile |
| Open With invokes platform app-selection | ✅ | Source trace: `createChooser(ACTION_VIEW)` over the same FileProvider URI+MIME; graceful "no app" via the system chooser. No device available for a live pick |
| Show in History locates the exact item | ✅ | Path-identity matching across store entries/folder runs/live rows; pinned by trace + the tombstone guards. JVM tests cover the consumers of the stamp/identity data |
| Navigates + applies the existing highlight effect | ✅ | Reuses the Files sheet's reveal values verbatim (800ms hold/450ms fade, 1/3-list position); runs in the History render's diff-commit callback. Runtime scroll not device-tested |
| Missing History entries handled gracefully | ✅ | Toast + no navigation; tombstoned records report the same honest absence |
| "From …" labels only in History | ✅ | hidePeer=true only where `selTab == "received"`; History call sites unchanged. Compile + trace |
| No Install button in either tab | ✅ | The one shared builder no longer adds it; both tabs render through it. Compile |
| APK card-click still installs | ✅ | openFile routes `.apk` to the untouched `installApk` (permission gate preserved). Not device-tested |
| Polished compact truncation | ✅ | JVM tests: ≤48 verbatim; 49+ head…tail with extension survival; spaces/no-extension handled |
| Purple badge gone (Received only) | ✅ | Solo + group binds set GONE on `selTab == "received"`; History/Active untouched. Compile |
| History badge system unchanged | ✅ | Only the Received branch of the two bind functions changed |
| Rename keeps position/timestamp/age | ✅ | Root cause removed in `ReceivedRegistry.record(atMs)`; JVM tests pin ordering + ago math + call sequence |
| Media → internal players (Received & History) | ✅ | One router for both tabs + folder children + the browse sheet; reuses the previewer's queue-preview entry and MusicPlayback's Track shape. Classification pinned by JVM tests |
| Files-tab / other entry points intact | ✅ | SendFragment's own open paths untouched; `openFile` preserved as the fallback |

## 3. Tests performed

- **Gradle `testReleaseUnitTest`: 214/214 pass, 0 failures, 0 skipped** — the
  FULL project suite through the real build pipeline (all prior passes'
  regression suites, the ui/ suites, and the 8 new FP5 tests).
- **Full JVM suite (standalone kotlinc + JUnit, engine-classpath subset):
  199/199 pass** — the same engine tests outside Gradle. The two new test
  classes initially caught a REAL product bug (SVG's `image/svg+xml` MIME
  slipping past the extension-only exclusion into the raster previewer) —
  fixed in the router and pinned by the test.
- **Gradle `assembleRelease`: BUILD SUCCESSFUL** — signed APK
  (`apksigner verify` clean, LumiDrop X cert), `assets/index.html` embedded.
- **Full-tree syntax check**: the modified tree's no-classpath error histogram
  contains no error class the pristine FP4 baseline lacks, and zero
  syntax-class errors.

## 4. Not performed (no access in this environment)

- **Physical-device / emulator testing** — every UI/UX claim above is
  source-trace + compile + JVM-test level. In particular, NOT runtime-verified
  on hardware: the Open With chooser against real share targets, the
  Show-in-History scroll/flash over a real long list, APK install handoff on
  real Android 5 and 8+ builds, the previewer/video playback over a
  file-URI item, and MusicPlayback enrichment on a real MediaStore.
- **Old-iOS/old-Android browser checks** — no browser changes were made this
  pass (the WebUI is untouched), so no new legacy risk was introduced.

## 5. Known limitations

- Group (folder) rows still have no ⋮ (the FP4 scope decision — folder
  rename/share is a filesystem operation over many files; unchanged).
- Show in History matches by file path: a file that was RENAMED outside the
  app after receipt (so the History entry holds the old path) honestly
  reports "No history record for this item".
- The ⋮ hides during selection mode (deliberate — same discipline as FP4's
  selection branch) and returns when the mode ends.

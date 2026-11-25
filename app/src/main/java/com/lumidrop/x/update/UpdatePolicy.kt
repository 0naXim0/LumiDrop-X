package com.lumidrop.x.update

/**
 * ══ PRODUCTION FINAL PASS 23 — the updater's decision core ═════════════════
 *
 * Pure Kotlin, zero Android imports: every policy the reconstructed download/
 * verify/install lifecycle needs is decided HERE so the whole matrix — 416
 * recovery, resume-vs-restart, reuse of an already-downloaded APK, retry
 * pacing, release supersession, cleanup keep-rules — is provable in plain
 * JVM unit tests (see Pass23UpdaterReconstructionTest). UpdateManager owns
 * I/O and state publication; it never invents a decision itself.
 *
 * Why this module exists (the Pass-22 audit): the failures reported on the
 * real device were all DECISION failures hiding behind I/O code —
 *  - HTTP 416 became a permanent failure because nothing inspected the
 *    server's Content-Range total or the partial file's actual length;
 *  - a validated APK was redownloaded (and 416-looped) because nothing
 *    compared the on-disk file against the expected asset before dialing;
 *  - update availability vanished on restart because nothing decided what
 *    to restore from disk;
 *  - a valid APK was DELETED on restart because the cleanup rule keyed on
 *    in-memory state that a process death had reset.
 */
object UpdatePolicy {

    // ── §3.B — Content-Range parsing (both response shapes) ─────────────────

    /**
     * Total resource length from a Content-Range header. Handles:
     *  - the 416 UNSATISFIED-range shape (star-slash-total: the server
     *    names the CURRENT total even though it refused the range);
     *  - the 206 SATISFIED-range shape (start-end-slash-total);
     *  - the unknown-total shape (start-end-slash-star) — null here.
     * Returns null when absent/unparseable — callers treat that honestly.
     */
    fun contentRangeTotal(header: String?): Long? {
        if (header.isNullOrBlank()) return null
        val unsatisfied = Regex("""bytes\s+\*/(\d+)""").find(header)
        if (unsatisfied != null) return unsatisfied.groupValues[1].toLongOrNull()
        val satisfied = Regex("""bytes\s+\d+-\d+/(\d+)""").find(header)
        return satisfied?.groupValues?.get(1)?.toLongOrNull()
    }

    /**
     * Did the server honor a resume from [fromByte]? True only when the
     * Content-Range's FIRST byte index equals the requested offset — a 206
     * that starts anywhere else (0, a smaller offset, a changed asset) must
     * never be appended onto the partial file.
     */
    fun rangeHonoredAt(header: String?, fromByte: Long): Boolean {
        if (header.isNullOrBlank() || fromByte <= 0) return false
        val m = Regex("""bytes\s+(\d+)-""").find(header) ?: return false
        return m.groupValues[1].toLongOrNull() == fromByte
    }

    // ── §3.C — intelligent HTTP 416 recovery ────────────────────────────────

    /** What a 416 response tells us to do with the existing partial file. */
    enum class RangeMismatchAction {
        /** The partial file already has every byte the server holds — treat
         *  it as a finished download and go straight to validation. */
        FILE_ALREADY_COMPLETE,
        /** The offset is unusable (asset changed / stale oversized partial /
         *  total unknown but a clean start is possible) — discard ONLY the
         *  invalid partial data and restart from byte 0. */
        RESTART_CLEAN,
        /** Recovery budget exhausted or the situation is undecidable — stop
         *  honestly instead of looping the same invalid request. */
        FAIL_HONEST
    }

    /**
     * The 416 decision matrix (§3.C 1–7). [partialLen] is the ACTUAL file
     * length on disk (never a previously saved offset — §3.A), [serverTotal]
     * from Content-Range (null = the server did not say), [expectedSize] the
     * release metadata size when known, [recoveryAttemptsUsed] how many
     * automatic recoveries this download call has already spent.
     */
    fun rangeMismatchAction(
        partialLen: Long,
        serverTotal: Long?,
        expectedSize: Long,
        recoveryAttemptsUsed: Int
    ): RangeMismatchAction {
        // Bounded recovery — never issue the same invalid request forever.
        if (recoveryAttemptsUsed >= MAX_RECOVERY_ATTEMPTS) return RangeMismatchAction.FAIL_HONEST
        val total = serverTotal ?: expectedSize.takeIf { it > 0 }
        if (total != null && total > 0) {
            // Every byte already present — the "resume" was actually a
            // re-request of a COMPLETE file (the exact real-device loop:
            // a finished APK sat in cache/updates, every Download press
            // asked for bytes=<fullsize>- and got 416 forever).
            if (partialLen == total) return RangeMismatchAction.FILE_ALREADY_COMPLETE
            // More bytes on disk than the asset now has (release asset was
            // replaced by a different/smaller one) — the partial is unusable.
            if (partialLen > total) return RangeMismatchAction.RESTART_CLEAN
            // Fewer bytes than total: a legit-looking offset the server
            // still refused (e.g. range support broken behind the CDN).
            // A clean restart is the only safe move — never re-ask the
            // same refused offset.
            return RangeMismatchAction.RESTART_CLEAN
        }
        // No total from either source: one clean restart is still safe and
        // useful (a 200 full response answers everything); beyond that, stop.
        return if (recoveryAttemptsUsed < 1) RangeMismatchAction.RESTART_CLEAN
        else RangeMismatchAction.FAIL_HONEST
    }

    // ── §3.D — network resilience ───────────────────────────────────────────

    /** Highest HTTP status we auto-retry (per-attempt budget enforced by
     *  [shouldAutoRetry]/[retryDelayMs] — transient 5xx and 429 qualify,
     *  4xx refusals other than 416 never do). */
    fun isRetryableHttp(code: Int): Boolean =
        code == 429 || code in 500..599

    /**
     * May this failure be retried automatically? Transient transport
     * problems yes; "no network at all" no (retrying instantly into a dead
     * radio is exactly the hammering §3.D forbids — the honest state tells
     * the user what happened and the partial stays for the next tap).
     */
    fun shouldAutoRetry(errorKind: ErrorKind, attempt: Int): Boolean {
        if (attempt >= MAX_NETWORK_ATTEMPTS) return false
        return when (errorKind) {
            ErrorKind.NO_NETWORK -> false
            ErrorKind.CANCELLED -> false
            ErrorKind.HTTP_REFUSED -> false       // 4xx other than 416/429
            ErrorKind.TIMEOUT, ErrorKind.RESET,
            ErrorKind.SERVER, ErrorKind.RATE_LIMITED -> true
            ErrorKind.UNKNOWN -> false            // non-network failures: never auto-hammer
        }
    }

    enum class ErrorKind { NO_NETWORK, TIMEOUT, RESET, SERVER, RATE_LIMITED, HTTP_REFUSED, CANCELLED, UNKNOWN }

    /** Exponential backoff, hard-capped so the UI never looks wedged. */
    fun retryDelayMs(attempt: Int): Long =
        (1000L shl (attempt.coerceIn(0, 3))).coerceAtMost(4000L)

    const val MAX_NETWORK_ATTEMPTS = 3
    const val MAX_RECOVERY_ATTEMPTS = 2

    // ── §4 — download each version only once ───────────────────────────────

    /** What [UpdateManager.download] must do BEFORE dialing the network. */
    enum class PreDownloadAction {
        /** A file matching the expected asset size is on disk — validate it
         *  in place; only if it passes verification is the download skipped
         *  (an unparseable/corrupt file is deleted and re-fetched). */
        VERIFY_IN_PLACE,
        /** A smaller partial exists — resume from its ACTUAL length. */
        RESUME,
        /** Nothing useful on disk — fetch from byte 0. */
        FRESH
    }

    /**
     * §4 reuse rules 1–7: compare the on-disk file with the release's
     * expected asset BEFORE starting any download. [fileLen] < 0 = absent.
     * Expected size 0 = unknown metadata: any non-empty file is worth an
     * in-place verification pass (the verifier is the authority, never the
     * filename).
     */
    fun preDownloadAction(fileLen: Long, expectedSize: Long): PreDownloadAction {
        if (fileLen <= 0L) return PreDownloadAction.FRESH
        if (expectedSize > 0L) {
            return if (fileLen >= expectedSize) PreDownloadAction.VERIFY_IN_PLACE
            else PreDownloadAction.RESUME
        }
        return PreDownloadAction.VERIFY_IN_PLACE
    }

    // ── §5 — restore after restart ────────────────────────────────────────

    /** What restore() concludes from the persisted snapshot + disk truth. */
    enum class RestoreAction {
        /** The installed version already covers the persisted release — the
         *  offer is obsolete: clear state and sweep the staged files. */
        SUPERSEDED_BY_INSTALL,
        /** The staged APK looks complete — re-validate it now; if it passes,
         *  land in READY_TO_INSTALL with NO network traffic (§4/Test D). */
        REVALIDATE_FILE,
        /** A partial file exists — offer the release with resume available. */
        PARTIAL_RESUME,
        /** Release metadata is all we have — show Update Available. */
        OFFER_ONLY,
        /** No snapshot — fresh session, ordinary idle. */
        NOTHING
    }

    fun restoreAction(
        installedVersion: String?,
        snapshotVersion: String?,
        fileLen: Long,               // < 0 = file absent
        expectedSize: Long
    ): RestoreAction {
        if (snapshotVersion == null) return RestoreAction.NOTHING
        // The persisted release is already running (installed during the
        // last session or by hand) — never re-offer it.
        if (ReleaseModel.compareVersions(installedVersion, snapshotVersion) >= 0) {
            return RestoreAction.SUPERSEDED_BY_INSTALL
        }
        if (fileLen <= 0L) return RestoreAction.OFFER_ONLY
        return preDownloadAction(fileLen, expectedSize).let {
            when (it) {
                PreDownloadAction.VERIFY_IN_PLACE -> RestoreAction.REVALIDATE_FILE
                PreDownloadAction.RESUME -> RestoreAction.PARTIAL_RESUME
                PreDownloadAction.FRESH -> RestoreAction.OFFER_ONLY
            }
        }
    }

    // ── §3.E — end-of-stream verdicts ──────────────────────────────────────

    /** What a clean end-of-body means for the file on disk. */
    enum class EofVerdict {
        /** Every expected byte is present — finish and hand to verification. */
        COMPLETE,
        /** The body ended early — resumable: retry from the real length, or
         *  (budget exhausted) fail while PRESERVING the partial. */
        INCOMPLETE,
        /** The file cannot be the advertised asset (oversized with no server
         *  truth backing it) — invalid data, discard and fail. */
        INVALID
    }

    /**
     * The end-of-stream decision. [len] = actual file length after EOF,
     * [expected] = release metadata size (0 unknown), [serverTotal] = the
     * length the SERVER itself reported for this resource (Content-Range
     * total, or offset+Content-Length of the last response; null unknown).
     *
     * When metadata and server disagree, the SERVER is the fresher truth
     * and the package verifier remains the hard gate — an asset replaced
     * upstream makes stale metadata sizes wrong in both directions, and
     * the verifier (package name, version, signing identity) is what
     * actually decides whether the file may install.
     */
    fun eofVerdict(len: Long, expected: Long, serverTotal: Long?): EofVerdict {
        if (len <= 0) return EofVerdict.INVALID
        val total = serverTotal ?: expected.takeIf { it > 0 }
        return when {
            total != null && total > 0 -> when {
                len == total -> EofVerdict.COMPLETE
                len < total -> EofVerdict.INCOMPLETE
                else -> EofVerdict.INVALID      // more bytes than the resource has
            }
            // Neither source knows the size (old behavior): clean EOF = done;
            // the verifier is the only remaining gate.
            else -> EofVerdict.COMPLETE
        }
    }

    // ── §11 — release supersession ────────────────────────────────────────

    /**
     * Does the newly discovered release supersede the one already staged?
     * Different tag OR different asset URL (the same tag whose asset was
     * replaced upstream) both count: the staged bytes may no longer be the
     * bytes the release points at, and must never be presented as the new
     * release's download (§11). Nulls never supersede.
     */
    fun supersedes(newTag: String?, newUrl: String?, oldTag: String?, oldUrl: String?): Boolean {
        if (newTag == null || oldTag == null) return false
        if (newTag != oldTag) return true
        return newUrl != null && oldUrl != null && newUrl != oldUrl
    }

    // ── §9 — cleanup keep-rules ────────────────────────────────────────────

    /**
     * May [candidate] be swept from the updates directory now? The sweep is
     * updater-owned (only files under cache/updates, never anything else —
     * §9 cleanup safety) and NEVER takes a file that an in-flight operation
     * may still be writing, or the CURRENT release's staged APK while it is
     * still needed (verified-ready or resumable).
     */
    fun maySweep(
        candidateName: String,
        currentApkName: String?,
        operationInFlight: Boolean
    ): Boolean {
        if (operationInFlight) return false                 // mid-write — hands off
        if (currentApkName == null) return true             // nothing tracked — all stale
        return candidateName != currentApkName              // only OTHER releases' files
    }

    // ── §3.E — optional published checksum ────────────────────────────────

    /**
     * A "SHA-256: <64 hex>" line inside the release notes is the only
     * trusted-checksum channel this repo uses (audited convention — GitHub
     * itself publishes none for release assets). Extract it for enforcement;
     * null means "nothing published → not enforced" (never invented).
     */
    fun checksumFromNotes(bodyMarkdown: String?): String? {
        if (bodyMarkdown.isNullOrBlank()) return null
        val m = Regex("""(?i)SHA-?256\s*[:=]\s*\b([0-9a-fA-F]{64})\b""").find(bodyMarkdown)
            ?: return null
        return m.groupValues[1].lowercase()
    }

    // ── §12 — request hygiene ─────────────────────────────────────────────

    /**
     * Progress ticks are throttled to this many milliseconds — real bytes on
     * every tick, but never a flood that starves the render thread.
     */
    const val PROGRESS_TICK_MS = 200L
}

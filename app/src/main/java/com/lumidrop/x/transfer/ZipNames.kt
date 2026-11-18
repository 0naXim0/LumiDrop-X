package com.lumidrop.x.transfer

/**
 * ── GOAT-PASS 14 (Phase 1, §1.4): the ONE user-facing ZIP filename generator ──
 * shared by every surface that names a synthesized archive — the browser's
 * own Full-Access "Download as zip" (LumiDropServer.apiZipStream) and the new
 * app-side queue-batch ZIP sends (browser Quick-Share leg and native-push leg
 * alike) — so both naming systems can never drift apart.
 *
 * Professional naming contract (an improved, character-bounded version of the
 * established "A_B_more__(N_Files).zip" style):
 *
 *  • ONE source                -> "<SourceName>.zip" (the source's full name,
 *                                 extension included; a source that already
 *                                 ends in ".zip" is never doubled).
 *  • MULTIPLE sources          -> "<First>_<Second>_more__(N_<noun>).zip"
 *      - First: the first source's label, capped at [FIRST_MAX] characters
 *        (cut at a word boundary where one exists, one "…" when cut).
 *      - Second: the next source's label, capped at [SECOND_MAX] characters,
 *        shown only when there are at least two sources.
 *      - "_more" appears only when there are more sources than labels shown.
 *      - N counts the TOP-LEVEL sources actually included (never items the
 *        caller dropped as excluded/unavailable), with the noun following the
 *        established all-Folders / all-Files / mixed-Items rule.
 *  • Every label is sanitized (filename-hostile characters -> '_', leading
 *    dots dropped) but meaningful Unicode is preserved verbatim.
 *  • The whole label portion is capped at [TOTAL_LABEL_MAX] so no archive
 *    name ever approaches a filesystem limit.
 *
 * Pure Kotlin with zero Android dependency, on purpose: the engine test suite
 * (ZipNamesTest) compiles and runs this object on the JVM.
 */
object ZipNames {

    /** One naming source — [label] is already the "display" form the caller
     *  wants in the name (files: nameWithoutExtension for multi-source
     *  labels / full name for the single-source case; folders: their own
     *  name). [isFolder] drives the noun. */
    data class Entry(val label: String, val isFolder: Boolean)

    private const val FIRST_MAX = 60
    private const val SECOND_MAX = 25
    private const val TOTAL_LABEL_MAX = 110
    private const val MIN_WORD_KEEP = 24

    private val HOSTILE = Regex("[/\\\\:*?\"<>|\u0000-\u001F]")
    private val WHITESPACE = Regex("\\s+")

    /** Sanitizes one label segment: whitespace runs collapse to a single
     *  space FIRST (so a tab or newline inside a label reads as the space
     *  it visually is, not as an underscore), then filename-hostile and C0
     *  control characters become '_', leading dots are dropped (a dot-file
     *  name would hide the archive), and an empty result falls back to a
     *  stable placeholder. */
    fun sanitizeLabel(s: String): String {
        val cleaned = s.replace(WHITESPACE, " ").replace(HOSTILE, "_").trim().trimStart('.')
        return cleaned.ifEmpty { "file" }
    }

    /** Caps [s] at [max] characters, preferring a word-boundary cut and
     *  marking any genuine cut with a single "…". Never returns an empty
     *  string for non-empty input. */
    private fun capLabel(s: String, max: Int): String {
        if (s.length <= max) return s
        val cut = s.substring(0, max - 1)   // leave room for the ellipsis
        val lastSpace = cut.lastIndexOf(' ')
        val kept = if (lastSpace >= MIN_WORD_KEEP) cut.substring(0, lastSpace) else cut
        val out = kept.trimEnd()
        return (if (out.isEmpty()) cut else out) + "…"
    }

    /** True when [name] already carries the archive extension. */
    private fun hasZipExt(name: String): Boolean =
        name.length >= 4 && name.substring(name.length - 4).equals(".zip", ignoreCase = true)

    /** Builds the archive filename for the given (already inclusion-filtered)
     *  sources. A zero/one-source list always produces a valid name. */
    fun build(entries: List<Entry>): String {
        val usable = entries.filter { it.label.isNotBlank() }
        if (usable.isEmpty()) return "Archive.zip"
        if (usable.size == 1) {
            // Single source: keep its full name (extension and all) exactly
            // like the established browser-side behaviour — only never let a
            // ".zip" suffix double up.
            val name = sanitizeLabel(usable[0].label)
            return if (hasZipExt(name)) name else "$name.zip"
        }
        val total = usable.size
        val first = capLabel(sanitizeLabel(usable[0].label), FIRST_MAX)
        val second = capLabel(sanitizeLabel(usable[1].label), SECOND_MAX)
        val more = if (total > 2) "_more" else ""
        val allDirs = usable.all { it.isFolder }
        val allFiles = usable.none { it.isFolder }
        val noun = when {
            allDirs -> "Folders"
            allFiles -> "Files"
            else -> "Items"
        }
        val label = "${first}_${second}$more"
        // Belt-and-braces total cap: the two bounded labels plus the fixed
        // suffix cannot exceed it by construction, but a future caller with
        // different caps stays safe.
        return (if (label.length > TOTAL_LABEL_MAX) label.substring(0, TOTAL_LABEL_MAX).trimEnd() else label) +
            "__(${total}_${noun}).zip"
    }
}

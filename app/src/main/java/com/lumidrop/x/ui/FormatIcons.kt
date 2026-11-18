package com.lumidrop.x.ui

import android.content.Context
import android.graphics.drawable.Drawable
import androidx.core.content.ContextCompat
import com.lumidrop.x.R

/**
 * §static-format-icons (post-v1.0 pass): ONE shared resolver for
 * "which glyph + which tint represents this file's format", replacing the
 * four per-screen `fileIconRes` copies (SendFragment, TransfersFragment,
 * ReceivingModal, QueueAdapter-via-lambda) that had drifted into the same
 * minimal set — where every document family collapsed into a PENCIL
 * (ic_edit), pdf/xlsx/pptx/epub had no glyph of their own at all, and
 * everything rendered in one undifferentiated gray. A no-thumbnail file's
 * ONLY identity in a list row is its glyph+color pair, so the pair is now
 * (a) per-family and (b) color-coded — a pdf reads red, a sheet green, an
 * archive amber — at the same 16-20dp sizes the old generic file icon
 * used. Media/apk families keep their existing glyphs (their thumbnails
 * usually exist anyway; the glyph is only their fallback).
 *
 * Call sites: SendFragment's queue rows (via QueueAdapter's
 * formatIcon lambda), TransfersFragment solo/group rows, ReceivingModal's
 * incoming rows — every surface that used to call its own fileIconRes.
 */
object FormatIcons {

    /** The glyph resource for an extension (with or without the dot). */
    fun iconFor(extIn: String): Int {
        val ext = extIn.removePrefix(".").lowercase()
        return when (ext) {
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico", "svg", "tiff" -> R.drawable.ic_image
            "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "3gp", "m4v", "ts", "mpg", "mpeg" -> R.drawable.ic_video
            "mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma", "aiff", "amr", "mid" -> R.drawable.ic_audio
            // ── GOAT-PASS 10 (PART 1.2): the package CONTAINER family gets its own ──
            // mark — a stacked-blocks document (ic_file_xapk) distinct from the
            // single-robot ic_file_apk, so an XAPK/APKM/APKS never reads as an
            // ordinary APK nor as a generic ZIP.
            "apk" -> R.drawable.ic_file_apk
            "xapk", "apkm", "apks" -> R.drawable.ic_file_xapk
            "aab" -> R.drawable.ic_file_apk
            "pdf" -> R.drawable.ic_file_pdf
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst" -> R.drawable.ic_file_zip
            "doc", "docx", "txt", "md", "rtf", "odt", "log", "info" -> R.drawable.ic_file_doc
            "xls", "xlsx", "csv", "ods", "tsv" -> R.drawable.ic_file_sheet
            "ppt", "pptx", "odp" -> R.drawable.ic_file_slide
            "epub", "mobi", "azw3", "azw", "fb2" -> R.drawable.ic_file_book
            "js", "ts", "html", "htm", "css", "py", "java", "kt", "json", "xml", "c", "cpp", "sh", "yaml", "yml", "sql" -> R.drawable.ic_file_code
            else -> R.drawable.ic_file
        }
    }

    /** The glance-identifiable per-family tint for an extension. */
    fun tintFor(extIn: String): Int {
        val ext = extIn.removePrefix(".").lowercase()
        return when (ext) {
            "pdf" -> R.color.file_pdf
            "zip", "rar", "7z", "tar", "gz", "bz2", "xz", "zst" -> R.color.file_zip
            "doc", "docx", "txt", "md", "rtf", "odt", "log", "info" -> R.color.file_doc
            "xls", "xlsx", "csv", "ods", "tsv" -> R.color.file_sheet
            "ppt", "pptx", "odp" -> R.color.file_slide
            "epub", "mobi", "azw3", "azw", "fb2" -> R.color.file_book
            "js", "ts", "html", "htm", "css", "py", "java", "kt", "json", "xml", "c", "cpp", "sh", "yaml", "yml", "sql" -> R.color.file_code
            // Media/apk families: the same neutral/accent tints these rows
            // always used — their color story was never the complaint.
            "jpg", "jpeg", "png", "gif", "webp", "bmp", "heic", "heif", "ico", "svg", "tiff" -> R.color.text_secondary
            "mp4", "mkv", "avi", "mov", "webm", "wmv", "flv", "3gp", "m4v", "ts", "mpg", "mpeg" -> R.color.text_secondary
            "mp3", "flac", "ogg", "wav", "aac", "m4a", "opus", "wma", "aiff", "amr", "mid" -> R.color.text_secondary
            // Pass-12 (Required Implementation 5): APK rows now render the
            // dedicated line-style package glyph (ic_file_apk — folded-corner
            // document + Android robot head) instead of the old filled robot
            // icon, mirrored by the web client's own inline SVG so both sides
            // of a transfer show the same professional mark. Green tint keeps
            // the glance-identifiable Android-family cue.
            "apk", "apks", "xapk", "aab" -> R.color.green
            else -> R.color.text_secondary
        }
    }

    /** Convenience: the glyph pre-tinted and ready to set on an ImageView. */
    fun icon(context: Context, ext: String): Drawable =
        Widgets.icon(context, iconFor(ext), ContextCompat.getColor(context, tintFor(ext)))
}

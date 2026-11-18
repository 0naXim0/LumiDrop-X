package com.lumidrop.x.server

object MimeTypes {

    private val map = mapOf(
        // Images
        "jpg"  to "image/jpeg",
        "jpeg" to "image/jpeg",
        "png"  to "image/png",
        "gif"  to "image/gif",
        "webp" to "image/webp",
        "bmp"  to "image/bmp",
        "svg"  to "image/svg+xml",
        "ico"  to "image/x-icon",
        "heic" to "image/heic",
        "heif" to "image/heif",

        // Video
        "mp4"  to "video/mp4",
        "mkv"  to "video/x-matroska",
        "avi"  to "video/x-msvideo",
        "mov"  to "video/quicktime",
        "wmv"  to "video/x-ms-wmv",
        "flv"  to "video/x-flv",
        "3gp"  to "video/3gpp",
        "webm" to "video/webm",
        "m4v"  to "video/mp4",
        "ts"   to "video/mp2t",

        // Audio
        "mp3"  to "audio/mpeg",
        "flac" to "audio/flac",
        "ogg"  to "audio/ogg",
        "wav"  to "audio/wav",
        "aac"  to "audio/aac",
        "m4a"  to "audio/mp4",
        "opus" to "audio/opus",
        "wma"  to "audio/x-ms-wma",
        "aiff" to "audio/aiff",

        // Documents
        "pdf"  to "application/pdf",
        "doc"  to "application/msword",
        "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        "xls"  to "application/vnd.ms-excel",
        "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        "ppt"  to "application/vnd.ms-powerpoint",
        "pptx" to "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        "txt"  to "text/plain",
        "csv"  to "text/csv",
        "html" to "text/html",
        "htm"  to "text/html",
        "xml"  to "text/xml",
        "json" to "application/json",
        "md"   to "text/markdown",

        // Books (Final Stabilization §10): the two ebook containers get
        // their REAL registered media types, not a generic binary blob —
        // "application/epub+zip" and "application/x-mobipocket-ebook" are
        // what every reader ecosystem and iOS's own document interception
        // layer key on. Serving them correctly means legacy Safari treats
        // the response as a non-displayable document it must hand to its
        // download/"Open in" machinery instead of dumping raw binary as a
        // text page.
        "epub" to "application/epub+zip",
        "mobi" to "application/x-mobipocket-ebook",
        "azw3" to "application/vnd.amazon.ebook",

        // Archives
        "zip"  to "application/zip",
        "rar"  to "application/x-rar-compressed",
        "7z"   to "application/x-7z-compressed",
        "tar"  to "application/x-tar",
        "gz"   to "application/gzip",

        // Apps
        "apk"  to "application/vnd.android.package-archive",

        // Code
        "js"   to "application/javascript",
        "css"  to "text/css",
        "py"   to "text/x-python",
        "kt"   to "text/x-kotlin",
        "java" to "text/x-java",
        "cpp"  to "text/x-c",
        "c"    to "text/x-c",
        "sh"   to "application/x-sh",

        // Final Stabilization (§2): plain-text formats the Web Client's
        // text viewer already handles — they download as text/* (openable
        // inline by any browser) instead of opaque octet-stream blobs.
        "log"  to "text/plain",
        "ini"  to "text/plain",
        "conf" to "text/plain",
        "yaml" to "text/yaml",
        "yml"  to "text/yaml",
        "srt"  to "text/plain",
        "vtt"  to "text/vtt",
        "rtf"  to "application/rtf",
    )

    fun getMimeType(extension: String): String =
        map[extension.lowercase()] ?: "application/octet-stream"

    // Phase 5 (§5.7 — ICO support): "ico" joins the isImage set. It was
    // already in the MIME map above (image/x-icon — served correctly to
    // browsers) but NOT in this set, so ThumbnailEngine.categoryOf("ico")
    // fell through to GENERIC and load() returned null before ever
    // attempting a decode — half of why .ico files rendered as a blank/
    // gray area. The other half (BitmapFactory having no ICO decoder at
    // all) is fixed by ThumbnailShared.icoThumb. SVG keeps its existing
    // place (browsers decode it as an image MIME; the native pipeline
    // returns null for it and rows fall back to the vector file icon —
    // unchanged).
    fun isImage(ext: String) = ext in setOf("jpg","jpeg","png","gif","webp","bmp","heic","heif","svg","ico")
    fun isVideo(ext: String) = ext in setOf("mp4","mkv","avi","mov","wmv","flv","3gp","webm","m4v","ts")
    fun isAudio(ext: String) = ext in setOf("mp3","flac","ogg","wav","aac","m4a","opus","wma","aiff")
    fun isDoc(ext: String)   = ext in setOf("pdf","doc","docx","xls","xlsx","ppt","pptx","txt","csv","md","log","xml","json","yaml","yml","ini","srt","vtt","epub","mobi")
    // GOAT-PASS 10 (PART 1.1): the ZIP-based Android package containers are
    // PACKAGES, not archives — routing them through isArchive made XAPKs
    // preview as generic ZIPs everywhere categoryOf feeds a decision.
    fun isApk(ext: String)   = ext == "apk" || ext == "xapk" || ext == "apkm" || ext == "apks"
    fun isArchive(ext: String) = ext in setOf("zip","rar","7z","tar","gz")

    // Final Stabilization (§10): the ebook containers the legacy-iOS
    // delivery system is built around — detected by the Web Client for the
    // "Open in Books" fallback card and by the server for the
    // attachment-disposition download route.
    fun isBook(ext: String)  = ext in setOf("epub","mobi","azw3","azw")
}

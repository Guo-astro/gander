package com.arjun.gander

/**
 * What we know how to display, and which bundled HTML page renders it
 * (empty page means a native view handles it).
 */
enum class FileKind(val page: String) {
    IMAGE(""),
    IMAGE_WEB("imgweb.html"),
    PDF("pdf.html"),
    PLAYER(""),
    DOCX("docx.html"),

    /**
     * OpenDocument text, Rich Text and Word 97-2003, read by Gander's own readers in one
     * page, which asks the file's first bytes which it is: a ".doc" is Rich Text about as
     * often as not. Issues #4 and #13, and the README's oldest caveat.
     */
    PROSE("prose.html"),
    XLSX("xlsx.html"),
    PPTX("pptx.html"),
    MD("md.html"),
    TEXT("text.html"),

    /** A 3D model, drawn with WebGL by a page of Gander's own. STL for now. */
    MODEL("model.html"),

    /** A .zip, which is listed rather than drawn: see ArchiveBrowser. Issue #30. */
    ARCHIVE(""),
    UNSUPPORTED("unsupported.html");

    /**
     * Drawn as pages: its page says which one is on screen, goes to one when asked, and
     * turns over for night mode. A PDF, and a Word document since issue #47.
     */
    val paged: Boolean get() = this == PDF || this == DOCX

    companion object {
        private val imageExt = setOf("jpg", "jpeg", "png", "webp", "bmp", "heic", "heif")
        private val imageWebExt = setOf("gif", "svg", "avif", "ico")

        // Each Office format's relatives are its own package with the main part declared as
        // something else: a template, a slide show, or a file allowed macros, which no viewer
        // here runs. The renderers read them as they read the format itself, and
        // tests/viewer opens one of each to prove it.
        private val wordExt = setOf("docx", "docm", "dotx")
        private val proseExt = setOf("odt", "ott", "fodt", "rtf", "doc", "dot")
        private val sheetExt = setOf("xlsx", "xls", "xlsm", "xlsb", "xltx", "csv", "ods")
        private val slideExt = setOf("pptx", "ppsx", "pptm", "potx")
        private val mdExt = setOf("md", "markdown")
        private val modelExt = setOf("stl")
        private val archiveExt = setOf("zip")
        private val videoExt = setOf(
            "mp4", "m4v", "mov", "mkv", "webm", "3gp", "3g2", "m2ts", "mts", "avi", "flv"
        )
        private val audioExt = setOf(
            "mp3", "m4a", "aac", "flac", "wav", "ogg", "oga", "opus", "amr"
        )
        private val textExt = setOf(
            "txt", "log", "json", "xml", "yaml", "yml",
            "kt", "java", "py", "js", "ts", "html", "htm", "css", "sh", "zsh", "bash",
            "c", "cpp", "h", "hpp", "rs", "go", "rb", "php", "sql", "swift", "dart",
            "gradle", "properties", "toml", "ini", "cfg", "conf", "tex", "r", "csv",
            // Subtitles, a playlist and the notes that come with a download, all text under
            // another name. The playlist is text even when it is labelled audio: see
            // playlistMimes.
            "srt", "vtt", "m3u", "nfo",
            // Hardware description: Verilog and SystemVerilog sources and headers, and VHDL.
            // A .vhd is also a virtual disk's name, which is not a file opened on a phone.
            "v", "vh", "sv", "svh", "vhd", "vhdl"
        )

        private const val MIME_DOCX =
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        private const val MIME_XLSX =
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        private const val MIME_PPTX =
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        private const val MIME_ODS = "application/vnd.oasis.opendocument.spreadsheet"
        private const val MIME_ODT = "application/vnd.oasis.opendocument.text"
        private const val MIME_OTT = "application/vnd.oasis.opendocument.text-template"
        private const val MIME_DOC = "application/msword"
        private val proseMimes = setOf(MIME_ODT, MIME_OTT, MIME_DOC, "application/rtf", "text/rtf")
        private const val MIME_ZIP = "application/zip"

        /** What Windows calls a zip, and so what one attached on Windows often arrives as. */
        private const val MIME_ZIP_WINDOWS = "application/x-zip-compressed"

        // The Office relatives by type, written in lower case because that is how route()
        // compares: see detect().
        private val wordMimes = setOf(
            MIME_DOCX,
            "application/vnd.ms-word.document.macroenabled.12",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template",
        )
        private val sheetMimes = setOf(
            MIME_XLSX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template",
            "application/vnd.ms-excel",
            "text/csv",
            MIME_ODS,
        )
        private val slideMimes = setOf(
            MIME_PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow",
            "application/vnd.ms-powerpoint.presentation.macroenabled.12",
            "application/vnd.openxmlformats-officedocument.presentationml.template",
        )

        /**
         * An STL, under every name it goes by. model/stl is the registered one, the two x.
         * ones and application/sla are what Linux desktops and older servers say, and
         * application/vnd.ms-pki.stl is what Android itself gives a .stl, having taken the
         * extension for a Windows certificate trust list. That last is the type a file
         * manager sends, so without it Gander would never be offered one.
         */
        private val modelMimes = setOf(
            "model/stl",
            "model/x.stl-binary",
            "model/x.stl-ascii",
            "application/sla",
            "application/vnd.ms-pki.stl",
        )

        /** Text whose type is not text/. SubRip's is the one Android 10 and later give a .srt. */
        private val textMimes = setOf("application/json", "application/xml", "application/x-subrip")

        /**
         * An .m3u playlist, which Android labels as audio. It is a list of where its tracks
         * are, as paths beside it or as links, and the player can follow neither: Gander
         * holds a grant for the playlist alone and has no network. So one that arrives with
         * no extension opens as text too, which at least lists what was on it.
         */
        private val playlistMimes = setOf("audio/x-mpegurl", "audio/mpegurl")

        fun isAudioExt(ext: String) = ext in audioExt

        /**
         * Where a file goes: by its extension, and failing that by the type its provider gave.
         *
         * The type is lowercased first. MIME types are case-insensitive, and the spellings
         * that reach Gander differ: Android 10 and later report every type in lower case,
         * while the registered one for a .docm or a .pptm says macroEnabled, and an app that
         * passes on the type a file was sent with can hand over either. That is also why the
         * manifest, whose matching is case-sensitive, claims those two in both spellings.
         */
        fun detect(ext: String, mime: String?): FileKind = route(ext, mime?.lowercase())

        private fun route(ext: String, mime: String?): FileKind = when {
            // .ts is TypeScript unless the provider says it is a video container
            ext == "ts" && mime?.startsWith("video/") == true -> PLAYER
            ext in imageExt -> IMAGE
            ext in imageWebExt -> IMAGE_WEB
            ext == "pdf" -> PDF
            ext in videoExt || ext in audioExt -> PLAYER
            ext in wordExt -> DOCX
            ext in proseExt -> PROSE
            ext in sheetExt -> XLSX
            ext in slideExt -> PPTX
            ext in mdExt -> MD
            ext in textExt -> TEXT
            ext in modelExt -> MODEL
            ext in archiveExt -> ARCHIVE
            mime == "application/pdf" -> PDF
            // Ahead of the audio branch, which would otherwise take it
            mime in playlistMimes -> TEXT
            mime?.startsWith("video/") == true || mime?.startsWith("audio/") == true -> PLAYER
            mime in wordMimes -> DOCX
            mime in proseMimes -> PROSE
            mime in sheetMimes -> XLSX
            mime in slideMimes -> PPTX
            mime in modelMimes -> MODEL
            mime == MIME_ZIP || mime == MIME_ZIP_WINDOWS -> ARCHIVE
            mime?.startsWith("image/") == true -> IMAGE_WEB
            mime?.startsWith("text/") == true -> TEXT
            mime in textMimes -> TEXT
            else -> UNSUPPORTED
        }
    }
}

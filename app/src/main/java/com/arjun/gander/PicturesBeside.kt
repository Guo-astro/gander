package com.arjun.gander

import android.content.ContentResolver
import android.net.Uri
import android.provider.DocumentsContract
import android.webkit.WebResourceResponse
import java.io.ByteArrayOutputStream

/**
 * The pictures a Markdown file names by a path from its own folder, such as `![](shots/home.png)`,
 * served to its page at [PATH]. Issue #62.
 *
 * Only a file opened from a folder added in Gander has a folder to look in, since Gander holds a
 * grant on the whole of that folder. Open with from another app grants the one file, so there the
 * page is answered with nothing, as before.
 */
internal class PicturesBeside(private val resolver: ContentResolver, private val document: Uri) {

    /** Folder ids from the added folder down to the one holding the document. */
    private val trail: List<String>? by lazy {
        if (!DocumentsContract.isTreeUri(document)) return@lazy null
        runCatching { DocumentsContract.findDocumentPath(resolver, document)?.path }
            .getOrNull()?.dropLast(1)?.takeIf { it.isNotEmpty() }
    }

    private val listings = HashMap<String, List<ChildDoc>?>()

    /** The picture md.js asks for by the path the file gives, or null for anything else. */
    fun respond(src: String?): WebResourceResponse? {
        val picture = src?.let(::find) ?: return null
        return runCatching {
            val bytes = resolver.openInputStream(
                DocumentsContract.buildDocumentUriUsingTree(document, picture.docId)
            ) ?: return null
            // So the page can only ever use it as a picture, never as a script or a style
            WebResourceResponse(
                picture.mime, null, 200, "OK", mapOf("X-Content-Type-Options" to "nosniff"), bytes
            )
        }.getOrNull()
    }

    /** One lookup at a time, since the page asks for its pictures at once and they share the listings. */
    @Synchronized
    private fun find(src: String): ChildDoc? {
        val steps = pictureSteps(src) ?: return null
        val folders = trail ?: return null
        return walkBeside(folders, steps, ::listing)?.takeIf { it.mime.startsWith("image/") }
    }

    private fun listing(folder: String): List<ChildDoc>? {
        if (folder in listings) return listings[folder]
        return runCatching {
            resolver.query(
                DocumentsContract.buildChildDocumentsUriUsingTree(document, folder),
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null, null, null
            )?.use { c ->
                buildList {
                    while (c.moveToNext()) {
                        add(ChildDoc(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", 0, 0))
                    }
                }
            }
        }.getOrNull().also { listings[folder] = it }
    }

    companion object {
        /** Where md.js asks for a picture, with the path the file gives in `path`. */
        const val PATH = "/beside"
    }
}

/**
 * The names a picture's path steps through from the Markdown file's folder, with "." dropped and
 * ".." kept for the walk. Null for anything else: a URL, a path from the top of a website, which
 * a file on a phone has no top to match, or a path that names no file.
 */
internal fun pictureSteps(src: String): List<String>? {
    // As a browser reads it: a backslash is a slash, and what follows ? or # is not the path
    val path = src.trim().substringBefore('#').substringBefore('?').replace('\\', '/')
    if (path.isEmpty() || path.startsWith('/') || URL_SCHEME.containsMatchIn(path)) return null
    val steps = path.split('/').map(::percentDecoded).filter { it.isNotEmpty() && it != "." }
    if (steps.isEmpty() || steps.last() == ".." || steps.any { '/' in it }) return null
    return steps
}

private val URL_SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

/**
 * Percent-decoding as a browser does it, where a % without two hex digits after it stays a %.
 * Uri.decode turns that % into a stand-in character, so "zoom 200%.png" was never found.
 */
private fun percentDecoded(s: String): String {
    if ('%' !in s) return s
    val bytes = s.encodeToByteArray()
    val out = ByteArrayOutputStream(bytes.size)
    var i = 0
    while (i < bytes.size) {
        val high = if (bytes[i] == '%'.code.toByte() && i + 2 < bytes.size) Character.digit(bytes[i + 1].toInt(), 16) else -1
        val low = if (high >= 0) Character.digit(bytes[i + 2].toInt(), 16) else -1
        if (low >= 0) {
            out.write(high * 16 + low)
            i += 3
        } else {
            out.write(bytes[i].toInt())
            i++
        }
    }
    return out.toByteArray().decodeToString()
}

/**
 * What [steps] lead to from the last folder in [trail], or null. ".." goes up a folder, but never
 * above the first one, which is the folder that was added. A name is matched as written, else
 * ignoring case: Windows and macOS ignore it, so a file written there often gets it wrong.
 */
internal fun walkBeside(
    trail: List<String>,
    steps: List<String>,
    listing: (String) -> List<ChildDoc>?
): ChildDoc? {
    val at = trail.toMutableList()
    for ((i, step) in steps.withIndex()) {
        if (step == "..") {
            if (at.size < 2) return null
            at.removeAt(at.lastIndex)
            continue
        }
        val last = i == steps.lastIndex
        // A folder on the way, a file at the end
        val candidates = listing(at.last())?.filter { (it.mime == MIME_DIR) != last } ?: return null
        val found = candidates.firstOrNull { it.name == step }
            ?: candidates.firstOrNull { it.name.equals(step, ignoreCase = true) }
            ?: return null
        if (last) return found
        at.add(found.docId)
    }
    return null
}

package com.arjun.gander

import android.content.ContentResolver
import android.content.Context
import android.net.Uri
import android.util.AtomicFile
import java.io.File
import java.security.MessageDigest

/**
 * The page each PDF or book was last left open at, so that it opens there next time. Issues
 * #25 and #50.
 *
 * Kept against what the file holds rather than where it came from. One document reaches
 * the viewer under a different URI from the picker, from a granted folder and from a file
 * manager's "Open with", and an attachment handed over by a mail or chat app often arrives
 * on a URI that will not exist tomorrow. A key read from the bytes is the same down every
 * one of those routes, survives the file being renamed or moved, and tells anyone reading
 * this store nothing about what the file is called or where it lives.
 *
 * Two copies of one file therefore share a page, which is the right answer, and a file
 * saved again with changes in it starts from the top, which is a defensible one.
 *
 * Held in the no-backup folder, which is why this is a file of its own and not a
 * preferences file: Android decides where those go, and it is not there. That folder is
 * the one part of an app's storage the backup framework always leaves out, cloud backup and
 * phone-to-phone transfer alike, whatever the manifest asks for. The manifest alone would
 * only be half of that. allowBackup="false" closes the cloud backup, but from Android 12 a
 * transfer to a new phone can still carry everything else, preferences included. Where
 * somebody was in a book stays on the phone they were reading it on.
 *
 * One line per document: the key, the page, and when it was left there, the last only so
 * the oldest entry can be dropped once there are [MAX] of them. A book's line has two more
 * fields, its [Place].
 */
object Positions {

    private const val FILE_NAME = "positions"

    /** More documents than anybody has on the go at once, and a few kilobytes at most. */
    private const val MAX = 100

    /**
     * How much of the file the key is read from.
     *
     * Far enough past the header and the first few objects, which two documents out of the
     * same generator can share byte for byte, and small enough to cost nothing beside the
     * read the viewer is about to make of the same file. The length goes in too, so a
     * document that has grown is a different document.
     */
    private const val HEAD_BYTES = 64 * 1024

    /**
     * Where in a book the top of the screen was: the chapter by its number in the reading
     * order, from nought, and how many of its letters and pictures were above the screen.
     *
     * A book has no pages. What its viewer counts as one is a stretch of its files that comes
     * to more than a screenful, and a book reopened at the start of that stretch lands above
     * the line it was left on. This is that line, and it is the same line at any size of type
     * and whichever way the phone is held, which a distance down the chapter would not be.
     */
    data class Place(val chapter: Int, val past: Int)

    private class Entry(val key: String, val page: Int, val time: Long, val place: Place? = null)

    /**
     * What the document at [uri] is remembered by, or null when it cannot be read.
     * [length] is its size as the provider reported it, or -1.
     */
    fun keyFor(resolver: ContentResolver, uri: Uri, length: Long): String? = runCatching {
        val head = ByteArray(HEAD_BYTES)
        var filled = 0
        val input = resolver.openInputStream(uri) ?: return null
        input.use {
            while (filled < head.size) {
                val n = it.read(head, filled, head.size - filled)
                if (n < 0) break
                filled += n
            }
        }
        if (filled == 0) return null
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(head, 0, filled)
        digest.update(length.toString().toByteArray())
        digest.digest().take(16).joinToString("") { "%02x".format(it) }
    }.getOrNull()

    /** The page to open the document under [key] at, or 0 for the top. */
    fun page(context: Context, key: String): Int =
        load(context).firstOrNull { it.key == key }?.page ?: 0

    /** The place to open the book under [key] at, or null for its start. */
    fun place(context: Context, key: String): Place? =
        load(context).firstOrNull { it.key == key }?.place

    /**
     * Remember that the document under [key] was left at [page] of [total].
     *
     * The first page is forgotten rather than stored, since it is where a document opens
     * anyway. So is the last, and that one is a decision rather than a saving: a document
     * read to the end that reopens on its final page looks broken, and somebody who has
     * finished it is more likely to be starting again than looking for the back cover.
     *
     * A book gives its [place] as well, and its start is the top of its first chapter rather
     * than the whole of its first page, so a book left a screenful in reopens there.
     *
     * The whole file is written again each time, through AtomicFile, so a process that dies
     * part-way through a write leaves the last good copy rather than half of a new one.
     */
    fun save(context: Context, key: String, page: Int, total: Int, place: Place? = null) {
        val all = load(context)
        val others = all.filter { it.key != key }
        val keep = if (place == null) page in 2 until total
        else page < total && (place.chapter > 0 || place.past > 0)
        // Nothing stored for this document and nothing to store now: leave the file alone.
        if (!keep && others.size == all.size) return
        val entries =
            if (keep) others + Entry(key, page, System.currentTimeMillis(), place) else others
        val text = entries.sortedByDescending { it.time }.take(MAX).joinToString("") {
            val where = it.place?.let { at -> " ${at.chapter} ${at.past}" } ?: ""
            "${it.key} ${it.page} ${it.time}$where\n"
        }
        val file = file(context)
        val out = runCatching { file.startWrite() }.getOrNull() ?: return
        runCatching {
            out.write(text.toByteArray())
            file.finishWrite(out)
        }.onFailure { file.failWrite(out) }
    }

    private fun file(context: Context) = AtomicFile(File(context.noBackupFilesDir, FILE_NAME))

    private fun load(context: Context): List<Entry> = runCatching {
        String(file(context).readFully()).lines().mapNotNull { line ->
            val parts = line.split(' ')
            val page = parts.getOrNull(1)?.toIntOrNull()
            val time = parts.getOrNull(2)?.toLongOrNull()
            val chapter = parts.getOrNull(3)?.toIntOrNull()
            val past = parts.getOrNull(4)?.toIntOrNull()
            when {
                page == null || time == null -> null
                parts.size == 3 -> Entry(parts[0], page, time)
                parts.size == 5 && chapter != null && past != null && chapter >= 0 && past >= 0 ->
                    Entry(parts[0], page, time, Place(chapter, past))
                else -> null
            }
        }
    }.getOrDefault(emptyList())
}

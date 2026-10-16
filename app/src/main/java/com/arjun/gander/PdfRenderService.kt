package com.arjun.gander

import android.app.Service
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.pdf.PdfRenderer
import android.graphics.pdf.RenderParams
import android.os.Build
import android.os.IBinder
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.ErrnoException
import android.system.Os
import android.util.Log
import androidx.core.graphics.createBitmap
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Draws PDF pages with Android's own PdfRenderer, for a phone whose WebView is too old for
 * pdf.js and can't be updated, such as a Huawei phone without Google services.
 *
 * PdfRenderer is PDFium, native code reading a file someone else made, so it runs here in an
 * isolated process (see the manifest), which has no storage, network or Gander data of its
 * own. All it can reach is what Gander hands it: one document's descriptor in, one pipe per
 * answer out. Gander treats whatever comes back as untrusted and counts the bytes itself.
 */
open class PdfRenderService : Service() {

    // PdfRenderer allows one open page at a time. Calls come one by one but on any binder
    // thread, and onDestroy on the main one.
    private val lock = Any()
    private var renderer: PdfRenderer? = null

    private val binder = object : IPdfRenderer.Stub() {
        override fun open(document: ParcelFileDescriptor?, answer: ParcelFileDescriptor?) {
            if (answer == null) {
                document?.close()
                return
            }
            reply(answer) { littleEndian(4).putInt(openDocument(document)) }
        }

        override fun pageSize(page: Int, answer: ParcelFileDescriptor?) {
            if (answer != null) reply(answer) { littleEndian(8).putLong(sizeOf(page)) }
        }

        override fun render(page: Int, width: Int, height: Int, flags: Int, answer: ParcelFileDescriptor?) {
            if (answer != null) draw(page, width, height, flags, answer)
        }
    }

    /**
     * Writes the bytes [answer] gives to [pipe] and closes it. If they can't be had, the pipe
     * still closes, so Gander's read ends at once rather than at its time limit.
     */
    private fun reply(pipe: ParcelFileDescriptor, answer: () -> ByteBuffer) {
        ParcelFileDescriptor.AutoCloseOutputStream(pipe).use { out ->
            try {
                out.write(answer().array())
            } catch (e: IOException) {
                // Gander closed its end, having stopped waiting: nothing to tell it
                Log.i(TAG, "answer not delivered: ${e.message}")
            } catch (e: RuntimeException) {
                // PdfRenderer's word for a page PDFium can't load, among others
                Log.w(TAG, "no answer", e)
            }
        }
    }

    private fun littleEndian(size: Int): ByteBuffer = ByteBuffer.allocate(size).order(ByteOrder.LITTLE_ENDIAN)

    override fun onBind(intent: Intent): IBinder = binder

    /**
     * Gander unbinds when it is done with the document. Ending the process then, instead of
     * leaving it for Android to reclaim, means nothing PDFium read stays in memory after. Not
     * under the lock, which a page stuck in PDFium holds for ever.
     */
    override fun onUnbind(intent: Intent): Boolean {
        Process.killProcess(Process.myPid())
        return false
    }

    override fun onDestroy() {
        synchronized(lock) { closeDocument() }
        super.onDestroy()
    }

    private fun openDocument(document: ParcelFileDescriptor?): Int {
        document ?: return OPEN_UNREADABLE
        synchronized(lock) {
            closeDocument()
            // Reading the start first tells a file this process may not read apart from one
            // that isn't a PDF, which PdfRenderer reports the same way.
            val start = ByteArray(HEADER_WINDOW)
            val read = try {
                Os.pread(document.fileDescriptor, start, 0, start.size, 0)
            } catch (e: ErrnoException) {
                Log.w(TAG, "can't read the document: ${e.message}")
                document.close()
                return OPEN_UNREADABLE
            }
            if (!startsLikePdf(start, read)) {
                document.close()
                return OPEN_NOT_PDF
            }
            return try {
                opening()
                PdfRenderer(document).also { renderer = it }.pageCount
            } catch (e: SecurityException) {
                document.close()
                OPEN_PASSWORD
            } catch (e: IOException) {
                Log.w(TAG, "PdfRenderer refused the document: ${e.message}")
                document.close()
                OPEN_DAMAGED
            } catch (e: IllegalArgumentException) {
                // PdfRenderer's word for a descriptor it can't seek or stat
                Log.w(TAG, "PdfRenderer can't use the descriptor: ${e.message}")
                document.close()
                OPEN_UNREADABLE
            }
        }
    }

    private fun sizeOf(page: Int): Long = synchronized(lock) {
        val r = renderer ?: return -1
        if (page !in 0 until r.pageCount) return -1
        r.openPage(page).use { (it.width.toLong() shl 32) or (it.height.toLong() and 0xffffffffL) }
    }

    private fun draw(page: Int, width: Int, height: Int, flags: Int, sink: ParcelFileDescriptor) {
        try {
            ParcelFileDescriptor.AutoCloseOutputStream(sink).use { out ->
                if (width !in 1..MAX_SIDE || height !in 1..MAX_SIDE ||
                    width.toLong() * height > MAX_PIXELS
                ) return
                val bitmap = synchronized(lock) {
                    val r = renderer ?: return
                    if (page !in 0 until r.pageCount) return
                    r.openPage(page).use { p ->
                        drawing(page)
                        createBitmap(width, height, Bitmap.Config.ARGB_8888).also {
                            it.eraseColor(Color.WHITE)
                            if (flags and RENDER_NOTES != 0 &&
                                Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM
                            ) {
                                p.render(it, null, null, withNotes())
                            } else {
                                p.render(it, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                            }
                        }
                    }
                }
                try {
                    // A strip at a time, so the copy beside the bitmap stays near a megabyte.
                    // getPixels gives ARGB ints, which little-endian are bytes in BMP's order.
                    val rows = (STRIP_BYTES / (4 * width)).coerceAtLeast(1)
                    val pixels = IntArray(width * rows)
                    val bytes = ByteBuffer.allocate(pixels.size * 4).order(ByteOrder.LITTLE_ENDIAN)
                    var y = 0
                    while (y < height) {
                        val n = minOf(rows, height - y)
                        bitmap.getPixels(pixels, 0, width, 0, y, width, n)
                        bytes.clear()
                        bytes.asIntBuffer().put(pixels, 0, width * n)
                        out.write(bytes.array(), 0, 4 * width * n)
                        y += n
                    }
                } finally {
                    bitmap.recycle()
                }
            }
        } catch (e: IOException) {
            // Gander closed its end, having stopped waiting or moved on: nothing to tell it
            Log.i(TAG, "page $page not delivered: ${e.message}")
        } catch (e: RuntimeException) {
            Log.w(TAG, "page $page failed", e)
        } catch (e: OutOfMemoryError) {
            Log.w(TAG, "page $page out of memory at $width x $height")
        }
    }

    /**
     * Display rendering with two kinds of note drawn too, highlights and comments, which Android
     * draws only from 15.
     */
    @androidx.annotation.RequiresApi(Build.VERSION_CODES.VANILLA_ICE_CREAM)
    private fun withNotes(): RenderParams =
        RenderParams.Builder(RenderParams.RENDER_MODE_FOR_DISPLAY)
            .setRenderFlags(
                RenderParams.FLAG_RENDER_HIGHLIGHT_ANNOTATIONS or RenderParams.FLAG_RENDER_TEXT_ANNOTATIONS
            )
            .build()

    private fun closeDocument() {
        runCatching { renderer?.close() }
        renderer = null
    }

    /** Where the debug build's FaultyPdfRenderService crashes or hangs, as PDFium might. */
    protected open fun opening() {}

    protected open fun drawing(page: Int) {}

    companion object {
        private const val TAG = "PdfRenderService"

        const val OPEN_PASSWORD = -1
        const val OPEN_UNREADABLE = -2
        const val OPEN_NOT_PDF = -3
        const val OPEN_DAMAGED = -4

        /** For render: draw the page's highlights and comments as well, where Android can. */
        const val RENDER_NOTES = 1

        /** pdf.mjs's own caps on a canvas, kept here too so asking for more gets nothing. */
        const val MAX_SIDE = 8192
        const val MAX_PIXELS = 8L * 1024 * 1024

        private const val HEADER_WINDOW = 1024
        private const val STRIP_BYTES = 1 shl 20
    }
}

/**
 * The same renderer under a second name, for thumbnails. Android starts each isolated service in
 * a process of its own, so the files of a browsed folder are never read in the process that
 * holds the document open in the viewer.
 */
class PdfThumbService : PdfRenderService()

/**
 * The same renderer under three more names, so that each viewer showing a PDF can have one of its
 * own: every binding of one isolated service shares its process, and the renderer holds one
 * document at a time. See [RendererSlots].
 */
class PdfRenderService1 : PdfRenderService()

class PdfRenderService2 : PdfRenderService()

class PdfRenderService3 : PdfRenderService()

/** Whether "%PDF" appears in [length] bytes of [start], which is where PDF readers look for it. */
internal fun startsLikePdf(start: ByteArray, length: Int): Boolean {
    val end = minOf(length, start.size) - 4
    for (i in 0..end) {
        if (start[i] == '%'.code.toByte() && start[i + 1] == 'P'.code.toByte() &&
            start[i + 2] == 'D'.code.toByte() && start[i + 3] == 'F'.code.toByte()
        ) return true
    }
    return false
}

package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger

/**
 * PDF thumbnails, drawn by PdfThumbService rather than in Gander: PDFium is native code reading a
 * file someone else made, and a browsed folder can hold any file. The renderer is bound when a
 * picture is needed and let go [IDLE_MS] after the last one, which ends its process.
 *
 * Pictures are drawn one at a time, on a thread of their own: the renderer holds one document at
 * a time, and Thumbs' pool is left to photos and videos.
 */
internal class PdfThumbs(private val context: Context, private val width: Int) {

    private val lane = Executors.newSingleThreadExecutor()
    private val main = Handler(Looper.getMainLooper())

    // Pictures asked for and not yet done
    private val waiting = AtomicInteger()

    // The lane's, read on the main thread only by a test that is done with it
    @Volatile
    private var renderer: PdfRenderClient? = null

    // The lane's alone
    private val refused = HashSet<String>()
    private val retried = HashSet<String>()

    /** Runs [job] once the pictures asked for before it are done. It may call [draw]. */
    fun queue(job: Runnable) {
        // The renderer is let go only after a spell with nothing waiting
        main.removeCallbacks(idle)
        waiting.incrementAndGet()
        lane.execute {
            try {
                job.run()
            } finally {
                if (waiting.decrementAndGet() == 0) {
                    main.removeCallbacks(idle)
                    main.postDelayed(idle, IDLE_MS)
                }
            }
        }
    }

    /**
     * The first page of [uri], [width] across, or null. A file the renderer refused, hung on or
     * died on twice isn't handed over again while Gander runs: the answer would be the same, and
     * a lost renderer has to be replaced each time.
     */
    @WorkerThread
    fun draw(uri: Uri, key: String): Bitmap? {
        if (key in refused) return null
        val document = context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        val renderer = renderer?.takeUnless { it.gone } ?: start()
        val picture = firstPage(renderer, document)
        if (picture != null) return picture
        // A renderer can die during one file of something else, such as closing the document
        // before it or Android reclaiming it, so the file gets one more try on a new renderer. A
        // hang gets none, since the files behind it would wait as long again.
        if (renderer.gone && !renderer.hung && retried.add(key)) return draw(uri, key)
        refused += key
        return null
    }

    private fun firstPage(renderer: PdfRenderClient, document: ParcelFileDescriptor): Bitmap? {
        if (renderer.open(document) < 1) return null
        val (pageWidth, pageHeight) = renderer.pageSize(0) ?: return null
        // Shaped as the page is, up to twice as tall as it is wide
        val height = (width.toFloat() * pageHeight / pageWidth).toInt().coerceIn(1, width * 2)
        val pixels = renderer.render(0, width, height) ?: return null
        return pageBitmap(pixels, width, height)
    }

    /** A renderer, the first or one in place of one that is gone. */
    private fun start(): PdfRenderClient {
        val old = renderer
        // Nothing to do when one goes: the next picture finds it gone and starts another
        val fresh = PdfRenderClient(context, Intent(context, PdfThumbService::class.java), HUNG_MS) {}
        renderer = fresh
        main.post {
            // Unbound first, or Android would hand the new binding the old renderer, hung or
            // waiting to restart
            old?.close()
            fresh.bind()
        }
        return fresh
    }

    // Asks the lane, where a picture asked for since would be waiting its turn
    private val idle = Runnable {
        if (!lane.isShutdown) lane.execute { if (waiting.get() == 0) letGo() }
    }

    private fun letGo() {
        val old = renderer ?: return
        renderer = null
        main.post { old.close() }
    }

    /** Ends the lane, its renderer and any call waiting on it, for a test whose Application it was. */
    @MainThread
    fun shutdown() {
        main.removeCallbacks(idle)
        lane.shutdownNow()
        renderer?.close()
    }

    companion object {
        /** How long the renderer is kept once no picture is waiting for it. */
        const val IDLE_MS = 10_000L

        /**
         * How long the renderer may go without answering before it is given up as hung: half
         * what the viewer allows a page, since a thumbnail is one small picture and every file
         * behind a hung one waits as long.
         */
        const val HUNG_MS = 10_000L
    }
}

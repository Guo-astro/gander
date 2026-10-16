package com.arjun.gander

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.webkit.WebResourceResponse
import androidx.annotation.MainThread
import androidx.annotation.VisibleForTesting
import androidx.annotation.WorkerThread
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.util.concurrent.Callable
import java.util.concurrent.CancellationException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException
import org.json.JSONArray
import org.json.JSONObject

/**
 * Gander's half of the simple view, [FileKind.PDF_SIMPLE]: a PDF's pages drawn by an isolated
 * renderer this viewer has to itself, and served to pdfsimple.html under /render/.
 *
 * - /render/document: how many pages there are and page 1's size, or why the file won't open
 * - /render/sizes?from=&to=: the sizes of a few pages, one based, for a jump to one of them
 * - /render/page?n=&w=&night=: page n drawn w pixels across, as a PNG, turned over for night 1
 *
 * Each is answered on the WebView's thread once the renderer has done its part. The renderer runs
 * one call at a time, in the order they arrive, so they are all sent from one thread of Gander's,
 * the lane, which reads each answer to the end before the next: a WebView thread waits only for
 * what it asked, never for another to be read.
 */
internal class SimplePdf(
    context: Context,
    private val document: Uri,
    /** Whether the viewer is in front, asked when the renderer goes while idle. */
    private val inFront: () -> Boolean,
    /** The document asked for a password, which Android's printing can't give it. */
    private val locked: () -> Unit,
    /** The renderer crashed or hung, or went too often to be opened again: the viewer's card. */
    private val gone: (crashed: Boolean) -> Unit,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val lane = Executors.newSingleThreadExecutor()

    /** The most pixels a page is drawn with here. See [pagePixelCap]. */
    private val cap = pagePixelCap(
        context.getSystemService(ActivityManager::class.java)?.memoryClass ?: 0
    )

    @Volatile
    private var client: PdfRenderClient? = null

    // Set on the main thread, read on the lane: the renderer went, and the next page asked for
    // opens the document again in a new one
    @Volatile
    private var stale = false

    // Nothing more is asked of a renderer: closed, or given up on
    @Volatile
    private var over = false

    // The lane's: the renderer the document was last handed to, what it answered, and the count
    private var openIn: PdfRenderClient? = null
    private var opened = PdfRenderClient.GONE
    private var pages = 0

    // Main thread only: the renderers that went while the viewer was in front
    private var deaths = 0

    /** Binds the renderer and hands it the document. */
    @MainThread
    fun start() {
        bindNew()
        submit { ready() }
    }

    /** Lets the renderer go, which ends its process, and every request waiting on it. */
    @MainThread
    fun close() {
        if (over) return
        over = true
        client?.close()
        client = null
        RendererSlots.release(this)
        lane.shutdownNow()
    }

    /**
     * Another viewer has taken this one's renderer, there being none free. The document is
     * opened again in another the next time a page is asked for.
     */
    @MainThread
    fun evicted() {
        client?.close()
        client = null
        stale = true
    }

    /** The answer to a request under /render/, once there is one. On the WebView's thread. */
    @WorkerThread
    fun respond(url: Uri): WebResourceResponse {
        val job = try {
            lane.submit(Callable { answer(url) })
        } catch (e: RejectedExecutionException) {
            return nothing()
        }
        return try {
            job.get()
        } catch (e: ExecutionException) {
            nothing()
        } catch (e: CancellationException) {
            nothing()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            nothing()
        }
    }

    private fun submit(job: () -> Unit) {
        try {
            // Not execute: a job that threw would end the app with it, where this keeps the
            // failure in a Future nobody reads, and the page asks again for what it needs
            lane.submit(job)
        } catch (e: RejectedExecutionException) {
            // Closed already
        }
    }

    private fun answer(url: Uri): WebResourceResponse = when (url.path) {
        "/render/document" -> json(describe())
        "/render/sizes" -> sizes(url)?.let(::json) ?: nothing()
        "/render/page" -> page(url)
        else -> nothing()
    }

    /** A renderer bound in a slot of this viewer's, in place of one gone. */
    @MainThread
    private fun bindNew(): PdfRenderClient? {
        if (over) return null
        val slot = RendererSlots.take(this)
        lateinit var fresh: PdfRenderClient
        fresh = PdfRenderClient(context, Intent(context, RendererSlots.services[slot])) { crashed ->
            lost(fresh, crashed)
        }
        client = fresh
        fresh.bind()
        return fresh
    }

    /**
     * [which] has gone. Crashed or hung while working, it is the file, and the viewer says so. Gone
     * while idle is Android reclaiming memory, mostly, and the document opens again in a new one
     * when a page is next asked for. In front, that is more likely the file too, so only two are
     * let pass there.
     */
    @MainThread
    private fun lost(which: PdfRenderClient, crashed: Boolean) {
        if (over || client !== which) return
        if (crashed || (inFront() && ++deaths > MAX_DEATHS_IN_FRONT)) {
            over = true
            gone(true)
            return
        }
        stale = true
    }

    /** The renderer, with the document open in it, opening it again in a new one if it went. */
    @WorkerThread
    private fun ready(): PdfRenderClient? {
        if (over) return null
        val renderer = client?.takeUnless { it.gone || stale } ?: onMain {
            client?.close()
            stale = false
            bindNew()
        } ?: return null
        if (openIn !== renderer) {
            openIn = renderer
            opened = runCatching { context.contentResolver.openFileDescriptor(document, "r") }
                .getOrNull()?.let { renderer.open(it) } ?: PdfRenderService.OPEN_UNREADABLE
            pages = opened.coerceAtLeast(0)
            if (opened == PdfRenderService.OPEN_PASSWORD) main.post { if (!over) locked() }
        }
        return renderer.takeIf { pages > 0 && !it.gone }
    }

    /** What the page is told of the document as it opens. */
    private fun describe(): JSONObject {
        val renderer = ready()
        val said = JSONObject()
        if (renderer == null) {
            return said.put(
                "error",
                when (opened) {
                    PdfRenderService.OPEN_PASSWORD -> "password"
                    PdfRenderService.OPEN_NOT_PDF -> "not-pdf"
                    PdfRenderService.OPEN_DAMAGED -> "damaged"
                    PdfRenderService.OPEN_UNREADABLE -> "unreadable"
                    else -> "gone"
                }
            )
        }
        said.put("pages", pages)
        renderer.pageSize(0)?.let { said.put("width", it[0]).put("height", it[1]) }
        return said
    }

    /** The sizes of pages from= to to=, one based and at most [MAX_SIZES], each null if unknown. */
    private fun sizes(url: Uri): JSONObject? {
        val from = number(url, "from") ?: return null
        val to = number(url, "to") ?: return null
        val renderer = ready() ?: return null
        if (from < 1 || to < from || to > pages || to - from >= MAX_SIZES) return null
        val all = JSONArray()
        for (n in from..to) {
            val size = renderer.pageSize(n - 1)
            all.put(size?.let { JSONArray().put(it[0]).put(it[1]) } ?: JSONObject.NULL)
        }
        return JSONObject().put("sizes", all)
    }

    /**
     * Page n=, w= pixels across or as near as the caps allow, as a PNG. The renderer refuses a page
     * the document hasn't.
     */
    private fun page(url: Uri): WebResourceResponse {
        val n = number(url, "n") ?: return nothing()
        val width = number(url, "w") ?: return nothing()
        val night = url.getQueryParameter("night") == "1"
        val renderer = ready() ?: return nothing()
        RendererSlots.touch(this)
        val png = drawn(renderer, n - 1, width, night) ?: return nothing()
        return WebResourceResponse("image/png", null, 200, "OK", PRIVATE, png)
    }

    private fun drawn(
        renderer: PdfRenderClient,
        page: Int,
        width: Int,
        night: Boolean,
    ): InputStream? {
        val (pageWidth, pageHeight) = renderer.pageSize(page) ?: return null
        val (w, h) = drawSize(width, pageWidth, pageHeight, cap) ?: return null
        val spool = Spool()
        return renderer.renderInto(page, w, h, PdfRenderService.RENDER_NOTES) { rows ->
            if (PagePng(w, h, night).write(rows, spool) { over }) spool.input() else null
        }
    }

    /** [step] on the main thread, waited for. Null if the lane was stopped meanwhile. */
    private fun <T> onMain(step: () -> T): T? {
        var made: T? = null
        val done = CountDownLatch(1)
        main.post {
            try {
                made = step()
            } finally {
                done.countDown()
            }
        }
        try {
            done.await()
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        }
        return made
    }

    /** A PNG as it is written, read back without another copy of it. */
    private class Spool : ByteArrayOutputStream(1 shl 16) {
        fun input(): InputStream = ByteArrayInputStream(buf, 0, count)
    }

    private fun number(url: Uri, name: String): Int? =
        url.getQueryParameter(name)?.takeIf { it.length in 1..9 && it.all(Char::isDigit) }?.toInt()

    private fun json(said: JSONObject) = WebResourceResponse(
        "application/json", "utf-8", 200, "OK", PRIVATE,
        ByteArrayInputStream(said.toString().toByteArray(Charsets.UTF_8))
    )

    private fun nothing() = WebResourceResponse(
        "text/plain", "utf-8", 404, "Not Found", PRIVATE, ByteArrayInputStream(ByteArray(0))
    )

    companion object {
        /** How many renderers may go while the viewer is in front before it stops opening more. */
        const val MAX_DEATHS_IN_FRONT = 2

        /** The most page sizes one request asks for. */
        const val MAX_SIZES = 16

        /**
         * Never kept: a page is drawn again whenever it is shown, and night mode asks for every
         * page again. nosniff, so nothing served here can be taken for a script or a page.
         */
        private val PRIVATE = mapOf(
            "Cache-Control" to "no-store",
            "X-Content-Type-Options" to "nosniff",
        )
    }
}

/**
 * Which of the four renderers each simple view has, so that no two viewers share a process: every
 * binding of one isolated service is served by the same process, and a renderer holds one document
 * at a time. More viewers than renderers means several PDFs opened from other apps, each in a card
 * of its own, and the one asked for a page longest ago gives its renderer up.
 */
internal object RendererSlots {
    val services: List<Class<out PdfRenderService>> = listOf(
        PdfRenderService::class.java, PdfRenderService1::class.java,
        PdfRenderService2::class.java, PdfRenderService3::class.java,
    )

    private val holders = arrayOfNulls<SimplePdf>(services.size)
    private val used = LongArray(services.size)

    /** A renderer for [holder]: its own, a free one, or the one used longest ago. */
    @MainThread
    fun take(holder: SimplePdf): Int {
        val evicted: SimplePdf?
        val slot: Int
        synchronized(this) {
            val mine = holders.indexOfFirst { it === holder }
            slot = if (mine >= 0) mine
            else holders.indexOfFirst { it == null }.takeIf { it >= 0 }
                ?: used.indices.minByOrNull { used[it] }!!
            evicted = holders[slot]?.takeIf { it !== holder }
            holders[slot] = holder
            used[slot] = SystemClock.uptimeMillis()
        }
        evicted?.evicted()
        return slot
    }

    @MainThread
    fun release(holder: SimplePdf) = synchronized(this) {
        val slot = holders.indexOfFirst { it === holder }
        if (slot >= 0) holders[slot] = null
    }

    /** [holder] is drawing a page, so its renderer is the last to be taken. */
    fun touch(holder: SimplePdf) = synchronized(this) {
        val slot = holders.indexOfFirst { it === holder }
        if (slot >= 0) used[slot] = SystemClock.uptimeMillis()
    }

    @VisibleForTesting
    fun resetForTests() = synchronized(this) {
        holders.fill(null)
        used.fill(0)
    }
}

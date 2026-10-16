package com.arjun.gander

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.os.ParcelFileDescriptor
import android.os.RemoteException
import android.os.SystemClock
import androidx.annotation.MainThread
import androidx.annotation.WorkerThread
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/**
 * Gander's side of [PdfRenderService]: binds the isolated renderer for as long as a screen needs
 * it, hands it a document and fetches its pages. Every call blocks, so none is made on the main
 * thread.
 *
 * The renderer may crash on a hostile file, hang in PDFium or be reclaimed by Android. Each ends
 * in [onGone], once and on the main thread, and every call after it fails at once. [onGone] is
 * told whether the renderer was working at the time, which a reclaimed one is not. It is given up
 * as hung once calls have waited [hungMs] with no answer.
 */
internal class PdfRenderClient(
    private val context: Context,
    private val service: Intent,
    private val hungMs: Long = HUNG_MS,
    private val onGone: (crashed: Boolean) -> Unit,
) {
    private val main = Handler(Looper.getMainLooper())

    // Down once the renderer is bound or can't be, so a call made before then waits for it
    private val settled = CountDownLatch(1)

    @Volatile
    private var binder: IBinder? = null

    // Gone or closed: nothing more is asked of the renderer
    @Volatile
    private var over = false

    // The open document's page count, which bounds every page asked for
    @Volatile
    private var pages = 0

    // Main thread only
    private var bound = false
    private var closed = false

    // The hang watch: the calls waiting on the renderer, and the answers it has given
    private val waiting = AtomicInteger()
    private val answers = AtomicLong()
    private val watching = AtomicBoolean()
    private var lastTick = 0L
    private var lastAnswers = 0L
    private var stalled = 0L

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, remote: IBinder?) {
            if (over) return
            if (remote == null) return lost(crashed = true)
            binder = remote
            answers.incrementAndGet()
            settled.countDown()
        }

        // Its process died. Android would start another, but with no document open in it.
        override fun onServiceDisconnected(name: ComponentName?) = lost()

        override fun onBindingDied(name: ComponentName?) = lost()

        override fun onNullBinding(name: ComponentName?) = lost(crashed = true)
    }

    /** Whether the renderer is gone or the client closed, so that every call fails at once. */
    val gone: Boolean get() = over

    /** Whether the renderer went by being given up as hung, rather than by dying. */
    @Volatile
    var hung = false
        private set

    /** Starts the renderer's process. */
    @MainThread
    fun bind() {
        if (bound || over) return
        bound = true
        val started = runCatching {
            context.bindService(service, connection, Context.BIND_AUTO_CREATE)
        }
        if (started.getOrDefault(false) != true) lost(crashed = true)
    }

    /** Unbinds, which ends the renderer's process. A call still waiting on it fails. */
    @MainThread
    fun close() {
        closed = true
        end()
        main.removeCallbacks(watch)
        unbind()
    }

    /**
     * Hands [document] to the renderer and closes Gander's copy. The page count, one of
     * PdfRenderService's OPEN_ codes, or [GONE].
     */
    @WorkerThread
    fun open(document: ParcelFileDescriptor): Int {
        pages = 0
        val answer = document.use { pfd ->
            ask { remote ->
                transact(remote, IPdfRenderer.Stub.TRANSACTION_open, { data ->
                    data.writeInt(1)
                    pfd.writeToParcel(data, 0)
                    // The parcel holds a copy of its own until it is sent
                    pfd.close()
                }) { it.readInt() }
            }
        } ?: return if (over) GONE else PdfRenderService.OPEN_DAMAGED
        return when (answer) {
            in 1..MAX_PAGES -> answer.also { pages = it }
            PdfRenderService.OPEN_PASSWORD, PdfRenderService.OPEN_UNREADABLE,
            PdfRenderService.OPEN_NOT_PDF, PdfRenderService.OPEN_DAMAGED -> answer
            // No pages, or a number no renderer gives
            else -> PdfRenderService.OPEN_DAMAGED
        }
    }

    /** The page's size in points, width then height, or null. */
    @WorkerThread
    fun pageSize(page: Int): IntArray? {
        if (page !in 0 until pages) return null
        val size = ask { remote ->
            transact(remote, IPdfRenderer.Stub.TRANSACTION_pageSize, { it.writeInt(page) }) {
                it.readLong()
            }
        } ?: return null
        val width = (size ushr 32).toInt()
        val height = size.toInt()
        return if (width > 0 && height > 0) intArrayOf(width, height) else null
    }

    /**
     * Page [page] drawn at [width] x [height] in rows of blue, green, red and alpha bytes, top row
     * first: exactly the bytes that takes, or null.
     */
    @WorkerThread
    fun render(page: Int, width: Int, height: Int): ByteArray? {
        if (page !in 0 until pages || width !in 1..PdfRenderService.MAX_SIDE ||
            height !in 1..PdfRenderService.MAX_SIDE ||
            width.toLong() * height > PdfRenderService.MAX_PIXELS
        ) return null
        val (source, sink) = try {
            ParcelFileDescriptor.createPipe()
        } catch (e: IOException) {
            return null
        }
        ParcelFileDescriptor.AutoCloseInputStream(source).use { input ->
            sink.use {
                return ask { remote ->
                    send(remote, IPdfRenderer.Stub.TRANSACTION_render) { data ->
                        data.writeInt(page)
                        data.writeInt(width)
                        data.writeInt(height)
                        data.writeInt(1)
                        sink.writeToParcel(data, 0)
                        // Gander's copy of the write end has to go, or the pipe could never end
                        sink.close()
                    }
                    val bytes = try {
                        readPage(input, 4 * width * height)
                    } catch (e: IOException) {
                        null
                    }
                    // A pipe that ended early from a renderer no longer there is a crash partway
                    if (bytes == null && !remote.pingBinder()) lost()
                    bytes
                }
            }
        }
    }

    /**
     * Runs [call] once the renderer is bound, counted by the hang watch while it runs. Null when
     * there is no renderer, it died during the call or it gave no answer.
     */
    private fun <T : Any> ask(call: (IBinder) -> T?): T? {
        check(Looper.myLooper() != Looper.getMainLooper()) {
            "the renderer is asked off the main thread"
        }
        if (over) return null
        waiting.incrementAndGet()
        if (watching.compareAndSet(false, true)) main.post(watch)
        try {
            settled.await()
            val remote = binder ?: return null
            return call(remote)
        } catch (e: RemoteException) {
            // A DeadObjectException mostly, and any other failure of the binder is as final
            lost()
            return null
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            answers.incrementAndGet()
            waiting.decrementAndGet()
        }
    }

    /**
     * A call and its answer, by hand rather than through the AIDL proxy: reading a reply that
     * carries an exception or a header builds whatever objects the renderer names, in Gander's
     * process, so anything but a plain answer is refused unread.
     */
    private fun <T> transact(
        remote: IBinder,
        code: Int,
        write: (Parcel) -> Unit,
        read: (Parcel) -> T,
    ): T? {
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IPdfRenderer.DESCRIPTOR)
            write(data)
            if (!remote.transact(code, data, reply, 0)) return null
            if (reply.readInt() != 0) return null
            return read(reply)
        } finally {
            reply.recycle()
            data.recycle()
        }
    }

    /** A oneway call, which has no answer to read. */
    private fun send(remote: IBinder, code: Int, write: (Parcel) -> Unit) {
        val data = Parcel.obtain()
        try {
            data.writeInterfaceToken(IPdfRenderer.DESCRIPTOR)
            write(data)
            remote.transact(code, data, null, IBinder.FLAG_ONEWAY)
        } finally {
            data.recycle()
        }
    }

    /**
     * Gives the renderer up as hung once calls have waited [hungMs] with no answer. Only time
     * Gander spends running counts, since the two are frozen together in the background and
     * thaw in no set order.
     */
    private val watch = object : Runnable {
        override fun run() {
            if (over) {
                watching.set(false)
                return
            }
            if (waiting.get() == 0) {
                watching.set(false)
                lastTick = 0L
                // A call that began between the check and the stop saw the watch still running
                if (waiting.get() == 0 || !watching.compareAndSet(false, true)) return
            }
            val now = SystemClock.uptimeMillis()
            val answered = answers.get()
            stalled = if (lastTick == 0L || answered != lastAnswers) 0L
            else stalled + minOf(now - lastTick, 2 * TICK_MS)
            lastTick = now
            lastAnswers = answered
            if (stalled < hungMs) {
                main.postDelayed(this, TICK_MS)
                return
            }
            hung = true
            lost(crashed = true)
        }
    }

    /** Ends every call, once. True for the caller that ended them. */
    private fun end(): Boolean {
        synchronized(this) {
            if (over) return false
            over = true
        }
        binder = null
        settled.countDown()
        return true
    }

    /**
     * The renderer is gone for good. [crashed] is whether it went while working or was given up
     * as hung, rather than reclaimed while it sat idle.
     */
    private fun lost(crashed: Boolean = waiting.get() > 0) {
        if (!end()) return
        main.post {
            // So that Android doesn't start another renderer in its place
            unbind()
            if (!closed) onGone(crashed)
        }
    }

    @MainThread
    private fun unbind() {
        if (!bound) return
        bound = false
        runCatching { context.unbindService(connection) }
    }

    companion object {
        /** What [open] gives once the renderer is gone, which [onGone] reports. */
        const val GONE = Int.MIN_VALUE

        /**
         * Far more pages than any real file has, so that a renderer lying about the count can't
         * make Gander set much aside for them.
         */
        const val MAX_PAGES = 1 shl 20

        /**
         * How long calls may wait on the renderer with no answer before it is taken to have hung.
         * The slowest page measured, from a 65 MB scan, took under a second on a phone.
         */
        const val HUNG_MS = 20_000L

        private const val TICK_MS = 1_000L
    }
}

package com.arjun.gander

import android.os.IBinder
import android.os.Parcel
import android.os.ParcelFileDescriptor
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Collections
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A well behaved renderer of a three-page A4 document, until a test says otherwise. */
internal class FakeRenderer : IPdfRenderer.Stub() {

    @Volatile var pages = 3

    @Volatile var size = (595L shl 32) or 842L

    @Volatile var dead = false

    /** How many bytes to send for an answer that takes the given number. */
    @Volatile var bytes: (Int) -> Int = { it }

    /** What a page drawn at a size is, as the renderer's rows of blue, green, red and alpha. */
    @Volatile var pixels: (page: Int, width: Int, height: Int) -> ByteArray = { _, w, h -> page(4 * w * h) }

    @Volatile var onOpen: () -> Unit = {}

    @Volatile var onRender: () -> Unit = {}

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val opens = AtomicInteger()
    val sized = AtomicInteger()
    val drawn: MutableList<String> = Collections.synchronizedList(mutableListOf())

    /** Each call that came with a reply to write, which none should. */
    val replied: MutableList<Int> = Collections.synchronizedList(mutableListOf())

    override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
        if (reply != null || flags and IBinder.FLAG_ONEWAY == 0) replied += code
        return super.onTransact(code, data, reply, flags)
    }

    override fun open(document: ParcelFileDescriptor?, answer: ParcelFileDescriptor?) {
        opens.incrementAndGet()
        document?.close()
        onOpen()
        reply(answer, ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(pages).array())
    }

    override fun pageSize(page: Int, answer: ParcelFileDescriptor?) {
        sized.incrementAndGet()
        reply(answer, ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putLong(size).array())
    }

    override fun render(page: Int, width: Int, height: Int, flags: Int, answer: ParcelFileDescriptor?) {
        drawn += "$page ${width}x$height" + if (flags != 0) " flags $flags" else ""
        onRender()
        reply(answer, pixels(page, width, height))
    }

    /** Writes [answer], cut short or run over as [bytes] says, and closes the pipe. */
    private fun reply(pipe: ParcelFileDescriptor?, answer: ByteArray) {
        val sent = bytes(answer.size)
        ParcelFileDescriptor.AutoCloseOutputStream(pipe!!).use {
            it.write(if (sent <= answer.size) answer.copyOf(sent) else answer + page(sent - answer.size))
        }
    }

    override fun pingBinder(): Boolean = !dead

    companion object {
        fun page(count: Int) = ByteArray(count) { (it % 251).toByte() }
    }
}

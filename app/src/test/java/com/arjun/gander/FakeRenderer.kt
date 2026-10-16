package com.arjun.gander

import android.os.ParcelFileDescriptor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.atomic.AtomicInteger

/** A well behaved renderer of a three-page A4 document, until a test says otherwise. */
internal class FakeRenderer : IPdfRenderer.Stub() {

    @Volatile var pages = 3
    @Volatile var size = (595L shl 32) or 842L
    @Volatile var dead = false

    /** How many bytes to send for a page that takes the given number. */
    @Volatile var bytes: (Int) -> Int = { it }
    @Volatile var onOpen: () -> Unit = {}
    @Volatile var onRender: () -> Unit = {}

    val entered = CountDownLatch(1)
    val release = CountDownLatch(1)
    val opens = AtomicInteger()
    val sized = AtomicInteger()
    val drawn: MutableList<String> = java.util.Collections.synchronizedList(mutableListOf())

    override fun open(document: ParcelFileDescriptor?): Int {
        opens.incrementAndGet()
        document?.close()
        onOpen()
        return pages
    }

    override fun pageSize(page: Int): Long {
        sized.incrementAndGet()
        return size
    }

    override fun render(page: Int, width: Int, height: Int, sink: ParcelFileDescriptor?) {
        drawn += "$page ${width}x$height"
        onRender()
        ParcelFileDescriptor.AutoCloseOutputStream(sink!!).use { it.write(page(bytes(4 * width * height))) }
    }

    override fun pingBinder(): Boolean = !dead

    companion object {
        fun page(count: Int) = ByteArray(count) { (it % 251).toByte() }
    }
}

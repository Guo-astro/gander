package com.arjun.gander

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gander's side of PdfRenderService's pipes. Nothing the renderer says is taken on trust: the
 * size to draw is worked out and capped here, and so is the number of bytes to read back.
 */

/**
 * Whether [document] is a regular file open for reading alone, the one kind of descriptor the
 * renderer is handed: not a pipe or a socket, and not one it could write through.
 */
internal fun readOnlyFile(document: ParcelFileDescriptor): Boolean = try {
    OsConstants.S_ISREG(Os.fstat(document.fileDescriptor).st_mode) &&
        accessMode(document.fd) == OsConstants.O_RDONLY
} catch (e: ErrnoException) {
    false
} catch (e: IOException) {
    false
}

/**
 * How [fd] was opened, O_RDONLY, O_WRONLY or O_RDWR, from the flags the kernel lists for it in
 * octal. fcntl would say as much, but Android has it public only from 11.
 */
private fun accessMode(fd: Int): Int? = File("/proc/self/fdinfo/$fd").readLines()
    .firstOrNull { it.startsWith("flags:") }
    ?.substringAfter(':')?.trim()?.toIntOrNull(8)
    ?.and(OsConstants.O_ACCMODE)

/**
 * What [PdfRenderClient.open] asks of a descriptor before handing it over, [readOnlyFile]. Unit
 * tests put a stand-in here, since Robolectric says every descriptor is neither a regular file
 * nor writable.
 */
@androidx.annotation.VisibleForTesting
internal var mayHandOver: (ParcelFileDescriptor) -> Boolean = ::readOnlyFile

/**
 * The size in pixels to draw a page of [pageWidth] x [pageHeight] points at, [width] pixels
 * across, kept within the renderer's caps however the page claims to be shaped. Null for a
 * size no page has, which is what a renderer that lies about it would send.
 */
internal fun drawSize(width: Int, pageWidth: Int, pageHeight: Int): IntArray? {
    if (width < 1 || pageWidth < 1 || pageHeight < 1) return null
    var w = width.coerceAtMost(PdfRenderService.MAX_SIDE)
    var h = (w.toLong() * pageHeight / pageWidth).coerceIn(1, PdfRenderService.MAX_SIDE.toLong()).toInt()
    if (h == PdfRenderService.MAX_SIDE) {
        w = (h.toLong() * pageWidth / pageHeight).coerceIn(1, w.toLong()).toInt()
    }
    val pixels = w.toLong() * h
    if (pixels > PdfRenderService.MAX_PIXELS) {
        val scale = Math.sqrt(PdfRenderService.MAX_PIXELS.toDouble() / pixels)
        w = (w * scale).toInt().coerceAtLeast(1)
        h = (h * scale).toInt().coerceAtLeast(1)
    }
    return intArrayOf(w, h)
}

/**
 * An answer from the renderer's pipe: exactly [count] bytes, the number worked out here from what
 * was asked, or null when the pipe ends early or holds more than that.
 */
internal fun readAnswer(input: InputStream, count: Int): ByteArray? {
    val bytes = ByteArray(count)
    var have = 0
    while (have < count) {
        val n = input.read(bytes, have, count - have)
        if (n < 0) return null
        have += n
    }
    return if (input.read() == -1) bytes else null
}

/**
 * A page from [readAnswer] as a bitmap. Its blue, green, red and alpha bytes are each pixel's
 * ARGB int read little-endian.
 */
internal fun pageBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

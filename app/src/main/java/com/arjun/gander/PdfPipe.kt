package com.arjun.gander

import android.graphics.Bitmap
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Gander's side of PdfRenderService's pipe. Nothing the renderer says is taken on trust: the
 * size to draw is worked out and capped here, and so is the number of bytes to read back.
 */

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
 * One page from the renderer's pipe: exactly [count] bytes, the number worked out here from the
 * size asked for, or null when the pipe ends early or holds more than that.
 */
internal fun readPage(input: InputStream, count: Int): ByteArray? {
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
 * A page from [readPage] as a bitmap. Its blue, green, red and alpha bytes are each pixel's ARGB
 * int read little-endian.
 */
internal fun pageBitmap(bytes: ByteArray, width: Int, height: Int): Bitmap {
    val pixels = IntArray(width * height)
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asIntBuffer().get(pixels)
    return Bitmap.createBitmap(pixels, width, height, Bitmap.Config.ARGB_8888)
}

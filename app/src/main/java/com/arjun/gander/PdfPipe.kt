package com.arjun.gander

import android.graphics.Bitmap
import android.os.ParcelFileDescriptor
import android.system.ErrnoException
import android.system.Os
import android.system.OsConstants
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.Deflater

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
 * What [PdfRenderClient.open] asks of a descriptor before handing it over, [readOnlyFile], and
 * what the viewer asks before sending a PDF to the simple view. Unit tests put a stand-in here,
 * since Robolectric says every descriptor is neither a regular file nor writable.
 */
@set:androidx.annotation.VisibleForTesting
internal var mayHandOver: (ParcelFileDescriptor) -> Boolean = ::readOnlyFile

/**
 * The size in pixels to draw a page of [pageWidth] x [pageHeight] points at, [width] pixels
 * across, kept within the renderer's caps, and [maxPixels] if that is less, however the page
 * claims to be shaped. Null for a size no page has, which is what a renderer that lies about it
 * would send.
 */
internal fun drawSize(
    width: Int,
    pageWidth: Int,
    pageHeight: Int,
    maxPixels: Long = PdfRenderService.MAX_PIXELS,
): IntArray? {
    if (width < 1 || pageWidth < 1 || pageHeight < 1) return null
    var w = width.coerceAtMost(PdfRenderService.MAX_SIDE)
    var h = (w.toLong() * pageHeight / pageWidth).coerceIn(1, PdfRenderService.MAX_SIDE.toLong()).toInt()
    if (h == PdfRenderService.MAX_SIDE) {
        w = (h.toLong() * pageWidth / pageHeight).coerceIn(1, w.toLong()).toInt()
    }
    val most = minOf(maxPixels, PdfRenderService.MAX_PIXELS)
    val pixels = w.toLong() * h
    if (pixels > most) {
        val scale = Math.sqrt(most.toDouble() / pixels)
        w = (w * scale).toInt().coerceAtLeast(1)
        h = (h * scale).toInt().coerceAtLeast(1)
    }
    return intArrayOf(w, h)
}

/**
 * The most pixels a page of the simple view is drawn with on a phone that gives each app
 * [memoryClass] MB: an eighth of that at four bytes a pixel, between a megapixel and the
 * renderer's cap. A page costs that much three times over while it is drawn, in the renderer, in
 * the WebView that decodes it, and on screen, so a phone with little memory gets smaller ones.
 * At 256 MB this is the renderer's cap, which pdf.js keeps to as well.
 */
internal fun pagePixelCap(memoryClass: Int): Long =
    ((memoryClass.toLong() shl 20) / 32).coerceIn(1L shl 20, PdfRenderService.MAX_PIXELS)

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

/**
 * A page from the renderer as a PNG, made here from the rows [PdfRenderClient.renderInto] counts
 * out, so that what reaches the WebView's decoder is a file Gander wrote around bytes that are only
 * pixels. Uncompressed, an A4 page on a phone is about 10 MB, and the WebView holds what it is
 * served for as long as the page is on screen, beside the picture it decodes from it. Compressed,
 * a page of text is a few hundred KB.
 *
 * With [night], the page is turned over by the matrix pdf.html's night mode uses, its vw-invert
 * filter, so the two views agree. Here it turns over the whole page, photographs too: the renderer
 * says nothing about where a page's pictures are.
 */
internal class PagePng(
    private val width: Int,
    private val height: Int,
    private val night: Boolean,
) {

    /**
     * Reads the page's rows from [rows] and writes the PNG to [out]. False if the rows ran out
     * before the page did, with [out] holding a PNG cut short, or [stop] said to give up.
     */
    fun write(rows: InputStream, out: OutputStream, stop: () -> Boolean = { false }): Boolean {
        out.write(SIGNATURE)
        val header = ByteBuffer.allocate(13).putInt(width).putInt(height)
            .put(byteArrayOf(8, 2, 0, 0, 0)).array()
        chunk(out, IHDR, header, header.size)

        val strip = (STRIP_BYTES / (4 * width)).coerceAtLeast(1)
        val input = ByteArray(4 * width * strip)
        // Each row as PNG has it: its filter, then red, green and blue
        val line = ByteArray(1 + 3 * width)
        val packed = ByteArray(CHUNK_BYTES)
        var have = 0
        val deflater = Deflater(Deflater.BEST_SPEED)

        // What the deflater has ready, an IDAT chunk each time the buffer fills
        fun pack() {
            have += deflater.deflate(packed, have, packed.size - have)
            if (have == packed.size) {
                chunk(out, IDAT, packed, have)
                have = 0
            }
        }
        try {
            var y = 0
            while (y < height) {
                if (stop()) return false
                val n = minOf(strip, height - y)
                if (!readFully(rows, input, 4 * width * n)) return false
                for (row in 0 until n) {
                    filterRow(input, 4 * width * row, line)
                    deflater.setInput(line)
                    while (!deflater.needsInput()) pack()
                }
                y += n
            }
            deflater.finish()
            while (!deflater.finished()) pack()
            if (have > 0) chunk(out, IDAT, packed, have)
        } finally {
            deflater.end()
        }
        chunk(out, IEND, packed, 0)
        return true
    }

    /**
     * One row of the renderer's blue, green, red and alpha bytes from [from] as a PNG line in
     * [line]: filtered by Sub, each byte less the same byte of the pixel to its left, which turns
     * a run of paper into a run of zeros.
     */
    private fun filterRow(input: ByteArray, from: Int, line: ByteArray) {
        line[0] = 1
        var lastR = 0
        var lastG = 0
        var lastB = 0
        var at = from
        var to = 1
        for (x in 0 until width) {
            var b = input[at].toInt() and 0xff
            var g = input[at + 1].toInt() and 0xff
            var r = input[at + 2].toInt() and 0xff
            if (night) {
                val tr = turnedR(r, g, b)
                val tg = turnedG(r, g, b)
                val tb = turnedB(r, g, b)
                r = tr
                g = tg
                b = tb
            }
            line[to] = (r - lastR).toByte()
            line[to + 1] = (g - lastG).toByte()
            line[to + 2] = (b - lastB).toByte()
            lastR = r
            lastG = g
            lastB = b
            at += 4
            to += 3
        }
    }

    private fun chunk(out: OutputStream, type: ByteArray, data: ByteArray, length: Int) {
        val crc = CRC32()
        crc.update(type)
        crc.update(data, 0, length)
        out.write(ByteBuffer.allocate(8).putInt(length).put(type).array())
        out.write(data, 0, length)
        out.write(ByteBuffer.allocate(4).putInt(crc.value.toInt()).array())
    }

    private fun readFully(input: InputStream, into: ByteArray, count: Int): Boolean {
        var have = 0
        while (have < count) {
            val n = input.read(into, have, count - have)
            if (n < 0) return false
            have += n
        }
        return true
    }

    companion object {
        private val SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
        private val IHDR = "IHDR".toByteArray(Charsets.US_ASCII)
        private val IDAT = "IDAT".toByteArray(Charsets.US_ASCII)
        private val IEND = "IEND".toByteArray(Charsets.US_ASCII)
        private const val STRIP_BYTES = 1 shl 20
        private const val CHUNK_BYTES = 1 shl 16

        /*
         * The vw-invert matrix in pdf.html, a fixed-point table a coefficient: invert composed with
         * hue-rotate(180deg), so lightness turns over and a colour keeps its hue. Every row sums to
         * -1, which with the offset of one sends a grey g to 1 - g.
         */
        private const val ONE = 1 shl 16
        private fun table(k: Double) = IntArray(256) { Math.round(k * it * ONE).toInt() }
        private val RR = table(0.574)
        private val RG = table(-1.430)
        private val RB = table(-0.144)
        private val GR = table(-0.426)
        private val GG = table(-0.430)
        private val GB = table(-0.144)
        private val BR = table(-0.426)
        private val BG = table(-1.430)
        private val BB = table(0.856)
        private const val OFFSET = 255 * ONE + ONE / 2

        private fun clamp(v: Int) = (v shr 16).coerceIn(0, 255)

        internal fun turnedR(r: Int, g: Int, b: Int) = clamp(OFFSET + RR[r] + RG[g] + RB[b])

        internal fun turnedG(r: Int, g: Int, b: Int) = clamp(OFFSET + GR[r] + GG[g] + GB[b])

        internal fun turnedB(r: Int, g: Int, b: Int) = clamp(OFFSET + BR[r] + BG[g] + BB[b])
    }
}

package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import org.junit.Test

/**
 * Gander's side of the isolated renderer's pipe, where nothing the renderer says is trusted: the
 * size to draw at, and how many bytes to take back.
 */
class PdfPipeTest {

    // ---------------------------------------------------------------
    // drawSize
    // ---------------------------------------------------------------

    @Test
    fun aPageKeepsItsShapeAtTheWidthAskedFor() {
        // A4 in points, drawn across a 1080-pixel phone
        assertThat(drawSize(1080, 595, 841)!!.toList()).containsExactly(1080, 1526).inOrder()
    }

    @Test
    fun aSizeNoPageHasIsRefused() {
        assertThat(drawSize(1080, 0, 841)).isNull()
        assertThat(drawSize(1080, 595, -1)).isNull()
        assertThat(drawSize(0, 595, 841)).isNull()
    }

    @Test
    fun aPageClaimingToBeAThreadIsCappedOnBothSides() {
        val (w, h) = drawSize(1080, 1, Int.MAX_VALUE)!!.toList()
        assertThat(h).isAtMost(PdfRenderService.MAX_SIDE)
        assertThat(w).isAtLeast(1)
        assertThat(w.toLong() * h).isAtMost(PdfRenderService.MAX_PIXELS)
    }

    @Test
    fun aWideRequestIsCappedToTheRenderersSideAndArea() {
        val (w, h) = drawSize(20_000, 1000, 1000)!!.toList()
        assertThat(w).isAtMost(PdfRenderService.MAX_SIDE)
        assertThat(w.toLong() * h).isAtMost(PdfRenderService.MAX_PIXELS)
        assertThat(w).isEqualTo(h)
    }

    // ---------------------------------------------------------------
    // readAnswer
    // ---------------------------------------------------------------

    @Test
    fun exactlyTheBytesWorkedOutAreAPage() {
        val page = ByteArray(16) { it.toByte() }
        assertThat(readAnswer(ByteArrayInputStream(page), 16)).isEqualTo(page)
    }

    @Test
    fun aPipeThatEndsEarlyIsNoPage() {
        assertThat(readAnswer(ByteArrayInputStream(ByteArray(15)), 16)).isNull()
        assertThat(readAnswer(ByteArrayInputStream(ByteArray(0)), 16)).isNull()
    }

    @Test
    fun aPipeHoldingMoreThanThePageIsNoPage() {
        assertThat(readAnswer(ByteArrayInputStream(ByteArray(17)), 16)).isNull()
    }

    // ---------------------------------------------------------------
    // startsLikePdf
    // ---------------------------------------------------------------

    @Test
    fun aPdfIsKnownByItsHeaderAnywhereInTheFirstKilobyte() {
        val plain = "%PDF-1.7\n".toByteArray()
        assertThat(startsLikePdf(plain, plain.size)).isTrue()
        val late = ByteArray(1024).also { "%PDF-1.4".toByteArray().copyInto(it, 1000) }
        assertThat(startsLikePdf(late, late.size)).isTrue()
    }

    @Test
    fun anythingElseIsNotAPdf() {
        val text = "Not a PDF, though it ends in .pdf".toByteArray()
        assertThat(startsLikePdf(text, text.size)).isFalse()
        // The header beyond what was read does not count
        val cut = "%PDF-1.7".toByteArray()
        assertThat(startsLikePdf(cut, 3)).isFalse()
        assertThat(startsLikePdf(ByteArray(0), 0)).isFalse()
    }

    // ---------------------------------------------------------------
    // The simple view's pages: pagePixelCap and PagePng
    // ---------------------------------------------------------------

    /** A page held to the cap is drawn with fewer pixels, at the same shape. */
    @Test
    fun aPageIsHeldToTheCapItIsGiven() {
        val (w, h) = drawSize(1960, 595, 842, 1L shl 20)!!.toList()
        assertThat(w.toLong() * h).isAtMost(1L shl 20)
        assertThat(h.toDouble() / w).isWithin(0.01).of(842.0 / 595)
        // And never past the renderer's own, whatever the cap says
        val (bw, bh) = drawSize(8000, 1000, 1000, Long.MAX_VALUE)!!.toList()
        assertThat(bw.toLong() * bh).isAtMost(PdfRenderService.MAX_PIXELS)
    }

    @Test
    fun aPhoneWithLessMemoryGetsSmallerPages() {
        assertThat(pagePixelCap(256)).isEqualTo(PdfRenderService.MAX_PIXELS)
        assertThat(pagePixelCap(512)).isEqualTo(PdfRenderService.MAX_PIXELS)
        assertThat(pagePixelCap(128)).isEqualTo(4L shl 20)
        assertThat(pagePixelCap(64)).isEqualTo(2L shl 20)
        // Never so small a page can't be read, nor nothing for a phone that says nothing
        assertThat(pagePixelCap(16)).isEqualTo(1L shl 20)
        assertThat(pagePixelCap(0)).isEqualTo(1L shl 20)
    }

    /** Rows of blue, green, red and alpha, as the renderer sends them, one colour a pixel. */
    private fun rows(width: Int, height: Int, colour: (x: Int, y: Int) -> Int): ByteArray {
        val bytes = ByteArray(4 * width * height)
        for (y in 0 until height) for (x in 0 until width) {
            val rgb = colour(x, y)
            val at = 4 * (y * width + x)
            bytes[at] = rgb.toByte()
            bytes[at + 1] = (rgb shr 8).toByte()
            bytes[at + 2] = (rgb shr 16).toByte()
            bytes[at + 3] = -1
        }
        return bytes
    }

    private fun png(width: Int, height: Int, night: Boolean, colour: (Int, Int) -> Int): ByteArray {
        val out = ByteArrayOutputStream()
        val whole = PagePng(width, height, night).write(ByteArrayInputStream(rows(width, height, colour)), out)
        assertThat(whole).isTrue()
        return out.toByteArray()
    }

    /** Wide enough that a row runs over the deflater's buffer, and tall enough for several strips. */
    @Test
    fun aPageComesOutAsAPngOfExactlyItsPixels() {
        val width = 1300
        val height = 900
        val colour = { x: Int, y: Int -> ((x * 7) and 0xff shl 16) or ((y * 3) and 0xff shl 8) or ((x + y) and 0xff) }
        val image = JdkPng(png(width, height, false, colour))
        assertThat(image.width).isEqualTo(width)
        assertThat(image.height).isEqualTo(height)
        for (y in 0 until height step 37) for (x in 0 until width step 41) {
            assertThat(image.rgb(x, y)).isEqualTo(colour(x, y))
        }
    }

    /** A page of paper is mostly zeros once filtered, so it comes out small. */
    @Test
    fun aPageOfPaperComesOutSmall() {
        val bytes = png(1342, 1898, false) { _, _ -> 0xffffff }
        assertThat(bytes.size).isLessThan(64 * 1024)
    }

    /**
     * pdf.html's night matrix: paper to black, black to white, a grey to the same distance from
     * the other end, and a blue still blue, lighter.
     */
    @Test
    fun nightTurnsAPageOverAsPdfHtmlDoes() {
        val image = JdkPng(png(4, 1, true) { x, _ -> intArrayOf(0xffffff, 0x000000, 0x141414, 0x0077c7)[x] })
        val turned = (0 until 4).map { image.rgb(it, 0) }
        assertThat(turned[0]).isEqualTo(0x000000)
        assertThat(turned[1]).isEqualTo(0xffffff)
        assertThat(turned[2]).isEqualTo(0xebebeb)
        // 0,119,199 lands on 56,175,255, as pdf.html's comment works out
        val blue = turned[3]
        assertThat(abs((blue shr 16 and 0xff) - 56)).isAtMost(1)
        assertThat(abs((blue shr 8 and 0xff) - 175)).isAtMost(1)
        assertThat(blue and 0xff).isEqualTo(255)
    }

    /** Rows that stop before the page does are no page. */
    @Test
    fun rowsThatRunOutAreNoPage() {
        val out = ByteArrayOutputStream()
        val short = ByteArrayInputStream(rows(10, 10) { _, _ -> 0 }.copyOf(4 * 10 * 9))
        assertThat(PagePng(10, 10, false).write(short, out)).isFalse()
    }

    /** A viewer that has gone stops a page part way. */
    @Test
    fun aPageCanBeStoppedPartWay() {
        val out = ByteArrayOutputStream()
        val rows = ByteArrayInputStream(rows(10, 10) { _, _ -> 0 })
        assertThat(PagePng(10, 10, false).write(rows, out) { true }).isFalse()
    }
}

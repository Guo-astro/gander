package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream

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
}

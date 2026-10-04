package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import kotlin.random.Random

/** Print, issue #45: which files print, what the job is called, and the copy a PDF prints from. */
class PrintTest {

    @Test
    fun onlyAPdfAndThePaperDocumentsPrint() {
        val printing = FileKind.entries.filter { printRoute(it) != null }
        assertThat(printing).containsExactly(FileKind.PDF, FileKind.DOCX, FileKind.PROSE)
        assertThat(printRoute(FileKind.PDF)).isEqualTo(PrintRoute.FILE)
        assertThat(printRoute(FileKind.DOCX)).isEqualTo(PrintRoute.PAGE)
        assertThat(printRoute(FileKind.PROSE)).isEqualTo(PrintRoute.PAGE)
    }

    /** "Save as PDF" adds .pdf itself, so a Word file would otherwise come out as letter.docx.pdf. */
    @Test
    fun theJobIsNamedWithoutTheExtension() {
        assertThat(printJobName("letter.docx")).isEqualTo("letter")
        assertThat(printJobName("statement.2026.10.pdf")).isEqualTo("statement.2026.10")
        assertThat(printJobName("README")).isEqualTo("README")
    }

    /** Android refuses a job with no name, so one is never empty. */
    @Test
    fun aNameThatIsOnlyAnExtensionIsKept() {
        assertThat(printJobName(".pdf")).isEqualTo(".pdf")
        assertThat(printJobName("")).isEqualTo("Document")
    }

    /** "Exactly as it is": every byte, in order, across many buffers' worth. */
    @Test
    fun aPdfIsCopiedByteForByte() {
        val bytes = Random(45).nextBytes(1_000_003)
        val out = ByteArrayOutputStream()
        assertThat(copyForPrint(ByteArrayInputStream(bytes), out) { false }).isTrue()
        assertThat(out.toByteArray()).isEqualTo(bytes)
    }

    @Test
    fun aCancelledCopyStopsAndSaysSo() {
        val bytes = Random(45).nextBytes(1_000_003)
        val out = ByteArrayOutputStream()
        var asked = 0
        assertThat(copyForPrint(ByteArrayInputStream(bytes), out) { ++asked > 2 }).isFalse()
        assertThat(out.size()).isLessThan(bytes.size)
    }
}

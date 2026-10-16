package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test

/**
 * [ViewerPolicy] is written into every viewer page as well as sent as a header, so that the
 * browser tests, which serve the pages as they are, enforce the same policy the app does.
 * Two copies drift, and the dangerous way round is a page without one: it would run under
 * the policy in the app and without it in the tests, so the tests would pass and prove
 * nothing about it. These hold every page to the same text, in the place it has to be.
 */
class ViewerPolicyTest {

    private companion object {
        val VIEWER = File("src/main/assets/viewer")
        val PAGES = VIEWER.listFiles { f -> f.extension == "html" }.orEmpty().sortedBy { it.name }
        val META = Regex("""<meta http-equiv="Content-Security-Policy" content="([^"]*)">""")
    }

    @Test
    fun everyViewerPageIsChecked() {
        assertThat(PAGES.map { it.name }).containsExactly(
            "docx.html", "epub.html", "imgweb.html", "md.html", "model.html", "pdf.html",
            "pdfsimple.html", "pptx.html", "prose.html", "text.html", "unsupported.html",
            "xlsx.html",
        )
    }

    @Test
    fun everyPageCarriesThePolicyWordForWord() {
        for (page in PAGES) {
            val found = META.findAll(page.readText()).map { it.groupValues[1] }.toList()
            assertWithMessage(page.name).that(found).containsExactly(ViewerPolicy.CSP)
        }
    }

    /**
     * A policy in a meta tag governs only what the parser meets after it, so it goes before
     * the first script, stylesheet or image. After the charset, which has to come first.
     */
    @Test
    fun thePolicyComesBeforeAnythingItGoverns() {
        for (page in PAGES) {
            val html = page.readText()
            val policy = html.indexOf("""<meta http-equiv="Content-Security-Policy"""")
            val governed = listOf("<script", "<link", "<style", "<img", "<iframe", "<object")
                .map { html.indexOf(it) }
                .filter { it >= 0 }
            assertWithMessage("${page.name}: charset first")
                .that(html.indexOf("""<meta charset="utf-8">""")).isIn(0 until policy)
            assertWithMessage("${page.name}: policy before ${governed.minOrNull()}")
                .that(governed.all { policy < it }).isTrue()
        }
    }

    /**
     * The policy forbids inline script, so any added to a page would not run in the app,
     * and nothing would say so. This fails first, and says why.
     */
    @Test
    fun noPageCarriesScriptThePolicyWouldRefuse() {
        val inline = Regex("""<script(?![^>]*\ssrc=)[^>]*>""")
        val handler = Regex("""<[a-z][^>]*\son[a-z]+\s*=""", RegexOption.IGNORE_CASE)
        for (page in PAGES) {
            val html = page.readText()
            assertWithMessage("${page.name}: inline script")
                .that(inline.findAll(html).map { it.value }.toList()).isEmpty()
            assertWithMessage("${page.name}: event-handler attribute")
                .that(handler.findAll(html).map { it.value }.toList()).isEmpty()
            assertWithMessage("${page.name}: javascript: URL")
                .that(html.contains("javascript:", ignoreCase = true)).isFalse()
        }
    }
}

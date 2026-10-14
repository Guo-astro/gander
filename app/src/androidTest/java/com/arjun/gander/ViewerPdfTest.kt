package com.arjun.gander

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * PDFs, rendered by the WebView Android actually ships.
 *
 * tests/viewer covers what the page does in far more detail and in a second
 * rather than a minute. What only a device can answer is whether the engine
 * on the phone behaves the same: a different Chromium build, in a separate
 * process, inside an app with no network permission, reading its document
 * through a WebViewAssetLoader rather than a web server.
 *
 * So this is deliberately a handful of end-to-end checks rather than a
 * duplicate of the browser suite.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ViewerPdfTest {

    @get:Rule
    val retry = RetryRule()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    private fun open(fixture: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(DeviceFixtures.viewIntent(fixture))

    /** A drawn canvas is the page width across; an untouched one is 300. */
    private val DRAWN =
        "[].slice.call(document.querySelectorAll('#pages .pg canvas'))" +
            ".filter(function (c) { return c.width > 300; }).length"

    private val TEXT_LAYER =
        "[].slice.call(document.querySelectorAll('#pages .pg .textLayer'))" +
            ".map(function (l) { return l.textContent; }).join(' ')"

    @Test
    fun aDocumentRendersOnTheDevicesOwnWebView() {
        open("six-pages.pdf").use { scenario ->
            WebViewProbe.await(scenario, "$DRAWN >= 1", "the first page to be drawn")
            val slots = WebViewProbe.eval(
                scenario, "document.querySelectorAll('#pages .pg').length"
            )
            assertThat(slots).isEqualTo("6")
        }
    }

    @Test
    fun theTextLayerCarriesTheWordsOnThePage() {
        open("six-pages.pdf").use { scenario ->
            WebViewProbe.await(
                scenario,
                "document.querySelector('#pages .pg .textLayer span')",
                "the text layer to be built"
            )
            assertThat(WebViewProbe.text(scenario, TEXT_LAYER)).contains("Alder Court")
        }
    }

    /**
     * The CJK regression on a device. The browser suite proves the CMap tables
     * work; this proves they are inside the APK and reachable through the
     * asset loader, which is a different question and the one that broke.
     */
    @Test
    fun chineseTextRendersFromTheBundledCmapTables() {
        open("cjk.pdf").use { scenario ->
            WebViewProbe.await(
                scenario,
                "document.querySelector('#pages .pg .textLayer span')",
                "the text layer to be built"
            )
            assertThat(WebViewProbe.text(scenario, TEXT_LAYER)).contains("你好世界")
        }
    }

    @Test
    fun anEncryptedDocumentUnlocksThroughTheInPageForm() {
        open("encrypted.pdf").use { scenario ->
            WebViewProbe.await(
                scenario, "document.getElementById('vw-pw')", "the password form"
            )

            WebViewProbe.eval(
                scenario,
                "(function () {" +
                    "  var input = document.getElementById('vw-pw');" +
                    "  input.value = 'gander';" +
                    "  document.querySelector('.vw-ask-row')" +
                    "    .dispatchEvent(new Event('submit', { cancelable: true }));" +
                    "  return 1;" +
                    "})()"
            )

            WebViewProbe.await(scenario, "$DRAWN >= 1", "the unlocked document to draw")
        }
    }

    @Test
    fun aFileThatIsNotAPdfSaysSoRatherThanShowingNothing() {
        open("not-a-pdf.pdf").use { scenario ->
            WebViewProbe.await(
                scenario,
                "document.querySelector('.vw-error-title')",
                "the error card"
            )
            val said = WebViewProbe.text(
                scenario, "document.querySelector('.vw-error-title').textContent"
            )
            assertThat(said).contains("not a PDF")
        }
    }

    /**
     * The document is served by the activity out of shouldInterceptRequest,
     * from a content URI, with no network permission anywhere. That whole path
     * exists only on a device.
     */
    @Test
    fun theDocumentReachesThePageThroughTheAssetHost() {
        open("six-pages.pdf").use { scenario ->
            WebViewProbe.await(scenario, "$DRAWN >= 1", "the first page to be drawn")
            val origin = WebViewProbe.text(scenario, "location.origin")
            assertThat(origin).isEqualTo("https://appassets.androidplatform.net")
        }
    }
}

package com.arjun.gander

import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.webkit.WebSettings
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.webkit.WebViewCompat
import com.google.android.material.appbar.MaterialToolbar
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The simple view, where a PDF is drawn by Android's own PdfRenderer in an isolated process
 * because pdf.js can't run on the WebView: see FileKind.PDF_SIMPLE.
 *
 * ViewerActivityTest drives it against a stand-in renderer, and tests/viewer drives its page
 * against a stand-in server. What only a device has is the renderer itself: the platform's PDFium,
 * in a process Android walls off, handing pages back through the pipe to a real WebView.
 *
 * pdf.js runs on the nightly image, so most of these open the view with the extra a debuggable
 * build takes for it. The card's own way in needs a WebView below pdf.js's floor that an update
 * would raise, which the sub-floor job in device.yml has, and is skipped anywhere else.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PdfSimpleDeviceTest {

    @get:Rule
    val retry = RetryRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        // Never offered on Android 8, where the renderer isn't walled off from Gander
        assumeTrue(PdfRenderClient.containedOn(Build.VERSION.SDK_INT))
        DeviceFixtures.clear()
    }

    private fun openSimple(fixture: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(
            DeviceFixtures.viewIntent(fixture).putExtra(ViewerActivity.EXTRA_SIMPLE_VIEW, true)
        )

    @Test
    fun pagesAreDrawnByARendererInAnIsolatedProcessOfItsOwn() {
        val renderers = RendererSlots.services.indices.map(::rendererProcess)
        openSimple("six-pages.pdf").use { scenario ->
            WebViewProbe.await(scenario, "$DRAWN >= 1", "the first page to be drawn")
            assertThat(WebViewProbe.eval(scenario, "document.querySelectorAll('#pages .pg').length"))
                .isEqualTo("6")

            // Every process of Gander's but its own, so a renderer that isn't isolated is found
            // too. This viewer's is among them by the name looked for once it has closed, or that
            // check would pass on finding none: a thumbnail renderer is no proof of the name.
            val others = othersOfGanders()
            assertWithMessage("the simple view's renderer among Gander's processes")
                .that(others.map { it.name }).containsAnyIn(renderers)
            for (other in others) {
                assertWithMessage(other.name).that(other.uid).isNotEqualTo(Process.myUid())
                assertWithMessage("${other.name}'s uid ${other.uid}")
                    .that(isIsolatedUid(other.uid)).isTrue()
            }
        }
        // It holds the document's descriptor, so it goes with the viewer
        awaitOthers("no renderer left once the viewer closed") { others ->
            others.none { it.name in renderers }
        }
    }

    /** Android's renderer reports the password itself, which is the part only a device can show. */
    @Test
    fun aPdfThatNeedsAPasswordSaysSoAndIsNotPrinted() {
        openSimple("encrypted.pdf").use { scenario ->
            WebViewProbe.await(scenario, "document.querySelector('.vw-error-title')", "the card")
            assertThat(WebViewProbe.text(scenario, "document.querySelector('.vw-error-title').textContent"))
                .contains("needs a password")
            awaitPrintLocked(scenario)
        }
    }

    @Test
    fun theCardOpensTheSimpleViewOnAWebViewAnUpdateWouldRaise() {
        whereTheCardOffersTheSimpleView()
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("six-pages.pdf")).use { scenario ->
            WebViewProbe.await(scenario, "document.querySelector('.vw-simple-btn')", "the card's button")
            WebViewProbe.eval(scenario, "(function () { document.querySelector('.vw-simple-btn').click(); return 1; })()")

            // The viewer is rebuilt around the simple view's page, which ActivityScenario follows
            WebViewProbe.await(scenario, "$DRAWN >= 1", "the simple view's first page")
            var line = ""
            scenario.onActivity {
                line = it.findViewById<MaterialToolbar>(R.id.toolbar).subtitle?.toString().orEmpty()
            }
            val target = instrumentation.targetContext
            assertThat(line).isAnyOf(
                target.getString(R.string.simple_view_line),
                target.getString(R.string.simple_view_line_notes),
            )
        }
    }

    /**
     * Read here from the engine rather than through pdfCardOffersSimple, so a route that offered
     * the card nowhere would fail this test instead of skipping it. The sub-floor job asks for the
     * test to fail instead of skipping, so an image that one day ships a newer WebView says so.
     */
    private fun whereTheCardOffersTheSimpleView() {
        val target = instrumentation.targetContext
        var userAgent: String? = null
        instrumentation.runOnMainSync { userAgent = WebSettings.getDefaultUserAgent(target) }
        val major = userAgent?.let { Regex("""Chrome/(\d+)""").find(it) }?.groupValues?.get(1)?.toInt()
        val provider = WebViewCompat.getCurrentWebViewPackage(target)?.packageName
        val offered = major != null && major < PDFJS_MIN_CHROMIUM_MAJOR &&
            provider !in LOCKED_WEBVIEW_PACKAGES
        val said = "WebView $major from $provider, and the card needs one below " +
            "$PDFJS_MIN_CHROMIUM_MAJOR that can be updated"
        if (InstrumentationRegistry.getArguments().getString("subFloor") == "required") {
            assertWithMessage(said).that(offered).isTrue()
        } else {
            assumeTrue(said, offered)
        }
    }

    private fun awaitPrintLocked(scenario: ActivityScenario<ViewerActivity>) {
        val deadline = System.currentTimeMillis() + 15_000
        while (System.currentTimeMillis() < deadline) {
            var locked = false
            scenario.onActivity { locked = it.printLocked }
            if (locked) return
            Thread.sleep(250)
        }
        throw AssertionError("The viewer never heard the PDF needs a password, so Print would send it")
    }

    private data class Running(val uid: Int, val name: String)

    /**
     * Gander's processes besides the one these tests run in, read from ps, since Android tells an
     * app only of those under its own uid. The WebView's renderer is named for the WebView's
     * package, so it isn't among them.
     */
    private fun othersOfGanders(): List<Running> {
        val listing = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand("ps -A -o UID,NAME")
        ).use { it.readBytes().decodeToString() }
        val prefix = instrumentation.targetContext.packageName + ":"
        return listing.lineSequence()
            .map { it.trim().split(Regex("""\s+"""), limit = 2) }
            .filter { it.size == 2 && it[0].toIntOrNull() != null && it[1].startsWith(prefix) }
            .map { Running(it[0].toInt(), it[1]) }
            .toList()
    }

    /** The name Android gives the process of the isolated renderer in [slot], as ps shows it. */
    private fun rendererProcess(slot: Int): String =
        instrumentation.targetContext.packageName + ":" + RendererSlots.services[slot].name

    private fun awaitOthers(what: String, done: (List<Running>) -> Boolean) {
        val deadline = System.currentTimeMillis() + 10_000
        var seen = othersOfGanders()
        while (!done(seen) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            seen = othersOfGanders()
        }
        assertWithMessage("$what, but ps shows $seen").that(done(seen)).isTrue()
    }

    private companion object {
        /** A page whose picture has come: pdfsimple.js puts one in a page only once it has loaded. */
        const val DRAWN =
            "[].slice.call(document.querySelectorAll('#pages .pg > img'))" +
                ".filter(function (i) { return i.naturalWidth > 0; }).length"

        /** Android's own test, Process.isIsolated(uid), which apps can't call: both isolated ranges. */
        fun isIsolatedUid(uid: Int): Boolean = uid % 100_000 in 90_000..99_999
    }
}

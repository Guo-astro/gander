package com.arjun.gander

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Binder
import android.os.Process
import android.os.SystemClock
import android.widget.FrameLayout
import android.widget.TextView
import androidx.lifecycle.Lifecycle
import androidx.recyclerview.widget.RecyclerView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.pressImeActionButton
import androidx.test.espresso.action.ViewActions.typeText
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.espresso.web.assertion.WebViewAssertions.webMatches
import androidx.test.espresso.web.sugar.Web.onWebView
import androidx.test.espresso.web.webdriver.DriverAtoms.findElement
import androidx.test.espresso.web.webdriver.DriverAtoms.getText
import androidx.test.espresso.web.webdriver.Locator
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.davemorrissey.labs.subscaleview.SubsamplingScaleImageView
import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.util.Locale
import org.hamcrest.Matchers.allOf
import org.hamcrest.Matchers.containsString
import org.hamcrest.Matchers.hasToString
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A .zip on a device, issue #30: the list, and files out of it in every surface.
 *
 * The part that cannot be tested anywhere else is the pipe. A compressed file reaches a viewer
 * through one, inflated on a thread as it is read, and Robolectric imitates pipes with a file.
 * So the Markdown and the PDF here are both compressed in the archive, and the photo is stored,
 * which is the other road: a window onto the archive's own bytes.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class ArchiveDeviceTest {

    @get:Rule
    val retry = RetryRule()

    private val target get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    @After
    fun tearDown() {
        ArchivePasswords.forgetAll()
    }

    /** The URI Gander's own list would open [path] under. */
    private fun entryUri(archive: String, path: String): Uri {
        val archiveUri = DeviceFixtures.uriFor(archive)
        val file = DeviceFixtures.made(archive)
        val raf = RandomAccessFile(file, "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, Locale.US).single { e -> e.path == path }
        }
        return ArchiveProvider.uriFor(target, archiveUri, entry)
    }

    /** What tapping [path] in the list starts. */
    private fun openEntry(archive: String, path: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(
            Intent()
                .setComponent(ComponentName(target, ViewerActivity.INTERNAL_VIEWER))
                .setData(entryUri(archive, path))
        )

    private fun <T : Any> waitFor(what: String, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            probe()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    /** The list's rows, as their titles or their message, once there are any. */
    private fun rows(scenario: ActivityScenario<ViewerActivity>): List<String> = waitFor("rows") {
        var titles: List<String>? = null
        scenario.onActivity { activity ->
            val container = activity.findViewById<FrameLayout>(R.id.container)
            val rv = (0 until container.childCount).map { container.getChildAt(it) }
                .filterIsInstance<RecyclerView>().firstOrNull() ?: return@onActivity
            val adapter = rv.adapter ?: return@onActivity
            titles = (0 until adapter.itemCount).map { position ->
                val holder = adapter.createViewHolder(rv, adapter.getItemViewType(position))
                adapter.bindViewHolder(holder, position)
                val row = holder.itemView
                (row.findViewById<TextView>(R.id.title) ?: row.findViewById(R.id.hintText))
                    .text.toString()
            }.takeIf { it.isNotEmpty() }
        }
        titles
    }

    @Test
    fun aZipOpensAsAListOfWhatIsInIt() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("archive.zip")).use {
            assertThat(rows(it))
                .containsExactly("nested", "photos", "private", "reports", "plain.txt").inOrder()
        }
    }

    /** Tapped through the list, to a file that is compressed, so it arrives through the pipe. */
    @Test
    fun aCompressedFileOpensFromTheList() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("archive.zip")).use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("reports"))).perform(click())
            onView(allOf(withId(R.id.title), withText("notes.md"))).perform(click())
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("Willowmere site notes")))
        }
    }

    /** pdf.js reading a compressed PDF out of the pipe, every page of it. */
    @Test
    fun aCompressedPdfRendersEveryPage() {
        openEntry("archive.zip", "reports/six-pages.pdf").use { scenario ->
            val pages = waitFor("six pages") {
                WebViewProbe.text(scenario, "document.querySelectorAll('#pages .pg').length")
                    .takeIf { it == "6" }
            }
            assertThat(pages).isEqualTo("6")
        }
    }

    /** A stored photo is a window onto the archive, and the tiling view reads it from there. */
    @Test
    fun aStoredPhotoOpensInTheTilingView() {
        openEntry("archive.zip", "photos/tiny.png").use { scenario ->
            val ready = waitFor("the photo") {
                var ready: Boolean? = null
                scenario.onActivity { activity ->
                    val container = activity.findViewById<FrameLayout>(R.id.container)
                    val view = (0 until container.childCount).map { container.getChildAt(it) }
                        .filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                    ready = view?.isReady?.takeIf { it }
                }
                ready
            }
            assertThat(ready).isTrue()
        }
    }

    /** A zip stored whole inside another is a window too, and so can be looked inside. */
    @Test
    fun aZipStoredInsideAZipOpensAsAList() {
        openEntry("archive.zip", "nested/stored.zip").use {
            assertThat(rows(it)).containsExactly("inside.txt")
        }
    }

    /**
     * And a file inside that one, whose URI names an archive that is itself one of these URIs:
     * the provider reads its own window to find the inner index, then the file.
     */
    @Test
    fun aFileInsideAZipInsideAZipOpens() {
        openEntry("archive.zip", "nested/stored.zip").use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("inside.txt"))).perform(click())
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("A file inside a zip inside a zip")))
        }
    }

    /** A compressed one arrives as a pipe, which cannot be read out of order, and says so. */
    @Test
    fun aZipCompressedInsideAZipSaysHowToOpenIt() {
        openEntry("archive.zip", "nested/packed.zip").use {
            assertThat(rows(it))
                .containsExactly(target.getString(R.string.archive_not_seekable))
        }
    }

    /**
     * A file from a zip inside a zip, read by the app it was shared with. That app's call runs
     * on a Gander binder thread under the app's uid, and the outer zip is one of Gander's own
     * URIs again, asked of the same provider on the same thread, which the app holds no grant
     * for. So the provider reads its archive as Gander, or Android refuses the app outright.
     *
     * The app is imitated by taking on another uid for the call, which is all its binder thread
     * would carry here; the framework's check of the one URI the app was given is left out, as
     * a real grant would pass it.
     */
    @Test
    fun aFileFromAZipInsideAZipCanBeReadByTheAppItWasSharedWith() {
        val outer = entryUri("archive.zip", "nested/stored.zip")
        val inner = target.contentResolver.openAssetFileDescriptor(outer, "r")!!.let { afd ->
            ZipSource.open(afd)!!.use { zip ->
                ZipReader.entries(zip, Locale.US).single { it.path == "inside.txt" }
            }
        }
        val shared = ArchiveProvider.uriFor(target, outer, inner)
        val text = target.contentResolver.acquireContentProviderClient(shared)!!.use { client ->
            val provider = client.localContentProvider as ArchiveProvider
            val gander = Binder.clearCallingIdentity()
            try {
                Binder.restoreCallingIdentity(((Process.myUid() + 1).toLong() shl 32) or Process.myPid().toLong())
                provider.openAssetFile(shared, "r").createInputStream().use { String(it.readBytes()) }
            } finally {
                Binder.restoreCallingIdentity(gander)
            }
        }
        assertThat(text).contains("A file inside a zip inside a zip")
    }

    /**
     * The code pages are Android's, built on ICU, and not the desktop JDK's the unit tests use,
     * so the names are read once more where they will be read for real.
     */
    @Test
    fun namesFromWindowsAndMacsReadAsNamesOnTheDevice() {
        mapOf(
            "names-gbk.zip" to listOf("季度报告", "照片"),
            "names-cp866.zip" to listOf("Документы", "Фото"),
            "names-sjis.zip" to listOf("資料"),
            "names-korean.zip" to listOf("문서", "사진"),
            "names-mac.zip" to listOf("Отчёт"),
        ).forEach { (fixture, folders) ->
            ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent(fixture)).use {
                assertThat(rows(it)).containsExactlyElementsIn(folders).inOrder()
            }
        }
    }

    /** Deflate64, which zlib and so Android cannot read, read by Gander's own inflater on ART. */
    @Test
    fun aDeflate64FileOpens() {
        openEntry("deflate64.zip", "short.txt").use {
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("Plain text, opened by the text viewer")))
        }
    }

    // ---------------------------------------------------------------
    // Under a password
    // ---------------------------------------------------------------

    /**
     * The password typed into the box and sent with the keyboard's own key, as a reader would.
     * Not Open: the keyboard comes up with the box, and the box moves above it while Espresso
     * is already tapping where it used to be. Open itself is pressed in ViewerActivityTest.
     */
    private fun unlockWith(password: String) {
        onView(isAssignableFrom(android.widget.EditText::class.java)).inRoot(isDialog())
            .perform(typeText(password), pressImeActionButton())
    }

    /**
     * The password is tried on the list's loader thread, which Espresso cannot see, so for a
     * moment after the key is pressed the box is still up and the UI looks idle. Asked about
     * the file's viewer in that moment, Espresso went looking for it inside the password box
     * and found nothing: seen on an Android 9 emulator straight after a cold boot, where the
     * try took nearly two seconds. The box did close and the file did open; the question was
     * just asked too soon. So wait for the file's own viewer to be in front first.
     */
    private fun waitForTheFileToOpen() = waitFor("the file to open") {
        var open = false
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            open = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .any { activity -> activity.intent?.data?.let { ArchiveProvider.isEntry(activity, it) } == true }
        }
        open.takeIf { it }
    }

    /**
     * The whole of it on a device: the box, the password tried against the file, and the file
     * decrypted into the pipe on its way to the text viewer.
     */
    @Test
    fun aFileUnderAPasswordOpensOnceItIsTyped() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("locked.zip")).use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("zipcrypto.txt"))).perform(click())
            unlockWith("gander")
            waitForTheFileToOpen()
            onWebView()
                .withElement(findElement(Locator.CSS_SELECTOR, "#content"))
                .check(webMatches(getText(), containsString("Plain text, opened by the text viewer")))
        }
    }

    /** A wrong one leaves the box up, saying so, and opens nothing. */
    @Test
    fun aWrongPasswordIsSaidToBeWrong() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("locked.zip")).use { scenario ->
            rows(scenario)
            onView(allOf(withId(R.id.title), withText("aes128.txt"))).perform(click())
            unlockWith("goose")
            val said = waitFor("the error") {
                var error: CharSequence? = null
                onView(isAssignableFrom(android.widget.EditText::class.java)).inRoot(isDialog())
                    .check { view, _ -> error = (view as android.widget.EditText).error }
                error?.toString()
            }
            assertThat(said).isEqualTo(target.getString(R.string.password_wrong))
            assertThat(scenario.state).isEqualTo(Lifecycle.State.RESUMED)
        }
    }

    /**
     * A photo under AES is still encrypted where it lies, so it can never be a window: it
     * reaches the tiling view through the pipe, decrypted on the way.
     */
    @Test
    fun anEncryptedPhotoOpensInTheTilingView() {
        ArchivePasswords.remember(DeviceFixtures.uriFor("locked.zip"), "gander")
        openEntry("locked.zip", "aes256.png").use { scenario ->
            val ready = waitFor("the photo") {
                var ready: Boolean? = null
                scenario.onActivity { activity ->
                    val container = activity.findViewById<FrameLayout>(R.id.container)
                    val view = (0 until container.childCount).map { container.getChildAt(it) }
                        .filterIsInstance<SubsamplingScaleImageView>().firstOrNull()
                    ready = view?.isReady?.takeIf { it }
                }
                ready
            }
            assertThat(ready).isTrue()
        }
    }

    /** pdf.js reading a PDF decrypted from AES-192 out of the pipe, every page of it. */
    @Test
    fun anEncryptedPdfRendersEveryPage() {
        ArchivePasswords.remember(DeviceFixtures.uriFor("locked.zip"), "gander")
        openEntry("locked.zip", "aes192.pdf").use { scenario ->
            val pages = waitFor("six pages") {
                WebViewProbe.text(scenario, "document.querySelectorAll('#pages .pg').length")
                    .takeIf { it == "6" }
            }
            assertThat(pages).isEqualTo("6")
        }
    }

    // ---------------------------------------------------------------
    // Pictures in the list
    // ---------------------------------------------------------------

    /** Whether [title]'s row is showing a picture rather than its badge, once there is one. */
    private fun showsAPicture(scenario: ActivityScenario<ViewerActivity>, title: String): Boolean =
        waitFor("a picture for $title") {
            var shown: Boolean? = null
            scenario.onActivity { activity ->
                val container = activity.findViewById<FrameLayout>(R.id.container)
                val rv = (0 until container.childCount).map { container.getChildAt(it) }
                    .filterIsInstance<RecyclerView>().firstOrNull() ?: return@onActivity
                val row = (0 until rv.childCount).map { rv.getChildAt(it) }
                    .firstOrNull { it.findViewById<TextView>(R.id.title)?.text == title } ?: return@onActivity
                shown = (row.findViewById<android.widget.ImageView>(R.id.thumb).visibility == android.view.View.VISIBLE)
                    .takeIf { it }
            }
            shown
        }

    /** A stored photo's picture comes through a window onto the archive. */
    @Test
    fun aPhotoInAZipShowsItsPicture() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("archive.zip")).use {
            rows(it)
            onView(allOf(withId(R.id.title), withText("photos"))).perform(click())
            assertThat(showsAPicture(it, "tiny.png")).isTrue()
        }
    }

    /**
     * And a photo under a password gets its picture once the password is known, decrypted
     * through the pipe, under either scheme.
     */
    @Test
    fun anEncryptedPhotoShowsItsPictureOnceUnlocked() {
        ArchivePasswords.remember(DeviceFixtures.uriFor("locked.zip"), "gander")
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("locked.zip")).use {
            rows(it)
            assertThat(showsAPicture(it, "aes256.png")).isTrue()
            assertThat(showsAPicture(it, "zipcrypto.png")).isTrue()
        }
    }

    // ---------------------------------------------------------------
    // The code page names are read in
    // ---------------------------------------------------------------

    /**
     * Chosen by hand from the menu, on the device's own code pages: GBK names read as Big5
     * come out as other characters, and choosing Automatic again brings them back.
     */
    @Test
    fun choosingACodePageRereadsTheNames() {
        ActivityScenario.launch<ViewerActivity>(DeviceFixtures.viewIntent("names-gbk.zip")).use { scenario ->
            assertThat(rows(scenario)).containsExactly("季度报告", "照片").inOrder()
            fun choose(label: String) {
                scenario.onActivity { activity ->
                    activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.toolbar)
                        .menu.performIdentifierAction(R.id.action_name_encoding, 0)
                }
                // By the adapter rather than the view: the list opens scrolled to the choice
                // already made, which leaves the ones above it out of the hierarchy
                onData(hasToString(label)).inRoot(isDialog()).perform(click())
            }
            choose(target.getString(R.string.encoding_big5))
            val big5 = String("季度报告".toByteArray(charset("GBK")), charset("Big5"))
            assertThat(waitFor("names read as Big5") { rows(scenario).takeIf { big5 in it } }).contains(big5)
            choose(target.getString(R.string.name_encoding_automatic_as, target.getString(R.string.encoding_gbk)))
            assertThat(waitFor("names read as GBK again") { rows(scenario).takeIf { "季度报告" in it } })
                .containsExactly("季度报告", "照片").inOrder()
        }
    }

    /** Only Gander's own list can open one of these. See ViewerActivity.INTERNAL_VIEWER. */
    @Test
    fun aFileInsideAZipIsRefusedFromOutsideTheList() {
        val intent = Intent(target, ViewerActivity::class.java)
            .setAction(Intent.ACTION_VIEW)
            .setData(entryUri("archive.zip", "plain.txt"))
        ActivityScenario.launch<ViewerActivity>(intent).use {
            assertThat(it.state).isEqualTo(Lifecycle.State.DESTROYED)
        }
    }
}

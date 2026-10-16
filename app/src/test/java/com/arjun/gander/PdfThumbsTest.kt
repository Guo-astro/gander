package com.arjun.gander

import android.app.Application
import android.content.ComponentName
import android.content.ContextWrapper
import android.content.Intent
import android.content.ServiceConnection
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.DeadObjectException
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.time.Duration
import java.util.Collections
import java.util.concurrent.CountDownLatch
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * PDF thumbnails, against a renderer in the test's own process standing in for PdfThumbService:
 * the picture is the renderer's first page, a file it drew nothing of isn't handed over again,
 * and the renderer is let go once no picture is waiting.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PdfThumbsTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val renderer = FakeRenderer()
    private val looper get() = shadowOf(Looper.getMainLooper())

    // Each binding and unbinding of the renderer, in the order the main thread made them
    private val log: MutableList<String> = Collections.synchronizedList(mutableListOf())
    private val context = object : ContextWrapper(app) {
        override fun bindService(service: Intent, conn: ServiceConnection, flags: Int): Boolean {
            log += "bind"
            return super.bindService(service, conn, flags)
        }

        override fun unbindService(conn: ServiceConnection) {
            log += "unbind"
            super.unbindService(conn)
        }
    }
    private val thumbs = PdfThumbs(context, 192)

    @Before
    fun setUp() {
        FixtureProvider.install()
        // For PdfThumbService's intent alone: without one, Robolectric answers every binding,
        // the viewer's renderer's included, with the same stand-in
        val service = ComponentName(app, PdfThumbService::class.java)
        shadowOf(app).setComponentNameAndServiceForBindServiceForIntent(
            Intent().setComponent(service), service, renderer
        )
    }

    @After
    fun tearDown() {
        thumbs.shutdown()
    }

    /** Runs the main thread until [done], in real time, since the lane runs in it. */
    private fun waitFor(done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < deadline) { "still waiting" }
            looper.idle()
            Thread.sleep(10)
        }
        looper.idle()
    }

    /** Asks for a fixture PDF's picture under [key], as Thumbs asks, and gives a wait for it. */
    private fun ask(key: String): () -> Bitmap? {
        val done = CountDownLatch(1)
        var picture: Bitmap? = null
        thumbs.queue {
            try {
                picture = thumbs.draw(FixtureProvider.uriFor("six-pages.pdf"), key)
            } finally {
                done.countDown()
            }
        }
        return {
            waitFor { done.count == 0L }
            picture
        }
    }

    private fun draw(key: String): Bitmap? = ask(key)()

    @Test
    fun aThumbnailIsTheFirstPageAtThumbnailWidth() {
        val picture = draw("a4")!!
        // A4 is 595 by 842 points
        assertThat(listOf(picture.width, picture.height)).containsExactly(192, 271).inOrder()
        assertThat(renderer.drawn).containsExactly("0 192x271")
    }

    /** However the renderer says the page is shaped, the picture stays a thumbnail. */
    @Test
    fun aPageOfAnyShapeIsDrawnNoTallerThanTwiceItsWidth() {
        renderer.size = (1L shl 32) or 1_000_000L
        val picture = draw("ribbon")!!
        assertThat(listOf(picture.width, picture.height)).containsExactly(192, 384).inOrder()
    }

    @Test
    fun thePixelsAreTheRenderersBlueGreenRedAndAlphaBytes() {
        val bytes = byteArrayOf(0x10, 0x20, 0x30, -1, 0x40, 0x50, 0x60, -1)
        val picture = pageBitmap(bytes, 2, 1)
        assertThat(picture.getPixel(0, 0)).isEqualTo(0xff302010.toInt())
        assertThat(picture.getPixel(1, 0)).isEqualTo(0xff605040.toInt())
    }

    /**
     * A renderer that dies on a file twice, as PDFium might on a hostile one, costs that file its
     * picture and nothing more: it isn't handed over again, and each try and the next file get a
     * renderer of their own, bound once the dead one is unbound.
     */
    @Test
    fun aFileTheRendererDiedOnIsNotHandedOverAgain() {
        renderer.onOpen = { throw DeadObjectException() }
        assertThat(draw("hostile")).isNull()
        assertThat(renderer.opens.get()).isEqualTo(2)
        assertThat(draw("hostile")).isNull()
        assertThat(renderer.opens.get()).isEqualTo(2)

        renderer.onOpen = {}
        assertThat(draw("next")).isNotNull()
        assertThat(renderer.opens.get()).isEqualTo(3)
        assertThat(log).containsExactly("bind", "unbind", "bind", "unbind", "bind").inOrder()
    }

    /** A renderer that died during a file for some other reason costs that file nothing. */
    @Test
    fun aFileIsNotBlamedForOneDeath() {
        var opens = 0
        renderer.onOpen = { if (opens++ == 0) throw DeadObjectException() }
        assertThat(draw("unlucky")).isNotNull()
        assertThat(renderer.opens.get()).isEqualTo(2)
        assertThat(log).containsExactly("bind", "unbind", "bind").inOrder()
    }

    /**
     * A renderer stuck on a file is given up after half the viewer's wait, and the file isn't
     * tried again, since everything behind it would wait as long a second time.
     */
    @Test
    fun aFileTheRendererHungOnIsGivenUpSoonerAndNotRetried() {
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        val stuck = ask("stuck")
        waitFor { renderer.entered.count == 0L }
        looper.idleFor(Duration.ofMillis(PdfThumbs.HUNG_MS - 2_000))
        assertThat(log).containsExactly("bind")
        looper.idleFor(Duration.ofSeconds(3))
        waitFor { log.size == 2 }
        // Its process ended on the unbind, so the pipe ends with nothing in it
        renderer.dead = true
        renderer.bytes = { 0 }
        renderer.release.countDown()
        assertThat(stuck()).isNull()
        assertThat(renderer.opens.get()).isEqualTo(1)

        renderer.dead = false
        renderer.bytes = { it }
        assertThat(draw("stuck")).isNull()
        assertThat(draw("next")).isNotNull()
        assertThat(renderer.opens.get()).isEqualTo(2)
    }

    /** A locked file is asked about once, and the renderer that answered is kept. */
    @Test
    fun aFileTheRendererRefusedIsNotHandedOverAgain() {
        renderer.pages = PdfRenderService.OPEN_PASSWORD
        assertThat(draw("locked")).isNull()
        assertThat(draw("locked")).isNull()
        assertThat(renderer.opens.get()).isEqualTo(1)

        renderer.pages = 3
        assertThat(draw("open")).isNotNull()
        assertThat(log).containsExactly("bind")
    }

    /** Kept while pictures keep coming, and let go, which ends its process, once they stop. */
    @Test
    fun theRendererIsLetGoOnceNoPictureHasBeenWaitedForAWhile() {
        draw("first")
        looper.idleFor(Duration.ofMillis(PdfThumbs.IDLE_MS - 1_000))
        // Still drawing when the renderer would have been let go after the first
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        val slow = ask("slow")
        waitFor { renderer.entered.count == 0L }
        looper.idleFor(Duration.ofSeconds(2))
        renderer.release.countDown()
        assertThat(slow()).isNotNull()
        draw("next")
        looper.idleFor(Duration.ofMillis(PdfThumbs.IDLE_MS - 1_000))
        assertThat(log).containsExactly("bind")

        looper.idleFor(Duration.ofSeconds(2))
        waitFor { log.size == 2 }
        assertThat(log).containsExactly("bind", "unbind").inOrder()

        assertThat(draw("later")).isNotNull()
        assertThat(log).containsExactly("bind", "unbind", "bind").inOrder()
    }

    @Test
    fun bothRenderersAreIsolatedAndUnexported() {
        listOf(PdfRenderService::class.java, PdfThumbService::class.java).forEach {
            val info = app.packageManager.getServiceInfo(ComponentName(app, it), 0)
            assertThat(info.flags and ServiceInfo.FLAG_ISOLATED_PROCESS).isNotEqualTo(0)
            assertThat(info.exported).isFalse()
        }
    }
}

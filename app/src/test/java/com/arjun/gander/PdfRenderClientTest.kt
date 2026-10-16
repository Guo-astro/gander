package com.arjun.gander

import android.app.Application
import android.content.ComponentName
import android.content.Intent
import android.os.DeadObjectException
import android.os.Looper
import android.os.ParcelFileDescriptor
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowSystemClock

/**
 * Gander's side of the isolated renderer, against a renderer in the test's own process: what it
 * hands back is checked, and a renderer that dies, hangs or lies ends in one report, not a crash.
 *
 * A local binder runs each call on the caller's thread, so a page is written before the client
 * reads it, which suits Robolectric, whose pipes are files.
 */
@RunWith(AndroidJUnit4::class)
class PdfRenderClientTest {

    private val app = ApplicationProvider.getApplicationContext<Application>()
    private val component = ComponentName(app, PdfRenderService::class.java)
    private val renderer = FakeRenderer()
    private val gone = mutableListOf<Boolean>()
    private val client = PdfRenderClient(app, Intent(app, PdfRenderService::class.java)) { gone += it }
    private val pool = Executors.newCachedThreadPool()
    private val looper get() = shadowOf(Looper.getMainLooper())

    @Before
    fun setUp() {
        shadowOf(app).setComponentNameAndServiceForBindService(component, renderer)
        mayHandOver = { true }
    }

    @After
    fun tearDown() {
        renderer.release.countDown()
        pool.shutdownNow()
        mayHandOver = ::readOnlyFile
    }

    private fun <T> off(call: () -> T): Future<T> = pool.submit(Callable(call))

    private fun <T> Future<T>.answer(): T = get(5, TimeUnit.SECONDS)

    private fun document(): ParcelFileDescriptor {
        val file = File.createTempFile("document", ".pdf").apply { writeText("%PDF-1.7\n") }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    /** Bound, with a document of the fake's pages open. */
    private fun opened(): Int {
        client.bind()
        val pages = off { client.open(document()) }
        looper.idle()
        return pages.answer()
    }

    private fun unbound() = shadowOf(app).unboundServiceConnections

    // ---------------------------------------------------------------
    // What a renderer answers
    // ---------------------------------------------------------------

    @Test
    fun aDocumentOpensAndDrawsOnceTheRendererIsBound() {
        assertThat(opened()).isEqualTo(3)
        assertThat(off { client.pageSize(2) }.answer()!!.toList()).containsExactly(595, 842).inOrder()
        val page = off { client.render(1, 10, 20) }.answer()
        assertThat(page).isEqualTo(FakeRenderer.page(4 * 10 * 20))
        assertThat(renderer.drawn).containsExactly("1 10x20")
        assertThat(gone).isEmpty()
    }

    /** A page outside the document, or a size past the renderer's caps, is never asked for. */
    @Test
    fun requestsNoRendererWouldDrawAreNeverSent() {
        opened()
        val refused = off {
            listOf(
                client.pageSize(3), client.pageSize(-1), client.render(3, 10, 10),
                client.render(-1, 10, 10), client.render(0, 0, 10),
                client.render(0, PdfRenderService.MAX_SIDE + 1, 10),
                client.render(0, 4000, 4000),
            )
        }.answer()
        assertThat(refused).containsExactly(null, null, null, null, null, null, null)
        assertThat(renderer.drawn).isEmpty()
        assertThat(renderer.sized.get()).isEqualTo(0)
    }

    @Test
    fun aCountOrCodeNoRendererGivesIsADamagedFile() {
        client.bind()
        looper.idle()
        fun openWith(pages: Int): Int {
            renderer.pages = pages
            return off { client.open(document()) }.answer()
        }
        assertThat(openWith(0)).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        assertThat(openWith(PdfRenderClient.MAX_PAGES + 1)).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        assertThat(openWith(-99)).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        assertThat(openWith(PdfRenderClient.GONE)).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        assertThat(openWith(PdfRenderService.OPEN_PASSWORD)).isEqualTo(PdfRenderService.OPEN_PASSWORD)
        assertThat(openWith(PdfRenderService.OPEN_NOT_PDF)).isEqualTo(PdfRenderService.OPEN_NOT_PDF)
        // A failed open leaves no pages to ask for
        assertThat(off { client.render(0, 10, 10) }.answer()).isNull()
        assertThat(gone).isEmpty()
    }

    @Test
    fun aPageSizeNoPageHasIsNoSize() {
        opened()
        renderer.size = -1L
        assertThat(off { client.pageSize(0) }.answer()).isNull()
        renderer.size = 842L
        assertThat(off { client.pageSize(0) }.answer()).isNull()
    }

    @Test
    fun aPipeThatEndsEarlyOrRunsOverFromALiveRendererIsNoPage() {
        opened()
        renderer.bytes = { it - 1 }
        assertThat(off { client.render(0, 10, 20) }.answer()).isNull()
        renderer.bytes = { it + 1 }
        assertThat(off { client.render(0, 10, 20) }.answer()).isNull()
        looper.idle()
        assertThat(gone).isEmpty()
    }

    /**
     * A call with a reply would let the renderer choose what Gander reads back, and what Android
     * rethrows on Gander's thread. Every call is oneway, and answers as bytes on a pipe.
     */
    @Test
    fun everyCallIsOnewaySoTheRendererWritesNoReply() {
        opened()
        off { client.pageSize(0); client.render(0, 10, 20) }.answer()
        assertThat(listOf(renderer.opens.get(), renderer.sized.get(), renderer.drawn.size))
            .containsExactly(1, 1, 1)
        assertThat(renderer.replied).isEmpty()
    }

    /** An answer a byte short or over, or none, from a renderer still there, is no answer. */
    @Test
    fun anAnswerOfTheWrongLengthIsNone() {
        client.bind()
        looper.idle()
        renderer.bytes = { it - 1 }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        renderer.bytes = { it + 1 }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        renderer.bytes = { it }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(3)
        renderer.bytes = { it - 1 }
        assertThat(off { client.pageSize(0) }.answer()).isNull()
        renderer.bytes = { it }
        renderer.onOpen = { throw IllegalStateException("from the renderer") }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderService.OPEN_DAMAGED)
        looper.idle()
        assertThat(gone).isEmpty()
    }

    /**
     * A descriptor the renderer could write through, or a pipe or socket, is never handed over.
     * Which ones those are, Robolectric can't tell, so the check itself runs on a device.
     */
    @Test
    fun aDescriptorTheCheckRefusesIsNeverHandedOver() {
        client.bind()
        looper.idle()
        mayHandOver = { false }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderService.OPEN_UNREADABLE)
        assertThat(renderer.opens.get()).isEqualTo(0)
        mayHandOver = { true }
        assertThat(off { client.open(document()) }.answer()).isEqualTo(3)
    }

    // ---------------------------------------------------------------
    // A renderer that goes
    // ---------------------------------------------------------------

    @Test
    fun aRendererThatCannotBeBoundIsGoneAtOnce() {
        shadowOf(app).declareComponentUnbindable(component)
        client.bind()
        looper.idle()
        assertThat(gone).containsExactly(true)
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderClient.GONE)
    }

    /** It died on the file, and is reported once however many ways its death is seen. */
    @Test
    fun aRendererThatDiesDuringACallHasCrashed() {
        renderer.onOpen = { throw DeadObjectException() }
        assertThat(opened()).isEqualTo(PdfRenderClient.GONE)
        looper.idle()
        assertThat(gone).containsExactly(true)
        assertThat(client.hung).isFalse()
        // Unbound, so that Android doesn't start another renderer in its place
        assertThat(unbound()).hasSize(1)
        shadowOf(app).boundServiceConnections.forEach { it.onServiceDisconnected(component) }
        unbound().forEach { it.onServiceDisconnected(component) }
        looper.idle()
        assertThat(gone).containsExactly(true)
    }

    @Test
    fun aPipeThatEndsEarlyFromARendererThatDiedIsACrash() {
        opened()
        renderer.bytes = { it / 2 }
        renderer.onRender = { renderer.dead = true }
        assertThat(off { client.render(0, 10, 20) }.answer()).isNull()
        looper.idle()
        assertThat(gone).containsExactly(true)
        assertThat(off { client.pageSize(0) }.answer()).isNull()
    }

    /** Android ended a renderer that sat idle, to free memory. Nothing more is asked of it. */
    @Test
    fun aRendererThatDiesIdleWasReclaimed() {
        opened()
        shadowOf(app).boundServiceConnections.single().onServiceDisconnected(component)
        looper.idle()
        assertThat(gone).containsExactly(false)
        val opens = renderer.opens.get()
        assertThat(off { client.open(document()) }.answer()).isEqualTo(PdfRenderClient.GONE)
        assertThat(renderer.opens.get()).isEqualTo(opens)
    }

    @Test
    fun closingEndsWhatWaitsAndReportsNothing() {
        client.bind()
        // Never connected, so the open waits until the close
        val pages = off { client.open(document()) }
        Thread.sleep(100)
        client.close()
        assertThat(pages.answer()).isEqualTo(PdfRenderClient.GONE)
        looper.idle()
        assertThat(gone).isEmpty()
        assertThat(unbound()).hasSize(1)
    }

    @Test
    fun theRendererIsNeverAskedOnTheMainThread() {
        opened()
        assertThrows(IllegalStateException::class.java) { client.pageSize(0) }
    }

    // ---------------------------------------------------------------
    // A renderer that hangs
    // ---------------------------------------------------------------

    /** Stuck in a page, as PDFium can be on a crafted file: given up, and unbound, which ends it. */
    @Test
    fun aRendererThatHangsIsGivenUpAfterTwentySeconds() {
        opened()
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        val page = off { client.render(0, 10, 20) }
        assertThat(renderer.entered.await(5, TimeUnit.SECONDS)).isTrue()
        looper.idleFor(Duration.ofMillis(PdfRenderClient.HUNG_MS - 3_000))
        assertThat(gone).isEmpty()
        looper.idleFor(Duration.ofSeconds(5))
        assertThat(gone).containsExactly(true)
        assertThat(client.hung).isTrue()
        assertThat(unbound()).hasSize(1)
        // Its process ended, so the pipe ends with nothing in it
        renderer.dead = true
        renderer.bytes = { 0 }
        renderer.release.countDown()
        assertThat(page.answer()).isNull()
    }

    /**
     * Frozen in the background, Gander and the renderer thaw in no set order, and the watch finds
     * minutes gone. Only the time Gander spends running counts towards a hang.
     */
    @Test
    fun timeGanderSpentFrozenIsNotCountedAsAHang() {
        opened()
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        off { client.render(0, 10, 20) }
        assertThat(renderer.entered.await(5, TimeUnit.SECONDS)).isTrue()
        looper.idleFor(Duration.ofSeconds(2))
        ShadowSystemClock.advanceBy(Duration.ofMinutes(5))
        looper.idle()
        assertThat(gone).isEmpty()
        looper.idleFor(Duration.ofMillis(PdfRenderClient.HUNG_MS - 8_000))
        assertThat(gone).isEmpty()
        looper.idleFor(Duration.ofSeconds(8))
        assertThat(gone).containsExactly(true)
    }

    /** Pages queue behind one another in the renderer, so a hang is a renderer answering nothing. */
    @Test
    fun aRendererStillAnsweringHasNotHung() {
        opened()
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        off { client.render(0, 10, 20) }
        assertThat(renderer.entered.await(5, TimeUnit.SECONDS)).isTrue()
        repeat(6) {
            looper.idleFor(Duration.ofSeconds(5))
            assertThat(off { client.pageSize(0) }.answer()).isNotNull()
        }
        assertThat(gone).isEmpty()
        looper.idleFor(Duration.ofMillis(PdfRenderClient.HUNG_MS + 2_000))
        assertThat(gone).containsExactly(true)
    }
}

package com.arjun.gander

import android.app.Application
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import android.view.View
import android.widget.ImageView
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.Locale
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.GraphicsMode

/**
 * Thumbnails: which files get one, and which way up.
 *
 * Native graphics, so the EXIF fixtures are decoded rather than stubbed.
 */
@RunWith(AndroidJUnit4::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ThumbsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Thumbs.resetForTests()
        File(context.cacheDir, "thumbs").deleteRecursively()
        // Robolectric can't say a descriptor is a file open for reading: see mayHandOver
        mayHandOver = { true }
    }

    @After
    fun tearDown() {
        mayHandOver = ::readOnlyFile
    }

    // ---------------------------------------------------------------
    // Which kinds get a preview at all
    // ---------------------------------------------------------------

    @Test
    fun thingsWithAPictureInThemGetAThumbnail() {
        assertThat(Thumbs.supported(FileKind.IMAGE, "jpg")).isTrue()
        assertThat(Thumbs.supported(FileKind.IMAGE_WEB, "gif")).isTrue()
        assertThat(Thumbs.supported(FileKind.PDF, "pdf")).isTrue()
        assertThat(Thumbs.supported(FileKind.PLAYER, "mp4")).isTrue()
    }

    /**
     * Audio is the exception inside PLAYER: there is no frame to grab, and a
     * blank grey square says less than the AUD badge it would replace.
     */
    @Test
    fun audioKeepsItsBadgeInsteadOfABlankFrame() {
        listOf("mp3", "m4a", "flac", "wav", "ogg", "opus", "amr", "aac", "oga")
            .forEach { assertThat(Thumbs.supported(FileKind.PLAYER, it)).isFalse() }
    }

    @Test
    fun documentsAndTextKeepTheirBadges() {
        listOf(
            FileKind.DOCX, FileKind.PROSE, FileKind.XLSX, FileKind.PPTX,
            FileKind.MD, FileKind.TEXT, FileKind.MODEL, FileKind.EPUB, FileKind.UNSUPPORTED,
        ).forEach { assertThat(Thumbs.supported(it, "")).isFalse() }
    }

    /**
     * Inside a zip: photos however they are packed, video only as a window, never a PDF. The
     * fixture's photo is stored and its PDF compressed, so the stored cases are built here.
     */
    @Test
    fun insideAZipOnlyWhatCanBeReadCheaplyGetsOne() {
        val stored = EntryLocation(0, ZipReader.METHOD_STORED, 1, 1, 0)
        val deflated = EntryLocation(0, ZipReader.METHOD_DEFLATED, 1, 1, 0)
        val locked = EntryLocation(0, ZipReader.METHOD_STORED, 1, 1, 0, Lock.AES256)
        assertThat(Thumbs.supportedInArchive(FileKind.IMAGE, "jpg", deflated)).isTrue()
        assertThat(Thumbs.supportedInArchive(FileKind.IMAGE, "jpg", locked)).isTrue()
        assertThat(Thumbs.supportedInArchive(FileKind.PLAYER, "mp4", stored)).isTrue()
        assertThat(Thumbs.supportedInArchive(FileKind.PLAYER, "mp4", deflated)).isFalse()
        assertThat(Thumbs.supportedInArchive(FileKind.PLAYER, "mp4", locked)).isFalse()
        assertThat(Thumbs.supportedInArchive(FileKind.PLAYER, "mp3", stored)).isFalse()
        assertThat(Thumbs.supportedInArchive(FileKind.PDF, "pdf", stored)).isFalse()
    }

    /** Every kind is decided one way or the other; none of them throws. */
    @Test
    fun everyKindHasAnAnswer() {
        FileKind.entries.forEach { Thumbs.supported(it, "bin") }
    }

    // ---------------------------------------------------------------
    // EXIF rotation
    // ---------------------------------------------------------------

    private fun rotationOf(fixture: String): Int {
        val uri = FixtureProvider.uriFor(fixture)
        return Thumbs.exifRotation(context.contentResolver, uri)
    }

    @Test
    fun anUnrotatedPhotoIsLeftAlone() {
        assertThat(rotationOf("exif-1.jpg")).isEqualTo(0)
    }

    @Test
    fun theThreeQuarterTurnsAreRead() {
        assertThat(rotationOf("exif-6.jpg")).isEqualTo(90)
        assertThat(rotationOf("exif-3.jpg")).isEqualTo(180)
        assertThat(rotationOf("exif-8.jpg")).isEqualTo(270)
    }

    /**
     * Transpose and transverse are a rotation plus a mirror. Gander drops the
     * mirror and keeps the rotation, which puts the photo the right way up
     * even though it is not strictly what the tag asked for. A mirrored
     * portrait is a far smaller wrong than a sideways one.
     */
    @Test
    fun theMirroredOrientationsKeepTheirRotation() {
        assertThat(rotationOf("exif-5.jpg")).isEqualTo(90)
        assertThat(rotationOf("exif-7.jpg")).isEqualTo(270)
    }

    @Test
    fun aPhotoWithNoExifIsNotRotated() {
        assertThat(rotationOf("tiny.png")).isEqualTo(0)
    }

    /** Asked about something that is not an image at all. */
    @Test
    fun aNonImageIsNotRotated() {
        assertThat(rotationOf("plain.txt")).isEqualTo(0)
        assertThat(rotationOf("six-pages.pdf")).isEqualTo(0)
    }

    /**
     * A provider that throws must give an upright photo, not a crash. This is
     * called while binding a row, so it runs for every file on screen.
     */
    @Test
    fun aProviderThatThrowsGivesNoRotation() {
        val uri = FixtureProvider.uriFor(FixtureProvider.BROKEN)
        assertThat(Thumbs.exifRotation(context.contentResolver, uri)).isEqualTo(0)
    }

    // ---------------------------------------------------------------
    // The disk cache
    // ---------------------------------------------------------------

    /** The same key derivation Thumbs uses, so the file can be found. */
    private fun cacheFileFor(uriString: String): File {
        val key = MessageDigest.getInstance("MD5")
            .digest(uriString.toByteArray())
            .joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, "thumbs"), "$key.png")
    }

    @Test
    fun evictingAFileRemovesItsCachedThumbnail() {
        val uri = FixtureProvider.uriFor("exif-1.jpg").toString()
        val cached = cacheFileFor(uri).apply {
            parentFile?.mkdirs()
            writeBytes(Fixtures.bytes("tiny.png"))
        }
        assertThat(cached.exists()).isTrue()

        Thumbs.evict(context, uri)

        assertThat(cached.exists()).isFalse()
    }

    @Test
    fun evictingSomethingNeverCachedIsHarmless() {
        Thumbs.evict(context, "content://elsewhere/never-seen")
    }

    /** Loads [uri]'s thumbnail the way a row does, and waits for it to be drawn. */
    private fun loadAndWait(uri: Uri, ext: String = "png") {
        // Hidden, as a row's is until its picture arrives: a new ImageView starts out visible,
        // and waiting for that would wait for nothing
        val into = ImageView(context).apply { visibility = View.GONE }
        val badge = View(context)
        Thumbs.load(context, uri, ext, into, badge)
        idleUntil("no thumbnail for $uri") { into.visibility == View.VISIBLE }
    }

    /**
     * Runs the main thread until [done], for up to ten seconds of real time, not SystemClock:
     * Robolectric's clock stands still unless a test moves it, so a deadline on that one never
     * arrives and a thumbnail that never comes hangs the run rather than failing it.
     */
    private fun idleUntil(what: String, done: () -> Boolean) {
        val deadline = System.nanoTime() + 10_000_000_000
        while (!done()) {
            check(System.nanoTime() < deadline) { what }
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
    }

    private fun cached(): List<String> =
        File(context.cacheDir, "thumbs").listFiles().orEmpty().map { it.name }

    /**
     * A PDF's is drawn by the thumbnail renderer, never by PDFium in Gander's own process, and
     * kept on disk like a photo's.
     */
    @Test
    fun aPdfIsDrawnByItsOwnRenderer() {
        val renderer = thumbnailRenderer()
        val uri = FixtureProvider.uriFor("six-pages.pdf")
        loadAndWait(uri, "pdf")
        assertThat(renderer.drawn).containsExactly("0 192x271")
        assertThat(cached()).containsExactly(cacheFileFor(uri.toString()).name)
    }

    /** Two PDFs asked for together are drawn in turn, because the renderer holds one document. */
    @Test
    fun pdfsAreDrawnOneAtATime() {
        val renderer = thumbnailRenderer()
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        val rows = listOf("one.pdf", "two.pdf").map { name ->
            ImageView(context).apply { visibility = View.GONE }.also {
                Thumbs.load(context, FixtureProvider.uriNamed("six-pages.pdf", name), "pdf", it, View(context))
            }
        }
        idleUntil("the first PDF was never drawn") { renderer.entered.count == 0L }
        // Time for a second to open beside it, were it allowed to
        Thread.sleep(300)
        assertThat(renderer.opens.get()).isEqualTo(1)

        renderer.release.countDown()
        idleUntil("a PDF was never drawn") { rows.all { it.visibility == View.VISIBLE } }
        assertThat(renderer.opens.get()).isEqualTo(2)
    }

    /** A PDF whose thumbnail is on disk already doesn't wait while the renderer draws another. */
    @Test
    fun aPdfKeptOnDiskDoesNotWaitForTheRenderer() {
        val renderer = thumbnailRenderer()
        renderer.onRender = { renderer.entered.countDown(); renderer.release.await() }
        val drawing = ImageView(context).apply { visibility = View.GONE }
        Thumbs.load(context, FixtureProvider.uriNamed("six-pages.pdf", "drawing.pdf"), "pdf", drawing, View(context))
        idleUntil("the first PDF was never drawn") { renderer.entered.count == 0L }

        val kept = FixtureProvider.uriNamed("six-pages.pdf", "kept.pdf")
        cacheFileFor(kept.toString()).apply {
            parentFile?.mkdirs()
            writeBytes(Fixtures.bytes("tiny.png"))
        }
        loadAndWait(kept, "pdf")
        assertThat(drawing.visibility).isEqualTo(View.GONE)
        renderer.release.countDown()
    }

    /** A stand-in for PdfThumbService, bound in its place and nothing else's. */
    private fun thumbnailRenderer(): FakeRenderer = FakeRenderer().also {
        val service = ComponentName(context, PdfThumbService::class.java)
        shadowOf(context as Application).setComponentNameAndServiceForBindServiceForIntent(
            Intent().setComponent(service), service, it
        )
    }

    /** The control for the next: a photo on the phone is kept on disk, so the check can see one. */
    @Test
    fun aPhotoOnThePhoneIsKeptOnDisk() {
        FixtureProvider.install()
        val uri = FixtureProvider.uriFor("tiny.png")
        loadAndWait(uri)
        assertThat(cached()).containsExactly(cacheFileFor(uri.toString()).name)
    }

    /**
     * A photo inside a zip is drawn and never written: nothing in a zip reaches storage, and a
     * thumbnail on disk would be a copy of the photo, only smaller.
     */
    @Test
    fun aPhotoInsideAZipIsNeverKeptOnDisk() {
        FixtureProvider.install()
        Robolectric.buildContentProvider(ArchiveProvider::class.java).create(ArchiveProvider.authority(context))
        val raf = RandomAccessFile(Fixtures.file("archive.zip"), "r")
        val entry = ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, Locale.US).single { e -> e.path == "photos/tiny.png" }
        }
        loadAndWait(ArchiveProvider.uriFor(context, FixtureProvider.uriFor("archive.zip"), entry))
        assertThat(cached()).isEmpty()
    }

    /**
     * Thumbnails live in the cache directory, which Android is free to empty
     * at any time. Nothing here may be the only copy of anything.
     */
    @Test
    fun thumbnailsAreKeptOnlyInTheCacheDirectory() {
        val uri = FixtureProvider.uriFor("exif-1.jpg").toString()
        assertThat(cacheFileFor(uri).canonicalPath)
            .startsWith(context.cacheDir.canonicalPath)
    }
}

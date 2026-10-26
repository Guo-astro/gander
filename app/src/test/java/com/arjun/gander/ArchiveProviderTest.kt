package com.arjun.gander

import android.content.Context
import android.content.res.AssetFileDescriptor
import android.net.Uri
import android.os.Process
import android.provider.OpenableColumns
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.FileNotFoundException
import java.io.RandomAccessFile
import java.util.Locale
import org.junit.After
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.shadows.ShadowBinder

/**
 * The provider that hands a file inside a zip to a viewer. Issue #30.
 *
 * The archive itself is served by FixtureProvider, standing in for the app that handed
 * Gander the zip, so everything here goes through a ContentResolver the way a viewer's
 * reads do. A compressed file is served through a pipe, which Robolectric imitates with a
 * file and cannot be trusted to time the way a pipe does; that path is read on a device, in
 * ArchiveDeviceTest, and inflating itself is ZipReaderTest's.
 */
@RunWith(AndroidJUnit4::class)
class ArchiveProviderTest {

    private lateinit var context: Context
    private val archive = FixtureProvider.uriFor("archive.zip")

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        Robolectric.buildContentProvider(ArchiveProvider::class.java)
            .create(ArchiveProvider.authority(context))
    }

    @After
    fun tearDown() {
        ShadowBinder.reset()
        ArchivePasswords.forgetAll()
    }

    private fun entry(path: String, fixture: String = "archive.zip"): ArchiveEntry {
        val raf = RandomAccessFile(Fixtures.file(fixture), "r")
        return ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, Locale.US).single { e -> e.path == path }
        }
    }

    private fun uriOf(path: String): Uri = ArchiveProvider.uriFor(context, archive, entry(path))

    private val locked = FixtureProvider.uriFor("locked.zip")

    private fun lockedUri(path: String): Uri = ArchiveProvider.uriFor(context, locked, entry(path, "locked.zip"))

    // ---------------------------------------------------------------
    // The URI
    // ---------------------------------------------------------------

    @Test
    fun aUriSaysEverythingNeededToServeItsFile() {
        val entry = entry("reports/six-pages.pdf")
        val ref = ArchiveProvider.parse(ArchiveProvider.uriFor(context, archive, entry))!!
        assertThat(ref.archive).isEqualTo(archive)
        assertThat(ref.name).isEqualTo("six-pages.pdf")
        assertThat(ref.location.headerOffset).isEqualTo(entry.location.headerOffset)
        assertThat(ref.location.method).isEqualTo(entry.location.method)
        assertThat(ref.location.compressedSize).isEqualTo(entry.location.compressedSize)
        assertThat(ref.location.size).isEqualTo(entry.location.size)
        assertThat(ref.location.crc).isEqualTo(entry.location.crc)
        assertThat(ref.location.lock).isEqualTo(Lock.NONE)
    }

    /** A name in any script, with spaces, and an archive URI with a query of its own. */
    @Test
    fun namesAndArchiveUrisSurviveTheRoundTrip() {
        val entry = ArchiveEntry(
            path = "季度报告/会议 记录 (final).pdf",
            isDirectory = false,
            modified = 0,
            location = EntryLocation(12, 8, 34, 56, 0xFFFFFFFFL, Lock.AES256),
        )
        val odd = Uri.parse("content://test.fixtures/archive.zip?name=Q3%20pack.zip")
        val ref = ArchiveProvider.parse(ArchiveProvider.uriFor(context, odd, entry))!!
        assertThat(ref.name).isEqualTo("会议 记录 (final).pdf")
        assertThat(ref.archive).isEqualTo(odd)
        assertThat(ref.location.crc).isEqualTo(0xFFFFFFFFL)
        assertThat(ref.location.lock).isEqualTo(Lock.AES256)
    }

    @Test
    fun anythingElseIsNotOneOfItsUris() {
        val base = "content://${ArchiveProvider.authority(context)}"
        val archive = "archive=content%3A%2F%2Fa%2Fb"
        // The one that is, so each of the others fails for its own reason and not for its shape
        assertThat(ArchiveProvider.parse(Uri.parse("$base/1/8/2/3/ff/none/x.pdf?$archive"))).isNotNull()
        listOf(
            "$base/1/8/2/3/ff/none?$archive",
            "$base/1/8/2/3/ff/none/x.pdf",
            "$base/one/8/2/3/ff/none/x.pdf?$archive",
            "$base/-1/8/2/3/ff/none/x.pdf?$archive",
            "$base/1/8/2/3/not-hex/none/x.pdf?$archive",
            "$base/1/8/2/3/ff/rot13/x.pdf?$archive",
        ).forEach { assertThat(ArchiveProvider.parse(Uri.parse(it))).isNull() }
    }

    // ---------------------------------------------------------------
    // What the viewer asks before it reads
    // ---------------------------------------------------------------

    @Test
    fun itGivesTheFilesOwnNameAndSize() {
        context.contentResolver.query(uriOf("reports/six-pages.pdf"), null, null, null, null)!!
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.getString(c.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME)))
                    .isEqualTo("six-pages.pdf")
                assertThat(c.getLong(c.getColumnIndexOrThrow(OpenableColumns.SIZE)))
                    .isEqualTo(Fixtures.file("six-pages.pdf").length())
            }
    }

    /** A column it does not keep is there and empty, rather than missing and a crash. */
    @Test
    fun aColumnItDoesNotKeepComesBackEmpty() {
        context.contentResolver.query(uriOf("plain.txt"), arrayOf("_data"), null, null, null)!!
            .use { c ->
                assertThat(c.moveToFirst()).isTrue()
                assertThat(c.isNull(0)).isTrue()
            }
    }

    @Test
    fun theTypeComesFromTheName() {
        assertThat(context.contentResolver.getType(uriOf("reports/six-pages.pdf")))
            .isEqualTo("application/pdf")
        assertThat(context.contentResolver.getType(uriOf("private/table.dat")))
            .isEqualTo("application/octet-stream")
    }

    // ---------------------------------------------------------------
    // Reading
    // ---------------------------------------------------------------

    /**
     * A stored file is a run of the archive's own bytes, so it is handed over as a window
     * onto them: anything that seeks can, and nothing is copied.
     */
    @Test
    fun aStoredFileIsAWindowOntoTheArchive() {
        val uri = uriOf("photos/tiny.png")
        context.contentResolver.openAssetFileDescriptor(uri, "r")!!.use { afd ->
            assertThat(afd.startOffset).isGreaterThan(0L)
            assertThat(afd.declaredLength).isEqualTo(Fixtures.file("tiny.png").length())
        }
        val bytes = context.contentResolver.openInputStream(uri)!!.use { it.readBytes() }
        assertThat(bytes).isEqualTo(Fixtures.bytes("tiny.png"))
    }

    /**
     * A window is a descriptor onto the whole archive, and its holder can read outside it. So
     * an app a stored file was shared with gets the file through a pipe, and never a window
     * that would carry every other file in the zip with it.
     */
    @Test
    fun anotherAppIsNeverHandedAWindowOntoTheArchive() {
        ShadowBinder.setCallingUid(Process.myUid() + 1)
        context.contentResolver.openAssetFileDescriptor(uriOf("photos/tiny.png"), "r")!!.use { afd ->
            assertThat(afd.startOffset).isEqualTo(0L)
            assertThat(afd.declaredLength).isEqualTo(AssetFileDescriptor.UNKNOWN_LENGTH)
        }
    }

    /** A file in the archive that has since changed is not served from where it used to be. */
    @Test
    fun aFileThatHasMovedIsNotFound() {
        val real = entry("photos/tiny.png")
        val stale = ArchiveEntry(
            real.path, false, 0,
            EntryLocation(real.location.headerOffset + 7, 0, real.size, real.size, real.location.crc),
        )
        val uri = ArchiveProvider.uriFor(context, archive, stale)
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(uri, "r")
        }
    }

    // ---------------------------------------------------------------
    // Under a password
    // ---------------------------------------------------------------

    /** The password is never in the URI, so without the one the list was given, nothing. */
    @Test
    fun aFileUnderAPasswordIsNotServedWithoutIt() {
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(lockedUri("zipcrypto.txt"), "r")
        }
    }

    /**
     * A stored file under a password is still encrypted where it lies, so even Gander's own
     * viewers get it through the pipe, decrypted, and never a window onto the encrypted bytes.
     * What comes down the pipe is read on a device, in ArchiveDeviceTest: Robolectric's pipes
     * read back empty.
     */
    @Test
    fun aStoredFileUnderAPasswordIsNeverAWindow() {
        ArchivePasswords.remember(locked, "gander")
        context.contentResolver.openAssetFileDescriptor(lockedUri("aes256.png"), "r")!!.use { afd ->
            assertThat(afd.startOffset).isEqualTo(0L)
            assertThat(afd.declaredLength).isEqualTo(AssetFileDescriptor.UNKNOWN_LENGTH)
        }
        // Encryption adds bytes, so its sizes alone already rule a window out. The lock is
        // what rules it out even for a location whose sizes would not
        val real = entry("aes256.png", "locked.zip").location
        val even = ArchiveEntry(
            "aes256.png", false, 0,
            EntryLocation(real.headerOffset, real.method, real.size, real.size, real.crc, real.lock),
        )
        context.contentResolver.openAssetFileDescriptor(ArchiveProvider.uriFor(context, locked, even), "r")!!
            .use { afd -> assertThat(afd.declaredLength).isEqualTo(AssetFileDescriptor.UNKNOWN_LENGTH) }
        // The same file in the clear is a window, so this is the lock deciding and not the method
        context.contentResolver.openAssetFileDescriptor(uriOf("photos/tiny.png"), "r")!!.use { afd ->
            assertThat(afd.declaredLength).isEqualTo(Fixtures.file("tiny.png").length())
        }
    }

    @Test
    fun itWillNotWrite() {
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(uriOf("plain.txt"), "w")
        }
    }

    @Test
    fun aMissingArchiveIsNotFound() {
        val gone = ArchiveProvider.uriFor(context, FixtureProvider.uriFor(FixtureProvider.BROKEN), entry("plain.txt"))
        assertThrows(FileNotFoundException::class.java) {
            context.contentResolver.openAssetFileDescriptor(gone, "r")
        }
    }
}

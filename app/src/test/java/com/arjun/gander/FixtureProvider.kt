package com.arjun.gander

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.OpenableColumns
import java.io.File
import java.io.FileNotFoundException
import org.robolectric.Robolectric

/**
 * A stand-in for whichever app handed Gander a document.
 *
 * Everything the viewer does to a file goes through a ContentResolver, so a
 * provider is the honest way to test it: openInputStream, openFileDescriptor
 * and openAssetFileDescriptor all work against this without any of the code
 * under test knowing it is not a real one.
 *
 * A URI is content://test.fixtures/<fixture name>. Providers that misbehave
 * are simulated by name: see [BROKEN] and [NO_LENGTH].
 */
internal class FixtureProvider : ContentProvider() {

    companion object {
        const val AUTHORITY = "test.fixtures"

        /** Every read of this one throws, as a provider in a bad state does. */
        const val BROKEN = "broken.pdf"

        /** This one opens but will not say how long it is. */
        const val NO_LENGTH = "nolength.pdf"

        /**
         * six-pages.pdf and exif-6.jpg, handed over only once [gate] opens, as a cloud provider
         * hands over a file once it has fetched it.
         */
        const val SLOW = "slow.pdf"
        const val SLOW_PHOTO = "slow.jpg"
        private val SLOW_STAND_INS = mapOf(SLOW to "six-pages.pdf", SLOW_PHOTO to "exif-6.jpg")

        @Volatile
        var gate = java.util.concurrent.CountDownLatch(0)

        /**
         * Registers the provider with the resolver, and answers it.
         *
         * Without this Robolectric hands openInputStream a placeholder stream
         * that yields no bytes, and every read quietly comes back empty rather
         * than failing, which is a long afternoon.
         */
        fun install(): FixtureProvider =
            Robolectric.buildContentProvider(FixtureProvider::class.java)
                .create(AUTHORITY)
                .get()

        fun uriFor(name: String): Uri = Uri.parse("content://$AUTHORITY/$name")

        /** A provider-supplied display name that is not the fixture's own. */
        fun uriNamed(name: String, displayName: String): Uri =
            Uri.parse("content://$AUTHORITY/$name?name=$displayName")
    }

    /** Files this provider was handed directly, rather than by fixture name. */
    private val extra = mutableMapOf<String, File>()

    /** Serves [file] under [name], for documents generated inside a test. */
    fun add(name: String, file: File): Uri {
        extra[name] = file
        return uriFor(name)
    }

    override fun onCreate() = true

    private fun nameOf(uri: Uri): String = uri.lastPathSegment.orEmpty()

    private fun fileFor(uri: Uri): File {
        val name = nameOf(uri)
        if (name == BROKEN) throw FileNotFoundException("this provider is having a bad day")
        extra[name]?.let { return it }
        return Fixtures.file(SLOW_STAND_INS[name] ?: name)
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val name = nameOf(uri)
        if (name == BROKEN) throw FileNotFoundException("this provider is having a bad day")
        val columns = arrayOf(OpenableColumns.DISPLAY_NAME, OpenableColumns.SIZE)
        return MatrixCursor(columns).apply {
            addRow(
                arrayOf(
                    uri.getQueryParameter("name") ?: name,
                    if (name == NO_LENGTH) null else fileFor(uri).length(),
                )
            )
        }
    }

    override fun getType(uri: Uri): String {
        val ext = nameOf(uri).substringAfterLast('.', "").lowercase()
        return documentMime(ext)
    }

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (nameOf(uri) in SLOW_STAND_INS) gate.await()
        return ParcelFileDescriptor.open(fileFor(uri), ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, args: Array<out String>?) = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        args: Array<out String>?
    ) = 0
}

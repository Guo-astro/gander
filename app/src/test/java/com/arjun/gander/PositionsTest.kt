package com.arjun.gander

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The page each PDF was left at, issue #25, and the place each book was, issue #50: what it
 * is filed under, and what is kept.
 *
 * The viewer's side, that the page reaches the URL and that nothing in a zip is filed, is in
 * ViewerActivityTest; this is the store itself.
 */
@RunWith(AndroidJUnit4::class)
class PositionsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        FixtureProvider.install()
        store().delete()
    }

    private fun store() = File(context.noBackupFilesDir, "positions")

    private fun keyOf(fixture: String): String =
        Positions.keyFor(
            context.contentResolver, FixtureProvider.uriFor(fixture), Fixtures.file(fixture).length()
        )!!

    @Test
    fun aPageIsKeptAndGivenBack() {
        val key = keyOf("six-pages.pdf")
        Positions.save(context, key, 4, 6)
        assertThat(Positions.page(context, key)).isEqualTo(4)
    }

    @Test
    fun aDocumentNeverLeftAnywhereOpensAtTheTop() {
        assertThat(Positions.page(context, keyOf("six-pages.pdf"))).isEqualTo(0)
    }

    /** One document reaches the viewer under many URIs; the key is read from what it holds. */
    @Test
    fun theKeyIsTheSameWhateverTheUriAndDiffersBetweenDocuments() {
        val direct = keyOf("six-pages.pdf")
        val named = Positions.keyFor(
            context.contentResolver,
            FixtureProvider.uriNamed("six-pages.pdf", "Renamed.pdf"),
            Fixtures.file("six-pages.pdf").length(),
        )
        assertThat(named).isEqualTo(direct)
        assertThat(keyOf("forty-pages.pdf")).isNotEqualTo(direct)
    }

    /** A document grown by an edit is a different document, even with the same first bytes. */
    @Test
    fun theLengthIsPartOfTheKey() {
        val uri = FixtureProvider.uriFor("six-pages.pdf")
        val length = Fixtures.file("six-pages.pdf").length()
        assertThat(Positions.keyFor(context.contentResolver, uri, length + 1))
            .isNotEqualTo(Positions.keyFor(context.contentResolver, uri, length))
    }

    @Test
    fun aDocumentThatCannotBeReadHasNoKey() {
        assertThat(
            Positions.keyFor(context.contentResolver, FixtureProvider.uriFor(FixtureProvider.BROKEN), 10)
        ).isNull()
    }

    /** Where a document opens anyway, so nothing is filed, and a page filed before is forgotten. */
    @Test
    fun theFirstPageIsNotKeptAndForgetsAnEarlierOne() {
        val key = keyOf("six-pages.pdf")
        Positions.save(context, key, 3, 6)
        Positions.save(context, key, 1, 6)
        assertThat(Positions.page(context, key)).isEqualTo(0)
    }

    /** A document read to the end opens at the beginning again. */
    @Test
    fun theLastPageIsNotKept() {
        val key = keyOf("six-pages.pdf")
        Positions.save(context, key, 6, 6)
        assertThat(Positions.page(context, key)).isEqualTo(0)
    }

    @Test
    fun onlyTheNewestHundredAreKept() {
        for (n in 0 until 101) {
            Positions.save(context, "key$n", 2, 6)
            // Each one later than the one before, which is what decides the oldest
            Thread.sleep(2)
        }
        assertThat(Positions.page(context, "key0")).isEqualTo(0)
        assertThat(Positions.page(context, "key1")).isEqualTo(2)
        assertThat(Positions.page(context, "key100")).isEqualTo(2)
    }

    /** Kept where no backup or transfer to a new phone reaches, never in preferences. */
    @Test
    fun theStoreIsInTheNoBackupFolder() {
        Positions.save(context, keyOf("six-pages.pdf"), 3, 6)
        assertThat(store().exists()).isTrue()
        assertThat(store().parentFile).isEqualTo(context.noBackupFilesDir)
    }

    /** A line that is not three fields is passed over rather than taken for a position. */
    @Test
    fun aDamagedLineIsPassedOver() {
        store().writeText("garbage\nabc 5 notatime\ngood 3 100\n")
        assertThat(Positions.page(context, "abc")).isEqualTo(0)
        assertThat(Positions.page(context, "good")).isEqualTo(3)
    }

    // ---------------------------------------------------------------
    // A book, which is left at a place finer than a page
    // ---------------------------------------------------------------

    @Test
    fun aBooksPlaceIsKeptWithItsPageAndGivenBack() {
        val key = keyOf("harbour.epub")
        Positions.save(context, key, 4, 9, Positions.Place(2, 2968))
        assertThat(Positions.place(context, key)).isEqualTo(Positions.Place(2, 2968))
        assertThat(Positions.page(context, key)).isEqualTo(4)
    }

    /** A PDF's line has no place in it, and one filed by a build before books reads as it did. */
    @Test
    fun aPdfHasNoPlace() {
        val key = keyOf("six-pages.pdf")
        Positions.save(context, key, 4, 6)
        assertThat(Positions.place(context, key)).isNull()
        assertThat(store().readText().trim().split(' ')).hasSize(3)
    }

    /**
     * What a book counts as its first page is more than a screenful, so a book left on it is
     * kept where a PDF left on its first page is not. Only the top of the first chapter is
     * where a book opens anyway.
     */
    @Test
    fun aBookLeftPartWayDownItsFirstPageIsKept() {
        val key = keyOf("harbour.epub")
        Positions.save(context, key, 1, 9, Positions.Place(0, 412))
        assertThat(Positions.place(context, key)).isEqualTo(Positions.Place(0, 412))

        Positions.save(context, key, 1, 9, Positions.Place(0, 0))
        assertThat(Positions.place(context, key)).isNull()

        // The top of a later chapter is a place like any other
        Positions.save(context, key, 3, 9, Positions.Place(2, 0))
        assertThat(Positions.place(context, key)).isEqualTo(Positions.Place(2, 0))
    }

    /** A book read to the end opens at the beginning again, as a PDF does. */
    @Test
    fun aBookLeftAtItsEndIsNotKept() {
        val key = keyOf("harbour.epub")
        Positions.save(context, key, 4, 9, Positions.Place(2, 2968))
        Positions.save(context, key, 9, 9, Positions.Place(3, 600))
        assertThat(Positions.place(context, key)).isNull()
        assertThat(Positions.page(context, key)).isEqualTo(0)
    }

    /** A line with a place that is not two counts is passed over whole, page and all. */
    @Test
    fun aLineWithADamagedPlaceIsPassedOver() {
        store().writeText(
            "four 3 100 2\nletters 3 100 two 5\nless 3 100 -1 5\nsix 3 100 2 5 9\ngood 3 100 2 5\n"
        )
        for (key in listOf("four", "letters", "less", "six")) {
            assertThat(Positions.page(context, key)).isEqualTo(0)
            assertThat(Positions.place(context, key)).isNull()
        }
        assertThat(Positions.place(context, "good")).isEqualTo(Positions.Place(2, 5))
    }
}

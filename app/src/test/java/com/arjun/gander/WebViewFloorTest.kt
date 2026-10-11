package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * Reading the engine version, and deciding what each viewer page is told about it.
 *
 * Every case here is a device that actually behaves this way; the reasoning
 * is in the KDoc beside each constant in WebViewFloor.kt.
 */
class WebViewFloorTest {

    private companion object {
        /** A current Android System WebView on a Pixel. */
        const val UA_MODERN =
            "Mozilla/5.0 (Linux; Android 14; Pixel 7) AppleWebKit/537.36 (KHTML, " +
            "like Gecko) Version/4.0 Chrome/138.0.7204.179 Mobile Safari/537.36"

        /** An engine below the pdf.js floor, but plainly a Chromium one. */
        const val UA_OLD =
            "Mozilla/5.0 (Linux; Android 9) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Chrome/110.0.5481.65 Mobile Safari/537.36"

        /** A vendor engine whose user agent carries no Chrome/ token at all. */
        const val UA_NO_TOKEN =
            "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Version/4.0 Mobile Safari/537.36"
    }

    // ---------------------------------------------------------------
    // chromiumMajor
    // ---------------------------------------------------------------

    @Test
    fun theUserAgentIsReadFirst() {
        assertThat(chromiumMajor(UA_MODERN) { null }).isEqualTo(138)
        assertThat(chromiumMajor(UA_OLD) { null }).isEqualTo(110)
    }

    /**
     * The whole reason the user agent comes first. Huawei numbers its WebView
     * package 15.0.4.326, which parses to 15 and would be dismissed as
     * unreadable, while the engine inside it says what it really is.
     */
    @Test
    fun theUserAgentWinsOverAVendorPackageNumber() {
        assertThat(chromiumMajor(UA_MODERN) { "15.0.4.326" }).isEqualTo(138)
        assertThat(chromiumMajor(UA_OLD) { "15.0.4.326" }).isEqualTo(110)
    }

    /** The package is the fallback when the user agent names no engine. */
    @Test
    fun thePackageVersionAnswersWhenTheUserAgentDoesNot() {
        assertThat(chromiumMajor(UA_NO_TOKEN) { "138.0.7204.179" }).isEqualTo(138)
        assertThat(chromiumMajor(null) { "125.0.6422.165" }).isEqualTo(125)
    }

    /**
     * The package is only asked once the user agent has failed to answer. That
     * lookup can throw on a provider in a bad state, and a throw must not cost a
     * version the user agent already gave: an engine too old for pdf.js would be
     * sent the renderer instead of the card saying why it cannot show the file.
     */
    @Test
    fun thePackageIsNotAskedOnceTheUserAgentHasAnswered() {
        var asked = false
        assertThat(chromiumMajor(UA_OLD) { asked = true; "124.0.6367.0" }).isEqualTo(110)
        assertThat(asked).isFalse()
    }

    /**
     * A number below the floor is a vendor scheme, not an engine that predates
     * updatable WebView. Treated as unreadable so the caller can decide, which
     * for an unlocked provider means letting the document through.
     */
    @Test
    fun aNumberTooLowToBeChromiumIsNotAVersion() {
        assertThat(chromiumMajor(null) { "15.0.4.326" }).isNull()
        assertThat(chromiumMajor(null) { "1.0" }).isNull()
        assertThat(chromiumMajor("Mozilla/5.0 Chrome/15.0.874.106") { null }).isNull()
        // and exactly at the floor it is
        assertThat(chromiumMajor(null) { "30.0.0.0" }).isEqualTo(30)
    }

    @Test
    fun nothingReadableGivesNull() {
        assertThat(chromiumMajor(null) { null }).isNull()
        assertThat(chromiumMajor(UA_NO_TOKEN) { null }).isNull()
        assertThat(chromiumMajor("") { "" }).isNull()
        assertThat(chromiumMajor(UA_NO_TOKEN) { "not-a-version" }).isNull()
    }

    // ---------------------------------------------------------------
    // webViewFloorParams
    // ---------------------------------------------------------------

    /**
     * Only PDF, Word and Markdown are drawn by a library with a floor. Every other
     * page's scripts parse as ES2015, so none of them is ever blocked.
     */
    @Test
    fun noFormatWithoutAFloorIsEverBlocked() {
        val floorless = FileKind.entries.filter { minChromiumMajor(it) == null }
        assertThat(floorless).containsNoneOf(FileKind.PDF, FileKind.DOCX, FileKind.MD)
        floorless.forEach { kind ->
            assertThat(webViewFloorParams(kind, 60, locked = true)).isEmpty()
            assertThat(webViewFloorParams(kind, null, locked = true)).isEmpty()
        }
    }

    @Test
    fun anEngineAtOrAboveTheFloorAddsNothing() {
        assertThat(webViewFloorParams(FileKind.PDF, PDFJS_MIN_CHROMIUM_MAJOR, false)).isEmpty()
        assertThat(webViewFloorParams(FileKind.PDF, 138, false)).isEmpty()
        assertThat(webViewFloorParams(FileKind.DOCX, DOCX_PREVIEW_MIN_CHROMIUM_MAJOR, false)).isEmpty()
        assertThat(webViewFloorParams(FileKind.MD, MARKED_MIN_CHROMIUM_MAJOR, false)).isEmpty()
    }

    @Test
    fun anOldEngineIsNamedAlongsideTheFloorItMisses() {
        assertThat(webViewFloorParams(FileKind.PDF, 110, locked = false))
            .isEqualTo("&webview=110&needs=125")
    }

    /**
     * Each format against its own library's floor, not the highest one. WebView
     * 110 is too old for pdf.js and fine for the other two; the WebView 64 of
     * issue #31 is too old for all three.
     */
    @Test
    fun eachFormatIsMeasuredAgainstItsOwnFloor() {
        assertThat(webViewFloorParams(FileKind.DOCX, 110, locked = false)).isEmpty()
        assertThat(webViewFloorParams(FileKind.MD, 110, locked = false)).isEmpty()
        assertThat(webViewFloorParams(FileKind.DOCX, 64, locked = false))
            .isEqualTo("&webview=64&needs=80")
        assertThat(webViewFloorParams(FileKind.MD, 64, locked = false))
            .isEqualTo("&webview=64&needs=92")
    }

    /** marked parses from 80, so between there and 92 only running it fails. */
    @Test
    fun markdownIsBlockedWhereMarkedParsesButCannotRun() {
        assertThat(webViewFloorParams(FileKind.MD, 91, locked = false))
            .isEqualTo("&webview=91&needs=92")
        assertThat(webViewFloorParams(FileKind.DOCX, 91, locked = false)).isEmpty()
    }

    /** On a locked provider the page drops the advice to go and update. */
    @Test
    fun aLockedProviderIsFlagged() {
        assertThat(webViewFloorParams(FileKind.PDF, 110, locked = true))
            .isEqualTo("&webview=110&needs=125&locked=1")
        assertThat(webViewFloorParams(FileKind.DOCX, 64, locked = true))
            .isEqualTo("&webview=64&needs=80&locked=1")
    }

    /**
     * The Huawei case the constants exist for: the version would not parse, and
     * because the provider cannot be replaced that means old rather than
     * unknown. The flag goes out with no major beside it, which is why the page
     * gates on either parameter.
     */
    @Test
    fun anUnreadableVersionOnALockedProviderStillBlocks() {
        assertThat(webViewFloorParams(FileKind.PDF, null, locked = true))
            .isEqualTo("&needs=125&locked=1")
    }

    /**
     * But only for a PDF. That rests on no Huawei build reaching 125, and nothing
     * says the same of 80 or 92, so a Word or Markdown file there is let through.
     */
    @Test
    fun anUnreadableLockedVersionBlocksOnlyPdfs() {
        assertThat(webViewFloorParams(FileKind.DOCX, null, locked = true)).isEmpty()
        assertThat(webViewFloorParams(FileKind.MD, null, locked = true)).isEmpty()
    }

    /**
     * And the opposite: unreadable on a provider the reader could replace is
     * waved through, for every format. Refusing files on a WebView that works is
     * the worse mistake, and pdf.html's nomodule fallback still catches a true
     * ancient.
     */
    @Test
    fun anUnreadableVersionOnAnOrdinaryProviderIsWavedThrough() {
        FileKind.entries.forEach { kind ->
            assertThat(webViewFloorParams(kind, null, locked = false)).isEmpty()
        }
    }

    // ---------------------------------------------------------------

    /**
     * The floor is repeated in docs/VENDORED.md and read by pdf.html out of the
     * query string. If it moves, the vendored pdf.js moved with it, and the
     * Chromium 138 ceiling on Android 8 and 9 is the thing to check first.
     */
    @Test
    fun theFloorIsStillOneTwentyFive() {
        assertThat(PDFJS_MIN_CHROMIUM_MAJOR).isEqualTo(125)
    }

    /**
     * Measured rather than published, on the files VendoredLibsTest pins by hash.
     * Moving either number means those files moved and were measured again, the
     * way docs/VENDORED.md describes.
     */
    @Test
    fun theWordAndMarkdownFloorsAreEightyAndNinetyTwo() {
        assertThat(DOCX_PREVIEW_MIN_CHROMIUM_MAJOR).isEqualTo(80)
        assertThat(MARKED_MIN_CHROMIUM_MAJOR).isEqualTo(92)
    }

    @Test
    fun huaweiIsTheOneLockedProvider() {
        assertThat(LOCKED_WEBVIEW_PACKAGES).containsExactly("com.huawei.webview")
    }

    // ---------------------------------------------------------------
    // Which pages search their own text, through find.js
    // ---------------------------------------------------------------

    /**
     * The seven that have find.js do, from the engine that can mark what it found; below it they
     * keep Chromium's own find. A PDF has no other search to fall back to, and the rest have
     * nothing to search or are not pages at all.
     */
    @Test
    fun pagesSearchThemselvesWhereTheEngineCanMarkWhatItFound() {
        val finders = listOf(
            FileKind.DOCX, FileKind.PROSE, FileKind.MD, FileKind.TEXT, FileKind.XLSX, FileKind.PPTX,
            FileKind.EPUB
        )
        for (kind in finders) {
            assertThat(searchesInPage(kind, HIGHLIGHT_MIN_CHROMIUM_MAJOR)).isTrue()
            assertThat(searchesInPage(kind, HIGHLIGHT_MIN_CHROMIUM_MAJOR - 1)).isFalse()
            // A version that could not be read is taken as new enough, as the floors take it
            assertThat(searchesInPage(kind, null)).isTrue()
        }
        assertThat(searchesInPage(FileKind.PDF, 90)).isTrue()
        val rest = FileKind.entries - finders.toSet() - FileKind.PDF
        for (kind in rest) assertThat(searchesInPage(kind, 150)).isFalse()
    }

    @Test
    fun theHighlightApiArrivedInChromium105() {
        assertThat(HIGHLIGHT_MIN_CHROMIUM_MAJOR).isEqualTo(105)
    }
}


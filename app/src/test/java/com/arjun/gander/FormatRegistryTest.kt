package com.arjun.gander

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The format list exists in four places, and they drift.
 *
 * [FileKind] routes a file once it is open. The manifest's two intent filters
 * decide whether Gander is offered in the share sheet at all. The welcome grid
 * names the kinds, and a string in strings.xml describes that grid to a screen
 * reader. Adding a format touches all four, and forgetting one is silent: the
 * app either never appears in the chooser, or appears and then says it cannot
 * open the thing it just claimed.
 *
 * This is the test that fails until they agree.
 */
@RunWith(AndroidJUnit4::class)
class FormatRegistryTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val packageManager: PackageManager = context.packageManager

    private companion object {
        val MANIFEST = File("src/main/AndroidManifest.xml").readText()

        /** The Office types whose registered spelling is not all lower case. */
        val MACRO_ENABLED = mapOf(
            "application/vnd.ms-word.document.macroEnabled.12" to FileKind.DOCX,
            "application/vnd.ms-powerpoint.presentation.macroEnabled.12" to FileKind.PPTX,
            "application/vnd.ms-excel.sheet.macroEnabled.12" to FileKind.XLSX,
            "application/vnd.ms-excel.sheet.binary.macroEnabled.12" to FileKind.XLSX,
        )

        /** Not a format but the lack of one, claimed for a file an app knows only as binary. */
        const val GENERIC_BINARY = "application/octet-stream"

        /** Every mimeType the manifest claims, in declaration order with duplicates. */
        fun claimedMimes(): List<String> =
            Regex("""android:mimeType="([^"]+)"""").findAll(MANIFEST)
                .map { it.groupValues[1] }
                .toList()

        /** The mimeTypes inside one intent-filter naming [action]. */
        fun mimesFor(action: String): Set<String> {
            val filters = Regex("""<intent-filter>(.*?)</intent-filter>""", RegexOption.DOT_MATCHES_ALL)
                .findAll(MANIFEST)
                .map { it.groupValues[1] }
            val filter = filters.first { it.contains("android.intent.action.$action") }
            return Regex("""android:mimeType="([^"]+)"""").findAll(filter)
                .map { it.groupValues[1] }
                .toSet()
        }
    }

    private fun resolves(action: String, mime: String): Boolean {
        val intent = Intent(action).apply {
            addCategory(Intent.CATEGORY_DEFAULT)
            if (action == Intent.ACTION_VIEW) {
                setDataAndType(Uri.parse("content://test.fixtures/a"), mime)
            } else {
                type = mime
            }
        }
        return packageManager.queryIntentActivities(intent, 0).any {
            it.activityInfo.name == ViewerActivity::class.java.name
        }
    }

    // ---------------------------------------------------------------
    // The manifest against itself
    // ---------------------------------------------------------------

    /**
     * VIEW is "Open with"; SEND is the share sheet. A format offered by one
     * and not the other works from the file manager and is invisible from
     * Gmail, or the reverse, and nothing says so.
     */
    @Test
    fun bothIntentFiltersClaimTheSameFormats() {
        assertThat(mimesFor("VIEW")).isEqualTo(mimesFor("SEND"))
    }

    @Test
    fun noMimeTypeIsClaimedTwiceInTheSameFilter() {
        val all = claimedMimes()
        assertThat(all).hasSize(mimesFor("VIEW").size + mimesFor("SEND").size)
    }

    @Test
    fun theViewerIsTheActivityThatAnswers() {
        mimesFor("VIEW").forEach { mime ->
            assertThat("$mime resolves to the viewer: ${resolves(Intent.ACTION_VIEW, mime)}")
                .isEqualTo("$mime resolves to the viewer: true")
        }
    }

    @Test
    fun theShareSheetOffersTheViewerForEveryClaimedFormat() {
        mimesFor("SEND").forEach { mime ->
            assertThat("$mime resolves to the viewer: ${resolves(Intent.ACTION_SEND, mime)}")
                .isEqualTo("$mime resolves to the viewer: true")
        }
    }

    // ---------------------------------------------------------------
    // The manifest against FileKind
    // ---------------------------------------------------------------

    /**
     * A share with no filename, which is what a mail client or a chat app
     * routinely sends, arrives with a MIME type and nothing else. If the
     * manifest claims a type that [FileKind.detect] cannot place, Gander
     * offers itself in the chooser and then shows the unsupported card.
     *
     * Except application/octet-stream, which is not a format but the lack of
     * one: it is what WhatsApp sends a file as when it has no name for its type,
     * a .stl among them. Such a file comes with its name, and goes by that.
     */
    @Test
    fun everyFormatTheManifestClaimsCanBeRoutedByMimeAlone() {
        val claimed = mimesFor("VIEW") - GENERIC_BINARY
        val wildcards = claimed.filter { it.endsWith("/*") }
        val exact = claimed.filterNot { it.endsWith("/*") }

        // Wildcards are checked with a representative type each
        val representative = mapOf(
            "image/*" to "image/png",
            "video/*" to "video/mp4",
            "audio/*" to "audio/mpeg",
            "text/*" to "text/plain",
        )
        wildcards.forEach { pattern ->
            val mime = representative.getValue(pattern)
            assertThat("$pattern via $mime: ${FileKind.detect("", mime)}")
                .isNotEqualTo("$pattern via $mime: ${FileKind.UNSUPPORTED}")
        }
        exact.forEach { mime ->
            assertThat("$mime routes to: ${FileKind.detect("", mime)}")
                .isNotEqualTo("$mime routes to: ${FileKind.UNSUPPORTED}")
        }
    }

    /**
     * WhatsApp shares a .stl, and anything else it has no type for, as a generic
     * binary. Without this Gander is left out of both the share sheet and Open with.
     */
    @Test
    fun aFileSentAsAGenericBinaryIsOffered() {
        listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND).forEach { action ->
            assertWithMessage("$action offers the viewer for $GENERIC_BINARY")
                .that(resolves(action, GENERIC_BINARY)).isTrue()
        }
    }

    /**
     * And the other way: a format Gander can render but does not claim never
     * gets offered, so the reader has to go and find the file themselves.
     */
    @Test
    fun everyFormatWithARendererIsClaimedByTheManifest() {
        val claimed = mimesFor("VIEW")
        val shouldBeOffered = mapOf(
            "application/pdf" to FileKind.PDF,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
                to FileKind.DOCX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
                to FileKind.XLSX,
            "application/vnd.openxmlformats-officedocument.presentationml.presentation"
                to FileKind.PPTX,
            "application/vnd.ms-excel" to FileKind.XLSX,
            "application/zip" to FileKind.ARCHIVE,
            "application/x-zip-compressed" to FileKind.ARCHIVE,
            "application/vnd.openxmlformats-officedocument.wordprocessingml.template"
                to FileKind.DOCX,
            "application/vnd.openxmlformats-officedocument.spreadsheetml.template"
                to FileKind.XLSX,
            "application/vnd.openxmlformats-officedocument.presentationml.slideshow"
                to FileKind.PPTX,
            "application/vnd.openxmlformats-officedocument.presentationml.template"
                to FileKind.PPTX,
            "application/x-subrip" to FileKind.TEXT,
            // What Android 10 and later call a .js, a .rb and a .sql
            "application/javascript" to FileKind.TEXT,
            "application/x-ruby" to FileKind.TEXT,
            "application/x-sql" to FileKind.TEXT,
            "model/stl" to FileKind.MODEL,
            "model/x.stl-binary" to FileKind.MODEL,
            "model/x.stl-ascii" to FileKind.MODEL,
            "application/sla" to FileKind.MODEL,
            // What Android calls a .stl, and so what a file manager sends one as
            "application/vnd.ms-pki.stl" to FileKind.MODEL,
        ) + MACRO_ENABLED.flatMap { (mime, kind) ->
            listOf(mime to kind, mime.lowercase() to kind)
        }
        shouldBeOffered.forEach { (mime, kind) ->
            assertThat("$mime claimed: ${mime in claimed}").isEqualTo("$mime claimed: true")
            assertThat(FileKind.detect("", mime)).isEqualTo(kind)
        }
    }

    /**
     * A filter matches a type exactly, case and all. Android 10 and later report a .docm, a
     * .pptm, a .xlsm or a .xlsb in lower case, and the registered spelling says macroEnabled,
     * so each is claimed both ways. Without one of the two lines Gander is missing from the
     * chooser for whichever spelling that was, in the one place nothing else would notice.
     */
    @Test
    fun theMacroEnabledTypesAreOfferedInBothSpellings() {
        MACRO_ENABLED.keys.flatMap { listOf(it, it.lowercase()) }.forEach { mime ->
            listOf(Intent.ACTION_VIEW, Intent.ACTION_SEND).forEach { action ->
                assertWithMessage("$action offers the viewer for $mime")
                    .that(resolves(action, mime)).isTrue()
            }
        }
    }

    // ---------------------------------------------------------------
    // The welcome grid against its spoken description
    // ---------------------------------------------------------------

    /**
     * The grid is nine tiles and the string is prose, so nothing but this
     * keeps them describing the same app. The KDoc on WELCOME_BADGES warns
     * about it; this is the warning made to fail. ETC is not a kind, so the
     * sentence has to name each of the kinds it stands for instead.
     */
    @Test
    fun theSpokenDescriptionNamesEveryKindTheGridShows() {
        val spoken = context.getString(R.string.welcome_formats_spoken).lowercase()
        val expected = mapOf(
            "PDF" to listOf(listOf("pdf")),
            "DOC" to listOf(listOf("word", "document")),
            "XLS" to listOf(listOf("excel", "spreadsheet")),
            "PPT" to listOf(listOf("powerpoint", "slide", "presentation")),
            "IMG" to listOf(listOf("photo", "image", "picture")),
            "VID" to listOf(listOf("video")),
            "AUD" to listOf(listOf("audio", "music", "sound")),
            "MD" to listOf(listOf("markdown")),
            "ETC" to listOf(listOf("text", "code"), listOf("zip"), listOf("3d model")),
        )
        WELCOME_BADGES.forEach { (label, _) ->
            expected.getValue(label).forEach { words ->
                val mentioned = words.any { it in spoken }
                assertThat("$label ${words.first()} mentioned in welcome_formats_spoken: $mentioned")
                    .isEqualTo("$label ${words.first()} mentioned in welcome_formats_spoken: true")
            }
        }
    }

    // ---------------------------------------------------------------

    /**
     * Every renderer the registry names has to be in the APK. This is the same
     * check FileKindTest makes against the source tree, made again against the
     * built assets, because that is what actually ships.
     */
    @Test
    fun everyViewerPageIsPackagedIntoTheAssets() {
        FileKind.entries.filter { it.page.isNotEmpty() }.forEach { kind ->
            val opened = runCatching {
                context.assets.open("viewer/${kind.page}").use { it.read() }
            }.isSuccess
            assertThat("${kind.page} is in the assets: $opened")
                .isEqualTo("${kind.page} is in the assets: true")
        }
    }

    /**
     * Zero permissions, read back from the package Android actually installed.
     *
     * app/build.gradle.kts fails the build if the merged manifest requests
     * anything outside its allowlist; this is the same promise checked from
     * the other side, which is the side the About screen shows the reader.
     * The allowlist is repeated here rather than shared, because a gate whose
     * two halves read the same list only checks one thing.
     */
    @Test
    fun theInstalledPackageRequestsNothingOutsideTheAllowlist() {
        // androidx.core declares this so libraries can call registerReceiver
        // with RECEIVER_NOT_EXPORTED. Signature level and self-granted, so
        // Android never shows it to the reader as a permission.
        val allowed = setOf(
            "${context.packageName}.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION"
        )
        val info = packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        val requested = info.requestedPermissions.orEmpty().toList()
        assertThat(requested).containsNoneIn(
            requested.filterNot { it in allowed }.ifEmpty { listOf("nothing unexpected") }
        )
    }

    /** Nothing the reader would ever be prompted for. */
    @Test
    fun noPermissionTheReaderWouldSeeIsRequested() {
        val info = packageManager.getPackageInfo(
            context.packageName, PackageManager.GET_PERMISSIONS
        )
        val userVisible = info.requestedPermissions.orEmpty()
            .filter { it.startsWith("android.permission.") }
        assertThat(userVisible).isEmpty()
    }
}

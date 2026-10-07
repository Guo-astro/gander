package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import java.io.File
import java.security.MessageDigest
import org.junit.Test

/**
 * The things that have to be true of a release, checked on every commit
 * instead of on the day.
 *
 * None of this is about the code. It is about the four files that describe a
 * version to somebody who is not reading the code: the store listing, the
 * changelog, and the two numbers in the build file.
 */
class ReleaseHygieneTest {

    private companion object {
        val REPO = File("..")
        val BUILD_FILE = File(REPO, "app/build.gradle.kts").readText()
        val CHANGELOG = File(REPO, "CHANGELOG.md").readText()

        val VERSION_NAME: String =
            Regex("""versionName\s*=\s*"([^"]+)"""").find(BUILD_FILE)!!.groupValues[1]
        val VERSION_CODE: Int =
            Regex("""versionCode\s*=\s*(\d+)""").find(BUILD_FILE)!!.groupValues[1].toInt()

        /**
         * From 1.17 on, Play's build of a release takes the even code one above GitHub's, since
         * Play offers an update only over a lower code. Its notes are pasted into Play Console and
         * F-Droid builds GitHub's codes, so no changelog file is read for it.
         */
        const val PLAY_CODES_FROM = 20

        fun isPlays(code: Int) = code >= PLAY_CODES_FROM && code % 2 == 0

        val LISTING = File(REPO, "fastlane/metadata/android")
        val ENGLISH = File(LISTING, "en-US")

        /**
         * The English listing the translations were last brought in line with:
         * the SHA-256 of en-US/short_description.txt followed by
         * en-US/full_description.txt, which is what
         * `cat short_description.txt full_description.txt | shasum -a 256` prints
         * from that folder. The translated short descriptions keep their "no ads"
         * while the English one leaves it out, because Play Console flags the
         * English phrase as a promotion keyword and none of the translated ones.
         */
        const val TRANSLATED_FROM = "8eeaa9e310b2cf232ac3918f99fec5cdbbfb63f0f61651ba42afc7c0716eaad9"

        /** Every listing language beside the English, read off the disk. */
        val TRANSLATIONS: List<File> = LISTING.listFiles { f -> f.isDirectory && f.name != "en-US" }
            .orEmpty()
            .sortedBy { it.name }

        /** Names a translation has to keep exactly as the English writes them. */
        val NAMES = listOf(
            "Gander", "Android System WebView", "Word", "Excel", "PowerPoint",
            "Markdown", "WebP", "WebM", "Opus", "Material",
        )
    }

    /**
     * Two components, never three. Gander versions are 1.13, not 1.13.0, and a
     * patch component appearing means somebody reached for a habit rather than
     * the scheme.
     */
    @Test
    fun theVersionNameHasNoPatchComponent() {
        assertThat(VERSION_NAME).matches("""\d+\.\d+""")
    }

    @Test
    fun theVersionCodeIsPositive() {
        assertThat(VERSION_CODE).isGreaterThan(0)
    }

    /**
     * The two numbers move together or not at all.
     *
     * Every release adds a changelog file named after its version code, so the
     * highest one on disk is the last release described. If the build declares
     * a higher code than that, a version was bumped without notes; a lower one
     * means notes were written for a release that was never built. Both have
     * happened to other projects on the day of a release, which is the worst
     * possible time to find out.
     */
    @Test
    fun theVersionCodeMatchesTheLastDescribedRelease() {
        val dir = File(REPO, "fastlane/metadata/android/en-US/changelogs")
        val highest = dir.listFiles { f -> f.extension == "txt" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .max()
        assertThat(VERSION_CODE).isEqualTo(highest)
    }

    /**
     * Fastlane publishes one changelog per version code, and a missing file is
     * a release that ships to F-Droid with no release notes at all.
     */
    @Test
    fun theCurrentVersionCodeHasAStoreChangelog() {
        val notes = File(REPO, "fastlane/metadata/android/en-US/changelogs/$VERSION_CODE.txt")
        assertThat("$VERSION_CODE.txt exists=${notes.exists()}")
            .isEqualTo("$VERSION_CODE.txt exists=true")
        assertThat(notes.readText().trim()).isNotEmpty()
    }

    /** And every code before it but Play's, so the listing has no gaps. */
    @Test
    fun everyVersionCodeUpToTheCurrentOneHasOne() {
        val dir = File(REPO, "fastlane/metadata/android/en-US/changelogs")
        val present = dir.listFiles { f -> f.extension == "txt" }
            .orEmpty()
            .mapNotNull { it.nameWithoutExtension.toIntOrNull() }
            .toSet()
        val missing = (1..VERSION_CODE).filterNot { it in present || isPlays(it) }
        assertThat(missing).isEmpty()
    }

    /** A release that adds 1 rather than 2 lands on the code Play's build of the last one already has. */
    @Test
    fun theVersionCodeLeavesTheOneAboveItForPlay() {
        assertThat("versionCode $VERSION_CODE is Play's: ${isPlays(VERSION_CODE)}")
            .isEqualTo("versionCode $VERSION_CODE is Play's: false")
    }

    /**
     * The store listing is published in several languages, and every one
     * beside en-US is a copy of the English that nothing else keeps in step.
     * Any edit to the English fails here until the same edit is made in each
     * translation, and in the app names in Play Console, which are not in the
     * repo. Then TRANSLATED_FROM takes the fingerprint this test prints.
     */
    @Test
    fun theTranslatedListingsWereMadeFromTheCurrentEnglish() {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(File(ENGLISH, "short_description.txt").readBytes())
        digest.update(File(ENGLISH, "full_description.txt").readBytes())
        val fingerprint = digest.digest().joinToString("") { "%02x".format(it) }
        assertWithMessage(
            "The English store listing changed after it was translated. Make the same change in " +
                "${TRANSLATIONS.joinToString { it.name }}, then set TRANSLATED_FROM to the new fingerprint",
        ).that(fingerprint).isEqualTo(TRANSLATED_FROM)
    }

    /**
     * Each translation has both descriptions, and the short one fits Play's 80
     * characters. The languages are read off the disk, so a new one is checked
     * from the commit that adds it, and finding none fails rather than leaving
     * the tests below with nothing to check.
     */
    @Test
    fun everyTranslationHasBothDescriptions() {
        assertThat(TRANSLATIONS).isNotEmpty()
        for (dir in TRANSLATIONS) {
            val short = File(dir, "short_description.txt")
            val full = File(dir, "full_description.txt")
            assertThat("${dir.name} has both=${short.exists() && full.exists()}")
                .isEqualTo("${dir.name} has both=true")
            val line = short.readText().trim()
            assertWithMessage("${dir.name} short description is one line").that(line).doesNotContain("\n")
            assertWithMessage("${dir.name} short description length")
                .that(line.codePointCount(0, line.length)).isAtMost(80)
            assertThat(full.readText().trim()).isNotEmpty()
        }
    }

    /**
     * A translation keeps the English layout: the same paragraphs in the same
     * order, and a bullet list with the same number of bullets wherever the
     * English has one. The one planned difference is the last list, the
     * limits, where every translation adds a bullet saying the app itself is
     * in English. This catches a bullet added to the English, or dropped from
     * a translation, even after TRANSLATED_FROM was updated.
     */
    @Test
    fun everyTranslationKeepsTheEnglishLayout() {
        val english = shape(File(ENGLISH, "full_description.txt").readText())
        val lastList = english.indexOfLast { it > 0 }
        val expected = english.mapIndexed { i, bullets -> if (i == lastList) bullets + 1 else bullets }
        for (dir in TRANSLATIONS) {
            assertWithMessage("${dir.name}: bullets per paragraph, 0 for prose")
                .that(shape(File(dir, "full_description.txt").readText()))
                .isEqualTo(expected)
        }
    }

    /**
     * Numbers, file extensions, capitalised names and links are the same in
     * every language, so each one the English uses has to appear in every
     * translation. This is what catches a new size or minimum version that
     * reached the English and some of the translations but not all of them.
     * MB is the exception, because French writes Mo.
     */
    @Test
    fun everyTranslationKeepsTheEnglishNumbersNamesAndLinks() {
        val english = File(ENGLISH, "full_description.txt").readText()
        val found = listOf(
            """\d+(?:\.\d+)?""", // 5, 8.0, 125
            """\.[a-z]{2,4}""", // .docx, .xls
            """[A-Z][A-Z0-9]+""", // PDF, MP4, INTERNET
            """https://\S+""",
        ).flatMap { pattern -> standalone(pattern).findAll(english).map { it.value }.toList() }
        val names = NAMES.filter { standalone(Regex.escape(it)).containsMatchIn(english) }
        val tokens = (found + names).toSet() - "MB"

        val missing = TRANSLATIONS.associate { dir ->
            val text = File(dir, "full_description.txt").readText()
            dir.name to tokens.filterNot { standalone(Regex.escape(it)).containsMatchIn(text) }
        }.filterValues { it.isNotEmpty() }
        assertThat(missing).isEmpty()
    }

    /**
     * The changelog has a heading for the version the build declares, in the
     * form every release has used: `## 1.17 (2026-09-13)`. The release commit
     * turns `## Unreleased` into it, so a commit that bumps versionName and
     * leaves the notes under Unreleased fails here. An Unreleased section does
     * not count, since between releases there always is one.
     */
    @Test
    fun theChangelogAccountsForTheCurrentVersion() {
        val heading = Regex(
            """^## ${Regex.escape(VERSION_NAME)} \(\d{4}-\d{2}-\d{2}\)$""",
            RegexOption.MULTILINE,
        )
        val documented = heading.containsMatchIn(CHANGELOG)
        assertThat("changelog has ## $VERSION_NAME (YYYY-MM-DD): $documented")
            .isEqualTo("changelog has ## $VERSION_NAME (YYYY-MM-DD): true")
    }

    /**
     * Zero permissions is the whole promise, and app/build.gradle.kts fails
     * the build if the merged manifest requests one. That gate is the thing
     * that must not quietly disappear, so its absence fails here too.
     */
    @Test
    fun thePermissionGateIsStillWiredToBothOutputs() {
        val build = code(BUILD_FILE, "//")
        assertThat(build).contains("checkPermissions")
        assertThat(build).contains("assemble\$suffix")
        assertThat(build).contains("bundle\$suffix")
    }

    /**
     * R8 shipped in 1.13 and the mapping file is the only way a pasted stack
     * trace can be read back. Line numbers survive it because of an explicit
     * keep rule; losing that turns every crash report into hex.
     */
    @Test
    fun releaseBuildsKeepTheirLineNumbers() {
        val rules = code(File(REPO, "app/proguard-rules.pro").readText(), "#")
        assertThat(rules).contains("-keepattributes SourceFile,LineNumberTable")
        assertThat(code(BUILD_FILE, "//")).contains("isMinifyEnabled = true")
    }

    /**
     * [text] without its comment lines, the ones starting with [marker], so a
     * guard above is not satisfied by the line it guards having been commented
     * out.
     */
    private fun code(text: String, marker: String): String =
        text.lines().filterNot { it.trimStart().startsWith(marker) }.joinToString("\n")

    /** Each paragraph of a description as its number of "- " bullets, 0 for prose. */
    private fun shape(text: String): List<Int> =
        text.trim().split(Regex("""\n\s*\n""")).map { paragraph ->
            val lines = paragraph.lines()
            if (lines.all { it.startsWith("- ") }) lines.size else 0
        }

    /**
     * A match that is not part of a longer run of ASCII letters and digits, so
     * the 3 in MP3 is not a 3, and a Japanese character beside a number still
     * counts as a boundary.
     */
    private fun standalone(pattern: String) =
        Regex("""(?<![0-9A-Za-z])(?:$pattern)(?![0-9A-Za-z])""")
}

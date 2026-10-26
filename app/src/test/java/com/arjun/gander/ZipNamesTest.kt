package com.arjun.gander

import com.google.common.truth.Truth.assertThat
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.CRC32
import org.junit.Test

/**
 * Names inside a zip that the zip does not say how to read. Issue #30.
 *
 * The four fixture archives were written by Python's own codecs, a separate implementation
 * from the JVM's, in the code page each name would have on the machine that made it. The
 * other cases build their bytes here, which is fair for what is under test: not the codecs,
 * but the choice of which one to use.
 *
 * Each archive is read on phones set to more than one language, because the language is only
 * meant to settle a tie. An archive whose names are unmistakably one script has to come out
 * right on a phone set to any language at all.
 */
class ZipNamesTest {

    private val chinese = Locale.SIMPLIFIED_CHINESE
    private val taiwan = Locale.TRADITIONAL_CHINESE
    private val japanese = Locale.JAPAN
    private val korean = Locale.KOREA
    private val russian = Locale.forLanguageTag("ru-RU")
    private val turkish = Locale.forLanguageTag("tr-TR")
    private val english = Locale.US

    private fun paths(fixture: String, locale: Locale): List<String> {
        val raf = RandomAccessFile(Fixtures.file(fixture), "r")
        return ZipSource(raf.channel, 0, raf.length(), raf).use {
            ZipReader.entries(it, locale).map { e -> e.path }
        }
    }

    private fun legacy(names: List<String>, charset: String): List<RawName> =
        names.map { RawName(it.toByteArray(Charset.forName(charset)), utf8 = false) }

    private fun decode(names: List<String>, charset: String, locale: Locale) =
        ZipNames.decode(legacy(names, charset), locale)

    // ---------------------------------------------------------------
    // Written on Windows, read anywhere
    // ---------------------------------------------------------------

    @Test
    fun chineseNamesFromWindowsReadAsChineseWhateverThePhoneIsSetTo() {
        listOf(chinese, english, russian, japanese).forEach { locale ->
            assertThat(paths("names-gbk.zip", locale))
                .containsExactly("季度报告/会议记录.txt", "照片/北京旅行.txt").inOrder()
        }
    }

    @Test
    fun russianNamesFromWindowsReadAsRussianWhateverThePhoneIsSetTo() {
        listOf(russian, english, chinese).forEach { locale ->
            assertThat(paths("names-cp866.zip", locale))
                .containsExactly("Документы/Отчёт за квартал.txt", "Фото/Москва.txt").inOrder()
        }
    }

    /**
     * The case strict decoding gets wrong: every one of these bytes is valid GBK, and reads
     * as Chinese characters that exist. They are rare ones, though, from the part of GBK that
     * everyday Chinese never reaches, which is what gives it away even on a Chinese phone.
     */
    @Test
    fun japaneseNamesAreNotReadAsChineseEvenOnAChinesePhone() {
        listOf(japanese, chinese, english).forEach { locale ->
            assertThat(paths("names-sjis.zip", locale))
                .containsExactly("資料/報告書.txt", "資料/議事録.txt").inOrder()
        }
    }

    /** macOS writes UTF-8 and does not set the flag that says so. */
    @Test
    fun namesFromAMacReadAsUtf8() {
        listOf(english, chinese, russian).forEach { locale ->
            assertThat(paths("names-mac.zip", locale)).containsExactly("Отчёт/报告 résumé.txt")
        }
    }

    @Test
    fun traditionalChineseReadsAsBig5() {
        val names = listOf("文件/季度報告.pdf", "文件/會議記錄.txt")
        assertThat(decode(names, "Big5", taiwan)).isEqualTo(names)
        assertThat(decode(names, "Big5", english)).isEqualTo(names)
    }

    @Test
    fun koreanReadsAsKoreanOnAKoreanPhone() {
        val names = listOf("문서/분기 보고서.pdf", "문서/회의록.txt")
        assertThat(decode(names, "x-windows-949", korean)).isEqualTo(names)
    }

    /**
     * Every byte of a Korean Windows name is also an everyday Chinese character in GBK, so
     * this used to read as Chinese on every phone not set to Korean. What gives it away is
     * that read as Korean it is the syllables Korean is mostly written in, and read as Chinese
     * it is characters Chinese rarely uses.
     */
    @Test
    fun koreanNamesFromWindowsReadAsKoreanWhateverThePhoneIsSetTo() {
        listOf(korean, english, chinese, taiwan, japanese, russian).forEach { locale ->
            assertThat(paths("names-korean.zip", locale))
                .containsExactly("문서/분기 보고서.pdf", "사진/제주도 여행.jpg").inOrder()
        }
    }

    /** One short name is the hardest case, since there is least to go on. */
    @Test
    fun aSingleKoreanNameReadsAsKoreanOnAnEnglishPhone() {
        listOf("보고서.pdf", "사진", "회의록.txt", "새 폴더").forEach { name ->
            assertThat(decode(listOf(name), "x-windows-949", english)).containsExactly(name)
        }
    }

    /**
     * And the change that fixed Korean must not have cost Chinese anything: a single short
     * Chinese name on a Korean phone, where Korean is tried first, and on a Russian one, where
     * GBK read as Windows Cyrillic scores as well as Chinese does.
     */
    @Test
    fun aSingleChineseNameStillReadsAsChineseOnKoreanAndRussianPhones() {
        listOf("报告.pdf", "照片", "会议记录.txt", "新建文件夹").forEach { name ->
            listOf(korean, russian, english).forEach { locale ->
                assertThat(decode(listOf(name), "GBK", locale)).containsExactly(name)
            }
        }
    }

    /**
     * Code page 437 read as CP866 is every bit as valid, and every accented letter becomes a
     * Cyrillic one. What gives it away is where they sit: inside words of plain Latin letters,
     * where Cyrillic never is. So a Russian phone still reads a French name as French.
     */
    @Test
    fun westernNamesAreNotReadAsCyrillicOnARussianPhone() {
        val names = listOf("Café menu/Crème brûlée.txt", "Café menu/Résumé.pdf")
        assertThat(decode(names, "IBM437", russian)).isEqualTo(names)
        assertThat(decode(names, "IBM437", english)).isEqualTo(names)
    }

    /**
     * The same trap with the double-byte code pages, which is worse: read two bytes to a
     * character, a Western name's accented letter pairs with the letter beside it into a real
     * and everyday Chinese or Japanese one. "Größe.xlsx" came out "Gr批e.xlsx" and "Año
     * 2024.xlsx" came out "A寸 2024.xlsx", on every phone but a Turkish or Central European
     * one, because both readings are made of characters people write and the tie went by the
     * order of the candidates. What gives it away is again where they sit, alone against a
     * word of English letters, and that the character's own bytes still spell the accented
     * letter and the one after it when read the Western way.
     */
    @Test
    fun aWesternNameFromWindowsIsNotReadAsChineseOrJapanese() {
        val names = listOf(
            "Größe.xlsx", "Crème.docx", "Año 2024.xlsx", "Mañana.txt", "Señor.docx",
            "Björk.mp3", "Räkning.pdf", "Ação.pdf",
        )
        listOf(english, chinese, japanese, korean, taiwan).forEach { locale ->
            names.forEach { name ->
                assertThat(decode(listOf(name), "IBM850", locale)).containsExactly(name)
            }
        }
    }

    /**
     * And in the shapes file names take. An underscore after a word's last letter can be the
     * second half of a character, and a rule that counted only a letter after the accented one
     * read the first two as "Pi誉bello.txt" and "Irm鑰2024.jpg". Words run together put a
     * capital straight after a small letter, and a rule that counted no capital there read the
     * third as "Malm認oto.jpg".
     */
    @Test
    fun aWesternNameWithAnUnderscoreOrRunTogetherIsNotReadAsChineseOrJapanese() {
        listOf(english, chinese, japanese, korean, taiwan).forEach { locale ->
            listOf("Più_bello.txt", "Irmã_2024.jpg", "MalmöFoto.jpg").forEach { name ->
                assertThat(decode(listOf(name), "IBM850", locale)).containsExactly(name)
            }
        }
    }

    /** And a name that really is written in one of them still reads as itself on those phones. */
    @Test
    fun aNameThatIsReallyCjkStillReadsAsItself() {
        listOf(english, chinese, japanese, korean).forEach { locale ->
            assertThat(decode(listOf("テスト.txt"), "windows-31j", locale)).containsExactly("テスト.txt")
            assertThat(decode(listOf("会議資料.docx"), "windows-31j", locale)).containsExactly("会議資料.docx")
            assertThat(decode(listOf("iPhone写真.jpg"), "windows-31j", locale)).containsExactly("iPhone写真.jpg")
            assertThat(decode(listOf("工作总结.docx"), "GBK", locale)).containsExactly("工作总结.docx")
        }
    }

    /**
     * One character against an English word is how a great many real names are written: an
     * edition, a file, a plan, a proposal. Where it stood was once the whole test for a
     * Western name misread, and these came out as whatever else their bytes spell, even on a
     * phone set to their own language: "PDF░ц.pdf", "WordАЙ.docx". What tells them apart is
     * that their bytes do not spell an accented letter in its word when read the Western way.
     */
    @Test
    fun oneCharacterAgainstAnEnglishWordStillReadsAsItself() {
        listOf(english, chinese, japanese, korean).forEach { locale ->
            assertThat(decode(listOf("Word檔.docx"), "Big5", locale)).containsExactly("Word檔.docx")
            assertThat(decode(listOf("B안.docx"), "x-windows-949", locale)).containsExactly("B안.docx")
            assertThat(decode(listOf("A案.pptx"), "windows-31j", locale)).containsExactly("A案.pptx")
        }
        // Not on a Korean phone, where the same two bytes are 경, a syllable as everyday as 版
        // is a character, and one name leaves the phone's language to settle it
        listOf(english, chinese, japanese).forEach { locale ->
            assertThat(decode(listOf("PDF版.pdf"), "GBK", locale)).containsExactly("PDF版.pdf")
        }
    }

    /**
     * Read the Western way, each of these characters is two letters, "AÄð", "Webùp", "ITòö" and
     * "WordãÃ", but not as any word has them: a capital then a small letter in a word of
     * capitals, a grave u before another letter, a small letter after a word of capitals, and
     * a small letter then an accented capital.
     */
    @Test
    fun oneCharacterWhoseBytesAreLettersOutOfPlaceStillReadsAsItself() {
        listOf(english, chinese, japanese, korean).forEach { locale ->
            listOf("A社.pdf", "Web用.docx", "IT部.xlsx").forEach { name ->
                assertThat(decode(listOf(name), "windows-31j", locale)).containsExactly(name)
            }
            assertThat(decode(listOf("Word판.docx"), "x-windows-949", locale)).containsExactly("Word판.docx")
        }
    }

    /** Not Explorer, but some Russian tools wrote zips in the Windows code page instead. */
    @Test
    fun russianInTheWindowsCodePageIsNotReadAsDos() {
        val names = listOf("Документы/Отчёт за квартал.txt", "Фото/Москва.txt")
        assertThat(decode(names, "windows-1251", russian)).isEqualTo(names)
    }

    /** Joins only on a Turkish phone; everywhere else it would only be a way to be wrong. */
    @Test
    fun turkishNamesReadAsTurkishOnATurkishPhone() {
        val names = listOf("Şirket raporları/Ağustos özeti.txt")
        assertThat(decode(names, "IBM857", turkish)).isEqualTo(names)
    }

    // ---------------------------------------------------------------
    // Chosen by hand
    // ---------------------------------------------------------------

    /** When the guess is wrong, the reader's choice is used for every name that did not say. */
    @Test
    fun aCodePageChosenByHandIsUsedInPlaceOfTheGuess() {
        val raw = legacy(listOf("季度报告/会议记录.txt"), "GBK")
        val big5 = Charset.forName("Big5")
        val read = ZipNames.read(raw, chinese, chosen = big5)
        assertThat(read.codePage).isEqualTo(big5)
        assertThat(read.names).containsExactly(String(raw[0].bytes, big5))
        assertThat(read.names).doesNotContain("季度报告/会议记录.txt")
    }

    /** A name that says it is UTF-8 is read as UTF-8 whatever is chosen. The writer knew. */
    @Test
    fun aChoiceNeverRereadsANameThatSaidWhatItWas() {
        val flagged = RawName("季度报告.txt".toByteArray(Charsets.UTF_8), utf8 = true)
        val read = ZipNames.read(listOf(flagged), english, chosen = Charset.forName("IBM866"))
        assertThat(read.names).containsExactly("季度报告.txt")
    }

    /**
     * The code page is only reported where a name needed one, which is what decides whether
     * the reader is offered the choice at all.
     */
    @Test
    fun theCodePageIsReportedOnlyWhereANameNeededOne() {
        assertThat(ZipNames.read(legacy(listOf("notes.txt"), "US-ASCII"), english).codePage).isNull()
        val flagged = RawName("Отчёт.txt".toByteArray(Charsets.UTF_8), utf8 = true)
        assertThat(ZipNames.read(listOf(flagged), english).codePage).isNull()
        assertThat(ZipNames.read(legacy(listOf("季度报告.txt"), "GBK"), english).codePage)
            .isEqualTo(Charset.forName("GBK"))
        assertThat(ZipNames.read(legacy(listOf("Отчёт.txt"), "UTF-8"), english).codePage)
            .isEqualTo(Charsets.UTF_8)
    }

    /** Every choice the menu offers is one the runtime has, under a key of its own. */
    @Test
    fun everyChoiceIsARealCodePage() {
        val keys = ZipNames.CHOICES.map { it.first }
        assertThat(keys).containsNoDuplicates()
        assertThat(keys.first()).isEqualTo("utf8")
        assertThat(keys).containsAtLeast("gbk", "big5", "sjis", "korean", "cp866", "cp437")
        ZipNames.CHOICES.forEach { (key, charset) ->
            assertThat(ZipNames.keyOf(charset)).isEqualTo(key)
        }
    }

    // ---------------------------------------------------------------
    // What the archive does say
    // ---------------------------------------------------------------

    @Test
    fun theUtf8FlagIsTakenAtItsWord() {
        val name = "季度报告.txt"
        val raw = RawName(name.toByteArray(Charsets.UTF_8), utf8 = true)
        assertThat(ZipNames.decode(listOf(raw), russian)).containsExactly(name)
    }

    /**
     * 7-Zip writes names that fit the machine's code page in it, and the rest as flagged UTF-8,
     * in the same archive. Each is read the way it was written.
     */
    @Test
    fun flaggedAndUnflaggedNamesInOneArchiveAreEachReadTheirOwnWay() {
        val flagged = RawName("Отчёт.txt".toByteArray(Charsets.UTF_8), utf8 = true)
        val gbk = legacy(listOf("季度报告/会议记录.txt", "照片/北京旅行.txt"), "GBK")
        assertThat(ZipNames.decode(listOf(flagged) + gbk, english))
            .containsExactly("Отчёт.txt", "季度报告/会议记录.txt", "照片/北京旅行.txt").inOrder()
    }

    /** Info-ZIP's Unicode Path field, when it still describes the name beside it. */
    @Test
    fun theUnicodePathFieldWinsWhenItsChecksumMatches() {
        val legacyName = "Otchet.txt".toByteArray()
        val field = byteArrayOf(1) + le32(crc(legacyName)) + "Отчёт.txt".toByteArray()
        assertThat(ZipNames.decode(listOf(RawName(legacyName, false, field)), english))
            .containsExactly("Отчёт.txt")
    }

    /** One left behind by a tool that renamed the entry and did not know about the field. */
    @Test
    fun aStaleUnicodePathFieldIsIgnored() {
        val legacyName = "Renamed.txt".toByteArray()
        val field = byteArrayOf(1) + le32(crc("Otchet.txt".toByteArray())) + "Отчёт.txt".toByteArray()
        assertThat(ZipNames.decode(listOf(RawName(legacyName, false, field)), english))
            .containsExactly("Renamed.txt")
    }

    @Test
    fun asciiNamesAreLeftAlone() {
        val raw = legacy(listOf("reports/q3.pdf", "notes.txt"), "US-ASCII")
        assertThat(ZipNames.decode(raw, chinese)).containsExactly("reports/q3.pdf", "notes.txt")
    }

    /** Bytes that no candidate can read still come out as a name, not as an exception. */
    @Test
    fun namesNothingCanReadStillComeOut() {
        val raw = listOf(RawName(byteArrayOf(0x81.toByte(), 0x7F, 0xFF.toByte(), 0x80.toByte()), false))
        assertThat(ZipNames.decode(raw, english)).hasSize(1)
    }

    private fun crc(bytes: ByteArray): Long = CRC32().apply { update(bytes) }.value

    private fun le32(value: Long) = ByteArray(4) { i -> (value ushr (8 * i)).toByte() }
}

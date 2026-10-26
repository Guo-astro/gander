package com.arjun.gander

import java.io.BufferedReader
import java.io.FileDescriptor
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.InputStreamReader
import java.io.PrintStream
import java.io.RandomAccessFile
import java.nio.charset.Charset
import java.util.Locale
import kotlin.random.Random

/**
 * The reading half of scripts/zip-fuzz/fuzz.py, which throws broken zips at the zip reader the
 * way scripts/prose-corpus/fuzz.py throws broken documents at the prose readers.
 *
 * Not a test. fuzz.py starts it as a program, hands it one case a line and reads one verdict a
 * line back, and starts it again when a case outlives its time or takes it down. It lives
 * beside the tests because they can see what `internal` hides, and so that a change to the
 * reader it no longer compiles against breaks the build rather than the next fuzzing run.
 *
 * Each case is read the way Gander reads a zip: the index as ArchiveBrowser lists it, the tree
 * it draws, and every file opened and read to its end as ArchiveProvider pumps one to a viewer.
 * A reader meeting a zip it cannot read has one acceptable answer, an IOException, which every
 * caller turns into a card. Anything else thrown is a bug wherever it would have been caught,
 * and so is running out of memory, taking more than [ALLOCATION_BUDGET] to read a small file,
 * or handing back something the rest of the app is promised never to see.
 */
internal object ZipFuzz {

    /** Far more than any case needs; the files fuzzed are tens of kilobytes. */
    private const val ALLOCATION_BUDGET = 256L * 1024 * 1024

    /** Read no further than this out of any one file, since a size can honestly be huge. */
    private const val READ_CAP = 32L * 1024 * 1024

    private const val MAX_FILES = 64
    private const val MAX_FOLDERS = 10_000

    private val LOCALES = listOf(
        Locale.US, Locale.SIMPLIFIED_CHINESE, Locale.TRADITIONAL_CHINESE, Locale.JAPAN, Locale.KOREA,
        Locale.forLanguageTag("ru-RU"), Locale.forLanguageTag("el-GR"), Locale.forLanguageTag("tr-TR"),
        Locale.forLanguageTag("pl-PL"),
    )

    /**
     * How much this thread has allocated so far. By reflection: the tests compile against
     * android.jar, which has no java.lang.management, and this runs on a desktop JVM, which does.
     */
    private val allocated: () -> Long = run {
        val threads = Class.forName("java.lang.management.ManagementFactory").getMethod("getThreadMXBean").invoke(null)
        val method = Class.forName("com.sun.management.ThreadMXBean").getMethod("getCurrentThreadAllocatedBytes")
        return@run { method.invoke(threads) as Long }
    }

    /**
     * What [walk] allocated, which is the harness's cost and not the reader's: the list is walked
     * a folder a tap, while this visits every folder from the top, and on a deep tree that is
     * the square of its depth.
     */
    private var walked = 0L

    private class Verdict(val outcome: String, val detail: String)

    /** Something the reader handed back that nothing downstream is ready for. */
    private class Broken(message: String) : Exception(message)

    @JvmStatic
    fun main(args: Array<String>) {
        val verdicts = PrintStream(FileOutputStream(FileDescriptor.out), true, "UTF-8")
        // Whatever else prints must not be read as a verdict
        System.setOut(System.err)
        val cases = BufferedReader(InputStreamReader(System.`in`, Charsets.UTF_8))
        while (true) {
            val line = cases.readLine() ?: break
            val verdict = try {
                judge(line.split('\t'))
            } catch (e: OutOfMemoryError) {
                Verdict("memory", describe(e))
            } catch (e: Broken) {
                Verdict("invariant", e.message.orEmpty())
            } catch (e: Throwable) {
                Verdict("escaped", describe(e))
            }
            verdicts.println("${verdict.outcome}\t${verdict.detail.replace('\t', ' ').replace('\n', ' ')}")
        }
    }

    private fun judge(case: List<String>): Verdict {
        val before = allocated()
        val verdict = when (case[0]) {
            "zip" -> zip(case[1], case[2].ifEmpty { null }, LOCALES[case[3].toInt() % LOCALES.size], chosen(case[4]))
            "deflate64" -> deflate64(case[1])
            "names" -> names(case[1].toLong())
            else -> throw IllegalArgumentException("no mode ${case[0]}")
        }
        val used = allocated() - before - walked
        walked = 0
        if (used > ALLOCATION_BUDGET) {
            return Verdict("memory", "${used / (1024 * 1024)} MB allocated: ${verdict.detail}")
        }
        return verdict
    }

    private fun chosen(key: String): Charset? = ZipNames.CHOICES.firstOrNull { it.first == key }?.second

    private fun zip(path: String, password: String?, locale: Locale, chosen: Charset?): Verdict {
        RandomAccessFile(path, "r").use { file ->
            val source = ZipSource(file.channel, 0, file.length(), file)
            val index = try {
                ZipReader.index(source, locale, chosen)
            } catch (e: IOException) {
                return Verdict("refused", describe(e))
            }
            index.entries.forEach(::holds)
            walk(ArchiveTree(index.entries))

            var opened = 0
            var refused = 0
            for (entry in index.entries.filter { !it.isDirectory && it.readable }.take(MAX_FILES)) {
                val given = if (entry.location.lock.opensWithPassword) password else null
                try {
                    ZipReader.open(source, entry.location, given).use { read(it, entry) }
                    opened++
                } catch (e: IOException) {
                    refused++
                }
            }
            return Verdict("read", "${index.entries.size} entries, $opened read, $refused refused")
        }
    }

    /** What ZipReader promises of every entry it lists, see [ZipReader.normalise]. */
    private fun holds(entry: ArchiveEntry) {
        val path = entry.path
        if (path.isEmpty()) throw Broken("an empty path")
        if (path.split('/').any { it.isEmpty() || it == "." || it == ".." }) {
            throw Broken("a path that climbs or has an empty part: ${path.take(80)}")
        }
        if (path.any { Character.isISOControl(it) || it in '\u202A'..'\u202E' || it in '\u2066'..'\u2069' }) {
            throw Broken("a path with a control or reordering character")
        }
        val at = entry.location
        if (at.size < 0 || at.compressedSize < 0 || at.headerOffset < 0) {
            throw Broken("a negative size or offset: ${at.size} ${at.compressedSize} ${at.headerOffset}")
        }
    }

    /** Every folder the list could show, as the list would show it. */
    private fun walk(tree: ArchiveTree) {
        val before = allocated()
        val queue = ArrayDeque(listOf(""))
        var seen = 0
        while (queue.isNotEmpty() && seen < MAX_FOLDERS) {
            val folder = queue.removeFirst()
            if (!tree.has(folder)) throw Broken("a folder the tree lists and does not have: ${folder.take(80)}")
            val (folders, _) = tree.list(folder)
            // Past a few thousand characters a path is only the harness's own cost: the list is
            // walked a folder a tap, and building every path of a deep tree is quadratic
            if (folder.length < 4096) folders.forEach { queue += if (folder.isEmpty()) it else "$folder/$it" }
            seen++
        }
        walked += allocated() - before
    }

    /** [entry] read to its end, or to [READ_CAP], and held to its size when read to its end. */
    private fun read(input: InputStream, entry: ArchiveEntry) {
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (total < READ_CAP) {
            val n = input.read(buffer)
            if (n < 0) {
                if (total != entry.size) throw Broken("read $total bytes of a file listed as ${entry.size}")
                return
            }
            if (n == 0) throw Broken("a read of nothing, which a caller would repeat forever")
            total += n
        }
    }

    private fun deflate64(path: String): Verdict {
        Deflate64(FileInputStream(path).buffered()).use { input ->
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (total < READ_CAP) {
                val n = try {
                    input.read(buffer)
                } catch (e: IOException) {
                    return Verdict("refused", describe(e))
                }
                if (n < 0) return Verdict("read", "$total bytes")
                if (n == 0) throw Broken("a read of nothing, which a caller would repeat forever")
                total += n
            }
            return Verdict("read", "$total bytes, capped")
        }
    }

    /**
     * Names made up from [seed], as bytes nothing wrote on purpose, under every code page a
     * phone might guess or be told, and passwords the same way.
     */
    private fun names(seed: Long): Verdict {
        val rng = Random(seed)
        var decoded = 0
        repeat(20) {
            val raws = List(rng.nextInt(1, 40)) { rawName(rng) }
            for (locale in LOCALES) {
                val names = ZipNames.read(raws, locale).names
                if (names.size != raws.size) throw Broken("${raws.size} names in, ${names.size} out")
                decoded += names.size
                for (name in names) {
                    val clean = ZipReader.normalise(name, rng.nextBoolean())
                    if (clean.split('/').any { it == "." || it == ".." } || clean.startsWith("/")) {
                        throw Broken("a name normalised to one that climbs: ${clean.take(80)}")
                    }
                }
            }
            val (_, charset) = ZipNames.CHOICES[rng.nextInt(ZipNames.CHOICES.size)]
            ZipNames.read(raws, LOCALES[rng.nextInt(LOCALES.size)], charset)
            ZipNames.keyOf(charset)
            ZipEncryption.passwordBytes(text(rng), LOCALES[rng.nextInt(LOCALES.size)])
        }
        return Verdict("read", "$decoded names")
    }

    private fun rawName(rng: Random): RawName {
        val length = when (rng.nextInt(10)) {
            0 -> 0
            1 -> rng.nextInt(1000, 4000)
            else -> rng.nextInt(1, 60)
        }
        val bytes = when (rng.nextInt(4)) {
            // Anything at all
            0 -> rng.nextBytes(length)
            // Lead bytes of the double-byte code pages, and what can follow them
            1 -> ByteArray(length) { (if (it % 2 == 0) rng.nextInt(0x81, 0xFF) else rng.nextInt(0x40, 0xFF)).toByte() }
            // Mostly ASCII, with folders and the odd byte above it
            2 -> ByteArray(length) {
                when (rng.nextInt(12)) {
                    0 -> '/'.code.toByte()
                    1 -> '\\'.code.toByte()
                    2 -> '.'.code.toByte()
                    3 -> rng.nextInt(0x80, 0x100).toByte()
                    else -> rng.nextInt(0x20, 0x7F).toByte()
                }
            }
            // Real text in some encoding, cut anywhere
            else -> text(rng).toByteArray(
                listOf(Charsets.UTF_8, Charset.forName("GBK"), Charset.forName("Shift_JIS"))[rng.nextInt(3)]
            ).let { it.copyOf(minOf(it.size, length.coerceAtLeast(1))) }
        }
        val unicodePath = when (rng.nextInt(4)) {
            0 -> null
            // A well-formed field that matches the name, with text that may not be UTF-8
            1 -> byteArrayOf(1) + crc(bytes) + rng.nextBytes(rng.nextInt(0, 40))
            2 -> byteArrayOf(1) + crc(bytes) + text(rng).toByteArray()
            else -> rng.nextBytes(rng.nextInt(0, 12))
        }
        return RawName(bytes, rng.nextInt(4) == 0, unicodePath)
    }

    private fun crc(bytes: ByteArray): ByteArray {
        val value = java.util.zip.CRC32().apply { update(bytes) }.value
        return ByteArray(4) { (value ushr (8 * it)).toByte() }
    }

    /** Characters from all over, lone surrogates included, which no encoder can write. */
    private fun text(rng: Random): String = buildString {
        repeat(rng.nextInt(0, 24)) {
            append(
                when (rng.nextInt(6)) {
                    0 -> rng.nextInt(0x20, 0x7F).toChar()
                    1 -> rng.nextInt(0x400, 0x500).toChar()
                    2 -> rng.nextInt(0x4E00, 0x9FFF).toChar()
                    3 -> rng.nextInt(0xAC00, 0xD7A4).toChar()
                    4 -> rng.nextInt(0xD800, 0xE000).toChar()
                    else -> rng.nextInt(0, 0x10000).toChar()
                }
            )
        }
    }

    /** The throwable and the first place in Gander it came through, which is what sorts one bug from another. */
    private fun describe(e: Throwable): String {
        val here = e.stackTrace.firstOrNull { it.className.startsWith("com.arjun.gander") && !it.className.contains("ZipFuzz") }
        val where = here?.let { " at ${it.fileName}:${it.lineNumber}" } ?: ""
        return "${e.javaClass.simpleName}: ${e.message?.take(120)}$where"
    }
}

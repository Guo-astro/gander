package com.arjun.gander

import java.io.InputStream

/** How much of a file is read to tell whether it is text, which is what git reads to tell. */
private const val TEXT_HEAD_BYTES = 8000

private const val ZERO: Byte = 0
private const val MARK_FF = 0xFF.toByte()
private const val MARK_FE = 0xFE.toByte()

/**
 * Whether a file of no known format reads as text, from its first bytes. Issue #51.
 *
 * Text has no zero byte in it and nearly everything else has one early, which is how git and
 * most editors tell the two apart. UTF-16 is half zeros and still text, so a file that opens
 * with its byte order mark counts, since the text page reads those.
 */
internal fun readsAsText(stream: InputStream): Boolean {
    val head = ByteArray(TEXT_HEAD_BYTES)
    var filled = 0
    while (filled < head.size) {
        val n = stream.read(head, filled, head.size - filled)
        if (n < 0) break
        filled += n
    }
    val utf16 = filled >= 2 &&
        ((head[0] == MARK_FF && head[1] == MARK_FE) || (head[0] == MARK_FE && head[1] == MARK_FF))
    return utf16 || (0 until filled).none { head[it] == ZERO }
}

package com.arjun.gander

import java.io.ByteArrayInputStream
import java.io.InputStream

/**
 * A PNG read by the JDK's own decoder, so that what Gander writes is checked by a reader it has
 * no part in. Through reflection, because the tests compile against Android's classes, which
 * carry no javax.imageio, though the JVM they run on does.
 */
internal class JdkPng(png: ByteArray) {
    private val image: Any = Class.forName("javax.imageio.ImageIO")
        .getMethod("read", InputStream::class.java)
        .invoke(null, ByteArrayInputStream(png))
        ?: error("not a PNG the JDK can read")

    val width: Int = image.javaClass.getMethod("getWidth").invoke(image) as Int
    val height: Int = image.javaClass.getMethod("getHeight").invoke(image) as Int

    private val getRgb = image.javaClass.getMethod("getRGB", Int::class.java, Int::class.java)

    /** The colour at [x], [y], as 0xRRGGBB. */
    fun rgb(x: Int, y: Int): Int = (getRgb.invoke(image, x, y) as Int) and 0xffffff
}

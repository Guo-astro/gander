package com.arjun.gander

import android.animation.ValueAnimator
import android.content.Context
import android.view.GestureDetector
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.view.animation.DecelerateInterpolator
import kotlin.math.hypot
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * How far in a double tap goes, in times the page fitted to the screen's width.
 *
 * Twice, as Jetpack's PDF viewer goes. It brings the body text of an A4 page to about the size
 * a phone sets its own text in, and one column of a two-column page to the width of the screen.
 */
internal const val DOUBLE_TAP_ZOOM = 2f

/**
 * Where a double tap takes a document drawn at [zoom] times its fitted width: in from the fitted
 * page or near it, and back to the fitted page from further in.
 *
 * The line between them is half way by ratio, so every double tap changes the size by at least
 * as much as that, wherever a pinch left it.
 */
internal fun doubleTapTarget(zoom: Float): Float =
    if (zoom < sqrt(DOUBLE_TAP_ZOOM)) DOUBLE_TAP_ZOOM else 1f

/**
 * The scroll offset that keeps a place in the document under the finger at [at] px along the
 * view. [place] is how far along the fitted document it is, and [range] and [extent] are the
 * document's and the view's lengths at [zoom].
 */
internal fun anchoredScroll(place: Float, zoom: Float, at: Float, range: Int, extent: Int): Int =
    (place * zoom - at).roundToInt().coerceIn(0, maxOf(0, range - extent))

/** The WebView as a double tap needs it, so the zoom can be tested against a stand-in. */
internal interface ZoomSurface {
    fun viewWidth(): Int
    fun viewHeight(): Int
    fun scrolledX(): Int
    fun scrolledY(): Int

    /** The document's width and height in px as it is zoomed now. */
    fun rangeX(): Int
    fun rangeY(): Int

    /** WebView.zoomBy, which zooms about the middle of the view, stops at its limits and throws outside 0.01 to 100. */
    fun zoomBy(factor: Float)
    fun scrollTo(x: Int, y: Int)
}

/**
 * A double tap on a PDF zooms in about the place tapped, and the next one zooms back out.
 *
 * The WebView's own double tap fits the block under the finger to the screen, which suits a web
 * page's columns. A PDF page is one block as wide as the screen, so it went in by a fifth.
 * pdf.html turns that one off with touch-action, and this is what a double tap does instead.
 */
internal class DoubleTapZoom(context: Context, private val surface: ZoomSurface) {
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var running: ValueAnimator? = null
    private var downX = 0f
    private var downY = 0f
    private var still = false

    private val taps = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        // The second tap, start to finish. A finger that goes down and drags is the WebView's
        // one-finger zoom, and a mouse's double click selects a word.
        override fun onDoubleTapEvent(e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.x
                    downY = e.y
                    still = !e.isFromSource(InputDevice.SOURCE_MOUSE)
                }
                MotionEvent.ACTION_MOVE ->
                    if (hypot(e.x - downX, e.y - downY) > slop) still = false
                MotionEvent.ACTION_UP -> if (still) zoomAbout(e.x, e.y)
            }
            return false
        }
    })

    /** A touch on the document. Every one comes through here, and none is consumed. */
    fun touched(event: MotionEvent) {
        // A finger put down takes the document over from a zoom still on its way
        if (event.actionMasked == MotionEvent.ACTION_DOWN) cancel()
        taps.onTouchEvent(event)
    }

    fun cancel() {
        running?.cancel()
        running = null
    }

    /** How many times its fitted width the document is drawn at, or null before it is laid out. */
    private fun zoom(): Float? {
        val width = surface.viewWidth()
        val range = surface.rangeX()
        return if (width > 0 && range > 0) range.toFloat() / width else null
    }

    private fun zoomAbout(x: Float, y: Float) {
        val from = zoom() ?: return
        val to = doubleTapTarget(from)
        // The place under the finger, as far along and down the fitted document
        val placeX = (surface.scrolledX() + x) / from
        val placeY = (surface.scrolledY() + y) / from
        cancel()
        running = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = ZOOM_MS
            interpolator = DecelerateInterpolator()
            addUpdateListener {
                // By ratio, so the page grows at an even rate rather than fastest at the start
                step(from * (to / from).pow(it.animatedFraction), placeX, placeY, x, y)
            }
            start()
        }
    }

    /** One frame: the document at [to] times its fitted width, with the place tapped under the finger. */
    private fun step(to: Float, placeX: Float, placeY: Float, x: Float, y: Float) {
        val now = zoom() ?: return
        // The fitted page is the WebView's floor, and asking for a little less lands on it exactly
        val by = if (to <= 1f && now > 1f) UNDER / now else to / now
        // WebView.zoomBy throws outside 0.01 to 100
        if (by != 1f) surface.zoomBy(by.coerceIn(0.02f, 50f))
        // Read back, since the WebView stops at its own limits
        val zoom = zoom() ?: return
        surface.scrollTo(
            anchoredScroll(placeX, zoom, x, surface.rangeX(), surface.viewWidth()),
            anchoredScroll(placeY, zoom, y, surface.rangeY(), surface.viewHeight()),
        )
    }

    private companion object {
        /** What Chromium gives its own double tap zoom. */
        const val ZOOM_MS = 250L

        /** A little under the fitted page, as a zoom. */
        const val UNDER = 0.9f
    }
}

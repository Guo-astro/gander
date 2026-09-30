package com.arjun.gander

import android.content.pm.ActivityInfo
import android.graphics.Color
import android.os.Build
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.VideoSize
import androidx.media3.ui.PlayerControlView
import androidx.media3.ui.PlayerView
import com.google.android.material.appbar.MaterialToolbar

/**
 * The title bar and the phone's bars over a video: they float over the picture and come and go
 * with the player's own controls. Issue #33.
 *
 * Everywhere else the title bar sits above the document and the viewer keeps clear of the phone's
 * bars, which suits a page and not a film. In landscape the three of them took about a third of
 * the screen's height, and hiding any of them where they stood would have resized the picture
 * every time the controls came and went. So for a video the picture fills the screen, the title
 * bar is lifted over its top edge with its own colour carried up behind the clock, the controls
 * keep clear of wherever the phone's bars can be, and nothing moves when the bars do. One tap
 * brings everything back, and it all goes again with the controls, unless the title bar or a menu
 * or box opened from it is in use.
 *
 * A film is dark whatever the phone is set to, so the parts over it take the colours a night-mode
 * PDF gives them, a black bar with the words and icons a phone set to dark has: a paper bar over a
 * picture is the one bright thing in the room.
 *
 * Under a screen reader nothing goes. A view that has faded out cannot be reached by swiping, and
 * the title bar holds Back and the menu; the page readout stays up there for the same reason.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
internal class VideoChrome(
    private val activity: AppCompatActivity,
    private val playerView: PlayerView,
    private val night: NightChrome,
    private val screenReader: () -> Boolean,
    /** How every other viewer keeps clear of the phone's bars, put back by [land]. */
    private val keepClear: (View) -> Unit,
) {
    private val root: ViewGroup = activity.findViewById(R.id.root)
    private val toolbar: MaterialToolbar = activity.findViewById(R.id.toolbar)
    private val saveProgress: View = activity.findViewById(R.id.saveProgress)
    // The frame the picture is in, with the things that float over a document
    private val stage = activity.findViewById<View>(R.id.container).parent as FrameLayout
    private val controls: PlayerControlView? =
        playerView.findViewById(androidx.media3.ui.R.id.exo_controller)

    /** The title bar and the save bar under it, over the top of the picture. */
    private val top = object : LinearLayout(activity) {
        // A finger on the title bar is using it, so the countdown starts again, as it does for a
        // finger on the player's own controls
        override fun dispatchTouchEvent(event: MotionEvent): Boolean {
            if (event.actionMasked == MotionEvent.ACTION_DOWN) playerView.showController()
            return super.dispatchTouchEvent(event)
        }
    }.apply { orientation = LinearLayout.VERTICAL }

    private val window = activity.window
    private val bars = WindowCompat.getInsetsController(window, window.decorView)

    // As float() found them, for land(). The night colours put the rest back themselves.
    private val toolbarAt = root.indexOfChild(toolbar)
    private val saveProgressAt = root.indexOfChild(saveProgress)
    private val toolbarElevation = toolbar.elevation
    private val darkNavigationIcons = bars.isAppearanceLightNavigationBars
    private val barsBehaviour = bars.systemBarsBehavior
    @Suppress("DEPRECATION")
    private val navigationBarColour = window.navigationBarColor
    /** How long the controls stay up untouched, as the viewer set it. */
    private val timeout = playerView.controllerShowTimeoutMs

    /** How the screen is held for full screen, or null while the phone turns it. */
    var held: Int? = null
        private set

    fun float() {
        night.show(true)
        root.removeView(toolbar)
        root.removeView(saveProgress)
        // The toolbar's surface, night's now, moves out to the strip holding it, so it reaches up
        // behind the clock, and the shadow goes with it to that strip's lower edge. Let go of
        // first, since a view letting go of a drawable clears whatever it has been given to since.
        val surface = toolbar.background
        toolbar.background = null
        toolbar.elevation = 0f
        top.background = surface
        top.elevation = toolbarElevation
        top.addView(toolbar)
        top.addView(saveProgress)
        stage.addView(top, FrameLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.TOP
        ))

        // Below Android 15 the window keeps clear of the bars itself unless told not to, and
        // paints them colours of its own over whatever is behind
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            @Suppress("DEPRECATION")
            window.statusBarColor = Color.TRANSPARENT
            @Suppress("DEPRECATION")
            window.navigationBarColor = Color.TRANSPARENT
        }
        // Pale over a picture, as the night colours have them at the top
        bars.isAppearanceLightNavigationBars = false
        // Hidden, they come back for a swipe in from the edge and go again by themselves, and a
        // tap still reaches the player
        bars.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        ViewCompat.setOnApplyWindowInsetsListener(root) { _, insets ->
            // Where the bars can be rather than where they are, so their coming and going moves
            // nothing
            val clear = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            // The controls keep clear only of what takes a tap. Three buttons at the bottom do;
            // the handle of gesture navigation takes a swipe, and taps under it still reach the
            // player, so kept clear of it the controls stopped a handle's height off the bottom.
            val tappable = insets.getInsetsIgnoringVisibility(
                WindowInsetsCompat.Type.tappableElement() or WindowInsetsCompat.Type.displayCutout()
            )
            root.setPadding(0, 0, 0, 0)
            top.setPadding(clear.left, clear.top, clear.right, 0)
            controls?.setPadding(tappable.left, tappable.top, tappable.right, tappable.bottom)
            WindowInsetsCompat.CONSUMED
        }
        ViewCompat.requestApplyInsets(root)

        playerView.setControllerVisibilityListener(
            PlayerView.ControllerVisibilityListener { visibility -> follow(visibility == View.VISIBLE) }
        )
    }

    // Offered once the video is known to be wider than it is tall
    private var offered = false

    /**
     * The full screen button, on a video wider than it is tall, which it turns to landscape. An
     * upright one, as a phone films, already fills an upright screen and landscape would only
     * shrink it, so it gets no button that seems to do nothing. None until the size is known.
     */
    fun sized(size: VideoSize) {
        val wide = widerThanTall(size)
        if (wide == offered) return
        offered = wide
        if (wide) {
            playerView.setFullscreenButtonClickListener { on ->
                hold(if (on) ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE else null)
            }
        } else {
            // The player view hands the controls a listener of its own whatever it is given, so
            // only the controls can take the button away again
            controls?.setOnFullScreenModeChangedListener(null)
            if (held != null) hold(null)
        }
    }

    /**
     * Holds the screen at [orientation] for full screen, or gives it back to the phone for null.
     * Called with what [held] was when the viewer is made again, so a video in full screen stays so.
     */
    fun hold(orientation: Int?) {
        held = orientation
        playerView.setFullscreenButtonState(orientation != null)
        activity.requestedOrientation = orientation ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    /**
     * Everything stays up while a menu or a box opened from the title bar is open over the video.
     * Either takes the window's focus, which the player's countdown knows nothing of, so the title
     * bar faded out from under the menu opened from it. The countdown starts again when it closes.
     */
    fun focusChanged(hasFocus: Boolean) {
        // Applied at once while the controls are fully up, and to their next showing otherwise
        playerView.controllerShowTimeoutMs = if (hasFocus) timeout else 0
    }

    private var controlsWereGone = false

    /**
     * Along with the player's controls, which go in two steps: the buttons after the timeout, and
     * two seconds later the line of progress left at the bottom. They say they are hidden only at
     * the second, but they report every step on the way and are fully up only before the first.
     * So everything goes as soon as they stop being fully up, and comes back the moment they
     * reappear from nothing. From the line of progress, a tap brings the title bar back once the
     * controls are fully up, since on the way in and on the way out look alike from here.
     */
    private fun follow(controlsVisible: Boolean) {
        val fullyUp = playerView.isControllerFullyVisible
        if (fullyUp) {
            // The player leaves its controls up for good if it was told to play while they were
            // still coming in: prepared first, they came up to stay until playback began, and it
            // counts the time to hide them only if they are fully up when it begins. Asked again
            // now that they are, it counts from here, or keeps them up for a paused or finished
            // video, whichever the player is by now.
            playerView.showController()
        }
        show(
            when {
                !controlsVisible -> false
                fullyUp -> true
                else -> controlsWereGone
            }
        )
        controlsWereGone = !controlsVisible
    }

    // Up when the video opens, as the controls are
    private var shown = true

    private fun show(visible: Boolean) {
        val up = visible || screenReader()
        // Asked at every step the controls take, and a fade begun again would never finish
        if (up == shown) return
        shown = up
        top.animate().cancel()
        if (up) {
            top.visibility = View.VISIBLE
            top.animate().alpha(1f).setDuration(FADE_MS).start()
            bars.show(WindowInsetsCompat.Type.systemBars())
        } else {
            top.animate().alpha(0f).setDuration(FADE_MS)
                .withEndAction { top.visibility = View.INVISIBLE }.start()
            bars.hide(WindowInsetsCompat.Type.systemBars())
        }
    }

    /** The viewer as every other file has it, for the card that stands in for a video that will not play. */
    fun land() {
        top.animate().cancel()
        stage.removeView(top)
        top.removeAllViews()
        top.background = null
        toolbar.elevation = toolbarElevation
        root.addView(toolbar, toolbarAt)
        root.addView(saveProgress, saveProgressAt)

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            WindowCompat.setDecorFitsSystemWindows(window, true)
            @Suppress("DEPRECATION")
            window.navigationBarColor = navigationBarColour
        }
        bars.show(WindowInsetsCompat.Type.systemBars())
        bars.isAppearanceLightNavigationBars = darkNavigationIcons
        bars.systemBarsBehavior = barsBehaviour
        // The card is the app's, so the parts around it go back to the phone's colours, the
        // toolbar's own surface and the status bar's with them
        night.show(false)
        keepClear(root)
        ViewCompat.requestApplyInsets(root)
        held = null
        activity.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }

    private companion object {
        /** The player's own, for its controls. */
        const val FADE_MS = 250L
    }
}

/** Whether a video of this size shows wider than it is tall, its pixels' own shape included. */
internal fun widerThanTall(size: VideoSize): Boolean =
    size.width * size.pixelWidthHeightRatio > size.height

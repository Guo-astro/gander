package com.arjun.gander

import android.app.ActivityManager
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withContentDescription
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Print, issue #45: that it reaches Android's own print screen, which only a device has. What
 * the print screen makes of each file was checked by hand, through Save as PDF.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class PrintDeviceTest {

    @get:Rule
    val retry = RetryRule()

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()

    @Before
    fun setUp() {
        DeviceFixtures.clear()
    }

    @After
    fun tearDown() {
        if (topPackage() == PRINT_SCREEN) {
            instrumentation.uiAutomation.executeShellCommand("input keyevent KEYCODE_BACK").close()
            awaitTop { it != PRINT_SCREEN }
        }
    }

    @Test
    fun aPdfGoesToThePrintScreen() {
        open("six-pages.pdf").use { scenario ->
            WebViewProbe.await(scenario, "document.querySelector('#pages .pg canvas')", "the first page")
            tapPrint()
            assertThat(awaitTop { it == PRINT_SCREEN }).isEqualTo(PRINT_SCREEN)
        }
    }

    @Test
    fun aWordFileGoesToThePrintScreen() {
        open("report.docx").use { scenario ->
            WebViewProbe.await(scenario, "document.querySelector('section.docx')", "the document")
            tapPrint()
            assertThat(awaitTop { it == PRINT_SCREEN }).isEqualTo(PRINT_SCREEN)
        }
    }

    /** Android's printing can't open it, so the viewer says so and stays. */
    @Test
    fun aPdfWithAPasswordStaysInTheViewer() {
        open("encrypted.pdf").use { scenario ->
            awaitLocked(scenario)
            tapPrint()
            Thread.sleep(3_000)
            assertThat(topPackage()).isEqualTo(instrumentation.targetContext.packageName)
        }
    }

    private fun open(fixture: String): ActivityScenario<ViewerActivity> =
        ActivityScenario.launch(DeviceFixtures.viewIntent(fixture))

    private fun tapPrint() {
        onView(withContentDescription("More options")).perform(click())
        onView(withText(R.string.print)).perform(click())
    }

    private fun awaitLocked(scenario: ActivityScenario<ViewerActivity>) {
        val deadline = System.currentTimeMillis() + 30_000
        while (System.currentTimeMillis() < deadline) {
            var locked = false
            scenario.onActivity { locked = it.printLocked }
            if (locked) return
            Thread.sleep(250)
        }
        throw AssertionError("The page never said the PDF asked for a password")
    }

    /** The package of the activity on top of Gander's own task, which is where the print screen opens. */
    private fun topPackage(): String? =
        instrumentation.targetContext.getSystemService(ActivityManager::class.java)
            .appTasks.firstOrNull()?.taskInfo?.topActivity?.packageName

    private fun awaitTop(done: (String?) -> Boolean): String? {
        val deadline = System.currentTimeMillis() + 15_000
        var top = topPackage()
        while (!done(top) && System.currentTimeMillis() < deadline) {
            Thread.sleep(250)
            top = topPackage()
        }
        return top
    }

    private companion object {
        const val PRINT_SCREEN = "com.android.printspooler"
    }
}

package com.arjun.gander

import android.app.Activity
import android.app.ActivityManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import android.provider.MediaStore
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.google.common.truth.Truth.assertThat
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Which card in the recent apps screen a file opens in (#46). Robolectric has no tasks to speak of, so only a
 * device can say.
 */
@RunWith(AndroidJUnit4::class)
@LargeTest
class RecentsEntryDeviceTest {

    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private val target get() = instrumentation.targetContext
    private var download: Uri? = null

    @After
    fun tearDown() {
        instrumentation.runOnMainSync {
            ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<ViewerActivity>()
                .forEach { it.finishAndRemoveTask() }
        }
        download?.let { target.contentResolver.delete(it, null, null) }
        DeviceFixtures.clear()
    }

    /**
     * Started from an activity, as WhatsApp starts it, so Android doesn't add a new task itself.
     * The home screen stands in for the other app.
     */
    @SdkSuppress(minSdkVersion = 29)
    @Test
    fun aFileFromAnotherAppOpensInAnEntryOfItsOwnAndBackReturnsToThatApp() {
        val view = Intent(Intent.ACTION_VIEW)
            .setClassName(target, ViewerActivity::class.java.name)
            .setDataAndType(downloadOf("six-pages.pdf"), "application/pdf")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val home = openFrom(scenario, view)
            val viewer = resumed<ViewerActivity>()
            val entry = taskOf(viewer)
            assertThat(entry).isNotEqualTo(home)
            assertThat(recents()).contains(entry)

            instrumentation.runOnMainSync { viewer.onBackPressedDispatcher.onBackPressed() }
            assertThat(taskOf(resumed<MainActivity>())).isEqualTo(home)
            waitFor("the file's card to leave recent apps") { (entry !in recents()).takeIf { it } }
        }
    }

    @Test
    fun aFileOpenedInsideGanderStaysInGandersEntry() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            val home = openFrom(scenario, DeviceFixtures.viewIntent("six-pages.pdf"))
            assertThat(taskOf(resumed<ViewerActivity>())).isEqualTo(home)
        }
    }

    /** Starts [intent] from the home screen, as a tap would, and answers the home screen's task. */
    private fun openFrom(scenario: ActivityScenario<MainActivity>, intent: Intent): Int {
        var home = -1
        scenario.onActivity { activity ->
            home = activity.taskId
            activity.startActivity(intent)
        }
        return home
    }

    private inline fun <reified T : Activity> resumed(): T = waitFor("a resumed ${T::class.java.simpleName}") {
        var found: T? = null
        instrumentation.runOnMainSync {
            found = ActivityLifecycleMonitorRegistry.getInstance()
                .getActivitiesInStage(Stage.RESUMED)
                .filterIsInstance<T>()
                .firstOrNull()
        }
        found
    }

    private fun taskOf(activity: Activity): Int {
        var task = -1
        instrumentation.runOnMainSync { task = activity.taskId }
        return task
    }

    /** The tasks of Gander's that the recent apps screen lists. */
    private fun recents(): List<Int> =
        target.getSystemService(ActivityManager::class.java).appTasks.map { it.taskInfo.taskId }

    private fun <T : Any> waitFor(what: String, probe: () -> T?): T {
        val deadline = SystemClock.uptimeMillis() + 15_000
        while (true) {
            probe()?.let { return it }
            check(SystemClock.uptimeMillis() < deadline) { "timed out waiting for $what" }
            Thread.sleep(50)
        }
    }

    /** [name] saved to Downloads, so its URI is on MediaStore's provider rather than Gander's. */
    private fun downloadOf(name: String): Uri {
        val resolver = target.contentResolver
        val pending = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, "gander-test-$name")
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, pending)!!
        download = uri
        resolver.openOutputStream(uri)!!.use { out ->
            instrumentation.context.assets.open(name).use { it.copyTo(out) }
        }
        resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
        return uri
    }
}

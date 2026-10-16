package com.arjun.gander

import android.content.Intent
import android.os.IBinder
import android.os.Process
import android.os.SystemClock
import android.system.Os
import android.system.OsConstants

/**
 * The isolated renderer failing on purpose, in debug builds only, so that a device run can watch
 * Gander come through what PDFium might do with a hostile file. Bound with an action naming the
 * fault and where it strikes: crash-open, segv-draw, hang-draw, slow-draw and so on.
 */
class FaultyPdfRenderService : PdfRenderService() {

    @Volatile
    private var fault: String? = null

    override fun onBind(intent: Intent): IBinder {
        fault = intent.action
        return super.onBind(intent)
    }

    override fun opening() = strike("open")

    override fun drawing(page: Int) = strike("draw")

    private fun strike(at: String) {
        when (fault) {
            "crash-$at" -> Process.killProcess(Process.myPid())
            // A crash as Android sees PDFium's, which a kill is not: the signal goes through the
            // crash reporter, as a fault in native code does
            "segv-$at" -> Os.kill(Process.myPid(), OsConstants.SIGSEGV)
            "hang-$at" -> {
                while (true) SystemClock.sleep(60_000)
            }
            // Eight seconds in short steps, so that what is left still runs after the process
            // has been stopped and started again
            "slow-$at" -> repeat(80) { SystemClock.sleep(100) }
        }
    }
}

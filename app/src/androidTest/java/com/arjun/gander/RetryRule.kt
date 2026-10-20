package com.arjun.gander

import android.util.Log
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Runs a test up to [attempts] times before calling it failed.
 *
 * Emulator tests flake: a renderer that has not warmed up, a frame that has
 * not landed, an animation that outlived the setting meant to disable it.
 * These tests run nightly and unattended, and a suite that cries wolf twice a
 * week is one nobody reads on the morning it is right.
 *
 * A retry hides a genuinely intermittent bug, which is the cost. The log line
 * is there so a test that only ever passes on the second attempt can be found
 * and looked at rather than quietly tolerated for months.
 */
class RetryRule(private val attempts: Int = 3) : TestRule {

    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                var last: Throwable? = null
                repeat(attempts) { attempt ->
                    try {
                        base.evaluate()
                        if (attempt > 0) {
                            Log.w(
                                "GanderTests",
                                "${description.displayName} passed on attempt " +
                                    "${attempt + 1} of $attempts"
                            )
                        }
                        return
                    } catch (skipped: AssumptionViolatedException) {
                        // A test that doesn't apply here won't on a second try either, and
                        // logging it as a failed attempt would hide the ones that flaked
                        throw skipped
                    } catch (t: Throwable) {
                        last = t
                        Log.w(
                            "GanderTests",
                            "${description.displayName} failed attempt " +
                                "${attempt + 1} of $attempts",
                            t
                        )
                    }
                }
                throw last!!
            }
        }
}

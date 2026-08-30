package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import android.os.SystemClock
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SeekingPositionIntegrationTest {
    @Test
    fun seekingRemainsCoherentThroughBackgroundAndControllerReconnect() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.playback-load-probe")
        try {
            val exercised = requireNotNull(resolver.call(probeUri, "seek-semantics", null, null))
            check(exercised.getBoolean("seekSemanticsPassed"))

            val device = UiDevice.getInstance(instrumentation)
            device.pressHome()
            SystemClock.sleep(BACKGROUND_SETTLE_MS)
            val background = requireNotNull(resolver.call(probeUri, "seek-background", null, null))
            check(background.getBoolean("seekSemanticsPassed"))

            launchApplication(instrumentation, device)
            val foreground = requireNotNull(resolver.call(probeUri, "verify-seek", null, null))
            check(foreground.getBoolean("seekSemanticsPassed"))
        } finally {
            resolver.call(probeUri, "stop", null, null)
        }
    }

    @Test
    fun pausedAndPlayingPositionsRestoreAcrossProcessLifecycle() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.playback-load-probe")
        val device = UiDevice.getInstance(instrumentation)
        try {
            val pausedPrepared = requireNotNull(
                resolver.call(probeUri, "prepare-paused-restore", null, null),
            )
            check(pausedPrepared.getBoolean("restorationPassed"))
            restartApplication(instrumentation, device)
            val pausedRestored = requireNotNull(
                resolver.call(probeUri, "verify-paused-restore", null, null),
            )
            check(pausedRestored.getBoolean("restorationPassed"))

            val playingPrepared = requireNotNull(
                resolver.call(probeUri, "prepare-playing-restore", null, null),
            )
            check(playingPrepared.getBoolean("restorationPassed"))
            restartApplication(instrumentation, device)
            val playingRestored = requireNotNull(
                resolver.call(probeUri, "verify-playing-restore", null, null),
            )
            check(playingRestored.getBoolean("restorationPassed"))
        } finally {
            resolver.call(probeUri, "stop", null, null)
        }
    }

    private fun restartApplication(
        instrumentation: android.app.Instrumentation,
        device: UiDevice,
    ) {
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        launchApplication(instrumentation, device)
    }

    private fun launchApplication(
        instrumentation: android.app.Instrumentation,
        device: UiDevice,
    ) {
        val launchIntent = requireNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), UI_TIMEOUT_MS)) {
            "LibrePlayer did not return to the foreground"
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val BACKGROUND_SETTLE_MS = 500L
        const val UI_TIMEOUT_MS = 10_000L
    }
}

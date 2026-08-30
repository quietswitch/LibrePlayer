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
class PlaybackSemanticsIntegrationTest {
    @Test
    fun playbackStateAndQueueSemantics() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.playback-load-probe")
        try {
            val exercised = requireNotNull(resolver.call(probeUri, "semantics", null, null))
            check(exercised.getBoolean("semanticsPassed"))

            val device = UiDevice.getInstance(instrumentation)
            device.pressHome()
            SystemClock.sleep(BACKGROUND_SETTLE_MS)
            val launchIntent = requireNotNull(
                instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.targetContext.startActivity(launchIntent)
            check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), UI_TIMEOUT_MS)) {
                "LibrePlayer did not return to the foreground"
            }

            val verified = requireNotNull(resolver.call(probeUri, "verify-semantics", null, null))
            check(verified.getBoolean("semanticsPassed"))
        } finally {
            resolver.call(probeUri, "stop", null, null)
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val BACKGROUND_SETTLE_MS = 500L
        const val UI_TIMEOUT_MS = 10_000L
    }
}

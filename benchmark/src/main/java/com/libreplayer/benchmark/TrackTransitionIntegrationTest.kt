package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TrackTransitionIntegrationTest {
    @Test
    fun naturalRepeatShuffleSeekAndFinalItemMatrix() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        try {
            val result = requireNotNull(resolver.call(PROBE_URI, "natural-matrix", null, null))
            check(result.getBoolean("passed"))
            println("Q2.3 natural matrix: $result")
        } finally {
            resolver.call(PROBE_URI, "reset", null, null)
        }
    }

    @Test
    fun naturalTransitionSurvivesBackgroundAndForegroundProjection() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        try {
            check(requireNotNull(resolver.call(PROBE_URI, "prepare-background", null, null)).getBoolean("passed"))
            device.pressHome()
            val background = requireNotNull(resolver.call(PROBE_URI, "verify-background", null, null))
            check(background.getBoolean("passed"))

            val launchIntent = requireNotNull(
                instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            instrumentation.targetContext.startActivity(launchIntent)
            check(device.wait(Until.hasObject(By.pkg(PACKAGE_NAME).depth(0)), UI_TIMEOUT_MS))
            val foreground = requireNotNull(resolver.call(PROBE_URI, "verify-foreground", null, null))
            check(foreground.getBoolean("passed"))
            println("Q2.3 background: $background foreground: $foreground")
        } finally {
            resolver.call(PROBE_URI, "reset", null, null)
        }
    }

    @Test
    fun controllerReconnectsAcrossAutomaticBoundary() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        try {
            val result = requireNotNull(resolver.call(PROBE_URI, "reconnect-boundary", null, null))
            check(result.getBoolean("passed"))
            println("Q2.3 reconnect: $result")
        } finally {
            resolver.call(PROBE_URI, "reset", null, null)
        }
    }

    @Test
    fun sameFormatPairsUseContinuousMedia3AudioPath() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val resolver = instrumentation.context.contentResolver
        val device = UiDevice.getInstance(instrumentation)
        for (format in listOf("wav", "flac", "mp3", "m4a")) {
            try {
                resolver.call(PROBE_URI, "reset", null, null)
                device.executeShellCommand("logcat -c")
                val result = requireNotNull(resolver.call(PROBE_URI, "format-pair", format, null))
                check(result.getBoolean("passed"))
                val audioLog = device.executeShellCommand("logcat -d -s LibrePlayerAudio:D '*:S'")
                check("input format:" in audioLog) { "$format did not expose an input format: $audioLog" }
                check("AudioTrack initialized:" in audioLog) { "$format did not initialize AudioTrack: $audioLog" }
                check("underrun:" !in audioLog) { "$format reported an audio underrun: $audioLog" }
                check("audio codec error" !in audioLog) { "$format reported a codec error: $audioLog" }
                check("audio sink error" !in audioLog) { "$format reported a sink error: $audioLog" }
                val initializedAt = audioLog.indexOf("AudioTrack initialized:")
                val releasedAt = audioLog.lastIndexOf("AudioTrack released:")
                check(releasedAt < initializedAt) {
                    "$format tore down its active AudioTrack before the adjacent item was established: $audioLog"
                }
                println("Q2.3 format=$format result=$result audio=${audioLog.lineSequence().filter { it.contains("LibrePlayerAudio") }.joinToString(" || ")}")
            } finally {
                resolver.call(PROBE_URI, "reset", null, null)
            }
        }
    }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        val PROBE_URI: Uri = Uri.parse("content://com.libreplayer.transition-probe")
        const val UI_TIMEOUT_MS = 10_000L
    }
}

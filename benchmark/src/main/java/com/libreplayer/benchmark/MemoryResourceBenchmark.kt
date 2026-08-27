package com.libreplayer.benchmark

import android.app.UiAutomation
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.util.Base64
import android.util.Log
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import java.io.FileInputStream
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class MemoryResourceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun memoryResourceAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val journey = requireNotNull(arguments.getString("journey"))
        val cycles = arguments.getString("cycles")?.toIntOrNull() ?: defaultCycles(journey)
        val settleMs = arguments.getString("settleMs")?.toLongOrNull() ?: 2_500L
        val playbackMinutes = arguments.getString("playbackMinutes")?.toIntOrNull() ?: 5
        require(journey in setOf("idle", "songs", "albums", "search", "playback"))
        require(cycles in 1..20 && settleMs in 2_000L..3_000L && playbackMinutes in 1..5)
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.memory-resource-probe")

        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Full(),
            iterations = 1,
            setupBlock = {
                killProcess()
                pressHome()
                startActivityAndWait()
                requireText(SONGS_TITLE)
                device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), 30_000L)
                when (journey) {
                    "albums" -> requireObject(By.text(ALBUMS_TITLE), "Albums navigation").click()
                    "search" -> requireObject(By.desc(SEARCH_DESCRIPTION), "Search action").click()
                    "playback" -> assertPlayback(requireNotNull(resolver.call(probeUri, "prepare", null, null)))
                }
                device.waitForIdle()
            },
        ) {
            when (journey) {
                "idle" -> idleJourney(instrumentation.uiAutomation, cycles, settleMs)
                "songs" -> scrollJourney(instrumentation.uiAutomation, journey, cycles, settleMs, FIRST_SONG_PATH)
                "albums" -> scrollJourney(instrumentation.uiAutomation, journey, cycles, settleMs, FIRST_ALBUM_TITLE)
                "search" -> searchJourney(instrumentation.uiAutomation, cycles, settleMs)
                "playback" -> playbackJourney(instrumentation.uiAutomation, resolver, probeUri, playbackMinutes)
            }
        }
    }

    private fun MacrobenchmarkScope.idleJourney(shell: UiAutomation, starts: Int, settleMs: Long) {
        repeat(starts) { index ->
            if (index > 0) {
                killProcess()
                startActivityAndWait()
                requireText(SONGS_TITLE)
            }
            SystemClock.sleep(settleMs)
            checkpoint(shell, "idle", "start-${index + 1}")
        }
        SystemClock.sleep(5_000L)
        checkpoint(shell, "idle", "final")
    }

    private fun MacrobenchmarkScope.scrollJourney(shell: UiAutomation, journey: String, cycles: Int, settleMs: Long, firstText: String) {
        SystemClock.sleep(settleMs)
        checkpoint(shell, journey, "baseline")
        repeat(cycles) { index ->
            repeat(6) { swipe(down = true) }
            check(!device.hasObject(By.text(firstText))) { "$journey did not move beyond its first fixture item" }
            repeat(6) { swipe(down = false) }
            requireText(firstText)
            SystemClock.sleep(settleMs)
            checkpoint(shell, journey, "cycle-${index + 1}")
        }
        SystemClock.sleep(5_000L)
        checkpoint(shell, journey, "final")
    }

    private fun MacrobenchmarkScope.searchJourney(shell: UiAutomation, cycles: Int, settleMs: Long) {
        requireText(SEARCH_EMPTY_TITLE)
        val field = requireObject(By.clazz("android.widget.EditText"), "Search field")
        SystemClock.sleep(settleMs)
        checkpoint(shell, "search", "baseline")
        repeat(cycles) { index ->
            SEARCH_STATES.forEach { query ->
                field.text = query
                check(device.wait(Until.hasObject(By.text(EXPECTED_SEARCH_RESULT)), 15_000L))
            }
            field.text = ""
            requireText(SEARCH_EMPTY_TITLE)
            if (index + 1 in setOf(1, 5, 10, 15, 20)) {
                SystemClock.sleep(settleMs)
                checkpoint(shell, "search", "cycle-${index + 1}")
            }
        }
        SystemClock.sleep(5_000L)
        checkpoint(shell, "search", "final")
    }

    private fun MacrobenchmarkScope.playbackJourney(shell: UiAutomation, resolver: android.content.ContentResolver, uri: Uri, minutes: Int) {
        checkpoint(shell, "playback", "minute-0")
        repeat(minutes) { index ->
            SystemClock.sleep(60_000L)
            assertPlayback(requireNotNull(resolver.call(uri, "status", null, null)))
            checkpoint(shell, "playback", "minute-${index + 1}")
        }
        val stopped = requireNotNull(resolver.call(uri, "stop", null, null))
        check(stopped.getBoolean("stopped"))
        SystemClock.sleep(5_000L)
        checkpoint(shell, "playback", "post-stop")
    }

    private fun assertPlayback(bundle: Bundle) {
        check(bundle.getBoolean("connected"))
        check(bundle.getInt("playbackState") == 3)
        check(bundle.getBoolean("playWhenReady") && bundle.getBoolean("isPlaying"))
        check(bundle.getInt("suppressionReason") == 0 && !bundle.getBoolean("hasPlayerError"))
        check(bundle.getInt("queueSize") == 20)
        check(bundle.getInt("playerErrors") == 0 && bundle.getInt("sessionDisconnects") == 0)
    }

    private fun MacrobenchmarkScope.checkpoint(shell: UiAutomation, journey: String, label: String) {
        val targetPid = shell("pidof -s $TARGET_PACKAGE", shell).trim()
        check(targetPid.matches(Regex("\\d+"))) { "Target process missing at $journey/$label" }
        val meminfo = shell("dumpsys meminfo $TARGET_PACKAGE", shell)
        val status = shell("cat /proc/$targetPid/status 2>&1", shell)
        val fds = shell("ls /proc/$targetPid/fd 2>&1", shell)
        val sessions = shell("dumpsys media_session", shell)
        val services = shell("dumpsys activity services $TARGET_PACKAGE", shell)
        val selected = buildString {
            appendLine("journey=$journey")
            appendLine("label=$label")
            appendLine("elapsedRealtimeMs=${SystemClock.elapsedRealtime()}")
            meminfo.lineSequence().filter { line -> MEMINFO_FIELDS.any(line::contains) }.forEach(::appendLine)
            status.lineSequence().filter { it.startsWith("Threads:") }.forEach(::appendLine)
            appendLine("fdProbe=${fds.trim()}")
            sessions.lineSequence().filter { line -> SESSION_FIELDS.any(line::contains) }.forEach(::appendLine)
            services.lineSequence().filter { line -> SERVICE_FIELDS.any(line::contains) }.forEach(::appendLine)
        }
        Log.i(LOG_TAG, "checkpoint=${Base64.encodeToString(selected.toByteArray(), Base64.NO_WRAP)}")
    }

    private fun shell(command: String, automation: UiAutomation): String {
        val descriptor: ParcelFileDescriptor = automation.executeShellCommand(command)
        return descriptor.use { FileInputStream(it.fileDescriptor).bufferedReader().readText() }
    }

    private fun MacrobenchmarkScope.swipe(down: Boolean) {
        val x = device.displayWidth / 2
        val low = (device.displayHeight * 0.78f).toInt()
        val high = (device.displayHeight * 0.31f).toInt()
        check(device.swipe(x, if (down) low else high, x, if (down) high else low, 40))
    }

    private fun MacrobenchmarkScope.requireText(text: String) = requireObject(By.text(text), "text '$text'")
    private fun MacrobenchmarkScope.requireObject(selector: androidx.test.uiautomator.BySelector, label: String) =
        device.wait(Until.findObject(selector), 15_000L) ?: error("Timed out waiting for $label")

    private fun defaultCycles(journey: String) = when (journey) {
        "idle" -> 5
        "songs", "albums" -> 10
        "search" -> 20
        else -> 1
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val LOG_TAG = "LibrePlayerMemory"
        const val SONGS_TITLE = "Songs"
        const val ALBUMS_TITLE = "Albums"
        const val SEARCH_DESCRIPTION = "Search library"
        const val SEARCH_EMPTY_TITLE = "Search your library"
        const val EXPECTED_SEARCH_RESULT = "Duplicate Display Metadata"
        const val FIRST_SONG_PATH = "Music/LibrePlayerBenchmark/MEDIUM/audio/artist-00093/album-00093/disc-01/"
        const val FIRST_ALBUM_TITLE = "Album 00006"
        const val UPDATING_LIBRARY_TITLE = "Updating library"
        val SEARCH_STATES = listOf("D", "Du", "Dup", "Dupl", "Duplicate")
        val MEMINFO_FIELDS = listOf("TOTAL PSS:", "Java Heap:", "Native Heap:", "Code:", "Stack:", "Graphics:", "Private Other:", "System:", "Views:", "AppContexts:", "Assets:", "Local Binders:", "Parcel memory:", "Death Recipients:", "Bitmap (malloced):")
        val SESSION_FIELDS = listOf("package=com.libreplayer", "controllers:", "state=PlaybackState", "queueTitle=")
        val SERVICE_FIELDS = listOf("ServiceRecord{", "isForeground=", "startRequested=")
    }
}

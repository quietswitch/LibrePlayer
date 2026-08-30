package com.libreplayer.benchmark

import android.os.SystemClock
import android.util.Log
import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class PhysicalReferenceBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun prepareReference() {
        val device = uiDevice()
        device.pressHome()
        val launch = device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.libreplayer.app.MainActivity",
        )
        check(launch.contains("Status: ok") || launch.contains("Warning: Activity not started")) {
            "Physical-reference activity did not launch"
        }
        requireUsableSongs(device)
        logMemory(device, "settled-idle")
        Log.i(LOG_TAG, "prepared package=$TARGET_PACKAGE")
    }

    @Test
    fun startupReference() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric()),
            compilationMode = authorityCompilationMode(),
            iterations = intArgument("iterations", 5),
            startupMode = StartupMode.COLD,
            setupBlock = { pressHome() },
        ) {
            startActivityAndWait()
            requireUsableSongs(device)
        }
        logMemory(uiDevice(), "after-startup")
    }

    @Test
    fun songsReference() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = authorityCompilationMode(),
            iterations = intArgument("iterations", 3),
            setupBlock = { prepareSongs() },
        ) {
            repeat(SCROLL_GESTURES) { humanScrollDown() }
            requireText(SONGS_TITLE)
        }
        logMemory(uiDevice(), "after-songs")
    }

    @Test
    fun albumsReference() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = authorityCompilationMode(),
            iterations = intArgument("iterations", 3),
            setupBlock = {
                prepareSongs()
                clickStable(textSelector(ALBUMS_TITLE), "Albums navigation")
                requireText(ALBUMS_TITLE)
                device.waitForIdle()
            },
        ) {
            repeat(SCROLL_GESTURES) { humanScrollDown() }
            requireText(ALBUMS_TITLE)
        }
        logMemory(uiDevice(), "after-albums")
    }

    @Test
    fun playbackRefreshRebuildReference() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
                warmupIterations = 0,
            ),
            iterations = 1,
            setupBlock = { prepareSongs() },
        ) {
            startAnonymousPlayback()
            var snapshot = requirePlayingSession()
            snapshot = requireProgress(snapshot, POSITION_CHECK_MS)

            pressHome()
            SystemClock.sleep(longArgument("backgroundMillis", DEFAULT_BACKGROUND_MS))
            snapshot = requireProgress(snapshot, POSITION_CHECK_MS)
            check(hasActiveTargetNotification()) {
                "Physical-reference playback notification was not active in background"
            }
            logMemory(device, "during-background-playback")

            foregroundTarget()
            clickStable(textSelector(SETTINGS_TITLE), "Settings navigation")
            requireText(SETTINGS_TITLE)
            repeat(REFRESH_COUNT) {
                clickScrollableText(RESCAN_TITLE)
                waitForLibraryOperation()
                snapshot = requireProgress(snapshot, POSITION_CHECK_MS)
            }
            logMemory(device, "after-refreshes")

            clickScrollableText(REBUILD_TITLE)
            waitForLibraryOperation()
            snapshot = requireProgress(snapshot, POSITION_CHECK_MS)
            clickStable(textSelector(SONGS_TITLE), "Songs navigation")
            requireUsableSongs(device)
            check(!device.hasObject(textSelector(NO_MUSIC_TITLE))) {
                "Physical-reference catalog was empty after its isolated rebuild"
            }
            logMemory(device, "after-rebuild")
            Log.i(
                LOG_TAG,
                "playback passed refreshes=$REFRESH_COUNT rebuild=true background=true " +
                    "notification=true identity_stable=true position_progress=true",
            )

            device.executeShellCommand("am force-stop $TARGET_PACKAGE")
            startActivityAndWait()
            requireUsableSongs(device)
            SystemClock.sleep(POST_PLAYBACK_SETTLE_MS)
            logMemory(device, "post-playback-settle")
        }
    }

    private fun MacrobenchmarkScope.prepareSongs() {
        killProcess()
        pressHome()
        startActivityAndWait()
        requireUsableSongs(device)
    }

    private fun MacrobenchmarkScope.startAnonymousPlayback() {
        val candidateFractions = listOf(0.22f, 0.30f, 0.38f, 0.46f, 0.54f)
        for (fraction in candidateFractions) {
            val y = (device.displayHeight * fraction).toInt()
            check(device.click(device.displayWidth / 2, y)) {
                "Anonymous song-row coordinate injection failed"
            }
            if (device.wait(
                    Until.hasObject(descriptionSelector(MINI_PLAYER_DESCRIPTION)),
                    ROW_CLICK_TIMEOUT_MS,
                )
            ) {
                return
            }
        }
        error("No anonymous visible Songs row started playback")
    }

    private fun MacrobenchmarkScope.foregroundTarget() {
        device.executeShellCommand(
            "am start -W -n $TARGET_PACKAGE/com.libreplayer.app.MainActivity",
        )
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE)), CONTENT_TIMEOUT_MS)) {
            "Physical-reference app did not return to foreground"
        }
        device.waitForIdle()
    }

    private fun MacrobenchmarkScope.requirePlayingSession(): SessionSnapshot {
        val dump = device.executeShellCommand("dumpsys media_session")
        val packageMarker = "package=$TARGET_PACKAGE"
        val packageIndex = dump.indexOf(packageMarker)
        check(packageIndex >= 0) { "Physical-reference MediaSession was absent" }
        val nextPackageIndex = dump.indexOf("\n    package=", packageIndex + packageMarker.length)
        val end = if (nextPackageIndex >= 0) {
            nextPackageIndex
        } else {
            (packageIndex + SESSION_CONTEXT_CHARS).coerceAtMost(dump.length)
        }
        val block = dump.substring(packageIndex, end)
        check(
            block.contains("state=PLAYING(3)") ||
                Regex("state=3(?:[, }]|$)").containsMatchIn(block),
        ) { "Physical-reference MediaSession was not PLAYING" }
        check(block.contains("error=null")) { "Physical-reference MediaSession exposed an error" }
        val stateLine = block.lineSequence().firstOrNull { it.contains("state=PlaybackState") }
            ?: error("Physical-reference MediaSession state was unavailable")
        val basePosition = Regex("position=(\\d+)").find(stateLine)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: error("Physical-reference MediaSession position was unavailable")
        val updateTime = Regex("updated=(\\d+)").find(stateLine)
            ?.groupValues
            ?.get(1)
            ?.toLongOrNull()
            ?: error("Physical-reference MediaSession update time was unavailable")
        val speed = Regex("speed=([0-9.]+)").find(stateLine)
            ?.groupValues
            ?.get(1)
            ?.toDoubleOrNull()
            ?: error("Physical-reference MediaSession speed was unavailable")
        val position = basePosition +
            ((SystemClock.elapsedRealtime() - updateTime).coerceAtLeast(0L) * speed).toLong()
        val identity = Regex("active item id=([^,}\\n]+)").find(block)
            ?.groupValues
            ?.get(1)
            ?.trim()
            ?.takeIf { it.isNotEmpty() && it != "-1" }
            ?: error("Physical-reference MediaSession active item identity was unavailable")
        return SessionSnapshot(position, identity)
    }

    private fun MacrobenchmarkScope.requireProgress(
        before: SessionSnapshot,
        waitMillis: Long,
    ): SessionSnapshot {
        SystemClock.sleep(waitMillis)
        val after = requirePlayingSession()
        check(after.identity == before.identity) { "Physical-reference MediaItem changed unexpectedly" }
        check(after.positionMs - before.positionMs >= MINIMUM_POSITION_ADVANCE_MS) {
            "Physical-reference playback position did not advance"
        }
        return after
    }

    private fun MacrobenchmarkScope.hasActiveTargetNotification(): Boolean {
        val dump = device.executeShellCommand("dumpsys notification")
        return dump.contains("pkg=$TARGET_PACKAGE") || dump.contains("PackageName: $TARGET_PACKAGE")
    }

    private fun MacrobenchmarkScope.clickScrollableText(text: String) {
        repeat(SETTINGS_SCROLL_ATTEMPTS) {
            val match = device.findObject(textSelector(text))
            if (match != null) {
                var clickable = match
                while (!clickable.isClickable) {
                    clickable = requireNotNull(clickable.parent) {
                        "Settings control '$text' has no clickable ancestor"
                    }
                }
                clickable.click()
                return
            }
            settingsScrollDown()
        }
        error("Settings control '$text' was not reachable")
    }

    private fun MacrobenchmarkScope.settingsScrollDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.70f).toInt()
        val endY = (device.displayHeight * 0.28f).toInt()
        check(device.swipe(x, startY, x, endY, SWIPE_STEPS)) {
            "Normalized physical Settings scroll injection failed"
        }
    }

    private fun MacrobenchmarkScope.waitForLibraryOperation() {
        device.wait(Until.gone(textSelector(UPDATING_LIBRARY_TITLE)), LIBRARY_OPERATION_TIMEOUT_MS)
        device.waitForIdle()
        SystemClock.sleep(LIBRARY_SETTLE_MS)
    }

    private fun MacrobenchmarkScope.humanScrollDown() {
        val x = device.displayWidth / 2
        val startY = (device.displayHeight * 0.78f).toInt()
        val endY = (device.displayHeight * 0.31f).toInt()
        check(device.swipe(x, startY, x, endY, SWIPE_STEPS)) {
            "Normalized physical scroll injection failed"
        }
    }

    private fun MacrobenchmarkScope.requireText(text: String) =
        requireObject(textSelector(text), "text '$text'")

    private fun textSelector(text: String) = By.pkg(TARGET_PACKAGE).text(text)

    private fun descriptionSelector(description: String) =
        By.pkg(TARGET_PACKAGE).desc(description)

    private fun MacrobenchmarkScope.requireObject(selector: BySelector, label: String) =
        device.wait(Until.findObject(selector), CONTENT_TIMEOUT_MS)
            ?: error("Timed out waiting for $label")

    private fun MacrobenchmarkScope.clickStable(selector: BySelector, label: String) {
        repeat(5) {
            try {
                requireObject(selector, label).click()
                return
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
        error("$label remained stale across retries")
    }

    private fun authorityCompilationMode(): CompilationMode {
        val channel = InstrumentationRegistry.getArguments().getString("channel")
            ?: error("Missing required physical-reference channel argument")
        return when (channel) {
            "enabled" -> CompilationMode.Partial(
                baselineProfileMode = BaselineProfileMode.Require,
                warmupIterations = 0,
            )
            "disabled" -> CompilationMode.None()
            else -> error("Unsupported physical-reference channel: $channel")
        }
    }

    private fun intArgument(name: String, default: Int): Int =
        InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: default

    private fun longArgument(name: String, default: Long): Long =
        InstrumentationRegistry.getArguments().getString(name)?.toLongOrNull() ?: default

    private fun uiDevice() = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun requireUsableSongs(device: UiDevice) {
        check(device.wait(Until.hasObject(textSelector(SONGS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Physical-reference Songs destination was unavailable"
        }
        check(device.wait(Until.gone(textSelector(UPDATING_LIBRARY_TITLE)), LIBRARY_OPERATION_TIMEOUT_MS)) {
            "Physical-reference library did not settle"
        }
        device.waitForIdle()
        check(!device.hasObject(textSelector(NO_MUSIC_TITLE))) {
            "Physical-reference library contained no visible music"
        }
    }

    private fun logMemory(device: UiDevice, label: String) {
        val meminfo = device.executeShellCommand("dumpsys meminfo $TARGET_PACKAGE")
        val pss = Regex("TOTAL PSS:\\s*(\\d+)").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: -1L
        val rss = Regex("TOTAL RSS:\\s*(\\d+)").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: -1L
        val pid = device.executeShellCommand("pidof $TARGET_PACKAGE").trim().split(' ').firstOrNull()
        val status = pid?.takeIf { it.isNotEmpty() }
            ?.let { device.executeShellCommand("cat /proc/$it/status") }
            .orEmpty()
        val threads = Regex("Threads:\\s*(\\d+)").find(status)?.groupValues?.get(1)?.toLongOrNull()
            ?: -1L
        Log.i(LOG_TAG, "memory label=$label pss_kb=$pss rss_kb=$rss threads=$threads")
    }

    private data class SessionSnapshot(
        val positionMs: Long,
        val identity: String,
    )

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer.physicalreference"
        const val SONGS_TITLE = "Songs"
        const val ALBUMS_TITLE = "Albums"
        const val SETTINGS_TITLE = "Settings"
        const val RESCAN_TITLE = "Rescan library"
        const val REBUILD_TITLE = "Rebuild library completely"
        const val UPDATING_LIBRARY_TITLE = "Updating library"
        const val NO_MUSIC_TITLE = "No music found"
        const val MINI_PLAYER_DESCRIPTION = "Mini player"
        const val LOG_TAG = "Q11I_PHYSICAL"
        const val REFRESH_COUNT = 3
        const val SCROLL_GESTURES = 6
        const val SETTINGS_SCROLL_ATTEMPTS = 8
        const val SWIPE_STEPS = 100
        const val CONTENT_TIMEOUT_MS = 60_000L
        const val LIBRARY_OPERATION_TIMEOUT_MS = 300_000L
        const val LIBRARY_SETTLE_MS = 2_000L
        const val ROW_CLICK_TIMEOUT_MS = 2_000L
        const val DEFAULT_BACKGROUND_MS = 120_000L
        const val POSITION_CHECK_MS = 1_500L
        const val MINIMUM_POSITION_ADVANCE_MS = 750L
        const val POST_PLAYBACK_SETTLE_MS = 5_000L
        const val SESSION_CONTEXT_CHARS = 12_000
    }
}

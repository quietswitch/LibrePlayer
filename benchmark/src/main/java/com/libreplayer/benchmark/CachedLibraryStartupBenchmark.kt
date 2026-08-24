package com.libreplayer.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class CachedLibraryStartupBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @OptIn(ExperimentalMetricApi::class)
    @Test
    fun cachedSongsColdStartup() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(
            StartupTimingMetric(),
            MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
        ),
        compilationMode = CompilationMode.Full(),
        iterations = 10,
        startupMode = StartupMode.COLD,
        setupBlock = {
            pressHome()
        },
    ) {
        startActivityAndWait()
        check(device.wait(Until.hasObject(By.text(SONGS_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Songs destination did not become visible"
        }
        check(device.wait(Until.hasObject(By.text(FIXTURE_SONG_TITLE)), CONTENT_TIMEOUT_MS)) {
            "Cached MEDIUM fixture content did not become visible"
        }

        // This wait occurs after reportFullyDrawn and does not contribute to StartupTimingMetric.
        device.wait(Until.gone(By.text(UPDATING_LIBRARY_TITLE)), REFRESH_SETTLE_TIMEOUT_MS)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val SONGS_TITLE = "Songs"
        const val FIXTURE_SONG_TITLE = "Duplicate Display Metadata"
        const val UPDATING_LIBRARY_TITLE = "Updating library"
        const val CONTENT_TIMEOUT_MS = 15_000L
        const val REFRESH_SETTLE_TIMEOUT_MS = 30_000L
    }
}

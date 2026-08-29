package com.libreplayer.benchmark

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.platform.app.InstrumentationRegistry
import com.libreplayer.benchmark.BaselineProfileJourneys.openAndScrollAlbums
import com.libreplayer.benchmark.BaselineProfileJourneys.prepareSongsAtTop
import com.libreplayer.benchmark.BaselineProfileJourneys.scrollSongs
import com.libreplayer.benchmark.BaselineProfileJourneys.startFixturePlaybackAndOpenNowPlaying
import com.libreplayer.benchmark.BaselineProfileJourneys.startUsableSongs
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@LargeTest
@RunWith(AndroidJUnit4::class)
class BaselineProfileAuthorityBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun startupEffect() = benchmarkRule.measureRepeated(
        packageName = BaselineProfileJourneys.TARGET_PACKAGE,
        metrics = listOf(StartupTimingMetric()),
        compilationMode = authorityCompilationMode(),
        iterations = intArgument("iterations", 10),
        startupMode = StartupMode.COLD,
        setupBlock = { pressHome() },
    ) {
        startActivityAndWait()
        BaselineProfileJourneys.run {
            requireText(BaselineProfileJourneys.SONGS_TITLE)
            requireText(BaselineProfileJourneys.FIRST_SONG_TITLE)
        }
    }

    @Test
    fun songsEffect() = benchmarkRule.measureRepeated(
        packageName = BaselineProfileJourneys.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = authorityCompilationMode(),
        iterations = intArgument("iterations", 5),
        setupBlock = { prepareSongsAtTop() },
    ) {
        scrollSongs()
    }

    @Test
    fun albumsEffect() = benchmarkRule.measureRepeated(
        packageName = BaselineProfileJourneys.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = authorityCompilationMode(),
        iterations = intArgument("iterations", 5),
        setupBlock = {
            prepareSongsAtTop()
            BaselineProfileJourneys.run {
                requireObject(androidx.test.uiautomator.By.text(ALBUMS_TITLE), "Albums navigation item").click()
                requireText(ALBUMS_TITLE)
                requireText(FIRST_ALBUM_TITLE)
                device.waitForIdle()
            }
        },
    ) {
        repeat(BaselineProfileJourneys.SCROLL_GESTURES) { BaselineProfileJourneys.run { humanScrollDown() } }
        check(!device.hasObject(androidx.test.uiautomator.By.text(BaselineProfileJourneys.FIRST_ALBUM_TITLE)))
    }

    @Test
    fun playbackNowPlayingCorrectness() = benchmarkRule.measureRepeated(
        packageName = BaselineProfileJourneys.TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = authorityCompilationMode(),
        iterations = 1,
        setupBlock = { prepareSongsAtTop() },
    ) {
        startFixturePlaybackAndOpenNowPlaying()
    }

    private fun authorityCompilationMode(): CompilationMode {
        val channel = InstrumentationRegistry.getArguments().getString("channel")
            ?: error("Missing required channel argument")
        return when (channel) {
            "enabled" -> CompilationMode.Partial(BaselineProfileMode.Require, warmupIterations = 0)
            "disabled" -> CompilationMode.None()
            else -> error("Unsupported profile authority channel: $channel")
        }
    }

    private fun intArgument(name: String, default: Int): Int =
        InstrumentationRegistry.getArguments().getString(name)?.toIntOrNull() ?: default
}

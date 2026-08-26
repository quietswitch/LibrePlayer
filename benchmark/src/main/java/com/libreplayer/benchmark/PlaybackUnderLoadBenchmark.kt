package com.libreplayer.benchmark

import android.net.Uri
import android.os.BaseBundle
import android.os.Bundle
import android.util.Base64
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.ExperimentalMetricApi
import androidx.benchmark.macro.MemoryUsageMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@OptIn(ExperimentalMetricApi::class)
class PlaybackUnderLoadBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun playbackUnderLoadAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val journey = requireArgument(arguments, "journey")
        val method = when (journey) {
            "unchanged", "add" -> "sync"
            "rebuild" -> "rebuild"
            else -> error("Unsupported playback-under-load journey: $journey")
        }
        val expectedCount = requireArgument(arguments, "expectedCount").toInt()
        val expectedFingerprint = requireArgument(arguments, "expectedFingerprint")
        val inspectedIdentity = arguments.getString("inspectedIdentity")
        val expectPresent = arguments.getString("expectPresent")?.toBooleanStrictOrNull()
        val expectedTitle = arguments.getString("expectedTitleBase64")?.let { encoded ->
            String(Base64.decode(encoded, Base64.NO_WRAP), Charsets.UTF_8)
        }
        val iterations = arguments.getString("iterations")?.toIntOrNull() ?: 1
        require(iterations in 1..20) { "iterations must be in 1..20" }
        val resolver = instrumentation.context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.playback-load-probe")

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(MemoryUsageMetric(MemoryUsageMetric.Mode.Max)),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = {
                pressHome()
                val prepared = requireNotNull(resolver.call(probeUri, "prepare", null, null))
                check(prepared.getBoolean("prepared"))
                check(prepared.getString("playbackFixtureIdentity") == PLAYBACK_FIXTURE_IDENTITY)
                check(prepared.getBoolean("beforeConnected"))
                check(prepared.getInt("beforePlaybackState") == PLAYBACK_STATE_READY)
                check(prepared.getBoolean("beforePlayWhenReady"))
                check(prepared.getBoolean("beforeIsPlaying"))
                check(prepared.getInt("beforeSuppressionReason") == PLAYBACK_SUPPRESSION_REASON_NONE)
                check(!prepared.getBoolean("beforeHasPlayerError"))
            },
        ) {
            val result = requireNotNull(resolver.call(probeUri, method, inspectedIdentity, null))
            assertPlaybackContinuity(result)
            assertCatalog(result, expectedCount, expectedFingerprint, expectPresent, expectedTitle)
        }
    }

    private fun assertPlaybackContinuity(result: Bundle) {
        check(result.getString("playbackFixtureIdentity") == PLAYBACK_FIXTURE_IDENTITY)
        check(result.getString("beforeMediaId")?.isNotBlank() == true)
        check(result.getString("beforeMediaId") == result.getString("afterMediaId"))
        for (prefix in listOf("before", "after")) {
            check(result.getBoolean("${prefix}Connected"))
            check(result.getInt("${prefix}PlaybackState") == PLAYBACK_STATE_READY)
            check(result.getBoolean("${prefix}PlayWhenReady"))
            check(result.getBoolean("${prefix}IsPlaying"))
            check(result.getInt("${prefix}SuppressionReason") == PLAYBACK_SUPPRESSION_REASON_NONE)
            check(!result.getBoolean("${prefix}HasPlayerError"))
            check(result.getFloat("${prefix}Speed") > 0f)
        }
        check(result.getLong("observationElapsedNanos") >= MINIMUM_OBSERVATION_NANOS)
        check(result.getLong("positionAdvancementMs") >= MINIMUM_POSITION_ADVANCEMENT_MS)
        check(result.getLong("synchronizationElapsedNanos") > 0L)
        checkZero(result, "playerErrors")
        checkZero(result, "mediaTransitions")
        checkZero(result, "positionDiscontinuities")
        checkZero(result, "sessionDisconnects")
    }

    private fun assertCatalog(
        result: Bundle,
        expectedCount: Int,
        expectedFingerprint: String,
        expectPresent: Boolean?,
        expectedTitle: String?,
    ) {
        check(result.getInt("fixtureCount") == expectedCount)
        check(result.getInt("uniqueIdentities") == expectedCount)
        check(result.getInt("duplicateIdentities") == 0)
        check(result.getString("identitySha256") == expectedFingerprint)
        if (expectPresent != null) check(result.getBoolean("inspectedPresent") == expectPresent)
        if (expectedTitle != null) check(result.getString("inspectedTitle") == expectedTitle)
    }

    private fun checkZero(result: BaseBundle, key: String) {
        check(result.getInt(key) == 0) { "$key=${result.getInt(key)}" }
    }

    private fun requireArgument(arguments: Bundle, name: String): String =
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument: $name" }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val PLAYBACK_FIXTURE_IDENTITY =
            "audio/artist-00010/album-00010/disc-01/track-00010.mp3"
        const val MINIMUM_OBSERVATION_NANOS = 1_800_000_000L
        const val MINIMUM_POSITION_ADVANCEMENT_MS = 1_000L
        const val PLAYBACK_STATE_READY = 3
        const val PLAYBACK_SUPPRESSION_REASON_NONE = 0
    }
}

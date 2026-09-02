package com.libreplayer.benchmark

import android.net.Uri
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
class SynchronizationBenchmark {
    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun synchronizationAuthority() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val arguments = InstrumentationRegistry.getArguments()
        val journey = requireArgument(arguments, "journey")
        val method = when (journey) {
            "unchanged", "add", "delete", "modify" -> "sync"
            "rebuild" -> "rebuild"
            else -> error("Unsupported synchronization journey: $journey")
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
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")

        benchmarkRule.measureRepeated(
            packageName = PACKAGE_NAME,
            metrics = listOf(
                MemoryUsageMetric(MemoryUsageMetric.Mode.Max),
            ),
            compilationMode = CompilationMode.Full(),
            iterations = iterations,
            setupBlock = {
                pressHome()
                requireNotNull(resolver.call(probeUri, "catalog", null, null))
            },
        ) {
            val result = requireNotNull(resolver.call(probeUri, method, inspectedIdentity, null))
            check(result.getInt("fixtureCount") == expectedCount) {
                "Expected $expectedCount fixture songs, got ${result.getInt("fixtureCount")}" 
            }
            check(result.getInt("uniqueIdentities") == expectedCount)
            check(result.getInt("duplicateIdentities") == 0)
            check(result.getString("identitySha256") == expectedFingerprint) {
                "Catalog identity fingerprint mismatch: ${result.getString("identitySha256")}" 
            }
            if (expectPresent != null) {
                check(result.getBoolean("inspectedPresent") == expectPresent) {
                    "Inspected identity presence did not match $expectPresent"
                }
            }
            if (expectedTitle != null) {
                check(result.getString("inspectedTitle") == expectedTitle) {
                    "Expected inspected title '$expectedTitle', got '${result.getString("inspectedTitle")}'"
                }
            }
        }
    }

    @Test
    fun libraryIdentityIngestionAuthority() {
        val resolver = InstrumentationRegistry.getInstrumentation().context.contentResolver
        val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")
        val result = requireNotNull(resolver.call(probeUri, "q3.1-sync", null, null))

        val ids = requireNotNull(result.getStringArray("q31Ids")).toList()
        val contentUris = requireNotNull(result.getStringArray("q31ContentUris")).toList()
        val displayNames = requireNotNull(result.getStringArray("q31DisplayNames")).toList()
        val titles = requireNotNull(result.getStringArray("q31Titles")).toList()
        val artists = requireNotNull(result.getStringArray("q31Artists")).toList()
        val albums = requireNotNull(result.getStringArray("q31Albums")).toList()
        val tracks = requireNotNull(result.getIntArray("q31Tracks")).toList()
        val discs = requireNotNull(result.getIntArray("q31Discs")).toList()
        val years = requireNotNull(result.getIntArray("q31Years")).toList()
        val paths = requireNotNull(result.getStringArray("q31RelativePaths")).toList()

        check(result.getInt("q31Count") == 3) { "Expected three Q3.1 occurrences, got ${result.getInt("q31Count")}" }
        check(ids.size == 3 && ids.distinct().size == 3 && ids.all { it.startsWith("media:") })
        check(contentUris.size == 3 && contentUris.distinct().size == 3)
        check(displayNames == listOf("Twin.mp3", "Twin.mp3", "DiscTrack.mp3"))
        check(titles == listOf("Twin", "Twin", "Disc Track"))
        check(artists == listOf("Identity Artist", "Identity Artist", "Track Artist"))
        check(albums == listOf("Identity Album", "Identity Album", "Multi Album"))
        check(tracks == listOf(1, 1, 1))
        check(discs == listOf(1, 1, 2))
        check(years == listOf(2024, 2024, 2023))
        check(paths == listOf(
            "Music/LibrePlayerQ31/CopyA/",
            "Music/LibrePlayerQ31/CopyB/",
            "Music/LibrePlayerQ31/Multi/",
        ))
    }

    private fun requireArgument(arguments: Bundle, name: String): String =
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument: $name" }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
    }
}

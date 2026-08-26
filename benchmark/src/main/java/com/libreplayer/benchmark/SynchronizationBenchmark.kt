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

    private fun requireArgument(arguments: Bundle, name: String): String =
        requireNotNull(arguments.getString(name)) { "Missing instrumentation argument: $name" }

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
    }
}

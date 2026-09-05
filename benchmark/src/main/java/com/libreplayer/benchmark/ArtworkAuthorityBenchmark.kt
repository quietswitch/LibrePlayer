package com.libreplayer.benchmark

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded API-36 authority for deterministic local artwork semantics and resource behavior. */
@RunWith(AndroidJUnit4::class)
class ArtworkAuthorityBenchmark {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)
    private val probeUri = Uri.parse("content://com.libreplayer.synchronization-probe")

    @Test
    fun localArtworkAuthority() {
        requireEmulatorAuthority()
        val results = linkedMapOf<String, String>()
        try {
            check(device.executeShellCommand("pm clear $PACKAGE_NAME").contains("Success"))
            device.executeShellCommand("pm grant $PACKAGE_NAME android.permission.READ_MEDIA_AUDIO")
            call(METHOD_CLEAR_CACHE)

            val initial = call(METHOD_SYNC)
            assertCatalog(initial, expectedMediaStoreCount = 14, expectedDocumentCount = 0)
            val unchanged = call(METHOD_SYNC)
            assertCatalog(unchanged, expectedMediaStoreCount = 14, expectedDocumentCount = 0)
            check(initial.string(KEY_FINGERPRINT) == unchanged.string(KEY_FINGERPRINT))
            check(initial.strings(KEY_IDS) == unchanged.strings(KEY_IDS))
            check(initial.strings(KEY_ARTWORK_URIS) == unchanged.strings(KEY_ARTWORK_URIS))
            results["unchangedFingerprint"] = initial.string(KEY_FINGERPRINT)

            assertDominant(loadSong("01-normal-jpeg.mp3"), dominant = Dominant.RED)
            assertLoadFailed(loadSong("02-missing.mp3"))
            assertLoadFailed(loadSong("11-corrupt-art.mp3"))
            assertSafePathologicalLoad(loadSong("12-truncated-art.mp3"))

            val partial = loadAlbum("03-partial-missing.mp3")
            assertDominant(partial, dominant = Dominant.GREEN)
            check(partial.strings(KEY_LOAD_CANDIDATES).size >= 2)
            results["partialWinner"] = partial.string(KEY_LOAD_WINNER)

            val greatestA = loadAlbum("07-greatest-a.mp3")
            val greatestB = loadAlbum("08-greatest-b.mp3")
            assertDominant(greatestA, dominant = Dominant.RED)
            assertDominant(greatestB, dominant = Dominant.BLUE)
            check(initial.string(KEY_GREATEST_A_ID) != initial.string(KEY_GREATEST_B_ID))
            check(greatestA.string(KEY_LOAD_CACHE_KEY) != greatestB.string(KEY_LOAD_CACHE_KEY))

            val large = loadSong("13-large-webp.mp3", variant = "FULL")
            check(large.string(KEY_LOAD_STATUS) == "loaded")
            check(large.getInt(KEY_LOAD_WIDTH) <= 1_536 && large.getInt(KEY_LOAD_HEIGHT) <= 1_536)
            check(large.getInt(KEY_LOAD_ALLOCATION_BYTES) <= 10 * 1024 * 1024)
            check(large.getInt(KEY_CACHE_BYTES) <= large.getInt(KEY_CACHE_MAX_BYTES))
            results["largeDecoded"] = "${large.getInt(KEY_LOAD_WIDTH)}x${large.getInt(KEY_LOAD_HEIGHT)}"
            results["largeAllocationBytes"] = large.getInt(KEY_LOAD_ALLOCATION_BYTES).toString()
            results["cache"] = "${large.getInt(KEY_CACHE_BYTES)}/${large.getInt(KEY_CACHE_MAX_BYTES)}"

            val beforeChangeCatalog = call(METHOD_CATALOG)
            val beforeChangeId = beforeChangeCatalog.mediaStoreValue("14-art-change.mp3", KEY_IDS)
            val beforeChangeUri = beforeChangeCatalog.mediaStoreValue("14-art-change.mp3", KEY_URIS)
            val beforeChange = loadSong("14-art-change.mp3")
            assertDominant(beforeChange, dominant = Dominant.RED)
            replaceArtworkFixture()
            val afterChangeCatalog = call(METHOD_SYNC)
            val afterChange = loadSong("14-art-change.mp3")
            assertDominant(afterChange, dominant = Dominant.BLUE)
            check(beforeChangeId == afterChangeCatalog.mediaStoreValue("14-art-change.mp3", KEY_IDS))
            check(beforeChangeUri == afterChangeCatalog.mediaStoreValue("14-art-change.mp3", KEY_URIS))
            check(beforeChange.string(KEY_LOAD_CACHE_KEY) != afterChange.string(KEY_LOAD_CACHE_KEY))
            results["artChangeIdentity"] = beforeChangeId

            val beforeDeletion = loadAlbum("05-conflict-red.mp3")
            deleteFixture("05-conflict-red.mp3")
            val afterDeletionCatalog = call(METHOD_SYNC)
            assertCatalog(afterDeletionCatalog, expectedMediaStoreCount = 13, expectedDocumentCount = 0)
            val afterDeletion = loadAlbum("06-conflict-green.mp3")
            assertDominant(afterDeletion, dominant = Dominant.GREEN)
            check(beforeDeletion.string(KEY_LOAD_ID) != afterDeletion.string(KEY_LOAD_ID))
            check(beforeDeletion.string(KEY_LOAD_CACHE_KEY) != afterDeletion.string(KEY_LOAD_CACHE_KEY))
            results["representativeAfterDeletion"] = afterDeletion.string(KEY_LOAD_WINNER)

            val withSaf = call(METHOD_ADD_SAF)
            assertCatalog(withSaf, expectedMediaStoreCount = 13, expectedDocumentCount = 1)
            val saf = loadSong("01-normal-jpeg.mp3", source = "document")
            assertDominant(saf, dominant = Dominant.RED)
            check(saf.string(KEY_LOAD_ID).startsWith("document:"))
            results["safIdentity"] = saf.string(KEY_LOAD_ID)

            listOf("01-normal-jpeg.mp3", "11-corrupt-art.mp3", "02-missing.mp3").forEach { file ->
                val playback = call(METHOD_PLAY, file)
                check(playback.string(KEY_SELECTED_ID) == playback.string(KEY_SELECTED_MEDIA_ID))
                check(playback.string(KEY_SELECTED_URI) == playback.string(KEY_SELECTED_MEDIA_URI))
                check(playback.string(KEY_SELECTED_ID) == playback.string(KEY_PLAY_CURRENT_ID))
                check(playback.string(KEY_SELECTED_URI) == playback.string(KEY_PLAY_CURRENT_URI))
                check(playback.getBoolean(KEY_PLAY_IS_PLAYING)) { playback.getString(KEY_PLAY_ERROR).orEmpty() }
                check(playback.getString(KEY_PLAY_ERROR) == null)
            }

            val memoryBefore = memory("before-ui")
            exerciseArtworkSurfaces()
            val memoryAfter = memory("after-ui-settle")
            check(memoryAfter.threads <= memoryBefore.threads + 16) {
                "Artwork journey retained too many threads: before=$memoryBefore after=$memoryAfter"
            }
            check(memoryAfter.pssKb <= memoryBefore.pssKb + 96 * 1024) {
                "Artwork journey retained an excessive settled PSS delta: before=$memoryBefore after=$memoryAfter"
            }
            results["memory"] = "before=$memoryBefore after=$memoryAfter"

            val logs = device.executeShellCommand("logcat -d -v brief")
            check("FATAL EXCEPTION" !in logs || PACKAGE_NAME !in logs)
            check("OutOfMemoryError" !in logs)
            check("was already used" !in logs)
        } finally {
            runCatching { call(METHOD_REMOVE_SAF) }
            cleanupFixtures()
            Log.i(LOG_TAG, results.entries.joinToString(" ") { "${it.key}=${it.value}" })
        }
    }

    private fun assertCatalog(result: Bundle, expectedMediaStoreCount: Int, expectedDocumentCount: Int) {
        check(result.getInt(KEY_MEDIASTORE_COUNT) == expectedMediaStoreCount)
        check(result.getInt(KEY_DOCUMENT_COUNT) == expectedDocumentCount)
        val ids = result.strings(KEY_IDS)
        val uris = result.strings(KEY_URIS)
        val files = result.strings(KEY_FILES)
        check(ids.size == expectedMediaStoreCount + expectedDocumentCount)
        check(ids.distinct().size == ids.size)
        check(uris.distinct().size == uris.size)
        check(files.count { it == "09-twin-red.mp3" || it == "10-twin-green.mp3" } == 2)
        check(result.string(KEY_SELECTED_ID) == result.string(KEY_SELECTED_MEDIA_ID))
        check(result.string(KEY_SELECTED_URI) == result.string(KEY_SELECTED_MEDIA_URI))
        check(result.getInt(KEY_CACHE_BYTES) <= result.getInt(KEY_CACHE_MAX_BYTES))
    }

    private fun loadSong(file: String, variant: String = "LIST", source: String = "media"): Bundle =
        call(
            METHOD_LOAD,
            file,
            Bundle().apply {
                putString(KEY_LOAD_KIND, "song")
                putString(KEY_LOAD_SOURCE, source)
                putString(KEY_LOAD_VARIANT, variant)
            },
        )

    private fun loadAlbum(file: String): Bundle =
        call(
            METHOD_LOAD,
            file,
            Bundle().apply {
                putString(KEY_LOAD_KIND, "album")
                putString(KEY_LOAD_SOURCE, "media")
                putString(KEY_LOAD_VARIANT, "LIST")
            },
        )

    private fun assertLoadFailed(result: Bundle) {
        check(result.string(KEY_LOAD_STATUS) == "failed") { "Unexpected load result: $result" }
    }

    private fun assertSafePathologicalLoad(result: Bundle) {
        when (result.string(KEY_LOAD_STATUS)) {
            "failed" -> Unit
            "loaded" -> {
                check(result.getInt(KEY_LOAD_WIDTH) in 1..320)
                check(result.getInt(KEY_LOAD_HEIGHT) in 1..320)
                check(result.getInt(KEY_CACHE_BYTES) <= result.getInt(KEY_CACHE_MAX_BYTES))
            }
            else -> error("Unexpected pathological-art result: $result")
        }
    }

    private fun assertDominant(result: Bundle, dominant: Dominant) {
        check(result.string(KEY_LOAD_STATUS) == "loaded") { "Artwork did not load: $result" }
        val red = result.getInt(KEY_LOAD_RED)
        val green = result.getInt(KEY_LOAD_GREEN)
        val blue = result.getInt(KEY_LOAD_BLUE)
        when (dominant) {
            Dominant.RED -> check(red > green + 80 && red > blue + 80)
            Dominant.GREEN -> check(green > red + 80 && green > blue + 80)
            Dominant.BLUE -> check(blue > red + 80 && blue > green + 80)
        }
    }

    private fun replaceArtworkFixture() {
        val source = "$REMOTE_ROOT/updates/14-art-change-blue.mp3"
        val target = "$REMOTE_ROOT/14-art-change.mp3"
        check(device.executeShellCommand("cp $source $target").isBlank())
        device.executeShellCommand("touch $target")
        scan(target)
        SystemClock.sleep(MEDIASTORE_SETTLE_MS)
    }

    private fun deleteFixture(name: String) {
        val remote = "$REMOTE_ROOT/$name"
        val mediaStore = "$MEDIASTORE_ROOT/$name"
        device.executeShellCommand("rm $remote")
        device.executeShellCommand(
            "content delete --uri content://media/external/audio/media --where \"_data='$mediaStore'\"",
        )
        scan(remote)
        SystemClock.sleep(MEDIASTORE_SETTLE_MS)
    }

    private fun scan(path: String) {
        device.executeShellCommand(
            "am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file://$path",
        )
    }

    private fun exerciseArtworkSurfaces() {
        device.executeShellCommand("logcat -c")
        device.executeShellCommand("am force-stop $PACKAGE_NAME")
        val launch = requireNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(PACKAGE_NAME),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        instrumentation.targetContext.startActivity(launch)
        check(device.wait(Until.hasObject(By.text("Songs")), UI_TIMEOUT_MS))
        device.wait(Until.gone(By.text("Updating library")), UI_TIMEOUT_MS)

        clickStable(By.desc("Search library"), "Search library action")
        check(device.wait(Until.hasObject(By.text("Search your library")), UI_TIMEOUT_MS))
        requireNotNull(
            device.wait(Until.findObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS),
        ).text = "Q35 Change"
        check(device.wait(Until.hasObject(By.text("Q35 Change")), UI_TIMEOUT_MS))
        clickStable(By.text("Q35 Change"), "Q35 Change search album")
        check(device.wait(Until.hasObject(By.text("Art Change")), UI_TIMEOUT_MS))

        clickStable(By.text("Art Change"), "Art Change album track")
        check(device.wait(Until.hasObject(By.desc("Mini player")), UI_TIMEOUT_MS))
        clickStable(By.desc("Mini player"), "Mini player")
        check(device.wait(Until.hasObject(By.text("Now Playing")), UI_TIMEOUT_MS))
        clickStable(By.desc("Open queue"), "Open queue action")
        check(device.wait(Until.hasObject(By.text("Queue")), UI_TIMEOUT_MS))
        device.pressBack()
        check(device.wait(Until.hasObject(By.text("Now Playing")), UI_TIMEOUT_MS))
        device.pressBack()
        check(device.wait(Until.hasObject(By.text("Art Change")), UI_TIMEOUT_MS))

        device.pressBack()
        check(device.wait(Until.hasObject(By.clazz("android.widget.EditText")), UI_TIMEOUT_MS))
        requireNotNull(device.findObject(By.clazz("android.widget.EditText"))).text = "Q35 Artist"
        check(device.wait(Until.hasObject(By.text("Q35 Artist")), UI_TIMEOUT_MS))
        clickStable(By.text("Q35 Artist"), "Q35 Artist search result")
        check(device.wait(Until.hasObject(By.text("Q35 Change")), UI_TIMEOUT_MS))
        repeat(3) {
            device.swipe(device.displayWidth / 2, device.displayHeight * 3 / 4, device.displayWidth / 2, device.displayHeight / 4, 12)
            device.swipe(device.displayWidth / 2, device.displayHeight / 4, device.displayWidth / 2, device.displayHeight * 3 / 4, 12)
        }
        SystemClock.sleep(SETTLE_MS)
    }

    private fun clickStable(selector: BySelector, label: String) {
        repeat(UI_OBJECT_RETRIES) {
            try {
                requireNotNull(device.wait(Until.findObject(selector), UI_TIMEOUT_MS)) {
                    "Timed out waiting for $label"
                }.click()
                return
            } catch (_: StaleObjectException) {
                device.waitForIdle()
            }
        }
        error("$label remained stale across retries")
    }

    private fun memory(label: String): MemorySnapshot {
        val meminfo = device.executeShellCommand("dumpsys meminfo $PACKAGE_NAME")
        val pss = Regex("TOTAL PSS:\\s*(\\d+)").find(meminfo)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("No TOTAL PSS for $label")
        val pid = device.executeShellCommand("pidof $PACKAGE_NAME").trim().substringBefore(' ')
        val status = device.executeShellCommand("cat /proc/$pid/status")
        val threads = Regex("Threads:\\s*(\\d+)").find(status)?.groupValues?.get(1)?.toLongOrNull()
            ?: error("No thread count for $label")
        return MemorySnapshot(pss, threads)
    }

    private fun cleanupFixtures() {
        device.executeShellCommand(
            "content delete --uri content://media/external/audio/media --where \"_data LIKE '$MEDIASTORE_ROOT/%'\"",
        )
        device.executeShellCommand("rm -rf $REMOTE_ROOT")
        runCatching { call(METHOD_SYNC) }
    }

    private fun Bundle.mediaStoreValue(file: String, key: String): String {
        val files = strings(KEY_FILES)
        val ids = strings(KEY_IDS)
        val index = files.indices.single { candidate ->
            files[candidate] == file && ids[candidate].startsWith("media:")
        }
        return strings(key)[index]
    }

    private fun call(method: String, arg: String? = null, extras: Bundle? = null): Bundle =
        requireNotNull(resolver.call(probeUri, method, arg, extras))

    private fun Bundle.strings(key: String): List<String> =
        requireNotNull(getStringArray(key)) { "Missing String array: $key" }.toList()

    private fun Bundle.string(key: String): String = requireNotNull(getString(key)) { "Missing String: $key" }

    private fun requireEmulatorAuthority() {
        check(device.executeShellCommand("getprop ro.kernel.qemu").trim() == "1")
        check(device.executeShellCommand("getprop ro.build.version.sdk").trim() == "36")
        check(device.executeShellCommand("getprop ro.boot.qemu.avd_name").trim() == EXPECTED_AVD)
    }

    private enum class Dominant { RED, GREEN, BLUE }
    private data class MemorySnapshot(val pssKb: Long, val threads: Long)

    private companion object {
        const val PACKAGE_NAME = "com.libreplayer"
        const val EXPECTED_AVD = "LibrePlayer_Benchmark_API_36"
        const val METHOD_SYNC = "q3.5-sync"
        const val METHOD_CATALOG = "q3.5-catalog"
        const val METHOD_LOAD = "q3.5-load"
        const val METHOD_PLAY = "q3.5-play"
        const val METHOD_ADD_SAF = "q3.5-add-saf"
        const val METHOD_REMOVE_SAF = "q3.5-remove-saf"
        const val METHOD_CLEAR_CACHE = "q3.5-clear-cache"
        const val KEY_MEDIASTORE_COUNT = "q35MediaStoreCount"
        const val KEY_DOCUMENT_COUNT = "q35DocumentCount"
        const val KEY_FILES = "q35Files"
        const val KEY_IDS = "q35Ids"
        const val KEY_URIS = "q35Uris"
        const val KEY_ARTWORK_URIS = "q35ArtworkUris"
        const val KEY_SELECTED_ID = "q35SelectedId"
        const val KEY_SELECTED_URI = "q35SelectedUri"
        const val KEY_SELECTED_MEDIA_ID = "q35SelectedMediaId"
        const val KEY_SELECTED_MEDIA_URI = "q35SelectedMediaUri"
        const val KEY_FINGERPRINT = "q35Fingerprint"
        const val KEY_GREATEST_A_ID = "q35GreatestAId"
        const val KEY_GREATEST_B_ID = "q35GreatestBId"
        const val KEY_LOAD_KIND = "q35LoadKind"
        const val KEY_LOAD_SOURCE = "q35LoadSource"
        const val KEY_LOAD_VARIANT = "q35LoadVariant"
        const val KEY_LOAD_ID = "q35LoadId"
        const val KEY_LOAD_STATUS = "q35LoadStatus"
        const val KEY_LOAD_WINNER = "q35LoadWinner"
        const val KEY_LOAD_CACHE_KEY = "q35LoadCacheKey"
        const val KEY_LOAD_CANDIDATES = "q35LoadCandidates"
        const val KEY_LOAD_WIDTH = "q35LoadWidth"
        const val KEY_LOAD_HEIGHT = "q35LoadHeight"
        const val KEY_LOAD_ALLOCATION_BYTES = "q35LoadAllocationBytes"
        const val KEY_LOAD_RED = "q35LoadRed"
        const val KEY_LOAD_GREEN = "q35LoadGreen"
        const val KEY_LOAD_BLUE = "q35LoadBlue"
        const val KEY_CACHE_BYTES = "q35CacheBytes"
        const val KEY_CACHE_MAX_BYTES = "q35CacheMaxBytes"
        const val KEY_PLAY_CURRENT_ID = "q35PlayCurrentId"
        const val KEY_PLAY_CURRENT_URI = "q35PlayCurrentUri"
        const val KEY_PLAY_IS_PLAYING = "q35PlayIsPlaying"
        const val KEY_PLAY_ERROR = "q35PlayError"
        const val REMOTE_ROOT = "/sdcard/Music/LibrePlayerBenchmark/Q35_ARTWORK_AUTHORITY"
        const val MEDIASTORE_ROOT = "/storage/emulated/0/Music/LibrePlayerBenchmark/Q35_ARTWORK_AUTHORITY"
        const val MEDIASTORE_SETTLE_MS = 1_500L
        const val UI_TIMEOUT_MS = 20_000L
        const val UI_OBJECT_RETRIES = 5
        const val SETTLE_MS = 2_000L
        const val LOG_TAG = "LibrePlayerQ35"
    }
}

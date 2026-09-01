package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.Locale
import kotlin.math.abs
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LongSessionPlaybackIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val arguments = InstrumentationRegistry.getArguments()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun wallClockSoakAuthority() {
        val durationMs = arguments.getString("wallSeconds")?.toLong()?.times(1_000L)
            ?: (arguments.getString("wallMinutes")?.toLong() ?: 45L) * 60_000L
        require(durationMs in MIN_WALL_DURATION_MS..MAX_WALL_DURATION_MS)
        val checkpoints = mutableListOf<JSONObject>()
        var targetPid = -1
        try {
            bringTargetForeground()
            val initialLibraryCount = libraryCount()
            val fixtures = call("fixtures").getStringArrayList("fixtureManifest").orEmpty()
            check(fixtures == EXPECTED_FIXTURES)
            var snapshot = prepare("q2.8-wall")
            requireHealthy(snapshot)
            targetPid = targetPid()
            check(targetPid > 0)
            val startedAt = SystemClock.elapsedRealtime()
            val baselinePersistence = persistenceFile()
            checkpoints += checkpoint("T+0", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 5)
            snapshot = snapshot()
            checkpoints += checkpoint("T+5", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 10)
            device.pressHome()
            device.executeShellCommand("input keyevent 223")
            awaitWallTime(startedAt, durationMs, 12)
            device.executeShellCommand("input keyevent 224")
            device.executeShellCommand("wm dismiss-keyguard")
            val afterWakeBaseline = snapshot()
            val afterScreenOff = await("screen-off playback continuation") {
                it.isPlaying && (
                    it.mediaId != afterWakeBaseline.mediaId ||
                        (
                            it.mediaId == afterWakeBaseline.mediaId &&
                                it.positionMs - afterWakeBaseline.positionMs >= MINIMUM_ADVANCEMENT_MS
                            )
                    )
            }
            checkpoints += checkpoint("T+12-screen-off", startedAt, targetPid, afterScreenOff)

            awaitWallTime(startedAt, durationMs, 15)
            check(call("release-controller").getBoolean("passed"))
            snapshot = call("reconnect").asSnapshot()
            requireHealthy(snapshot)
            check(snapshot.deliberateControllerReleases == 1)
            check(snapshot.controllerConnections == 1)
            checkpoints += checkpoint("T+15-reconnect", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 20)
            bringTargetForeground()
            rotateTargetOnce()
            snapshot = await("activity recreation") { it.isPlaying && !it.hasPlayerError }
            checkpoints += checkpoint("T+20-recreate", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 25)
            snapshot = call("seek", "7000").asSnapshot()
            snapshot = await("wall-clock seek") {
                it.isPlaying && it.positionMs in 6_500L..9_500L
            }
            checkpoints += checkpoint("T+25-seek", startedAt, targetPid, snapshot)

            for (minute in 30..32) {
                awaitWallTime(startedAt, durationMs, minute)
                val refreshed = call("refresh")
                check(refreshed.getLong("refreshElapsedNanos") > 0L)
                requireHealthy(refreshed.asSnapshot())
                check(libraryCount() == initialLibraryCount)
            }

            awaitWallTime(startedAt, durationMs, 35)
            snapshot = snapshot()
            checkpoints += checkpoint("T+35-refreshes", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 40)
            val paused = call("system-pause").asSnapshot()
            check(!paused.playWhenReady && !paused.isPlaying)
            val pausedPosition = paused.positionMs
            SystemClock.sleep(PAUSE_STABILITY_MS)
            val stablePause = snapshot()
            check(!stablePause.playWhenReady && !stablePause.isPlaying)
            check(abs(stablePause.positionMs - pausedPosition) <= PAUSED_POSITION_TOLERANCE_MS)
            snapshot = call("system-play").asSnapshot()
            snapshot = await("wall-clock resume") { it.isPlaying && !it.hasPlayerError }
            checkpoints += checkpoint("T+40-pause-resume", startedAt, targetPid, snapshot)

            awaitWallTime(startedAt, durationMs, 45)
            snapshot = snapshot()
            requireHealthy(snapshot)
            check(snapshot.errorCount == 0)
            check(snapshot.sessionDisconnects == 0)
            check(snapshot.automaticDiscontinuityCount == snapshot.automaticTransitionCount)
            check(targetPid() == targetPid)
            check(mediaSessionCount() == 1)
            check(libraryCount() == initialLibraryCount)
            val persisted = persisted()
            check(persisted.getStringArrayList("queueIds") == snapshot.mediaIds)
            check(persisted.getInt("currentIndex") == snapshot.currentIndex)
            check(persisted.getBoolean("playWhenReady"))
            val finalPersistence = persistenceFile()
            check(finalPersistence.lastModifiedMs > baselinePersistence.lastModifiedMs)
            checkpoints += checkpoint("T+45-final", startedAt, targetPid, snapshot)

            val result = JSONObject()
                .put("axis", "wall-clock")
                .put("requestedDurationMs", durationMs)
                .put("actualDurationMs", SystemClock.elapsedRealtime() - startedAt)
                .put("pid", targetPid)
                .put("fixtureManifest", JSONArray(fixtures))
                .put("checkpoints", JSONArray(checkpoints))
                .put("final", snapshot.toJson())
                .put("initialLibraryCount", initialLibraryCount)
                .put("finalLibraryCount", libraryCount())
                .put("persistenceStart", baselinePersistence.toJson())
                .put("persistenceFinal", finalPersistence.toJson())
                .put("screenOff", "established")
                .put("result", "PASS")
            emit("WALL_FINAL", result)
        } finally {
            runCatching { device.executeShellCommand("input keyevent 224") }
            runCatching { device.executeShellCommand("wm dismiss-keyguard") }
            runCatching { restoreRotation() }
            runCatching { call("reset") }
        }
    }

    @Test
    fun transitionControlAndLateRecoveryAuthority() {
        val smoke = arguments.getString("smoke")?.toBooleanStrictOrNull() == true
        val targetTransitions = arguments.getString("transitionCount")?.toInt() ?: 200
        if (smoke) {
            require(targetTransitions in 5..25)
        } else {
            require(targetTransitions in 200..500)
        }
        val checkpoints = mutableListOf<JSONObject>()
        try {
            bringTargetForeground()
            val initialLibraryCount = libraryCount()
            val transitionStartedAt = SystemClock.elapsedRealtime()
            var snapshot = prepare("q2.8-transition")
            requireHealthy(snapshot)
            val targetPid = targetPid()
            val baseTransitionCount = snapshot.transitionCount
            val baseAutomaticCount = snapshot.automaticTransitionCount + snapshot.repeatTransitionCount
            val baseAutomaticDiscontinuities = snapshot.automaticDiscontinuityCount
            checkpoints += checkpoint("transition-0", transitionStartedAt, targetPid, snapshot)

            val targets = listOf(50.coerceAtMost(targetTransitions), 100.coerceAtMost(targetTransitions), targetTransitions)
                .distinct()
            for (target in targets) {
                snapshot = await(
                    description = "natural transition $target",
                    timeoutMs = TRANSITION_SOAK_TIMEOUT_MS,
                ) { candidate ->
                    candidate.transitionCount - baseTransitionCount >= target &&
                        candidate.isPlaying && !candidate.hasPlayerError
                }
                val completed = snapshot.transitionCount - baseTransitionCount
                val automatic = snapshot.automaticTransitionCount + snapshot.repeatTransitionCount -
                    baseAutomaticCount
                val discontinuities = snapshot.automaticDiscontinuityCount - baseAutomaticDiscontinuities
                check(automatic == completed)
                check(discontinuities == completed)
                check(snapshot.currentIndex == completed % snapshot.mediaIds.size)
                checkpoints += checkpoint("transition-$completed", transitionStartedAt, targetPid, snapshot)
            }
            val completedTransitions = snapshot.transitionCount - baseTransitionCount
            val naturalSoakDurationMs = SystemClock.elapsedRealtime() - transitionStartedAt
            check(completedTransitions in targetTransitions..(targetTransitions + 1))
            check(snapshot.errorCount == 0 && snapshot.sessionDisconnects == 0)

            val shuffleStart = snapshot.transitionCount
            snapshot = call("shuffle", "true").asSnapshot()
            check(snapshot.shuffleEnabled)
            snapshot = await("shuffle longevity", TRANSITION_SOAK_TIMEOUT_MS) {
                it.transitionCount - shuffleStart >= SHUFFLE_TRANSITIONS && it.isPlaying
            }
            check(snapshot.mediaId in snapshot.mediaIds)
            call("shuffle", "false")
            call("repeat", REPEAT_MODE_OFF.toString())
            snapshot = call("repeat", REPEAT_MODE_ALL.toString()).asSnapshot()
            check(!snapshot.shuffleEnabled && snapshot.repeatMode == REPEAT_MODE_ALL)
            val repeatStart = snapshot.transitionCount
            snapshot = await("repeat-all longevity", TRANSITION_SOAK_TIMEOUT_MS) {
                it.transitionCount - repeatStart >= REPEAT_TRANSITIONS && it.isPlaying
            }
            check(snapshot.mediaId in snapshot.mediaIds)
            emit(
                "TRANSITION_FINAL",
                snapshot.toJson()
                    .put("targetNaturalTransitions", targetTransitions)
                    .put("completedNaturalTransitions", completedTransitions)
                    .put("naturalSoakDurationMs", naturalSoakDurationMs)
                    .put("shuffleTransitions", SHUFFLE_TRANSITIONS)
                    .put("repeatAllTransitions", REPEAT_TRANSITIONS),
            )

            val churnStartedAt = SystemClock.elapsedRealtime()
            snapshot = prepare("q2.8-churn")
            val churnPid = targetPid()
            check(churnPid == targetPid)
            val churnStart = snapshot
            repeat(PAUSE_RESUME_CYCLES) {
                val paused = call("system-pause").asSnapshot()
                check(!paused.playWhenReady && !paused.isPlaying)
                val pausedAt = paused.positionMs
                SystemClock.sleep(CHURN_SETTLE_MS)
                check(abs(snapshot().positionMs - pausedAt) <= PAUSED_POSITION_TOLERANCE_MS)
                call("system-play")
                requireHealthy(await("churn resume") { it.isPlaying })
            }
            repeat(SEEK_CYCLES) { index ->
                val position = (index % 6) * 2_500L
                call("seek", position.toString())
                val sought = await("churn seek $index") { it.isPlaying && abs(it.positionMs - position) <= 1_500L }
                requireHealthy(sought)
            }
            repeat(NEXT_CYCLES) {
                requireHealthy(call("system-next").asSnapshot())
            }
            repeat(PREVIOUS_PAIR_CYCLES) {
                call("seek", "7000")
                val beforeRestart = snapshot()
                val restarted = call("system-previous").asSnapshot()
                check(restarted.currentIndex == beforeRestart.currentIndex && restarted.positionMs < 1_500L)
                val previous = call("system-previous").asSnapshot()
                check(previous.currentIndex != restarted.currentIndex)
                requireHealthy(previous)
            }
            repeat(CONTROLLER_RECONNECT_CYCLES) {
                check(call("release-controller").getBoolean("passed"))
                requireHealthy(call("reconnect").asSnapshot())
            }
            repeat(ROTATION_CYCLES) {
                rotateTargetOnce()
                requireHealthy(snapshot())
                check(targetPid() == targetPid)
            }
            repeat(BACKGROUND_FOREGROUND_CYCLES) {
                device.pressHome()
                SystemClock.sleep(CHURN_SETTLE_MS)
                bringTargetForeground()
                requireHealthy(snapshot())
                check(targetPid() == targetPid)
            }
            repeat(REFRESH_CYCLES) {
                val refreshed = call("refresh")
                check(refreshed.getLong("refreshElapsedNanos") > 0L)
                requireHealthy(refreshed.asSnapshot())
            }
            snapshot = requireAdvancing(snapshot())
            check(snapshot.errorCount == 0 && snapshot.sessionDisconnects == 0)
            check(snapshot.deliberateControllerReleases == CONTROLLER_RECONNECT_CYCLES)
            check(snapshot.controllerConnections == CONTROLLER_RECONNECT_CYCLES)
            check(snapshot.mediaIds == churnStart.mediaIds)
            check(targetPid() == targetPid)
            check(mediaSessionCount() == 1)
            check(libraryCount() == initialLibraryCount)
            val churnCheckpoint = checkpoint("churn-final", churnStartedAt, targetPid, snapshot)
            emit(
                "CHURN_FINAL",
                churnCheckpoint
                    .put("pauseResumeCycles", PAUSE_RESUME_CYCLES)
                    .put("seekCycles", SEEK_CYCLES)
                    .put("nextCycles", NEXT_CYCLES)
                    .put("previousCycles", PREVIOUS_PAIR_CYCLES * 2)
                    .put("controllerReconnectCycles", CONTROLLER_RECONNECT_CYCLES)
                    .put("activityRotationCycles", ROTATION_CYCLES)
                    .put("backgroundForegroundCycles", BACKGROUND_FOREGROUND_CYCLES)
                    .put("refreshCycles", REFRESH_CYCLES)
                    .put("durationMs", SystemClock.elapsedRealtime() - churnStartedAt),
            )

            val lateRecoveryStartedAt = SystemClock.elapsedRealtime()
            snapshot = prepare("q2.8-late-error")
            snapshot = await("late-session missing source") {
                it.mediaId == LATE_MISSING_ID && it.playerErrorCode == ERROR_IO_FILE_NOT_FOUND &&
                    it.playbackState == STATE_IDLE && !it.playWhenReady && !it.isPlaying
            }
            check(snapshot.errorCount == 1)
            snapshot = call("system-next").asSnapshot()
            snapshot = await("late-session recovery") {
                it.currentIndex == 1 && it.mediaId == "q2.8:late:valid:0" && it.isPlaying && !it.hasPlayerError
            }
            val recoveredTransitionStart = snapshot.transitionCount
            snapshot = await("post-recovery natural transitions", TRANSITION_SOAK_TIMEOUT_MS) {
                it.transitionCount - recoveredTransitionStart >= POST_RECOVERY_TRANSITIONS &&
                    it.isPlaying && !it.hasPlayerError
            }
            check(snapshot.errorCount == 1)
            check(snapshot.currentIndex == 1 + POST_RECOVERY_TRANSITIONS)
            check(snapshot.sessionDisconnects == 0)
            check(targetPid() == targetPid)
            emit(
                "LATE_RECOVERY_FINAL",
                snapshot.toJson()
                    .put("postRecoveryTransitions", POST_RECOVERY_TRANSITIONS)
                    .put("durationMs", SystemClock.elapsedRealtime() - lateRecoveryStartedAt)
                    .put("result", "PASS"),
            )
        } finally {
            runCatching { restoreRotation() }
            runCatching { call("reset") }
        }
    }

    private fun prepare(scenario: String): Snapshot = call("prepare", scenario).asSnapshot()

    private fun snapshot(): Snapshot = call("snapshot").asSnapshot()

    private fun call(method: String, argument: String? = null): Bundle =
        requireNotNull(resolver.call(PROBE_URI, method, argument, null))

    private fun await(
        description: String,
        timeoutMs: Long = STATE_TIMEOUT_MS,
        predicate: (Snapshot) -> Boolean,
    ): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs
        var latest = snapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = snapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.toJson()}")
    }

    private fun requireHealthy(snapshot: Snapshot) {
        check(snapshot.connected)
        check(snapshot.playbackState == STATE_READY)
        check(snapshot.playWhenReady && snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(!snapshot.hasPlayerError)
        check(snapshot.currentIndex in snapshot.mediaIds.indices)
        check(snapshot.mediaId == snapshot.mediaIds[snapshot.currentIndex])
        check(snapshot.uiMediaId == snapshot.mediaId)
        check(snapshot.uiCurrentIndex == snapshot.currentIndex)
        check(snapshot.uiErrorMessage == null)
    }

    private fun requireAdvancing(before: Snapshot): Snapshot {
        requireHealthy(before)
        SystemClock.sleep(ADVANCEMENT_OBSERVATION_MS)
        val after = snapshot()
        requireHealthy(after)
        if (after.mediaId == before.mediaId) {
            check(after.positionMs - before.positionMs >= MINIMUM_ADVANCEMENT_MS)
        } else {
            check(after.transitionCount > before.transitionCount)
        }
        return after
    }

    private fun awaitWallTime(startedAt: Long, durationMs: Long, nominalMinute: Int) {
        val target = startedAt + durationMs * nominalMinute / 45L
        while (SystemClock.elapsedRealtime() < target) {
            SystemClock.sleep((target - SystemClock.elapsedRealtime()).coerceAtMost(WALL_SLEEP_SLICE_MS))
        }
    }

    private fun checkpoint(
        label: String,
        startedAt: Long,
        expectedPid: Int,
        snapshot: Snapshot,
    ): JSONObject {
        requireHealthy(snapshot)
        check(targetPid() == expectedPid)
        val resource = resourceSnapshot(expectedPid)
        check(resource.mediaSessions == 1)
        check(resource.serviceForeground)
        val result = JSONObject()
            .put("label", label)
            .put("elapsedMs", (SystemClock.elapsedRealtime() - startedAt).coerceAtLeast(0L))
            .put("resource", resource.toJson())
            .put("playback", snapshot.toJson())
            .put("persistence", persistenceFile().toJson())
        emit("CHECKPOINT", result)
        return result
    }

    private fun resourceSnapshot(pid: Int): ResourceSnapshot {
        val meminfo = device.executeShellCommand("dumpsys meminfo $pid")
        val status = device.executeShellCommand("cat /proc/$pid/status")
        val fdOutput = device.executeShellCommand("sh -c 'ls /proc/$pid/fd 2>/dev/null | wc -l'").trim()
        val services = device.executeShellCommand("dumpsys activity services $TARGET_PACKAGE")
        return ResourceSnapshot(
            pid = pid,
            pssKb = Regex("TOTAL PSS:\\s*([0-9,]+)").find(meminfo)
                ?.groupValues?.get(1)?.replace(",", "")?.toLongOrNull() ?: -1L,
            rssKb = Regex("VmRSS:\\s*(\\d+)").find(status)?.groupValues?.get(1)?.toLongOrNull() ?: -1L,
            threads = Regex("Threads:\\s*(\\d+)").find(status)?.groupValues?.get(1)?.toIntOrNull() ?: -1,
            fileDescriptors = fdOutput.toIntOrNull() ?: -1,
            mediaSessions = mediaSessionCount(),
            serviceForeground = "isForeground=true" in services || "foregroundId=" in services,
        )
    }

    private fun targetPid(): Int =
        device.executeShellCommand("pidof $TARGET_PACKAGE").trim().split(" ").firstOrNull()?.toIntOrNull() ?: -1

    private fun mediaSessionCount(): Int {
        val dump = device.executeShellCommand("dumpsys media_session")
        val marker = "package=$TARGET_PACKAGE"
        return dump.windowed(marker.length).count { it == marker }
    }

    private fun persisted(): Bundle = call("persisted")

    private fun persistenceFile(): PersistenceFile {
        val bundle = call("persistence-file")
        return PersistenceFile(
            exists = bundle.getBoolean("exists"),
            length = bundle.getLong("length"),
            lastModifiedMs = bundle.getLong("lastModifiedMs"),
        )
    }

    private fun libraryCount(): Int = call("library-summary").getInt("songCount")

    private fun bringTargetForeground() {
        val intent = Intent().setComponent(ComponentName(TARGET_PACKAGE, TARGET_ACTIVITY))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS))
        device.waitForIdle()
    }

    private fun rotateTargetOnce() {
        device.executeShellCommand("settings put system accelerometer_rotation 0")
        device.executeShellCommand("settings put system user_rotation 1")
        SystemClock.sleep(ROTATION_SETTLE_MS)
        device.executeShellCommand("settings put system user_rotation 0")
        SystemClock.sleep(ROTATION_SETTLE_MS)
        bringTargetForeground()
    }

    private fun restoreRotation() {
        device.executeShellCommand("settings put system accelerometer_rotation 0")
        device.executeShellCommand("settings put system user_rotation 0")
    }

    private fun emit(phase: String, value: JSONObject) {
        val line = "$phase ${value}"
        Log.i(LOG_TAG, line)
        println("Q2.8 $line")
    }

    private fun Bundle.asSnapshot(): Snapshot = Snapshot(
        mediaId = getString("mediaId"),
        currentIndex = getInt("currentIndex"),
        mediaIds = getStringArrayList("mediaIds").orEmpty(),
        positionMs = getLong("positionMs"),
        playbackState = getInt("playbackState"),
        playWhenReady = getBoolean("playWhenReady"),
        suppressionReason = getInt("suppressionReason"),
        isPlaying = getBoolean("isPlaying"),
        connected = getBoolean("connected"),
        playerErrorCode = getInt("playerErrorCode"),
        errorCount = getInt("errorCount"),
        transitionCount = getInt("transitionCount"),
        automaticTransitionCount = getInt("automaticTransitionCount"),
        repeatTransitionCount = getInt("repeatTransitionCount"),
        seekTransitionCount = getInt("seekTransitionCount"),
        discontinuityCount = getInt("discontinuityCount"),
        automaticDiscontinuityCount = getInt("automaticDiscontinuityCount"),
        seekDiscontinuityCount = getInt("seekDiscontinuityCount"),
        sessionDisconnects = getInt("sessionDisconnects"),
        controllerConnections = getInt("controllerConnections"),
        deliberateControllerReleases = getInt("deliberateControllerReleases"),
        repeatMode = getInt("repeatMode"),
        shuffleEnabled = getBoolean("shuffleEnabled"),
        uiMediaId = getString("uiMediaId"),
        uiCurrentIndex = getInt("uiCurrentIndex"),
        uiErrorMessage = getString("uiErrorMessage"),
    )

    private data class Snapshot(
        val mediaId: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
        val isPlaying: Boolean,
        val connected: Boolean,
        val playerErrorCode: Int,
        val errorCount: Int,
        val transitionCount: Int,
        val automaticTransitionCount: Int,
        val repeatTransitionCount: Int,
        val seekTransitionCount: Int,
        val discontinuityCount: Int,
        val automaticDiscontinuityCount: Int,
        val seekDiscontinuityCount: Int,
        val sessionDisconnects: Int,
        val controllerConnections: Int,
        val deliberateControllerReleases: Int,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
        val uiMediaId: String?,
        val uiCurrentIndex: Int,
        val uiErrorMessage: String?,
    ) {
        val hasPlayerError: Boolean get() = playerErrorCode != NO_ERROR

        fun toJson(): JSONObject = JSONObject()
            .put("mediaId", mediaId)
            .put("currentIndex", currentIndex)
            .put("queueSize", mediaIds.size)
            .put("mediaIds", JSONArray(mediaIds))
            .put("positionMs", positionMs)
            .put("playbackState", playbackState)
            .put("playWhenReady", playWhenReady)
            .put("isPlaying", isPlaying)
            .put("suppressionReason", suppressionReason)
            .put("playerErrorCode", playerErrorCode)
            .put("errorCount", errorCount)
            .put("transitionCount", transitionCount)
            .put("automaticTransitionCount", automaticTransitionCount)
            .put("repeatTransitionCount", repeatTransitionCount)
            .put("seekTransitionCount", seekTransitionCount)
            .put("discontinuityCount", discontinuityCount)
            .put("automaticDiscontinuityCount", automaticDiscontinuityCount)
            .put("seekDiscontinuityCount", seekDiscontinuityCount)
            .put("sessionDisconnects", sessionDisconnects)
            .put("controllerConnections", controllerConnections)
            .put("deliberateControllerReleases", deliberateControllerReleases)
            .put("repeatMode", repeatMode)
            .put("shuffleEnabled", shuffleEnabled)
            .put("connected", connected)
            .put("uiMediaId", uiMediaId)
            .put("uiCurrentIndex", uiCurrentIndex)
            .put("uiErrorMessage", uiErrorMessage)
    }

    private data class ResourceSnapshot(
        val pid: Int,
        val pssKb: Long,
        val rssKb: Long,
        val threads: Int,
        val fileDescriptors: Int,
        val mediaSessions: Int,
        val serviceForeground: Boolean,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("pid", pid)
            .put("pssKb", pssKb)
            .put("rssKb", rssKb)
            .put("threads", threads)
            .put("fileDescriptors", fileDescriptors)
            .put("mediaSessions", mediaSessions)
            .put("serviceForeground", serviceForeground)
    }

    private data class PersistenceFile(
        val exists: Boolean,
        val length: Long,
        val lastModifiedMs: Long,
    ) {
        fun toJson(): JSONObject = JSONObject()
            .put("exists", exists)
            .put("length", length)
            .put("lastModifiedMs", lastModifiedMs)
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val TARGET_ACTIVITY = "com.libreplayer.app.MainActivity"
        const val LOG_TAG = "LibrePlayerQ28"
        const val LATE_MISSING_ID = "q2.8:late:missing"
        const val ERROR_IO_FILE_NOT_FOUND = 2_005
        const val NO_ERROR = Int.MIN_VALUE
        const val STATE_IDLE = 1
        const val STATE_READY = 3
        const val SUPPRESSION_NONE = 0
        const val REPEAT_MODE_OFF = 0
        const val REPEAT_MODE_ALL = 2
        const val MIN_WALL_DURATION_MS = 20_000L
        const val MAX_WALL_DURATION_MS = 3_600_000L
        const val STATE_TIMEOUT_MS = 12_000L
        const val TRANSITION_SOAK_TIMEOUT_MS = 12 * 60_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val POLL_MS = 100L
        const val WALL_SLEEP_SLICE_MS = 30_000L
        const val PAUSE_STABILITY_MS = 2_000L
        const val PAUSED_POSITION_TOLERANCE_MS = 300L
        const val ADVANCEMENT_OBSERVATION_MS = 1_000L
        const val MINIMUM_ADVANCEMENT_MS = 500L
        const val CHURN_SETTLE_MS = 250L
        const val ROTATION_SETTLE_MS = 750L
        const val SHUFFLE_TRANSITIONS = 12
        const val REPEAT_TRANSITIONS = 6
        const val POST_RECOVERY_TRANSITIONS = 3
        const val PAUSE_RESUME_CYCLES = 10
        const val SEEK_CYCLES = 10
        const val NEXT_CYCLES = 12
        const val PREVIOUS_PAIR_CYCLES = 3
        const val CONTROLLER_RECONNECT_CYCLES = 10
        const val ROTATION_CYCLES = 5
        const val BACKGROUND_FOREGROUND_CYCLES = 5
        const val REFRESH_CYCLES = 5
        val PROBE_URI: Uri = Uri.parse("content://com.libreplayer.adversarial-media-probe")
        val EXPECTED_FIXTURES = arrayListOf(
            "good-a.flac:34617:99ba43f6984bb05a8753f0edf3df44f2f10a371f0d2f4a161a7401c1e1b91122",
            "good-c.flac:34617:99ba43f6984bb05a8753f0edf3df44f2f10a371f0d2f4a161a7401c1e1b91122",
            "good-long.flac:272022:92cf7501065e8c0c0582f5e157940fb240b9bf0aeed6eeca06520fc592ae94f4",
            "missing.flac:missing:-",
            "zero.flac:0:e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            "garbage.mp3:3072:33986ba530755f39dbc3d5f8a412108357bc4e05f8dee94edbc0cb4091e1472d",
            "truncated.flac:8192:6c5886891619a5805992ceeb1ef580608cbabbd4af3c571c4d32aef3cf729260",
            "deletable.flac:272022:92cf7501065e8c0c0582f5e157940fb240b9bf0aeed6eeca06520fc592ae94f4",
        )
    }
}

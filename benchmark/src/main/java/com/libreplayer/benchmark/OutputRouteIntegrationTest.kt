package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.Intent
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.Until
import java.util.UUID
import org.junit.Test
import org.junit.runner.RunWith

/** Bounded Q2.6 authority over Media3's real becoming-noisy path on Android 16. */
@RunWith(AndroidJUnit4::class)
class OutputRouteIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun deviceSwitchingAndOutputRobustnessAuthority() {
        val results = linkedMapOf<String, String>()
        try {
            // D1 — playing baseline and privacy-safe output-type observation.
            bringTargetForeground()
            val baseline = focusCall("start").asFocusSnapshot()
            requireActive(baseline)
            check(AudioDeviceInfo.TYPE_BUILTIN_SPEAKER in baseline.outputDeviceTypes)
            val advanced = requireAdvance(baseline)
            requireSingleSession(FOCUS_MEDIA_ID, FOCUS_TITLE)
            results["D1"] = "outputs=${baseline.outputDeviceTypes} ${advanced.summary()}"

            // D2 — a real platform broadcast reaches Media3's dynamically registered receiver.
            val noisyOutput = sendNoisyBroadcast()
            val noisy = awaitFocus("becoming-noisy safety pause") {
                !it.playWhenReady && !it.isPlaying &&
                    it.lastPlayWhenReadyReason == REASON_AUDIO_BECOMING_NOISY
            }
            requireSafetyPause(noisy, baseline)
            requireStable(noisy)
            requireSingleSession(FOCUS_MEDIA_ID, FOCUS_TITLE)
            check(noisy.playWhenReadyEvents.count { event ->
                event.startsWith("false:$REASON_AUDIO_BECOMING_NOISY:")
            } == 1)
            results["D2"] = "broadcast=$noisyOutput ${noisy.summary()}"

            // D3 — the emulator has no legitimate route-return apparatus; time alone cannot resume.
            val remainsPaused = requireStable(noisy)
            requireSafetyPause(remainsPaused, baseline)
            results["D3"] = "route simulation unavailable; remained paused ${remainsPaused.summary()}"

            // D4 — an explicit normal Play resumes the same item and occurrence.
            val explicitPlay = focusCall("user-play").asFocusSnapshot()
            requireActive(explicitPlay)
            check(explicitPlay.currentIndex == noisy.currentIndex)
            check(explicitPlay.mediaItemCount == noisy.mediaItemCount)
            requireAdvance(explicitPlay)
            results["D4"] = explicitPlay.summary()

            // D5 — noisy while already paused neither starts playback nor changes identity.
            val explicitlyPaused = focusCall("user-pause").asFocusSnapshot()
            check(!explicitlyPaused.playWhenReady && !explicitlyPaused.isPlaying)
            val noisyReasonsBefore = explicitlyPaused.noisyReasonCount()
            sendNoisyBroadcast()
            val pausedNoisy = requireStable(awaitFocus("noisy while paused") {
                !it.playWhenReady && !it.isPlaying
            })
            check(pausedNoisy.noisyReasonCount() == noisyReasonsBefore + 1)
            check(pausedNoisy.mediaId == FOCUS_MEDIA_ID && pausedNoisy.currentIndex == 0)
            results["D5"] = pausedNoisy.summary()

            // D6 — the service handles noisy while the Activity is backgrounded.
            bringTargetForeground()
            focusCall("start")
            device.pressHome()
            sendNoisyBroadcast()
            val backgroundNoisy = awaitFocus("background noisy pause") {
                !it.playWhenReady && !it.isPlaying &&
                    it.lastPlayWhenReadyReason == REASON_AUDIO_BECOMING_NOISY
            }
            requireSafetyPause(backgroundNoisy)
            requireNotification(FOCUS_TITLE, expectedPrimaryAction = "Play")
            bringTargetForeground()
            val reopened = awaitFocus("reopened paused projection") {
                it.uiMediaId == FOCUS_MEDIA_ID && !it.uiPlayWhenReady && !it.uiIsPlaying
            }
            results["D6"] = reopened.summary()

            // D7 — a fresh external controller projects the same safety pause.
            val beforeReconnect = focusSnapshot()
            check(focusCall("release-controller").getBoolean("passed"))
            focusCall("reconnect")
            val reconnected = awaitFocus("controller reconnect after noisy") {
                it.connected && it.mediaId == beforeReconnect.mediaId &&
                    !it.playWhenReady && !it.isPlaying
            }
            check(reconnected.currentIndex == beforeReconnect.currentIndex)
            check(reconnected.positionMs + RECONNECT_TOLERANCE_MS >= beforeReconnect.positionMs)
            check(reconnected.sessionDisconnects == 0)
            requireSingleSession(FOCUS_MEDIA_ID, FOCUS_TITLE)
            results["D7"] = reconnected.summary()

            // D8 — the actual safety-pause intent reaches the existing snapshot store.
            val persisted = awaitPersisted {
                it.getStringArrayList("queueIds") == arrayListOf(FOCUS_MEDIA_ID) &&
                    it.getInt("currentIndex") == 0 &&
                    !it.getBoolean("playWhenReady")
            }
            results["D8"] = "index=${persisted.getInt("currentIndex")} " +
                "position=${persisted.getLong("positionMs")} pwr=${persisted.getBoolean("playWhenReady")}"

            // D9 — two bounded events produce one state transition and no oscillation.
            focusCall("start")
            sendNoisyBroadcast()
            sendNoisyBroadcast()
            val rapid = awaitFocus("bounded repeated noisy events") {
                !it.playWhenReady && !it.isPlaying && it.noisyReasonCount() == 1
            }
            requireStable(rapid)
            check(rapid.playerErrors == 0 && rapid.sessionDisconnects == 0)
            results["D9"] = rapid.summary()

            // D10 — no supported emulator route handoff exists; do not fake one.
            results["D10"] = "LIMITED: no wired/A2DP endpoint or supported route-switch shell command"

            // D11 — one natural A→B transition remains coherent when noisy follows it.
            bringTargetForeground()
            systemCall("prepare-short")
            device.pressHome()
            val successor = awaitSystem("automatic successor before noisy") {
                it.mediaId == TRANSITION_SUCCESSOR && it.currentIndex == 1 && it.isPlaying
            }
            sendNoisyBroadcast()
            val successorPaused = awaitSystem("successor safety pause") {
                it.mediaId == TRANSITION_SUCCESSOR && it.currentIndex == 1 &&
                    !it.playWhenReady && !it.isPlaying
            }
            check(successorPaused.mediaIds == successor.mediaIds)
            check(successorPaused.mediaIds.size == 2)
            requireSystemStable(successorPaused)
            requireSingleSession(TRANSITION_SUCCESSOR, TRANSITION_TITLE)
            val systemPlay = systemCall("external-play").asSystemSnapshot()
            check(systemPlay.mediaId == TRANSITION_SUCCESSOR && systemPlay.isPlaying)
            requireNotification(TRANSITION_TITLE, expectedPrimaryAction = "Pause")
            results["D11"] = "paused=${successorPaused.summary()} resumed=${systemPlay.summary()}"

            // Focused Q2.1/Q2.2/Q2.5 transport regressions against the same live session.
            systemCall("prepare-long")
            check(!systemCall("external-pause").asSystemSnapshot().playWhenReady)
            check(systemCall("external-play").asSystemSnapshot().playWhenReady)
            val sought = systemCall("external-seek", "7000").asSystemSnapshot()
            check(sought.positionMs in 6_700L..8_500L)
            systemCall("external-next")
            val next = awaitSystem("focused next") { it.mediaId == LONG_THREE && it.currentIndex == 2 }
            check(next.mediaId == LONG_THREE)
            systemCall("external-previous")
            val previous = awaitSystem("focused previous") {
                it.mediaId == LONG_TWO && it.currentIndex == 1
            }
            results["Q2.1-Q2.2-Q2.5"] = previous.summary()

            // Q2.4 remains distinct: focus suppression preserves play intent and auto-resumes.
            bringTargetForeground()
            focusCall("start")
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val focusLoss = awaitFocus("focused transient focus loss") {
                it.playWhenReady && !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            abandonAggressor()
            val focusGain = awaitFocus("focused transient focus gain") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            results["Q2.4"] = "loss=${focusLoss.summary()} gain=${focusGain.summary()}"

            val final = focusSnapshot()
            check(final.playerErrors == 0 && final.sessionDisconnects == 0)
            results.forEach { (phase, result) ->
                Log.i(LOG_TAG, "$phase $result")
                println("Q2.6 $phase $result")
            }
        } finally {
            runCatching { abandonAggressor() }
            runCatching { focusCall("reset") }
            runCatching { systemCall("reset") }
        }
    }

    private fun sendNoisyBroadcast(): String =
        device.executeShellCommand(
            "su 0 am broadcast --user current -a android.media.AUDIO_BECOMING_NOISY",
        ).trim().also { output ->
            check("Broadcast completed" in output) { "Root noisy broadcast failed: $output" }
        }

    private fun bringTargetForeground() {
        val intent = requireNotNull(
            instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS))
        device.waitForIdle()
    }

    private fun requireActive(snapshot: FocusSnapshot) {
        check(snapshot.mediaId == FOCUS_MEDIA_ID && snapshot.currentIndex == 0)
        check(snapshot.mediaItemCount == 1 && snapshot.playbackState == STATE_READY)
        check(snapshot.playWhenReady && snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.connected && !snapshot.hasPlayerError)
        check(snapshot.uiPlayWhenReady && snapshot.uiIsPlaying)
    }

    private fun requireSafetyPause(snapshot: FocusSnapshot, baseline: FocusSnapshot? = null) {
        check(snapshot.mediaId == FOCUS_MEDIA_ID && snapshot.currentIndex == 0)
        check(snapshot.mediaItemCount == 1 && snapshot.playbackState == STATE_READY)
        check(!snapshot.playWhenReady && !snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.lastPlayWhenReadyReason == REASON_AUDIO_BECOMING_NOISY)
        check(snapshot.connected && !snapshot.hasPlayerError)
        check(!snapshot.uiPlayWhenReady && !snapshot.uiIsPlaying)
        baseline?.let { check(snapshot.mediaId == it.mediaId && snapshot.currentIndex == it.currentIndex) }
    }

    private fun requireAdvance(before: FocusSnapshot): FocusSnapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = focusSnapshot()
        check(after.positionMs - before.positionMs >= ACTIVE_POSITION_MINIMUM_MS) {
            "Position did not advance: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requireStable(before: FocusSnapshot): FocusSnapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = focusSnapshot()
        check(after.positionMs - before.positionMs <= STABLE_POSITION_TOLERANCE_MS) {
            "Position advanced while paused: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requireSystemStable(before: SystemSnapshot) {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = systemSnapshot()
        check(after.mediaId == before.mediaId && after.currentIndex == before.currentIndex)
        check(after.positionMs - before.positionMs <= STABLE_POSITION_TOLERANCE_MS)
    }

    private fun requireSingleSession(expectedMediaId: String, expectedTitle: String) {
        val dump = device.executeShellCommand("dumpsys media_session")
        val marker = "package=$TARGET_PACKAGE"
        check(dump.windowed(marker.length).count { it == marker } == 1)
        val start = dump.indexOf(marker)
        check(start >= 0)
        val end = dump.indexOf("\n    package=", start + marker.length)
            .takeIf { it >= 0 } ?: (start + SESSION_CONTEXT_CHARS).coerceAtMost(dump.length)
        val block = dump.substring(start, end)
        check("description=$expectedTitle" in block) { "Wrong session item for $expectedMediaId" }
        check("error=null" in block)
    }

    private fun requireNotification(expectedTitle: String, expectedPrimaryAction: String) {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val dump = device.executeShellCommand("dumpsys notification --noredact")
                .substringAfter("Notification List:")
                .substringBefore("Notification listeners:")
            val records = dump.lines().filter { line ->
                line.startsWith("    NotificationRecord(") && "pkg=$TARGET_PACKAGE " in line
            }
            val keys = records.mapNotNull { line ->
                Regex("key=([^:]+): Notification").find(line)?.groupValues?.get(1)
            }.distinct()
            if (
                keys.size == 1 &&
                "android.title=String ($expectedTitle)" in dump &&
                "\"$expectedPrimaryAction\" ->" in dump
            ) return
            SystemClock.sleep(POLL_MS)
        }
        error("Notification did not project title=$expectedTitle action=$expectedPrimaryAction")
    }

    private fun awaitFocus(description: String, predicate: (FocusSnapshot) -> Boolean): FocusSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var latest = focusSnapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = focusSnapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.summary()}")
    }

    private fun awaitSystem(description: String, predicate: (SystemSnapshot) -> Boolean): SystemSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var latest = systemSnapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = systemSnapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.summary()}")
    }

    private fun awaitPersisted(predicate: (Bundle) -> Boolean): Bundle {
        val deadline = SystemClock.elapsedRealtime() + PERSISTENCE_TIMEOUT_MS
        var latest = focusCall("persisted")
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = focusCall("persisted")
            if (predicate(latest)) return latest
            SystemClock.sleep(PERSISTENCE_POLL_MS)
        }
        error("Timed out waiting for persisted safety pause: $latest")
    }

    private fun requestAggressor(gain: Int): FocusAggressorRegistry.Snapshot {
        val token = UUID.randomUUID().toString()
        val intent = Intent(FocusAggressorActivity.ACTION_REQUEST)
            .setComponent(ComponentName(instrumentation.context, FocusAggressorActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(FocusAggressorActivity.EXTRA_TOKEN, token)
            .putExtra(FocusAggressorActivity.EXTRA_GAIN, gain)
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(AGGRESSOR_PACKAGE).depth(0)), UI_TIMEOUT_MS))
        return awaitAggressor(token) { it.requestResult != Int.MIN_VALUE }
    }

    private fun abandonAggressor(): FocusAggressorRegistry.Snapshot {
        val token = UUID.randomUUID().toString()
        val intent = Intent(FocusAggressorActivity.ACTION_ABANDON)
            .setComponent(ComponentName(instrumentation.context, FocusAggressorActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(FocusAggressorActivity.EXTRA_TOKEN, token)
        instrumentation.context.startActivity(intent)
        return awaitAggressor(token) { it.abandonResult != Int.MIN_VALUE }
    }

    private fun awaitAggressor(
        token: String,
        predicate: (FocusAggressorRegistry.Snapshot) -> Boolean,
    ): FocusAggressorRegistry.Snapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val state = FocusAggressorRegistry.snapshot
            if (state.token == token && predicate(state)) return state
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for focus aggressor token=$token")
    }

    private fun FocusAggressorRegistry.Snapshot.requireGranted() {
        check(requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) { "Focus request failed: $this" }
    }

    private fun focusCall(method: String): Bundle =
        requireNotNull(resolver.call(FOCUS_PROBE_URI, method, null, null))

    private fun systemCall(method: String, argument: String? = null): Bundle =
        requireNotNull(resolver.call(SYSTEM_PROBE_URI, method, argument, null))

    private fun focusSnapshot(): FocusSnapshot = focusCall("snapshot").asFocusSnapshot()
    private fun systemSnapshot(): SystemSnapshot = systemCall("snapshot").asSystemSnapshot()

    private fun Bundle.asFocusSnapshot(): FocusSnapshot = FocusSnapshot(
        mediaId = getString("mediaId"),
        currentIndex = getInt("currentIndex"),
        mediaItemCount = getInt("mediaItemCount"),
        positionMs = getLong("positionMs"),
        playbackState = getInt("playbackState"),
        playWhenReady = getBoolean("playWhenReady"),
        suppressionReason = getInt("suppressionReason"),
        isPlaying = getBoolean("isPlaying"),
        connected = getBoolean("connected"),
        hasPlayerError = getBoolean("hasPlayerError"),
        lastPlayWhenReadyReason = getInt("lastPlayWhenReadyReason"),
        playerErrors = getInt("playerErrors"),
        sessionDisconnects = getInt("sessionDisconnects"),
        uiMediaId = getString("uiMediaId"),
        uiPlayWhenReady = getBoolean("uiPlayWhenReady"),
        uiIsPlaying = getBoolean("uiIsPlaying"),
        outputDeviceTypes = getIntArray("outputDeviceTypes")?.toList().orEmpty(),
        playWhenReadyEvents = getString("playWhenReadyEvents").orEmpty().split('|').filter(String::isNotBlank),
    )

    private fun Bundle.asSystemSnapshot(): SystemSnapshot = SystemSnapshot(
        mediaId = getString("mediaId"),
        currentIndex = getInt("currentIndex"),
        mediaIds = getStringArrayList("mediaIds").orEmpty(),
        positionMs = getLong("positionMs"),
        playWhenReady = getBoolean("playWhenReady"),
        isPlaying = getBoolean("isPlaying"),
        playerErrors = getInt("playerErrors"),
        sessionDisconnects = getInt("sessionDisconnects"),
    )

    private data class FocusSnapshot(
        val mediaId: String?,
        val currentIndex: Int,
        val mediaItemCount: Int,
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
        val isPlaying: Boolean,
        val connected: Boolean,
        val hasPlayerError: Boolean,
        val lastPlayWhenReadyReason: Int,
        val playerErrors: Int,
        val sessionDisconnects: Int,
        val uiMediaId: String?,
        val uiPlayWhenReady: Boolean,
        val uiIsPlaying: Boolean,
        val outputDeviceTypes: List<Int>,
        val playWhenReadyEvents: List<String>,
    ) {
        fun noisyReasonCount(): Int = playWhenReadyEvents.count { event ->
            event.startsWith("false:$REASON_AUDIO_BECOMING_NOISY:")
        }

        fun summary(): String =
            "id=$mediaId index=$currentIndex/$mediaItemCount pos=$positionMs state=$playbackState " +
                "pwr=$playWhenReady suppression=$suppressionReason playing=$isPlaying " +
                "reason=$lastPlayWhenReadyReason outputs=$outputDeviceTypes events=$playWhenReadyEvents " +
                "connected=$connected errors=$playerErrors disconnects=$sessionDisconnects " +
                "uiId=$uiMediaId uiPwr=$uiPlayWhenReady uiPlaying=$uiIsPlaying"
    }

    private data class SystemSnapshot(
        val mediaId: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val playerErrors: Int,
        val sessionDisconnects: Int,
    ) {
        fun summary(): String =
            "id=$mediaId index=$currentIndex/${mediaIds.size} pos=$positionMs " +
                "pwr=$playWhenReady playing=$isPlaying errors=$playerErrors disconnects=$sessionDisconnects"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val AGGRESSOR_PACKAGE = "com.libreplayer.benchmark"
        const val LOG_TAG = "LibrePlayerQ26"
        const val FOCUS_MEDIA_ID = "q2.4:audio-focus"
        const val FOCUS_TITLE = "Q2.4 audio focus authority"
        const val TRANSITION_SUCCESSOR = "q2.5:short:2"
        const val TRANSITION_TITLE = "Q2.5 short 2"
        const val LONG_TWO = "q2.5:long:2"
        const val LONG_THREE = "q2.5:long:3"
        const val STATE_READY = 3
        const val SUPPRESSION_NONE = 0
        const val SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS = 1
        const val REASON_AUDIO_BECOMING_NOISY = 3
        const val POSITION_OBSERVATION_MS = 700L
        const val ACTIVE_POSITION_MINIMUM_MS = 350L
        const val STABLE_POSITION_TOLERANCE_MS = 200L
        const val RECONNECT_TOLERANCE_MS = 500L
        const val POLL_MS = 30L
        const val PERSISTENCE_POLL_MS = 50L
        const val STATE_TIMEOUT_MS = 10_000L
        const val PERSISTENCE_TIMEOUT_MS = 8_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_TIMEOUT_MS = 8_000L
        const val SESSION_CONTEXT_CHARS = 8_000
        val FOCUS_PROBE_URI: Uri = Uri.parse("content://com.libreplayer.audio-focus-probe")
        val SYSTEM_PROBE_URI: Uri = Uri.parse("content://com.libreplayer.system-control-probe")
    }
}

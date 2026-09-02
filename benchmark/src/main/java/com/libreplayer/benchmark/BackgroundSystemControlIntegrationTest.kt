package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.Intent
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

@RunWith(AndroidJUnit4::class)
class BackgroundSystemControlIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun systemPreviousExactThresholdAuthority() {
        try {
            bringTargetForeground()

            call("prepare-long")
            val exactPaused = call("external-pause").asSnapshot()
            check(!exactPaused.playWhenReady && !exactPaused.isPlaying)
            val exactPosition = awaitAfterCommand(
                "external-seek",
                "5000",
                "exact-threshold seek",
            ) {
                it.mediaId == LONG_TWO &&
                    it.currentIndex == 1 &&
                    it.positionMs == 5_000L &&
                    !it.playWhenReady &&
                    !it.isPlaying
            }
            check(exactPosition.positionMs == 5_000L)
            val exactPrevious = awaitAfterCommand(
                "external-previous",
                "exact-threshold system Previous",
            ) {
                it.mediaId == LONG_ONE &&
                    it.currentIndex == 0 &&
                    it.positionMs < ITEM_START_TOLERANCE_MS &&
                    !it.playWhenReady &&
                    !it.isPlaying
            }
            requireSingleSession(LONG_ONE)
            check(!exactPrevious.hasPlayerError)
            check(exactPrevious.playerErrors == 0)
            check(exactPrevious.sessionDisconnects == 0)

            call("prepare-long")
            val abovePaused = call("external-pause").asSnapshot()
            check(!abovePaused.playWhenReady && !abovePaused.isPlaying)
            val abovePosition = awaitAfterCommand(
                "external-seek",
                "6000",
                "above-threshold seek",
            ) {
                it.mediaId == LONG_TWO &&
                    it.currentIndex == 1 &&
                    it.positionMs == 6_000L &&
                    !it.playWhenReady &&
                    !it.isPlaying
            }
            check(abovePosition.positionMs == 6_000L)
            val abovePrevious = awaitAfterCommand(
                "external-previous",
                "above-threshold system Previous",
            ) {
                it.mediaId == LONG_TWO &&
                    it.currentIndex == 1 &&
                    it.positionMs < ITEM_START_TOLERANCE_MS &&
                    !it.playWhenReady &&
                    !it.isPlaying
            }
            requireSingleSession(LONG_TWO)
            check(!abovePrevious.hasPlayerError)
            check(abovePrevious.playerErrors == 0)
            check(abovePrevious.sessionDisconnects == 0)

            Log.i(
                LOG_TAG,
                "D2 exact=${exactPrevious.summary()} above=${abovePrevious.summary()}",
            )
        } finally {
            runCatching { call("reset") }
        }
    }

    @Test
    fun backgroundSessionAndSystemControlAuthority() {
        val results = linkedMapOf<String, String>()
        try {
            // B1 — foreground to background with one active session and notification.
            bringTargetForeground()
            var baseline = call("prepare-long").asSnapshot()
            requireActive(baseline, LONG_TWO)
            requireSystemSurface(LONG_TWO)
            val targetPid = targetPid()
            device.pressHome()
            val background = requireAdvance(baseline)
            check(targetPid() == targetPid)
            requireActive(background, LONG_TWO)
            requireSystemSurface(LONG_TWO)
            results["B1"] = background.summary()

            // B2 — real Activity recreation via configuration change keeps the session/player.
            bringTargetForeground()
            call("reset-activity")
            val beforeRecreate = snapshot()
            device.setOrientationLeft()
            device.waitForIdle()
            SystemClock.sleep(ACTIVITY_SETTLE_MS)
            device.setOrientationNatural()
            device.waitForIdle()
            SystemClock.sleep(ACTIVITY_SETTLE_MS)
            device.unfreezeRotation()
            val lifecycle = call("activity")
            check(lifecycle.getInt("activityCreates") >= 1)
            check(lifecycle.getInt("activityDestroys") >= 1)
            check(lifecycle.getInt("activityResumes") >= 1)
            val afterRecreate = awaitSnapshot("Activity recreation projection") {
                it.uiConnected && it.uiMediaId == LONG_TWO && it.uiIsPlaying
            }
            check(afterRecreate.positionMs > beforeRecreate.positionMs)
            check(afterRecreate.mediaIds == beforeRecreate.mediaIds)
            check(targetPid() == targetPid)
            requireSingleSession(LONG_TWO)
            results["B2"] = "creates=${lifecycle.getInt("activityCreates")} " +
                "destroys=${lifecycle.getInt("activityDestroys")} ${afterRecreate.summary()}"

            // B3 — an external controller reconnects to the existing authoritative state.
            val beforeReconnect = snapshot()
            check(call("release-controller").getBoolean("passed"))
            call("reconnect")
            val reconnected = awaitSnapshot("controller reconnect projection") {
                it.connected &&
                    it.mediaId == beforeReconnect.mediaId &&
                    it.currentIndex == beforeReconnect.currentIndex &&
                    it.positionMs + RECONNECT_POSITION_TOLERANCE_MS >= beforeReconnect.positionMs
            }
            check(reconnected.connected)
            check(reconnected.mediaIds == beforeReconnect.mediaIds)
            check(reconnected.mediaId == beforeReconnect.mediaId)
            check(reconnected.currentIndex == beforeReconnect.currentIndex)
            check(reconnected.repeatMode == beforeReconnect.repeatMode)
            check(reconnected.shuffleEnabled == beforeReconnect.shuffleEnabled)
            check(reconnected.sessionDisconnects == 0)
            results["B3"] = reconnected.summary()

            // B4 — real MediaController Pause and Play.
            val paused = call("external-pause").asSnapshot()
            check(!paused.playWhenReady && !paused.isPlaying)
            requireStable(paused)
            val played = call("external-play").asSnapshot()
            requireActive(requireAdvance(played), LONG_TWO)
            results["B4"] = "paused=${paused.summary()} played=${played.summary()}"

            // B5 — session Next follows the timeline.
            call("prepare-long")
            val next = awaitAfterCommand("external-next", "system next") {
                it.mediaId == LONG_THREE && it.currentIndex == 2 && it.isPlaying
            }
            results["B5"] = next.summary()

            // B6 — session Previous obeys LibrePlayer's five-second boundary.
            call("prepare-long")
            call("external-seek", "4000")
            val belowThreshold = awaitAfterCommand("external-previous", "previous below threshold") {
                it.mediaId == LONG_ONE && it.currentIndex == 0 && it.positionMs < ITEM_START_TOLERANCE_MS
            }
            call("prepare-long")
            call("external-seek", "6000")
            val aboveThreshold = awaitAfterCommand("external-previous", "previous above threshold") {
                it.mediaId == LONG_TWO && it.currentIndex == 1 && it.positionMs < ITEM_START_TOLERANCE_MS
            }
            results["B6"] = "below=${belowThreshold.summary()} above=${aboveThreshold.summary()}"

            // B7 — one ordinary external seek uses the live player position.
            call("prepare-long")
            val sought = awaitAfterCommand("external-seek", "7000", "system seek") {
                it.mediaId == LONG_TWO && it.positionMs in 6_800L..8_500L && it.isPlaying
            }
            results["B7"] = sought.summary()

            // B8 — a natural transition updates session metadata while backgrounded.
            bringTargetForeground()
            call("prepare-short")
            device.pressHome()
            val successor = awaitSnapshot("background automatic successor") {
                it.mediaId == SHORT_TWO && it.currentIndex == 1 && it.isPlaying
            }
            check(successor.title == "Q2.5 short 2")
            requireSystemSurface(SHORT_TWO)
            bringTargetForeground()
            val successorUi = awaitSnapshot("successor UI projection") {
                it.uiMediaId == SHORT_TWO && it.uiCurrentIndex == 1 && it.uiIsPlaying
            }
            results["B8"] = successorUi.summary()

            // B9 — final ended state remains coherent and the first external Play restarts it.
            val ended = call("prepare-ended").asSnapshot()
            check(ended.mediaId == SHORT_TWO)
            check(ended.playbackState == STATE_ENDED)
            check(!ended.isPlaying)
            check(!ended.uiPrimaryControlShowsPause)
            requireSingleSession(SHORT_TWO)
            val restarted = awaitAfterCommand("external-play", "system ended restart") {
                it.mediaId == SHORT_TWO &&
                    it.playbackState == STATE_READY &&
                    it.isPlaying &&
                    it.positionMs < ITEM_START_TOLERANCE_MS
            }
            results["B9"] = "ended=${ended.summary()} restarted=${restarted.summary()}"

            // B10 — one real transient focus loss remains coherent to a system controller.
            bringTargetForeground()
            call("prepare-long")
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val suppressed = awaitSnapshot("system controller focus suppression") {
                it.mediaId == LONG_TWO &&
                    it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            check(suppressed.uiPrimaryControlShowsPause)
            val focusPaused = call("external-pause").asSnapshot()
            check(!focusPaused.playWhenReady && !focusPaused.isPlaying)
            abandonAggressor()
            val remainsPaused = awaitSnapshot("system Pause cancels focus resume") {
                !it.playWhenReady && !it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            results["B10"] = "suppressed=${suppressed.summary()} paused=${remainsPaused.summary()}"

            // B11 — dismissing the task does not end intentional playback.
            bringTargetForeground()
            baseline = call("prepare-long").asSnapshot()
            dismissTargetTask()
            val afterPlayingDismissal = requireAdvance(baseline)
            requireActive(afterPlayingDismissal, LONG_TWO)
            requireSystemSurface(LONG_TWO)
            bringTargetForeground()
            val reopened = awaitSnapshot("active task-removal projection") {
                it.uiMediaId == LONG_TWO && it.uiIsPlaying
            }
            results["B11"] = reopened.summary()

            // B12 — paused task dismissal preserves coherent paused session state.
            val beforePausedDismissal = call("external-pause").asSnapshot()
            requireStable(beforePausedDismissal)
            dismissTargetTask()
            val afterPausedDismissal = awaitSnapshot("paused task-removal state") {
                it.mediaId == LONG_TWO && !it.playWhenReady && !it.isPlaying
            }
            requireStable(afterPausedDismissal)
            requireSingleSession(LONG_TWO)
            requireNotification(
                LONG_TWO,
                expectedPrimaryAction = "Play",
                requiresForegroundService = false,
            )
            results["B12"] = afterPausedDismissal.summary()

            // B13 — genuine routed active-session media keys reach LibrePlayer.
            bringTargetForeground()
            call("prepare-long")
            device.pressHome()
            device.executeShellCommand("input keyevent KEYCODE_MEDIA_PAUSE")
            val keyPaused = awaitSnapshot("media-key Pause") { !it.playWhenReady && !it.isPlaying }
            device.executeShellCommand("input keyevent KEYCODE_MEDIA_PLAY")
            awaitSnapshot("media-key Play") { it.mediaId == LONG_TWO && it.isPlaying }
            device.executeShellCommand("input keyevent KEYCODE_MEDIA_NEXT")
            val keyNext = awaitSnapshot("media-key Next") {
                it.mediaId == LONG_THREE && it.currentIndex == 2 && it.isPlaying
            }
            device.executeShellCommand("input keyevent KEYCODE_MEDIA_PREVIOUS")
            val keyPrevious = awaitSnapshot("media-key Previous") {
                it.mediaId == LONG_TWO && it.currentIndex == 1 && it.isPlaying
            }
            results["B13"] = "pause=${keyPaused.summary()} next=${keyNext.summary()} " +
                "previous=${keyPrevious.summary()}"

            val final = snapshot()
            check(final.playerErrors == 0)
            check(final.sessionDisconnects == 0)
            results.forEach { (phase, result) ->
                Log.i(LOG_TAG, "$phase $result")
                println("Q2.5 $phase $result")
            }
        } finally {
            runCatching { device.unfreezeRotation() }
            runCatching { abandonAggressor() }
            runCatching { call("reset") }
        }
    }

    private fun bringTargetForeground() {
        val launchIntent = requireNotNull(
            instrumentation.targetContext.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        instrumentation.targetContext.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)) {
            "LibrePlayer did not reach the foreground"
        }
        device.waitForIdle()
    }

    private fun dismissTargetTask() {
        device.pressRecentApps()
        val card = awaitRecentsCard(present = true)
        device.executeShellCommand(
            "input swipe ${card.visibleCenter.x} ${card.visibleCenter.y} " +
                "${card.visibleCenter.x} 0 $RECENTS_SWIPE_DURATION_MS",
        )
        awaitRecentsCard(present = false)
        device.pressHome()
        SystemClock.sleep(TASK_REMOVAL_SETTLE_MS)
    }

    private fun awaitRecentsCard(present: Boolean): androidx.test.uiautomator.UiObject2 {
        val deadline = SystemClock.elapsedRealtime() + UI_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val card = device.findObjects(By.desc(APP_LABEL))
                .filter { it.visibleBounds.height() > device.displayHeight / 3 }
                .maxByOrNull { it.visibleBounds.height() }
            if (present && card != null) return card
            if (!present && card == null) return device.findObject(By.desc("Home"))
                ?: error("Recents closed before task dismissal could be observed")
            SystemClock.sleep(POLL_MS)
        }
        error(if (present) "LibrePlayer task was not visible in Recents" else "LibrePlayer task remained in Recents")
    }

    private fun requireSystemSurface(
        expectedMediaId: String,
        expectedPrimaryAction: String = "Pause",
    ) {
        requireSingleSession(expectedMediaId)
        val serviceDump = device.executeShellCommand("dumpsys activity services $TARGET_PACKAGE")
        check("PlaybackService" in serviceDump)
        check("isForeground=true" in serviceDump || "foregroundServiceType=mediaPlayback" in serviceDump)
        requireNotification(expectedMediaId, expectedPrimaryAction)
    }

    private fun requireSingleSession(expectedMediaId: String) {
        val dump = device.executeShellCommand("dumpsys media_session")
        val packageMarker = "package=$TARGET_PACKAGE"
        check(dump.windowed(packageMarker.length).count { it == packageMarker } == 1) {
            "Expected exactly one LibrePlayer MediaSession"
        }
        val packageIndex = dump.indexOf(packageMarker)
        check(packageIndex >= 0)
        val end = dump.indexOf("\n    package=", packageIndex + packageMarker.length)
            .takeIf { it >= 0 }
            ?: (packageIndex + SESSION_CONTEXT_CHARS).coerceAtMost(dump.length)
        val block = dump.substring(packageIndex, end)
        val expectedTitle = expectedTitle(expectedMediaId)
        check("description=$expectedTitle" in block) { "System MediaSession metadata mismatch" }
        check("error=null" in block)
    }

    private fun requireNotification(
        expectedMediaId: String,
        expectedPrimaryAction: String,
        requiresForegroundService: Boolean = true,
    ) {
        val deadline = SystemClock.elapsedRealtime() + NOTIFICATION_TIMEOUT_MS
        var latestKeys = emptyList<String>()
        var latest = ""
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = device.executeShellCommand("dumpsys notification --noredact")
                .substringAfter("Notification List:")
                .substringBefore("Notification listeners:")
            val primaryRecords = latest.lines().filter { line ->
                line.startsWith("    NotificationRecord(") && "pkg=$TARGET_PACKAGE " in line
            }
            latestKeys = primaryRecords.map { line ->
                requireNotNull(Regex("key=([^:]+): Notification").find(line)?.groupValues?.get(1))
            }.distinct()
            if (
                latestKeys.size == 1 &&
                (!requiresForegroundService || "FOREGROUND_SERVICE" in latest) &&
                "android.title=String (${expectedTitle(expectedMediaId)})" in latest &&
                "\"$expectedPrimaryAction\" ->" in latest
            ) {
                return
            }
            SystemClock.sleep(POLL_MS)
        }
        error(
            "Notification projection timed out: keys=$latestKeys " +
                "title=${expectedTitle(expectedMediaId)} action=$expectedPrimaryAction dumpLength=${latest.length}",
        )
    }

    private fun expectedTitle(mediaId: String): String = when (mediaId) {
        LONG_ONE -> "Q2.5 long 1"
        LONG_TWO -> "Q2.5 long 2"
        LONG_THREE -> "Q2.5 long 3"
        SHORT_TWO -> "Q2.5 short 2"
        else -> error("Unknown synthetic system-surface identity: $mediaId")
    }

    private fun targetPid(): String =
        device.executeShellCommand("pidof $TARGET_PACKAGE").trim().also {
            check(it.matches(Regex("\\d+"))) { "LibrePlayer process was missing" }
        }

    private fun requireAdvance(before: Snapshot): Snapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = snapshot()
        check(after.positionMs - before.positionMs >= ACTIVE_POSITION_MINIMUM_MS) {
            "Position did not advance: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requireStable(before: Snapshot): Snapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = snapshot()
        check(after.positionMs - before.positionMs <= STABLE_POSITION_TOLERANCE_MS) {
            "Position advanced while paused: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requireActive(snapshot: Snapshot, mediaId: String) {
        check(snapshot.mediaId == mediaId)
        check(snapshot.playbackState == STATE_READY)
        check(snapshot.playWhenReady && snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.connected && !snapshot.hasPlayerError)
        check(snapshot.commandPlayPause && snapshot.commandSeek)
        check(snapshot.commandNext && snapshot.commandPrevious)
        check(snapshot.commandRepeat && snapshot.commandShuffle)
    }

    private fun awaitAfterCommand(
        method: String,
        description: String,
        predicate: (Snapshot) -> Boolean,
    ): Snapshot {
        call(method)
        return awaitSnapshot(description, predicate)
    }

    private fun awaitAfterCommand(
        method: String,
        argument: String,
        description: String,
        predicate: (Snapshot) -> Boolean,
    ): Snapshot {
        call(method, argument)
        return awaitSnapshot(description, predicate)
    }

    private fun awaitSnapshot(description: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var latest = snapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = snapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.summary()}")
    }

    private fun snapshot(): Snapshot = call("snapshot").asSnapshot()

    private fun call(method: String, argument: String? = null): Bundle =
        requireNotNull(resolver.call(PROBE_URI, method, argument, null))

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
        check(requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Aggressor focus request was not granted: $this"
        }
    }

    private fun Bundle.asSnapshot(): Snapshot = Snapshot(
        mediaId = getString("mediaId"),
        title = getString("title"),
        currentIndex = getInt("currentIndex"),
        mediaIds = getStringArrayList("mediaIds").orEmpty(),
        positionMs = getLong("positionMs"),
        playbackState = getInt("playbackState"),
        playWhenReady = getBoolean("playWhenReady"),
        suppressionReason = getInt("suppressionReason"),
        isPlaying = getBoolean("isPlaying"),
        repeatMode = getInt("repeatMode"),
        shuffleEnabled = getBoolean("shuffleEnabled"),
        connected = getBoolean("connected"),
        hasPlayerError = getBoolean("hasPlayerError"),
        playerErrors = getInt("playerErrors"),
        sessionDisconnects = getInt("sessionDisconnects"),
        commandPlayPause = getBoolean("commandPlayPause"),
        commandSeek = getBoolean("commandSeek"),
        commandNext = getBoolean("commandNext"),
        commandPrevious = getBoolean("commandPrevious"),
        commandRepeat = getBoolean("commandRepeat"),
        commandShuffle = getBoolean("commandShuffle"),
        uiConnected = getBoolean("uiConnected"),
        uiMediaId = getString("uiMediaId"),
        uiCurrentIndex = getInt("uiCurrentIndex"),
        uiIsPlaying = getBoolean("uiIsPlaying"),
        uiPrimaryControlShowsPause = getBoolean("uiPrimaryControlShowsPause"),
    )

    private data class Snapshot(
        val mediaId: String?,
        val title: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
        val isPlaying: Boolean,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
        val connected: Boolean,
        val hasPlayerError: Boolean,
        val playerErrors: Int,
        val sessionDisconnects: Int,
        val commandPlayPause: Boolean,
        val commandSeek: Boolean,
        val commandNext: Boolean,
        val commandPrevious: Boolean,
        val commandRepeat: Boolean,
        val commandShuffle: Boolean,
        val uiConnected: Boolean,
        val uiMediaId: String?,
        val uiCurrentIndex: Int,
        val uiIsPlaying: Boolean,
        val uiPrimaryControlShowsPause: Boolean,
    ) {
        fun summary(): String =
            "id=$mediaId index=$currentIndex/${mediaIds.size} pos=$positionMs state=$playbackState " +
                "pwr=$playWhenReady suppression=$suppressionReason playing=$isPlaying " +
                "repeat=$repeatMode shuffle=$shuffleEnabled connected=$connected errors=$playerErrors " +
                "disconnects=$sessionDisconnects uiId=$uiMediaId uiIndex=$uiCurrentIndex " +
                "uiPlaying=$uiIsPlaying uiPause=$uiPrimaryControlShowsPause"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val AGGRESSOR_PACKAGE = "com.libreplayer.benchmark"
        const val APP_LABEL = "LibrePlayer"
        const val LOG_TAG = "LibrePlayerQ25"
        const val LONG_ONE = "q2.5:long:1"
        const val LONG_TWO = "q2.5:long:2"
        const val LONG_THREE = "q2.5:long:3"
        const val SHORT_TWO = "q2.5:short:2"
        val PROBE_URI: Uri = Uri.parse("content://com.libreplayer.system-control-probe")
        const val STATE_READY = 3
        const val STATE_ENDED = 4
        const val SUPPRESSION_NONE = 0
        const val SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS = 1
        const val POSITION_OBSERVATION_MS = 700L
        const val ACTIVE_POSITION_MINIMUM_MS = 350L
        const val STABLE_POSITION_TOLERANCE_MS = 200L
        const val ITEM_START_TOLERANCE_MS = 1_000L
        const val RECONNECT_POSITION_TOLERANCE_MS = 500L
        const val ACTIVITY_SETTLE_MS = 500L
        const val TASK_REMOVAL_SETTLE_MS = 700L
        const val POLL_MS = 30L
        const val STATE_TIMEOUT_MS = 10_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_TIMEOUT_MS = 8_000L
        const val RECENTS_SWIPE_DURATION_MS = 100
        const val SESSION_CONTEXT_CHARS = 8_000
    }
}

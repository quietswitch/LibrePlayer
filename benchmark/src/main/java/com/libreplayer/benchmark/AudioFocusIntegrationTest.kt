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
class AudioFocusIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun realPlatformAudioFocusInterruptionMatrix() {
        val results = linkedMapOf<String, String>()
        try {
            // F1 — baseline play.
            var baseline = startTarget()
            checkPlayingIdentity(baseline)
            check(baseline.audioUsage == AUDIO_USAGE_MEDIA)
            check(baseline.audioContentType == AUDIO_CONTENT_TYPE_MUSIC)
            check(baseline.uiPrimaryControlShowsPause)
            baseline = requirePositionAdvance(baseline)
            results["F1"] = baseline.summary()

            // F2 — transient loss and gain.
            startTarget()
            val transientGrant = requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
            check(transientGrant.requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED)
            val transientLoss = awaitTarget("transient focus suppression") {
                it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            checkStableIdentity(transientLoss)
            check(transientLoss.uiPlayWhenReady)
            check(!transientLoss.uiIsPlaying)
            check(transientLoss.uiSuppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS)
            check(transientLoss.uiPrimaryControlShowsPause)
            requirePositionStable(transientLoss)
            val persistedTransient = awaitPersisted("transient play intent") {
                it.getBoolean("playWhenReady")
            }
            check(persistedTransient.getStringArrayList("queueIds") == arrayListOf(FOCUS_MEDIA_ID))
            check(persistedTransient.getInt("currentIndex") == 0)
            abandonAggressor()
            val transientGain = awaitTarget("automatic resume after transient gain") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            requirePositionAdvance(transientGain)
            results["F2"] = "grant=$transientGrant loss=${transientLoss.summary()} gain=${transientGain.summary()}"

            // F3 — explicit Pause while transiently suppressed cancels automatic resume.
            startTarget()
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val beforePause = awaitTarget("suppression before user pause") {
                it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS &&
                    it.uiPrimaryControlShowsPause
            }
            val paused = targetCall("user-pause").asTargetSnapshot()
            check(!paused.playWhenReady && !paused.isPlaying)
            check(!paused.uiPrimaryControlShowsPause)
            abandonAggressor()
            val remainsPaused = awaitTarget("explicit pause retained after gain") {
                !it.playWhenReady && !it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            requirePositionStable(remainsPaused)
            val persistedPause = awaitPersisted("explicit pause") { !it.getBoolean("playWhenReady") }
            check(persistedPause.getStringArrayList("queueIds") == arrayListOf(FOCUS_MEDIA_ID))
            check(persistedPause.getInt("currentIndex") == 0)
            results["F3"] = "before=${beforePause.summary()} paused=${remainsPaused.summary()}"

            // F4 — transient-can-duck follows Media3's music policy without application suppression.
            startTarget()
            val duckGrant = requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
            duckGrant.requireGranted()
            val ducking = awaitTarget("MAY_DUCK continuation") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            requirePositionAdvance(ducking)
            abandonAggressor()
            val duckRestored = awaitTarget("MAY_DUCK abandon") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            results["F4"] = "grant=$duckGrant active=${ducking.summary()} restored=${duckRestored.summary()}"

            // F5 — permanent loss clears play intent and does not resume merely on abandon.
            startTarget()
            val permanentGrant = requestAggressor(AudioManager.AUDIOFOCUS_GAIN)
            permanentGrant.requireGranted()
            val permanentLoss = awaitTarget("permanent audio-focus loss") {
                !it.playWhenReady && !it.isPlaying
            }
            checkStableIdentity(permanentLoss)
            check(permanentLoss.lastPlayWhenReadyReason == PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS)
            val permanentStopped = requirePositionStable(permanentLoss)
            awaitPersisted("permanent focus loss") {
                !it.getBoolean("playWhenReady")
            }
            abandonAggressor()
            SystemClock.sleep(SETTLE_OBSERVATION_MS)
            val afterPermanentAbandon = targetSnapshot()
            check(!afterPermanentAbandon.playWhenReady && !afterPermanentAbandon.isPlaying)
            check(afterPermanentAbandon.positionMs - permanentStopped.positionMs <= STABLE_POSITION_TOLERANCE_MS)
            results["F5"] = "grant=$permanentGrant loss=${permanentLoss.summary()} after=${afterPermanentAbandon.summary()}"

            // F6 — a later explicit user Play reacquires focus and resumes coherently.
            bringTargetForeground()
            val explicitPlay = targetCall("user-play").asTargetSnapshot()
            checkPlayingIdentity(explicitPlay)
            requirePositionAdvance(explicitPlay)
            results["F6"] = explicitPlay.summary()

            // F7 — background service handles transient loss/gain without an Activity.
            startTarget()
            device.pressHome()
            SystemClock.sleep(UI_SETTLE_MS)
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val backgroundLoss = awaitTarget("background transient suppression") {
                it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            val serviceDump = device.executeShellCommand("dumpsys activity services $TARGET_PACKAGE")
            check("isForeground=true" in serviceDump || "foregroundServiceType=mediaPlayback" in serviceDump) {
                "PlaybackService was not foreground during transient suppression: $serviceDump"
            }
            abandonAggressor()
            val backgroundGain = awaitTarget("background automatic resume") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            bringTargetForeground()
            val foregroundProjection = targetSnapshot()
            check(foregroundProjection.uiPlayWhenReady)
            check(foregroundProjection.uiIsPlaying)
            check(foregroundProjection.uiPrimaryControlShowsPause)
            results["F7"] = "loss=${backgroundLoss.summary()} gain=${backgroundGain.summary()}"

            // F8 — observer controller can reconnect while the service remains interrupted.
            startTarget()
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val reconnectLoss = awaitTarget("transient suppression before reconnect") {
                it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
            }
            check(targetCall("release-controller").getBoolean("passed"))
            val reconnected = targetCall("reconnect").asTargetSnapshot()
            checkStableIdentity(reconnected)
            check(reconnected.playWhenReady && !reconnected.isPlaying)
            check(reconnected.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS)
            abandonAggressor()
            val reconnectGain = awaitTarget("resume after interrupted reconnect") {
                it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
            }
            results["F8"] = "loss=${reconnectLoss.summary()} reconnect=${reconnected.summary()} gain=${reconnectGain.summary()}"

            // F9 — two bounded transient cycles settle without a stuck state.
            startTarget()
            repeat(2) { cycle ->
                requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
                awaitTarget("rapid loss ${cycle + 1}") {
                    it.playWhenReady &&
                        !it.isPlaying &&
                        it.suppressionReason == SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS
                }
                abandonAggressor()
                awaitTarget("rapid gain ${cycle + 1}") {
                    it.playWhenReady && it.isPlaying && it.suppressionReason == SUPPRESSION_NONE
                }
            }
            val rapidFinal = targetSnapshot()
            checkPlayingIdentity(rapidFinal)
            check(rapidFinal.playerErrors == 0)
            check(rapidFinal.sessionDisconnects == 0)
            results["F9"] = rapidFinal.summary()

            results.forEach { (phase, result) ->
                Log.i(LOG_TAG, "$phase $result")
                println("Q2.4 $phase $result")
            }
        } finally {
            runCatching { abandonAggressor() }
            runCatching { targetCall("reset") }
        }
    }

    @Test
    fun focusedQ21ToQ23PlaybackRegression() {
        try {
            bringTargetForeground()
            val result = targetCall("q2-regression")
            check(result.getBoolean("passed"))
            Log.i(LOG_TAG, "Q2.1-Q2.3 focused regression $result")
        } finally {
            runCatching { targetCall("reset") }
        }
    }

    private fun startTarget(): TargetSnapshot {
        bringTargetForeground()
        return targetCall("start").asTargetSnapshot().also(::checkPlayingIdentity)
    }

    private fun bringTargetForeground() {
        val launchIntent = requireNotNull(
            instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        instrumentation.context.startActivity(launchIntent)
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS)) {
            "LibrePlayer did not reach the foreground"
        }
    }

    private fun requestAggressor(gain: Int): FocusAggressorRegistry.Snapshot {
        val token = UUID.randomUUID().toString()
        val intent = Intent(FocusAggressorActivity.ACTION_REQUEST)
            .setComponent(ComponentName(instrumentation.context, FocusAggressorActivity::class.java))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            .putExtra(FocusAggressorActivity.EXTRA_TOKEN, token)
            .putExtra(FocusAggressorActivity.EXTRA_GAIN, gain)
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(AGGRESSOR_PACKAGE).depth(0)), UI_TIMEOUT_MS)) {
            "Focus aggressor was not the top app"
        }
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
            val snapshot = FocusAggressorRegistry.snapshot
            if (snapshot.token == token && predicate(snapshot)) return snapshot
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for focus aggressor token=$token state=${FocusAggressorRegistry.snapshot}")
    }

    private fun awaitTarget(
        description: String,
        predicate: (TargetSnapshot) -> Boolean,
    ): TargetSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var latest = targetSnapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = targetSnapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.summary()}")
    }

    private fun awaitPersisted(description: String, predicate: (Bundle) -> Boolean): Bundle {
        val deadline = SystemClock.elapsedRealtime() + PERSISTENCE_TIMEOUT_MS
        var latest = targetCall("persisted")
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = targetCall("persisted")
            if (predicate(latest)) return latest
            SystemClock.sleep(PERSISTENCE_POLL_MS)
        }
        error("Timed out waiting for persisted $description: $latest")
    }

    private fun targetSnapshot(): TargetSnapshot = targetCall("snapshot").asTargetSnapshot()

    private fun targetCall(method: String): Bundle =
        requireNotNull(resolver.call(PROBE_URI, method, null, null))

    private fun checkPlayingIdentity(snapshot: TargetSnapshot) {
        checkStableIdentity(snapshot)
        check(snapshot.playWhenReady)
        check(snapshot.isPlaying)
        check(snapshot.playbackState == PLAYBACK_STATE_READY)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.uiPlayWhenReady)
        check(snapshot.uiIsPlaying)
        check(snapshot.uiPrimaryControlShowsPause)
    }

    private fun checkStableIdentity(snapshot: TargetSnapshot) {
        check(snapshot.mediaId == FOCUS_MEDIA_ID)
        check(snapshot.currentIndex == 0)
        check(snapshot.mediaItemCount == 1)
        check(snapshot.connected)
        check(!snapshot.hasPlayerError)
        check(snapshot.playerErrors == 0)
    }

    private fun requirePositionAdvance(before: TargetSnapshot): TargetSnapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = targetSnapshot()
        check(after.positionMs - before.positionMs >= ACTIVE_POSITION_MINIMUM_MS) {
            "Playing position did not advance: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requirePositionStable(before: TargetSnapshot): TargetSnapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = targetSnapshot()
        check(after.positionMs - before.positionMs <= STABLE_POSITION_TOLERANCE_MS) {
            "Suppressed position advanced materially: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun Bundle.asTargetSnapshot(): TargetSnapshot = TargetSnapshot(
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
        audioUsage = getInt("audioUsage"),
        audioContentType = getInt("audioContentType"),
        lastPlayWhenReadyReason = getInt("lastPlayWhenReadyReason"),
        playerErrors = getInt("playerErrors"),
        sessionDisconnects = getInt("sessionDisconnects"),
        uiPlayWhenReady = getBoolean("uiPlayWhenReady"),
        uiIsPlaying = getBoolean("uiIsPlaying"),
        uiSuppressionReason = getInt("uiSuppressionReason"),
        uiPrimaryControlShowsPause = getBoolean("uiPrimaryControlShowsPause"),
        playWhenReadyEvents = getString("playWhenReadyEvents").orEmpty(),
        suppressionEvents = getString("suppressionEvents").orEmpty(),
        isPlayingEvents = getString("isPlayingEvents").orEmpty(),
    )

    private fun FocusAggressorRegistry.Snapshot.requireGranted() {
        check(requestResult == AudioManager.AUDIOFOCUS_REQUEST_GRANTED) {
            "Aggressor focus request was not granted: $this"
        }
    }

    private data class TargetSnapshot(
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
        val audioUsage: Int,
        val audioContentType: Int,
        val lastPlayWhenReadyReason: Int,
        val playerErrors: Int,
        val sessionDisconnects: Int,
        val uiPlayWhenReady: Boolean,
        val uiIsPlaying: Boolean,
        val uiSuppressionReason: Int,
        val uiPrimaryControlShowsPause: Boolean,
        val playWhenReadyEvents: String,
        val suppressionEvents: String,
        val isPlayingEvents: String,
    ) {
        fun summary(): String =
            "id=$mediaId index=$currentIndex/$mediaItemCount pos=$positionMs " +
                "state=$playbackState pwr=$playWhenReady suppression=$suppressionReason " +
                "playing=$isPlaying pwrReason=$lastPlayWhenReadyReason uiPause=$uiPrimaryControlShowsPause " +
                "pwrEvents=[$playWhenReadyEvents] suppressionEvents=[$suppressionEvents] " +
                "playingEvents=[$isPlayingEvents]"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val AGGRESSOR_PACKAGE = "com.libreplayer.benchmark"
        const val LOG_TAG = "LibrePlayerQ24"
        const val FOCUS_MEDIA_ID = "q2.4:audio-focus"
        val PROBE_URI: Uri = Uri.parse("content://com.libreplayer.audio-focus-probe")

        // Media3 Player constants as installed for the 1.9.2 authority target.
        const val PLAYBACK_STATE_READY = 3
        const val SUPPRESSION_NONE = 0
        const val SUPPRESSION_TRANSIENT_AUDIO_FOCUS_LOSS = 1
        const val PLAY_WHEN_READY_REASON_AUDIO_FOCUS_LOSS = 2
        const val AUDIO_USAGE_MEDIA = 1
        const val AUDIO_CONTENT_TYPE_MUSIC = 2

        const val POSITION_OBSERVATION_MS = 700L
        const val ACTIVE_POSITION_MINIMUM_MS = 350L
        const val STABLE_POSITION_TOLERANCE_MS = 200L
        const val SETTLE_OBSERVATION_MS = 800L
        const val UI_SETTLE_MS = 300L
        const val POLL_MS = 25L
        const val STATE_TIMEOUT_MS = 8_000L
        const val PERSISTENCE_TIMEOUT_MS = 7_000L
        const val PERSISTENCE_POLL_MS = 100L
        const val UI_TIMEOUT_MS = 10_000L
    }
}

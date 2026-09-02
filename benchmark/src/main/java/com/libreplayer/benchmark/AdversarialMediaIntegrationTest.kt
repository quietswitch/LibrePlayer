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

/** Bounded Q2.7 authority over real local-source Media3 failures and recovery. */
@RunWith(AndroidJUnit4::class)
class AdversarialMediaIntegrationTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val resolver = instrumentation.context.contentResolver
    private val device = UiDevice.getInstance(instrumentation)

    @Test
    fun playbackMasteryCloseAuthority() {
        val results = linkedMapOf<String, String>()
        try {
            bringTargetForeground()
            val initialLibraryCount = libraryCount()
            val targetPid = targetPid()

            var baseline = prepare("q2.8-churn")
            requireActive(baseline, Q29_LONG_QUEUE[0], 0)
            check(baseline.mediaIds == Q29_LONG_QUEUE)
            requireSingleSession(Q29_LONG_QUEUE[0], "Q2.8 churn 1")
            baseline = requireAdvance(baseline)
            results["QUEUE"] = baseline.summary()

            val sought = awaitAfter("seek", "19000", "ordinary closeout seek") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    it.currentIndex == 0 &&
                    it.positionMs in 18_700L..19_700L &&
                    it.isPlaying
            }
            val automaticB = await("automatic closeout A to B") {
                it.mediaId == Q29_LONG_QUEUE[1] &&
                    it.currentIndex == 1 &&
                    it.positionMs < ITEM_START_TOLERANCE_MS &&
                    it.isPlaying &&
                    !it.hasPlayerError
            }
            check(automaticB.transitionCount > sought.transitionCount)
            check(automaticB.mediaIds == Q29_LONG_QUEUE)
            results["SEEK-TRANSITION"] = "seek=${sought.summary()} auto=${automaticB.summary()}"

            val previous = awaitAfter("system-previous", description = "system Previous below threshold") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    it.currentIndex == 0 &&
                    it.positionMs < ITEM_START_TOLERANCE_MS &&
                    it.isPlaying
            }
            requireActive(previous, Q29_LONG_QUEUE[0], 0)
            check(previous.mediaIds == Q29_LONG_QUEUE)
            results["PREVIOUS"] = previous.summary()

            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val suppressed = await("transient focus suppression") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_FOCUS
            }
            val focusPaused = awaitAfter("system-pause", description = "Pause while focus suppressed") {
                it.mediaId == Q29_LONG_QUEUE[0] && !it.playWhenReady && !it.isPlaying
            }
            abandonAggressor()
            val remainsPaused = await("focus abandon does not resume cancelled intent") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    !it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE
            }
            val focusResumed = awaitAfter("system-play", description = "explicit Play after focus") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    it.playWhenReady &&
                    it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE
            }
            results["FOCUS"] =
                "loss=${suppressed.summary()} pause=${focusPaused.summary()} " +
                    "abandon=${remainsPaused.summary()} play=${focusResumed.summary()}"

            sendNoisyBroadcast()
            val noisy = await("becoming-noisy safety pause") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    !it.playWhenReady &&
                    !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE
            }
            val postNoisyPlay = awaitAfter("system-play", description = "explicit Play after noisy") {
                it.mediaId == Q29_LONG_QUEUE[0] &&
                    it.playWhenReady &&
                    it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE
            }
            results["NOISY"] = "pause=${noisy.summary()} play=${postNoisyPlay.summary()}"

            device.pressHome()
            val backgroundNext = awaitAfter("system-next", description = "background system Next") {
                it.mediaId == Q29_LONG_QUEUE[1] &&
                    it.currentIndex == 1 &&
                    it.isPlaying &&
                    !it.hasPlayerError
            }
            requireSingleSession(Q29_LONG_QUEUE[1], "Q2.8 churn 2")
            check(call("release-controller").getBoolean("passed"))
            call("reconnect")
            val reconnected = await("controller reconnect") {
                it.connected &&
                    it.mediaId == Q29_LONG_QUEUE[1] &&
                    it.currentIndex == 1 &&
                    it.isPlaying
            }
            check(reconnected.mediaIds == Q29_LONG_QUEUE)
            check(reconnected.sessionDisconnects == 0)
            results["BACKGROUND-SESSION"] =
                "next=${backgroundNext.summary()} reconnect=${reconnected.summary()}"

            val failure = prepareFailure(
                "recovery-transition",
                MISSING,
                0,
                ERROR_IO_FILE_NOT_FOUND,
            )
            requireFailure(failure, MISSING, 0, ERROR_IO_FILE_NOT_FOUND)
            requireStableFailure(failure)
            requireSingleSession(MISSING, MISSING_TITLE)
            val recoveredA = awaitAfter("system-next", description = "one-action error recovery") {
                it.mediaId == GOOD_A &&
                    it.currentIndex == 1 &&
                    it.isPlaying &&
                    !it.hasPlayerError
            }
            val healthySuccessor = await("healthy automatic transition after recovery") {
                it.mediaId == GOOD_C &&
                    it.currentIndex == 2 &&
                    it.isPlaying &&
                    !it.hasPlayerError &&
                    it.uiErrorMessage == null
            }
            check(healthySuccessor.errorCount == recoveredA.errorCount)
            results["ERROR-RECOVERY"] =
                "failure=${failure.summary()} recovered=${recoveredA.summary()} " +
                    "successor=${healthySuccessor.summary()}"

            val finalPaused = awaitAfter("system-pause", description = "final ordinary Pause") {
                it.mediaId == GOOD_C && !it.playWhenReady && !it.isPlaying && !it.hasPlayerError
            }
            val finalPlay = awaitAfter("system-play", description = "final ordinary Play") {
                it.mediaId == GOOD_C &&
                    it.currentIndex == 2 &&
                    it.playWhenReady &&
                    it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE &&
                    !it.hasPlayerError
            }
            val finalState = requireAdvance(finalPlay)
            requireActive(finalState, GOOD_C, 2)
            check(finalState.mediaIds == listOf(MISSING, GOOD_A, GOOD_C))
            check(finalState.sessionDisconnects == 0)
            check(targetPid() == targetPid)
            check(libraryCount() == initialLibraryCount)
            requireSingleSession(GOOD_C, GOOD_C_TITLE)
            results["FINAL"] =
                "pause=${finalPaused.summary()} play=${finalPlay.summary()} final=${finalState.summary()}"

            results.forEach { (phase, result) ->
                Log.i(Q29_LOG_TAG, "$phase $result")
                println("Q2.9 $phase $result")
            }
        } finally {
            runCatching { abandonAggressor() }
            runCatching { call("reset") }
        }
    }

    @Test
    fun adversarialMediaAndFailureRecoveryAuthority() {
        val results = linkedMapOf<String, String>()
        try {
            bringTargetForeground()
            val fixtureManifest = call("fixtures").getStringArrayList("fixtureManifest").orEmpty()
            check(fixtureManifest == EXPECTED_FIXTURE_MANIFEST)
            val initialLibraryCount = libraryCount()
            results["FIXTURES"] = fixtureManifest.joinToString()

            // E1 — valid baseline remains ordinary playback.
            var baseline = prepare("baseline")
            requireActive(baseline, GOOD_A, 0)
            baseline = requireAdvance(baseline)
            requireSingleSession(GOOD_A, GOOD_A_TITLE)
            results["E1"] = baseline.summary()

            // E2/E3 — missing source fails safely and one UI Next recovers to valid C.
            val missing = prepareFailure("missing", MISSING, 1, ERROR_IO_FILE_NOT_FOUND)
            requireFailure(missing, MISSING, 1, ERROR_IO_FILE_NOT_FOUND)
            check(missing.uiErrorMessage?.contains("no longer available") == true)
            requireStableFailure(missing)
            val missingRecovered = awaitAfter("ui-next", description = "UI Next after missing") {
                it.mediaId == GOOD_C && it.currentIndex == 2 && it.isPlaying && !it.hasPlayerError
            }
            requireActive(missingRecovered, GOOD_C, 2)
            check(missingRecovered.uiErrorMessage == null)
            results["E2-E3"] = "failed=${missing.summary()} recovered=${missingRecovered.summary()}"

            // E4 — zero bytes reaches the real unsupported-container path and system Next recovers.
            val zero = prepareFailure("zero", ZERO, 1, ERROR_PARSING_CONTAINER_UNSUPPORTED)
            requireFailure(zero, ZERO, 1, ERROR_PARSING_CONTAINER_UNSUPPORTED)
            val zeroRecovered = awaitAfter("system-next", description = "system Next after zero") {
                it.mediaId == GOOD_C && it.isPlaying && !it.hasPlayerError
            }
            results["E4"] = "failed=${zero.summary()} recovered=${zeroRecovered.summary()}"

            // E5 — deterministic garbage fails through the extractor and direct selection recovers.
            val garbage = prepareFailure("garbage", GARBAGE, 1, ERROR_PARSING_CONTAINER_UNSUPPORTED)
            requireFailure(garbage, GARBAGE, 1, ERROR_PARSING_CONTAINER_UNSUPPORTED)
            val garbageRecovered = awaitAfter("select", "2", "valid selection after garbage") {
                it.mediaId == GOOD_C && it.isPlaying && !it.hasPlayerError
            }
            results["E5"] = "failed=${garbage.summary()} recovered=${garbageRecovered.summary()}"

            // E6 — deterministic FLAC truncation fails with EOF-backed I/O and Previous recovers.
            val truncated = prepareFailure("truncated", TRUNCATED, 1, ERROR_IO_UNSPECIFIED)
            requireFailure(truncated, TRUNCATED, 1, ERROR_IO_UNSPECIFIED)
            check("java.io.EOFException" in truncated.failureEvents)
            val truncatedRecovered = awaitAfter("ui-previous", description = "Previous after truncation") {
                it.mediaId == GOOD_A && it.currentIndex == 0 && it.isPlaying && !it.hasPlayerError
            }
            results["E6"] = "failed=${truncated.summary()} recovered=${truncatedRecovered.summary()}"

            // E7 — natural valid→bad stops on B; it does not auto-skip to C.
            bringTargetForeground()
            prepare("bad-successor")
            val badSuccessor = await("natural bad successor") {
                it.mediaId == MISSING && it.currentIndex == 1 && it.hasPlayerError &&
                    !it.playWhenReady && !it.isPlaying
            }
            requireFailure(badSuccessor, MISSING, 1, ERROR_IO_FILE_NOT_FOUND)
            check(badSuccessor.uiErrorMessage?.contains("no longer available") == true)
            requireStableFailure(badSuccessor)
            val successorRecovered = awaitAfter("system-next", description = "system recovery to C") {
                it.mediaId == GOOD_C && it.currentIndex == 2 && it.isPlaying && !it.hasPlayerError
            }
            results["E7"] = "failed=${badSuccessor.summary()} recovered=${successorRecovered.summary()}"

            // E8 — consecutive and all-invalid queues advance only once per explicit command.
            bringTargetForeground()
            prepare("consecutive")
            val firstBad = await("first consecutive bad item") {
                it.mediaId == ZERO && it.currentIndex == 1 && it.errorCount == 1 &&
                    !it.playWhenReady && !it.isPlaying
            }
            requireStableFailure(firstBad)
            call("system-next")
            val secondBad = await("second consecutive bad item") {
                it.mediaId == GARBAGE && it.currentIndex == 2 && it.errorCount == 2 &&
                    !it.playWhenReady && !it.isPlaying
            }
            requireStableFailure(secondBad)
            val goodAfterConsecutive = awaitAfter("system-next", description = "good after consecutive bad") {
                it.mediaId == GOOD_LONG && it.currentIndex == 3 && it.isPlaying && !it.hasPlayerError
            }
            bringTargetForeground()
            val allBadFirst = prepareFailure("all-bad", MISSING, 0, ERROR_IO_FILE_NOT_FOUND)
            requireFailure(allBadFirst, MISSING, 0, ERROR_IO_FILE_NOT_FOUND)
            call("system-next")
            await("all-bad zero") {
                it.mediaId == ZERO && it.currentIndex == 1 && it.errorCount == 2 && !it.playWhenReady
            }
            call("system-next")
            val allBadFinal = await("all-bad garbage") {
                it.mediaId == GARBAGE && it.currentIndex == 2 && it.errorCount == 3 && !it.playWhenReady
            }
            call("system-next")
            val terminal = requireStableFailure(allBadFinal)
            check(terminal.currentIndex == 2 && terminal.errorCount == 3)
            results["E8"] = "consecutive=${goodAfterConsecutive.summary()} allBad=${terminal.summary()}"

            // E9/E11 — a background failure retains notification/session and external recovery.
            bringTargetForeground()
            prepare("bad-successor")
            device.pressHome()
            val backgroundFailure = await("background bad successor") {
                it.mediaId == MISSING && it.hasPlayerError && !it.playWhenReady && !it.isPlaying
            }
            requireNotification(MISSING_TITLE, "Play")
            requireSingleSession(MISSING, MISSING_TITLE)
            val backgroundRecovered = awaitAfter("system-next", description = "background system Next") {
                it.mediaId == GOOD_C && it.isPlaying && !it.hasPlayerError
            }
            requireNotification(GOOD_C_TITLE, "Pause")
            bringTargetForeground()
            val reopened = await("reopened recovered UI") {
                it.uiMediaId == GOOD_C && it.uiIsPlaying && it.uiErrorMessage == null
            }
            results["E9-E11"] = "failed=${backgroundFailure.summary()} reopened=${reopened.summary()}"

            // E10 — reconnecting a controller preserves the singular errored session.
            val reconnectFailure = prepareFailure("missing", MISSING, 1, ERROR_IO_FILE_NOT_FOUND)
            check(call("release-controller").getBoolean("passed"))
            call("reconnect")
            val reconnected = await("controller reconnect after error") {
                it.connected && it.mediaId == MISSING && it.currentIndex == 1 && it.hasPlayerError
            }
            check(reconnected.mediaIds == reconnectFailure.mediaIds)
            check(reconnected.sessionDisconnects == 0)
            requireSingleSession(MISSING, MISSING_TITLE)
            results["E10"] = reconnected.summary()

            // E12 — after one recovery, normal A→C automatic transition works again.
            prepareFailure("recovery-transition", MISSING, 0, ERROR_IO_FILE_NOT_FOUND)
            val recoveredA = awaitAfter("system-next", description = "recover to short A") {
                it.mediaId == GOOD_A && it.currentIndex == 1 && it.isPlaying && !it.hasPlayerError
            }
            val normalSuccessor = await("normal transition after recovery") {
                it.mediaId == GOOD_C && it.currentIndex == 2 && it.isPlaying && !it.hasPlayerError
            }
            check(normalSuccessor.errorCount == recoveredA.errorCount)
            results["E12"] = normalSuccessor.summary()

            // E13/E14 — seek and transient focus semantics remain normal after recovery.
            prepareFailure("long-recovery", MISSING, 0, ERROR_IO_FILE_NOT_FOUND)
            val recoveredLong = awaitAfter("system-next", description = "recover to long valid item") {
                it.mediaId == GOOD_LONG && it.currentIndex == 1 && it.isPlaying && !it.hasPlayerError
            }
            call("seek", "7000")
            val sought = await("seek after recovery") {
                it.mediaId == GOOD_LONG && it.positionMs in 6_700L..8_600L && it.isPlaying
            }
            requestAggressor(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT).requireGranted()
            val focusLoss = await("focus loss after recovery") {
                it.mediaId == GOOD_LONG && it.playWhenReady && !it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_TRANSIENT_FOCUS
            }
            abandonAggressor()
            val focusGain = await("focus gain after recovery") {
                it.mediaId == GOOD_LONG && it.playWhenReady && it.isPlaying &&
                    it.suppressionReason == SUPPRESSION_NONE
            }
            results["E13-E14"] =
                "recovered=${recoveredLong.summary()} seek=${sought.summary()} " +
                    "loss=${focusLoss.summary()} gain=${focusGain.summary()}"

            // File deletion cases use only regenerated cache fixtures.
            bringTargetForeground()
            prepare("delete-successor")
            check(call("delete", DELETABLE_FILE).getBoolean("passed"))
            val deletedSuccessor = await("pre-opened deleted successor") {
                it.mediaId == DELETABLE && it.currentIndex == 1 && it.isPlaying && !it.hasPlayerError
            }
            bringTargetForeground()
            var deletedCurrent = prepare("delete-current")
            requireActive(deletedCurrent, DELETABLE, 0)
            check(call("delete", DELETABLE_FILE).getBoolean("passed"))
            deletedCurrent = requireAdvance(deletedCurrent)
            check(!deletedCurrent.hasPlayerError)
            results["DELETE"] =
                "successor=${deletedSuccessor.summary()} current=${deletedCurrent.summary()}"

            // Persistence records a real stop, not a retry intent or exception details.
            prepareFailure("missing", MISSING, 1, ERROR_IO_FILE_NOT_FOUND)
            val persisted = awaitPersisted {
                it.getStringArrayList("queueIds") == arrayListOf(GOOD_A, MISSING, GOOD_C) &&
                    it.getInt("currentIndex") == 1 && !it.getBoolean("playWhenReady")
            }
            results["PERSISTENCE"] =
                "index=${persisted.getInt("currentIndex")} pos=${persisted.getLong("positionMs")} " +
                    "pwr=${persisted.getBoolean("playWhenReady")}"

            // Focused Q2.1 transport and Q2.6 noisy semantics after valid recovery.
            baseline = prepare("baseline")
            check(!call("ui-play").asSnapshot().playWhenReady)
            val resumed = awaitAfter("ui-play", description = "ordinary resumed Play") {
                it.mediaId == GOOD_A && it.isPlaying
            }
            val ordinaryNext = awaitAfter("system-next", description = "ordinary Next") {
                it.mediaId == GOOD_C && it.currentIndex == 1 && it.isPlaying
            }
            val ordinaryPrevious = awaitAfter("system-previous", description = "ordinary Previous") {
                it.mediaId == GOOD_A && it.currentIndex == 0 && it.isPlaying
            }
            sendNoisyBroadcast()
            val noisy = await("noisy after normal recovery") {
                it.mediaId == GOOD_A && !it.playWhenReady && !it.isPlaying && !it.hasPlayerError
            }
            val postNoisyPlay = awaitAfter("system-play", description = "Play after noisy") {
                it.mediaId == GOOD_A && it.isPlaying && !it.hasPlayerError
            }
            results["Q2.1-Q2.6"] =
                "resume=${resumed.summary()} next=${ordinaryNext.summary()} " +
                    "previous=${ordinaryPrevious.summary()} noisy=${noisy.summary()} " +
                    "play=${postNoisyPlay.summary()}"

            check(libraryCount() == initialLibraryCount)
            check(snapshot().sessionDisconnects == 0)
            results.forEach { (phase, result) ->
                Log.i(LOG_TAG, "$phase $result")
                println("Q2.7 $phase $result")
            }
        } finally {
            runCatching { abandonAggressor() }
            runCatching { call("reset") }
        }
    }

    private fun prepare(scenario: String): Snapshot = call("prepare", scenario).asSnapshot()

    private fun prepareFailure(
        scenario: String,
        mediaId: String,
        index: Int,
        errorCode: Int,
    ): Snapshot {
        prepare(scenario)
        return await("settled $scenario failure") {
            it.mediaId == mediaId && it.currentIndex == index &&
                it.playerErrorCode == errorCode && it.playbackState == STATE_IDLE &&
                !it.playWhenReady && !it.isPlaying
        }
    }

    private fun snapshot(): Snapshot = call("snapshot").asSnapshot()

    private fun call(method: String, argument: String? = null): Bundle =
        requireNotNull(resolver.call(PROBE_URI, method, argument, null))

    private fun awaitAfter(
        method: String,
        argument: String? = null,
        description: String,
        predicate: (Snapshot) -> Boolean,
    ): Snapshot {
        call(method, argument)
        return await(description, predicate)
    }

    private fun await(description: String, predicate: (Snapshot) -> Boolean): Snapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        var latest = snapshot()
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = snapshot()
            if (predicate(latest)) return latest
            SystemClock.sleep(POLL_MS)
        }
        error("Timed out waiting for $description: ${latest.summary()}")
    }

    private fun awaitPersisted(predicate: (Bundle) -> Boolean): Bundle {
        val deadline = SystemClock.elapsedRealtime() + PERSISTENCE_TIMEOUT_MS
        var latest = call("persisted")
        while (SystemClock.elapsedRealtime() < deadline) {
            latest = call("persisted")
            if (predicate(latest)) return latest
            SystemClock.sleep(PERSISTENCE_POLL_MS)
        }
        error("Timed out waiting for persisted failure state: $latest")
    }

    private fun requireActive(snapshot: Snapshot, mediaId: String, index: Int) {
        check(snapshot.mediaId == mediaId && snapshot.currentIndex == index)
        check(snapshot.playbackState == STATE_READY)
        check(snapshot.playWhenReady && snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.connected && !snapshot.hasPlayerError)
        check(snapshot.uiErrorMessage == null)
    }

    private fun requireFailure(snapshot: Snapshot, mediaId: String, index: Int, errorCode: Int) {
        check(snapshot.mediaId == mediaId && snapshot.currentIndex == index)
        check(snapshot.playbackState == STATE_IDLE)
        check(!snapshot.playWhenReady && !snapshot.isPlaying)
        check(snapshot.suppressionReason == SUPPRESSION_NONE)
        check(snapshot.connected && snapshot.playerErrorCode == errorCode)
        check(snapshot.errorCount >= 1 && snapshot.sessionDisconnects == 0)
    }

    private fun requireAdvance(before: Snapshot): Snapshot {
        SystemClock.sleep(POSITION_OBSERVATION_MS)
        val after = snapshot()
        check(after.mediaId == before.mediaId && after.currentIndex == before.currentIndex)
        check(after.positionMs - before.positionMs >= ACTIVE_POSITION_MINIMUM_MS) {
            "Position did not advance: before=${before.summary()} after=${after.summary()}"
        }
        return after
    }

    private fun requireStableFailure(before: Snapshot): Snapshot {
        SystemClock.sleep(FAILURE_STABILITY_MS)
        val after = snapshot()
        check(after.mediaId == before.mediaId && after.currentIndex == before.currentIndex)
        check(after.errorCount == before.errorCount)
        check(after.transitionCount == before.transitionCount)
        check(after.positionMs - before.positionMs <= FAILURE_POSITION_TOLERANCE_MS)
        check(after.playbackState == STATE_IDLE && !after.playWhenReady && !after.isPlaying)
        return after
    }

    private fun libraryCount(): Int = call("library-summary").getInt("songCount")

    private fun targetPid(): String =
        device.executeShellCommand("pidof $TARGET_PACKAGE").trim().also {
            check(it.matches(Regex("\\d+"))) { "LibrePlayer process was missing" }
        }

    private fun bringTargetForeground() {
        val intent = requireNotNull(
            instrumentation.context.packageManager.getLaunchIntentForPackage(TARGET_PACKAGE),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        instrumentation.context.startActivity(intent)
        check(device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), UI_TIMEOUT_MS))
        device.waitForIdle()
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
    }

    private fun requireNotification(expectedTitle: String, expectedAction: String) {
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
                "\"$expectedAction\" ->" in dump
            ) return
            SystemClock.sleep(POLL_MS)
        }
        error("Notification did not project title=$expectedTitle action=$expectedAction")
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

    private fun sendNoisyBroadcast() {
        val output = device.executeShellCommand(
            "su 0 am broadcast --user current -a android.media.AUDIO_BECOMING_NOISY",
        )
        check("Broadcast completed" in output) { "Root noisy broadcast failed: $output" }
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
        failureEvents = getString("failureEvents").orEmpty(),
        transitionCount = getInt("transitionCount"),
        sessionDisconnects = getInt("sessionDisconnects"),
        uiMediaId = getString("uiMediaId"),
        uiPlayWhenReady = getBoolean("uiPlayWhenReady"),
        uiIsPlaying = getBoolean("uiIsPlaying"),
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
        val failureEvents: String,
        val transitionCount: Int,
        val sessionDisconnects: Int,
        val uiMediaId: String?,
        val uiPlayWhenReady: Boolean,
        val uiIsPlaying: Boolean,
        val uiErrorMessage: String?,
    ) {
        val hasPlayerError: Boolean get() = playerErrorCode != NO_ERROR

        fun summary(): String =
            "id=$mediaId index=$currentIndex/${mediaIds.size} pos=$positionMs state=$playbackState " +
                "pwr=$playWhenReady suppression=$suppressionReason playing=$isPlaying " +
                "error=$playerErrorCode errors=$errorCount transitions=$transitionCount " +
                "connected=$connected disconnects=$sessionDisconnects uiId=$uiMediaId " +
                "uiPwr=$uiPlayWhenReady uiPlaying=$uiIsPlaying uiError=$uiErrorMessage"
    }

    private companion object {
        const val TARGET_PACKAGE = "com.libreplayer"
        const val AGGRESSOR_PACKAGE = "com.libreplayer.benchmark"
        const val LOG_TAG = "LibrePlayerQ27"
        const val Q29_LOG_TAG = "LibrePlayerQ29"
        const val GOOD_A = "q2.7:good:a"
        const val GOOD_C = "q2.7:good:c"
        const val GOOD_LONG = "q2.7:good:long"
        const val MISSING = "q2.7:bad:missing"
        const val ZERO = "q2.7:bad:zero"
        const val GARBAGE = "q2.7:bad:garbage"
        const val TRUNCATED = "q2.7:bad:truncated-flac"
        const val DELETABLE = "q2.7:deletable"
        const val DELETABLE_FILE = "deletable.flac"
        const val GOOD_A_TITLE = "Q2.7 good A"
        const val GOOD_C_TITLE = "Q2.7 good C"
        const val MISSING_TITLE = "Q2.7 missing"
        const val ERROR_IO_UNSPECIFIED = 2_000
        const val ERROR_IO_FILE_NOT_FOUND = 2_005
        const val ERROR_PARSING_CONTAINER_UNSUPPORTED = 3_003
        const val NO_ERROR = Int.MIN_VALUE
        const val STATE_IDLE = 1
        const val STATE_READY = 3
        const val SUPPRESSION_NONE = 0
        const val SUPPRESSION_TRANSIENT_FOCUS = 1
        const val POSITION_OBSERVATION_MS = 700L
        const val ACTIVE_POSITION_MINIMUM_MS = 350L
        const val ITEM_START_TOLERANCE_MS = 1_000L
        const val FAILURE_STABILITY_MS = 500L
        const val FAILURE_POSITION_TOLERANCE_MS = 100L
        const val POLL_MS = 30L
        const val PERSISTENCE_POLL_MS = 50L
        const val STATE_TIMEOUT_MS = 10_000L
        const val PERSISTENCE_TIMEOUT_MS = 8_000L
        const val UI_TIMEOUT_MS = 10_000L
        const val NOTIFICATION_TIMEOUT_MS = 8_000L
        const val SESSION_CONTEXT_CHARS = 8_000
        val PROBE_URI: Uri = Uri.parse("content://com.libreplayer.adversarial-media-probe")
        val Q29_LONG_QUEUE = listOf(
            "q2.8:churn:0",
            "q2.8:churn:1",
            "q2.8:churn:2",
            "q2.8:churn:3",
        )
        val EXPECTED_FIXTURE_MANIFEST = arrayListOf(
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

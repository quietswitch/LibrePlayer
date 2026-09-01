package com.libreplayer.debug

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import java.io.File
import java.security.MessageDigest
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Debug-only Q2.7 real-source fixture, observation, and command authority. */
@UnstableApi
class AdversarialMediaProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private var initialConnectionSettled = false
    private var deliberateControllerRelease = false
    private val events = FailureEvents()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            val controller = observer
            events.failures += FailureEvent(
                elapsedMs = SystemClock.elapsedRealtime(),
                errorCode = error.errorCode,
                errorCodeName = PlaybackException.getErrorCodeName(error.errorCode),
                causeClass = error.cause?.javaClass?.name,
                mediaId = controller?.currentMediaItem?.mediaId,
                index = controller?.currentMediaItemIndex ?: -1,
                positionMs = controller?.currentPosition?.coerceAtLeast(0L) ?: 0L,
                playbackState = controller?.playbackState ?: Player.STATE_IDLE,
                playWhenReady = controller?.playWhenReady ?: false,
                suppressionReason = controller?.playbackSuppressionReason
                    ?: Player.PLAYBACK_SUPPRESSION_REASON_NONE,
            )
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events.transitions += "${mediaItem?.mediaId}:${observer?.currentMediaItemIndex}:$reason"
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (!deliberateControllerRelease) events.sessionDisconnects.incrementAndGet()
            events.disconnected.set(true)
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                METHOD_FIXTURES -> fixtureBundle(generateFixtures())
                METHOD_PREPARE -> prepareScenario(application, requireNotNull(arg))
                METHOD_SNAPSHOT -> snapshotBundle(application, connectedObserver())
                METHOD_UI_NEXT -> command(application) {
                    application.appContainer.playbackConnection.skipNext()
                }
                METHOD_UI_PREVIOUS -> command(application) {
                    application.appContainer.playbackConnection.skipPrevious()
                }
                METHOD_UI_PLAY -> command(application) {
                    application.appContainer.playbackConnection.togglePlayPause()
                }
                METHOD_SELECT -> command(application) {
                    application.appContainer.playbackConnection.selectQueueItem(requireNotNull(arg).toInt())
                }
                METHOD_SYSTEM_NEXT -> controllerCommand(application) { seekToNext() }
                METHOD_SYSTEM_PREVIOUS -> controllerCommand(application) { seekToPrevious() }
                METHOD_SYSTEM_PLAY -> controllerCommand(application) { play() }
                METHOD_SEEK -> controllerCommand(application) { seekTo(requireNotNull(arg).toLong()) }
                METHOD_DELETE -> deleteFixture(requireNotNull(arg))
                METHOD_PERSISTED -> persistedBundle(application)
                METHOD_LIBRARY_SUMMARY -> librarySummary(application)
                METHOD_RELEASE_CONTROLLER -> releaseObserver()
                METHOD_RECONNECT -> snapshotBundle(application, connectedObserver())
                METHOD_RESET -> resetPlayback()
                else -> error("Unsupported adversarial-media probe method: $method")
            }
        }

    private suspend fun prepareScenario(
        application: LibrePlayerApplication,
        scenario: String,
    ): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) {
            controller.stop()
            controller.clearMediaItems()
        }
        val fixtures = generateFixtures()
        events.reset()
        val definition = scenarioDefinition(scenario, fixtures)
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            application.appContainer.playbackConnection.playQueue(
                queue = definition.queue,
                startIndex = definition.startIndex,
                positionMs = 0L,
            )
        }
        val expectedId = definition.queue[definition.startIndex].id
        val settled = awaitSnapshot(application, controller, "scenario $scenario") { snapshot ->
            snapshot.mediaId == expectedId &&
                (snapshot.isPlaying || snapshot.playerErrorCode != null || events.failures.isNotEmpty())
        }
        return settled.toBundle(events).apply {
            putString("scenario", scenario)
            putStringArrayList("fixtureManifest", ArrayList(fixtures.manifestLines()))
        }
    }

    private suspend fun command(
        application: LibrePlayerApplication,
        action: suspend () -> Unit,
    ): Bundle {
        withContext(Dispatchers.Main.immediate) { action() }
        delay(COMMAND_SETTLE_MS)
        return snapshotBundle(application, connectedObserver())
    }

    private suspend fun controllerCommand(
        application: LibrePlayerApplication,
        action: MediaController.() -> Unit,
    ): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) { controller.action() }
        delay(COMMAND_SETTLE_MS)
        return snapshotBundle(application, controller)
    }

    private suspend fun persistedBundle(application: LibrePlayerApplication): Bundle {
        val persisted = application.appContainer.playbackSnapshotStore.snapshot.first()
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putStringArrayList("queueIds", ArrayList(persisted.queueIds))
            putInt("currentIndex", persisted.currentIndex)
            putLong("positionMs", persisted.positionMs)
            putBoolean("playWhenReady", persisted.playWhenReady)
        }
    }

    private suspend fun librarySummary(application: LibrePlayerApplication): Bundle =
        Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putInt("songCount", application.appContainer.libraryRepository.getAllSongs().size)
        }

    private fun deleteFixture(name: String): Bundle {
        require(name in DELETABLE_FIXTURE_NAMES) { "Fixture is not test-deletable: $name" }
        val file = File(fixtureDirectory(), name)
        check(file.parentFile?.canonicalFile == fixtureDirectory().canonicalFile)
        val existed = file.exists()
        val deleted = !existed || file.delete()
        return Bundle().apply {
            putBoolean(KEY_PASSED, deleted)
            putBoolean("existed", existed)
            putBoolean("existsAfter", file.exists())
        }
    }

    private suspend fun releaseObserver(): Bundle {
        deliberateControllerRelease = true
        withContext(Dispatchers.Main.immediate) {
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        deliberateControllerRelease = false
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
    }

    private suspend fun resetPlayback(): Bundle {
        deliberateControllerRelease = true
        withContext(Dispatchers.Main.immediate) {
            observer?.stop()
            observer?.clearMediaItems()
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        deliberateControllerRelease = false
        events.reset()
        fixtureNames.forEach { name -> File(fixtureDirectory(), name).delete() }
        return Bundle().apply { putBoolean(KEY_PASSED, true) }
    }

    private suspend fun connectedObserver(): MediaController {
        observer?.let { existing ->
            if (withContext(Dispatchers.Main.immediate) { existing.isConnected }) return existing
        }
        val appContext = requireNotNull(context).applicationContext
        val future = withContext(Dispatchers.Main.immediate) {
            MediaController.Builder(
                appContext,
                SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
            ).setListener(controllerListener).buildAsync()
        }
        val controller = future.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS).also { controller ->
            withContext(Dispatchers.Main.immediate) { controller.addListener(playerListener) }
            observer = controller
            events.disconnected.set(false)
        }
        if (!initialConnectionSettled) {
            delay(SERVICE_RESTORE_SETTLE_MS)
            initialConnectionSettled = true
        }
        return controller
    }

    private suspend fun awaitSnapshot(
        application: LibrePlayerApplication,
        controller: MediaController,
        description: String,
        predicate: (FailureSnapshot) -> Boolean,
    ): FailureSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = withContext(Dispatchers.Main.immediate) { snapshot(application, controller) }
            if (predicate(snapshot)) return snapshot
            delay(POLL_MS)
        }
        error(
            "Timed out waiting for $description: " +
                withContext(Dispatchers.Main.immediate) { snapshot(application, controller) },
        )
    }

    private suspend fun snapshotBundle(
        application: LibrePlayerApplication,
        controller: MediaController,
    ): Bundle = withContext(Dispatchers.Main.immediate) {
        snapshot(application, controller).toBundle(events)
    }

    private fun snapshot(
        application: LibrePlayerApplication,
        controller: MediaController,
    ): FailureSnapshot {
        val uiState = application.appContainer.playbackConnection.uiState.value
        return FailureSnapshot(
            mediaId = controller.currentMediaItem?.mediaId,
            currentIndex = controller.currentMediaItemIndex,
            mediaIds = List(controller.mediaItemCount) { index -> controller.getMediaItemAt(index).mediaId },
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            durationMs = controller.duration.coerceAtLeast(0L),
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            suppressionReason = controller.playbackSuppressionReason,
            isPlaying = controller.isPlaying,
            connected = controller.isConnected && !events.disconnected.get(),
            playerErrorCode = controller.playerError?.errorCode,
            uiState = uiState,
        )
    }

    private fun generateFixtures(): Fixtures {
        val directory = fixtureDirectory().apply { mkdirs() }
        fixtureNames.forEach { name -> File(directory, name).delete() }
        copyAsset("q2_3/transition-flac-1.flac", File(directory, GOOD_A_FILE))
        copyAsset("q2_3/transition-flac-2.flac", File(directory, GOOD_C_FILE))
        copyAsset("q2_4/audio-focus.flac", File(directory, GOOD_LONG_FILE))
        copyAsset("q2_4/audio-focus.flac", File(directory, DELETABLE_FILE))
        File(directory, ZERO_FILE).writeBytes(byteArrayOf())
        File(directory, GARBAGE_FILE).writeBytes(
            ByteArray(GARBAGE_SIZE_BYTES) { index -> ((index * 73 + 19) and 0xff).toByte() },
        )
        val longBytes = File(directory, GOOD_LONG_FILE).readBytes()
        File(directory, TRUNCATED_FILE).writeBytes(longBytes.copyOf(TRUNCATED_SIZE_BYTES))
        File(directory, MISSING_FILE).delete()
        return Fixtures(directory)
    }

    private fun copyAsset(assetPath: String, destination: File) {
        requireNotNull(context).assets.open(assetPath).use { input ->
            destination.outputStream().use(input::copyTo)
        }
    }

    private fun fixtureDirectory(): File =
        File(requireNotNull(context).cacheDir, FIXTURE_DIRECTORY_NAME)

    private fun fixtureBundle(fixtures: Fixtures): Bundle = Bundle().apply {
        putBoolean(KEY_PASSED, true)
        putStringArrayList("fixtureManifest", ArrayList(fixtures.manifestLines()))
    }

    private fun scenarioDefinition(scenario: String, fixtures: Fixtures): Scenario = when (scenario) {
        SCENARIO_BASELINE -> Scenario(listOf(fixtures.goodA, fixtures.goodC), 0)
        SCENARIO_MISSING -> Scenario(listOf(fixtures.goodA, fixtures.missing, fixtures.goodC), 1)
        SCENARIO_ZERO -> Scenario(listOf(fixtures.goodA, fixtures.zero, fixtures.goodC), 1)
        SCENARIO_GARBAGE -> Scenario(listOf(fixtures.goodA, fixtures.garbage, fixtures.goodC), 1)
        SCENARIO_TRUNCATED -> Scenario(listOf(fixtures.goodA, fixtures.truncated, fixtures.goodC), 1)
        SCENARIO_BAD_SUCCESSOR -> Scenario(listOf(fixtures.goodA, fixtures.missing, fixtures.goodC), 0)
        SCENARIO_CONSECUTIVE -> Scenario(
            listOf(fixtures.goodA, fixtures.zero, fixtures.garbage, fixtures.goodLong),
            0,
        )
        SCENARIO_ALL_BAD -> Scenario(listOf(fixtures.missing, fixtures.zero, fixtures.garbage), 0)
        SCENARIO_DELETE_SUCCESSOR -> Scenario(
            listOf(fixtures.goodA, fixtures.deletable, fixtures.goodC),
            0,
        )
        SCENARIO_DELETE_CURRENT -> Scenario(listOf(fixtures.deletable, fixtures.goodC), 0)
        SCENARIO_LONG_RECOVERY -> Scenario(listOf(fixtures.missing, fixtures.goodLong, fixtures.goodC), 0)
        SCENARIO_RECOVERY_TRANSITION -> Scenario(
            listOf(fixtures.missing, fixtures.goodA, fixtures.goodC),
            0,
        )
        else -> error("Unknown adversarial scenario: $scenario")
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private data class Scenario(val queue: List<Song>, val startIndex: Int)

    private inner class Fixtures(private val directory: File) {
        val goodA = song(GOOD_A_ID, GOOD_A_FILE, "Q2.7 good A", 2_000L, "audio/flac")
        val goodC = song(GOOD_C_ID, GOOD_C_FILE, "Q2.7 good C", 2_000L, "audio/flac")
        val goodLong = song(GOOD_LONG_ID, GOOD_LONG_FILE, "Q2.7 good long", 20_000L, "audio/flac")
        val missing = song(MISSING_ID, MISSING_FILE, "Q2.7 missing", 2_000L, "audio/flac")
        val zero = song(ZERO_ID, ZERO_FILE, "Q2.7 zero", 0L, "audio/flac")
        val garbage = song(GARBAGE_ID, GARBAGE_FILE, "Q2.7 garbage", 0L, "audio/mpeg")
        val truncated = song(TRUNCATED_ID, TRUNCATED_FILE, "Q2.7 truncated FLAC", 20_000L, "audio/flac")
        val deletable = song(DELETABLE_ID, DELETABLE_FILE, "Q2.7 deletable", 20_000L, "audio/flac")

        fun manifestLines(): List<String> = fixtureNames.map { name ->
            val file = File(directory, name)
            if (!file.exists()) {
                "$name:missing:-"
            } else {
                "$name:${file.length()}:${file.sha256()}"
            }
        }

        private fun song(
            id: String,
            fileName: String,
            title: String,
            durationMs: Long,
            mimeType: String,
        ): Song = Song(
            id = id,
            sourceType = SongSourceType.DOCUMENT,
            contentUri = Uri.fromFile(File(directory, fileName)).toString(),
            title = title,
            artist = "Synthetic fixture",
            album = "Q2.7 adversarial authority",
            durationMs = durationMs,
            trackNumber = 1,
            discNumber = 1,
            year = null,
            dateAddedEpochSeconds = 0L,
            dateModifiedEpochSeconds = 0L,
            displayName = fileName,
            relativePath = null,
            mimeType = mimeType,
            artworkUri = null,
            isFavorite = false,
        )
    }

    private data class FailureSnapshot(
        val mediaId: String?,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val positionMs: Long,
        val durationMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
        val isPlaying: Boolean,
        val connected: Boolean,
        val playerErrorCode: Int?,
        val uiState: PlaybackUiState,
    ) {
        fun toBundle(events: FailureEvents): Bundle = Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", mediaId)
            putInt("currentIndex", currentIndex)
            putStringArrayList("mediaIds", ArrayList(mediaIds))
            putLong("positionMs", positionMs)
            putLong("durationMs", durationMs)
            putInt("playbackState", playbackState)
            putBoolean("playWhenReady", playWhenReady)
            putInt("suppressionReason", suppressionReason)
            putBoolean("isPlaying", isPlaying)
            putBoolean("connected", connected)
            putInt("playerErrorCode", playerErrorCode ?: NO_ERROR_CODE)
            putInt("errorCount", events.failures.size)
            putString("failureEvents", events.failures.joinToString("|"))
            putInt("transitionCount", events.transitions.size)
            putString("transitionEvents", events.transitions.joinToString("|"))
            putInt("sessionDisconnects", events.sessionDisconnects.get())
            putString("uiMediaId", uiState.currentSong?.id)
            putInt("uiCurrentIndex", uiState.currentIndex)
            putLong("uiPositionMs", uiState.positionMs)
            putInt("uiPlaybackState", uiState.playbackState)
            putBoolean("uiPlayWhenReady", uiState.playWhenReady)
            putBoolean("uiIsPlaying", uiState.isPlaying)
            putBoolean("uiPrimaryControlShowsPause", uiState.primaryControlShowsPause)
            putString("uiErrorMessage", uiState.errorMessage)
        }
    }

    private data class FailureEvent(
        val elapsedMs: Long,
        val errorCode: Int,
        val errorCodeName: String,
        val causeClass: String?,
        val mediaId: String?,
        val index: Int,
        val positionMs: Long,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val suppressionReason: Int,
    )

    private class FailureEvents {
        val failures = CopyOnWriteArrayList<FailureEvent>()
        val transitions = CopyOnWriteArrayList<String>()
        val sessionDisconnects = AtomicInteger()
        val disconnected = AtomicBoolean()

        fun reset() {
            failures.clear()
            transitions.clear()
            sessionDisconnects.set(0)
            disconnected.set(false)
        }
    }

    companion object {
        const val AUTHORITY = "com.libreplayer.adversarial-media-probe"
        const val METHOD_FIXTURES = "fixtures"
        const val METHOD_PREPARE = "prepare"
        const val METHOD_SNAPSHOT = "snapshot"
        const val METHOD_UI_NEXT = "ui-next"
        const val METHOD_UI_PREVIOUS = "ui-previous"
        const val METHOD_UI_PLAY = "ui-play"
        const val METHOD_SELECT = "select"
        const val METHOD_SYSTEM_NEXT = "system-next"
        const val METHOD_SYSTEM_PREVIOUS = "system-previous"
        const val METHOD_SYSTEM_PLAY = "system-play"
        const val METHOD_SEEK = "seek"
        const val METHOD_DELETE = "delete"
        const val METHOD_PERSISTED = "persisted"
        const val METHOD_LIBRARY_SUMMARY = "library-summary"
        const val METHOD_RELEASE_CONTROLLER = "release-controller"
        const val METHOD_RECONNECT = "reconnect"
        const val METHOD_RESET = "reset"
        const val KEY_PASSED = "passed"
        const val NO_ERROR_CODE = Int.MIN_VALUE
        const val SCENARIO_BASELINE = "baseline"
        const val SCENARIO_MISSING = "missing"
        const val SCENARIO_ZERO = "zero"
        const val SCENARIO_GARBAGE = "garbage"
        const val SCENARIO_TRUNCATED = "truncated"
        const val SCENARIO_BAD_SUCCESSOR = "bad-successor"
        const val SCENARIO_CONSECUTIVE = "consecutive"
        const val SCENARIO_ALL_BAD = "all-bad"
        const val SCENARIO_DELETE_SUCCESSOR = "delete-successor"
        const val SCENARIO_DELETE_CURRENT = "delete-current"
        const val SCENARIO_LONG_RECOVERY = "long-recovery"
        const val SCENARIO_RECOVERY_TRANSITION = "recovery-transition"
        const val GOOD_A_ID = "q2.7:good:a"
        const val GOOD_C_ID = "q2.7:good:c"
        const val GOOD_LONG_ID = "q2.7:good:long"
        const val MISSING_ID = "q2.7:bad:missing"
        const val ZERO_ID = "q2.7:bad:zero"
        const val GARBAGE_ID = "q2.7:bad:garbage"
        const val TRUNCATED_ID = "q2.7:bad:truncated-flac"
        const val DELETABLE_ID = "q2.7:deletable"
        const val DELETABLE_FILE = "deletable.flac"
        private const val FIXTURE_DIRECTORY_NAME = "q2_7_adversarial"
        private const val GOOD_A_FILE = "good-a.flac"
        private const val GOOD_C_FILE = "good-c.flac"
        private const val GOOD_LONG_FILE = "good-long.flac"
        private const val MISSING_FILE = "missing.flac"
        private const val ZERO_FILE = "zero.flac"
        private const val GARBAGE_FILE = "garbage.mp3"
        private const val TRUNCATED_FILE = "truncated.flac"
        private const val GARBAGE_SIZE_BYTES = 3_072
        private const val TRUNCATED_SIZE_BYTES = 8_192
        private const val STATE_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 20L
        private const val COMMAND_SETTLE_MS = 200L
        private const val CONTROLLER_TIMEOUT_SECONDS = 10L
        private const val SERVICE_RESTORE_SETTLE_MS = 500L
        private val fixtureNames = listOf(
            GOOD_A_FILE,
            GOOD_C_FILE,
            GOOD_LONG_FILE,
            MISSING_FILE,
            ZERO_FILE,
            GARBAGE_FILE,
            TRUNCATED_FILE,
            DELETABLE_FILE,
        )
        private val DELETABLE_FIXTURE_NAMES = setOf(DELETABLE_FILE)

        private fun File.sha256(): String =
            MessageDigest.getInstance("SHA-256")
                .digest(readBytes())
                .joinToString("") { byte -> "%02x".format(byte) }
    }
}

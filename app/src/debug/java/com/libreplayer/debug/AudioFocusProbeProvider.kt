package com.libreplayer.debug

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.playback.PlaybackSnapshot
import com.libreplayer.media.service.PlaybackService
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Debug-only Q2.4 authority over the target player's real Media3 audio-focus state. */
@UnstableApi
class AudioFocusProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private var initialConnectionSettled = false
    private var deliberateControllerRelease = false
    private val events = FocusEvents()

    private val playerListener = object : Player.Listener {
        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            events.playWhenReadyChanges += "$playWhenReady:$reason:${SystemClock.elapsedRealtime()}"
            events.lastPlayWhenReadyReason.set(reason)
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            events.suppressionChanges += "$playbackSuppressionReason:${SystemClock.elapsedRealtime()}"
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            events.isPlayingChanges += "$isPlaying:${SystemClock.elapsedRealtime()}"
        }

        override fun onPlayerError(error: PlaybackException) {
            events.playerErrors.incrementAndGet()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            if (!deliberateControllerRelease) {
                events.sessionDisconnects.incrementAndGet()
            }
            events.disconnected.set(true)
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                METHOD_START -> startPlayback(application)
                METHOD_SNAPSHOT -> snapshotBundle(application, connectedObserver())
                METHOD_USER_PAUSE -> userPause(application)
                METHOD_USER_PLAY -> userPlay(application)
                METHOD_PERSISTED -> persistedBundle(application)
                METHOD_Q2_REGRESSION -> focusedQ2Regression(application)
                METHOD_RELEASE_CONTROLLER -> releaseObserver()
                METHOD_RECONNECT -> snapshotBundle(application, connectedObserver())
                METHOD_RESET -> resetPlayback()
                else -> error("Unsupported audio-focus probe method: $method")
            }
        }

    private suspend fun startPlayback(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        events.reset()
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            application.appContainer.playbackConnection.playQueue(
                queue = listOf(FOCUS_SONG),
                startIndex = 0,
                positionMs = 0L,
            )
        }
        val started = awaitSnapshot(application, controller, "focus fixture playback") {
            it.isPlaying &&
                it.playWhenReady &&
                it.suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
                it.mediaId == FOCUS_MEDIA_ID &&
                it.uiState.playWhenReady &&
                it.uiState.isPlaying &&
                it.uiState.primaryControlShowsPause
        }
        return started.toBundle(events)
    }

    private suspend fun userPause(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) {
            application.appContainer.playbackConnection.togglePlayPause()
        }
        return awaitSnapshot(application, controller, "explicit pause") {
            !it.playWhenReady && !it.isPlaying
        }.toBundle(events)
    }

    private suspend fun userPlay(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        withContext(Dispatchers.Main.immediate) {
            application.appContainer.playbackConnection.togglePlayPause()
        }
        return awaitSnapshot(application, controller, "explicit play") {
            it.playWhenReady &&
                it.isPlaying &&
                it.suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }.toBundle(events)
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

    private suspend fun focusedQ2Regression(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val connection = application.appContainer.playbackConnection
        events.reset()
        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            controller.shuffleModeEnabled = false
            connection.playQueue(Q2_REGRESSION_QUEUE, startIndex = 0, positionMs = 0L)
        }
        awaitSnapshot(application, controller, "Q2.1 ordinary play") {
            it.mediaId == Q2_REGRESSION_QUEUE[0].id && it.isPlaying && it.uiState.primaryControlShowsPause
        }

        withContext(Dispatchers.Main.immediate) { connection.togglePlayPause() }
        awaitSnapshot(application, controller, "Q2.1 ordinary pause") {
            !it.playWhenReady && !it.isPlaying && !it.uiState.primaryControlShowsPause
        }
        withContext(Dispatchers.Main.immediate) { connection.seekTo(1_000L) }
        awaitSnapshot(application, controller, "Q2.2 paused seek") {
            !it.playWhenReady && it.positionMs in 850L..1_150L
        }
        withContext(Dispatchers.Main.immediate) { connection.togglePlayPause() }
        awaitSnapshot(application, controller, "Q2.2 playing seek") {
            it.playWhenReady && it.isPlaying && it.positionMs >= 950L
        }

        val automaticSuccessor = awaitSnapshot(application, controller, "Q2.3 ordinary A to B") {
            it.mediaId == Q2_REGRESSION_QUEUE[1].id &&
                it.currentIndex == 1 &&
                it.isPlaying &&
                it.positionMs < START_POSITION_TOLERANCE_MS
        }
        val ended = awaitSnapshot(application, controller, "Q2.3 final ended state") {
            it.mediaId == Q2_REGRESSION_QUEUE[1].id &&
                it.playbackState == Player.STATE_ENDED &&
                !it.isPlaying &&
                !it.uiState.primaryControlShowsPause
        }
        withContext(Dispatchers.Main.immediate) { connection.togglePlayPause() }
        val restarted = awaitSnapshot(application, controller, "Q2.3 final ended restart") {
            it.mediaId == Q2_REGRESSION_QUEUE[1].id &&
                it.isPlaying &&
                it.positionMs < START_POSITION_TOLERANCE_MS
        }

        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_ONE
            connection.playQueue(Q2_REGRESSION_QUEUE, startIndex = 0, positionMs = 0L)
        }
        awaitSnapshot(application, controller, "Q2.3 repeat-one first traversal") {
            it.mediaId == Q2_REGRESSION_QUEUE[0].id && it.isPlaying && it.positionMs >= 800L
        }
        val repeated = awaitSnapshot(application, controller, "Q2.3 repeat-one natural restart") {
            it.mediaId == Q2_REGRESSION_QUEUE[0].id &&
                it.currentIndex == 0 &&
                it.isPlaying &&
                it.positionMs < 500L
        }
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        val manualNext = awaitSnapshot(application, controller, "Q2.1 repeat-one manual next") {
            it.mediaId == Q2_REGRESSION_QUEUE[1].id &&
                it.currentIndex == 1 &&
                it.isPlaying &&
                it.positionMs < START_POSITION_TOLERANCE_MS
        }

        check(events.playerErrors.get() == 0)
        check(events.sessionDisconnects.get() == 0)
        return Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putLong("automaticSuccessorPositionMs", automaticSuccessor.positionMs)
            putInt("endedPlaybackState", ended.playbackState)
            putLong("restartPositionMs", restarted.positionMs)
            putLong("repeatPositionMs", repeated.positionMs)
            putInt("manualNextIndex", manualNext.currentIndex)
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
        predicate: (FocusSnapshot) -> Boolean,
    ): FocusSnapshot {
        val deadline = SystemClock.elapsedRealtime() + STATE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = withContext(Dispatchers.Main.immediate) {
                snapshot(application, controller)
            }
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
    ): FocusSnapshot {
        val uiState = application.appContainer.playbackConnection.uiState.value
        return FocusSnapshot(
            mediaId = controller.currentMediaItem?.mediaId,
            currentIndex = controller.currentMediaItemIndex,
            mediaItemCount = controller.mediaItemCount,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            suppressionReason = controller.playbackSuppressionReason,
            isPlaying = controller.isPlaying,
            connected = controller.isConnected && !events.disconnected.get(),
            hasPlayerError = controller.playerError != null,
            audioUsage = controller.audioAttributes.usage,
            audioContentType = controller.audioAttributes.contentType,
            uiState = uiState,
        )
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
        val audioUsage: Int,
        val audioContentType: Int,
        val uiState: PlaybackUiState,
    ) {
        fun toBundle(events: FocusEvents): Bundle = Bundle().apply {
            putBoolean(KEY_PASSED, true)
            putString("mediaId", mediaId)
            putInt("currentIndex", currentIndex)
            putInt("mediaItemCount", mediaItemCount)
            putLong("positionMs", positionMs)
            putInt("playbackState", playbackState)
            putBoolean("playWhenReady", playWhenReady)
            putInt("suppressionReason", suppressionReason)
            putBoolean("isPlaying", isPlaying)
            putBoolean("connected", connected)
            putBoolean("hasPlayerError", hasPlayerError)
            putInt("audioUsage", audioUsage)
            putInt("audioContentType", audioContentType)
            putInt("lastPlayWhenReadyReason", events.lastPlayWhenReadyReason.get())
            putString("playWhenReadyEvents", events.playWhenReadyChanges.joinToString("|"))
            putString("suppressionEvents", events.suppressionChanges.joinToString("|"))
            putString("isPlayingEvents", events.isPlayingChanges.joinToString("|"))
            putInt("playerErrors", events.playerErrors.get())
            putInt("sessionDisconnects", events.sessionDisconnects.get())
            putBoolean("uiPlayWhenReady", uiState.playWhenReady)
            putBoolean("uiIsPlaying", uiState.isPlaying)
            putInt("uiPlaybackState", uiState.playbackState)
            putInt("uiSuppressionReason", uiState.playbackSuppressionReason)
            putBoolean("uiPrimaryControlShowsPause", uiState.primaryControlShowsPause)
        }
    }

    private class FocusEvents {
        val playWhenReadyChanges = CopyOnWriteArrayList<String>()
        val suppressionChanges = CopyOnWriteArrayList<String>()
        val isPlayingChanges = CopyOnWriteArrayList<String>()
        val lastPlayWhenReadyReason = AtomicInteger(Int.MIN_VALUE)
        val playerErrors = AtomicInteger()
        val sessionDisconnects = AtomicInteger()
        val disconnected = AtomicBoolean()

        fun reset() {
            playWhenReadyChanges.clear()
            suppressionChanges.clear()
            isPlayingChanges.clear()
            lastPlayWhenReadyReason.set(Int.MIN_VALUE)
            playerErrors.set(0)
            sessionDisconnects.set(0)
            disconnected.set(false)
        }
    }

    companion object {
        const val AUTHORITY = "com.libreplayer.audio-focus-probe"
        const val METHOD_START = "start"
        const val METHOD_SNAPSHOT = "snapshot"
        const val METHOD_USER_PAUSE = "user-pause"
        const val METHOD_USER_PLAY = "user-play"
        const val METHOD_PERSISTED = "persisted"
        const val METHOD_Q2_REGRESSION = "q2-regression"
        const val METHOD_RELEASE_CONTROLLER = "release-controller"
        const val METHOD_RECONNECT = "reconnect"
        const val METHOD_RESET = "reset"
        const val KEY_PASSED = "passed"
        private const val FOCUS_MEDIA_ID = "q2.4:audio-focus"
        private const val STATE_TIMEOUT_MS = 8_000L
        private const val POLL_MS = 20L
        private const val CONTROLLER_TIMEOUT_SECONDS = 10L
        private const val SERVICE_RESTORE_SETTLE_MS = 500L
        private const val START_POSITION_TOLERANCE_MS = 900L
        private val FOCUS_SONG = Song(
            id = FOCUS_MEDIA_ID,
            sourceType = SongSourceType.DOCUMENT,
            contentUri = "asset:///q2_4/audio-focus.flac",
            title = "Q2.4 audio focus authority",
            artist = "Synthetic fixture",
            album = "Q2.4 interruption authority",
            durationMs = 20_000L,
            trackNumber = 1,
            discNumber = 1,
            year = null,
            dateAddedEpochSeconds = 0L,
            dateModifiedEpochSeconds = 0L,
            displayName = "audio-focus.flac",
            relativePath = null,
            mimeType = "audio/flac",
            artworkUri = null,
            isFavorite = false,
        )
        private val Q2_REGRESSION_QUEUE = (1..2).map { part ->
            Song(
                id = "q2.4:regression:$part",
                sourceType = SongSourceType.DOCUMENT,
                contentUri = "asset:///q2_3/transition-mp3-$part.mp3",
                title = "Q2.4 regression $part",
                artist = "Synthetic fixture",
                album = "Q2.4 focused regression",
                durationMs = 2_000L,
                trackNumber = part,
                discNumber = 1,
                year = null,
                dateAddedEpochSeconds = 0L,
                dateModifiedEpochSeconds = 0L,
                displayName = "transition-mp3-$part.mp3",
                relativePath = null,
                mimeType = "audio/mpeg",
                artworkUri = null,
                isFavorite = false,
            )
        }
    }
}

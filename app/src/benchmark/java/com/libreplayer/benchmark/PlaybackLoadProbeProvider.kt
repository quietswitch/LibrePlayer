package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.os.Trace
import android.util.Base64
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.LibraryRepository
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import com.libreplayer.media.service.PlaybackService
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.json.JSONObject

/** Benchmark-variant-only observation of real MediaSession playback during real library work. */
@UnstableApi
class PlaybackLoadProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private var playingMediaId: String? = null
    private var semanticsQueueIds: List<String> = emptyList()
    private val events = PlaybackEvents()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: PlaybackException) {
            events.playerErrors.incrementAndGet()
        }

        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            events.mediaTransitions.incrementAndGet()
        }

        override fun onPositionDiscontinuity(
            oldPosition: Player.PositionInfo,
            newPosition: Player.PositionInfo,
            reason: Int,
        ) {
            events.positionDiscontinuities.incrementAndGet()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            events.playbackStateChanges.incrementAndGet()
        }

        override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) {
            events.playWhenReadyChanges.incrementAndGet()
        }

        override fun onPlaybackSuppressionReasonChanged(playbackSuppressionReason: Int) {
            events.suppressionReasonChanges.incrementAndGet()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            events.isPlayingChanges.incrementAndGet()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            events.sessionDisconnects.incrementAndGet()
            events.disconnected.set(true)
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                METHOD_PREPARE -> preparePlayback(application)
                METHOD_SYNC -> observeLoad(application.appContainer.libraryRepository, false, arg)
                METHOD_REBUILD -> observeLoad(application.appContainer.libraryRepository, true, arg)
                METHOD_SEMANTICS -> exercisePlaybackSemantics(application)
                METHOD_VERIFY_SEMANTICS -> verifyPlaybackSemantics()
                METHOD_SEEK_SEMANTICS -> exerciseSeekSemantics(application)
                METHOD_SEEK_BACKGROUND -> verifyBackgroundSeekAndReconnect()
                METHOD_VERIFY_SEEK -> verifySeekSemantics()
                METHOD_PREPARE_PAUSED_RESTORE -> preparePositionRestoration(application, playWhenReady = false)
                METHOD_PREPARE_PLAYING_RESTORE -> preparePositionRestoration(application, playWhenReady = true)
                METHOD_VERIFY_PAUSED_RESTORE -> verifyPositionRestoration(application, playWhenReady = false)
                METHOD_VERIFY_PLAYING_RESTORE -> verifyPositionRestoration(application, playWhenReady = true)
                METHOD_STOP -> stopPlayback()
                else -> error("Unsupported playback-load probe method: $method")
            }
        }

    private suspend fun preparePlayback(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val repository = application.appContainer.libraryRepository
        val song = repository.getAllSongs().singleOrNull { it.fixtureIdentity() == PLAYBACK_FIXTURE_IDENTITY }
            ?: error("Playback fixture is not uniquely present: $PLAYBACK_FIXTURE_IDENTITY")
        check(song.durationMs >= MINIMUM_TRACK_DURATION_MS) {
            "Playback fixture is too short: ${song.durationMs}ms"
        }

        withContext(Dispatchers.Main.immediate) {
            application.appContainer.playbackConnection.playSong(listOf(song), 0)
        }
        awaitActivePlayback(controller, song.id)
        delay(STABILIZATION_MS)
        val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        check(snapshot.isContinuouslyActive(song.id)) { "Playback did not stabilize: $snapshot" }
        check(snapshot.positionMs < song.durationMs - NATURAL_COMPLETION_GUARD_MS) {
            "Playback fixture is too close to natural completion: $snapshot"
        }
        playingMediaId = song.id
        events.reset()
        return Bundle().apply {
            putBoolean(KEY_PREPARED, true)
            putString(KEY_PLAYBACK_FIXTURE_IDENTITY, PLAYBACK_FIXTURE_IDENTITY)
            putLong(KEY_TRACK_DURATION_MS, song.durationMs)
            putSnapshot("before", snapshot)
        }
    }

    private suspend fun observeLoad(
        repository: LibraryRepository,
        rebuild: Boolean,
        inspectedIdentity: String?,
    ): Bundle {
        val controller = requireNotNull(observer)
        check(withContext(Dispatchers.Main.immediate) { controller.isConnected }) {
            "Playback observer is not connected"
        }
        val expectedMediaId = requireNotNull(playingMediaId)
        events.reset()
        val before = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        check(before.isContinuouslyActive(expectedMediaId)) { "Playback inactive before load: $before" }

        val observationStartedNanos = SystemClock.elapsedRealtimeNanos()
        val synchronizationStartedNanos = SystemClock.elapsedRealtimeNanos()
        Trace.beginSection(TRACE_SECTION)
        try {
            if (rebuild) repository.rebuildLibrary() else repository.rescanLibrary()
        } finally {
            Trace.endSection()
        }
        val synchronizationElapsedNanos = SystemClock.elapsedRealtimeNanos() - synchronizationStartedNanos
        val remainingNanos = OBSERVATION_NANOS -
            (SystemClock.elapsedRealtimeNanos() - observationStartedNanos)
        if (remainingNanos > 0L) {
            delay((remainingNanos + NANOS_PER_MILLISECOND - 1L) / NANOS_PER_MILLISECOND)
        }
        val observationElapsedNanos = SystemClock.elapsedRealtimeNanos() - observationStartedNanos
        val after = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        val eventSnapshot = events.snapshot()
        val catalog = catalogBundle(repository.getAllSongs(), inspectedIdentity)
        val result = Bundle().apply {
            putLong(KEY_SYNCHRONIZATION_ELAPSED_NANOS, synchronizationElapsedNanos)
            putLong(KEY_OBSERVATION_ELAPSED_NANOS, observationElapsedNanos)
            putLong(KEY_POSITION_ADVANCEMENT_MS, after.positionMs - before.positionMs)
            putString(KEY_PLAYBACK_FIXTURE_IDENTITY, PLAYBACK_FIXTURE_IDENTITY)
            putSnapshot("before", before)
            putSnapshot("after", after)
            putEvents(eventSnapshot)
            putAll(catalog)
        }
        Log.i(LOG_TAG, "record=${encodeRecord(result, if (rebuild) METHOD_REBUILD else METHOD_SYNC)}")
        return result
    }

    private suspend fun exercisePlaybackSemantics(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val connection = application.appContainer.playbackConnection
        val songsByIdentity = application.appContainer.libraryRepository.getAllSongs()
            .mapNotNull { song -> song.fixtureIdentity()?.let { it to song } }
            .toMap()
        fun fixture(track: Int): Song {
            val identity = fixtureIdentity(track)
            return requireNotNull(songsByIdentity[identity]) { "Missing playback fixture: $identity" }
        }

        val initialQueue = listOf(fixture(9), fixture(10), fixture(11))
        val replacementQueue = listOf(fixture(12), fixture(13))
        val appendedSong = fixture(14)

        withContext(Dispatchers.Main.immediate) {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_OFF
        }
        awaitSnapshot(controller, "initial playback modes") {
            !it.shuffleEnabled && it.repeatMode == Player.REPEAT_MODE_OFF
        }
        withContext(Dispatchers.Main.immediate) {
            connection.playSong(initialQueue, 1)
        }
        awaitSnapshot(controller, "initial queue") { snapshot ->
            snapshot.isContinuouslyActive(initialQueue[1].id) &&
                snapshot.currentIndex == 1 &&
                snapshot.mediaIds == initialQueue.map(Song::id)
        }

        withContext(Dispatchers.Main.immediate) {
            controller.pause()
            controller.pause()
        }
        awaitSnapshot(controller, "idempotent pause") { snapshot ->
            snapshot.mediaId == initialQueue[1].id && !snapshot.playWhenReady && !snapshot.isPlaying
        }
        withContext(Dispatchers.Main.immediate) {
            controller.play()
            controller.play()
        }
        awaitActivePlayback(controller, initialQueue[1].id)

        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "normal next") { it.mediaId == initialQueue[2].id && it.currentIndex == 2 }
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "final next with repeat off") {
            it.mediaId == initialQueue[2].id && it.currentIndex == 2
        }

        withContext(Dispatchers.Main.immediate) { connection.skipPrevious() }
        awaitSnapshot(controller, "previous item") { it.mediaId == initialQueue[1].id && it.currentIndex == 1 }
        withContext(Dispatchers.Main.immediate) { controller.seekTo(7_000L) }
        awaitSnapshot(controller, "position before previous restart") { it.positionMs >= 6_500L }
        withContext(Dispatchers.Main.immediate) { connection.skipPrevious() }
        awaitSnapshot(controller, "previous restarts current") {
            it.mediaId == initialQueue[1].id && it.currentIndex == 1 && it.positionMs < 2_000L
        }

        withContext(Dispatchers.Main.immediate) {
            controller.repeatMode = Player.REPEAT_MODE_OFF
            connection.cycleRepeatMode()
        }
        awaitSnapshot(controller, "repeat all") { it.repeatMode == Player.REPEAT_MODE_ALL }
        withContext(Dispatchers.Main.immediate) { connection.cycleRepeatMode() }
        awaitSnapshot(controller, "repeat one") { it.repeatMode == Player.REPEAT_MODE_ONE }
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "manual next ignores repeat one") {
            it.mediaId == initialQueue[2].id && it.repeatMode == Player.REPEAT_MODE_ONE
        }

        withContext(Dispatchers.Main.immediate) { controller.repeatMode = Player.REPEAT_MODE_ALL }
        awaitSnapshot(controller, "repeat all before wrap") {
            it.repeatMode == Player.REPEAT_MODE_ALL
        }
        withContext(Dispatchers.Main.immediate) { connection.selectQueueItem(2) }
        awaitSnapshot(controller, "final item before repeat all wrap") {
            it.currentIndex == 2 && it.mediaId == initialQueue[2].id
        }
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "repeat all wraps manual next") {
            it.mediaId == initialQueue[0].id && it.currentIndex == 0
        }

        withContext(Dispatchers.Main.immediate) {
            connection.selectQueueItem(1)
            connection.setShuffleEnabled(true)
        }
        val shuffledCurrent = awaitSnapshot(controller, "shuffle preserves current") {
            it.mediaId == initialQueue[1].id && it.shuffleEnabled
        }
        val shuffledNextIndex = withContext(Dispatchers.Main.immediate) {
            controller.nextMediaItemIndex
        }
        check(shuffledNextIndex in initialQueue.indices && shuffledNextIndex != shuffledCurrent.currentIndex)
        withContext(Dispatchers.Main.immediate) { connection.skipNext() }
        awaitSnapshot(controller, "shuffled next") {
            it.currentIndex == shuffledNextIndex && it.mediaId == initialQueue[shuffledNextIndex].id
        }
        withContext(Dispatchers.Main.immediate) { connection.skipPrevious() }
        awaitSnapshot(controller, "shuffled previous") {
            it.mediaId == initialQueue[1].id && it.currentIndex == 1
        }

        withContext(Dispatchers.Main.immediate) { connection.selectQueueItem(2) }
        awaitSnapshot(controller, "select existing queue occurrence") {
            it.mediaId == initialQueue[2].id &&
                it.currentIndex == 2 &&
                it.mediaIds == initialQueue.map(Song::id) &&
                it.repeatMode == Player.REPEAT_MODE_ALL &&
                it.shuffleEnabled
        }

        withContext(Dispatchers.Main.immediate) { connection.playSong(replacementQueue, 1) }
        awaitSnapshot(controller, "queue replacement") {
            it.isContinuouslyActive(replacementQueue[1].id) &&
                it.currentIndex == 1 &&
                it.mediaIds == replacementQueue.map(Song::id) &&
                it.repeatMode == Player.REPEAT_MODE_ALL &&
                it.shuffleEnabled
        }
        withContext(Dispatchers.Main.immediate) { connection.addToQueue(appendedSong) }
        val expectedFinalQueue = replacementQueue.map(Song::id) + appendedSong.id
        awaitSnapshot(controller, "append") {
            it.mediaId == replacementQueue[1].id &&
                it.currentIndex == 1 &&
                it.mediaIds == expectedFinalQueue
        }

        withContext(Dispatchers.Main.immediate) {
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        val reconnected = connectedObserver()
        val finalSnapshot = awaitSnapshot(reconnected, "controller reconnection") {
            it.isContinuouslyActive(replacementQueue[1].id) &&
                it.currentIndex == 1 &&
                it.mediaIds == expectedFinalQueue &&
                it.repeatMode == Player.REPEAT_MODE_ALL &&
                it.shuffleEnabled
        }
        playingMediaId = replacementQueue[1].id
        semanticsQueueIds = expectedFinalQueue
        return Bundle().apply {
            putBoolean(KEY_SEMANTICS_PASSED, true)
            putSnapshot("after", finalSnapshot)
        }
    }

    private suspend fun verifyPlaybackSemantics(): Bundle {
        val controller = connectedObserver()
        val expectedMediaId = requireNotNull(playingMediaId) { "Semantics sequence was not prepared" }
        val snapshot = awaitSnapshot(controller, "activity return") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.mediaIds == semanticsQueueIds &&
                it.repeatMode == Player.REPEAT_MODE_ALL &&
                it.shuffleEnabled
        }
        return Bundle().apply {
            putBoolean(KEY_SEMANTICS_PASSED, true)
            putSnapshot("after", snapshot)
        }
    }

    private suspend fun exerciseSeekSemantics(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val connection = application.appContainer.playbackConnection
        val queue = seekFixtureQueue(application)
        val expectedMediaId = queue[1].id

        withContext(Dispatchers.Main.immediate) {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_OFF
            connection.playSong(queue, 1)
        }
        val started = awaitSnapshot(controller, "seek journey start") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.currentIndex == 1 &&
                it.mediaIds == queue.map(Song::id)
        }
        delay(SEEK_ADVANCEMENT_OBSERVATION_MS)
        val advanced = awaitSnapshot(controller, "position advancement") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.positionMs - started.positionMs >= MINIMUM_SEEK_ADVANCEMENT_MS
        }

        withContext(Dispatchers.Main.immediate) { connection.seekTo(10_000L) }
        val playingSeek = awaitSnapshot(controller, "playing seek") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.positionMs in 9_500L..12_500L
        }

        withContext(Dispatchers.Main.immediate) { controller.pause() }
        awaitSnapshot(controller, "paused before seek") {
            it.mediaId == expectedMediaId && !it.playWhenReady && !it.isPlaying
        }
        withContext(Dispatchers.Main.immediate) { connection.seekTo(15_000L) }
        val pausedSeek = awaitSnapshot(controller, "paused seek") {
            it.mediaId == expectedMediaId &&
                !it.playWhenReady &&
                !it.isPlaying &&
                it.positionMs in 14_500L..15_500L
        }

        withContext(Dispatchers.Main.immediate) { connection.seekTo(-1L) }
        awaitSnapshot(controller, "negative seek normalization") {
            it.mediaId == expectedMediaId &&
                !it.playWhenReady &&
                it.positionMs in 0L..PAUSED_SEEK_TOLERANCE_MS
        }
        withContext(Dispatchers.Main.immediate) { controller.play() }
        awaitActivePlayback(controller, expectedMediaId)

        withContext(Dispatchers.Main.immediate) {
            connection.seekTo(3_000L)
            connection.seekTo(7_000L)
            connection.seekTo(12_000L)
        }
        val repeatedSeek = awaitSnapshot(controller, "final repeated seek") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.positionMs in 11_500L..14_500L
        }

        withContext(Dispatchers.Main.immediate) { connection.seekTo(7_000L) }
        awaitSnapshot(controller, "position before restart threshold") {
            it.mediaId == expectedMediaId && it.positionMs >= 6_500L
        }
        withContext(Dispatchers.Main.immediate) { connection.skipPrevious() }
        awaitSnapshot(controller, "previous restarts current at zero") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.currentIndex == 1 &&
                it.positionMs < ITEM_START_TOLERANCE_MS
        }

        withContext(Dispatchers.Main.immediate) { connection.skipPrevious() }
        awaitSnapshot(controller, "previous selects prior item at default position") {
            it.isContinuouslyActive(queue[0].id) &&
                it.currentIndex == 0 &&
                it.positionMs < ITEM_START_TOLERANCE_MS
        }
        withContext(Dispatchers.Main.immediate) {
            connection.seekTo(16_000L)
            connection.skipNext()
        }
        val finalSnapshot = awaitSnapshot(controller, "next does not inherit old position") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.currentIndex == 1 &&
                it.positionMs < ITEM_START_TOLERANCE_MS
        }

        playingMediaId = expectedMediaId
        semanticsQueueIds = queue.map(Song::id)
        return Bundle().apply {
            putBoolean(KEY_SEEK_SEMANTICS_PASSED, true)
            putLong(KEY_POSITION_ADVANCEMENT_MS, advanced.positionMs - started.positionMs)
            putSnapshot("playingSeek", playingSeek)
            putSnapshot("pausedSeek", pausedSeek)
            putSnapshot("repeatedSeek", repeatedSeek)
            putSnapshot("after", finalSnapshot)
        }
    }

    private suspend fun verifyBackgroundSeekAndReconnect(): Bundle {
        val controller = connectedObserver()
        val expectedMediaId = requireNotNull(playingMediaId) { "Seek sequence was not prepared" }
        withContext(Dispatchers.Main.immediate) { controller.seekTo(BACKGROUND_SEEK_POSITION_MS) }
        awaitSnapshot(controller, "background MediaSession seek") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.positionMs in
                (BACKGROUND_SEEK_POSITION_MS - PLAYING_SEEK_EARLY_TOLERANCE_MS)..
                    (BACKGROUND_SEEK_POSITION_MS + PLAYING_SEEK_LATE_TOLERANCE_MS)
        }
        withContext(Dispatchers.Main.immediate) {
            observer?.removeListener(playerListener)
            observer?.release()
            observer = null
        }
        val reconnected = connectedObserver()
        val snapshot = awaitSnapshot(reconnected, "controller reconnect after background seek") {
            it.isContinuouslyActive(expectedMediaId) &&
                it.mediaIds == semanticsQueueIds &&
                it.positionMs >= BACKGROUND_SEEK_POSITION_MS - PLAYING_SEEK_EARLY_TOLERANCE_MS
        }
        return Bundle().apply {
            putBoolean(KEY_SEEK_SEMANTICS_PASSED, true)
            putSnapshot("after", snapshot)
        }
    }

    private suspend fun verifySeekSemantics(): Bundle {
        val controller = connectedObserver()
        val expectedMediaId = requireNotNull(playingMediaId) { "Seek sequence was not prepared" }
        val snapshot = awaitSnapshot(controller, "foreground return after seek") {
            it.isContinuouslyActive(expectedMediaId) && it.mediaIds == semanticsQueueIds
        }
        return Bundle().apply {
            putBoolean(KEY_SEEK_SEMANTICS_PASSED, true)
            putSnapshot("after", snapshot)
        }
    }

    private suspend fun preparePositionRestoration(
        application: LibrePlayerApplication,
        playWhenReady: Boolean,
    ): Bundle {
        val controller = connectedObserver()
        val connection = application.appContainer.playbackConnection
        val fixtures = seekFixtureQueue(application)
        val queue = listOf(fixtures[0], fixtures[1], fixtures[0])
        val index = if (playWhenReady) 1 else 2
        val positionMs = if (playWhenReady) PLAYING_RESTORE_POSITION_MS else PAUSED_RESTORE_POSITION_MS

        withContext(Dispatchers.Main.immediate) {
            controller.shuffleModeEnabled = false
            controller.repeatMode = Player.REPEAT_MODE_OFF
            connection.playQueue(queue, startIndex = index, positionMs = positionMs)
        }
        awaitActivePlayback(controller, queue[index].id)
        if (!playWhenReady) {
            withContext(Dispatchers.Main.immediate) { controller.pause() }
        }
        awaitSnapshot(controller, "restoration state before persistence") {
            it.mediaId == queue[index].id &&
                it.currentIndex == index &&
                it.mediaIds == queue.map(Song::id) &&
                it.playWhenReady == playWhenReady &&
                it.positionMs >= positionMs - PAUSED_SEEK_TOLERANCE_MS
        }
        val persisted = withTimeout(PERSISTENCE_TIMEOUT_MS) {
            application.appContainer.playbackSnapshotStore.snapshot.first { snapshot ->
                snapshot.queueIds == queue.map(Song::id) &&
                    snapshot.currentIndex == index &&
                    snapshot.playWhenReady == playWhenReady &&
                    snapshot.positionMs >= positionMs - PAUSED_SEEK_TOLERANCE_MS
            }
        }
        return Bundle().apply {
            putBoolean(KEY_RESTORATION_PASSED, true)
            putLong("persistedPositionMs", persisted.positionMs)
            putBoolean("persistedPlayWhenReady", persisted.playWhenReady)
        }
    }

    private suspend fun verifyPositionRestoration(
        application: LibrePlayerApplication,
        playWhenReady: Boolean,
    ): Bundle {
        val controller = connectedObserver()
        val fixtures = seekFixtureQueue(application)
        val queue = listOf(fixtures[0], fixtures[1], fixtures[0])
        val index = if (playWhenReady) 1 else 2
        val positionMs = if (playWhenReady) PLAYING_RESTORE_POSITION_MS else PAUSED_RESTORE_POSITION_MS
        val snapshot = awaitSnapshot(controller, "restored position and play intent") {
            it.mediaId == queue[index].id &&
                it.currentIndex == index &&
                it.mediaIds == queue.map(Song::id) &&
                it.playWhenReady == playWhenReady &&
                it.isPlaying == playWhenReady &&
                it.positionMs >= positionMs - PAUSED_SEEK_TOLERANCE_MS &&
                (!playWhenReady || it.positionMs <= positionMs + PLAYING_RESTORE_LATE_TOLERANCE_MS) &&
                (playWhenReady || it.positionMs <= positionMs + PAUSED_SEEK_TOLERANCE_MS)
        }
        return Bundle().apply {
            putBoolean(KEY_RESTORATION_PASSED, true)
            putSnapshot("after", snapshot)
        }
    }

    private suspend fun seekFixtureQueue(application: LibrePlayerApplication): List<Song> {
        val songsByIdentity = application.appContainer.libraryRepository.getAllSongs()
            .mapNotNull { song -> song.fixtureIdentity()?.let { it to song } }
            .toMap()
        return listOf(9, 10, 11).map { track ->
            val identity = fixtureIdentity(track)
            requireNotNull(songsByIdentity[identity]) { "Missing playback fixture: $identity" }
        }
    }

    private suspend fun stopPlayback(): Bundle {
        withContext(Dispatchers.Main.immediate) {
            observer?.stop()
            observer?.removeListener(playerListener)
            observer?.release()
        }
        observer = null
        playingMediaId = null
        semanticsQueueIds = emptyList()
        events.reset()
        return Bundle().apply { putBoolean(KEY_STOPPED, true) }
    }

    private suspend fun connectedObserver(): MediaController {
        observer?.let { existing ->
            if (withContext(Dispatchers.Main.immediate) { existing.isConnected }) return existing
            withContext(Dispatchers.Main.immediate) {
                runCatching {
                    existing.removeListener(playerListener)
                    existing.release()
                }
            }
        }
        val appContext = requireNotNull(context).applicationContext
        val token = SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java))
        val future = withContext(Dispatchers.Main.immediate) {
            MediaController.Builder(appContext, token)
                .setListener(controllerListener)
                .buildAsync()
        }
        return future.get(CONTROLLER_TIMEOUT_SECONDS, TimeUnit.SECONDS).also { controller ->
            withContext(Dispatchers.Main.immediate) {
                controller.addListener(playerListener)
            }
            observer = controller
            events.disconnected.set(false)
        }
    }

    private suspend fun awaitSnapshot(
        controller: MediaController,
        description: String,
        predicate: (PlaybackSnapshot) -> Boolean,
    ): PlaybackSnapshot {
        val deadline = SystemClock.elapsedRealtime() + ACTIVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
            if (predicate(snapshot)) return snapshot
            delay(ACTIVE_POLL_MS)
        }
        val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        error("Timed out waiting for $description: $snapshot")
    }

    private suspend fun awaitActivePlayback(controller: MediaController, mediaId: String) {
        val deadline = SystemClock.elapsedRealtime() + ACTIVE_TIMEOUT_MS
        while (SystemClock.elapsedRealtime() < deadline) {
            val active = withContext(Dispatchers.Main.immediate) {
                playbackSnapshot(controller).isContinuouslyActive(mediaId)
            }
            if (active) return
            delay(ACTIVE_POLL_MS)
        }
        val snapshot = withContext(Dispatchers.Main.immediate) { playbackSnapshot(controller) }
        error("Playback did not become active: $snapshot")
    }

    private fun playbackSnapshot(controller: MediaController): PlaybackSnapshot =
        PlaybackSnapshot(
            monotonicNanos = SystemClock.elapsedRealtimeNanos(),
            mediaId = controller.currentMediaItem?.mediaId,
            playbackState = controller.playbackState,
            playWhenReady = controller.playWhenReady,
            isPlaying = controller.isPlaying,
            suppressionReason = controller.playbackSuppressionReason,
            positionMs = controller.currentPosition.coerceAtLeast(0L),
            speed = controller.playbackParameters.speed,
            connected = controller.isConnected && !events.disconnected.get(),
            hasPlayerError = controller.playerError != null,
            currentIndex = controller.currentMediaItemIndex,
            mediaIds = List(controller.mediaItemCount) { index -> controller.getMediaItemAt(index).mediaId },
            repeatMode = controller.repeatMode,
            shuffleEnabled = controller.shuffleModeEnabled,
        )

    private fun PlaybackSnapshot.isContinuouslyActive(expectedMediaId: String): Boolean =
        connected &&
            mediaId == expectedMediaId &&
            playbackState == Player.STATE_READY &&
            playWhenReady &&
            isPlaying &&
            suppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE &&
            !hasPlayerError

    private fun catalogBundle(allSongs: List<Song>, inspectedIdentity: String?): Bundle {
        val fixtureSongs = allSongs.filter { song ->
            song.sourceType == SongSourceType.MEDIA_STORE && song.fixtureIdentity() != null
        }
        val identities = fixtureSongs.mapNotNull { it.fixtureIdentity() }.sorted()
        val duplicates = identities.groupingBy { it }.eachCount().count { it.value > 1 }
        val inspected = inspectedIdentity?.let { identity ->
            fixtureSongs.singleOrNull { it.fixtureIdentity() == identity }
        }
        return Bundle().apply {
            putInt(KEY_FIXTURE_COUNT, fixtureSongs.size)
            putInt(KEY_UNIQUE_IDENTITIES, identities.distinct().size)
            putInt(KEY_DUPLICATE_IDENTITIES, duplicates)
            putString(KEY_IDENTITY_SHA256, identityFingerprint(identities))
            putBoolean(KEY_INSPECTED_PRESENT, inspected != null)
            putString(KEY_INSPECTED_TITLE, inspected?.title)
        }
    }

    private fun Song.fixtureIdentity(): String? {
        val directory = relativePath?.replace('\\', '/') ?: return null
        if (!directory.startsWith(FIXTURE_RELATIVE_ROOT)) return null
        return directory.removePrefix(FIXTURE_RELATIVE_ROOT) + displayName
    }

    private fun identityFingerprint(identities: List<String>): String {
        val digest = MessageDigest.getInstance("SHA-256")
        identities.forEach { identity ->
            digest.update(identity.toByteArray(Charsets.UTF_8))
            digest.update('\n'.code.toByte())
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    private fun Bundle.putSnapshot(prefix: String, snapshot: PlaybackSnapshot) {
        putLong("${prefix}MonotonicNanos", snapshot.monotonicNanos)
        putString("${prefix}MediaId", snapshot.mediaId)
        putInt("${prefix}PlaybackState", snapshot.playbackState)
        putBoolean("${prefix}PlayWhenReady", snapshot.playWhenReady)
        putBoolean("${prefix}IsPlaying", snapshot.isPlaying)
        putInt("${prefix}SuppressionReason", snapshot.suppressionReason)
        putLong("${prefix}PositionMs", snapshot.positionMs)
        putFloat("${prefix}Speed", snapshot.speed)
        putBoolean("${prefix}Connected", snapshot.connected)
        putBoolean("${prefix}HasPlayerError", snapshot.hasPlayerError)
        putInt("${prefix}CurrentIndex", snapshot.currentIndex)
        putStringArrayList("${prefix}MediaIds", ArrayList(snapshot.mediaIds))
        putInt("${prefix}RepeatMode", snapshot.repeatMode)
        putBoolean("${prefix}ShuffleEnabled", snapshot.shuffleEnabled)
    }

    private fun Bundle.putEvents(snapshot: PlaybackEventSnapshot) {
        putInt(KEY_PLAYER_ERRORS, snapshot.playerErrors)
        putInt(KEY_MEDIA_TRANSITIONS, snapshot.mediaTransitions)
        putInt(KEY_POSITION_DISCONTINUITIES, snapshot.positionDiscontinuities)
        putInt(KEY_SESSION_DISCONNECTS, snapshot.sessionDisconnects)
        putInt(KEY_PLAYBACK_STATE_CHANGES, snapshot.playbackStateChanges)
        putInt(KEY_PLAY_WHEN_READY_CHANGES, snapshot.playWhenReadyChanges)
        putInt(KEY_SUPPRESSION_REASON_CHANGES, snapshot.suppressionReasonChanges)
        putInt(KEY_IS_PLAYING_CHANGES, snapshot.isPlayingChanges)
    }

    private fun encodeRecord(bundle: Bundle, method: String): String {
        val json = JSONObject().apply {
            put("method", method)
            bundle.keySet().sorted().forEach { key -> put(key, bundle.get(key)) }
        }
        return Base64.encodeToString(json.toString().toByteArray(Charsets.UTF_8), Base64.NO_WRAP)
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

    private data class PlaybackSnapshot(
        val monotonicNanos: Long,
        val mediaId: String?,
        val playbackState: Int,
        val playWhenReady: Boolean,
        val isPlaying: Boolean,
        val suppressionReason: Int,
        val positionMs: Long,
        val speed: Float,
        val connected: Boolean,
        val hasPlayerError: Boolean,
        val currentIndex: Int,
        val mediaIds: List<String>,
        val repeatMode: Int,
        val shuffleEnabled: Boolean,
    )

    private class PlaybackEvents {
        val playerErrors = AtomicInteger()
        val mediaTransitions = AtomicInteger()
        val positionDiscontinuities = AtomicInteger()
        val sessionDisconnects = AtomicInteger()
        val playbackStateChanges = AtomicInteger()
        val playWhenReadyChanges = AtomicInteger()
        val suppressionReasonChanges = AtomicInteger()
        val isPlayingChanges = AtomicInteger()
        val disconnected = AtomicBoolean()

        fun reset() {
            playerErrors.set(0)
            mediaTransitions.set(0)
            positionDiscontinuities.set(0)
            sessionDisconnects.set(0)
            playbackStateChanges.set(0)
            playWhenReadyChanges.set(0)
            suppressionReasonChanges.set(0)
            isPlayingChanges.set(0)
            disconnected.set(false)
        }

        fun snapshot(): PlaybackEventSnapshot = PlaybackEventSnapshot(
            playerErrors = playerErrors.get(),
            mediaTransitions = mediaTransitions.get(),
            positionDiscontinuities = positionDiscontinuities.get(),
            sessionDisconnects = sessionDisconnects.get(),
            playbackStateChanges = playbackStateChanges.get(),
            playWhenReadyChanges = playWhenReadyChanges.get(),
            suppressionReasonChanges = suppressionReasonChanges.get(),
            isPlayingChanges = isPlayingChanges.get(),
        )
    }

    private data class PlaybackEventSnapshot(
        val playerErrors: Int,
        val mediaTransitions: Int,
        val positionDiscontinuities: Int,
        val sessionDisconnects: Int,
        val playbackStateChanges: Int,
        val playWhenReadyChanges: Int,
        val suppressionReasonChanges: Int,
        val isPlayingChanges: Int,
    )

    companion object {
        const val AUTHORITY = "com.libreplayer.playback-load-probe"
        const val METHOD_PREPARE = "prepare"
        const val METHOD_SYNC = "sync"
        const val METHOD_REBUILD = "rebuild"
        const val METHOD_SEMANTICS = "semantics"
        const val METHOD_VERIFY_SEMANTICS = "verify-semantics"
        const val METHOD_SEEK_SEMANTICS = "seek-semantics"
        const val METHOD_SEEK_BACKGROUND = "seek-background"
        const val METHOD_VERIFY_SEEK = "verify-seek"
        const val METHOD_PREPARE_PAUSED_RESTORE = "prepare-paused-restore"
        const val METHOD_PREPARE_PLAYING_RESTORE = "prepare-playing-restore"
        const val METHOD_VERIFY_PAUSED_RESTORE = "verify-paused-restore"
        const val METHOD_VERIFY_PLAYING_RESTORE = "verify-playing-restore"
        const val METHOD_STOP = "stop"
        const val TRACE_SECTION = "LibrePlayerPlaybackUnderLoad"
        const val LOG_TAG = "LibrePlayerPlaybackLoad"
        const val PLAYBACK_FIXTURE_IDENTITY =
            "audio/artist-00010/album-00010/disc-01/track-00010.mp3"
        const val KEY_PREPARED = "prepared"
        const val KEY_STOPPED = "stopped"
        const val KEY_SEMANTICS_PASSED = "semanticsPassed"
        const val KEY_SEEK_SEMANTICS_PASSED = "seekSemanticsPassed"
        const val KEY_RESTORATION_PASSED = "restorationPassed"
        const val KEY_PLAYBACK_FIXTURE_IDENTITY = "playbackFixtureIdentity"
        const val KEY_TRACK_DURATION_MS = "trackDurationMs"
        const val KEY_SYNCHRONIZATION_ELAPSED_NANOS = "synchronizationElapsedNanos"
        const val KEY_OBSERVATION_ELAPSED_NANOS = "observationElapsedNanos"
        const val KEY_POSITION_ADVANCEMENT_MS = "positionAdvancementMs"
        const val KEY_PLAYER_ERRORS = "playerErrors"
        const val KEY_MEDIA_TRANSITIONS = "mediaTransitions"
        const val KEY_POSITION_DISCONTINUITIES = "positionDiscontinuities"
        const val KEY_SESSION_DISCONNECTS = "sessionDisconnects"
        const val KEY_PLAYBACK_STATE_CHANGES = "playbackStateChanges"
        const val KEY_PLAY_WHEN_READY_CHANGES = "playWhenReadyChanges"
        const val KEY_SUPPRESSION_REASON_CHANGES = "suppressionReasonChanges"
        const val KEY_IS_PLAYING_CHANGES = "isPlayingChanges"
        const val KEY_FIXTURE_COUNT = "fixtureCount"
        const val KEY_UNIQUE_IDENTITIES = "uniqueIdentities"
        const val KEY_DUPLICATE_IDENTITIES = "duplicateIdentities"
        const val KEY_IDENTITY_SHA256 = "identitySha256"
        const val KEY_INSPECTED_PRESENT = "inspectedPresent"
        const val KEY_INSPECTED_TITLE = "inspectedTitle"
        private const val FIXTURE_RELATIVE_ROOT = "Music/LibrePlayerBenchmark/MEDIUM/"
        private const val MINIMUM_TRACK_DURATION_MS = 30_000L
        private const val NATURAL_COMPLETION_GUARD_MS = 5_000L
        private const val OBSERVATION_NANOS = 2_000_000_000L
        private const val NANOS_PER_MILLISECOND = 1_000_000L
        private const val STABILIZATION_MS = 750L
        private const val ACTIVE_TIMEOUT_MS = 15_000L
        private const val ACTIVE_POLL_MS = 50L
        private const val CONTROLLER_TIMEOUT_SECONDS = 15L
        private const val SEEK_ADVANCEMENT_OBSERVATION_MS = 750L
        private const val MINIMUM_SEEK_ADVANCEMENT_MS = 400L
        private const val PAUSED_SEEK_TOLERANCE_MS = 500L
        private const val ITEM_START_TOLERANCE_MS = 2_000L
        private const val BACKGROUND_SEEK_POSITION_MS = 6_000L
        private const val PLAYING_SEEK_EARLY_TOLERANCE_MS = 500L
        private const val PLAYING_SEEK_LATE_TOLERANCE_MS = 3_000L
        private const val PAUSED_RESTORE_POSITION_MS = 15_000L
        private const val PLAYING_RESTORE_POSITION_MS = 8_000L
        private const val PLAYING_RESTORE_LATE_TOLERANCE_MS = 5_000L
        private const val PERSISTENCE_TIMEOUT_MS = 10_000L

        private fun fixtureIdentity(track: Int): String {
            val padded = track.toString().padStart(5, '0')
            return "audio/artist-$padded/album-$padded/disc-01/track-$padded.mp3"
        }
    }
}

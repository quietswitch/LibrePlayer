package com.libreplayer.benchmark

import android.content.ComponentName
import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.media.service.PlaybackService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext

/** Benchmark-variant-only control and observation for the Q1.1g steady-playback journey. */
@UnstableApi
class MemoryResourceProbeProvider : ContentProvider() {
    private var observer: MediaController? = null
    private val playerErrors = AtomicInteger()
    private val sessionDisconnects = AtomicInteger()

    private val playerListener = object : Player.Listener {
        override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
            playerErrors.incrementAndGet()
        }
    }

    private val controllerListener = object : MediaController.Listener {
        override fun onDisconnected(controller: MediaController) {
            sessionDisconnects.incrementAndGet()
        }
    }

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle =
        runBlocking(Dispatchers.IO) {
            val application = requireNotNull(context).applicationContext as LibrePlayerApplication
            when (method) {
                "prepare" -> prepare(application)
                "status" -> status()
                "stop" -> stop()
                else -> error("Unsupported memory-resource probe method: $method")
            }
        }

    private suspend fun prepare(application: LibrePlayerApplication): Bundle {
        val controller = connectedObserver()
        val songs = application.appContainer.libraryRepository.getAllSongs()
            .filter { song -> song.relativePath?.startsWith("Music/LibrePlayerBenchmark/MEDIUM/") == true }
            .sortedBy { song -> song.resolvedTitle }
            .take(20)
        check(songs.size == 20) { "Expected 20 deterministic MEDIUM playback tracks, found ${songs.size}" }
        check(songs.none { song -> MUTATION_IDENTITIES.any(song.relativePath.orEmpty()::endsWith) }) {
            "Steady-playback queue overlaps a synchronization mutation target"
        }
        playerErrors.set(0)
        sessionDisconnects.set(0)
        withContext(Dispatchers.Main.immediate) {
            application.appContainer.playbackConnection.playQueue(songs)
        }
        val deadline = System.currentTimeMillis() + 15_000L
        while (System.currentTimeMillis() < deadline) {
            val active = withContext(Dispatchers.Main.immediate) {
                controller.isConnected && controller.playbackState == Player.STATE_READY &&
                    controller.playWhenReady && controller.isPlaying && controller.playerError == null
            }
            if (active) break
            delay(50L)
        }
        delay(750L)
        return status().apply {
            putBoolean("prepared", true)
            putInt("expectedQueueSize", songs.size)
        }
    }

    private suspend fun status(): Bundle {
        val controller = requireNotNull(observer) { "Playback observer is not connected" }
        return withContext(Dispatchers.Main.immediate) {
            Bundle().apply {
                putBoolean("connected", controller.isConnected)
                putInt("playbackState", controller.playbackState)
                putBoolean("playWhenReady", controller.playWhenReady)
                putBoolean("isPlaying", controller.isPlaying)
                putInt("suppressionReason", controller.playbackSuppressionReason)
                putBoolean("hasPlayerError", controller.playerError != null)
                putInt("queueSize", controller.mediaItemCount)
                putInt("currentIndex", controller.currentMediaItemIndex)
                putLong("positionMs", controller.currentPosition)
                putInt("playerErrors", playerErrors.get())
                putInt("sessionDisconnects", sessionDisconnects.get())
            }
        }
    }

    private suspend fun stop(): Bundle {
        val controller = observer
        if (controller != null) {
            withContext(Dispatchers.Main.immediate) {
                if (controller.isConnected) {
                    controller.stop()
                    controller.clearMediaItems()
                    controller.removeListener(playerListener)
                    controller.release()
                }
            }
        }
        observer = null
        return Bundle().apply { putBoolean("stopped", true) }
    }

    private suspend fun connectedObserver(): MediaController {
        observer?.let { controller ->
            if (withContext(Dispatchers.Main.immediate) { controller.isConnected }) return controller
        }
        val appContext = requireNotNull(context).applicationContext
        val future = MediaController.Builder(
            appContext,
            SessionToken(appContext, ComponentName(appContext, PlaybackService::class.java)),
        ).setListener(controllerListener).buildAsync()
        val controller = withContext(Dispatchers.IO) { future.get(15L, TimeUnit.SECONDS) }
        withContext(Dispatchers.Main.immediate) { controller.addListener(playerListener) }
        observer = controller
        return controller
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    private companion object {
        val MUTATION_IDENTITIES = setOf(
            "audio/artist-00007/album-00007/disc-01/track-00007.mp3",
            "audio/artist-00011/album-00011/disc-01/track-00011.mp3",
            "audio/artist-00013/album-00013/disc-01/track-00013.mp3",
        )
    }
}

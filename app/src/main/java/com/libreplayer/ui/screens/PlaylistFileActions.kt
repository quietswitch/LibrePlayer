package com.libreplayer.ui.screens

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.libreplayer.app.LibrePlayerApplication
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.library.playlist.PlaylistImportPreview
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** Document-picker IO is a one-time user action, independent of playback-position state. */
@Composable
internal fun PlaylistFileActions(playlists: List<UserPlaylist>) {
    val application = LocalContext.current.applicationContext as LibrePlayerApplication
    val interchange = application.appContainer.playlistInterchange
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var preview by remember { mutableStateOf<PlaylistImportPreview?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var exporting by rememberSaveable { mutableStateOf<Long?>(null) }
    var exportMenu by remember { mutableStateOf(false) }

    fun perform(action: suspend () -> Unit) {
        if (busy) return
        busy = true
        scope.launch {
            try {
                action()
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "Playlist operation could not complete. Check document access, UTF-8 encoding and the 1 MiB / 10000-entry / 8192-character line limits. Existing playlists and music were not changed; an interrupted export document may be incomplete."
            } finally {
                busy = false
            }
        }
    }

    val open = rememberLauncherForActivityResult(LocalPlaylistDocument()) { uri ->
        if (uri != null) perform { preview = interchange.preview(uri) }
    }
    val create = rememberLauncherForActivityResult(LocalPlaylistExport()) { uri ->
        val id = exporting
        exporting = null
        if (uri != null && id != null) perform {
            val result = interchange.export(id, uri)
            message = "Exported ${result.written} songs; ${result.unavailable} unavailable references omitted. " +
                "${result.uriReferences} content URI references require the same provider/library and are not portable file paths."
        }
    }
    TextButton(enabled = !busy && preview == null, onClick = { open.launch(arrayOf("*/*")) }) {
        Text(if (busy) "Working…" else "Import M3U")
    }
    Box {
        TextButton(enabled = !busy && preview == null && playlists.isNotEmpty(), onClick = { exportMenu = true }) {
            Text("Export M3U")
        }
        DropdownMenu(expanded = exportMenu, onDismissRequest = { exportMenu = false }) {
            playlists.forEach { playlist ->
                DropdownMenuItem(text = { Text(playlist.name) }, onClick = {
                    exporting = playlist.id
                    exportMenu = false
                    val name = playlist.name.map { if (it in "/\\\r\n") '_' else it }.joinToString("").take(120).ifBlank { "Playlist" }
                    create.launch("$name.m3u8")
                })
            }
        }
    }
    preview?.let { pending ->
        AlertDialog(
            onDismissRequest = { if (!busy) preview = null },
            title = { Text("Import ${pending.name}?") },
            text = { Text(pending.report.summary()) },
            confirmButton = {
                TextButton(enabled = !busy, onClick = {
                    perform {
                        interchange.commitImport(pending)
                        preview = null
                        message = "Imported ${pending.report.importSongIds.size} songs into a new playlist. Existing playlists and library metadata were not changed."
                    }
                }) { Text("Import ${pending.report.importSongIds.size} songs") }
            },
            dismissButton = { TextButton(enabled = !busy, onClick = { preview = null }) { Text("Cancel") } },
        )
    }
    message?.let { result ->
        AlertDialog(
            onDismissRequest = { message = null },
            title = { Text("Playlist file result") },
            text = { Text(result) },
            confirmButton = { TextButton(onClick = { message = null }) { Text("OK") } },
        )
    }
}

private class LocalPlaylistDocument : ActivityResultContracts.OpenDocument() {
    override fun createIntent(context: Context, input: Array<String>): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

private class LocalPlaylistExport : ActivityResultContracts.CreateDocument("audio/x-mpegurl") {
    override fun createIntent(context: Context, input: String): Intent =
        super.createIntent(context, input).putExtra(Intent.EXTRA_LOCAL_ONLY, true)
}

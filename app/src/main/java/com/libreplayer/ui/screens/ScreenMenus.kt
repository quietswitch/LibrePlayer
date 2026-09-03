package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.library.semantics.albumBrowseGroupId
import com.libreplayer.library.semantics.artistBrowseGroupId
import java.util.Locale

@Composable
internal fun SongOverflowMenu(
    song: Song,
    onToggleFavorite: (String) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onAddToPlaylist: () -> Unit,
    onOpenDetails: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Song actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (song.isFavorite) "Remove favorite" else "Add favorite") },
                leadingIcon = {
                    Icon(
                        if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = null,
                    )
                },
                onClick = {
                    onToggleFavorite(song.id)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add to queue") },
                onClick = {
                    routeAddToQueue(song, onAddToQueue)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Add to playlist") },
                onClick = {
                    onAddToPlaylist()
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Audio details") },
                onClick = {
                    onOpenDetails(song.id)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Open album") },
                onClick = {
                    routeOpenAlbum(song, onOpenAlbum)
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Open artist") },
                onClick = {
                    routeOpenArtist(song, onOpenArtist)
                    expanded = false
                },
            )
        }
    }
}

@Composable
internal fun PlaylistOverflowMenu(
    onRename: () -> Unit,
    onDelete: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Playlist actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Rename") },
                onClick = {
                    onRename()
                    expanded = false
                },
            )
            DropdownMenuItem(
                text = { Text("Delete") },
                onClick = {
                    onDelete()
                    expanded = false
                },
            )
        }
    }
}

@Composable
internal fun PlaylistSongOverflowMenu(
    editable: Boolean,
    canMoveUp: Boolean,
    canMoveDown: Boolean,
    isFavorite: Boolean,
    onToggleFavorite: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "Playlist song actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text(if (isFavorite) "Remove favorite" else "Add favorite") },
                onClick = {
                    onToggleFavorite()
                    expanded = false
                },
            )
            if (editable && canMoveUp) {
                DropdownMenuItem(
                    text = { Text("Move up") },
                    onClick = {
                        onMoveUp()
                        expanded = false
                    },
                )
            }
            if (editable && canMoveDown) {
                DropdownMenuItem(
                    text = { Text("Move down") },
                    onClick = {
                        onMoveDown()
                        expanded = false
                    },
                )
            }
            if (editable) {
                DropdownMenuItem(
                    text = { Text("Remove from playlist") },
                    onClick = {
                        onRemove()
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
internal fun PlaylistPickerDialog(
    song: Song,
    playlists: List<UserPlaylist>,
    onDismiss: () -> Unit,
    onSelectPlaylist: (Long) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to playlist") },
        text = {
            if (playlists.isEmpty()) {
                Text("Create a playlist first from the Playlists tab, then add ${song.resolvedTitle} to it.")
            } else {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    playlists.forEach { playlist ->
                        OutlinedButton(
                            onClick = { onSelectPlaylist(playlist.id) },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(playlist.name)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(if (playlists.isEmpty()) "Close" else "Cancel")
            }
        },
    )
}

@Composable
internal fun PlaylistNameDialog(
    title: String,
    initialValue: String,
    confirmLabel: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by rememberSaveable(initialValue) { mutableStateOf(initialValue) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                label = { Text("Playlist name") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
        },
        confirmButton = {
            Button(onClick = { onConfirm(text) }) {
                Text(confirmLabel)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

@Composable
internal fun SortMenu(
    selected: LibrarySortOption,
    onSelected: (LibrarySortOption) -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.Sort, contentDescription = "Sort songs")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            LibrarySortOption.entries.forEach { option ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = option.displayLabel(),
                            fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
                        )
                    },
                    onClick = {
                        onSelected(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

internal fun displayTitle(song: Song, settings: AppSettings): String =
    when {
        !song.title.isNullOrBlank() -> song.title
        settings.showFileNamesWhenMetadataMissing -> song.displayName.substringBeforeLast('.')
        else -> "Unknown title"
    }

internal fun displaySubtitle(song: Song): String = "${song.resolvedArtist} - ${song.resolvedAlbum}"

internal fun albumKey(song: Song): String =
    song.albumBrowseGroupId()

internal fun artistKey(song: Song): String = song.artistBrowseGroupId()

internal fun routeAddToQueue(
    song: Song,
    onAddToQueue: (Song) -> Unit,
) {
    onAddToQueue(song)
}

internal fun routeOpenAlbum(
    song: Song,
    onOpenAlbum: (String) -> Unit,
) {
    onOpenAlbum(albumKey(song))
}

internal fun routeOpenArtist(
    song: Song,
    onOpenArtist: (String) -> Unit,
) {
    onOpenArtist(artistKey(song))
}

private fun String.normalized(): String = trim().lowercase(Locale.US)

private fun LibrarySortOption.displayLabel(): String =
    when (this) {
        LibrarySortOption.TITLE -> "Title"
        LibrarySortOption.ARTIST -> "Artist"
        LibrarySortOption.ALBUM -> "Album"
        LibrarySortOption.DURATION -> "Duration"
        LibrarySortOption.DATE_ADDED -> "Date added"
    }

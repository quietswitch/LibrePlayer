package com.libreplayer.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.LibraryScreenState
import com.libreplayer.data.repository.LibrarySortOption
import com.libreplayer.data.repository.SmartPlaylistType
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.ui.components.AlbumRow
import com.libreplayer.ui.components.ArtistRow
import com.libreplayer.ui.components.DividerItem
import com.libreplayer.ui.components.EmptyState
import com.libreplayer.ui.components.PlaylistRow
import com.libreplayer.ui.components.SectionHeader
import com.libreplayer.ui.components.SongRow
import com.libreplayer.util.AudioPermissionAction
import com.libreplayer.util.audioReadPermission
import com.libreplayer.util.findActivity
import com.libreplayer.util.hasAudioReadPermission
import com.libreplayer.util.openAppSettings
import com.libreplayer.util.resolveAudioPermissionAction

enum class SongsSection {
    SONGS,
    ALBUMS,
    ARTISTS,
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SongsAlbumsArtistsScreen(
    title: String,
    section: SongsSection,
    libraryState: LibraryScreenState,
    settings: AppSettings,
    onSearch: () -> Unit,
    onSortChange: (LibrarySortOption) -> Unit,
    onGrantPermissionRescan: () -> Unit,
    onToggleFavorite: (String) -> Unit,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAudioDetails: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    playlists: List<UserPlaylist>,
    onAddSongToPlaylist: (Long, String) -> Unit,
    contentPadding: PaddingValues,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = audioReadPermission()
    val activity = context.findActivity()
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    var deniedRequestCount by rememberSaveable { mutableStateOf(0) }
    var resumeSignal by rememberSaveable { mutableStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            deniedRequestCount = 0
            onGrantPermissionRescan()
        } else {
            deniedRequestCount++
        }
    }
    val permissionAction = resolveAudioPermissionAction(
        hasPermission = hasAudioReadPermission(context),
        deniedRequestCount = deniedRequestCount,
        shouldShowRationale = activity?.shouldShowRequestPermissionRationale(permission) == true,
    )

    fun requestPermission() {
        hasRequestedPermission = true
        permissionLauncher.launch(permission)
    }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                resumeSignal++
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    LaunchedEffect(libraryState.permissionRequired, permissionAction, resumeSignal) {
        if (!libraryState.permissionRequired) return@LaunchedEffect
        when {
            hasAudioReadPermission(context) -> onGrantPermissionRescan()
            permissionAction == AudioPermissionAction.REQUEST && !hasRequestedPermission -> requestPermission()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(title) },
                actions = {
                    if (section == SongsSection.SONGS) {
                        SortMenu(
                            selected = settings.defaultSortOption,
                            onSelected = onSortChange,
                        )
                    }
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search library")
                    }
                },
            )
        },
    ) { innerPadding ->
        val combinedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 16.dp,
        )
        val hasVisibleContent = when (section) {
            SongsSection.SONGS -> libraryState.songs.isNotEmpty()
            SongsSection.ALBUMS -> libraryState.albums.isNotEmpty()
            SongsSection.ARTISTS -> libraryState.artists.isNotEmpty()
        }
        val statusBanner = if (hasVisibleContent) {
            when {
                libraryState.permissionRequired -> LibraryStatusBannerState(
                    title = "Audio access needed",
                    message = if (permissionAction == AudioPermissionAction.OPEN_SETTINGS) {
                        "Showing saved music. Open Android settings and allow Music and audio access to refresh the library."
                    } else if (deniedRequestCount > 0) {
                        "Showing saved music. Audio access was denied, but you can retry without leaving the app."
                    } else {
                        "Showing saved music while LibrePlayer requests audio access to refresh the library."
                    },
                    actionLabel = when {
                        permissionAction == AudioPermissionAction.OPEN_SETTINGS -> "Open settings"
                        deniedRequestCount > 0 -> "Try again"
                        else -> "Grant access"
                    },
                    onAction = {
                        when (permissionAction) {
                            AudioPermissionAction.OPEN_SETTINGS -> openAppSettings(context)
                            AudioPermissionAction.REQUEST, null -> requestPermission()
                        }
                    },
                )
                libraryState.errorMessage != null -> LibraryStatusBannerState(
                    title = "Library update paused",
                    message = "Showing saved music. ${libraryState.errorMessage}",
                    actionLabel = "Try again",
                    onAction = onGrantPermissionRescan,
                )
                libraryState.isLoading -> LibraryStatusBannerState(
                    title = "Updating library",
                    message = "Showing saved music while LibrePlayer refreshes your library in the background.",
                )
                else -> null
            }
        } else {
            null
        }
        when {
            libraryState.permissionRequired && !hasVisibleContent -> EmptyState(
                title = "Audio access needed",
                message = if (permissionAction == AudioPermissionAction.OPEN_SETTINGS) {
                    "LibrePlayer only reads your local music library. Audio access is still blocked, so open Android settings and allow Music and audio access."
                } else if (deniedRequestCount > 0) {
                    "LibrePlayer only reads your local music library. Audio access was denied, but you can try again here without leaving the app."
                } else {
                    "LibrePlayer only reads your local music library. Grant audio access to scan songs on this device."
                },
                modifier = Modifier.padding(combinedPadding),
                action = {
                    Button(
                        onClick = {
                            when (permissionAction) {
                                AudioPermissionAction.OPEN_SETTINGS -> openAppSettings(context)
                                AudioPermissionAction.REQUEST, null -> requestPermission()
                            }
                        },
                    ) {
                        Text(
                            when {
                                permissionAction == AudioPermissionAction.OPEN_SETTINGS -> "Open settings"
                                deniedRequestCount > 0 -> "Try again"
                                else -> "Grant access"
                            },
                        )
                    }
                },
            )

            libraryState.isLoading && !hasVisibleContent -> EmptyState(
                title = "Scanning library",
                message = "Looking for local music files and reading available metadata.",
                modifier = Modifier.padding(combinedPadding),
            )

            libraryState.errorMessage != null && !hasVisibleContent -> EmptyState(
                title = "Library unavailable",
                message = libraryState.errorMessage,
                modifier = Modifier.padding(combinedPadding),
                action = {
                    OutlinedButton(onClick = onGrantPermissionRescan) {
                        Text("Try again")
                    }
                },
            )

            else -> when (section) {
                SongsSection.SONGS -> SongsList(
                    songs = libraryState.songs,
                    settings = settings,
                    playlists = playlists,
                    onPlaySongs = onPlaySongs,
                    onAddToQueue = onAddToQueue,
                    onOpenAudioDetails = onOpenAudioDetails,
                    onToggleFavorite = onToggleFavorite,
                    onOpenAlbum = onOpenAlbum,
                    onOpenArtist = onOpenArtist,
                    onAddSongToPlaylist = onAddSongToPlaylist,
                    contentPadding = combinedPadding,
                    statusBanner = statusBanner,
                )
                SongsSection.ALBUMS -> AlbumsList(
                    libraryState = libraryState,
                    contentPadding = combinedPadding,
                    onOpenAlbum = onOpenAlbum,
                    statusBanner = statusBanner,
                )
                SongsSection.ARTISTS -> ArtistsList(
                    libraryState = libraryState,
                    contentPadding = combinedPadding,
                    onOpenArtist = onOpenArtist,
                    statusBanner = statusBanner,
                )
            }
        }
    }
}

@Composable
private fun SongsList(
    songs: List<Song>,
    settings: AppSettings,
    playlists: List<UserPlaylist>,
    onPlaySongs: (List<Song>, Int) -> Unit,
    onAddToQueue: (Song) -> Unit,
    onOpenAudioDetails: (String) -> Unit,
    onToggleFavorite: (String) -> Unit,
    onOpenAlbum: (String) -> Unit,
    onOpenArtist: (String) -> Unit,
    onAddSongToPlaylist: (Long, String) -> Unit,
    contentPadding: PaddingValues,
    statusBanner: LibraryStatusBannerState? = null,
) {
    var selectedSongForPlaylist by remember { mutableStateOf<Song?>(null) }

    if (songs.isEmpty()) {
        EmptyState(
            title = "No music found",
            message = "LibrePlayer only shows local audio files. Add music to your device or import a folder from Settings.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = contentPadding,
    ) {
        statusBanner?.let { banner ->
            item(key = "status_banner") {
                LibraryStatusBanner(state = banner)
            }
        }
        itemsIndexed(songs, key = { _, item -> item.id }) { index, song ->
            SongRow(
                title = displayTitle(song, settings),
                subtitle = displaySubtitle(song),
                supportingText = song.relativePath,
                durationMs = song.durationMs,
                artworkUri = song.artworkUri,
                fallbackArtworkUri = song.contentUri,
                sourceRevisionEpochSeconds = song.dateModifiedEpochSeconds,
                trailingContent = {
                    SongOverflowMenu(
                        song = song,
                        onToggleFavorite = onToggleFavorite,
                        onAddToQueue = onAddToQueue,
                        onAddToPlaylist = { selectedSongForPlaylist = song },
                        onOpenDetails = onOpenAudioDetails,
                        onOpenAlbum = onOpenAlbum,
                        onOpenArtist = onOpenArtist,
                    )
                },
                onClick = { onPlaySongs(songs, index) },
            )
            DividerItem()
        }
    }

    selectedSongForPlaylist?.let { song ->
        PlaylistPickerDialog(
            song = song,
            playlists = playlists,
            onDismiss = { selectedSongForPlaylist = null },
            onSelectPlaylist = { playlistId ->
                onAddSongToPlaylist(playlistId, song.id)
                selectedSongForPlaylist = null
            },
        )
    }
}

@Composable
private fun AlbumsList(
    libraryState: LibraryScreenState,
    contentPadding: PaddingValues,
    onOpenAlbum: (String) -> Unit,
    statusBanner: LibraryStatusBannerState? = null,
) {
    if (libraryState.albums.isEmpty()) {
        EmptyState(
            title = "No albums yet",
            message = "Albums appear once LibrePlayer can read local audio metadata on this device.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }
    LazyColumn(contentPadding = contentPadding) {
        statusBanner?.let { banner ->
            item(key = "status_banner") {
                LibraryStatusBanner(state = banner)
            }
        }
        itemsIndexed(libraryState.albums, key = { _, album -> album.id }) { _, album ->
            AlbumRow(album = album, onClick = { onOpenAlbum(album.id) })
            DividerItem()
        }
    }
}

@Composable
private fun ArtistsList(
    libraryState: LibraryScreenState,
    contentPadding: PaddingValues,
    onOpenArtist: (String) -> Unit,
    statusBanner: LibraryStatusBannerState? = null,
) {
    if (libraryState.artists.isEmpty()) {
        EmptyState(
            title = "No artists yet",
            message = "Artists appear once LibrePlayer can read local audio metadata on this device.",
            modifier = Modifier.padding(contentPadding),
        )
        return
    }
    LazyColumn(contentPadding = contentPadding) {
        statusBanner?.let { banner ->
            item(key = "status_banner") {
                LibraryStatusBanner(state = banner)
            }
        }
        itemsIndexed(libraryState.artists, key = { _, artist -> artist.id }) { _, artist ->
            ArtistRow(artist = artist, onClick = { onOpenArtist(artist.id) })
            DividerItem()
        }
    }
}

private data class LibraryStatusBannerState(
    val title: String,
    val message: String,
    val actionLabel: String? = null,
    val onAction: (() -> Unit)? = null,
)

@Composable
private fun LibraryStatusBanner(state: LibraryStatusBannerState) {
    Card(
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        androidx.compose.foundation.layout.Column(
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(
                text = state.title,
                style = MaterialTheme.typography.titleSmall,
            )
            Text(
                text = state.message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (state.actionLabel != null && state.onAction != null) {
                OutlinedButton(
                    onClick = state.onAction,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    Text(state.actionLabel)
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaylistsScreen(
    libraryState: LibraryScreenState,
    onSearch: () -> Unit,
    onCreatePlaylist: (String) -> Unit,
    onRenamePlaylist: (Long, String) -> Unit,
    onDeletePlaylist: (Long) -> Unit,
    onOpenPlaylist: (Long) -> Unit,
    onOpenSmartPlaylist: (SmartPlaylistType) -> Unit,
    contentPadding: PaddingValues,
) {
    var createDialogVisible by rememberSaveable { mutableStateOf(false) }
    var renameTarget by remember { mutableStateOf<UserPlaylist?>(null) }
    var deleteTarget by remember { mutableStateOf<UserPlaylist?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Playlists") },
                actions = {
                    PlaylistFileActions(libraryState.playlists)
                    IconButton(onClick = onSearch) {
                        Icon(Icons.Filled.Search, contentDescription = "Search library")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { createDialogVisible = true }) {
                Icon(Icons.Filled.Add, contentDescription = "Create playlist")
            }
        },
    ) { innerPadding ->
        val combinedPadding = PaddingValues(
            top = innerPadding.calculateTopPadding() + contentPadding.calculateTopPadding(),
            bottom = contentPadding.calculateBottomPadding() + 88.dp,
        )
        LazyColumn(contentPadding = combinedPadding) {
            item { SectionHeader(title = "Smart collections") }
            item {
                PlaylistRow(
                    playlist = UserPlaylist(-1L, "Favorites", libraryState.favorites.size, System.currentTimeMillis()),
                    onClick = { onOpenSmartPlaylist(SmartPlaylistType.FAVORITES) },
                )
                DividerItem()
            }
            item {
                PlaylistRow(
                    playlist = UserPlaylist(-2L, "Recently played", libraryState.recentlyPlayed.size, System.currentTimeMillis()),
                    onClick = { onOpenSmartPlaylist(SmartPlaylistType.RECENTLY_PLAYED) },
                )
                DividerItem()
            }
            item { SectionHeader(title = "Your playlists") }
            if (libraryState.playlists.isEmpty()) {
                item {
                    EmptyState(
                        title = "No playlists yet",
                        message = "Create a playlist to save custom local queues.",
                    )
                }
            } else {
                itemsIndexed(libraryState.playlists, key = { _, item -> item.id }) { _, playlist ->
                    PlaylistRow(
                        playlist = playlist,
                        trailingContent = {
                            PlaylistOverflowMenu(
                                onRename = { renameTarget = playlist },
                                onDelete = { deleteTarget = playlist },
                            )
                        },
                        onClick = { onOpenPlaylist(playlist.id) },
                    )
                    DividerItem()
                }
            }
        }
    }

    if (createDialogVisible) {
        PlaylistNameDialog(
            title = "Create playlist",
            initialValue = "",
            confirmLabel = "Create",
            onDismiss = { createDialogVisible = false },
            onConfirm = { name ->
                onCreatePlaylist(name)
                createDialogVisible = false
            },
        )
    }

    renameTarget?.let { playlist ->
        PlaylistNameDialog(
            title = "Rename playlist",
            initialValue = playlist.name,
            confirmLabel = "Save",
            onDismiss = { renameTarget = null },
            onConfirm = { name ->
                onRenamePlaylist(playlist.id, name)
                renameTarget = null
            },
        )
    }

    deleteTarget?.let { playlist ->
        AlertDialog(
            onDismissRequest = { deleteTarget = null },
            title = { Text("Delete playlist?") },
            text = { Text("Delete \"${playlist.name}\"? This removes the playlist but not its audio files.") },
            confirmButton = {
                Button(
                    onClick = {
                        val target = deleteTarget ?: return@Button
                        deleteTarget = null
                        confirmPlaylistDeletion(target, onDeletePlaylist)
                    },
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteTarget = null }) {
                    Text("Cancel")
                }
            },
        )
    }
}

internal fun confirmPlaylistDeletion(
    playlist: UserPlaylist,
    onDeletePlaylist: (Long) -> Unit,
) {
    onDeletePlaylist(playlist.id)
}

package com.libreplayer.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Favorite
import androidx.compose.material.icons.filled.FavoriteBorder
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QueueMusic
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import com.libreplayer.data.repository.AppSettings
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.media.playback.queueOccurrenceKey
import com.libreplayer.ui.components.ArtworkVariant
import com.libreplayer.ui.components.ArtworkThumbnail
import com.libreplayer.ui.components.DividerItem
import com.libreplayer.ui.components.EmptyState
import com.libreplayer.ui.components.SongRow
import com.libreplayer.util.formatDuration
import com.libreplayer.util.formatRemainingDuration

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NowPlayingScreen(
    playbackState: PlaybackUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeatMode: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenAudioDetails: (String) -> Unit,
) {
    val song = playbackState.currentSong
    if (song == null) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Now Playing") },
                    navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                )
            },
        ) { padding ->
            EmptyState(
                title = "Nothing loaded",
                message = "Pick a song from your local library to start playback.",
                modifier = Modifier.padding(padding),
            )
        }
        return
    }

    var sliderValue by remember(song.id) { mutableFloatStateOf(playbackState.positionMs.toFloat()) }
    var dragging by remember { mutableStateOf(false) }

    LaunchedEffect(playbackState.positionMs, song.id, dragging) {
        if (!dragging) {
            sliderValue = playbackState.positionMs.toFloat()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Now Playing") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    NowPlayingMoreMenu(
                        onOpenAudioDetails = { onOpenAudioDetails(song.id) },
                    )
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            ArtworkThumbnail(
                artworkUri = song.artworkUri,
                fallbackArtworkUri = song.contentUri,
                fallbackText = displayTitle(song, settings),
                variant = ArtworkVariant.FULL,
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .heightIn(max = 320.dp),
            )
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = displayTitle(song, settings),
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = "${song.resolvedArtist} - ${song.resolvedAlbum}",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                playbackState.errorMessage?.let { message ->
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
            }
            Slider(
                value = sliderValue.coerceAtLeast(0f),
                onValueChange = {
                    dragging = true
                    sliderValue = it
                },
                onValueChangeFinished = {
                    onSeekTo(sliderValue.toLong())
                    dragging = false
                },
                valueRange = 0f..playbackState.durationMs.coerceAtLeast(1L).toFloat(),
                modifier = Modifier.fillMaxWidth(),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text(formatDuration(sliderValue.toLong()))
                Text(formatRemainingDuration(sliderValue.toLong(), playbackState.durationMs))
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        Icons.Filled.Shuffle,
                        contentDescription = "Toggle shuffle",
                        tint = if (playbackState.shuffleEnabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                }
                IconButton(onClick = onSkipPrevious) {
                    Icon(Icons.Filled.SkipPrevious, contentDescription = "Previous")
                }
                Button(onClick = onTogglePlayPause) {
                    Icon(
                        imageVector = if (playbackState.isPlaying) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                        contentDescription = if (playbackState.isPlaying) "Pause" else "Play",
                    )
                    Spacer(modifier = Modifier.size(8.dp))
                    Text(if (playbackState.isPlaying) "Pause" else "Play")
                }
                IconButton(onClick = onSkipNext) {
                    Icon(Icons.Filled.SkipNext, contentDescription = "Next")
                }
                IconButton(onClick = onCycleRepeatMode) {
                    Icon(
                        imageVector = when (playbackState.repeatMode) {
                            Player.REPEAT_MODE_ONE -> Icons.Filled.RepeatOne
                            else -> Icons.Filled.Repeat
                        },
                        contentDescription = "Repeat mode",
                        tint = if (playbackState.repeatMode == Player.REPEAT_MODE_OFF) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.primary
                        },
                    )
                }
            }
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onToggleFavorite) {
                    Icon(
                        imageVector = if (song.isFavorite) Icons.Filled.Favorite else Icons.Filled.FavoriteBorder,
                        contentDescription = if (song.isFavorite) "Remove favorite" else "Add favorite",
                    )
                }
                IconButton(onClick = onOpenQueue) {
                    Icon(Icons.Filled.QueueMusic, contentDescription = "Open queue")
                }
            }
        }
    }
}

@Composable
private fun NowPlayingMoreMenu(
    onOpenAudioDetails: () -> Unit,
) {
    var expanded by remember { mutableStateOf(false) }
    androidx.compose.foundation.layout.Box {
        IconButton(onClick = { expanded = true }) {
            Icon(Icons.Filled.MoreVert, contentDescription = "More actions")
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(
                text = { Text("Audio details") },
                onClick = {
                    onOpenAudioDetails()
                    expanded = false
                },
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlaybackQueueScreen(
    playbackState: PlaybackUiState,
    settings: AppSettings,
    onBack: () -> Unit,
    onPlaySongAt: (Int) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Queue") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(scaffoldPadding = padding)
        if (playbackState.queue.isEmpty()) {
            EmptyState(
                title = "Queue is empty",
                message = "Start playback from the library to see the current queue.",
                modifier = Modifier.padding(contentPadding),
            )
        } else {
            LazyColumn(contentPadding = contentPadding) {
                itemsIndexed(
                    playbackState.queue,
                    key = { index, song -> queueOccurrenceKey(index, song.id) },
                ) { index, song ->
                    SongRow(
                        title = displayTitle(song, settings),
                        subtitle = displaySubtitle(song),
                        supportingText = if (index == playbackState.currentIndex) "Playing now" else null,
                        durationMs = song.durationMs,
                        artworkUri = song.artworkUri,
                        fallbackArtworkUri = song.contentUri,
                        onClick = { onPlaySongAt(index) },
                    )
                    DividerItem()
                }
            }
        }
    }
}


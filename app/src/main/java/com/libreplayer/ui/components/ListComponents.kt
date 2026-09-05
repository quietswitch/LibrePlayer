package com.libreplayer.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.libreplayer.data.repository.Album
import com.libreplayer.data.repository.ArtworkCandidate
import com.libreplayer.data.repository.Artist
import com.libreplayer.data.repository.PlaybackUiState
import com.libreplayer.data.repository.UserPlaylist
import com.libreplayer.util.formatDuration

@Composable
fun SongRow(
    title: String,
    subtitle: String,
    durationMs: Long,
    artworkUri: String?,
    fallbackArtworkUri: String? = null,
    artworkCandidates: List<ArtworkCandidate> = emptyList(),
    sourceRevisionEpochSeconds: Long = 0L,
    modifier: Modifier = Modifier,
    supportingText: String? = null,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ArtworkThumbnail(
            artworkUri = artworkUri,
            fallbackArtworkUri = fallbackArtworkUri,
            artworkCandidates = artworkCandidates,
            sourceRevisionEpochSeconds = sourceRevisionEpochSeconds,
            fallbackText = title,
            variant = ArtworkVariant.LIST,
            modifier = Modifier.size(56.dp),
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = subtitle,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (!supportingText.isNullOrBlank()) {
                Text(
                    text = supportingText,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = formatDuration(durationMs),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (trailingContent != null) {
            Spacer(modifier = Modifier.width(8.dp))
            trailingContent()
        }
    }
}

@Composable
fun AlbumRow(
    album: Album,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SongRow(
        title = album.title,
        subtitle = buildString {
            append(album.artist ?: "Unknown artist")
            append(" - ")
            append("${album.songCount} songs")
        },
        durationMs = album.totalDurationMs,
        artworkUri = album.artworkUri,
        fallbackArtworkUri = album.artworkFallbackUri,
        artworkCandidates = album.artworkCandidates,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
fun ArtistRow(
    artist: Artist,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    SongRow(
        title = artist.name,
        subtitle = "${artist.songCount} songs",
        durationMs = artist.totalDurationMs,
        artworkUri = artist.artworkUri,
        fallbackArtworkUri = artist.artworkFallbackUri,
        artworkCandidates = artist.artworkCandidates,
        modifier = modifier,
        onClick = onClick,
    )
}

@Composable
fun PlaylistRow(
    playlist: UserPlaylist,
    modifier: Modifier = Modifier,
    trailingContent: @Composable (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Surface(
            modifier = Modifier.size(56.dp),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
            Box(contentAlignment = Alignment.Center) {
                Text(
                    text = playlist.name.take(1).uppercase(),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = playlist.name,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${playlist.songCount} songs",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        trailingContent?.invoke()
    }
}

@Composable
fun EmptyState(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(text = title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (action != null) {
                Spacer(modifier = Modifier.height(8.dp))
                action()
            }
        }
    }
}

@Composable
fun SectionHeader(
    title: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleMedium,
            modifier = Modifier.weight(1f),
        )
        action?.invoke()
    }
}

@Composable
fun DividerItem() {
    HorizontalDivider(modifier = Modifier.padding(start = 84.dp))
}

@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    onOpenNowPlaying: () -> Unit,
    onTogglePlayPause: () -> Unit,
    onSkipNext: () -> Unit,
) {
    val currentSong = state.currentSong ?: return
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .semantics { contentDescription = "Mini player" }
            .clickable(onClick = onOpenNowPlaying),
        shape = RoundedCornerShape(18.dp),
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ArtworkThumbnail(
                artworkUri = currentSong.artworkUri,
                fallbackArtworkUri = currentSong.contentUri,
                sourceRevisionEpochSeconds = currentSong.dateModifiedEpochSeconds,
                fallbackText = currentSong.resolvedTitle,
                variant = ArtworkVariant.MINI,
                modifier = Modifier.size(48.dp),
            )
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = currentSong.resolvedTitle,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    text = state.errorMessage ?: currentSong.resolvedArtist,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (state.errorMessage == null) {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    } else {
                        MaterialTheme.colorScheme.error
                    },
                )
            }
            IconButton(onClick = onTogglePlayPause) {
                Icon(
                    imageVector = if (state.primaryControlShowsPause) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                    contentDescription = if (state.primaryControlShowsPause) "Pause" else "Play",
                )
            }
            IconButton(onClick = onSkipNext) {
                Icon(
                    imageVector = Icons.Filled.SkipNext,
                    contentDescription = "Next track",
                )
            }
        }
    }
}

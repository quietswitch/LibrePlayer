package com.libreplayer.ui.screens

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.libreplayer.library.details.AudioDetails
import com.libreplayer.library.details.AudioDetailsFormatter
import com.libreplayer.ui.components.EmptyState
import com.libreplayer.ui.components.SectionHeader
import com.libreplayer.util.AudioPermissionAction
import com.libreplayer.util.audioReadPermission
import com.libreplayer.util.findActivity
import com.libreplayer.util.formatDuration
import com.libreplayer.util.hasAudioReadPermission
import com.libreplayer.util.openAppSettings
import com.libreplayer.util.resolveAudioPermissionAction

@Composable
fun AudioDetailsRoute(
    songId: String,
    onBack: () -> Unit,
) {
    val decodedSongId = Uri.decode(songId)
    val requiresAudioPermission = decodedSongId.startsWith("media:")
    val viewModel: AudioDetailsViewModel = viewModel(factory = AudioDetailsViewModel.factory(decodedSongId))
    val state by viewModel.state.collectAsStateWithLifecycle()
    AudioDetailsScreen(
        state = state,
        requiresAudioPermission = requiresAudioPermission,
        onBack = onBack,
        onRefresh = viewModel::refresh,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AudioDetailsScreen(
    state: AudioDetailsScreenState,
    requiresAudioPermission: Boolean,
    onBack: () -> Unit,
    onRefresh: () -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val permission = audioReadPermission()
    val activity = context.findActivity()
    var hasRequestedPermission by rememberSaveable { mutableStateOf(false) }
    var deniedRequestCount by rememberSaveable { mutableStateOf(0) }
    var resumeSignal by rememberSaveable { mutableIntStateOf(0) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted ->
        if (granted) {
            deniedRequestCount = 0
            onRefresh()
        } else {
            deniedRequestCount++
        }
    }
    val hasAudioPermission = hasAudioReadPermission(context)
    val permissionAction = resolveAudioPermissionAction(
        hasPermission = hasAudioPermission,
        deniedRequestCount = deniedRequestCount,
        shouldShowRationale = activity?.shouldShowRequestPermissionRationale(permission) == true,
    )
    val details = state.details

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

    LaunchedEffect(requiresAudioPermission, permissionAction, resumeSignal) {
        if (!requiresAudioPermission || details != null) return@LaunchedEffect
        when {
            hasAudioReadPermission(context) -> onRefresh()
            permissionAction == AudioPermissionAction.REQUEST && !hasRequestedPermission -> requestPermission()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Audio Details") },
                navigationIcon = { TextButton(onClick = onBack) { Text("Back") } },
                actions = {
                    if (details != null) {
                        CopyDetailsButton(details = details)
                        ShareDetailsButton(details = details)
                    }
                },
            )
        },
    ) { padding ->
        val contentPadding = secondaryScreenContentPadding(
            scaffoldPadding = padding,
            extraBottom = 24.dp,
        )
        when {
            state.isLoading -> {
                EmptyState(
                    title = "Inspecting track",
                    message = "Reading technical file properties and metadata from local storage.",
                    modifier = Modifier.padding(contentPadding),
                )
            }

            requiresAudioPermission && !hasAudioPermission && details == null -> {
                EmptyState(
                    title = "Audio access needed",
                    message = if (permissionAction == AudioPermissionAction.OPEN_SETTINGS) {
                        "LibrePlayer needs audio access to inspect this MediaStore track. Open Android settings and allow Music and audio access, then return here."
                    } else if (deniedRequestCount > 0) {
                        "LibrePlayer still needs audio access to inspect this MediaStore track. You can retry here before opening Android settings."
                    } else {
                        "LibrePlayer needs audio access to inspect this MediaStore track. Grant access to load the available file details."
                    },
                    modifier = Modifier.padding(contentPadding),
                    action = {
                        TextButton(
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
            }

            state.errorMessage != null -> {
                EmptyState(
                    title = "Details unavailable",
                    message = state.errorMessage,
                    modifier = Modifier.padding(contentPadding),
                    action = {
                        TextButton(onClick = onRefresh) {
                            Text("Try again")
                        }
                    },
                )
            }

            details != null -> {
                LazyColumn(
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    item { SummarySection(details = details) }
                    item {
                        DetailsSection(title = "Audio") {
                            DetailsRow("Format", AudioDetailsFormatter.formatDisplayLabel(details.format))
                            DetailsRow("Container", details.format.containerLabel ?: "Unknown")
                            DetailsRow("Extension", AudioDetailsFormatter.formatExtension(details.format.extension))
                            DetailsRow("Sample rate", AudioDetailsFormatter.formatSampleRate(details.sampleRateHz))
                            DetailsRow("Bit depth", AudioDetailsFormatter.formatBitDepth(details.bitDepth))
                            DetailsRow("Bitrate", AudioDetailsFormatter.formatBitrate(details.bitrateKbps))
                            DetailsRow("Bitrate mode", details.bitrateMode.label)
                            DetailsRow("Channels", AudioDetailsFormatter.formatChannels(details.channelCount))
                            DetailsRow("Duration", details.durationMs?.let(::formatDuration) ?: "Unknown")
                            DetailsRow("File size", AudioDetailsFormatter.formatFileSize(details.fileSizeBytes))
                            DetailsRow("Encoding", AudioDetailsFormatter.encodingLabel(details.encodingKind))
                            DetailsRow("Quality", details.qualityLabel.label)
                        }
                    }
                    item {
                        DetailsSection(title = "Tags") {
                            DetailsRow("Title", details.title ?: "Unknown")
                            DetailsRow("Artist", details.artist ?: "Unknown")
                            DetailsRow("Album", details.album ?: "Unknown")
                            DetailsRow("Album artist", details.albumArtist ?: "Unknown")
                            DetailsRow("Track number", details.trackNumber?.toString() ?: "Unknown")
                            DetailsRow("Disc number", details.discNumber?.toString() ?: "Unknown")
                            DetailsRow("Year", details.year?.toString() ?: "Unknown")
                            DetailsRow("Genre", details.genre ?: "Unknown")
                            DetailsRow("Composer", details.composer ?: "Unknown")
                        }
                    }
                    item {
                        DetailsSection(title = "File") {
                            DetailsRow("File name", details.fileName ?: "Unknown")
                            DetailsRow("Location", details.location ?: "Unknown", multiline = true)
                            DetailsRow("Date added", AudioDetailsFormatter.formatDateAdded(details.dateAddedEpochSeconds))
                            DetailsRow("Last modified", AudioDetailsFormatter.formatDateTime(details.lastModifiedEpochMillis))
                            DetailsRow("Source type", details.sourceTypeLabel)
                            DetailsRow("Embedded artwork", AudioDetailsFormatter.artworkLabel(details.hasEmbeddedArtwork))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SummarySection(details: AudioDetails) {
    DetailsSection(title = "Summary") {
        Text(
            text = AudioDetailsFormatter.formatSummary(details).ifBlank { "Unknown format" },
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

@Composable
private fun DetailsSection(
    title: String,
    content: @Composable () -> Unit,
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
        ),
    ) {
        Column(
            modifier = Modifier.padding(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            SectionHeader(title = title)
            Column(
                modifier = Modifier.padding(horizontal = 16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                content()
            }
        }
    }
}

@Composable
private fun DetailsRow(
    label: String,
    value: String,
    multiline: Boolean = false,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(0.38f),
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.weight(0.62f),
            maxLines = if (multiline) Int.MAX_VALUE else 3,
            overflow = if (multiline) TextOverflow.Clip else TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun CopyDetailsButton(details: AudioDetails) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(
        onClick = {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(
                ClipData.newPlainText("Audio details", AudioDetailsFormatter.plainText(details)),
            )
            Toast.makeText(context, "Details copied", Toast.LENGTH_SHORT).show()
        },
    ) {
        Icon(
            imageVector = Icons.Filled.ContentCopy,
            contentDescription = "Copy details",
        )
    }
}

@Composable
private fun ShareDetailsButton(details: AudioDetails) {
    val context = androidx.compose.ui.platform.LocalContext.current
    IconButton(
        onClick = {
            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_SUBJECT, details.fileName ?: details.title ?: "Audio details")
                putExtra(Intent.EXTRA_TEXT, AudioDetailsFormatter.plainText(details))
            }
            context.startActivity(Intent.createChooser(shareIntent, "Share audio details"))
        },
    ) {
        Icon(
            imageVector = Icons.Filled.Share,
            contentDescription = "Share details",
        )
    }
}

package com.libreplayer.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.LruCache
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.sp
import com.libreplayer.data.repository.ArtworkCandidate
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext

@Composable
internal fun ArtworkThumbnail(
    artworkUri: String?,
    fallbackArtworkUri: String? = null,
    artworkCandidates: List<ArtworkCandidate> = emptyList(),
    sourceRevisionEpochSeconds: Long = 0L,
    fallbackText: String,
    variant: ArtworkVariant = ArtworkVariant.LIST,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(
        artworkUri,
        fallbackArtworkUri,
        artworkCandidates,
        sourceRevisionEpochSeconds,
        variant,
    ) {
        val candidates = if (artworkCandidates.isNotEmpty()) {
            ArtworkSourceResolver.mergeCandidates(artworkCandidates)
        } else {
            ArtworkSourceResolver.selectCandidates(
                artworkUri = artworkUri,
                fallbackArtworkUri = fallbackArtworkUri,
                sourceRevisionEpochSeconds = sourceRevisionEpochSeconds,
            )
        }
        ArtworkRequest(
            candidates = candidates,
            variant = variant,
        )
    }
    val result by produceState<ArtworkLoadResult>(initialValue = ArtworkLoadResult.Loading, request) {
        value = ArtworkLoader.load(context, request)
    }
    val bitmap = (result as? ArtworkLoadResult.Loaded)?.bitmap

    if (bitmap != null) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = null,
            modifier = modifier,
            contentScale = ContentScale.Crop,
        )
    } else {
        Box(
            modifier = modifier.background(MaterialTheme.colorScheme.surfaceContainerHighest),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = fallbackText.take(1).uppercase(),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                fontSize = 24.sp,
            )
        }
    }
}

internal sealed interface ArtworkLoadResult {
    data object Loading : ArtworkLoadResult
    data object Missing : ArtworkLoadResult
    data object Failed : ArtworkLoadResult
    data class Loaded(
        val bitmap: Bitmap,
        val candidate: ArtworkCandidate,
    ) : ArtworkLoadResult
}

internal object ArtworkLoader {
    internal val maxCacheBytes: Int =
        (Runtime.getRuntime().maxMemory() / CACHE_MEMORY_DIVISOR)
            .coerceIn(MIN_CACHE_BYTES.toLong(), MAX_CACHE_BYTES.toLong())
            .toInt()

    private val cache = object : LruCache<String, CachedArtwork>(maxCacheBytes) {
        override fun sizeOf(key: String, value: CachedArtwork): Int =
            value.bitmap.allocationByteCount.coerceAtLeast(1)
    }

    suspend fun load(context: Context, request: ArtworkRequest): ArtworkLoadResult {
        if (request.candidates.isEmpty()) return ArtworkLoadResult.Missing
        cache.get(request.cacheKey)?.let { cached ->
            return ArtworkLoadResult.Loaded(cached.bitmap, cached.candidate)
        }

        return withContext(Dispatchers.IO) {
            request.candidates.forEach { candidate ->
                coroutineContext.ensureActive()
                val uri = Uri.parse(candidate.uri)
                val bitmap = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    try {
                        context.contentResolver.loadThumbnail(
                            uri,
                            android.util.Size(request.variant.targetSizePx, request.variant.targetSizePx),
                            null,
                        )
                    } catch (exception: CancellationException) {
                        throw exception
                    } catch (_: Exception) {
                        embeddedBitmapOrNull(context, uri, request.variant.targetSizePx)
                    }
                } else {
                    embeddedBitmapOrNull(context, uri, request.variant.targetSizePx)
                }
                if (bitmap != null) {
                    cache.put(request.cacheKey, CachedArtwork(bitmap, candidate))
                    return@withContext ArtworkLoadResult.Loaded(bitmap, candidate)
                }
            }
            ArtworkLoadResult.Failed
        }
    }

    internal fun clearMemoryCache() = cache.evictAll()

    internal fun cacheSizeBytes(): Int = cache.size()

    private fun embeddedBitmapOrNull(
        context: Context,
        uri: Uri,
        targetSizePx: Int,
    ): Bitmap? =
        try {
            embeddedBitmap(context, uri, targetSizePx)
        } catch (exception: CancellationException) {
            throw exception
        } catch (_: Exception) {
            null
        }

    private fun embeddedBitmap(
        context: Context,
        uri: Uri,
        targetSizePx: Int,
    ): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(context, uri)
            retriever.embeddedPicture?.let { bytes ->
                decodeScaledBitmap(bytes, targetSizePx)
            }
        } finally {
            retriever.release()
        }
    }

    private fun decodeScaledBitmap(
        bytes: ByteArray,
        targetSizePx: Int,
    ): Bitmap? {
        val bounds = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val options = BitmapFactory.Options().apply {
            inSampleSize = ArtworkDecodePolicy.calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetSizePx = targetSizePx,
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private const val CACHE_MEMORY_DIVISOR = 16L
    private const val MIN_CACHE_BYTES = 4 * 1024 * 1024
    private const val MAX_CACHE_BYTES = 24 * 1024 * 1024

    private data class CachedArtwork(
        val bitmap: Bitmap,
        val candidate: ArtworkCandidate,
    )
}

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
internal fun ArtworkThumbnail(
    artworkUri: String?,
    fallbackArtworkUri: String? = null,
    fallbackText: String,
    variant: ArtworkVariant = ArtworkVariant.LIST,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val request = remember(artworkUri, fallbackArtworkUri, variant) {
        ArtworkRequest(
            artworkUri = artworkUri,
            fallbackArtworkUri = fallbackArtworkUri,
            variant = variant,
        )
    }
    val bitmap by produceState<Bitmap?>(initialValue = null, request) {
        value = ArtworkLoader.load(context, request)
    }

    if (bitmap != null) {
        Image(
            bitmap = bitmap!!.asImageBitmap(),
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

private object ArtworkLoader {
    private val cache = LruCache<String, Bitmap>(64)

    suspend fun load(context: Context, request: ArtworkRequest): Bitmap? {
        if (request.candidateUris.isEmpty()) return null
        cache.get(request.cacheKey)?.let { return it }

        return withContext(Dispatchers.IO) {
            val bitmap = request.candidateUris.firstNotNullOfOrNull { candidate ->
                val uri = Uri.parse(candidate)
                runCatching {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        context.contentResolver.loadThumbnail(
                            uri,
                            android.util.Size(request.variant.targetSizePx, request.variant.targetSizePx),
                            null,
                        )
                    } else {
                        embeddedBitmap(context, uri, request.variant.targetSizePx)
                    }
                }.recoverCatching {
                    embeddedBitmap(context, uri, request.variant.targetSizePx)
                }.getOrNull()
            }

            if (bitmap != null) {
                cache.put(request.cacheKey, bitmap)
            }
            bitmap
        }
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
            inSampleSize = calculateInSampleSize(
                width = bounds.outWidth,
                height = bounds.outHeight,
                targetSizePx = targetSizePx,
            )
        }
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
    }

    private fun calculateInSampleSize(
        width: Int,
        height: Int,
        targetSizePx: Int,
    ): Int {
        if (width <= 0 || height <= 0 || targetSizePx <= 0) return 1
        var sampleSize = 1
        var sampledWidth = width
        var sampledHeight = height
        while (sampledWidth / 2 >= targetSizePx && sampledHeight / 2 >= targetSizePx) {
            sampleSize *= 2
            sampledWidth /= 2
            sampledHeight /= 2
        }
        return sampleSize.coerceAtLeast(1)
    }
}

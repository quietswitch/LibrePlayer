package com.libreplayer.library.scanner

import android.os.Build
import android.provider.MediaStore
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DeviceLibraryScannerProjectionTest {
    @Test
    fun `pre Android 10 projection excludes relative path`() {
        val projection = mediaStoreAudioProjection(Build.VERSION_CODES.P)

        assertThat(projection.toList()).doesNotContain(MediaStore.Audio.Media.RELATIVE_PATH)
        assertThat(projection.toList()).doesNotContain(MediaStore.MediaColumns.GENERATION_MODIFIED)
        assertThat(projection.toList()).contains(MediaStore.Audio.Media._ID)
    }

    @Test
    fun `Android 10 projection includes relative path`() {
        val projection = mediaStoreAudioProjection(Build.VERSION_CODES.Q)

        assertThat(projection.toList()).contains(MediaStore.Audio.Media.RELATIVE_PATH)
    }

    @Test
    fun `Android 11 projection includes generation modified`() {
        val projection = mediaStoreAudioProjection(Build.VERSION_CODES.R)

        assertThat(projection.toList()).contains(MediaStore.MediaColumns.GENERATION_MODIFIED)
    }
}

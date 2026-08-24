package com.libreplayer.library.scanner

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class IncrementalScannerPolicyTest {
    @Test
    fun `matching generation skips MediaStore metadata query`() {
        val checkpoint = MediaStoreCheckpoint("version", 10L)

        assertThat(decideGenerationSync(checkpoint, checkpoint, forceFull = false))
            .isEqualTo(GenerationSyncDecision.UNCHANGED)
    }

    @Test
    fun `higher generation requests changed rows`() {
        assertThat(
            decideGenerationSync(
                previous = MediaStoreCheckpoint("version", 10L),
                current = MediaStoreCheckpoint("version", 11L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.INCREMENTAL)
    }

    @Test
    fun `version change and generation reset force full reconciliation`() {
        assertThat(
            decideGenerationSync(
                previous = MediaStoreCheckpoint("old", 10L),
                current = MediaStoreCheckpoint("new", 1L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.FULL)
    }

    @Test
    fun `missing checkpoint after database restart forces full reconciliation`() {
        assertThat(
            decideGenerationSync(
                previous = null,
                current = MediaStoreCheckpoint("version", 10L),
                forceFull = false,
            ),
        ).isEqualTo(GenerationSyncDecision.FULL)
    }

    @Test
    fun `incremental merge adds modifies and deletes`() {
        val cachedOne = song("media:1", title = "One")
        val cachedTwo = song("media:2", title = "Old")
        val modifiedTwo = song("media:2", title = "New")
        val addedThree = song("media:3", title = "Three")

        val merged = mergeIncrementalMediaStoreSongs(
            cachedSongs = listOf(cachedOne, cachedTwo),
            changedSongs = listOf(modifiedTwo, addedThree),
            currentIds = setOf("media:2", "media:3"),
        )

        assertThat(merged.map(ScannedSong::id)).containsExactly("media:2", "media:3").inOrder()
        assertThat(merged.first { it.id == "media:2" }.title).isEqualTo("New")
    }

    @Test
    fun `unchanged SAF document reuses cached metadata`() {
        val cached = song(
            id = "document:content://tree/song",
            title = "Cached title",
            sourceType = SongSourceType.DOCUMENT,
            modified = 123L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 123L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isTrue()
    }

    @Test
    fun `SAF provider without modification time is reparsed for correctness`() {
        val cached = song(
            id = "document:content://tree/song",
            sourceType = SongSourceType.DOCUMENT,
            modified = 0L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 0L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isFalse()
    }

    @Test
    fun `changed SAF modification time reparses metadata`() {
        val cached = song(
            id = "document:content://tree/song",
            sourceType = SongSourceType.DOCUMENT,
            modified = 10L,
        )
        val descriptor = DocumentDescriptor(
            id = cached.id,
            contentUri = cached.contentUri,
            displayName = cached.displayName,
            relativePath = cached.relativePath,
            mimeType = cached.mimeType,
            dateModifiedEpochSeconds = 11L,
        )

        assertThat(canReuseCachedDocument(cached, descriptor)).isFalse()
    }
}

private fun song(
    id: String,
    title: String = id,
    sourceType: SongSourceType = SongSourceType.MEDIA_STORE,
    modified: Long = 1L,
) = ScannedSong(
    id = id,
    sourceType = sourceType,
    contentUri = id.removePrefix("document:"),
    title = title,
    artist = "Artist",
    album = "Album",
    durationMs = 60_000L,
    trackNumber = 1,
    discNumber = 1,
    year = 2026,
    dateAddedEpochSeconds = 1L,
    dateModifiedEpochSeconds = modified,
    displayName = "$id.mp3",
    relativePath = "/Music/$id.mp3",
    mimeType = "audio/mpeg",
    artworkUri = null,
)

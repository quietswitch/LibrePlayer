package com.libreplayer.library.semantics

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class LibraryMetadataSemanticsTest {
    @Test
    fun `MediaStore track field decodes positive track and disc components`() {
        assertThat(decodeMediaStoreTrackNumber(-1)).isEqualTo(TrackDiscNumbers(null, null))
        assertThat(decodeMediaStoreTrackNumber(0)).isEqualTo(TrackDiscNumbers(null, null))
        assertThat(decodeMediaStoreTrackNumber(1)).isEqualTo(TrackDiscNumbers(1, null))
        assertThat(decodeMediaStoreTrackNumber(1_000)).isEqualTo(TrackDiscNumbers(null, 1))
        assertThat(decodeMediaStoreTrackNumber(1_001)).isEqualTo(TrackDiscNumbers(1, 1))
        assertThat(decodeMediaStoreTrackNumber(2_010)).isEqualTo(TrackDiscNumbers(10, 2))
    }

    @Test
    fun `metadata ordinals accept n and n of total without inventing zero`() {
        assertThat(parseMetadataOrdinal("1")).isEqualTo(1)
        assertThat(parseMetadataOrdinal(" 1 / 12 ")).isEqualTo(1)
        assertThat(parseMetadataOrdinal("01/12")).isEqualTo(1)
        assertThat(parseMetadataOrdinal("1/0")).isEqualTo(1)
        assertThat(parseMetadataOrdinal(null)).isNull()
        assertThat(parseMetadataOrdinal("")).isNull()
        assertThat(parseMetadataOrdinal(" \t\n ")).isNull()
        assertThat(parseMetadataOrdinal("0/12")).isNull()
        assertThat(parseMetadataOrdinal("-1")).isNull()
        assertThat(parseMetadataOrdinal("side-a")).isNull()
        assertThat(parseMetadataOrdinal("999999999999999999999999/12")).isNull()
    }

    @Test
    fun `year parser preserves only explicit year precision`() {
        assertThat(parseMetadataYear("2024")).isEqualTo(2024)
        assertThat(parseMetadataYear(" 1999 ")).isEqualTo(1999)
        assertThat(parseMetadataYear(null)).isNull()
        assertThat(parseMetadataYear("0")).isNull()
        assertThat(parseMetadataYear("-1")).isNull()
        assertThat(parseMetadataYear("9999")).isEqualTo(9999)
        assertThat(parseMetadataYear("10000")).isNull()
        assertThat(parseMetadataYear("999999999999999999999999")).isNull()
        assertThat(parseMetadataYear("2024-05-06")).isNull()
        assertThat(parseMetadataYear("unknown")).isNull()
        assertThat(normalizeMetadataYear(Long.MIN_VALUE)).isNull()
        assertThat(normalizeMetadataYear(2026L)).isEqualTo(2026)
        assertThat(normalizeMetadataYear(Long.MAX_VALUE)).isNull()
    }

    @Test
    fun `presentation fallbacks do not rewrite supplied metadata`() {
        assertThat(resolvedSongTitle(null, "File Name.flac")).isEqualTo("File Name")
        assertThat(resolvedTrackArtist(" ")).isEqualTo(UNKNOWN_ARTIST)
        assertThat(resolvedAlbumTitle(null)).isEqualTo(UNKNOWN_ALBUM)
        assertThat(resolvedTrackArtist("Unknown Artist")).isEqualTo("Unknown Artist")
    }

    @Test
    fun `album identity separates unrelated artists and honors album artist when available`() {
        assertThat(albumGroupingKey("Shared", null, "Artist A"))
            .isNotEqualTo(albumGroupingKey("Shared", null, "Artist B"))
        assertThat(albumGroupingKey("Compilation", "Various Artists", "Artist A"))
            .isEqualTo(albumGroupingKey("Compilation", "Various Artists", "Artist B"))
        assertThat(albumGroupingKey("Album", null, "Track Artist"))
            .isEqualTo(albumGroupingKey(" album ", null, " track artist "))
    }

    @Test
    fun `grouping is deterministic but not fuzzy`() {
        assertThat(artistGroupingKey("The Artist"))
            .isEqualTo(artistGroupingKey(" the artist "))
        assertThat(artistGroupingKey("Artist and Friend"))
            .isNotEqualTo(artistGroupingKey("Artist & Friend"))
    }
}

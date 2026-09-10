package com.libreplayer.data.repository

import com.libreplayer.data.database.LegacySourceRow
import com.libreplayer.data.database.classifyLegacySources
import com.libreplayer.library.scanner.ScannedSong
import com.libreplayer.library.scanner.SourceIdentity
import com.libreplayer.library.scanner.SourceObservation
import com.libreplayer.library.scanner.SourceScan
import com.libreplayer.library.scanner.MediaStoreCheckpoint
import com.libreplayer.data.database.entity.LibrarySourceEntity

internal object ProvenanceFixtures {
    val primary = SourceIdentity.media("external_primary")
    val secondary = SourceIdentity.media("1234-abcd")
    val rootA = SourceIdentity.root("content://provider.a/tree/rootA")
    val rootB = SourceIdentity.root("content://provider.a/tree/rootB")
    val rootOtherProvider = SourceIdentity.root("content://provider.b/tree/rootA")

    fun media(volume: String = "external_primary", item: String = "42") = song(
        "media:$item", "content://media/$volume/audio/media/$item", SongSourceType.MEDIA_STORE,
    )

    fun document(authority: String = "provider.a", root: String = "rootA", item: String = "opaque%3AX") = song(
        "document:content://$authority/tree/$root/document/$item",
        "content://$authority/tree/$root/document/$item", SongSourceType.DOCUMENT,
    )

    private fun song(id: String, uri: String, type: SongSourceType) = ScannedSong(
        id, type, uri, "Identical", "Artist", "Album", 60_000, 1, 1, 2026, 1, 1,
        "same.mp3", "Music/", "audio/mpeg", uri,
    )

    fun legacy(): SourceState {
        val songs = listOf(
            media().asEntity(true),
            media("external", "43").asEntity(true),
            document().asEntity(false),
            media(item = "44").asEntity(false),
            media(item = "45").asEntity(false),
        )
        val classified = classifyLegacySources(songs.map { LegacySourceRow(it.id, it.sourceType, it.contentUri) }, listOf(rootA.locator))
        return SourceState(songs, classified.sources, classified.memberships, classified.protections)
    }

    fun complete(source: LibrarySourceEntity, vararg songs: ScannedSong, generation: Long = 10, version: String = "epoch-A") =
        SourceScan.Complete(source, songs.map { song ->
            SourceObservation(
                if (source.kind == SourceIdentity.DOCUMENT) checkNotNull(SourceIdentity.documentKey(song.contentUri))
                else checkNotNull(SourceIdentity.mediaItem(song.contentUri)).second,
                song,
            )
        }, if (source.kind == SourceIdentity.MEDIA) MediaStoreCheckpoint(version, generation) else null)

    fun empty() = SourceState(emptyList(), emptyList(), emptyList(), emptyList())
}

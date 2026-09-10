package com.libreplayer.data.repository

import androidx.room.withTransaction
import com.libreplayer.data.database.AppDatabase
import com.libreplayer.data.database.entity.LegacySongProtectionEntity
import com.libreplayer.data.database.entity.LibrarySourceEntity
import com.libreplayer.data.database.entity.SongEntity
import com.libreplayer.data.database.entity.SongSourceEntity
import com.libreplayer.library.scanner.SourceIdentity
import com.libreplayer.library.scanner.SourceScan

internal data class SourceState(
    val songs: List<SongEntity>,
    val sources: List<LibrarySourceEntity>,
    val memberships: List<SongSourceEntity>,
    val protections: List<LegacySongProtectionEntity>,
)

internal fun reconcileSource(state: SourceState, scan: SourceScan, now: Long): SourceState {
    if (scan is SourceScan.Unavailable) return state
    val observations = (scan as? SourceScan.Complete)?.observations.orEmpty()
    require(observations.map { it.itemKey }.distinct().size == observations.size)
    val previous = state.sources.find { it.id == scan.source.id } ?: scan.source
    val checkpoint = (scan as? SourceScan.Complete)?.checkpoint
    val reset = previous.version != null && checkpoint != null &&
        (previous.version != checkpoint.version || checkpoint.generation < (previous.generation ?: 0))
    val source = previous.copy(
        incarnation = previous.incarnation + if (reset) 1 else 0,
        version = if (scan is SourceScan.Detached) previous.version else checkpoint?.version,
        generation = if (scan is SourceScan.Detached) previous.generation else checkpoint?.generation,
        reconciledAtEpochMillis = if (scan is SourceScan.Detached) previous.reconciledAtEpochMillis else now,
    )
    val songs = state.songs.associateBy { it.id }.toMutableMap()
    val protections = state.protections.associateBy { it.songId }.toMutableMap()
    val sourceById = state.sources.associateBy { it.id } + (source.id to source)
    val memberships = state.memberships.associateBy { Triple(it.sourceId, it.incarnation, it.itemKey) }.toMutableMap()
    val touched = memberships.values.filter { it.sourceId == source.id }.map { it.songId }.toMutableSet()
    memberships.replaceAll { _, member -> if (member.sourceId == source.id) member.copy(present = false) else member }
    val bySong = memberships.values.groupBy { it.songId }.mapValues { it.value.toMutableList() }.toMutableMap()
    if (reset) {
        touched.forEach { id ->
            if (bySong[id].orEmpty().none { it.present }) {
                protections[id] = LegacySongProtectionEntity(id, "epoch-discontinuity")
            }
        }
    }
    val eligible = memberships.values.filter { it.songId !in protections }
    val documents = eligible.filter { sourceById[it.sourceId]?.kind == SourceIdentity.DOCUMENT }.groupBy { it.itemKey }
    val physical = eligible.filter { it.present && it.physicalKey != null && sourceById[it.sourceId]?.kind != source.kind }
        .groupBy { it.physicalKey }
    val legacy = state.songs.filter { protections[it.id]?.reason == "unscoped" }.mapNotNull { song ->
        val key = if (source.kind == SourceIdentity.MEDIA) SourceIdentity.mediaItem(song.contentUri)
            ?.takeIf { it.first.id == source.id }?.second else SourceIdentity.documentKey(song.contentUri)
        key?.let { it to song.id }
    }.groupBy({ it.first }, { it.second })
    observations.forEach { observation ->
        val scanned = observation.song
        val mappingKey = Triple(source.id, source.incarnation, observation.itemKey)
        val exact = memberships[mappingKey]
        val physicalKey = SourceIdentity.physicalKey(scanned)
        val candidates = if (exact != null || reset) emptySet() else buildSet {
            // A root change is not a document change: provider-qualified document identity is exact.
            if (source.kind == SourceIdentity.DOCUMENT) documents[observation.itemKey].orEmpty().forEach { add(it.songId) }
            if (physicalKey != null) physical[physicalKey].orEmpty().forEach { add(it.songId) }
            addAll(legacy[observation.itemKey].orEmpty())
        }
        val compatible = candidates.filter { candidate ->
            bySong[candidate].orEmpty().none { it.sourceId == source.id &&
                it.incarnation == source.incarnation && it.itemKey != observation.itemKey }
        }
        val id = exact?.songId ?: compatible.singleOrNull()
            ?: run {
                val base = SourceIdentity.qualifiedId(source, observation.itemKey)
                // Legacy IDs are arbitrary strings. A reserved spelling gets a deterministic,
                // framed disambiguator; the resulting alias is then persisted permanently.
                generateSequence(0L) { it + 1 }.map { attempt ->
                    if (attempt == 0L) base else "source-collision:${com.libreplayer.library.scanner.sourceKey(base, attempt.toString())}"
                }.first { it !in songs && it !in bySong }
            }
        val member = SongSourceEntity(source.id, source.incarnation, observation.itemKey, id, scanned.contentUri, physicalKey)
        memberships[mappingKey] = member
        val songMemberships = bySong.getOrPut(id) { mutableListOf() }
        songMemberships.removeAll { it.sourceId == source.id && it.incarnation == source.incarnation && it.itemKey == observation.itemKey }
        songMemberships += member
        protections.remove(id)
        touched += id
        val current = songs[id]
        // Prefer MediaStore metadata when a proven file is also visible through SAF.
        val retainMedia = source.kind == SourceIdentity.DOCUMENT && current?.sourceType == SourceIdentity.MEDIA &&
            songMemberships.any { it.present && it.contentUri == current.contentUri }
        if (!retainMedia) songs[id] = scanned.copy(id = id).asEntity(current?.isFavorite == true)
    }
    touched.forEach { id ->
        val active = bySong[id].orEmpty().filter { it.present }
        if (active.isEmpty() && id !in protections) songs.remove(id)
        else songs[id]?.let { song ->
            // An alternate root must supply its own usable grant URI after the original disappears.
            if (active.isNotEmpty() && active.none { it.contentUri == song.contentUri }) {
                val member = active.sortedBy { it.sourceId }.first()
                val kind = sourceById.getValue(member.sourceId).kind
                songs[id] = song.copy(contentUri = member.contentUri, sourceType = kind, artworkUri = member.contentUri)
            }
        }
    }
    return SourceState(songs.values.toList(), sourceById.values.toList(), memberships.values.toList(), protections.values.toList())
}

/** All authority, including the checkpoint, shares the same Room transaction. */
internal suspend fun AppDatabase.reconcileSources(scans: List<SourceScan>, now: Long = System.currentTimeMillis()) {
    for (scan in scans) {
        if (scan is SourceScan.Unavailable) continue
        withTransaction {
            val dao = sourceDao()
            val before = SourceState(songDao().getAllSongs(), dao.getSources(), dao.getMemberships(), dao.getProtections())
            val after = reconcileSource(before, scan, now)
            val oldSongs = before.songs.associateBy { it.id }
            val newIds = after.songs.map { it.id }.toSet()
            before.songs.filter { it.id !in newIds }.map { it.id }.chunked(900).forEach { songDao().deleteSongsByIds(it) }
            songDao().upsertSongs(after.songs.filter { oldSongs[it.id] != it })
            val protected = after.protections.map { it.songId }.toSet()
            val visible = after.songs.filter { it.id !in protected }
            val albums = buildAlbums(visible)
            val artists = buildArtists(visible)
            val oldAlbums = albumDao().getAllAlbums().associateBy { it.id }
            val oldArtists = artistDao().getAllArtists().associateBy { it.id }
            (oldAlbums.keys - albums.map { it.id }.toSet()).toList().chunked(900).forEach { albumDao().deleteAlbumsByIds(it) }
            (oldArtists.keys - artists.map { it.id }.toSet()).toList().chunked(900).forEach { artistDao().deleteArtistsByIds(it) }
            albumDao().upsertAlbums(albums.filter { oldAlbums[it.id] != it })
            artistDao().upsertArtists(artists.filter { oldArtists[it.id] != it })
            val oldSources = before.sources.toSet()
            val oldMemberships = before.memberships.toSet()
            val oldProtections = before.protections.toSet()
            dao.upsertSources(after.sources.filter { it !in oldSources })
            dao.upsertMemberships(after.memberships.filter { it !in oldMemberships })
            dao.upsertProtections(after.protections.filter { it !in oldProtections })
            (before.protections.map { it.songId }.toSet() - protected).toList().chunked(900).forEach { dao.deleteProtections(it) }
        }
    }
}

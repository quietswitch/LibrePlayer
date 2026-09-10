package com.libreplayer.data.repository

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.database.LegacySourceRow
import com.libreplayer.data.database.classifyLegacySources
import com.libreplayer.library.scanner.SourceIdentity
import com.libreplayer.library.scanner.SourceScan
import com.libreplayer.library.scanner.sourceKey
import com.libreplayer.data.repository.ProvenanceFixtures as F
import org.junit.Test

class SourceProvenanceTest {
    @Test fun `legacy duplicate document identities across roots remain protected`() {
        val a = F.document()
        val b = F.document(root = "rootB")
        val classified = classifyLegacySources(listOf(LegacySourceRow(a.id, SourceIdentity.DOCUMENT, a.contentUri),
            LegacySourceRow(b.id, SourceIdentity.DOCUMENT, b.contentUri)), listOf(F.rootA.locator, F.rootB.locator))
        assertThat(classified.memberships).isEmpty()
        assertThat(classified.protections.map { it.songId }).containsExactly(a.id, b.id)
    }

    @Test fun `exact platform root physical path can prove MediaStore and SAF equivalence`() {
        val media = F.media().copy(relativePath = "")
        val doc = F.document("com.android.externalstorage.documents", "primary%3A", "primary%3Asame.mp3")
        assertThat(SourceIdentity.physicalKey(media)).isEqualTo(SourceIdentity.physicalKey(doc))
    }
    @Test fun `explicit root detach preserves alias and does not advance checkpoint`() {
        var state = reconcileSource(F.empty(), F.complete(F.rootA, F.document()), 1)
        val source = state.sources.single()
        val id = state.songs.single().id
        state = reconcileSource(state, SourceScan.Detached(F.rootA), 2)
        assertThat(state.sources.single()).isEqualTo(source)
        assertThat(state.songs).isEmpty()
        assertThat(state.memberships.single().present).isFalse()
        state = reconcileSource(state, F.complete(F.rootA, F.document()), 3)
        assertThat(state.songs.single().id).isEqualTo(id)
    }

    @Test fun `legacy row reserving qualified spelling is never overwritten`() {
        val reserved = SourceIdentity.qualifiedId(F.primary, "42")
        val legacy = F.legacy()
        val state = legacy.copy(songs = legacy.songs + F.media("external", "77").asEntity(true).copy(id = reserved))
        val after = reconcileSource(state, F.complete(F.primary, F.media(item = "88")), 1)
        assertThat(after.songs.single { it.id == reserved }).isEqualTo(state.songs.last())
        val empty = F.empty().copy(songs = listOf(state.songs.last()))
        val conflict = reconcileSource(empty, F.complete(F.primary, F.media()), 1)
        assertThat(conflict.songs).hasSize(2)
        assertThat(conflict.songs.single { it.id != reserved }.id).startsWith("source-collision:")
    }
    @Test fun `migration classifies exact sources and preserves every original row`() {
        val before = F.legacy()
        assertThat(before.songs.map { it.id }).containsExactly("media:42", "media:43", F.document().id, "media:44", "media:45")
        assertThat(before.protections.single().songId).isEqualTo("media:43")
        assertThat(before.memberships.map { it.songId }).containsExactly("media:42", "media:44", "media:45", F.document().id)
        assertThat(before.sources.all { it.version == null && it.generation == null }).isTrue()
    }

    @Test fun `same numeric ID across volumes never transfers favorite or legacy reference`() {
        val before = F.legacy()
        val after = reconcileSource(before, F.complete(F.secondary, F.media("1234-abcd")), 100)
        assertThat(after.songs.first { it.id == "media:42" }).isEqualTo(before.songs.first())
        val added = after.songs.single { it.id.startsWith("media-scoped:") }
        assertThat(added.isFavorite).isFalse()
        assertThat(added.contentUri).isEqualTo("content://media/1234-abcd/audio/media/42")
        assertThat(added.id).isEqualTo(SourceIdentity.qualifiedId(F.secondary, "42"))
    }

    @Test fun `two new namespaces and equal relative paths stay distinct`() {
        var state = reconcileSource(F.empty(), F.complete(F.primary, F.media()), 100)
        state = reconcileSource(state, F.complete(F.secondary, F.media("1234-abcd")), 101)
        assertThat(state.songs).hasSize(2)
        assertThat(state.songs.map { it.id }.distinct()).hasSize(2)
        assertThat(state.memberships.map { it.sourceId }.distinct()).hasSize(2)
    }

    @Test fun `different MediaStore rows with identical paths remain distinct even through SAF`() {
        val root = SourceIdentity.root("content://com.android.externalstorage.documents/tree/primary%3AMusic")
        val document = F.document("com.android.externalstorage.documents", "primary%3AMusic", "primary%3AMusic%2Fsame.mp3")
        var state = reconcileSource(F.empty(), F.complete(root, document), 1)
        state = reconcileSource(state, F.complete(F.primary, F.media(), F.media(item = "99")), 2)
        assertThat(state.songs).hasSize(2)
        assertThat(state.memberships.filter { it.sourceId == F.primary.id }.map { it.songId }.distinct()).hasSize(2)
    }

    @Test fun `equal opaque document IDs across providers do not collapse`() {
        var state = reconcileSource(F.empty(), F.complete(F.rootA, F.document()), 1)
        state = reconcileSource(state, F.complete(F.rootOtherProvider, F.document("provider.b")), 2)
        assertThat(state.songs).hasSize(2)
    }

    @Test fun `overlapping roots share exact document and losing either grant retains song`() {
        var state = reconcileSource(F.empty(), F.complete(F.rootA, F.document()), 1)
        state = reconcileSource(state, F.complete(F.rootB, F.document(root = "rootB")), 2)
        assertThat(state.songs).hasSize(1)
        assertThat(state.memberships).hasSize(2)
        val id = state.songs.single().id
        state = reconcileSource(state, F.complete(F.rootA), 3)
        assertThat(state.songs.single().id).isEqualTo(id)
        assertThat(state.songs.single().contentUri).contains("/tree/rootB/")
        state = reconcileSource(state, F.complete(F.rootB), 4)
        assertThat(state.songs).isEmpty()
        assertThat(state.memberships.all { !it.present }).isTrue()
        state = reconcileSource(state, F.complete(F.rootA, F.document()), 5)
        assertThat(state.songs.single().id).isEqualTo(id)
    }

    @Test fun `successful absence retains exact legacy alias and reconnects old ID`() {
        var state = reconcileSource(F.legacy(), F.complete(F.primary), 1)
        assertThat(state.songs.map { it.id }).doesNotContain("media:42")
        assertThat(state.memberships.single { it.songId == "media:42" }.present).isFalse()
        state = reconcileSource(state, F.complete(F.secondary, F.media("1234-abcd")), 2)
        assertThat(state.songs.map { it.id }).doesNotContain("media:42")
        state = reconcileSource(state, F.complete(F.primary, F.media()), 3)
        assertThat(state.songs.single { it.contentUri == F.media().contentUri }.id).isEqualTo("media:42")
    }

    @Test fun `unavailable source has no authority and success is symmetric`() {
        var state = reconcileSource(F.empty(), F.complete(F.primary, F.media()), 1)
        state = reconcileSource(state, F.complete(F.secondary, F.media("1234-abcd")), 2)
        for ((failed, successful) in listOf(F.primary to F.secondary, F.secondary to F.primary)) {
            val prior = state
            state = reconcileSource(state, SourceScan.Unavailable(failed, "SecurityException"), 3)
            assertThat(state).isEqualTo(prior)
            val checkpoint = state.sources.single { it.id == failed.id }
            val failedMembers = state.memberships.filter { it.sourceId == failed.id }
            state = reconcileSource(state, F.complete(successful, F.media(successful.locator), generation = 11), 4)
            assertThat(state.sources.single { it.id == failed.id }).isEqualTo(checkpoint)
            assertThat(state.memberships.filter { it.sourceId == failed.id }).isEqualTo(failedMembers)
        }
    }

    @Test fun `unchanged add delete unavailable reappear checkpoint regression`() {
        var state = reconcileSource(F.empty(), F.complete(F.primary, F.media()), 1)
        val original = state.songs
        state = reconcileSource(state, F.complete(F.primary, F.media()), 2)
        assertThat(state.songs).isEqualTo(original)
        state = reconcileSource(state, F.complete(F.primary, F.media(), F.media(item = "99"), generation = 11), 3)
        assertThat(state.songs).hasSize(2)
        state = reconcileSource(state, F.complete(F.primary, F.media(item = "99"), generation = 12), 4)
        assertThat(state.songs).hasSize(1)
        val prior = state
        state = reconcileSource(state, SourceScan.Unavailable(F.primary, "partial enumeration"), 5)
        assertThat(state).isEqualTo(prior)
        state = reconcileSource(state, F.complete(F.primary, F.media(), F.media(item = "99"), generation = 13), 6)
        assertThat(state.songs.map { it.id }).contains(original.single().id)
        assertThat(state.sources.single().generation).isEqualTo(13)
    }

    @Test fun `generation updates leave IDs stable and epoch reset cannot transfer reference`() {
        var state = reconcileSource(F.legacy(), F.complete(F.primary, F.media()), 1)
        state = reconcileSource(state, F.complete(F.primary, F.media(), generation = 11), 2)
        assertThat(state.songs.single { it.contentUri == F.media().contentUri }.id).isEqualTo("media:42")
        state = reconcileSource(state, F.complete(F.primary, F.media(), generation = 1, version = "epoch-B"), 3)
        assertThat(state.songs.single { it.id == "media:42" }.isFavorite).isTrue()
        assertThat(state.protections.first { it.songId == "media:42" }.reason).isEqualTo("epoch-discontinuity")
        assertThat(state.songs.single { it.id.startsWith("media-scoped:") }.isFavorite).isFalse()
        assertThat(state.sources.single { it.id == F.primary.id }.incarnation).isEqualTo(1)
    }

    @Test fun `ambiguous legacy is retained through any source absence or metadata match`() {
        val original = F.legacy().songs.single { it.id == "media:43" }
        var state = reconcileSource(F.legacy(), F.complete(F.primary, F.media(item = "43")), 1)
        state = reconcileSource(state, F.complete(F.primary), 2)
        assertThat(state.songs.single { it.id == "media:43" }).isEqualTo(original)
        assertThat(state.protections.map { it.songId }).contains("media:43")
    }

    @Test fun `unscoped document can only be claimed later by exact provider document proof`() {
        val row = F.document().asEntity(true)
        val classified = classifyLegacySources(listOf(LegacySourceRow(row.id, row.sourceType, row.contentUri)), emptyList())
        var state = SourceState(listOf(row), classified.sources, classified.memberships, classified.protections)
        state = reconcileSource(state, F.complete(F.rootOtherProvider, F.document("provider.b")), 1)
        assertThat(state.protections.map { it.songId }).contains(row.id)
        state = reconcileSource(state, F.complete(F.rootB, F.document(root = "rootB")), 2)
        assertThat(state.protections).isEmpty()
        assertThat(state.memberships.single { it.sourceId == F.rootB.id }.songId).isEqualTo(row.id)
    }

    @Test fun `opaque document prefix is not root evidence`() {
        assertThat(SourceIdentity.hasExactRoot("content://provider.a/document/rootA%2Fchild", F.rootA.locator)).isFalse()
        assertThat(SourceIdentity.hasExactRoot(F.document().contentUri, F.rootA.locator)).isTrue()
        assertThat(SourceIdentity.hasExactRoot(F.document().contentUri, F.rootB.locator)).isFalse()
    }

    @Test fun `length framing and document normalization preserve delimiters plus and encoded slashes`() {
        assertThat(sourceKey("ab", "c")).isNotEqualTo(sourceKey("a", "bc"))
        assertThat(sourceKey("a:2", "b")).isNotEqualTo(sourceKey("a", "2:b"))
        assertThat(SourceIdentity.documentKey("content://p/document/a+b")).isEqualTo(SourceIdentity.documentKey("content://p/document/a%2Bb"))
        assertThat(SourceIdentity.documentKey("content://p/document/a%252Fb")).isNotEqualTo(SourceIdentity.documentKey("content://p/document/a%2Fb"))
        assertThat(SourceIdentity.documentKey("content://p/document/a?different=1")).isNull()
    }

    @Test fun `duplicate legacy evidence remains protected instead of merging existing IDs`() {
        val rows = listOf(LegacySourceRow("old-a", SourceIdentity.MEDIA, F.media().contentUri), LegacySourceRow("old-b", SourceIdentity.MEDIA, F.media().contentUri))
        val classified = classifyLegacySources(rows, emptyList())
        assertThat(classified.memberships).isEmpty()
        assertThat(classified.protections.map { it.songId }).containsExactly("old-a", "old-b")
    }
}

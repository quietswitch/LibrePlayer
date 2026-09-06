package com.libreplayer.library.playlist

import com.google.common.truth.Truth.assertThat
import com.libreplayer.data.repository.Song
import com.libreplayer.data.repository.SongSourceType
import org.junit.Test

class M3uSemanticsTest {
    private val root = "/storage/emulated/0/Music/Q36"
    private val a = source("a", "A.mp3")
    private val b = source("b", "B.mp3")
    private fun parse(text: String) = M3uSemantics.parse(text.toByteArray(Charsets.UTF_8))
    private fun resolve(text: String, sources: List<PlaylistSource> = listOf(a, b), directory: String? = root) =
        M3uSemantics.resolve(parse(text), sources, directory)

    @Test fun `BOM mixed newlines comments EXTINF and final line are advisory`() {
        val report = resolve("\uFEFF#EXTM3U\r\n#comment\n\n#EXTINF:999,Fake artist - Fake title\r\nA.mp3\nB.mp3")
        assertThat(report.resolvedSongIds).containsExactly("a", "b").inOrder()
        assertThat(a.song.title).isEqualTo("Original a")
        assertThat(a.song.durationMs).isEqualTo(31000L)
    }

    @Test fun `parser retains duplicates and reports unique membership limitation`() {
        val document = parse("A.mp3\nB.mp3\nA.mp3\nA.mp3")
        assertThat(document.entries.map { it.number }).containsExactly(1, 2, 3, 4).inOrder()
        val report = M3uSemantics.resolve(document, listOf(a, b), root)
        assertThat(report.resolvedSongIds).containsExactly("a", "b", "a", "a").inOrder()
        assertThat(report.importSongIds).containsExactly("a", "b").inOrder()
        assertThat(report.duplicateCount).isEqualTo(2)
        assertThat(report.summary()).contains("2 repeated references omitted")
    }

    @Test fun `empty comments only and orphan malformed EXTINF terminate safely`() {
        listOf("", "#EXTM3U\n#comment", "#EXTINF:12,Orphan", "#EXTINF:bad").forEach { text ->
            assertThat(parse(text).entries).isEmpty()
        }
        assertThat(parse("#EXTINF:12,Orphan").malformedDirectives).isEqualTo(1)
        assertThat(parse("#EXTINF:bad\nA.mp3").malformedDirectives).isEqualTo(1)
        assertThat(parse("#EXTINF:bad").malformedDirectives).isEqualTo(1)
        assertThat(parse("#EXTINF broken").malformedDirectives).isEqualTo(1)
    }

    @Test fun `malformed UTF8 and bounded oversize inputs reject wholly`() {
        assertThat(runCatching { M3uSemantics.parse(byteArrayOf(0xC3.toByte(), 0x28)) }.isFailure).isTrue()
        assertThat(runCatching { M3uSemantics.parse(ByteArray(M3uSemantics.MAX_BYTES + 1)) }.isFailure).isTrue()
        assertThat(runCatching { parse("a".repeat(M3uSemantics.MAX_LINE_CHARS + 1)) }.isFailure).isTrue()
        assertThat(runCatching { parse("A.mp3\n".repeat(M3uSemantics.MAX_ENTRIES + 1)) }.isFailure).isTrue()
        assertThat(parse("a".repeat(4096)).entries).hasSize(1)
    }

    @Test fun `Unicode spaces punctuation hash percent and plus remain literal path data`() {
        val names = listOf("音楽 café [1] (live).mp3", "a#b.mp3", "100%.mp3", "literal%2F.mp3", "a+b.mp3", " leading .mp3", "x?y.mp3")
        val sources = names.mapIndexed { index, name -> source("$index", name) }
        assertThat(resolve(names.joinToString("\n"), sources).resolvedSongIds).containsExactlyElementsIn(sources.map { it.song.id }).inOrder()
        assertThat(resolve("literal/.mp3", listOf(source("percent", "literal%2F.mp3"))).resolvedSongIds).isEmpty()
        assertThat(resolve("a b.mp3", listOf(source("plus", "a+b.mp3"))).resolvedSongIds).isEmpty()
        assertThat(resolve("x?other.mp3", listOf(source("question", "x?y.mp3"))).resolvedSongIds).isEmpty()
    }

    @Test fun `absolute relative dot and parent paths resolve only against current index`() {
        assertThat(resolve("$root/A.mp3\n./B.mp3\n../Q36/A.mp3").resolvedSongIds).containsExactly("a", "b", "a").inOrder()
        assertThat(resolve("../../../../../../etc/passwd").entries.single().status).isEqualTo(M3uResolution.MALFORMED)
        assertThat(resolve("../Elsewhere/A.mp3").entries.single().status).isEqualTo(M3uResolution.MISSING)
    }

    @Test fun `file URI decodes escapes once and content URI must match exactly`() {
        val unicode = source("u", "a b%25.mp3")
        assertThat(resolve("file://$root/a%20b%2525.mp3", listOf(unicode)).resolvedSongIds).containsExactly("u")
        assertThat(resolve(a.song.contentUri).resolvedSongIds).containsExactly("a")
        assertThat(resolve("content://media/external/audio/media/not-here").entries.single().status).isEqualTo(M3uResolution.MISSING)
        assertThat(resolve("file://remotehost/music/A.mp3").entries.single().status).isEqualTo(M3uResolution.MALFORMED)
        assertThat(resolve("file://$root/%FF.mp3").entries.single().status).isEqualTo(M3uResolution.MALFORMED)
    }

    @Test fun `remote unknown schemes malformed controls and protocol relative paths are not resolved`() {
        val report = resolve("https://example.com/a\nHTTP://example.com/b\n//host/path\nftp://host/a\nintent:evil\nfile:///bad%zz\nA\u0000.mp3")
        assertThat(report.entries.map { it.status }).containsExactly(
            M3uResolution.UNSUPPORTED_REMOTE, M3uResolution.UNSUPPORTED_REMOTE, M3uResolution.UNSUPPORTED_REMOTE,
            M3uResolution.UNSUPPORTED_SCHEME, M3uResolution.UNSUPPORTED_SCHEME, M3uResolution.MALFORMED,
            M3uResolution.MALFORMED,
        ).inOrder()
        assertThat(report.resolvedSongIds).isEmpty()
    }

    @Test fun `ambiguous path or URI never chooses first Song`() {
        val twin = PlaylistSource(a.song.copy(id = "other"), a.physicalPath)
        assertThat(resolve("A.mp3", listOf(a, twin)).entries.single().status).isEqualTo(M3uResolution.AMBIGUOUS)
        assertThat(resolve(a.song.contentUri, listOf(a, twin)).entries.single().status).isEqualTo(M3uResolution.AMBIGUOUS)
    }

    @Test fun `missing relative context never falls back to one global basename`() {
        assertThat(resolve("A.mp3", directory = null).entries.single().status).isEqualTo(M3uResolution.RELATIVE_CONTEXT_UNAVAILABLE)
        val twin = source("twin", "A.mp3", "/storage/AAAA-BBBB/Other")
        assertThat(resolve("A.mp3", listOf(a, twin), null).entries.single().status).isEqualTo(M3uResolution.AMBIGUOUS)
        assertThat(resolve("A.mp3", listOf(a, twin)).resolvedSongIds).containsExactly("a")
    }

    @Test fun `case matches Q31 shared storage but volumes remain distinct`() {
        val secondary = source("secondary", "A.mp3", "/storage/AAAA-BBBB/Music/Q36")
        assertThat(resolve("$root/a.MP3", listOf(a, secondary)).resolvedSongIds).containsExactly("a")
        assertThat(resolve("/storage/aaaa-bbbb/Music/Q36/A.mp3", listOf(a, secondary)).resolvedSongIds).containsExactly("secondary")
    }

    @Test fun `writer is deterministic UTF8 LF and semantic roundtrip retains order and repeats`() {
        val uriOnly = PlaylistSource(b.song, null)
        val ordered = listOf(uriOnly, a, uriOnly)
        val bytes = M3uSemantics.write(ordered)
        assertThat(bytes).isEqualTo(M3uSemantics.write(ordered))
        assertThat(bytes.toString(Charsets.UTF_8)).startsWith("#EXTM3U\n")
        assertThat(bytes.toString(Charsets.UTF_8)).doesNotContain("\r")
        assertThat(M3uSemantics.resolve(M3uSemantics.parse(bytes), listOf(a, uriOnly), null).resolvedSongIds)
            .containsExactly("b", "a", "b").inOrder()
    }

    @Test fun `writer rejects remote references and line injection`() {
        assertThat(runCatching { M3uSemantics.write(listOf(PlaylistSource(a.song.copy(contentUri = "https://host/a"), null))) }.isFailure).isTrue()
        assertThat(runCatching { M3uSemantics.write(listOf(PlaylistSource(a.song.copy(contentUri = "content://host/a\nhttps://host/b"), null))) }.isFailure).isTrue()
        assertThat(runCatching { M3uSemantics.write(listOf(PlaylistSource(a.song.copy(contentUri = "content://host/unescaped space"), null))) }.isFailure).isTrue()
    }

    @Test fun `moderate thousand entry import builds deterministic reports and preserves repetitions`() {
        val input = "A.mp3\nB.mp3\n".repeat(500)
        val first = resolve(input)
        assertThat(first).isEqualTo(resolve(input))
        assertThat(first.entries).hasSize(1000)
        assertThat(first.duplicateCount).isEqualTo(998)
        assertThat(first.importSongIds).containsExactly("a", "b").inOrder()
    }

    @Test fun `filename stem is display name not identity`() {
        assertThat(M3uSemantics.defaultName("同名 (mix).m3u8")).isEqualTo("同名 (mix)")
        assertThat(M3uSemantics.defaultName(".m3u")).isEqualTo("New playlist")
        assertThat(M3uSemantics.defaultName(" mix.m3u")).isEqualTo("mix")
    }

    private fun source(id: String, name: String, directory: String = root) = PlaylistSource(
        Song(id, SongSourceType.MEDIA_STORE, "content://media/external/audio/media/$id", "Original $id", "Artist", "Album",
            31_000L, 1, 1, 2024, 1L, 1L, name, "Music/Q36/", "audio/mpeg", null, false),
        "$directory/$name",
    )
}

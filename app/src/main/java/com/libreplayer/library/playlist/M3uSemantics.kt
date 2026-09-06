package com.libreplayer.library.playlist

import com.libreplayer.data.repository.Song
import com.libreplayer.library.scanner.ScannedSongDeduper
import java.net.URI
import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

internal data class M3uLine(val number: Int, val reference: String)
internal data class M3uDocument(val entries: List<M3uLine>, val malformedDirectives: Int)
internal data class PlaylistSource(val song: Song, val physicalPath: String?)
internal enum class M3uResolution {
    RESOLVED, MISSING, AMBIGUOUS, UNSUPPORTED_REMOTE, UNSUPPORTED_SCHEME,
    RELATIVE_CONTEXT_UNAVAILABLE, MALFORMED,
}
internal data class M3uEntryResult(val line: M3uLine, val status: M3uResolution, val songId: String? = null)
internal data class M3uImportReport(val entries: List<M3uEntryResult>, val malformedDirectives: Int) {
    val resolvedSongIds: List<String> get() = entries.mapNotNull { it.songId }
    val importSongIds: List<String> get() = resolvedSongIds.distinct()
    val duplicateCount: Int get() = resolvedSongIds.size - importSongIds.size

    fun summary(): String = buildString {
        append("${entries.size} references; ${resolvedSongIds.size} resolved. ")
        append("${importSongIds.size} songs will be imported; $duplicateCount repeated references omitted ")
        append("because internal playlists allow each Song once.")
        M3uResolution.entries.filter { it != M3uResolution.RESOLVED }.forEach { status ->
            val count = entries.count { it.status == status }
            if (count > 0) append("\n${status.name.lowercase(Locale.US).replace('_', ' ')}: $count")
        }
        if (malformedDirectives > 0) append("\nIgnored malformed/orphan EXTINF directives: $malformedDirectives")
    }
}

/** Bounded text interchange only: no file access, ingestion, metadata mutation or URL opening. */
internal object M3uSemantics {
    const val MAX_BYTES = 1_048_576
    const val MAX_ENTRIES = 10_000
    const val MAX_LINE_CHARS = 8_192
    private val uriScheme = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    fun parse(bytes: ByteArray): M3uDocument {
        require(bytes.size <= MAX_BYTES) { "Playlist exceeds the 1 MiB input limit." }
        val decoder = Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
        val text = decoder.decode(ByteBuffer.wrap(bytes)).toString().removePrefix("\uFEFF")
        val entries = mutableListOf<M3uLine>()
        var malformed = 0
        var pendingInfo = false
        text.lineSequence().forEachIndexed { index, line ->
            require(line.length <= MAX_LINE_CHARS) { "Playlist line exceeds the 8192-character limit." }
            when {
                line.isBlank() -> Unit
                line.startsWith("#EXTINF") -> {
                    if (pendingInfo) malformed++
                    val duration = line.substringAfter(':').substringBefore(',').toDoubleOrNull()
                    pendingInfo = line.startsWith("#EXTINF:") && ',' in line && duration != null && duration.isFinite()
                    if (!pendingInfo) malformed++
                }
                line.startsWith('#') -> Unit
                else -> {
                    require(entries.size < MAX_ENTRIES) { "Playlist exceeds the 10000-reference limit." }
                    entries += M3uLine(index + 1, line) // Preserve path spaces, percent text and repeated lines.
                    pendingInfo = false
                }
            }
        }
        if (pendingInfo) malformed++
        return M3uDocument(entries, malformed)
    }

    fun resolve(document: M3uDocument, sources: List<PlaylistSource>, directory: String?): M3uImportReport {
        val byUri = sources.groupBy { it.song.contentUri }
        val byPath = sources.mapNotNull { source ->
            source.physicalPath?.let(PlaylistPaths::key)?.let { it to source }
        }.groupBy({ it.first }, { it.second })
        val byName = sources.groupBy { it.song.displayName }
        val results = document.entries.map { line ->
            val reference = line.reference
            fun result(status: M3uResolution) = M3uEntryResult(line, status)
            fun match(candidates: List<PlaylistSource>): M3uEntryResult {
                val songs = candidates.distinctBy { it.song.id }
                return when (songs.size) {
                    0 -> result(M3uResolution.MISSING)
                    1 -> M3uEntryResult(line, M3uResolution.RESOLVED, songs.single().song.id)
                    else -> result(M3uResolution.AMBIGUOUS)
                }
            }
            fun pathMatch(path: String): M3uEntryResult = PlaylistPaths.key(path)
                ?.let { match(byPath[it].orEmpty()) } ?: result(M3uResolution.MALFORMED)
            val scheme = uriScheme.find(reference)?.value?.dropLast(1)
                ?.lowercase(Locale.US)
            when {
                reference.any { it.code < 32 || it == '\uFEFF' } -> result(M3uResolution.MALFORMED)
                scheme == "http" || scheme == "https" || reference.startsWith("//") -> result(M3uResolution.UNSUPPORTED_REMOTE)
                scheme == "content" -> {
                    val uri = runCatching { URI(reference) }.getOrNull()
                    if (uri?.rawAuthority.isNullOrEmpty() || uri.rawFragment != null) result(M3uResolution.MALFORMED)
                    else match(byUri[reference].orEmpty())
                }
                scheme == "file" -> {
                    val uri = runCatching { URI(reference) }.getOrNull()
                    if (uri == null || !uri.rawAuthority.isNullOrEmpty() || uri.rawQuery != null || uri.rawFragment != null) {
                        result(M3uResolution.MALFORMED)
                    } else {
                        val path = runCatching { decodeUriPath(uri.rawPath.orEmpty()) }.getOrNull()
                        if (path == null) result(M3uResolution.MALFORMED) else pathMatch(path)
                    }
                }
                scheme != null -> result(M3uResolution.UNSUPPORTED_SCHEME)
                reference.startsWith('/') -> pathMatch(reference)
                directory != null -> pathMatch("$directory/$reference")
                '/' !in reference && '\\' !in reference && byName[reference].orEmpty().distinctBy { it.song.id }.size > 1 ->
                    result(M3uResolution.AMBIGUOUS)
                else -> result(M3uResolution.RELATIVE_CONTEXT_UNAVAILABLE)
            }
        }
        return M3uImportReport(results, document.malformedDirectives)
    }

    fun write(sources: List<PlaylistSource>): ByteArray {
        require(sources.size <= MAX_ENTRIES) { "Playlist exceeds the 10000-entry export limit." }
        val text = buildString {
            append("#EXTM3U\n")
            sources.forEach { source ->
                val reference = source.physicalPath?.let(PlaylistPaths::normalize) ?: source.song.contentUri
                require(reference.startsWith('/') || reference.startsWith("content://")) { "Song has no supported local export reference." }
                require(!reference.startsWith("//") && reference.length <= MAX_LINE_CHARS &&
                    reference.none { it.code < 32 || it == '\uFEFF' }) { "Song reference cannot be represented safely in M3U." }
                if (reference.startsWith("content://")) {
                    val uri = URI(reference)
                    require(!uri.rawAuthority.isNullOrEmpty() && uri.rawFragment == null) { "Unsupported local URI reference." }
                }
                append(reference)
                append('\n')
                require(length <= MAX_BYTES) { "Playlist exceeds the export limit." }
            }
        }
        val encoded = Charsets.UTF_8.newEncoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).encode(CharBuffer.wrap(text))
        return ByteArray(encoded.remaining()).also {
            encoded.get(it)
            require(it.size <= MAX_BYTES) { "Playlist exceeds the 1 MiB export limit." }
        }
    }

    fun defaultName(fileName: String): String = fileName.substringBeforeLast('.', fileName).trim().ifBlank { "New playlist" }

    private fun decodeUriPath(raw: String): String {
        val bytes = java.io.ByteArrayOutputStream()
        var index = 0
        while (index < raw.length) {
            if (raw[index] == '%') {
                bytes.write(raw.substring(index + 1, index + 3).toInt(16))
                index += 3
            } else {
                val end = raw.indexOf('%', index).let { if (it < 0) raw.length else it }
                bytes.write(raw.substring(index, end).toByteArray(Charsets.UTF_8))
                index = end
            }
        }
        return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
    }
}

internal object PlaylistPaths {
    /** Lexical only. Never opens a path or treats it as a grant to read a new source. */
    fun normalize(path: String): String? {
        if (!path.startsWith('/') || path.startsWith("//") || path.any { it.code < 32 }) return null
        val parts = mutableListOf<String>()
        path.replace('\\', '/').split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeAt(parts.lastIndex)
                else -> parts += part
            }
        }
        return "/${parts.joinToString("/")}"
    }

    fun key(path: String): String? {
        val normalized = normalize(path) ?: return null
        val fileName = normalized.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: return null
        val acceptedKey = ScannedSongDeduper.canonicalDocumentPathKey("", normalized, fileName) ?: return null
        // Q3.1 authority is reused, with a lossless discriminator: percent/plus/query-like
        // filename text must not accidentally resolve through lossy URI normalization.
        return "$acceptedKey|${normalized.lowercase(Locale.US)}"
    }
}

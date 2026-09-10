package com.libreplayer.library.scanner

import com.libreplayer.data.database.entity.LibrarySourceEntity
import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** Length framing is injective, including delimiters, Unicode and opaque provider identifiers. */
internal fun sourceKey(vararg parts: String): String = parts.joinToString("") { "${it.length}:$it" }

internal object SourceIdentity {
    const val MEDIA = "MEDIA_STORE"
    const val DOCUMENT = "DOCUMENT"

    fun media(volume: String): LibrarySourceEntity {
        require(volume.isNotBlank() && volume != "external")
        return LibrarySourceEntity(sourceKey(MEDIA, "media", volume), MEDIA, "media", volume)
    }

    fun root(uri: String): LibrarySourceEntity {
        val parsed = URI(uri)
        require(parsed.scheme == "content" && !parsed.authority.isNullOrBlank())
        return LibrarySourceEntity(sourceKey(DOCUMENT, uri), DOCUMENT, parsed.authority, uri)
    }

    fun mediaItem(uri: String): Pair<LibrarySourceEntity, String>? = runCatching {
        val parsed = URI(uri)
        val parts = parsed.path.trim('/').split('/')
        if (parsed.scheme != "content" || parsed.authority != "media" || parts.size != 4 ||
            parts[1] != "audio" || parts[2] != "media" || parts[3].toLongOrNull() == null ||
            parts[0] == "external" || parts[0] == "internal" || parsed.query != null || parsed.fragment != null
        ) return null
        media(parts[0]) to parts[3]
    }.getOrNull()

    fun documentKey(uri: String): String? = runCatching {
        val parsed = URI(uri)
        if (parsed.scheme != "content" || parsed.authority.isNullOrBlank() || parsed.query != null || parsed.fragment != null) return null
        val parts = parsed.rawPath.trim('/').split('/')
        val index = when {
            parts.size == 2 && parts[0] == "document" -> 1
            parts.size == 4 && parts[0] == "tree" && parts[2] == "document" -> 3
            else -> return null
        }
        sourceKey(parsed.authority, decode(parts[index]))
    }.getOrNull()

    fun hasExactRoot(documentUri: String, rootUri: String): Boolean = runCatching {
        if (documentUri == rootUri) return true
        val document = URI(documentUri)
        val root = URI(rootUri)
        val d = document.rawPath.trim('/').split('/')
        val r = root.rawPath.trim('/').split('/')
        document.authority == root.authority && document.scheme == "content" && root.scheme == "content" &&
            d.size == 4 && d[0] == "tree" && d[2] == "document" &&
            r.size == 2 && r[0] == "tree" && decode(d[1]) == decode(r[1])
    }.getOrDefault(false)

    fun qualifiedId(source: LibrarySourceEntity, itemKey: String): String =
        if (source.kind == DOCUMENT) "document-scoped:${sourceKey(itemKey)}"
        else "media-scoped:${sourceKey(source.id, source.incarnation.toString(), itemKey)}"

    // Only the platform external-storage provider exposes the accepted volume:path convention.
    // Opaque providers, aggregate MediaStore URIs, and two MediaStore rows never gain path authority.
    fun physicalKey(song: ScannedSong): String? {
        mediaItem(song.contentUri)?.let { (scope, _) ->
            val relative = song.relativePath ?: return null
            return physical(scope.locator, (if (relative.isEmpty()) "" else relative.trimEnd('/') + "/") + song.displayName)
        }
        return runCatching {
            val uri = URI(song.contentUri)
            if (uri.authority != "com.android.externalstorage.documents") return null
            if (documentKey(song.contentUri) == null) return null
            val documentId = decode(uri.rawPath.substringAfterLast('/'))
            val volume = documentId.substringBefore(':', "")
            if (volume != "primary" && !volume.matches(Regex("[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"))) return null
            physical(if (volume == "primary") "external_primary" else volume, documentId.substringAfter(':'))
        }.getOrNull()
    }

    private fun physical(volume: String, path: String): String? {
        if (path.isBlank() || path.startsWith('/') || path.split('/').any { it == "." || it == ".." }) return null
        return sourceKey(volume.lowercase(Locale.ROOT), path.lowercase(Locale.ROOT))
    }

    private fun decode(value: String): String = URLDecoder.decode(value.replace("+", "%2B"), "UTF-8")
}

internal data class SourceObservation(val itemKey: String, val song: ScannedSong)

internal sealed interface SourceScan {
    val source: LibrarySourceEntity
    data class Complete(
        override val source: LibrarySourceEntity,
        val observations: List<SourceObservation>,
        val checkpoint: MediaStoreCheckpoint? = null,
    ) : SourceScan
    // An explicit Remove Folder action supplies membership authority, not a scan checkpoint.
    data class Detached(override val source: LibrarySourceEntity) : SourceScan
    data class Unavailable(
        override val source: LibrarySourceEntity,
        val reason: String,
        val permissionRequired: Boolean = false,
    ) : SourceScan
}

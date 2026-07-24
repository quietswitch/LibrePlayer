package com.libreplayer.navigation

import android.net.Uri

sealed class AppRoute(val route: String) {
    data object Songs : AppRoute("songs")
    data object Albums : AppRoute("albums")
    data object Artists : AppRoute("artists")
    data object Playlists : AppRoute("playlists")
    data object Settings : AppRoute("settings")
    data object Search : AppRoute("search")
    data object NowPlaying : AppRoute("now_playing")
    data object Queue : AppRoute("queue")
    data object AudioDetails : AppRoute("audio_details/{songId}") {
        fun create(songId: String): String = "audio_details/${Uri.encode(songId)}"
    }

    data object AlbumDetail : AppRoute("album/{albumId}") {
        fun create(albumId: String): String = "album/${Uri.encode(albumId)}"
    }

    data object ArtistDetail : AppRoute("artist/{artistId}") {
        fun create(artistId: String): String = "artist/${Uri.encode(artistId)}"
    }

    data object PlaylistDetail : AppRoute("playlist/{playlistId}") {
        fun create(playlistId: Long): String = "playlist/$playlistId"
    }

    data object SmartPlaylistDetail : AppRoute("smart/{type}") {
        fun create(type: String): String = "smart/${Uri.encode(type)}"
    }
}

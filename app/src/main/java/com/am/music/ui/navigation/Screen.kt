package com.am.music.ui.navigation

sealed class Screen(val route: String) {
    data object Local : Screen("local")
    data object Playlists : Screen("playlists")
    data object Online : Screen("online")
    data object Ranking : Screen("ranking")
    data object Player : Screen("player")
    data object Queue : Screen("queue")
    data object Settings : Screen("settings")
    data object Recognize : Screen("recognize")
    data object Favorites : Screen("favorites")
    data object Recent : Screen("recent")
    data object PlaylistDetail : Screen("playlist/{playlistId}") {
        fun routeFor(playlistId: Long) = "playlist/$playlistId"
    }
    data object AddSongs : Screen("playlist/{playlistId}/add") {
        fun routeFor(playlistId: Long) = "playlist/$playlistId/add"
    }
}
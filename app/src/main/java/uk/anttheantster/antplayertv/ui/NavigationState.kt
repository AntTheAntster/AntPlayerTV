package uk.anttheantster.antplayertv.ui

import uk.anttheantster.antplayertv.model.MediaItem

sealed class NavigationState {
    object Home : NavigationState()
    data class Details(val item: MediaItem) : NavigationState()
    data class Player(
        val item: MediaItem,
        val startPositionMs: Long? = null
    ) : NavigationState()
    object Search : NavigationState()
    object Settings : NavigationState()

    data class Watchlist(val id: Long, val name: String) : NavigationState()
}

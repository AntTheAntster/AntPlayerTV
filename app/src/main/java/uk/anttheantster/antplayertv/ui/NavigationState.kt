package uk.anttheantster.antplayertv.ui

import uk.anttheantster.antplayertv.model.MediaItem

sealed class NavigationState {
    /** v2.0: 4-card launcher shown right after the boot sequence. */
    object Hub : NavigationState()

    /** Original "Home" feed (Continue Watching / History / Featured). */
    object Home : NavigationState()

    /** v2.0: replaces Search. Browse-with-search + Popular / What's new sections. */
    object Browse : NavigationState()

    /** v2.0: placeholder destination for upcoming IPTV. */
    object LiveTV : NavigationState()

    /**
     * Kept for backwards compatibility with code paths that still reference Search;
     * routed to Browse in the main when() block.
     */
    object Search : NavigationState()

    object Settings : NavigationState()

    /** Legacy details for non-TMDB items (continue-watching, watchlists). */
    data class Details(val item: MediaItem) : NavigationState()

    /**
     * v2.0 TMDB-driven details. The hint title lets the screen render
     * something while the full TMDB record is loading.
     */
    data class TmdbDetails(
        val tmdbId: Int,
        val type: String,
        val titleHint: String,
        val posterHint: String = "",
    ) : NavigationState()

    data class Player(
        val item: MediaItem,
        val startPositionMs: Long? = null,
        /** Optional next-episode the player can offer mid-playback. */
        val nextItem: MediaItem? = null,
    ) : NavigationState()

    data class Watchlist(val id: Long, val name: String) : NavigationState()

    object BrowseAll : NavigationState()
}

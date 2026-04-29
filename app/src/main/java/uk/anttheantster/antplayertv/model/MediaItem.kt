package uk.anttheantster.antplayertv.model

data class MediaItem(
    val id: String,
    val title: String,
    val description: String,
    val image: String,
    val streamUrl: String,

    // Optional metadata (used in search UI etc)
    val releaseYear: String? = null,
    val totalEpisodes: Int? = null,
    val type: String? = null,        // e.g. "TV", "Movie", "OVA"
    val ageRating: String? = null,   // e.g. "PG-13", "R", etc

    // v2.0 — TMDB anchor. Set by TmdbStreamBridge so progress / watchlist
    // entries can route back into the TMDB-driven Details screen instead
    // of the legacy AntPlayerDetails.
    val tmdbId: Int? = null,
    val tmdbType: String? = null,    // "tv" or "movie"

    // v2.0 — TMDB season/episode pinning. Lets per-episode progress show
    // up on the right Details rail card (filled white seek bar + Completed
    // badge) without ambiguity, even for multi-season Western TV where
    // the scraper's flat episode numbering wouldn't otherwise resolve to
    // a TMDB (season, episode) pair.
    val tmdbSeason: Int? = null,
    val tmdbEpisode: Int? = null
)

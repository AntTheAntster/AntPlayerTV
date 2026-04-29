package uk.anttheantster.antplayertv.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "progress")
data class ProgressEntity(
    @PrimaryKey val mediaId: String,
    val title: String,
    val image: String,
    val streamUrl: String,
    val lastPositionMs: Long,
    val durationMs: Long,
    val lastUpdated: Long,

    // v2.0 — TMDB anchor (nullable). When present, Continue Watching /
    // History items in the UI route to the TMDB-driven Details screen
    // rather than the legacy details screen.
    val tmdbId: Int? = null,
    val tmdbType: String? = null,

    // v2.0.1 — TMDB season/episode pin. Used by the Details rail to render
    // per-episode progress (seek bar + "Completed" badge).
    val tmdbSeason: Int? = null,
    val tmdbEpisode: Int? = null
)

package uk.anttheantster.antplayertv.data.watchlist

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "watchlist_items",
    indices = [
        Index(value = ["watchlistId"]),
        Index(value = ["watchlistId", "mediaId"], unique = true)
    ]
)
data class WatchlistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val watchlistId: Long,

    // Use your MediaItem.id (remote href / Animekai id / etc.)
    val mediaId: String,
    val title: String,
    val description: String,
    val image: String,
    val streamUrl: String,

    // v2.0 — TMDB anchor (nullable). Lets watchlist items route into the
    // TMDB-driven Details screen when known.
    val tmdbId: Int? = null,
    val tmdbType: String? = null,

    val addedAt: Long = System.currentTimeMillis()
)

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
    val ageRating: String? = null    // e.g. "PG-13", "R", etc
)

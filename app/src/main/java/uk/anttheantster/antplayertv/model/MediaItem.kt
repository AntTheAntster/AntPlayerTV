package uk.anttheantster.antplayertv.model

data class MediaItem(
    val id: String,
    val title: String,
    val description: String,
    val image: String,
    val streamUrl: String
)
package uk.anttheantster.antplayertv.model

import uk.anttheantster.antplayertv.data.AshiEpisode

data class MediaItem(
    val id: String,
    val title: String,
    val description: String,
    val image: String,
    val streamUrl: String,
    val episode: String,
    val watchType: String
)
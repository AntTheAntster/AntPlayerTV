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
    val lastUpdated: Long
)

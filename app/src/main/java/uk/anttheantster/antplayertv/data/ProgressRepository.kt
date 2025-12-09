package uk.anttheantster.antplayertv.data

import uk.anttheantster.antplayertv.model.MediaItem

// Full per-episode progress, backing resume + status UI
data class EpisodeProgress(
    val episodeNumber: Int,
    val positionMs: Long,
    val durationMs: Long,
    val mediaId: String,
    val title: String,
    val image: String?,
    val streamUrl: String,
    val lastUpdated: Long
)

// Extract "series key" from a mediaId, e.g.
// "Animekai:1234#ep3#Sub" -> "Animekai:1234"
// "local_movie_1"        -> "local_movie_1"
private fun seriesKeyFor(mediaId: String): String =
    mediaId.substringBefore("#")

// Try to pull the episode number out of an episode id, e.g.
// "Animekai:1234#ep3#Sub" -> 3
private fun episodeNumberFromMediaId(mediaId: String): Int? {
    val parts = mediaId.split("#")
    if (parts.size < 2) return null
    val epPart = parts[1] // "ep3"
    return epPart.removePrefix("ep").toIntOrNull()
}

class ProgressRepository(private val dao: ProgressDao) {

    private fun toMediaItem(entity: ProgressEntity): MediaItem =
        MediaItem(
            id = entity.mediaId,
            title = entity.title,
            description = "",
            image = entity.image,
            streamUrl = entity.streamUrl,
        )

    // Create a *title-level* MediaItem for a group of progress rows
    // - For local items (no "#ep"), just use the exact stored item.
    // - For remote episodes ("...#epX#label"), collapse to the series id + series title
    private fun toTitleMediaItem(latest: ProgressEntity): MediaItem {
        val key = seriesKeyFor(latest.mediaId)

        val isEpisode = latest.mediaId.contains("#ep")
        return if (!isEpisode) {
            // Local / single item content – just return as-is
            toMediaItem(latest)
        } else {
            // Remote TV show – group into a single "title" entry
            val seriesTitle = latest.title
                .substringBefore(" - Ep ")
                .ifBlank { latest.title }

            MediaItem(
                id = key,               // series id (e.g. "Animekai:1234")
                title = seriesTitle,    // base title only
                description = "",
                image = latest.image,
                streamUrl = ""          // blank so Details treats this as a series
            )
        }
    }

    // Items you haven't fully finished (approx.)
    suspend fun getContinueWatching(): List<MediaItem> {
        val entities = dao.getAll()

        // First filter to "partially watched"
        val partial = entities.filter { e ->
            val pos = e.lastPositionMs
            val dur = e.durationMs
            pos > 10_000L && (dur <= 0L || pos < dur - 10_000L)
        }

        // Group by series / title
        val grouped = partial.groupBy { seriesKeyFor(it.mediaId) }

        // For each series, keep only the latest entry (by lastUpdated)
        return grouped.values
            .mapNotNull { group ->
                val latest = group.maxByOrNull { it.lastUpdated } ?: return@mapNotNull null
                latest to toTitleMediaItem(latest)
            }
            .sortedByDescending { it.first.lastUpdated }
            .map { it.second }
    }

    // Everything you've watched, but grouped by title instead of per episode
    suspend fun getHistory(): List<MediaItem> {
        val entities = dao.getAll()

        val grouped = entities.groupBy { seriesKeyFor(it.mediaId) }

        return grouped.values
            .mapNotNull { group ->
                val latest = group.maxByOrNull { it.lastUpdated } ?: return@mapNotNull null
                latest to toTitleMediaItem(latest)
            }
            .sortedByDescending { it.first.lastUpdated }
            .map { it.second }
    }

    suspend fun getProgressFor(mediaId: String): Long? {
        return dao.getByMediaId(mediaId)?.lastPositionMs
    }

    // Per-series: one EpisodeProgress per watched episode
    // Per-series: one EpisodeProgress per watched episode
    suspend fun getEpisodeProgressForSeries(seriesId: String): List<EpisodeProgress> {
        val entities = dao.getAll()

        return entities.mapNotNull { e ->
            if (seriesKeyFor(e.mediaId) != seriesId) return@mapNotNull null
            val epNumber = episodeNumberFromMediaId(e.mediaId) ?: return@mapNotNull null

            EpisodeProgress(
                episodeNumber = epNumber,
                positionMs = e.lastPositionMs,
                durationMs = e.durationMs,
                mediaId = e.mediaId,
                title = e.title,
                image = e.image,
                streamUrl = e.streamUrl,
                lastUpdated = e.lastUpdated
            )
        }
    }

    // Latest watched episode for a series (by lastUpdated)
    suspend fun getLatestEpisodeForSeries(seriesId: String): EpisodeProgress? {
        return getEpisodeProgressForSeries(seriesId)
            .maxByOrNull { it.lastUpdated }
    }

    suspend fun saveProgress(item: MediaItem, positionMs: Long, durationMs: Long) {
        val entity = ProgressEntity(
            mediaId = item.id,
            title = item.title,
            image = item.image,
            streamUrl = item.streamUrl,
            lastPositionMs = positionMs,
            durationMs = durationMs,
            lastUpdated = System.currentTimeMillis()
        )
        dao.upsert(entity)
    }

    suspend fun clearAll() {
        dao.clearAll()
    }
}
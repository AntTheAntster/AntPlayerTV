package uk.anttheantster.antplayertv.data

import uk.anttheantster.antplayertv.model.MediaItem

class ProgressRepository(private val dao: ProgressDao) {

    private fun toMediaItem(entity: ProgressEntity): MediaItem =
        MediaItem(
            id = entity.mediaId,
            title = entity.title,
            description = "",
            image = entity.image,
            streamUrl = entity.streamUrl,
        )

    // Items you haven't fully finished (rough heuristic)
    suspend fun getContinueWatching(): List<MediaItem> {
        val entities = dao.getAll()
        return entities
            .filter { e ->
                val pos = e.lastPositionMs
                val dur = e.durationMs
                pos > 10_000L && (dur <= 0L || pos < dur - 10_000L)
            }
            .map(::toMediaItem)
    }

    // Everything you've watched, newest first
    suspend fun getHistory(): List<MediaItem> {
        val entities = dao.getAll()
        return entities.map(::toMediaItem)
    }

    suspend fun getProgressFor(mediaId: String): Long? {
        return dao.getByMediaId(mediaId)?.lastPositionMs
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

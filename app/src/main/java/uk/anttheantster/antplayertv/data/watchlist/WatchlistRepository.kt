package uk.anttheantster.antplayertv.data.watchlist

import kotlinx.coroutines.flow.Flow
import uk.anttheantster.antplayertv.model.MediaItem

class WatchlistRepository(
    private val dao: WatchlistDao
) {
    companion object {
        const val DEFAULT_WATCH_LATER_NAME = "Watch later"
    }

    fun observeWatchlists(): Flow<List<WatchlistEntity>> = dao.observeWatchlists()
    fun observeItems(watchlistId: Long): Flow<List<WatchlistItemEntity>> = dao.observeItems(watchlistId)

    suspend fun ensureDefaultWatchLater() {
        val exists = dao.countWatchlistsNamed(DEFAULT_WATCH_LATER_NAME) > 0
        if (!exists) {
            dao.insertWatchlist(WatchlistEntity(name = DEFAULT_WATCH_LATER_NAME))
        }
    }

    suspend fun createWatchlist(name: String): Long? {
        val n = name.trim()
        if (n.isBlank()) return null
        if (dao.countWatchlistsNamed(n) > 0) return null
        return dao.insertWatchlist(WatchlistEntity(name = n))
    }

    suspend fun addToWatchlist(watchlistId: Long, item: MediaItem) {
        dao.insertItem(
            WatchlistItemEntity(
                watchlistId = watchlistId,
                mediaId = item.id,
                title = item.title,
                description = item.description,
                image = item.image,
                streamUrl = item.streamUrl
            )
        )
    }

    suspend fun deleteWatchlist(watchlistId: Long) {
        val wl = dao.getWatchlist(watchlistId) ?: return
        if (wl.name.equals(DEFAULT_WATCH_LATER_NAME, ignoreCase = true)) return
        dao.deleteWatchlistAndItems(watchlistId)
    }

    suspend fun removeFromWatchlist(watchlistId: Long, mediaId: String) {
        dao.removeItem(watchlistId, mediaId)
    }

    suspend fun renameWatchlist(watchlistId: Long, newName: String): Boolean {
        val wl = dao.getWatchlist(watchlistId) ?: return false
        if (wl.name.equals(DEFAULT_WATCH_LATER_NAME, ignoreCase = true)) return false

        val n = newName.trim()
        if (n.isBlank()) return false
        if (dao.countWatchlistsNamed(n) > 0) return false

        dao.renameWatchlist(watchlistId, n)
        return true
    }
}

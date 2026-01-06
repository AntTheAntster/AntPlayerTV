package uk.anttheantster.antplayertv.data.watchlist

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchlistDao {

    @Query("SELECT * FROM watchlists ORDER BY createdAt ASC")
    fun observeWatchlists(): Flow<List<WatchlistEntity>>

    @Query("SELECT * FROM watchlists WHERE id = :id LIMIT 1")
    suspend fun getWatchlist(id: Long): WatchlistEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertWatchlist(watchlist: WatchlistEntity): Long

    @Query("DELETE FROM watchlists WHERE id = :id")
    suspend fun deleteWatchlist(id: Long)

    @Query("SELECT * FROM watchlist_items WHERE watchlistId = :watchlistId ORDER BY addedAt DESC")
    fun observeItems(watchlistId: Long): Flow<List<WatchlistItemEntity>>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertItem(item: WatchlistItemEntity)

    @Query("DELETE FROM watchlist_items WHERE watchlistId = :watchlistId AND mediaId = :mediaId")
    suspend fun removeItem(watchlistId: Long, mediaId: String)

    @Query("DELETE FROM watchlist_items WHERE watchlistId = :watchlistId")
    suspend fun clearWatchlist(watchlistId: Long)

    @Query("SELECT COUNT(*) FROM watchlists WHERE name = :name")
    suspend fun countWatchlistsNamed(name: String): Int

    @Transaction
    suspend fun deleteWatchlistAndItems(watchlistId: Long) {
        clearWatchlist(watchlistId)
        deleteWatchlist(watchlistId)
    }

    @Query("UPDATE watchlists SET name = :newName WHERE id = :id")
    suspend fun renameWatchlist(id: Long, newName: String)


}

package uk.anttheantster.antplayertv.data

import androidx.room.*

@Dao
interface ProgressDao {

    @Query("SELECT * FROM progress ORDER BY lastUpdated DESC")
    suspend fun getAll(): List<ProgressEntity>

    @Query("SELECT * FROM progress WHERE mediaId = :mediaId LIMIT 1")
    suspend fun getByMediaId(mediaId: String): ProgressEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ProgressEntity)

    @Query("DELETE FROM progress WHERE mediaId = :mediaId")
    suspend fun delete(mediaId: String)

    @Query("DELETE FROM progress")
    suspend fun clearAll()
}

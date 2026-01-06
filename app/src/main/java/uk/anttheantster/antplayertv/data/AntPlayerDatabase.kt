package uk.anttheantster.antplayertv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import uk.anttheantster.antplayertv.data.watchlist.WatchlistDao
import uk.anttheantster.antplayertv.data.watchlist.WatchlistEntity
import uk.anttheantster.antplayertv.data.watchlist.WatchlistItemEntity

@Database(
    entities = [
        ProgressEntity::class,
        WatchlistEntity::class,
        WatchlistItemEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class AntPlayerDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun watchlistDao(): WatchlistDao

    companion object {
        @Volatile
        private var INSTANCE: AntPlayerDatabase? = null

        fun getInstance(context: Context): AntPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AntPlayerDatabase::class.java,
                    "antplayer.db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

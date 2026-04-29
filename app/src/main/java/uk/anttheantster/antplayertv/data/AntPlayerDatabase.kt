package uk.anttheantster.antplayertv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import uk.anttheantster.antplayertv.data.watchlist.WatchlistDao
import uk.anttheantster.antplayertv.data.watchlist.WatchlistEntity
import uk.anttheantster.antplayertv.data.watchlist.WatchlistItemEntity

@Database(
    entities = [
        ProgressEntity::class,
        WatchlistEntity::class,
        WatchlistItemEntity::class
    ],
    version = 4,
    exportSchema = false
)
abstract class AntPlayerDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao
    abstract fun watchlistDao(): WatchlistDao

    companion object {
        @Volatile
        private var INSTANCE: AntPlayerDatabase? = null

        /**
         * v2 → v3 migration: add nullable `tmdbId` (INTEGER) and `tmdbType`
         * (TEXT) columns to the `progress` and `watchlist_items` tables so
         * existing rows survive the upgrade. Existing rows get NULL for
         * both fields, which the UI treats as "legacy item — route to the
         * non-TMDB Details screen."
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN tmdbId INTEGER")
                db.execSQL("ALTER TABLE progress ADD COLUMN tmdbType TEXT")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN tmdbId INTEGER")
                db.execSQL("ALTER TABLE watchlist_items ADD COLUMN tmdbType TEXT")
            }
        }

        /**
         * v3 → v4 migration: add nullable `tmdbSeason` / `tmdbEpisode` columns
         * to the progress table so per-episode progress can be looked up by
         * (tmdbId, season) and rendered on the Details rail.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE progress ADD COLUMN tmdbSeason INTEGER")
                db.execSQL("ALTER TABLE progress ADD COLUMN tmdbEpisode INTEGER")
            }
        }

        fun getInstance(context: Context): AntPlayerDatabase {
            return INSTANCE ?: synchronized(this) {
                Room.databaseBuilder(
                    context.applicationContext,
                    AntPlayerDatabase::class.java,
                    "antplayer.db"
                )
                    .addMigrations(MIGRATION_2_3, MIGRATION_3_4)
                    // Last-resort fallback so the app boots even if the user
                    // returns from a much older version that we forgot to
                    // path-cover. Their progress would reset, but the app
                    // doesn't get bricked.
                    .fallbackToDestructiveMigration()
                    .build()
                    .also { INSTANCE = it }
            }
        }
    }
}

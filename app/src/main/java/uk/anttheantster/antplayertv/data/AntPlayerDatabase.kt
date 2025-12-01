package uk.anttheantster.antplayertv.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ProgressEntity::class],
    version = 1,
    exportSchema = false
)
abstract class AntPlayerDatabase : RoomDatabase() {

    abstract fun progressDao(): ProgressDao

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

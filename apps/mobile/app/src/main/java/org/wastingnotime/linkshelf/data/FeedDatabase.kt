package org.wastingnotime.linkshelf.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [SavedLink::class], version = 3, exportSchema = true)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun links(): LinkDao

    companion object {
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_links ADD COLUMN imagePath TEXT")
                db.execSQL("UPDATE saved_links SET previewStatus = 'pending' WHERE previewStatus = 'fallback'")
            }
        }

        val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE saved_links ADD COLUMN isFavorite INTEGER NOT NULL DEFAULT 0")
            }
        }

        fun open(context: Context, name: String = "linkshelf.db"): FeedDatabase =
            Room.databaseBuilder(context.applicationContext, FeedDatabase::class.java, name)
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3).build()
    }
}

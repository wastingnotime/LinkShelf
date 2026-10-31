package org.wastingnotime.myfeed.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [SavedLink::class], version = 1, exportSchema = true)
abstract class FeedDatabase : RoomDatabase() {
    abstract fun links(): LinkDao

    companion object {
        fun open(context: Context, name: String = "my-feed.db"): FeedDatabase =
            Room.databaseBuilder(context.applicationContext, FeedDatabase::class.java, name).build()
    }
}

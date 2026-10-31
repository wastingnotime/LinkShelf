package org.wastingnotime.myfeed

import android.app.Application
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.wastingnotime.myfeed.data.FeedDatabase
import org.wastingnotime.myfeed.data.LinkRepository

class FeedApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    val database by lazy { FeedDatabase.open(this) }
    val repository by lazy { LinkRepository(database.links()) }
}

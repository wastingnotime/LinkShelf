package org.wastingnotime.linkshelf.ui

import org.wastingnotime.linkshelf.R
import org.wastingnotime.linkshelf.data.FeedDestination
import org.wastingnotime.linkshelf.data.SearchScope

internal val FeedDestination.labelResource: Int get() = when (this) {
    FeedDestination.Main -> R.string.inbox
    FeedDestination.Grouped -> R.string.grouped
    FeedDestination.Archived -> R.string.archived
}
internal val SearchScope.labelResource: Int get() = when (this) {
    SearchScope.Inbox -> R.string.inbox
    SearchScope.Archived -> R.string.archived
    SearchScope.All -> R.string.all
}
internal fun settingsLabel(key: String): Int = when (key) {
    "Appearance" -> R.string.appearance
    "Backup" -> R.string.backup
    "Recently deleted" -> R.string.recently_deleted
    "About" -> R.string.about
    "System" -> R.string.system
    "Light" -> R.string.light
    "Dark" -> R.string.dark
    else -> error("Unknown settings key: $key")
}

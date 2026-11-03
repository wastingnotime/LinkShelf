package org.wastingnotime.linkshelf.ui

import android.app.UiModeManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build

/** Keep Android's launch window in step with the saved Compose appearance. */
internal fun syncLaunchAppearance(context: Context, theme: String) {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        context.getSystemService(UiModeManager::class.java).setApplicationNightMode(when (theme) {
            "Light" -> UiModeManager.MODE_NIGHT_NO
            "Dark" -> UiModeManager.MODE_NIGHT_YES
            // For the application override, AUTO clears the explicit night qualifier.
            else -> UiModeManager.MODE_NIGHT_AUTO
        })
    }
}

/** The compatibility splash reads Activity resources on Android 6–11. */
internal fun launchAppearanceContext(context: Context): Context {
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return context
    val night = when (context.getSharedPreferences("appearance", 0).getString("theme", "System")) {
        "Light" -> Configuration.UI_MODE_NIGHT_NO
        "Dark" -> Configuration.UI_MODE_NIGHT_YES
        else -> return context
    }
    return context.createConfigurationContext(Configuration(context.resources.configuration).apply {
        uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or night
    })
}

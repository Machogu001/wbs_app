package com.example.myapplication

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate

/**
 * Maps a persisted theme_preference value ("system" | "light" | "dark", as
 * returned/accepted by the theme.php API and me.php's user.theme_preference
 * field) to the corresponding AppCompatDelegate night mode constant. Any
 * unrecognised or missing value safely falls back to following the device.
 */
internal fun mapThemePreferenceToNightMode(preference: String?): Int = when (preference) {
    "light" -> AppCompatDelegate.MODE_NIGHT_NO
    "dark" -> AppCompatDelegate.MODE_NIGHT_YES
    else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
}

/**
 * Applies the last-known (or default "system") theme preference as early as
 * possible in the process lifecycle - before any Activity is created - so the
 * login screen and every other first-drawn frame already render in the
 * correct light/dark appearance without a visible flash or a forced activity
 * recreation. MainActivity re-applies/syncs this once the user's authoritative
 * server-side preference (me.php/theme.php) is known.
 */
class WbsApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val preferences = getSharedPreferences("wbs_session", MODE_PRIVATE)
        val preference = preferences.getString("theme_preference", "system")
        AppCompatDelegate.setDefaultNightMode(mapThemePreferenceToNightMode(preference))
    }
}

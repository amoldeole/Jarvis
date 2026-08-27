package com.jarvis.phoneguardian.core.security

import android.content.Context

/** Non-sensitive product preferences. Credentials remain in Android Keystore or provider auth. */
class GuardianSettings(context: Context) {
    private val prefs = context.getSharedPreferences("guardian_settings", Context.MODE_PRIVATE)

    var trashRetentionDays: Int
        get() = prefs.getInt(KEY_TRASH_DAYS, 30)
        set(value) { prefs.edit().putInt(KEY_TRASH_DAYS, value.coerceIn(0, 30)).apply() }

    var autoRemoveEmptyFolders: Boolean
        get() = prefs.getBoolean(KEY_AUTO_EMPTY, false)
        set(value) { prefs.edit().putBoolean(KEY_AUTO_EMPTY, value).apply() }

    var aiMode: String
        get() = prefs.getString(KEY_AI_MODE, PrivacyDefaults.AI_MODE_OFF) ?: PrivacyDefaults.AI_MODE_OFF
        set(value) { prefs.edit().putString(KEY_AI_MODE, value).apply() }

    companion object {
        private const val KEY_TRASH_DAYS = "trash_retention_days"
        private const val KEY_AUTO_EMPTY = "auto_remove_empty_folders"
        private const val KEY_AI_MODE = "ai_mode"
    }
}

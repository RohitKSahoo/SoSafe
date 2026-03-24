package com.rohit.sosafe.data

import android.content.Context
import android.content.SharedPreferences

class AppModeManager(context: Context) {
    private val PREFS_NAME = "sosafe_app_prefs"
    private val APP_MODE_KEY = "app_mode"
    private val sharedPrefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun getAppMode(): AppMode? {
        val modeString = sharedPrefs.getString(APP_MODE_KEY, null)
        return modeString?.let { AppMode.valueOf(it) }
    }

    fun setAppMode(mode: AppMode) {
        sharedPrefs.edit().putString(APP_MODE_KEY, mode.name).apply()
    }
}
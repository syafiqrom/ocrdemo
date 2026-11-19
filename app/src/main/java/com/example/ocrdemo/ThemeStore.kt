package com.example.ocrdemo

import android.content.Context
import androidx.appcompat.app.AppCompatDelegate

object ThemeStore {
    private const val PREFS = "settings"
    private const val KEY = "theme_mode"

    fun get(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getInt(KEY, AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)

    fun set(context: Context, mode: Int) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putInt(KEY, mode).apply()
        AppCompatDelegate.setDefaultNightMode(mode)
    }

    fun apply(context: Context) {
        AppCompatDelegate.setDefaultNightMode(get(context))
    }
}
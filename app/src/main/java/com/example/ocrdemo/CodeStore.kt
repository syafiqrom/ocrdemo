package com.example.ocrdemo

import android.content.Context
import java.util.UUID

// Single place that creates and reads the secret code, shared by all screens
object CodeStore {
    fun getCode(context: Context): String {
        val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
        return prefs.getString("code", null)
            ?: UUID.randomUUID().toString().replace("-", "")
                .also { prefs.edit().putString("code", it).apply() }
    }
}
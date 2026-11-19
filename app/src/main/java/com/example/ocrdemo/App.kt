package com.example.ocrdemo

import android.app.Application

class App : Application() {
    override fun onCreate() {
        super.onCreate()
        ThemeStore.apply(this)
    }
}
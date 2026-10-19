package com.example.transmogrifier

import android.app.Application

class TransmogrifierApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

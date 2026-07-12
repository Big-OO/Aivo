package com.assistant.aivo

import android.app.Application
import com.assistant.aivo.di.AppContainer
import com.assistant.aivo.di.AppContainerImpl

class AivoApplication : Application() {
    lateinit var container: AppContainer

    override fun onCreate() {
        super.onCreate()
        container = AppContainerImpl(this)
    }
}

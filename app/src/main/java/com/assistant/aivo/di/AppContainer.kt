package com.assistant.aivo.di

import android.content.Context
import com.assistant.aivo.BuildConfig
import com.assistant.aivo.data.agent.GeminiAiAgent
import com.assistant.aivo.data.prefs.ApplicationSettingsRepository
import com.assistant.aivo.domain.agent.AiAgent
import com.assistant.aivo.domain.discovery.AppDiscoveryManager
import com.assistant.aivo.domain.runner.UniversalAppFunctionRunner
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import java.util.concurrent.TimeUnit

import com.assistant.aivo.core.NetworkMonitor

interface AppContainer {
    val aiAgent: AiAgent
    val appDiscoveryManager: AppDiscoveryManager
    val settingsRepository: ApplicationSettingsRepository
    val appFunctionRunner: UniversalAppFunctionRunner
    val networkMonitor: NetworkMonitor
}

class AppContainerImpl(private val context: Context) : AppContainer {

    private val okHttpClient: OkHttpClient by lazy {
        val loggingInterceptor = HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BODY
        }
        OkHttpClient.Builder()
            .addInterceptor(loggingInterceptor)
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .build()
    }

    override val aiAgent: AiAgent by lazy {
        GeminiAiAgent(
            okHttpClient = okHttpClient,
            baseUrl = BuildConfig.AI_API_BASE_URL,
            apiKey = BuildConfig.AI_API_KEY
        )
    }

    override val appDiscoveryManager: AppDiscoveryManager by lazy {
        AppDiscoveryManager(context)
    }

    override val settingsRepository: ApplicationSettingsRepository by lazy {
        ApplicationSettingsRepository(context)
    }

    override val appFunctionRunner: UniversalAppFunctionRunner by lazy {
        UniversalAppFunctionRunner(context)
    }

    override val networkMonitor: NetworkMonitor by lazy {
        NetworkMonitor(context)
    }
}

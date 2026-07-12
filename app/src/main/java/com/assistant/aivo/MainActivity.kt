package com.assistant.aivo

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import com.assistant.aivo.presentation.ui.AIChatScreen
import com.assistant.aivo.presentation.ui.AIChatViewModel
import com.assistant.aivo.ui.theme.AivoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        val container = (application as AivoApplication).container
        val viewModel: AIChatViewModel by viewModels {
            AIChatViewModel.provideFactory(
                aiAgent = container.aiAgent,
                appDiscoveryManager = container.appDiscoveryManager,
                settingsRepository = container.settingsRepository,
                appFunctionRunner = container.appFunctionRunner,
                networkMonitor = container.networkMonitor
            )
        }

        setContent {
            AivoTheme {
                AIChatScreen(
                    viewModel = viewModel,
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }
}
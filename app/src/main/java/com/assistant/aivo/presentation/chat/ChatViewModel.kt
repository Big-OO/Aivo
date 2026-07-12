package com.assistant.aivo.presentation.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assistant.aivo.core.Result
import com.assistant.aivo.core.models.ChatMessage
import com.assistant.aivo.core.models.MessageRole
import com.assistant.aivo.data.prefs.ApplicationSettingsRepository
import com.assistant.aivo.domain.agent.AiAgent
import com.assistant.aivo.domain.discovery.AppDiscoveryManager
import com.assistant.aivo.domain.discovery.DiscoveredApp
import com.assistant.aivo.domain.runner.UniversalAppFunctionRunner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val inputText: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val discoveredApps: List<DiscoveredApp> = emptyList(),
    val enabledPackages: Map<String, Boolean> = emptyMap(),
    val toolExecutionProgress: String? = null
)

class ChatViewModel(
    private val aiAgent: AiAgent,
    private val appDiscoveryManager: AppDiscoveryManager,
    private val settingsRepository: ApplicationSettingsRepository,
    private val appFunctionRunner: UniversalAppFunctionRunner
) : ViewModel() {

    private val _state = MutableStateFlow(ChatUiState())
    val state: StateFlow<ChatUiState> = _state.asStateFlow()

    init {
        loadDiscoveredApps()
    }

    fun loadDiscoveredApps() {
        viewModelScope.launch {
            val apps = appDiscoveryManager.discoverApps()
            val enabledMap = apps.associate { it.packageName to settingsRepository.isAppEnabled(it.packageName) }
            _state.update {
                it.copy(
                    discoveredApps = apps,
                    enabledPackages = enabledMap
                )
            }
        }
    }

    fun toggleAppEnabled(packageName: String) {
        val currentVal = _state.value.enabledPackages[packageName] ?: true
        val newVal = !currentVal
        settingsRepository.setAppEnabled(packageName, newVal)
        _state.update {
            it.copy(
                enabledPackages = it.enabledPackages + (packageName to newVal)
            )
        }
    }

    fun onInputTextChange(text: String) {
        _state.update { it.copy(inputText = text) }
    }

    fun sendMessage() {
        val currentText = _state.value.inputText.trim()
        if (currentText.isEmpty() || _state.value.isLoading) return

        val userMessage = ChatMessage(
            role = MessageRole.USER,
            content = currentText
        )

        val updatedMessages = _state.value.messages + userMessage

        _state.update {
            it.copy(
                messages = updatedMessages,
                inputText = "",
                isLoading = true,
                error = null,
                toolExecutionProgress = "Processing your request..."
            )
        }

        viewModelScope.launch {
            val activeApps = _state.value.discoveredApps.filter {
                _state.value.enabledPackages[it.packageName] == true
            }
            val activeTools = activeApps.flatMap { it.functions }

            val result = aiAgent.chat(
                messages = updatedMessages,
                tools = activeTools,
                onToolCallProgress = { progress ->
                    _state.update { it.copy(toolExecutionProgress = progress) }
                },
                executeTool = { packageName, functionName, args ->
                    appFunctionRunner.execute(packageName, functionName, args)
                }
            )

            when (result) {
                is Result.Success -> {
                    _state.update {
                        it.copy(
                            messages = it.messages + result.data,
                            isLoading = false,
                            toolExecutionProgress = null
                        )
                    }
                }
                is Result.Error -> {
                    _state.update {
                        it.copy(
                            isLoading = false,
                            error = result.message ?: "An unexpected error occurred",
                            toolExecutionProgress = null
                        )
                    }
                }
                is Result.Loading -> {
                    // Handled before launch
                }
            }
        }
    }

    fun clearChat() {
        _state.update {
            it.copy(
                messages = emptyList(),
                inputText = "",
                isLoading = false,
                error = null,
                toolExecutionProgress = null
            )
        }
    }

    companion object {
        fun provideFactory(
            aiAgent: AiAgent,
            appDiscoveryManager: AppDiscoveryManager,
            settingsRepository: ApplicationSettingsRepository,
            appFunctionRunner: UniversalAppFunctionRunner
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return ChatViewModel(
                    aiAgent,
                    appDiscoveryManager,
                    settingsRepository,
                    appFunctionRunner
                ) as T
            }
        }
    }
}

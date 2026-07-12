package com.assistant.aivo.presentation.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.assistant.aivo.core.Result
import com.assistant.aivo.core.models.ChatMessage as AgentChatMessage
import com.assistant.aivo.core.models.MessageRole
import com.assistant.aivo.data.prefs.ApplicationSettingsRepository
import com.assistant.aivo.domain.agent.AiAgent
import com.assistant.aivo.domain.discovery.AppDiscoveryManager
import com.assistant.aivo.domain.discovery.DiscoveredApp
import com.assistant.aivo.domain.runner.UniversalAppFunctionRunner
import com.assistant.aivo.core.NetworkMonitor
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds

enum class MessageType {
    TEXT, PRODUCT_LIST, COMPARISON, OUTFIT, ERROR
}

data class Product(
    val id: Long,
    val title: String,
    val price: Double,
    val vendor: String? = null,
    val imageUrl: String? = null
)

data class ChatMessage(
    val id: String = UUID.randomUUID().toString(),
    val text: String,
    val isUser: Boolean,
    val type: MessageType = MessageType.TEXT,
    val products: List<Product> = emptyList(),
    val isVoiceMessage: Boolean = false,
    val timestamp: Long = System.currentTimeMillis(),
    val options: List<String> = emptyList(),
    val isTypingFinished: Boolean = true
)

data class AIChatUiState(
    val messages: List<ChatMessage> = emptyList(),
    val isProcessing: Boolean = false,
    val statusMessage: String? = null,
    val discoveredApps: List<DiscoveredApp> = emptyList(),
    val enabledPackages: Map<String, Boolean> = emptyMap()
)

class AIChatViewModel(
    private val aiAgent: AiAgent,
    private val appDiscoveryManager: AppDiscoveryManager,
    private val settingsRepository: ApplicationSettingsRepository,
    private val appFunctionRunner: UniversalAppFunctionRunner,
    private val networkMonitor: NetworkMonitor
) : ViewModel() {

    private val _uiState = MutableStateFlow(AIChatUiState())
    val uiState: StateFlow<AIChatUiState> = _uiState.asStateFlow()

    val isOnline: StateFlow<Boolean> = networkMonitor.isOnline
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = networkMonitor.isCurrentlyConnected()
        )

    // Dynamic state for tracking favorites locally
    private val _favoriteIds = MutableStateFlow<Set<Long>>(emptySet())
    val favoriteIds: StateFlow<Set<Long>> = _favoriteIds.asStateFlow()

    val selectedCurrency = MutableStateFlow("USD")

    private val productCache = mutableMapOf<Long, Product>()

    init {
        val initialMessages = savedMessages ?: listOf(
            ChatMessage(
                text = "WELCOME_PLACEHOLDER",
                isUser = false
            )
        )
        _uiState.update {
            it.copy(messages = initialMessages)
        }
        loadDiscoveredApps()
        syncFavorites()
    }

    fun loadDiscoveredApps() {
        viewModelScope.launch {
            val apps = appDiscoveryManager.discoverApps()
            val enabledMap = apps.associate { it.packageName to settingsRepository.isAppEnabled(it.packageName) }
            _uiState.update {
                it.copy(
                    discoveredApps = apps,
                    enabledPackages = enabledMap
                )
            }
        }
    }

    fun toggleAppEnabled(packageName: String) {
        val currentVal = _uiState.value.enabledPackages[packageName] ?: true
        val newVal = !currentVal
        settingsRepository.setAppEnabled(packageName, newVal)
        _uiState.update {
            it.copy(
                enabledPackages = it.enabledPackages + (packageName to newVal)
            )
        }
    }

    private fun syncFavorites() {
        viewModelScope.launch {
            try {
                val result = appFunctionRunner.execute("com.shopify.carto", "showWishlist", emptyMap())
                val parsed = parseProducts(result)
                _favoriteIds.update { parsed.map { it.id }.toSet() }
            } catch (e: Exception) {
                Log.e("AivoDebug", "Failed to sync favorites", e)
            }
        }
    }

    fun toggleProductFavorite(product: Product) {
        val isFav = _favoriteIds.value.contains(product.id)
        viewModelScope.launch {
            try {
                if (isFav) {
                    appFunctionRunner.execute(
                        "com.shopify.carto",
                        "removeFromWishlist",
                        mapOf("productId" to JsonPrimitive(product.id))
                    )
                    _favoriteIds.update { it - product.id }
                } else {
                    appFunctionRunner.execute(
                        "com.shopify.carto",
                        "addToWishlist",
                        mapOf("productId" to JsonPrimitive(product.id))
                    )
                    _favoriteIds.update { it + product.id }
                }
            } catch (e: Exception) {
                Log.e("AivoDebug", "Failed to toggle favorite", e)
            }
        }
    }

    fun addProductToCart(productId: Long) {
        viewModelScope.launch {
            try {
                appFunctionRunner.execute(
                    "com.shopify.carto",
                    "addToCart",
                    mapOf(
                        "productId" to JsonPrimitive(productId),
                        "quantity" to JsonPrimitive(1),
                        "size" to JsonPrimitive(""),
                        "color" to JsonPrimitive("")
                    )
                )
            } catch (e: Exception) {
                Log.e("AivoDebug", "Failed to add to cart", e)
            }
        }
    }

    fun sendMessage(text: String, isVoice: Boolean = false) {
        if (text.isBlank() || _uiState.value.isProcessing) return

        val userMessage = ChatMessage(text = text, isUser = true, isVoiceMessage = isVoice)
        _uiState.update {
            it.copy(
                messages = it.messages + userMessage,
                isProcessing = true,
                statusMessage = "Thinking..."
            )
        }

        executeMessageQuery(text)
    }

    fun regenerateLastResponse() {
        if (_uiState.value.isProcessing) return

        val lastUserMessage = _uiState.value.messages.lastOrNull { it.isUser } ?: return
        val indexOfLastUser = _uiState.value.messages.lastIndexOf(lastUserMessage)

        _uiState.update {
            it.copy(
                messages = it.messages.subList(0, indexOfLastUser + 1),
                isProcessing = true,
                statusMessage = "Thinking..."
            )
        }

        executeMessageQuery(lastUserMessage.text)
    }

    private fun executeMessageQuery(text: String) {
        viewModelScope.launch {
            try {
                val agentMessages = _uiState.value.messages.map { msg ->
                    AgentChatMessage(
                        role = if (msg.isUser) MessageRole.USER else MessageRole.ASSISTANT,
                        content = msg.text
                    )
                }

                val activeApps = _uiState.value.discoveredApps.filter {
                    _uiState.value.enabledPackages[it.packageName] == true
                }
                val activeTools = activeApps.flatMap { it.functions }

                val result = aiAgent.chat(
                    messages = agentMessages,
                    tools = activeTools,
                    onToolCallProgress = { step ->
                        _uiState.update { it.copy(statusMessage = step) }
                    },
                    executeTool = { packageName, functionName, args ->
                        val res = appFunctionRunner.execute(packageName, functionName, args)
                        if (functionName == "searchProducts" || functionName == "getProductDetails") {
                            val parsed = parseProducts(res)
                            parsed.forEach { productCache[it.id] = it }
                        }
                        res
                    }
                )

                when (result) {
                    is Result.Success -> {
                        val agentResponse = result.data.content

                        val optionsRegex = Regex("""\[Options:\s*([^\]]+)\]""", RegexOption.IGNORE_CASE)
                        val optionsMatch = optionsRegex.find(agentResponse)
                        val extractedOptions = if (optionsMatch != null) {
                            optionsMatch.groupValues[1].split("|").map { it.trim() }.filter { it.isNotEmpty() }
                        } else {
                            emptyList()
                        }

                        val responseWithoutOptions = agentResponse.replace(optionsRegex, "").trim()
                        val cleanedText = responseWithoutOptions
                            .replace(Regex("""\(\s*Product ID:\s*\d+\s*\)""", RegexOption.IGNORE_CASE), "")
                            .replace(Regex("""\s*Product ID:\s*\d+""", RegexOption.IGNORE_CASE), "")
                            .trim()

                        val productIds = extractProductIdsFromString(agentResponse)
                        val recommendedProducts = mutableListOf<Product>()

                        productIds.forEach { id ->
                            val cached = productCache[id]
                            if (cached != null) {
                                recommendedProducts.add(cached)
                            } else {
                                try {
                                    val detailsText = appFunctionRunner.execute(
                                        "com.shopify.carto",
                                        "getProductDetails",
                                        mapOf("productId" to JsonPrimitive(id))
                                    )
                                    val parsed = parseProducts(detailsText)
                                    val product = parsed.firstOrNull()
                                    if (product != null) {
                                        productCache[id] = product
                                        recommendedProducts.add(product)
                                    }
                                } catch (e: Exception) {
                                    Log.e("AivoDebug", "Failed to fetch details for product $id", e)
                                }
                            }
                        }

                        val targetText = if (recommendedProducts.isNotEmpty()) {
                            extractIntroText(cleanedText)
                        } else {
                            cleanedText.ifBlank { "Here you go!" }
                        }

                        val aiMessageId = UUID.randomUUID().toString()

                        val aiMessage = ChatMessage(
                            id = aiMessageId,
                            text = "",
                            isUser = false,
                            type = if (recommendedProducts.isNotEmpty()) MessageType.PRODUCT_LIST else MessageType.TEXT,
                            products = recommendedProducts,
                            options = extractedOptions,
                            isTypingFinished = false
                        )

                        _uiState.update {
                            it.copy(
                                messages = it.messages + aiMessage,
                                statusMessage = null
                            )
                        }

                        val words = targetText.split(" ")
                        var currentText = ""
                        for (i in words.indices) {
                            currentText += (if (i == 0) "" else " ") + words[i]
                            _uiState.update { state ->
                                state.copy(
                                    messages = state.messages.map { msg ->
                                        if (msg.id == aiMessageId) {
                                            msg.copy(text = currentText)
                                        } else {
                                            msg
                                        }
                                    }
                                )
                            }
                            delay(25.milliseconds)
                        }

                        _uiState.update { state ->
                            state.copy(
                                messages = state.messages.map { msg ->
                                    if (msg.id == aiMessageId) {
                                        msg.copy(isTypingFinished = true)
                                    } else {
                                        msg
                                    }
                                },
                                isProcessing = false
                            )
                        }
                    }
                    is Result.Error -> {
                        val errorMessage = ChatMessage(
                            text = "Sorry, I encountered an error: ${result.message}",
                            isUser = false,
                            type = MessageType.ERROR
                        )
                        _uiState.update {
                            it.copy(
                                messages = it.messages + errorMessage,
                                isProcessing = false,
                                statusMessage = null
                            )
                        }
                    }
                    else -> Unit
                }
            } catch (e: Exception) {
                val errorMessage = ChatMessage(
                    text = "Sorry, I encountered an error: ${e.localizedMessage}",
                    isUser = false,
                    type = MessageType.ERROR
                )
                _uiState.update {
                    it.copy(
                        messages = it.messages + errorMessage,
                        isProcessing = false,
                        statusMessage = null
                    )
                }
            }
        }
    }

    private fun extractIntroText(fullText: String): String {
        val lines = fullText.lines()
        val introLines = mutableListOf<String>()

        val productDetailPatterns = listOf(
            Regex("""^\d+[\.\)]\s+.{3,}"""),
            Regex("""^[-*•]\s+\*\*.+\*\*"""),
            Regex("""^[-*•]\s+.+(price|EGP|USD|\$|£)""", RegexOption.IGNORE_CASE),
            Regex("""^\*\*.+\*\*\s*[-–:]\s*"""),
            Regex("""^#+\s+(here|product|item|result|option|suggestion)""", RegexOption.IGNORE_CASE)
        )

        for (line in lines) {
            val trimmed = line.trim()
            if (trimmed.isBlank()) {
                if (introLines.isNotEmpty()) break
                continue
            }
            if (productDetailPatterns.any { it.containsMatchIn(trimmed) }) break
            introLines.add(trimmed)
            if (introLines.size >= 2) break
        }

        return introLines.joinToString(" ").trim()
            .ifBlank { "Here are some items I found for you:" }
    }

    private fun extractProductIdsFromString(text: String): List<Long> {
        val ids = mutableListOf<Long>()
        val digit13Regex = Regex("""\b(\d{13})\b""")
        digit13Regex.findAll(text).forEach { match ->
            match.value.toLongOrNull()?.let { ids.add(it) }
        }
        val labelRegex = Regex("""Product ID:\s*(\d+)""", RegexOption.IGNORE_CASE)
        labelRegex.findAll(text).forEach { match ->
            match.groupValues[1].toLongOrNull()?.let { ids.add(it) }
        }
        return ids.distinct()
    }

    private fun parseProducts(text: String): List<Product> {
        Log.d("AivoDebug", "parseProducts: Raw data received from Carto:\n$text")
        val products = mutableListOf<Product>()
        val blocks = text.split("---")
        for (block in blocks) {
            val lines = block.lines().map { it.trim() }
            var id: Long? = null
            var title = ""
            var price = 0.0
            var vendor: String? = null
            var imageUrl: String? = null
            for (line in lines) {
                when {
                    line.startsWith("Product ID:", ignoreCase = true) -> {
                        id = line.substringAfter(":").trim().toLongOrNull()
                    }
                    line.startsWith("Title:", ignoreCase = true) -> {
                        title = line.substringAfter(":").trim()
                    }
                    line.startsWith("Price:", ignoreCase = true) -> {
                        val priceStr = line.substringAfter(":").trim().substringBefore(" ").trim()
                        price = priceStr.toDoubleOrNull() ?: 0.0
                    }
                    line.startsWith("Vendor:", ignoreCase = true) -> {
                        vendor = line.substringAfter(":").trim()
                    }
                    line.startsWith("Image URL:", ignoreCase = true) -> {
                        imageUrl = line.substringAfter(":").trim()
                    }
                }
            }
            if (id != null && title.isNotEmpty()) {
                val parsed = Product(id, title, price, vendor, imageUrl)
                Log.d("AivoDebug", "parseProducts: Parsed Product: id=$id, title='$title', price=$price, vendor='$vendor', imageUrl='$imageUrl'")
                products.add(parsed)
            }
        }
        Log.d("AivoDebug", "parseProducts: Total parsed products size: ${products.size}")
        return products
    }

    private fun updateUiState(update: (AIChatUiState) -> AIChatUiState) {
        _uiState.update {
            val newState = update(it)
            savedMessages = newState.messages
            newState
        }
    }

    companion object {
        private var savedMessages: List<ChatMessage>? = null

        fun provideFactory(
            aiAgent: AiAgent,
            appDiscoveryManager: AppDiscoveryManager,
            settingsRepository: ApplicationSettingsRepository,
            appFunctionRunner: UniversalAppFunctionRunner,
            networkMonitor: NetworkMonitor
        ): ViewModelProvider.Factory = object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return AIChatViewModel(
                    aiAgent,
                    appDiscoveryManager,
                    settingsRepository,
                    appFunctionRunner,
                    networkMonitor
                ) as T
            }
        }
    }
}

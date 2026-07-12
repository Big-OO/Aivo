package com.assistant.aivo.presentation.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.material.icons.filled.WifiOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.assistant.aivo.domain.discovery.DiscoveredApp
import com.assistant.aivo.voice.SpeechRecognizerManager
import com.assistant.aivo.voice.VoiceRecognitionState
import com.assistant.aivo.presentation.ui.components.*
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AIChatScreen(
    viewModel: AIChatViewModel,
    modifier: Modifier = Modifier,
    onBackClick: () -> Unit = {},
    onCheckoutClick: () -> Unit = {}
) {
    val uiState by viewModel.uiState.collectAsState()
    val favoriteIds by viewModel.favoriteIds.collectAsState()
    val isOnline by viewModel.isOnline.collectAsState()
    val scope = rememberCoroutineScope()
    val listState = rememberLazyListState()
    val snackbarHostState = remember { SnackbarHostState() }
    val context = LocalContext.current

    var showOnlineBanner by remember { mutableStateOf(false) }
    var wasOffline by remember { mutableStateOf(false) }

    LaunchedEffect(isOnline) {
        if (!isOnline) {
            wasOffline = true
            showOnlineBanner = false
        } else {
            if (wasOffline) {
                showOnlineBanner = true
                kotlinx.coroutines.delay(3000)
                showOnlineBanner = false
                wasOffline = false
            }
        }
    }

    var textInput by remember { mutableStateOf("") }
    var voiceState by remember { mutableStateOf<VoiceRecognitionState>(VoiceRecognitionState.Idle) }
    val rmsHistory = remember { mutableStateListOf<Float>() }

    val manager = remember {
        SpeechRecognizerManager(context) { state ->
            voiceState = state
            if (state is VoiceRecognitionState.Listening) {
                rmsHistory.add(state.rmsDb.coerceIn(0f, 12f))
                if (rmsHistory.size > 40) rmsHistory.removeFirst()
            }
        }
    }
    DisposableEffect(Unit) {
        onDispose { manager.destroy() }
    }

    var isHoldingMic by remember { mutableStateOf(false) }
    var voiceAccumulatedText by remember { mutableStateOf("") }

    LaunchedEffect(voiceState) {
        when (val s = voiceState) {
            is VoiceRecognitionState.Partial -> {
                voiceAccumulatedText = s.text
                textInput = s.text
            }
            is VoiceRecognitionState.Error   -> {
                if (isHoldingMic) {
                    manager.startListening()
                } else {
                    voiceState = VoiceRecognitionState.Idle
                    rmsHistory.clear()
                }
            }
            is VoiceRecognitionState.Result  -> {
                if (s.text.isNotBlank()) {
                    voiceAccumulatedText = s.text
                    textInput = s.text
                }
                if (isHoldingMic) {
                    manager.startListening()
                }
            }
            else -> Unit
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            voiceState = VoiceRecognitionState.ReadyForSpeech
            rmsHistory.clear()
            manager.startListening()
        } else {
            isHoldingMic = false
            scope.launch { snackbarHostState.showSnackbar("Microphone permission required") }
        }
    }

    LaunchedEffect(
        uiState.messages.size,
        uiState.isProcessing,
        uiState.statusMessage,
        uiState.messages.lastOrNull()?.text
    ) {
        val messageCount = uiState.messages.size
        val hasThinking = uiState.isProcessing && uiState.statusMessage != null
        val totalCount = messageCount + (if (hasThinking) 1 else 0)
        if (totalCount > 0) {
            listState.animateScrollToItem(totalCount - 1)
        }
    }

    val isListening = isHoldingMic

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = { ChatHeader(onBackClick = onBackClick) },
        modifier = modifier
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            // Connectivity warnings and animations
            AnimatedVisibility(
                visible = !isOnline,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                Surface(
                    color = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.WifiOff,
                            contentDescription = "Offline",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "You are offline. Please check connection.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            AnimatedVisibility(
                visible = showOnlineBanner,
                enter = slideInVertically { -it } + fadeIn(),
                exit = slideOutVertically { -it } + fadeOut()
            ) {
                Surface(
                    color = Color(0xFFE8F5E9), // Premium light green
                    contentColor = Color(0xFF2E7D32), // Emerald green
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(vertical = 8.dp, horizontal = 16.dp),
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.Wifi,
                            contentDescription = "Back Online",
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "Back online! Connection restored.",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }

            // Horizontal Discovered Apps strip (like basic screen but styled beautifully)
            if (uiState.discoveredApps.isNotEmpty()) {
                androidx.compose.foundation.lazy.LazyRow(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    items(uiState.discoveredApps) { app ->
                        val isEnabled = uiState.enabledPackages[app.packageName] ?: true
                        AppMetadataCard(
                            discoveredApp = app,
                            isEnabled = isEnabled,
                            onToggleEnabled = { viewModel.toggleAppEnabled(app.packageName) }
                        )
                    }
                }
            }

            // Chat messages area
            Box(modifier = Modifier.weight(1f)) {
                if (uiState.messages.isEmpty() ||
                    (uiState.messages.size == 1 && uiState.messages.first().text.isBlank())
                ) {
                    EmptyState(onSuggestionClick = { viewModel.sendMessage(it) })
                } else {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(uiState.messages, key = { it.id }) { message ->
                            val isLast = uiState.messages.lastOrNull() == message
                            MessageBubble(
                                message = message,
                                favoriteIds = favoriteIds,
                                isLastAiMessage = isLast && !message.isUser,
                                onProductClick = { productId ->
                                    viewModel.sendMessage("Show details for product $productId")
                                },
                                onFavoriteClick = { viewModel.toggleProductFavorite(it) },
                                onAddToCartClick = { product ->
                                    viewModel.addProductToCart(product.id)
                                    scope.launch {
                                        snackbarHostState.showSnackbar("Added ${product.title} to cart")
                                    }
                                },
                                onRegenerateClick = { viewModel.regenerateLastResponse() },
                                onOptionClick = { option ->
                                    if (option.equals("Go to Checkout", ignoreCase = true)) {
                                        onCheckoutClick()
                                    } else {
                                        viewModel.sendMessage(option)
                                    }
                                },
                                snackbarHostState = snackbarHostState,
                                context = context
                            )
                        }

                        val statusMsg = uiState.statusMessage
                        if (uiState.isProcessing && statusMsg != null) {
                            item { ThinkingBubble(statusMessage = statusMsg) }
                        }
                    }
                }
            }

            // Recording overlay
            AnimatedVisibility(
                visible = isListening,
                enter = fadeIn(tween(150)) + expandVertically(tween(150)),
                exit  = fadeOut(tween(150)) + shrinkVertically(tween(150))
            ) {
                VoiceRecordingBar(
                    rmsHistory = rmsHistory.toList(),
                    onCancelClick = {
                        isHoldingMic = false
                        manager.stopListening()
                        textInput = ""
                        voiceAccumulatedText = ""
                        voiceState = VoiceRecognitionState.Idle
                        rmsHistory.clear()
                    }
                )
            }

            // Input bar
            ChatInput(
                textInput = textInput,
                isListening = isListening,
                isProcessing = uiState.isProcessing,
                onValueChange = { textInput = it },
                enabled = isOnline,
                onSendClick = {
                    if (textInput.isNotBlank()) {
                        viewModel.sendMessage(textInput)
                        textInput = ""
                    }
                },
                onMicPressStart = {
                    isHoldingMic = true
                    voiceAccumulatedText = ""
                    permissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                },
                onMicPressEnd = {
                    if (isHoldingMic) {
                        isHoldingMic = false
                        manager.stopListening()
                        if (voiceAccumulatedText.isNotBlank()) {
                            viewModel.sendMessage(voiceAccumulatedText, isVoice = true)
                        }
                        textInput = ""
                        voiceAccumulatedText = ""
                        voiceState = VoiceRecognitionState.Idle
                        rmsHistory.clear()
                    }
                },
                onMicCancel = {
                    if (isHoldingMic) {
                        isHoldingMic = false
                        manager.stopListening()
                        textInput = ""
                        voiceAccumulatedText = ""
                        voiceState = VoiceRecognitionState.Idle
                        rmsHistory.clear()
                    }
                }
            )
        }
    }
}

@Composable
fun AppMetadataCard(
    discoveredApp: DiscoveredApp,
    isEnabled: Boolean,
    onToggleEnabled: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.width(200.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = discoveredApp.appName,
                    style = MaterialTheme.typography.titleMedium,
                    maxLines = 1
                )
                Text(
                    text = "${discoveredApp.functionCount} functions",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isEnabled,
                onCheckedChange = { onToggleEnabled() },
                modifier = Modifier.padding(start = 4.dp)
            )
        }
    }
}

package com.assistant.aivo.presentation.ui.components

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.util.Log
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import com.assistant.aivo.R
import com.assistant.aivo.presentation.ui.ChatMessage
import com.assistant.aivo.presentation.ui.MessageType
import com.assistant.aivo.presentation.ui.Product

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MessageBubble(
    message: ChatMessage,
    favoriteIds: Set<Long>,
    isLastAiMessage: Boolean,
    onProductClick: (Long) -> Unit,
    onFavoriteClick: (Product) -> Unit,
    onAddToCartClick: (Product) -> Unit,
    onRegenerateClick: () -> Unit,
    onOptionClick: (String) -> Unit,
    snackbarHostState: SnackbarHostState,
    context: Context,
    modifier: Modifier = Modifier
) {
    val isUser = message.isUser
    val scope = rememberCoroutineScope()
    val userShape = RoundedCornerShape(20.dp, 20.dp, 4.dp, 20.dp)
    val aiShape   = RoundedCornerShape(4.dp, 20.dp, 20.dp, 20.dp)
    val hasProducts = message.products.isNotEmpty()

    val animVisible = rememberSaveable(message.id) { mutableStateOf(false) }
    LaunchedEffect(Unit) {
        animVisible.value = true
    }

    AnimatedVisibility(
        visible = animVisible.value,
        enter = fadeIn(animationSpec = tween(400, easing = FastOutSlowInEasing)) + 
                slideInVertically(animationSpec = tween(400, easing = FastOutSlowInEasing)) { it / 3 },
        exit = fadeOut(animationSpec = tween(200))
    ) {
        Column(
            modifier = modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(6.dp)
        ) {

            // ── AI product response: cards FIRST at full width ──────────────────
            if (!isUser && hasProducts) {
                ProductsCarousel(
                    products = message.products,
                    favoriteIds = favoriteIds,
                    onProductClick = onProductClick,
                    onFavoriteClick = onFavoriteClick,
                    onAddToCartClick = onAddToCartClick
                )
            }

            // ── Message bubble row ────────────────────────────────────────────────
            val skipTextBubble = !isUser && hasProducts && message.text.isBlank()

            if (!skipTextBubble) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start,
                    verticalAlignment = Alignment.Bottom
                ) {
                    // AI avatar
                    if (!isUser) {
                        Surface(
                            modifier = Modifier.padding(end = 6.dp, bottom = 2.dp).size(26.dp),
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer
                        ) {
                            Icon(
                                imageVector = Icons.Default.AutoAwesome,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.padding(5.dp)
                            )
                        }
                    }

                    Column(
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .then(
                                if (isUser) Modifier.padding(start = 52.dp)
                                else Modifier.padding(end = 52.dp)
                            )
                    ) {
                        when {
                            // Error bubble
                            message.type == MessageType.ERROR -> {
                                ErrorCard(
                                    errorMessage = message.text,
                                    onRetryClick = onRegenerateClick
                                )
                            }
                            // Voice message bubble (user)
                            message.isVoiceMessage && isUser -> {
                                VoiceMessageBubble()
                            }
                            // Normal or product-intro text bubble
                            else -> {
                                if (message.text.isNotBlank()) {
                                    Surface(
                                        color = if (isUser)
                                            MaterialTheme.colorScheme.primary
                                        else
                                            MaterialTheme.colorScheme.surfaceVariant,
                                        shape = if (isUser) userShape else aiShape
                                    ) {
                                        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
                                            MarkdownRenderer(
                                                text = if (message.text == "WELCOME_PLACEHOLDER") {
                                                    stringResource(id = R.string.ai_welcome_assistant_message)
                                                } else {
                                                    message.text
                                                },
                                                color = if (isUser) Color.White
                                                        else MaterialTheme.colorScheme.onSurface
                                            )
                                        }
                                    }
                                }

                                if (!isUser && message.options.isNotEmpty()) {
                                    var optionsVisible by remember { mutableStateOf(false) }
                                    LaunchedEffect(message.isTypingFinished) {
                                        if (message.isTypingFinished) {
                                            optionsVisible = true
                                        }
                                    }
                                    AnimatedVisibility(
                                        visible = optionsVisible,
                                        enter = fadeIn(animationSpec = tween(300)),
                                        exit = fadeOut(animationSpec = tween(300))
                                    ) {
                                        Column {
                                            Spacer(modifier = Modifier.height(8.dp))

                                            val productOptions = remember(message.options) {
                                                message.options.filter {
                                                    it.startsWith("quantity(", ignoreCase = true) ||
                                                    it.startsWith("size(", ignoreCase = true) ||
                                                    it.startsWith("color(", ignoreCase = true)
                                                }
                                            }
                                            val normalOptions = remember(message.options) {
                                                message.options.filter {
                                                    !it.startsWith("quantity(", ignoreCase = true) &&
                                                    !it.startsWith("size(", ignoreCase = true) &&
                                                    !it.startsWith("color(", ignoreCase = true)
                                                }
                                            }

                                            if (productOptions.isNotEmpty()) {
                                                val quantityRegex = remember { Regex("""quantity\((.+)\)""", RegexOption.IGNORE_CASE) }
                                                val sizeRegex = remember { Regex("""size\((.+)\)""", RegexOption.IGNORE_CASE) }
                                                val colorRegex = remember { Regex("""color\((.+)\)""", RegexOption.IGNORE_CASE) }

                                                val quantityValues = remember(productOptions) {
                                                    productOptions.firstOrNull { it.startsWith("quantity(", ignoreCase = true) }?.let { opt ->
                                                        quantityRegex.find(opt)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                                                    }
                                                }
                                                val sizeValues = remember(productOptions) {
                                                    productOptions.firstOrNull { it.startsWith("size(", ignoreCase = true) }?.let { opt ->
                                                        sizeRegex.find(opt)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                                                    }
                                                }
                                                val colorValues = remember(productOptions) {
                                                    productOptions.firstOrNull { it.startsWith("color(", ignoreCase = true) }?.let { opt ->
                                                        colorRegex.find(opt)?.groupValues?.get(1)?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
                                                    }
                                                }

                                                var selectedQuantity by remember { mutableStateOf<String?>(null) }
                                                var selectedSize by remember { mutableStateOf<String?>(null) }
                                                var selectedColor by remember { mutableStateOf<String?>(null) }

                                                val isQuantityRequired = quantityValues != null
                                                val isSizeRequired = sizeValues != null
                                                val isColorRequired = colorValues != null

                                                val allSelected = (!isQuantityRequired || selectedQuantity != null) &&
                                                                  (!isSizeRequired || selectedSize != null) &&
                                                                  (!isColorRequired || selectedColor != null)

                                                Card(
                                                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                                                    shape = RoundedCornerShape(16.dp),
                                                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)),
                                                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
                                                ) {
                                                    Column(modifier = Modifier.padding(14.dp)) {
                                                        Text(
                                                            text = "Select Item Options",
                                                            style = MaterialTheme.typography.titleSmall,
                                                            fontWeight = FontWeight.Bold,
                                                            color = MaterialTheme.colorScheme.primary
                                                        )
                                                        Spacer(modifier = Modifier.height(10.dp))

                                                        // Quantity Selector
                                                        if (quantityValues != null) {
                                                            Text(
                                                                text = "Quantity",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            FlowRow(
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                quantityValues.forEach { qty ->
                                                                    Surface(
                                                                        onClick = { selectedQuantity = qty },
                                                                        shape = RoundedCornerShape(8.dp),
                                                                        color = if (selectedQuantity == qty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                                        contentColor = if (selectedQuantity == qty) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                        border = BorderStroke(1.dp, if (selectedQuantity == qty) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = qty,
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.height(10.dp))
                                                        }

                                                        // Size Selector
                                                        if (sizeValues != null) {
                                                            Text(
                                                                text = "Size",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            FlowRow(
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                sizeValues.forEach { sz ->
                                                                    Surface(
                                                                        onClick = { selectedSize = sz },
                                                                        shape = RoundedCornerShape(8.dp),
                                                                        color = if (selectedSize == sz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                                        contentColor = if (selectedSize == sz) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                        border = BorderStroke(1.dp, if (selectedSize == sz) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = sz,
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.height(10.dp))
                                                        }

                                                        // Color Selector
                                                        if (colorValues != null) {
                                                            Text(
                                                                text = "Color",
                                                                style = MaterialTheme.typography.labelMedium,
                                                                fontWeight = FontWeight.SemiBold
                                                            )
                                                            Spacer(modifier = Modifier.height(4.dp))
                                                            FlowRow(
                                                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                                                verticalArrangement = Arrangement.spacedBy(6.dp)
                                                            ) {
                                                                colorValues.forEach { col ->
                                                                    Surface(
                                                                        onClick = { selectedColor = col },
                                                                        shape = RoundedCornerShape(8.dp),
                                                                        color = if (selectedColor == col) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surface,
                                                                        contentColor = if (selectedColor == col) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
                                                                        border = BorderStroke(1.dp, if (selectedColor == col) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant),
                                                                        modifier = Modifier.padding(vertical = 2.dp)
                                                                    ) {
                                                                        Text(
                                                                            text = col,
                                                                            style = MaterialTheme.typography.bodyMedium,
                                                                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)
                                                                        )
                                                                    }
                                                                }
                                                            }
                                                            Spacer(modifier = Modifier.height(12.dp))
                                                        }

                                                        Button(
                                                            onClick = {
                                                                if (allSelected) {
                                                                    val parts = mutableListOf<String>()
                                                                    if (isQuantityRequired) parts.add("Quantity: $selectedQuantity")
                                                                    if (isSizeRequired) parts.add("Size: $selectedSize")
                                                                    if (isColorRequired) parts.add("Color: $selectedColor")
                                                                    onOptionClick(parts.joinToString(", "))
                                                                }
                                                            },
                                                            enabled = allSelected,
                                                            modifier = Modifier.fillMaxWidth(),
                                                            shape = RoundedCornerShape(10.dp)
                                                        ) {
                                                            Text("Confirm Selections")
                                                        }
                                                    }
                                                }
                                            }

                                            if (normalOptions.isNotEmpty()) {
                                                FlowRow(
                                                    modifier = Modifier.fillMaxWidth(),
                                                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                                                    verticalArrangement = Arrangement.spacedBy(8.dp)
                                                ) {
                                                    val sortedOptions = remember(normalOptions) { normalOptions.sorted() }
                                                    sortedOptions.forEachIndexed { index, option ->
                                                        var chipVisible by remember { mutableStateOf(false) }
                                                        LaunchedEffect(Unit) {
                                                            delay(index * 60L)
                                                            chipVisible = true
                                                        }
                                                        AnimatedVisibility(
                                                            visible = chipVisible,
                                                            enter = fadeIn(tween(250)) + slideInVertically(tween(250)) { it / 2 },
                                                            exit = fadeOut(tween(150))
                                                        ) {
                                                            val interactionSource = remember { MutableInteractionSource() }
                                                            val isPressed by interactionSource.collectIsPressedAsState()
                                                            val scale by animateFloatAsState(
                                                                targetValue = if (isPressed) 0.95f else 1f,
                                                                animationSpec = spring(
                                                                    dampingRatio = Spring.DampingRatioMediumBouncy,
                                                                    stiffness = Spring.StiffnessLow
                                                                ),
                                                                label = "chipScale"
                                                            )
                                                            Surface(
                                                                onClick = { onOptionClick(option) },
                                                                shape = RoundedCornerShape(20.dp),
                                                                color = MaterialTheme.colorScheme.primaryContainer,
                                                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                                                border = BorderStroke(
                                                                    width = 1.dp,
                                                                    color = MaterialTheme.colorScheme.primary.copy(alpha = 0.15f)
                                                                ),
                                                                shadowElevation = if (isPressed) 1.dp else 2.dp,
                                                                interactionSource = interactionSource,
                                                                modifier = Modifier
                                                                    .graphicsLayer {
                                                                        scaleX = scale
                                                                        scaleY = scale
                                                                    }
                                                            ) {
                                                                Text(
                                                                    text = option,
                                                                    style = MaterialTheme.typography.bodyMedium,
                                                                    fontWeight = FontWeight.SemiBold,
                                                                    modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)
                                                                )
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        // Action row
                        if (!isUser && message.type != MessageType.ERROR) {
                            CopyActionRow(
                                showRegenerate = isLastAiMessage,
                                onCopyClick = {
                                    val cb = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val welcomeText = if (message.text == "WELCOME_PLACEHOLDER") {
                                        context.getString(R.string.ai_welcome_assistant_message)
                                    } else {
                                        message.text
                                    }
                                    cb.setPrimaryClip(ClipData.newPlainText("Aivo AI", welcomeText))
                                    scope.launch { snackbarHostState.showSnackbar(context.getString(R.string.ai_copied_toast)) }
                                },
                                onRegenerateClick = onRegenerateClick
                            )
                        }
                    }

                    if (isUser) Spacer(modifier = Modifier.size(6.dp))
                }
            }

            if (isUser && hasProducts) {
                ProductsCarousel(
                    products = message.products,
                    favoriteIds = favoriteIds,
                    onProductClick = onProductClick,
                    onFavoriteClick = onFavoriteClick,
                    onAddToCartClick = onAddToCartClick
                )
            }
        }
    }
}

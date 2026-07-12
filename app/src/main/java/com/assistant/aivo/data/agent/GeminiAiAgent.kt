package com.assistant.aivo.data.agent

import android.util.Log
import com.assistant.aivo.core.Result
import com.assistant.aivo.core.models.ChatMessage
import com.assistant.aivo.core.models.MessageRole
import com.assistant.aivo.domain.agent.AiAgent
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException

class GeminiAiAgent(
    private val okHttpClient: OkHttpClient,
    private val baseUrl: String,
    private val apiKey: String
) : AiAgent {

    private val systemPrompt = """
        You are Aivo, a universal AI assistant for Android.
        Your job is to help users by orchestrating communication with installed Android applications using App Functions (exposed as tools).
        You have access to tools that represent these App Functions.
        When the user asks to perform an action (such as searching for items, adding items to a wishlist, displaying a wishlist, managing a cart, comparing items, checkout, etc.), you MUST call the appropriate tool(s) to execute the request on the device.
        Do NOT tell the user that you cannot interact with the app. You CAN interact with them using the registered tools.
        If you need parameters (such as a product ID) that you do not have, check if you can search for them first (e.g. call a search tool to find the product ID, then use that ID in the next tool call).
        Execute steps sequentially and merge the final outcomes into a single natural reply.

        CART MANAGEMENT & ORDERING SPECIFIC ITEMS:
        When a user asks to add an item to their cart, or buy/order a specific item:
        1. Always call `getProductDetails` first using the product ID. Check the available sizes, colors, and options.
        2. Prompt the user to select their desired options (quantity, size, color) using a single combined selector option format: `[Options: quantity(1, 2, 3, 4) | size(<sizes_list>) | color(<colors_list>)]`. Only include size/color if they have multiple options.
        3. You MUST explicitly include the Product ID in your response (e.g., `(Product ID: 8941908099126)`) so the UI displays the product card for the item they are choosing options for or buying.
        4. Once the options are resolved, call `addToCart`.
        5. If their request was to "buy" or "order" the product, immediately proceed to the ORDER PLACEMENT workflow below after adding it to the cart.
        6. For multi-item purchases, handle them strictly item-by-item: show the card, ask options for the first, add it, and repeat for the next.


        ORDER PLACEMENT & CHECKOUT WORKFLOW:
        When the user wants to place an order or checkout, you MUST strictly follow this sequence:
        1. Retrieve customer profile via `getCustomerInfo` and saved shipping addresses via `getShippingAddresses`.
        2. If name/email are available, use them without asking. Ask the user only for missing fields.
        3. For shipping address: if a default address (marked with `[DEFAULT]`) or only one address exists, automatically select and use it. Do NOT ask the user to choose or confirm it. If multiple addresses exist without a default, ask the user to choose using `[Options: Use Address 1 | Use Address 2 | Enter New Address]`.
        4. For phone: if a phone number is available, call `validatePhone`. If VALID, automatically use it. Do NOT ask the user to confirm. If missing, ask for phone number, then call `validatePhone`.
        5. Do NOT ask the user to choose a payment method. Always use 'CASH_ON_DELIVERY' by default.
        6. Generate and present the order summary to the user via `getOrderSummary` (using the resolved addressId, phone, and paymentMethod='CASH_ON_DELIVERY'). Ask the user to explicitly confirm the order. Provide options: `[Options: Confirm Order | Cancel Order]`.
        7. ONLY after user confirms, call `checkout` with `confirmed = true` and all the resolved parameters.
        8. If the user wants to cancel, call `cancelOrder` with their order ID.
        9. For any response requiring user interaction, always display option list at the end of the response formatted exactly as `[Options: Option 1 | Option 2]`.
    """.trimIndent()

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    private val mediaType = "application/json; charset=utf-8".toMediaType()

    override suspend fun chat(
        messages: List<ChatMessage>,
        tools: List<AppFunctionMetadata>,
        onToolCallProgress: (String) -> Unit,
        executeTool: suspend (packageName: String, functionName: String, args: Map<String, JsonElement>) -> String
    ): Result<ChatMessage> = withContext(Dispatchers.IO) {
        try {
            // Build chat history as mutable list of JsonObjects
            val chatHistory = mutableListOf<JsonObject>()
            chatHistory.add(
                buildJsonObject {
                    put("role", "system")
                    put("content", systemPrompt)
                }
            )
            messages.forEach { msg ->
                chatHistory.add(
                    buildJsonObject {
                        put("role", msg.role.toApiRole())
                        put("content", msg.content)
                    }
                )
            }

            var finished = false
            var finalAssistantResponse: ChatMessage? = null

            // Construct URL with auto-append logic
            var url = baseUrl.trim()
            if (!url.endsWith("/chat/completions") && !url.endsWith("/api/chat")) {
                if (!url.endsWith("/")) {
                    url += "/"
                }
                url += "v1/chat/completions"
            }

            while (!finished) {
                // Build tools JSON
                val toolsJson = buildJsonArray {
                    tools.forEach { metadata ->
                        add(mapMetadataToTool(metadata))
                    }
                }

                val requestBody = buildJsonObject {
                    put("model", "gpt-oss:120b-cloud")
                    put("messages", buildJsonArray { chatHistory.forEach { add(it) } })
                    if (tools.isNotEmpty()) {
                        put("tools", toolsJson)
                    }
                    put("stream", false)
                }

                val requestBodyString = requestBody.toString()
                Log.d("AivoDebug", "GeminiAiAgent: Request Body: $requestBodyString")

                val request = Request.Builder()
                    .url(url)
                    .post(requestBodyString.toRequestBody(mediaType))
                    .addHeader("Content-Type", "application/json")
                    .apply {
                        if (apiKey.isNotEmpty()) {
                            addHeader("Authorization", "Bearer $apiKey")
                        }
                    }
                    .build()

                okHttpClient.newCall(request).execute().use { response ->
                    val responseBody = response.body?.string() ?: ""
                    Log.d("AivoDebug", "GeminiAiAgent: HTTP Code: ${response.code}, Response Body: $responseBody")
                    if (!response.isSuccessful) {
                        return@withContext Result.Error(
                            IOException("HTTP Error ${response.code}: $responseBody")
                        )
                    }

                    val responseObj = try {
                        json.parseToJsonElement(responseBody).jsonObject
                    } catch (e: Exception) {
                        return@withContext Result.Error(
                            e,
                            "Failed to parse response JSON. Response: $responseBody"
                        )
                    }

                    val messageObj = responseObj["message"]?.jsonObject
                        ?: responseObj["choices"]?.jsonArray?.firstOrNull()?.jsonObject?.get("message")?.jsonObject
                        ?: return@withContext Result.Error(
                            IllegalStateException("Failed to parse message from response: $responseBody")
                        )

                    val toolCalls = messageObj["tool_calls"]?.jsonArray

                    if (toolCalls != null && toolCalls.isNotEmpty()) {
                        Log.d("AivoDebug", "GeminiAiAgent: Model returned ${toolCalls.size} tool calls")
                        // Append assistant message with tool calls to history
                        chatHistory.add(messageObj)

                        for (toolCall in toolCalls) {
                            val callObj = toolCall.jsonObject
                            val functionObj = callObj["function"]?.jsonObject ?: continue
                            val functionName = functionObj["name"]?.jsonPrimitive?.content ?: continue

                            val argsElement = functionObj["arguments"] ?: buildJsonObject {}
                            val rawArgsMap = when (argsElement) {
                                is JsonObject -> argsElement
                                is JsonPrimitive -> {
                                    try {
                                        json.parseToJsonElement(argsElement.content).jsonObject
                                    } catch (e: Exception) {
                                        buildJsonObject {}
                                    }
                                }
                                else -> buildJsonObject {}
                            }

                            Log.d("AivoDebug", "GeminiAiAgent: Parsing tool call: $functionName with args: $rawArgsMap")

                            // Match function name to find its package
                            val metadata = tools.firstOrNull { it.id.substringAfterLast('#') == functionName }
                            if (metadata == null) {
                                Log.w("AivoDebug", "GeminiAiAgent: Tool function not found in metadata list: $functionName")
                                continue
                            }

                            // Extract packageName from metadata.id (e.g. before the class name)
                            // ID: com.shopify.carto.feature.ai_integration.appfunctions.WishlistFunctions#addToWishlist
                            val packagePrefix = metadata.id.substringBeforeLast('#').substringBefore(".feature")
                            // Fallback if packagePrefix doesn't contain '.'
                            val targetPackage = if (packagePrefix.contains(".")) packagePrefix else "com.shopify.carto"

                            val progressMsg = "Executing: $functionName..."
                            onToolCallProgress(progressMsg)

                            Log.d("AivoDebug", "GeminiAiAgent: Calling executeTool package=$targetPackage, function=$functionName")
                            // Execute function
                            val resultText = executeTool(targetPackage, functionName, rawArgsMap)
                            Log.d("AivoDebug", "GeminiAiAgent: Tool execution result: $resultText")

                            val toolCallId = callObj["id"]?.jsonPrimitive?.content ?: ""
                            chatHistory.add(
                                buildJsonObject {
                                    put("role", "tool")
                                    put("name", functionName)
                                    put("tool_call_id", toolCallId)
                                    put("content", resultText)
                                }
                            )
                        }
                    } else {
                        val replyContent = messageObj["content"]?.jsonPrimitive?.content ?: ""
                        finalAssistantResponse = ChatMessage(
                            role = MessageRole.ASSISTANT,
                            content = replyContent
                        )
                        chatHistory.add(messageObj)
                        finished = true
                    }
                }
            }

            finalAssistantResponse?.let {
                Result.Success(it)
            } ?: Result.Error(IllegalStateException("No response from AI agent"))
        } catch (e: Exception) {
            Result.Error(e)
        }
    }

    private fun mapMetadataToTool(metadata: AppFunctionMetadata): JsonObject {
        val simpleName = metadata.id.substringAfterLast('#')
        return buildJsonObject {
            put("type", "function")
            putJsonObject("function") {
                put("name", simpleName)
                put("description", metadata.description ?: "Execute function $simpleName")
                putJsonObject("parameters") {
                    put("type", "object")
                    putJsonObject("properties") {
                        metadata.parameters.forEach { param ->
                            putJsonObject(param.name) {
                                val typeStr = when (param.dataType) {
                                    is AppFunctionIntTypeMetadata, is AppFunctionLongTypeMetadata -> "integer"
                                    is AppFunctionFloatTypeMetadata, is AppFunctionDoubleTypeMetadata -> "number"
                                    is AppFunctionBooleanTypeMetadata -> "boolean"
                                    else -> "string"
                                }
                                put("type", typeStr)
                                put("description", param.description ?: "")
                            }
                        }
                    }
                    putJsonArray("required") {
                        metadata.parameters.forEach { param ->
                            if (param.isRequired) {
                                add(param.name)
                            }
                        }
                    }
                }
            }
        }
    }
}

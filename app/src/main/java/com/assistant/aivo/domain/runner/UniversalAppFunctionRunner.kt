package com.assistant.aivo.domain.runner

import android.content.Context
import android.util.Log
import androidx.appfunctions.AppFunctionData
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.ExecuteAppFunctionRequest
import androidx.appfunctions.ExecuteAppFunctionResponse
import androidx.appfunctions.metadata.AppFunctionBooleanTypeMetadata
import androidx.appfunctions.metadata.AppFunctionDoubleTypeMetadata
import androidx.appfunctions.metadata.AppFunctionFloatTypeMetadata
import androidx.appfunctions.metadata.AppFunctionIntTypeMetadata
import androidx.appfunctions.metadata.AppFunctionLongTypeMetadata
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import androidx.appfunctions.metadata.CompileTimeAppFunctionMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.double
import kotlinx.serialization.json.float
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

import android.content.ComponentName
import android.content.Intent
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlin.coroutines.resume

import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.Messenger

class UniversalAppFunctionRunner(
    private val context: Context
) {

    private suspend fun executeViaCartoProxy(
        functionId: String,
        argsJsonString: String
    ): String = suspendCancellableCoroutine { continuation ->
        val handler = object : Handler(Looper.getMainLooper()) {
            override fun handleMessage(msg: Message) {
                val result = msg.data.getString("result") ?: "Error: No response from Carto proxy"
                if (continuation.isActive) {
                    continuation.resume(result)
                }
            }
        }
        val messenger = Messenger(handler)

        val intent = Intent().apply {
            component = ComponentName(
                "com.shopify.carto",
                "com.shopify.carto.feature.ai_integration.appfunctions.CartoAppFunctionProxyReceiver"
            )
            putExtra("function_id", functionId)
            putExtra("parameters_json", argsJsonString)
            putExtra("callback", messenger)
        }

        try {
            context.sendBroadcast(intent)
        } catch (e: Exception) {
            if (continuation.isActive) {
                continuation.resume("Error sending broadcast to Carto proxy: ${e.message}")
            }
        }
    }

    suspend fun execute(
        packageName: String,
        simpleName: String,
        args: Map<String, JsonElement>
    ): String {
        if (packageName == "com.shopify.carto") {
            Log.d("AivoDebug", "UniversalAppFunctionRunner: Routing Carto execution through proxy service")
            val jsonObject = buildJsonObject {
                for ((key, value) in args) {
                    put(key, value)
                }
            }
            return executeViaCartoProxy(
                functionId = "com.shopify.carto.feature.ai_integration.appfunctions.CartoAppFunctionProxyService#$simpleName",
                argsJsonString = jsonObject.toString()
            )
        }

        val manager = try {
            AppFunctionManager.getInstance(context)
        } catch (e: Exception) {
            Log.e("AivoDebug", "UniversalAppFunctionRunner: Failed to get AppFunctionManager", e)
            return "Error: AppFunctionManager not available."
        } ?: return "Error: AppFunctionManager is null."

        try {
            Log.d("AivoDebug", "UniversalAppFunctionRunner: Searching for $simpleName in package $packageName")

            val metadataList = discoverFunctions(packageName, manager)
            val metadata = metadataList.firstOrNull { it.id.substringAfterLast('#') == simpleName }

            if (metadata == null) {
                Log.e("AivoDebug", "UniversalAppFunctionRunner: Function $simpleName not found in package $packageName")
                return "Error: Function $simpleName not found in package $packageName. Available functions are: ${metadataList.map { it.id.substringAfterLast('#') }}"
            }

            Log.d("AivoDebug", "UniversalAppFunctionRunner: Executing function: ${metadata.id} in package $packageName")
            val params = buildParameters(metadata, args)

            val request = ExecuteAppFunctionRequest(
                targetPackageName = packageName,
                functionIdentifier = metadata.id,
                functionParameters = params
            )

            val response = manager.executeAppFunction(request)
            Log.d("AivoDebug", "UniversalAppFunctionRunner: Execution response: ${response.javaClass.simpleName}")

            return when (response) {
                is ExecuteAppFunctionResponse.Success -> {
                    val resultString = readReturnValue(metadata, response.returnValue)
                    Log.d("AivoDebug", "UniversalAppFunctionRunner: Execution Success. Return value: $resultString")
                    resultString
                }
                is ExecuteAppFunctionResponse.Error -> {
                    val errMsg = "Error [${response.error.javaClass.simpleName}]: ${response.error.message}"
                    Log.e("AivoDebug", "UniversalAppFunctionRunner: Execution Failure. $errMsg")
                    "Error executing function: $errMsg"
                }
            }
        } catch (e: Exception) {
            Log.e("AivoDebug", "UniversalAppFunctionRunner: Exception during execution", e)
            return "Error: ${e.message}"
        }
    }

    private suspend fun discoverFunctions(packageName: String, manager: AppFunctionManager): List<AppFunctionMetadata> {
        var list = try {
            val spec = AppFunctionSearchSpec(packageNames = setOf(packageName))
            val staticMetadataList = withTimeoutOrNull(1000) {
                manager.observeAppFunctions(spec)
                    .filter { it.isNotEmpty() }
                    .first()
            } ?: emptyList()
            staticMetadataList.flatMap { it.appFunctions }
        } catch (e: Exception) {
            Log.e("AivoDebug", "UniversalAppFunctionRunner: Error querying functions for package: $packageName", e)
            emptyList()
        }

        if (list.isEmpty() && (packageName == "com.shopify.carto" || packageName == "com.plcoding.appfunctionsdemo")) {
            Log.d("AivoDebug", "UniversalAppFunctionRunner: observeAppFunctions returned 0. Using dynamic APK fallback for $packageName...")
            list = loadApkMetadataFallback(packageName)
        }
        return list
    }

    private fun loadApkMetadataFallback(pkg: String): List<AppFunctionMetadata> {
        val metadataList = mutableListOf<AppFunctionMetadata>()
        try {
            val packageInfo = context.packageManager.getPackageInfo(pkg, 0)
            val apkPath = packageInfo.applicationInfo?.sourceDir ?: return emptyList()
            val classLoader = dalvik.system.PathClassLoader(apkPath, context.classLoader)
            
            val inventoryClassNames = when (pkg) {
                "com.shopify.carto" -> listOf(
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$CartFunctions_AppFunctionInventory",
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$CompareFunctions_AppFunctionInventory",
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$OutfitFunctions_AppFunctionInventory",
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$SearchFunctions_AppFunctionInventory",
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$WishlistFunctions_AppFunctionInventory",
                    "com.shopify.carto.feature.ai_integration.appfunctions.\$CheckoutFunctions_AppFunctionInventory"
                )
                "com.plcoding.appfunctionsdemo" -> listOf(
                    "com.plcoding.appfunctionsdemo.appfunctions.\$CounterFunctions_AppFunctionInventory"
                )
                else -> emptyList()
            }
            
            for (className in inventoryClassNames) {
                try {
                    val clazz = classLoader.loadClass(className)
                    val instance = clazz.getDeclaredConstructor().newInstance()
                    val getMapMethod = clazz.getMethod("getFunctionIdToMetadataMap")
                    val map = getMapMethod.invoke(instance) as Map<*, *>
                    for (value in map.values) {
                        if (value is CompileTimeAppFunctionMetadata) {
                            val components = value.components
                            val packageMetadata = AppFunctionPackageMetadata(pkg, components)
                            val name = AppFunctionName(pkg, value.id)
                            val appFunctionMetadata = AppFunctionMetadata(
                                name = name,
                                schema = value.schema,
                                parameters = value.parameters,
                                response = value.response,
                                packageMetadata = packageMetadata,
                                isEnabled = value.isEnabledByDefault
                            )
                            metadataList.add(appFunctionMetadata)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("AivoDebug", "Failed to load inventory class $className", e)
                }
            }
        } catch (e: Exception) {
            Log.e("AivoDebug", "Failed to load package info for fallback $pkg", e)
        }
        return metadataList
    }

    private fun buildParameters(metadata: AppFunctionMetadata, args: Map<String, JsonElement>): AppFunctionData {
        val builder = AppFunctionData.Builder(metadata.parameters, metadata.components)
        metadata.parameters.forEach { parameter ->
            val element = args[parameter.name] ?: return@forEach
            val value = element.jsonPrimitive
            Log.d("AivoDebug", "  - Parameter Name: ${parameter.name}, raw value: $value")
            when (parameter.dataType) {
                is AppFunctionIntTypeMetadata -> builder.setInt(parameter.name, value.int)
                is AppFunctionLongTypeMetadata -> builder.setLong(parameter.name, value.long)
                is AppFunctionFloatTypeMetadata -> builder.setFloat(parameter.name, value.float)
                is AppFunctionDoubleTypeMetadata -> builder.setDouble(parameter.name, value.double)
                is AppFunctionBooleanTypeMetadata -> builder.setBoolean(parameter.name, value.boolean)
                else -> builder.setString(parameter.name, value.content)
            }
        }
        return builder.build()
    }

    private fun readReturnValue(metadata: AppFunctionMetadata, data: AppFunctionData): String {
        val key = ExecuteAppFunctionResponse.Success.PROPERTY_RETURN_VALUE
        return when (metadata.response.valueType) {
            is AppFunctionIntTypeMetadata -> data.getInt(key).toString()
            is AppFunctionLongTypeMetadata -> data.getLong(key).toString()
            is AppFunctionFloatTypeMetadata -> data.getFloat(key).toString()
            is AppFunctionDoubleTypeMetadata -> data.getDouble(key).toString()
            is AppFunctionBooleanTypeMetadata -> data.getBoolean(key).toString()
            else -> data.getString(key) ?: "Success"
        }
    }
}


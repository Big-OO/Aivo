package com.assistant.aivo.domain.discovery

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.util.Log
import androidx.appfunctions.AppFunctionManager
import androidx.appfunctions.AppFunctionSearchSpec
import androidx.appfunctions.metadata.AppFunctionMetadata
import androidx.appfunctions.metadata.AppFunctionName
import androidx.appfunctions.metadata.AppFunctionPackageMetadata
import androidx.appfunctions.metadata.CompileTimeAppFunctionMetadata
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.withTimeoutOrNull

data class DiscoveredApp(
    val packageName: String,
    val appName: String,
    val functionCount: Int,
    val functions: List<AppFunctionMetadata>
)

class AppDiscoveryManager(private val context: Context) {

    suspend fun discoverApps(): List<DiscoveredApp> {
        Log.d("AivoDebug", "AppDiscoveryManager: Starting discovery...")
        val packageManager = context.packageManager
        val intent = Intent("android.app.appfunctions.AppFunctionService")
        val services = packageManager.queryIntentServices(
            intent,
            PackageManager.GET_META_DATA
        )

        Log.d("AivoDebug", "AppDiscoveryManager: Found ${services.size} services matching intent")
        val uniquePackages = services.map { it.serviceInfo.packageName }.distinct()
        Log.d("AivoDebug", "AppDiscoveryManager: Unique packages: $uniquePackages")
        val discoveredApps = mutableListOf<DiscoveredApp>()

        val manager = try {
            AppFunctionManager.getInstance(context)
        } catch (e: Exception) {
            Log.e("AivoDebug", "AppDiscoveryManager: Failed to get AppFunctionManager instance", e)
            null
        } ?: return emptyList()



        for (pkg in uniquePackages) {
            try {
                Log.d("AivoDebug", "AppDiscoveryManager: Querying functions for package: $pkg")
                val spec = AppFunctionSearchSpec(packageNames = setOf(pkg))
                
                var staticMetadataList = try {
                    withTimeoutOrNull(1000) {
                        manager.observeAppFunctions(spec)
                            .filter { it.isNotEmpty() }
                            .first()
                    } ?: emptyList()
                } catch (e: Exception) {
                    Log.e("AivoDebug", "AppDiscoveryManager: Error collecting functions for $pkg", e)
                    emptyList()
                }

                var appFunctions = staticMetadataList.flatMap { it.appFunctions }

                if (appFunctions.isEmpty() && (pkg == "com.shopify.carto" || pkg == "com.plcoding.appfunctionsdemo")) {
                    Log.d("AivoDebug", "AppDiscoveryManager: observeAppFunctions returned 0. Using dynamic APK fallback for $pkg...")
                    appFunctions = loadApkMetadataFallback(pkg)
                }

                Log.d("AivoDebug", "AppDiscoveryManager: Package $pkg has ${appFunctions.size} functions")
                appFunctions.forEach { fn ->
                    Log.d("AivoDebug", "  - Function ID: ${fn.id}, simpleName: ${fn.id.substringAfterLast('#')}")
                }

                if (appFunctions.isNotEmpty()) {
                    val appInfo = packageManager.getApplicationInfo(pkg, 0)
                    val appName = packageManager.getApplicationLabel(appInfo).toString()
                    discoveredApps.add(
                        DiscoveredApp(
                            packageName = pkg,
                            appName = appName,
                            functionCount = appFunctions.size,
                            functions = appFunctions
                        )
                    )
                }
            } catch (e: Exception) {
                Log.e("AivoDebug", "AppDiscoveryManager: Failed to query functions for package $pkg", e)
            }
        }
        return discoveredApps
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
}


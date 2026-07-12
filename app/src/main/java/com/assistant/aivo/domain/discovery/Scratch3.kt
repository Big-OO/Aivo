package com.assistant.aivo.domain.discovery

import android.util.Log
import androidx.appfunctions.metadata.CompileTimeAppFunctionMetadata

fun testMetadata() {
    val clazz = CompileTimeAppFunctionMetadata::class.java
    Log.d("AivoReflection", "Class: ${clazz.name}")
    Log.d("AivoReflection", "Superclass: ${clazz.superclass?.name}")
    for (m in clazz.methods) {
        Log.d("AivoReflection", "Method: ${m.name} returning ${m.returnType.name}")
    }
    for (f in clazz.declaredFields) {
        Log.d("AivoReflection", "Field: ${f.name} of type ${f.type.name}")
    }
}

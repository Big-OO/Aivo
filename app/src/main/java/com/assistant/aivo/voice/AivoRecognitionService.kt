package com.assistant.aivo.voice

import android.content.Intent
import android.speech.RecognitionService

class AivoRecognitionService : RecognitionService() {
    override fun onStartListening(recognizerIntent: Intent?, listener: Callback?) {}
    override fun onCancel(listener: Callback?) {}
    override fun onStopListening(listener: Callback?) {}
}

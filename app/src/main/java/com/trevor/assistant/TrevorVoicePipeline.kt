package com.trevor.assistant

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Lifecycle-owned speech pipeline for the foreground assistant screen.
 * Recognition is started only after the user taps the microphone and grants RECORD_AUDIO.
 */
class TrevorVoicePipeline(context: Context) {
    private val appContext = context.applicationContext
    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var ttsReady = false
    private var onSpeechState: ((String) -> Unit)? = null

    init {
        tts = TextToSpeech(appContext) { status ->
            ttsReady = status == TextToSpeech.SUCCESS
            if (ttsReady) {
                tts?.language = Locale.getDefault()
                tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) {
                        onSpeechState?.invoke("SPEAKING")
                    }

                    override fun onDone(utteranceId: String?) {
                        onSpeechState?.invoke("READY")
                    }

                    @Deprecated("Deprecated by Android")
                    override fun onError(utteranceId: String?) {
                        onSpeechState?.invoke("VOICE OUTPUT ERROR")
                    }

                    override fun onError(utteranceId: String?, errorCode: Int) {
                        onSpeechState?.invoke("VOICE OUTPUT ERROR")
                    }
                })
            }
        }
    }

    fun startListening(
        onState: (String) -> Unit,
        onResult: (String) -> Unit
    ) {
        onSpeechState = onState
        if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
            onState("SPEECH SERVICE UNAVAILABLE")
            return
        }

        runCatching { recognizer?.destroy() }
        recognizer = SpeechRecognizer.createSpeechRecognizer(appContext).also { speech ->
            speech.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = onState("LISTENING")
                override fun onBeginningOfSpeech() = onState("LISTENING")
                override fun onRmsChanged(rmsdB: Float) = Unit
                override fun onBufferReceived(buffer: ByteArray?) = Unit
                override fun onEndOfSpeech() = onState("TRANSCRIBING")
                override fun onError(error: Int) {
                    onState(
                        when (error) {
                            SpeechRecognizer.ERROR_NO_MATCH -> "NO SPEECH RECOGNISED"
                            SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "NO SPEECH DETECTED"
                            SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "MICROPHONE PERMISSION REQUIRED"
                            SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "VOICE SERVICE BUSY"
                            else -> "VOICE INPUT ERROR ($error)"
                        }
                    )
                }

                override fun onResults(results: Bundle?) {
                    val transcript = results
                        ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                        ?.firstOrNull()
                        ?.trim()
                    if (!transcript.isNullOrBlank()) {
                        onState("TRANSCRIPT READY")
                        onResult(transcript)
                    } else {
                        onState("NO SPEECH RECOGNISED")
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) = Unit
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
        }

        val request = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
        }
        runCatching { recognizer?.startListening(request) }
            .onFailure { onState("COULD NOT START VOICE INPUT") }
        onState("LISTENING")
    }

    fun stopListening() {
        runCatching { recognizer?.stopListening() }
    }

    fun speak(text: String, onState: (String) -> Unit) {
        val clean = text.trim()
        if (clean.isEmpty()) return
        onSpeechState = onState
        val engine = tts
        if (engine == null || !ttsReady) {
            onState("VOICE OUTPUT UNAVAILABLE")
            return
        }
        val utteranceId = "trevor-" + System.nanoTime()
        val result = engine.speak(clean, TextToSpeech.QUEUE_FLUSH, null, utteranceId)
        if (result == TextToSpeech.ERROR) onState("VOICE OUTPUT ERROR")
    }

    fun release() {
        runCatching { recognizer?.cancel() }
        runCatching { recognizer?.destroy() }
        recognizer = null
        runCatching { tts?.stop() }
        runCatching { tts?.shutdown() }
        tts = null
        onSpeechState = null
    }
}

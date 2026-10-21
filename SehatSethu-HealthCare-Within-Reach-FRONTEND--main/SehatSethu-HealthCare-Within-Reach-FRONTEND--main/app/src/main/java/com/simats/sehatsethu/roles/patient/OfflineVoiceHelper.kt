package com.simats.sehatsethu.roles.patient

import android.content.Context
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import org.vosk.android.StorageService

class OfflineVoiceHelper(
    private val ctx: Context,
    private val modelAssetFolder: String,
    private val onFinalText: (String) -> Unit,
    private val onErrorText: (String) -> Unit
) : RecognitionListener {

    private var model: Model? = null
    private var service: SpeechService? = null
    private var recognizer: Recognizer? = null

    fun start() {
        try {
            StorageService.unpack(
                ctx,
                modelAssetFolder,
                "voskModel",
                { m ->
                    model = m
                    recognizer = Recognizer(m, 16000.0f)
                    service = SpeechService(recognizer, 16000.0f)
                    service?.startListening(this)
                },
                { e -> onErrorText(e?.message ?: "Model load failed") }
            )
        } catch (t: Throwable) {
            onErrorText(t.message ?: "Voice start error")
        }
    }

    fun stop() {
        runCatching {
            service?.stop()
            service?.shutdown()
        }
        service = null
        recognizer = null
        model = null
    }

    // REQUIRED by RecognitionListener
    override fun onPartialResult(hypothesis: String?) {
        // optional: you can show live text somewhere if you want
    }

    override fun onResult(hypothesis: String?) {
        // intermediate results (we can ignore)
    }

    override fun onFinalResult(hypothesis: String?) {
        val raw = hypothesis.orEmpty()
        val spoken = Regex("\"text\"\\s*:\\s*\"([^\"]*)\"")
            .find(raw)?.groupValues?.getOrNull(1).orEmpty()
            .trim()

        if (spoken.isNotBlank()) onFinalText(spoken)
        stop()
    }

    override fun onError(exception: Exception?) {
        onErrorText(exception?.message ?: "Voice error")
        stop()
    }

    override fun onTimeout() {
        stop()
    }
}

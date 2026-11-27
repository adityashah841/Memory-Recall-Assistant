package com.synapseriders.memory_recall_app.capture

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

/**
 * Continuous on-device speech-to-text from the live microphone via Android's [SpeechRecognizer]
 * (IMPLEMENTATION_PLAN.md §6 fallback when Whisper/QNN isn't available — e.g. on a Pixel).
 *
 * Prefers the dedicated **on-device** recognizer (no audio leaves the phone); if the device lacks an
 * on-device model for the language it asks Android to download one and meanwhile uses the default
 * recognizer with `EXTRA_PREFER_OFFLINE`. Each recognizer session ends after a pause in speech, so the
 * listener restarts itself to keep listening. Final phrases accumulate until the capture loop
 * [drain]s them into the next diary entry.
 *
 * `SpeechRecognizer` must be used from the main thread; [start]/[stop] post there. [drain] is
 * thread-safe. Never throws.
 */
class LiveSpeechTranscriber(context: Context) {

    private val appContext = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val pending = StringBuilder()

    private var recognizer: SpeechRecognizer? = null
    private var useOnDevice = true
    @Volatile private var running = false

    /** Start continuous listening. Safe to call from any thread. */
    fun start() {
        main.post {
            if (running) return@post
            if (!SpeechRecognizer.isRecognitionAvailable(appContext)) {
                Log.w(TAG, "no speech recognizer on this device — transcripts disabled")
                return@post
            }
            useOnDevice = SpeechRecognizer.isOnDeviceRecognitionAvailable(appContext)
            running = true
            Log.i(TAG, "listening (on-device=$useOnDevice)")
            listen()
        }
    }

    fun stop() {
        main.post {
            running = false
            main.removeCallbacksAndMessages(null)
            destroyRecognizer()
            Log.i(TAG, "stopped")
        }
    }

    /** Text recognized since the last call ("" if none). Thread-safe. */
    fun drain(): String = synchronized(pending) {
        pending.toString().trim().also { pending.setLength(0) }
    }

    private fun listen() {
        if (!running) return
        val sr = recognizer ?: createRecognizer()?.also { recognizer = it } ?: run {
            running = false
            return
        }
        try {
            sr.startListening(recognizerIntent())
        } catch (t: Throwable) {
            Log.w(TAG, "startListening failed", t)
            restart(RETRY_DELAY_MS, recreate = true)
        }
    }

    private fun createRecognizer(): SpeechRecognizer? = try {
        val sr = if (useOnDevice) {
            SpeechRecognizer.createOnDeviceSpeechRecognizer(appContext)
        } else {
            SpeechRecognizer.createSpeechRecognizer(appContext)
        }
        sr.setRecognitionListener(listener)
        sr
    } catch (t: Throwable) {
        Log.w(TAG, "could not create recognizer (on-device=$useOnDevice)", t)
        null
    }

    private fun recognizerIntent() = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
    }

    private fun restart(delayMs: Long, recreate: Boolean = false) {
        if (!running) return
        if (recreate) destroyRecognizer()
        main.postDelayed({ listen() }, delayMs)
    }

    private fun destroyRecognizer() {
        recognizer?.let {
            try { it.cancel() } catch (_: Throwable) {}
            try { it.destroy() } catch (_: Throwable) {}
        }
        recognizer = null
    }

    private val listener = object : RecognitionListener {
        override fun onResults(results: Bundle?) {
            val text = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
            if (!text.isNullOrBlank()) {
                synchronized(pending) {
                    if (pending.isNotEmpty()) pending.append(". ")
                    pending.append(text.trim())
                }
                Log.d(TAG, "heard: $text")
            }
            restart(RESTART_DELAY_MS)
        }

        override fun onError(error: Int) {
            when (error) {
                // Silence / nothing understood: normal — just listen again.
                SpeechRecognizer.ERROR_NO_MATCH,
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> restart(RESTART_DELAY_MS)

                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> {
                    Log.w(TAG, "RECORD_AUDIO not granted — transcripts disabled")
                    running = false
                }

                // No on-device model for this language yet: ask Android to fetch it, and use the
                // default recognizer (still offline-preferred) in the meantime.
                SpeechRecognizer.ERROR_LANGUAGE_NOT_SUPPORTED,
                SpeechRecognizer.ERROR_LANGUAGE_UNAVAILABLE -> {
                    if (useOnDevice) {
                        Log.w(TAG, "on-device language model missing (error=$error) — requesting download")
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                            try { recognizer?.triggerModelDownload(recognizerIntent()) } catch (_: Throwable) {}
                        }
                        useOnDevice = false
                        restart(RETRY_DELAY_MS, recreate = true)
                    } else {
                        Log.w(TAG, "speech language unavailable (error=$error) — transcripts disabled")
                        running = false
                    }
                }

                else -> {
                    Log.d(TAG, "recognizer error=$error — restarting")
                    restart(RETRY_DELAY_MS, recreate = true)
                }
            }
        }

        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private companion object {
        const val TAG = "LiveSpeech"
        const val RESTART_DELAY_MS = 150L
        const val RETRY_DELAY_MS = 1_000L
    }
}

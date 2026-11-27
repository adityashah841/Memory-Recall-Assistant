package com.synapseriders.memory_recall_app.recall

import android.content.Context
import android.speech.tts.TextToSpeech
import android.util.Log
import java.util.Locale

/**
 * Speaks recall cues and answers aloud with Android's built-in [TextToSpeech] (IMPLEMENTATION_PLAN.md
 * §22 CP9 "cue spoken"). On a phone today — and through the glasses' speakers in the intended Meta
 * glasses setup — the user hears who's in front of them without looking at a screen.
 *
 * Initializes lazily on first use; text requested before the engine is ready is spoken once it is.
 * Each new utterance replaces the previous one. Never throws.
 */
class RecallSpeaker(context: Context) {

    private val appContext = context.applicationContext
    @Volatile private var tts: TextToSpeech? = null
    @Volatile private var ready = false
    @Volatile private var queued: String? = null

    /** Start the TTS engine early (call on the main thread) so the first cue isn't delayed. */
    fun warmUp() = ensureEngine()

    fun speak(text: String?) {
        val say = text?.trim().orEmpty()
        if (say.isEmpty()) return
        if (ready) {
            utter(say)
            return
        }
        queued = say
        ensureEngine()
    }

    fun stop() {
        try { tts?.stop() } catch (_: Throwable) {}
    }

    fun shutdown() {
        try { tts?.shutdown() } catch (_: Throwable) {}
        tts = null
        ready = false
    }

    @Synchronized
    private fun ensureEngine() {
        if (tts != null) return
        tts = try {
            TextToSpeech(appContext) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.getDefault()
                    tts?.setSpeechRate(SPEECH_RATE)
                    ready = true
                    queued?.let { utter(it) }
                    queued = null
                } else {
                    Log.w(TAG, "TextToSpeech init failed (status=$status)")
                }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "TextToSpeech unavailable", t)
            null
        }
    }

    private fun utter(text: String) {
        try {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "recall-${System.nanoTime()}")
        } catch (t: Throwable) {
            Log.w(TAG, "speak failed", t)
        }
    }

    private companion object {
        const val TAG = "RecallSpeaker"
        // Slightly slower than default: the audience for this app is older adults.
        const val SPEECH_RATE = 0.9f
    }
}

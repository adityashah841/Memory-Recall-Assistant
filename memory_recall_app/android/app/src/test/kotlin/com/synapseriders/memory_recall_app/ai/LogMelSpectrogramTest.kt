package com.synapseriders.memory_recall_app.ai

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.sin

/**
 * CP5 (§6): log-mel front-end shape + normalization. (Numeric parity with Python whisper must still be
 * validated on-device — §24.3.)
 */
class LogMelSpectrogramTest {

    private val mel = LogMelSpectrogram()

    @Test
    fun outputHasWhisperShape() {
        val out = mel.compute(ShortArray(16000), 16000) // 1 s of silence
        assertEquals(LogMelSpectrogram.N_MELS * LogMelSpectrogram.N_FRAMES, out.size)
        assertEquals(80 * 3000, out.size)
    }

    @Test
    fun allValuesFinite() {
        val tone = ShortArray(16000) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val out = mel.compute(tone, 16000)
        assertTrue(out.all { it.isFinite() })
    }

    @Test
    fun toneProducesNonConstantSpectrum() {
        val tone = ShortArray(16000) { (8000 * sin(2 * PI * 440 * it / 16000)).toInt().toShort() }
        val out = mel.compute(tone, 16000)
        assertTrue("a real tone should not be a flat spectrum", out.max() - out.min() > 0.1f)
    }
}

package com.synapseriders.memory_recall_app.ai

import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/**
 * Whisper log-mel front-end (IMPLEMENTATION_PLAN.md §6): PCM16 → `float32[1, 80, 3000]`.
 *
 * Reproduces `whisper.audio.log_mel_spectrogram`: pad/trim to 30 s, reflect-padded STFT
 * (n_fft=400, hop=160, Hann window), Slaney mel filterbank (80 bins, sr=16 kHz, fmax=8 kHz, htk=False,
 * slaney-normalized — matching `librosa.filters.mel`), `log10`, then `max(x, max-8)` and `(x+4)/4`.
 * Output is mel-major row order: `out[mel * 3000 + frame]`.
 *
 * Filterbank + DFT tables are precomputed once. Pure Kotlin (no Android) so it is unit-tested for
 * shape/normalization; ⚠️ **numeric parity with the Python reference must be validated** before trusting
 * transcripts (no reference available in this environment) — tracked in §24.3.
 */
class LogMelSpectrogram {

    private val hann = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * Math.PI * it / N_FFT)).toFloat() }
    // DFT tables: cosT/sinT[k * N_FFT + n] for k in 0..N_FREQS-1, n in 0..N_FFT-1.
    private val cosT = FloatArray(N_FREQS * N_FFT)
    private val sinT = FloatArray(N_FREQS * N_FFT)
    private val melW = FloatArray(N_MELS * N_FREQS) // [80 x 201] filterbank

    init {
        for (k in 0 until N_FREQS) {
            for (n in 0 until N_FFT) {
                val a = 2.0 * Math.PI * k * n / N_FFT
                cosT[k * N_FFT + n] = cos(a).toFloat()
                sinT[k * N_FFT + n] = sin(a).toFloat()
            }
        }
        buildMelFilters()
    }

    /** Compute the `float32[80*3000]` log-mel features for a PCM16 [pcm] sampled at [sampleRate]. */
    fun compute(pcm: ShortArray, sampleRate: Int): FloatArray {
        val audio = toMonoFloat16k(pcm, sampleRate)
        val samples = if (audio.size == N_SAMPLES) audio else audio.copyOf(N_SAMPLES) // pad/trim 30s
        val padded = reflectPad(samples, N_FFT / 2)

        val mel = FloatArray(N_MELS * N_FRAMES)
        val frame = FloatArray(N_FFT)
        val power = FloatArray(N_FREQS)
        for (t in 0 until N_FRAMES) {
            val start = t * HOP
            for (n in 0 until N_FFT) frame[n] = padded[start + n] * hann[n]
            for (k in 0 until N_FREQS) {
                var re = 0f
                var im = 0f
                val base = k * N_FFT
                for (n in 0 until N_FFT) {
                    re += frame[n] * cosT[base + n]
                    im += frame[n] * sinT[base + n]
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                var s = 0f
                val wb = m * N_FREQS
                for (k in 0 until N_FREQS) s += melW[wb + k] * power[k]
                mel[m * N_FRAMES + t] = s
            }
        }

        // log10 + Whisper normalization.
        var maxLog = Float.NEGATIVE_INFINITY
        for (i in mel.indices) {
            val v = if (mel[i] < 1e-10f) 1e-10f else mel[i]
            val l = log10(v)
            mel[i] = l
            if (l > maxLog) maxLog = l
        }
        val floor = maxLog - 8f
        for (i in mel.indices) {
            val l = if (mel[i] < floor) floor else mel[i]
            mel[i] = (l + 4f) / 4f
        }
        return mel
    }

    private fun buildMelFilters() {
        val fftFreqs = FloatArray(N_FREQS) { it.toFloat() * SAMPLE_RATE / N_FFT }
        val melMin = hzToMel(0.0)
        val melMax = hzToMel((SAMPLE_RATE / 2).toDouble())
        val melPts = DoubleArray(N_MELS + 2) { melMin + (melMax - melMin) * it / (N_MELS + 1) }
        val hz = DoubleArray(N_MELS + 2) { melToHz(melPts[it]) }
        for (m in 0 until N_MELS) {
            val lo = hz[m]
            val ctr = hz[m + 1]
            val hi = hz[m + 2]
            val enorm = (2.0 / (hi - lo)).toFloat()
            for (k in 0 until N_FREQS) {
                val f = fftFreqs[k]
                val lower = ((f - lo) / (ctr - lo)).toFloat()
                val upper = ((hi - f) / (hi - ctr)).toFloat()
                val w = minOf(lower, upper).coerceAtLeast(0f)
                melW[m * N_FREQS + k] = w * enorm
            }
        }
    }

    /** Slaney (htk=False) hz→mel, matching librosa. */
    private fun hzToMel(f: Double): Double {
        val fSp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logstep = ln(6.4) / 27.0
        return if (f >= minLogHz) minLogMel + ln(f / minLogHz) / logstep else f / fSp
    }

    private fun melToHz(m: Double): Double {
        val fSp = 200.0 / 3.0
        val minLogHz = 1000.0
        val minLogMel = minLogHz / fSp
        val logstep = ln(6.4) / 27.0
        return if (m >= minLogMel) minLogHz * exp(logstep * (m - minLogMel)) else fSp * m
    }

    private fun reflectPad(x: FloatArray, p: Int): FloatArray {
        val n = x.size
        val out = FloatArray(n + 2 * p)
        for (i in 0 until p) out[i] = x[p - i]            // mirror, excluding x[0]
        System.arraycopy(x, 0, out, p, n)
        for (i in 0 until p) out[p + n + i] = x[n - 2 - i] // mirror, excluding x[n-1]
        return out
    }

    /** PCM16 → mono float [-1,1] @ 16 kHz (linear resample when needed). */
    private fun toMonoFloat16k(pcm: ShortArray, sampleRate: Int): FloatArray {
        if (sampleRate == SAMPLE_RATE) return FloatArray(pcm.size) { pcm[it] / 32768f }
        val ratio = SAMPLE_RATE.toDouble() / sampleRate
        val outLen = (pcm.size * ratio).toInt().coerceAtLeast(1)
        return FloatArray(outLen) {
            val src = it / ratio
            val i0 = src.toInt()
            val i1 = (i0 + 1).coerceAtMost(pcm.size - 1)
            val frac = (src - i0).toFloat()
            ((pcm[i0] * (1 - frac)) + pcm[i1] * frac) / 32768f
        }
    }

    companion object {
        const val SAMPLE_RATE = 16000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80
        const val N_FRAMES = 3000
        const val N_SAMPLES = 480000 // 30 s
        const val N_FREQS = N_FFT / 2 + 1 // 201
    }
}

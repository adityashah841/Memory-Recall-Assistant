package com.synapseriders.memory_recall_app.capture

/**
 * Rolling ~30 s PCM16 audio window for ASR (IMPLEMENTATION_PLAN.md §6, §8.1, §20.3).
 *
 * The capture loop appends mic frames continuously; the most recent 30 s are kept in a ring buffer and
 * handed to Whisper at each tick. Also caches the latest transcript so the recall layer can read it
 * without re-running ASR. Thread-safe (capture thread writes, tick thread reads).
 *
 * Pure Kotlin (no Android) → unit-tested for rolling/window semantics.
 */
class TranscriptBuffer(
    windowSeconds: Int = 30,
    sampleRate: Int = 16000,
) {
    private val capacity = windowSeconds * sampleRate
    private val ring = ShortArray(capacity)
    private var writePos = 0
    private var filled = 0

    @Volatile
    private var latestTranscript: String = ""

    /** Append [length] samples from [samples] (defaults to the whole array). Oldest samples roll off. */
    @Synchronized
    fun append(samples: ShortArray, length: Int = samples.size) {
        val n = length.coerceAtMost(samples.size)
        // If the incoming chunk is larger than the window, keep only its tail.
        val srcStart = if (n > capacity) n - capacity else 0
        for (i in srcStart until n) {
            ring[writePos] = samples[i]
            writePos = (writePos + 1) % capacity
            if (filled < capacity) filled++
        }
    }

    /** The buffered audio, oldest→newest (length = number buffered, ≤ capacity). Empty if none. */
    @Synchronized
    fun window(): ShortArray {
        if (filled == 0) return ShortArray(0)
        val out = ShortArray(filled)
        val start = if (filled < capacity) 0 else writePos
        for (i in 0 until filled) out[i] = ring[(start + i) % capacity]
        return out
    }

    /** Number of buffered samples (≤ capacity). */
    @Synchronized
    fun size(): Int = filled

    @Synchronized
    fun clear() {
        writePos = 0
        filled = 0
    }

    /** Cache the latest ASR transcript for the recall layer. */
    fun setTranscript(text: String) { latestTranscript = text }

    /** Most recent transcript for the current window ("" if none yet). */
    fun getTranscriptWindow(): String = latestTranscript
}

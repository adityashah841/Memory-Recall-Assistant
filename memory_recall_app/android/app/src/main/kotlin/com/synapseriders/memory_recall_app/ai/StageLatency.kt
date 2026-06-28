package com.synapseriders.memory_recall_app.ai

import android.os.SystemClock
import android.util.Log

/**
 * Per-stage latency tracking (IMPLEMENTATION_PLAN.md §22 CP3, §9 CP9 dashboard).
 *
 * Each runner times its inference stages through [measure]; the last duration per stage is retained
 * in [last] so the DebugScreen latency dashboard (CP9) can read it later without re-instrumenting.
 */
object StageLatency {

    /** Canonical stage names used across runners and the latency dashboard. */
    const val FACE_DETECTION = "face_detection"
    const val FACE_ALIGNMENT = "face_alignment"
    const val FACE_EMBEDDING = "face_embedding"
    const val IDENTITY_MATCH = "identity_match"
    const val ASR = "asr"
    const val LLM_GENERATION = "llm_generation"

    private const val TAG = "MemoryLatency"

    private val lastMs = HashMap<String, Long>()

    /** Most recent duration (ms) recorded for [stage], or null if never run. */
    fun last(stage: String): Long? = synchronized(lastMs) { lastMs[stage] }

    /** Snapshot of all recorded stage durations (ms). */
    fun snapshot(): Map<String, Long> = synchronized(lastMs) { HashMap(lastMs) }

    /** Run [block], record + log its wall-clock duration under [stage], and return its result. */
    inline fun <T> measure(stage: String, block: () -> T): T {
        val start = SystemClock.elapsedRealtime()
        try {
            return block()
        } finally {
            record(stage, SystemClock.elapsedRealtime() - start)
        }
    }

    /** Record a duration directly (e.g. when timing is split across native calls). */
    fun record(stage: String, durationMs: Long) {
        synchronized(lastMs) { lastMs[stage] = durationMs }
        Log.d(TAG, "$stage: ${durationMs}ms")
    }
}

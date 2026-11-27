package com.synapseriders.memory_recall_app.capture

/**
 * Pure helper for continuous speech recognition (unit-tested): Android's recognizer sometimes re-emits
 * the previous phrase at the start of the next result ("how was your trip to Chicago" →
 * "how how is your trip to Chicago I'm starting a new job"). [novelTail] keeps only the new words.
 */
object TranscriptMerge {

    /**
     * The part of [next] that isn't a re-recognition of [previous]. If [next] opens by repeating most of
     * [previous]'s words (≥ [OVERLAP_RATIO], allowing small recognition differences), returns the words
     * after that overlap; otherwise returns [next] unchanged. Returns "" if [next] adds nothing.
     */
    fun novelTail(previous: String?, next: String): String {
        val prev = words(previous ?: return next.trim())
        val cur = next.trim().split(WS).filter { it.isNotEmpty() }
        if (prev.isEmpty() || cur.isEmpty()) return next.trim()

        // Look for the previous phrase's words near the start of the new one.
        val window = cur.take(prev.size + SLACK).map(::norm)
        var matched = 0
        var lastMatchIdx = -1
        var searchFrom = 0
        for (w in prev) {
            val idx = window.subList(searchFrom, window.size).indexOf(w)
            if (idx >= 0) {
                matched++
                lastMatchIdx = searchFrom + idx
                searchFrom = lastMatchIdx + 1
            }
        }
        if (matched.toFloat() / prev.size < OVERLAP_RATIO) return next.trim()
        return cur.drop(lastMatchIdx + 1).joinToString(" ")
    }

    private fun words(s: String): List<String> = s.trim().split(WS).map(::norm).filter { it.isNotEmpty() }

    private fun norm(w: String): String = w.lowercase().filter { it.isLetterOrDigit() || it == '\'' }

    private val WS = Regex("\\s+")
    private const val OVERLAP_RATIO = 0.7f
    private const val SLACK = 2
}

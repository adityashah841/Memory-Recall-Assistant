package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.bridge.SuggestionDto
import com.synapseriders.memory_recall_app.data.MemorySessionEntity

/**
 * Template-first question suggestions (IMPLEMENTATION_PLAN.md §12.3). Templates are filled from the
 * retrieved rows only; the LLM may later *rewrite* phrasing but must not invent topics absent from the
 * rows. Suggestion ids are stable so `RecallEngine.answerQuestion` can resolve the chosen text.
 *
 * Pure + deterministic (unit-tested); LLM rewrite is intentionally deferred (no topic invention).
 */
class QuestionSuggestionEngine {

    /** Up to 3 suggestions for the given person/location context over [rows]. */
    fun suggest(
        personName: String?,
        locationLabel: String?,
        rows: List<MemorySessionEntity>,
    ): List<SuggestionDto> {
        val place = locationLabel ?: rows.firstOrNull { it.locationLabel != null }?.locationLabel
        val out = mutableListOf<SuggestionDto>()

        if (personName != null) {
            val topic = extractTopic(rows)
            if (topic != null) {
                val where = if (place != null) " at $place" else ""
                // Transcripts aren't split by speaker, so ask about the shared conversation.
                out += SuggestionDto("q_topic", "What did $personName and I talk about regarding $topic$where?")
            }
            out += SuggestionDto("q_last_seen", "When did I last see $personName?")
        }
        if (place != null) {
            out += SuggestionDto("q_place", "What did we discuss at $place?")
        }
        if (out.isEmpty()) {
            out += SuggestionDto("q_recent", "What happened recently?")
        }
        return out.take(3)
    }

    /** Naive topic: the longest alphabetic word in the most recent transcript (no LLM, no invention). */
    private fun extractTopic(rows: List<MemorySessionEntity>): String? {
        val transcript = rows.firstOrNull { !it.transcript.isNullOrBlank() }?.transcript ?: return null
        return transcript
            .split(Regex("[^A-Za-z]+"))
            .filter { it.length > 4 }
            .filterNot { it.lowercase() in STOPWORDS }
            .maxByOrNull { it.length }
    }

    private companion object {
        val STOPWORDS = setOf(
            "about", "there", "their", "would", "could", "should", "really", "where", "which",
            "these", "those", "today", "tomorrow", "yesterday", "again", "going", "thing", "things",
        )
    }
}

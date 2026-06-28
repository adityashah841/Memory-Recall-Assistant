package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP7 (§12.3): template-first suggestions, deterministic ids. */
class QuestionSuggestionEngineTest {

    private val engine = QuestionSuggestionEngine()

    private fun row(label: String?, transcript: String?) = MemorySessionEntity(
        personId = 1L, locationLabel = label, latitude = null, longitude = null,
        timestamp = 1L, frameImage = "f.jpg", transcript = transcript, createdAt = 1L,
    )

    @Test
    fun knownPersonGetsUpToThreeSuggestionsWithStableIds() {
        val rows = listOf(row("cafe", "Kyoto temples were the highlight"))
        val s = engine.suggest("Priya", "cafe", rows)
        assertEquals(3, s.size)
        val ids = s.map { it.id }.toSet()
        assertTrue(ids.containsAll(setOf("q_topic", "q_last_seen", "q_place")))
        assertTrue(s.any { it.questionText.contains("Priya") })
    }

    @Test
    fun noPersonGivesLocationSuggestion() {
        val s = engine.suggest(personName = null, locationLabel = "home desk", rows = listOf(row("home desk", "note")))
        assertEquals(listOf("q_place"), s.map { it.id })
        assertTrue(s.first().questionText.contains("home desk"))
    }

    @Test
    fun emptyContextFallsBackToRecent() {
        val s = engine.suggest(personName = null, locationLabel = null, rows = emptyList())
        assertEquals(listOf("q_recent"), s.map { it.id })
    }
}

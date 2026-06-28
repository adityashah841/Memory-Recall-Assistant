package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.ai.FakeModelRunner
import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP7 (§7.2, §12.4): grounded answer + evidence; empty-rows message. */
class AnswerGeneratorTest {

    private val gen = AnswerGenerator(FakeModelRunner())

    private fun row(id: Long) = MemorySessionEntity(
        sessionId = id, personId = 1L, locationLabel = "cafe", latitude = null, longitude = null,
        timestamp = id, frameImage = "f$id.jpg", transcript = "transcript $id", createdAt = id,
    )

    @Test
    fun emptyRowsReturnsNoMemoryMessage() = runBlocking {
        val a = gen.answer("Priya", "When did I last see Priya?", emptyList())
        assertEquals("I don't have a memory of that.", a.answerText)
        assertTrue(a.evidence.isEmpty())
    }

    @Test
    fun attachesUpToThreeEvidenceRows() = runBlocking {
        val a = gen.answer("Priya", "What did we discuss?", (1L..5L).map { row(it) })
        assertEquals(3, a.evidence.size)
        assertTrue(a.answerText.isNotBlank()) // FakeModelRunner returns a non-blank completion
        assertEquals("cafe", a.evidence.first().locationLabel)
    }
}

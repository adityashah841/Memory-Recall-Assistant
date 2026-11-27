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

    @Test
    fun timeAgoIsHumanReadable() {
        val now = 10L * 24 * 3_600_000
        val g = AnswerGenerator(FakeModelRunner(), clock = { now })
        assertEquals("just now", g.timeAgo(now - 10_000))
        assertEquals("5 minutes ago", g.timeAgo(now - 5 * 60_000))
        assertEquals("1 hour ago", g.timeAgo(now - 61 * 60_000))
        assertEquals("yesterday", g.timeAgo(now - 30L * 3_600_000))
        assertEquals("3 days ago", g.timeAgo(now - 3L * 24 * 3_600_000))
    }

    @Test
    fun fallsBackToMostRecentMemoryWhenLlmUnavailable() = runBlocking {
        val silent = object : com.synapseriders.memory_recall_app.ai.OnDeviceModelRunner by FakeModelRunner() {
            override fun generateChat(system: String, user: String, maxTokens: Int) = ""
        }
        val a = AnswerGenerator(silent).answer("Priya", "What did we discuss?", listOf(row(1L)))
        assertTrue(a.answerText.startsWith("Most recent memory with Priya"))
        assertTrue(a.answerText.contains("cafe"))
    }
}

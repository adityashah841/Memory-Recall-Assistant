package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.ai.OnDeviceModelRunner
import com.synapseriders.memory_recall_app.bridge.EvidenceDto
import com.synapseriders.memory_recall_app.bridge.RecallAnswerDto
import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Grounded answer generation (IMPLEMENTATION_PLAN.md §7.2, §12.4). Builds the answer prompt from the
 * top retrieved transcripts, runs the LLM with a 5 s budget, and always attaches the cited rows as
 * evidence. On empty rows → "I don't have a memory of that."; on LLM timeout/unavailable → the
 * template fallback `"This is {name}."` (§7.3). Never throws.
 */
class AnswerGenerator(private val runner: OnDeviceModelRunner) {

    suspend fun answer(
        personName: String?,
        question: String,
        rows: List<MemorySessionEntity>,
    ): RecallAnswerDto {
        if (rows.isEmpty()) {
            return RecallAnswerDto("I don't have a memory of that.", emptyList())
        }
        val evidence = rows.take(MAX_EVIDENCE).map { it.toEvidence() }

        val generated = withTimeoutOrNull(LLM_TIMEOUT_MS) {
            withContext(Dispatchers.Default) { runner.generate(buildPrompt(question, rows), MAX_TOKENS) }
        }?.trim()

        val text = if (!generated.isNullOrBlank()) generated else templateAnswer(personName)
        return RecallAnswerDto(text, evidence)
    }

    /** §7.2 answer prompt: grounded strictly in the retrieved transcripts. */
    private fun buildPrompt(question: String, rows: List<MemorySessionEntity>): String {
        val records = rows.take(MAX_EVIDENCE)
            .mapNotNull { it.transcript?.takeIf(String::isNotBlank) }
            .joinToString("\n")
        return buildString {
            append("<|begin_of_text|><|start_header_id|>system<|end_header_id|>\n")
            append("You are a private memory assistant. Answer the question using only the provided ")
            append("memory records. If no evidence exists say \"I don't have a memory of that.\"\n")
            append("<|eot_id|><|start_header_id|>user<|end_header_id|>\n")
            append("Question: ").append(question).append('\n')
            append("Memory records:\n").append(records).append('\n')
            append("Answer with evidence:\n")
            append("<|eot_id|><|start_header_id|>assistant<|end_header_id|>")
        }
    }

    private fun templateAnswer(personName: String?): String =
        if (personName != null) "This is $personName." else "Here's what I remember from nearby."

    private fun MemorySessionEntity.toEvidence(): EvidenceDto = EvidenceDto(
        sessionId = sessionId,
        timestampMs = timestamp,
        locationLabel = locationLabel,
        frameThumbPath = frameImage,
        transcriptSnippet = transcript?.take(SNIPPET_LEN),
    )

    private companion object {
        const val LLM_TIMEOUT_MS = 5_000L
        const val MAX_TOKENS = 200
        const val MAX_EVIDENCE = 3
        const val SNIPPET_LEN = 160
    }
}

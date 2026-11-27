package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.ai.OnDeviceModelRunner
import com.synapseriders.memory_recall_app.bridge.EvidenceDto
import com.synapseriders.memory_recall_app.bridge.RecallAnswerDto
import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Grounded answer + recall-cue generation (IMPLEMENTATION_PLAN.md §7, §12.4).
 *
 * Builds model-agnostic system/user prompts from the top retrieved sessions and runs them through
 * [OnDeviceModelRunner.generateChat] (Gemini Nano or Llama/Genie, whichever backend is active).
 * Records carry *when* and *where* each memory happened so time/place questions are answerable.
 * Evidence (the cited rows) is always attached. On empty rows → "I don't have a memory of that.";
 * when the LLM is unavailable or times out → a deterministic summary of the most recent memory.
 * Never throws.
 */
class AnswerGenerator(
    private val runner: OnDeviceModelRunner,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {

    suspend fun answer(
        personName: String?,
        question: String,
        rows: List<MemorySessionEntity>,
    ): RecallAnswerDto {
        if (rows.isEmpty()) {
            return RecallAnswerDto(NO_MEMORY, emptyList())
        }
        val cited = rows.take(MAX_EVIDENCE)
        val evidence = cited.map { it.toEvidence() }

        val user = buildString {
            if (personName != null) append("The person in front of the user is ").append(personName).append(".\n")
            append("Question: ").append(question).append("\n\n")
            append("Memory records (most recent first):\n").append(records(cited)).append('\n')
            append("Answer the question in 1-3 short sentences using only these records. ")
            append("Mention when or where it happened if that helps. ")
            append("Describe what was discussed as a shared conversation (\"you talked about…\"); ")
            append("never claim which person said something. ")
            append("If the records don't contain the answer, reply exactly: \"").append(NO_MEMORY).append('"')
        }
        val generated = llm(ANSWER_SYSTEM, user, ANSWER_MAX_TOKENS)
        val text = if (!generated.isNullOrBlank()) generated else fallbackAnswer(personName, cited.first())
        return RecallAnswerDto(text, evidence)
    }

    /**
     * One warm sentence to jog the user's memory of a known person (§7.1), e.g. "This is Priya — at the
     * office cafe yesterday you talked about the Kyoto trip." Speaker-neutral: transcripts aren't
     * diarized, so it never claims who said what. Template fallback if the LLM is unavailable.
     */
    suspend fun recallCue(personName: String, rows: List<MemorySessionEntity>): String {
        val latest = rows.firstOrNull() ?: return "This is $personName."
        val user = buildString {
            append("Person: ").append(personName).append('\n')
            append("Last seen: ").append(timeAgo(latest.timestamp)).append('\n')
            latest.locationLabel?.let { append("Location: ").append(it).append('\n') }
            val talk = rows.mapNotNull { it.transcript?.takeIf(String::isNotBlank) }.take(2)
            if (talk.isNotEmpty()) append("Recent conversation (both people, speakers not labeled): ")
                .append(talk.joinToString(" | ")).append('\n')
            append("Generate the recall cue:")
        }
        val generated = llm(CUE_SYSTEM, user, CUE_MAX_TOKENS)
        return if (!generated.isNullOrBlank()) generated else fallbackCue(personName, latest)
    }

    private suspend fun llm(system: String, user: String, maxTokens: Int): String? =
        withTimeoutOrNull(LLM_TIMEOUT_MS) {
            withContext(Dispatchers.Default) { runner.generateChat(system, user, maxTokens) }
        }?.trim()

    private fun records(rows: List<MemorySessionEntity>): String =
        rows.mapIndexed { i, r ->
            val where = r.locationLabel?.let { ", $it" } ?: ""
            val said = r.transcript?.takeIf(String::isNotBlank) ?: "(no conversation recorded)"
            "${i + 1}. [${timeAgo(r.timestamp)}$where] $said"
        }.joinToString("\n")

    private fun fallbackAnswer(personName: String?, latest: MemorySessionEntity): String {
        val who = personName?.let { " with $it" } ?: ""
        val where = latest.locationLabel?.let { " at $it" } ?: ""
        val said = latest.transcript?.takeIf(String::isNotBlank)?.let { " — \"${it.take(SNIPPET_LEN)}\"" } ?: ""
        return "Most recent memory$who: ${timeAgo(latest.timestamp)}$where$said"
    }

    private fun fallbackCue(personName: String, latest: MemorySessionEntity): String {
        val where = latest.locationLabel?.let { " at $it" } ?: ""
        return "This is $personName. You last saw them ${timeAgo(latest.timestamp)}$where."
    }

    /** "just now", "5 minutes ago", "3 hours ago", "yesterday", "4 days ago". */
    internal fun timeAgo(timestampMs: Long): String {
        val mins = (clock() - timestampMs).coerceAtLeast(0) / 60_000
        return when {
            mins < 1 -> "just now"
            mins < 60 -> "$mins minute${if (mins == 1L) "" else "s"} ago"
            mins < 24 * 60 -> (mins / 60).let { "$it hour${if (it == 1L) "" else "s"} ago" }
            mins < 48 * 60 -> "yesterday"
            else -> "${mins / (24 * 60)} days ago"
        }
    }

    private fun MemorySessionEntity.toEvidence(): EvidenceDto = EvidenceDto(
        sessionId = sessionId,
        timestampMs = timestamp,
        locationLabel = locationLabel,
        frameThumbPath = frameImage,
        transcriptSnippet = transcript?.take(SNIPPET_LEN),
    )

    private companion object {
        const val NO_MEMORY = "I don't have a memory of that."
        /** Transcripts mix both people's speech with no speaker labels (no diarization on-device). */
        const val SPEAKERS_NOTE = "Each transcript is a conversation between the user and the other " +
            "person, with both voices mixed and no speaker labels, so you cannot know who said what. " +
            "Always describe topics as things \"you talked about\" or \"you discussed\" together, and " +
            "refer to them neutrally (\"the Chicago trip\", not \"her trip\" or \"your trip\"). " +
            "Never say the other person told, said or mentioned something."
        const val ANSWER_SYSTEM = "You are a private, on-device memory assistant helping someone recall " +
            "their own past conversations. Answer only from the memory records you are given. Be warm, " +
            "brief and concrete. Never invent people, places, times or topics that are not in the records. " +
            SPEAKERS_NOTE
        const val CUE_SYSTEM = "You are a private memory assistant. Generate one short warm sentence to " +
            "help the user remember the person in front of them, e.g. \"This is Sam — yesterday at the " +
            "cafe you talked about the Chicago trip and the new job.\" Use only the facts given. Never " +
            "mention memory loss. Maximum 2 sentences. " + SPEAKERS_NOTE
        // The LLM call enforces its own (shorter) timeout; this is only a safety net.
        const val LLM_TIMEOUT_MS = 15_000L
        const val ANSWER_MAX_TOKENS = 200
        const val CUE_MAX_TOKENS = 60
        const val MAX_EVIDENCE = 3
        const val SNIPPET_LEN = 160
    }
}

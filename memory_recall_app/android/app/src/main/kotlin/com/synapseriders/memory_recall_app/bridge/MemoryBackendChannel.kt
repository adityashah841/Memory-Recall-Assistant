package com.synapseriders.memory_recall_app.bridge

import android.content.Context
import com.synapseriders.memory_recall_app.data.MemoryDatabase
import com.synapseriders.memory_recall_app.data.MemoryRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Host implementation of the Pigeon [MemoryBackendApi].
 *
 * Milestone 2: timeline/enrollment/debug data flows through the real Room data layer via
 * [MemoryRepository]. The capture loop (service lifecycle) and recall pipeline are still stubbed
 * and arrive in later milestones (IMPLEMENTATION_PLAN.md Section 16). Per Section 13.2 the channel
 * holds no business logic beyond delegation + DTO mapping.
 *
 * `@async` host methods run their work off the platform thread (Section 14.3): each coroutine is
 * launched on the main dispatcher, offloads the DB call to [Dispatchers.IO], then delivers the
 * Pigeon callback back on the main thread.
 */
class MemoryBackendChannel(context: Context) : MemoryBackendApi {

    private val repository: MemoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // In-memory capture flag until the foreground service lands (Milestone 3).
    private var running: Boolean = false

    init {
        val db = MemoryDatabase.getInstance(context)
        repository = MemoryRepository(db.personDao(), db.sessionDao())
    }

    // --- Service lifecycle (in-memory stub) ----------------------------------

    override fun startCapture() {
        running = true
    }

    override fun stopCapture() {
        running = false
    }

    override fun forceTick() {
        // Stub: a real tick (Milestone 3) would persist a session here.
    }

    override fun getServiceState(callback: (Result<ServiceStateDto>) -> Unit) = deliver(callback) {
        ServiceStateDto(
            running = running,
            sessionCount = repository.sessionCount(),
            personCount = repository.personCount(),
        )
    }

    // --- Recall flow (canned until Milestone 6) ------------------------------

    override fun captureRecallContext(): RecallContextDto = RecallContextDto(
        personId = 1L,
        personName = "Priya",
        personKnown = true, // Case A
        personPresent = true,
        locationLabel = "office cafe",
        latitude = 37.4219983,
        longitude = -122.084,
        currentTranscript = null,
        frameThumbPath = "frames/priya_cafe_1.jpg",
    )

    override fun getSuggestions(ctx: RecallContextDto): List<SuggestionDto> = listOf(
        SuggestionDto(id = "q1", questionText = "What did Priya say about Kyoto?"),
        SuggestionDto(id = "q2", questionText = "When did I last see Priya?"),
        SuggestionDto(id = "q3", questionText = "Where did I meet Priya?"),
    )

    override fun answerQuestion(
        ctx: RecallContextDto,
        suggestionId: String,
        freeText: String?,
    ): RecallAnswerDto = RecallAnswerDto(
        answerText = "You last spoke with Priya at the office cafe about her Kyoto trip; " +
            "she said the temples in Arashiyama were the highlight.",
        evidence = listOf(
            EvidenceDto(
                sessionId = 101L,
                timestampMs = 1_719_300_000_000L,
                locationLabel = "office cafe",
                frameThumbPath = "frames/priya_cafe_1.jpg",
                transcriptSnippet = "Kyoto was incredible, the temples in Arashiyama were the highlight.",
            ),
        ),
    )

    // --- Enrollment ----------------------------------------------------------

    override fun enrollPerson(
        personId: Long,
        name: String,
        contextNotes: String?,
        callback: (Result<Unit>) -> Unit,
    ) = deliver(callback) {
        repository.enrollPerson(personId, name, contextNotes)
    }

    // --- Timeline / debug ----------------------------------------------------

    override fun listSessions(
        limit: Long,
        offset: Long,
        callback: (Result<List<MemorySessionDto>>) -> Unit,
    ) = deliver(callback) {
        repository.listSessions(limit, offset).map { it.toDto() }
    }

    override fun listPeople(callback: (Result<List<PersonProfileDto>>) -> Unit) = deliver(callback) {
        repository.listPeople().map { it.toDto() }
    }

    override fun seedDemoData(callback: (Result<Unit>) -> Unit) = deliver(callback) {
        repository.seedDemo()
    }

    override fun clearAllData(callback: (Result<Unit>) -> Unit) = deliver(callback) {
        repository.clearAll()
    }

    /**
     * Run [block] off the platform thread and deliver its outcome to [callback] on the main
     * thread, mapping any throwable to a failed [Result] (surfaced to Dart as a PlatformException).
     */
    private fun <T> deliver(callback: (Result<T>) -> Unit, block: suspend () -> T) {
        scope.launch {
            val result = try {
                Result.success(withContext(Dispatchers.IO) { block() })
            } catch (e: Throwable) {
                Result.failure(e)
            }
            callback(result)
        }
    }
}

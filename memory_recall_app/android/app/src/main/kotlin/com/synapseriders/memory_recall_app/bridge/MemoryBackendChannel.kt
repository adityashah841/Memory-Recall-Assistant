package com.synapseriders.memory_recall_app.bridge

import android.content.Context
import com.synapseriders.memory_recall_app.ai.CompositeModelRunner
import com.synapseriders.memory_recall_app.data.MemoryDatabase
import com.synapseriders.memory_recall_app.data.MemoryRepository
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import com.synapseriders.memory_recall_app.recall.AnswerGenerator
import com.synapseriders.memory_recall_app.recall.QuestionSuggestionEngine
import com.synapseriders.memory_recall_app.recall.RecallEngine
import com.synapseriders.memory_recall_app.recall.RetrievalRanker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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

    // CP3: the production model runner. Warm-up moves to MemoryForegroundService in CP6 (§20.2, §24);
    // for now it is warmed here off the UI thread so CP3 is independently demoable (DoD: models load
    // at startup, visible in logcat via ModelAssets.report()).
    private val modelRunner = CompositeModelRunner(context)

    // CP7: read-path recall engine. Frame/location providers default to null until CP6 wires the
    // camera, so captureRecallContext falls back to seeded data (§24.x).
    private val recallEngine: RecallEngine

    // In-memory capture flag until the foreground service lands (Milestone 3).
    private var running: Boolean = false

    init {
        val db = MemoryDatabase.getInstance(context)
        val personDao = db.personDao()
        val sessionDao = db.sessionDao()
        repository = MemoryRepository(personDao, sessionDao)
        recallEngine = RecallEngine(
            personDao = personDao,
            sessionDao = sessionDao,
            faceEngine = FaceEmbeddingEngine(modelRunner),
            ranker = RetrievalRanker(sessionDao),
            suggester = QuestionSuggestionEngine(),
            answerer = AnswerGenerator(modelRunner),
        )
        scope.launch(Dispatchers.Default) { modelRunner.warmUp() }
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

    // --- Recall flow (CP7: real read path via RecallEngine) ------------------
    // These Pigeon methods are synchronous; the recall work is suspend, so we bridge with
    // runBlocking on Dispatchers.IO (DB queries fast; LLM is time-boxed to 5s and currently falls
    // back instantly). §24.x: move to @async (Pigeon regen) once the Genie LLM is wired live.

    override fun captureRecallContext(): RecallContextDto =
        runBlocking(Dispatchers.IO) { recallEngine.captureRecallContext() }

    override fun getSuggestions(ctx: RecallContextDto): List<SuggestionDto> =
        runBlocking(Dispatchers.IO) { recallEngine.getSuggestions(ctx) }

    override fun answerQuestion(
        ctx: RecallContextDto,
        suggestionId: String,
        freeText: String?,
    ): RecallAnswerDto = runBlocking(Dispatchers.IO) {
        recallEngine.answerQuestion(ctx, suggestionId, freeText)
    }

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

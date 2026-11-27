package com.synapseriders.memory_recall_app.bridge

import android.content.Context
import com.synapseriders.memory_recall_app.ai.ModelRuntime
import com.synapseriders.memory_recall_app.capture.CaptureState
import com.synapseriders.memory_recall_app.data.MemoryDatabase
import com.synapseriders.memory_recall_app.data.MemoryRepository
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import com.synapseriders.memory_recall_app.recall.AnswerGenerator
import com.synapseriders.memory_recall_app.recall.QuestionSuggestionEngine
import com.synapseriders.memory_recall_app.recall.RecallEngine
import com.synapseriders.memory_recall_app.recall.LivePreviewProvider
import com.synapseriders.memory_recall_app.recall.RecallLocation
import com.synapseriders.memory_recall_app.recall.RecallSpeaker
import com.synapseriders.memory_recall_app.recall.RetrievalRanker
import com.synapseriders.memory_recall_app.enrollment.PersonEnrollmentManager
import com.synapseriders.memory_recall_app.service.MemoryForegroundService
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

    private val appContext = context.applicationContext
    private val repository: MemoryRepository
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    // Shared on-device model runner (warmed by the foreground service; lazy-warms on first use too).
    private val modelRunner = ModelRuntime.get(context)

    // CP7 read-path recall engine. CP6 supplies live frame/location from CaptureState; when the
    // service isn't running these are null and captureRecallContext falls back to seeded data (§24.4).
    private val recallEngine: RecallEngine

    // CP8 proactive enrollment (5-frame capture → average → store), reads the live camera frame.
    private val enrollmentManager: PersonEnrollmentManager

    // Live camera view + targeting state for the Recall screen.
    private val livePreview: LivePreviewProvider

    // Reads recall cues / answers aloud (CP9 "cue spoken").
    private val speaker = RecallSpeaker(context)

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
            frameProvider = { CaptureState.latestFrame },
            locationProvider = {
                CaptureState.latestLocation?.let { RecallLocation(it.label, it.latitude, it.longitude) }
            },
        )
        speaker.warmUp()
        livePreview = LivePreviewProvider(personDao, FaceEmbeddingEngine(modelRunner))
        enrollmentManager = PersonEnrollmentManager(
            personDao = personDao,
            faceEngine = FaceEmbeddingEngine(modelRunner),
            frameProvider = { CaptureState.latestFrame },
        )
    }

    // --- Service lifecycle (CP6: real foreground service) --------------------

    override fun startCapture() {
        MemoryForegroundService.start(appContext)
    }

    override fun stopCapture() {
        MemoryForegroundService.stop(appContext)
    }

    override fun forceTick() {
        MemoryForegroundService.tick(appContext)
    }

    override fun getServiceState(callback: (Result<ServiceStateDto>) -> Unit) = deliver(callback) {
        ServiceStateDto(
            running = MemoryForegroundService.running,
            sessionCount = repository.sessionCount(),
            personCount = repository.personCount(),
        )
    }

    // --- Recall flow (CP7: real read path via RecallEngine) ------------------
    // @async Pigeon methods: face ID + on-device LLM run on Dispatchers.IO and the result is
    // delivered back on the main thread, so the UI thread never blocks (no ANR while Gemini thinks).

    override fun captureRecallContext(callback: (Result<RecallContextDto>) -> Unit) = deliver(callback) {
        speaker.stop()
        recallEngine.captureRecallContext().also { ctx ->
            speaker.speak(ctx.recallCue ?: if (ctx.personPresent && !ctx.personKnown) UNKNOWN_SPOKEN else null)
        }
    }

    override fun getSuggestions(
        ctx: RecallContextDto,
        callback: (Result<List<SuggestionDto>>) -> Unit,
    ) = deliver(callback) {
        recallEngine.getSuggestions(ctx)
    }

    override fun answerQuestion(
        ctx: RecallContextDto,
        suggestionId: String,
        freeText: String?,
        callback: (Result<RecallAnswerDto>) -> Unit,
    ) = deliver(callback) {
        recallEngine.answerQuestion(ctx, suggestionId, freeText).also { speaker.speak(it.answerText) }
    }

    // --- Live camera view (Phase 4 preview) ----------------------------------

    override fun getLivePreview(maxWidth: Long, callback: (Result<LivePreviewDto>) -> Unit) =
        deliver(callback) { livePreview.snapshot(maxWidth.toInt()) }

    // --- Enrollment ----------------------------------------------------------

    override fun enrollPerson(
        personId: Long,
        name: String,
        contextNotes: String?,
        callback: (Result<Unit>) -> Unit,
    ) = deliver(callback) {
        repository.enrollPerson(personId, name, contextNotes)
    }

    override fun enrollNewPerson(
        name: String,
        contextNotes: String?,
        callback: (Result<EnrollResultDto>) -> Unit,
    ) = deliver(callback) {
        val result = enrollmentManager.enroll(name, contextNotes)
        EnrollResultDto(
            success = result.success,
            personId = result.personId,
            samplesCaptured = result.samples.toLong(),
            message = result.message,
        )
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

    private companion object {
        const val UNKNOWN_SPOKEN = "I don't recognize this person yet."
    }
}

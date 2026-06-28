package com.synapseriders.memory_recall_app.recall

import android.graphics.Bitmap
import com.synapseriders.memory_recall_app.bridge.RecallAnswerDto
import com.synapseriders.memory_recall_app.bridge.RecallContextDto
import com.synapseriders.memory_recall_app.bridge.SuggestionDto
import com.synapseriders.memory_recall_app.data.PersonDao
import com.synapseriders.memory_recall_app.data.SessionDao
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine

/** Current location for recall vicinity (filled by `LocationProvider` in CP6). */
data class RecallLocation(val label: String?, val latitude: Double?, val longitude: Double?)

/**
 * Read-path orchestrator (IMPLEMENTATION_PLAN.md §11, §12, §8.4).
 *
 * Identifies the current center face (Case A/B/C), retrieves + ranks the relevant sessions, builds
 * template suggestions, and produces a grounded answer with evidence.
 *
 * > §24 deviation: CP7 is implemented before CP6 (capture) / CP8 (enrollment). The live frame +
 * > location come from [frameProvider]/[locationProvider]; until CP6 wires the camera they return
 * > null, so `captureRecallContext` falls back to the **most-recent seeded session** (via
 * > `seedDemoData`) so the read path is fully demoable. The real camera path is active the moment CP6
 * > supplies a frame provider.
 */
class RecallEngine(
    private val personDao: PersonDao,
    private val sessionDao: SessionDao,
    private val faceEngine: FaceEmbeddingEngine,
    private val ranker: RetrievalRanker,
    private val suggester: QuestionSuggestionEngine,
    private val answerer: AnswerGenerator,
    private val frameProvider: () -> Bitmap? = { null },
    private val locationProvider: () -> RecallLocation? = { null },
) {

    /** Identify the center face in the current frame and build the recall context (§11). */
    suspend fun captureRecallContext(): RecallContextDto {
        val loc = locationProvider()
        val frame = frameProvider()
        if (frame != null) {
            val match = faceEngine.identify(frame, personDao.findAll())
            return when {
                match == null -> contextNoPerson(loc)                       // Case C
                match.isKnown -> context(                                   // Case A
                    personId = match.personId.toLong(),
                    name = match.name,
                    known = true,
                    loc = loc,
                )
                else -> context(personId = null, name = null, known = false, loc = loc) // Case B
            }
        }
        return demoContextFromDb(loc)
    }

    /** Suggestions for Cases A/C (§12.3). Case B has no history → empty. */
    suspend fun getSuggestions(ctx: RecallContextDto): List<SuggestionDto> {
        if (ctx.personPresent && !ctx.personKnown) return emptyList() // Case B
        val rows = ranker.retrieve(ctx.personId, ctx.locationLabel, ctx.latitude, ctx.longitude)
        return suggester.suggest(ctx.personName, ctx.locationLabel, rows)
    }

    /** Grounded answer for the selected suggestion (or free text), with evidence (§12.4). */
    suspend fun answerQuestion(ctx: RecallContextDto, suggestionId: String, freeText: String?): RecallAnswerDto {
        val rows = ranker.retrieve(ctx.personId, ctx.locationLabel, ctx.latitude, ctx.longitude)
        val question = freeText?.takeIf { it.isNotBlank() }
            ?: suggester.suggest(ctx.personName, ctx.locationLabel, rows)
                .firstOrNull { it.id == suggestionId }?.questionText
            ?: "What happened recently?"
        return answerer.answer(ctx.personName, question, rows)
    }

    // --- context builders ---

    private fun context(personId: Long?, name: String?, known: Boolean, loc: RecallLocation?) =
        RecallContextDto(
            personId = personId,
            personName = name,
            personKnown = known,
            personPresent = true,
            locationLabel = loc?.label,
            latitude = loc?.latitude,
            longitude = loc?.longitude,
            currentTranscript = null,
            frameThumbPath = null,
        )

    private fun contextNoPerson(loc: RecallLocation?) = RecallContextDto(
        personId = null,
        personName = null,
        personKnown = false,
        personPresent = false,
        locationLabel = loc?.label,
        latitude = loc?.latitude,
        longitude = loc?.longitude,
        currentTranscript = null,
        frameThumbPath = null,
    )

    /** Demo fallback (no camera yet): treat the most-recent known-person session as the target. */
    private suspend fun demoContextFromDb(loc: RecallLocation?): RecallContextDto {
        val recent = sessionDao.findRecent(20)
        val withPerson = recent.firstOrNull { it.personId != null }
        if (withPerson != null) {
            val person = personDao.findById(withPerson.personId!!)
            return RecallContextDto(
                personId = person?.personId,
                personName = person?.name,
                personKnown = true,
                personPresent = true,
                locationLabel = loc?.label ?: withPerson.locationLabel,
                latitude = loc?.latitude ?: withPerson.latitude,
                longitude = loc?.longitude ?: withPerson.longitude,
                currentTranscript = withPerson.transcript,
                frameThumbPath = withPerson.frameImage,
            )
        }
        val any = recent.firstOrNull()
        return RecallContextDto(
            personId = null,
            personName = null,
            personKnown = false,
            personPresent = false,
            locationLabel = loc?.label ?: any?.locationLabel,
            latitude = loc?.latitude ?: any?.latitude,
            longitude = loc?.longitude ?: any?.longitude,
            currentTranscript = null,
            frameThumbPath = any?.frameImage,
        )
    }
}

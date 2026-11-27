package com.synapseriders.memory_recall_app.data

/**
 * Single entry point over the DAOs (Section 13.6), used by the bridge today and by the capture
 * loop / recall engine in later milestones. All methods are `suspend`; callers choose the
 * dispatcher.
 */
class MemoryRepository(
    private val personDao: PersonDao,
    private val sessionDao: SessionDao,
) : CaptureSink {

    // --- Capture write path (§10, CP6) ---
    override suspend fun recordKnown(personId: Long, rec: CaptureRecord) {
        insertOrMerge(rec.toSession(personId))
    }

    override suspend fun recordUnknown(faceEmbedding: ByteArray, rec: CaptureRecord): Long {
        val personId = personDao.insert(
            PersonProfileEntity(
                name = null,
                faceEmbedding = faceEmbedding,
                contextNotes = null,
                createdAt = rec.timestamp,
                updatedAt = rec.timestamp,
            ),
        )
        insertOrMerge(rec.toSession(personId))
        return personId
    }

    override suspend fun recordNoPerson(rec: CaptureRecord) {
        insertOrMerge(rec.toSession(null))
    }

    /**
     * One diary entry per **conversation**, not one per 5 s tick. A conversation is a continuous stretch
     * of ticks with gaps ≤ [MERGE_WINDOW_MS]; once a person is identified in it, it belongs to them —
     * because in real use the camera drifts off the face for a few seconds while people keep talking:
     * - same subject as the newest session → extend it (append transcript, bump time);
     * - newest session has no person, now a person is centered → claim it for that person
     *   (speech heard just before the face came into view belongs to them);
     * - newest session has a person, now nobody is centered → extend it, keeping the person;
     * - a *different* person → start a new session.
     */
    private suspend fun insertOrMerge(session: MemorySessionEntity) {
        val last = sessionDao.findRecent(1).firstOrNull()
        val sameConversation = last != null &&
            (session.timestamp - last.timestamp) in 0..MERGE_WINDOW_MS &&
            (last.personId == session.personId || last.personId == null || session.personId == null)
        if (last != null && sameConversation) {
            sessionDao.extend(
                sessionId = last.sessionId,
                personId = last.personId ?: session.personId, // whoever was identified owns it
                transcript = joinTranscripts(last.transcript, session.transcript),
                timestamp = session.timestamp,
            )
        } else {
            sessionDao.insert(session)
        }
    }

    private fun joinTranscripts(a: String?, b: String?): String? = when {
        a.isNullOrBlank() -> b
        b.isNullOrBlank() -> a
        else -> "${a.trimEnd()} ${b.trim()}"
    }

    private fun CaptureRecord.toSession(personId: Long?) = MemorySessionEntity(
        personId = personId,
        locationLabel = locationLabel,
        latitude = latitude,
        longitude = longitude,
        timestamp = timestamp,
        frameImage = frameThumbPath,
        transcript = transcript,
        createdAt = timestamp,
    )

    // --- Reads (timeline / debug) ---
    suspend fun listSessions(limit: Long, offset: Long): List<SessionWithPersonName> =
        sessionDao.listPaged(limit, offset)

    suspend fun listPeople(): List<PersonProfileEntity> = personDao.findAll()

    suspend fun sessionCount(): Long = sessionDao.count()

    suspend fun personCount(): Long = personDao.count()

    // --- Enrollment (Case B) ---
    suspend fun enrollPerson(personId: Long, name: String, contextNotes: String?) {
        personDao.updateNameAndNotes(
            personId = personId,
            name = name,
            contextNotes = contextNotes,
            updatedAt = System.currentTimeMillis(),
        )
    }

    // --- Demo / retention ---
    suspend fun seedDemo() {
        clearAll()
        DemoSeeder(personDao, sessionDao).seed()
    }

    suspend fun clearAll() {
        // Children first to avoid transient FK violations.
        sessionDao.clear()
        personDao.clear()
    }

    companion object {
        /** Ticks closer than this (same subject) extend the same diary entry. */
        const val MERGE_WINDOW_MS = 60_000L
    }
}

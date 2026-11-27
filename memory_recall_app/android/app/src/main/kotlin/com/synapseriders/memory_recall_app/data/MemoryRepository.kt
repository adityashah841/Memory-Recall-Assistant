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
        sessionDao.insert(rec.toSession(personId))
        return personId
    }

    override suspend fun recordNoPerson(rec: CaptureRecord) {
        insertOrMerge(rec.toSession(null))
    }

    /**
     * One diary entry per conversation, not one per 5 s tick: if the newest session is about the same
     * subject (same person, or both no-person) and was last updated within [MERGE_WINDOW_MS], append
     * this tick's transcript to it and bump its time; otherwise insert a new session. Keeps the
     * timeline readable and gives the LLM whole conversations to answer from.
     */
    private suspend fun insertOrMerge(session: MemorySessionEntity) {
        val last = sessionDao.findRecent(1).firstOrNull()
        val gap = session.timestamp - (last?.timestamp ?: Long.MIN_VALUE / 2)
        if (last != null && last.personId == session.personId && gap in 0..MERGE_WINDOW_MS) {
            sessionDao.extend(last.sessionId, joinTranscripts(last.transcript, session.transcript), session.timestamp)
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

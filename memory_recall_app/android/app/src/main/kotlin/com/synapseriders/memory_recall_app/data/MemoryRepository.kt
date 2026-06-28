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
        sessionDao.insert(rec.toSession(personId))
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
        sessionDao.insert(rec.toSession(null))
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
}

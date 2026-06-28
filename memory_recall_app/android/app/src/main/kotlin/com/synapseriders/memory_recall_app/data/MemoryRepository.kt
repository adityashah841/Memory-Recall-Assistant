package com.synapseriders.memory_recall_app.data

/**
 * Single entry point over the DAOs (Section 13.6), used by the bridge today and by the capture
 * loop / recall engine in later milestones. All methods are `suspend`; callers choose the
 * dispatcher.
 */
class MemoryRepository(
    private val personDao: PersonDao,
    private val sessionDao: SessionDao,
) {

    // --- Reads (timeline / debug) ---
    suspend fun listSessions(limit: Long, offset: Long): List<SessionWithPersonName> =
        sessionDao.getPaged(limit, offset)

    suspend fun listPeople(): List<PersonProfileEntity> = personDao.getAll()

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

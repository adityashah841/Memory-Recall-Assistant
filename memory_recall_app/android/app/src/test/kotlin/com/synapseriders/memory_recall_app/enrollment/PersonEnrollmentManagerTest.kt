package com.synapseriders.memory_recall_app.enrollment

import com.synapseriders.memory_recall_app.ai.FakeModelRunner
import com.synapseriders.memory_recall_app.data.PersonDao
import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/** CP8 (§3.6): enrollment fails cleanly (no profile inserted) when no centered frames arrive. */
class PersonEnrollmentManagerTest {

    @Test
    fun failsWhenNoCenteredFramesCaptured() = runBlocking {
        val dao = FakePersonDao()
        val manager = PersonEnrollmentManager(
            personDao = dao,
            faceEngine = FaceEmbeddingEngine(FakeModelRunner()),
            frameProvider = { null }, // camera not producing frames
            sampleIntervalMs = 1,
            maxAttempts = 3,
        )
        val result = manager.enroll("Priya", null)
        assertFalse(result.success)
        assertEquals(0, result.samples)
        assertEquals(-1L, result.personId)
        assertEquals(0, dao.insertCount) // nothing stored
    }
}

private class FakePersonDao : PersonDao {
    var insertCount = 0
    override suspend fun insert(person: PersonProfileEntity): Long { insertCount++; return insertCount.toLong() }
    override suspend fun findAll(): List<PersonProfileEntity> = emptyList()
    override suspend fun findById(personId: Long): PersonProfileEntity? = null
    override suspend fun count(): Long = insertCount.toLong()
    override suspend fun updateNameAndNotes(personId: Long, name: String?, contextNotes: String?, updatedAt: Long) {}
    override suspend fun clear() {}
}

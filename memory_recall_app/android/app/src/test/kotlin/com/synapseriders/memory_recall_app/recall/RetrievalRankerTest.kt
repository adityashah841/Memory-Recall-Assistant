package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import com.synapseriders.memory_recall_app.data.SessionDao
import com.synapseriders.memory_recall_app.data.SessionWithPersonName
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP7 (§11, §12.2): retrieval branch selection + recency top-5. */
class RetrievalRankerTest {

    private fun session(id: Long, person: Long?, label: String?, ts: Long) = MemorySessionEntity(
        sessionId = id, personId = person, locationLabel = label,
        latitude = null, longitude = null, timestamp = ts,
        frameImage = "f$id.jpg", transcript = "t$id", createdAt = ts,
    )

    @Test
    fun personOnlyFilterReturnsRecencyTop5() = runBlocking {
        val rows = (1..7L).map { session(it, person = 1L, label = "cafe", ts = it * 100) }
        val ranker = RetrievalRanker(FakeSessionDao(rows))
        val out = ranker.retrieve(personId = 1L, locationLabel = null, lat = null, lng = null)
        assertEquals(5, out.size)
        // newest first
        assertEquals(700L, out.first().timestamp)
        assertTrue(out.all { it.personId == 1L })
    }

    @Test
    fun labelVicinityNarrowsToMatchingLabel() = runBlocking {
        val rows = listOf(
            session(1, 1L, "cafe", 300),
            session(2, 1L, "gym", 200),
            session(3, 1L, "cafe", 100),
        )
        val ranker = RetrievalRanker(FakeSessionDao(rows))
        val out = ranker.retrieve(personId = 1L, locationLabel = "cafe", lat = null, lng = null)
        assertEquals(listOf(1L, 3L), out.map { it.sessionId })
    }

    @Test
    fun noPersonFallsBackToPersonlessSessions() = runBlocking {
        val rows = listOf(
            session(1, 1L, "cafe", 300),
            session(2, null, "desk", 200),
            session(3, null, "desk", 100),
        )
        val ranker = RetrievalRanker(FakeSessionDao(rows))
        val out = ranker.retrieve(personId = null, locationLabel = null, lat = null, lng = null)
        assertEquals(listOf(2L, 3L), out.map { it.sessionId })
    }
}

/** In-memory [SessionDao] with naive query semantics for ranker tests. */
private class FakeSessionDao(private val rows: List<MemorySessionEntity>) : SessionDao {
    private fun byRecency(l: List<MemorySessionEntity>) = l.sortedByDescending { it.timestamp }
    private fun inBox(s: MemorySessionEntity, label: String?, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) =
        (label != null && s.locationLabel == label) ||
            (s.latitude != null && s.longitude != null &&
                s.latitude in minLat..maxLat && s.longitude in minLng..maxLng)

    override suspend fun insert(session: MemorySessionEntity): Long = 0L
    override suspend fun listPaged(limit: Long, offset: Long): List<SessionWithPersonName> = emptyList()
    override suspend fun findByPersonId(personId: Long) = byRecency(rows.filter { it.personId == personId })
    override suspend fun vicinity(personId: Long, locationLabel: String?, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) =
        byRecency(rows.filter { it.personId == personId && inBox(it, locationLabel, minLat, maxLat, minLng, maxLng) })
    override suspend fun findRecent(limit: Long) = byRecency(rows).take(limit.toInt())
    override suspend fun findNoPerson() = byRecency(rows.filter { it.personId == null })
    override suspend fun vicinityNoPerson(locationLabel: String?, minLat: Double, maxLat: Double, minLng: Double, maxLng: Double) =
        byRecency(rows.filter { it.personId == null && inBox(it, locationLabel, minLat, maxLat, minLng, maxLng) })
    override suspend fun count(): Long = rows.size.toLong()
    override suspend fun clear() {}
}

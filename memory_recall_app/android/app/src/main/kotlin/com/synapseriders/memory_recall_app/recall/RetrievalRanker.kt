package com.synapseriders.memory_recall_app.recall

import com.synapseriders.memory_recall_app.data.MemorySessionEntity
import com.synapseriders.memory_recall_app.data.SessionDao
import com.synapseriders.memory_recall_app.identity.IdentityConfig
import kotlin.math.cos

/**
 * SQL retrieval + recency ranking for recall (IMPLEMENTATION_PLAN.md §11, §12.2).
 *
 * Narrows `session_table` by `person_id` (or `IS NULL` for Case C) **AND** vicinity (same
 * `location_label` OR within [IdentityConfig.VICINITY_RADIUS_M] of the current lat/lng), sorts by
 * recency, and returns the top [TOP_K]. No vector search — SQL only.
 */
class RetrievalRanker(private val sessionDao: SessionDao) {

    /**
     * @param personId matched person (Case A), or null for Case C (no person near center).
     * @param locationLabel / [lat] / [lng] current location; nulls relax the vicinity filter.
     */
    suspend fun retrieve(
        personId: Long?,
        locationLabel: String?,
        lat: Double?,
        lng: Double?,
    ): List<MemorySessionEntity> {
        val hasGeo = lat != null && lng != null
        val box = if (lat != null && lng != null) boundingBox(lat, lng, IdentityConfig.VICINITY_RADIUS_M) else IMPOSSIBLE_BOX
        val hasLocation = hasGeo || locationLabel != null

        val rows = when {
            personId != null && hasLocation ->
                sessionDao.vicinity(personId, locationLabel, box.minLat, box.maxLat, box.minLng, box.maxLng)
            personId != null ->
                sessionDao.findByPersonId(personId) // missing location ⇒ person-only (§12.2)
            hasLocation ->
                sessionDao.vicinityNoPerson(locationLabel, box.minLat, box.maxLat, box.minLng, box.maxLng)
            else ->
                sessionDao.findNoPerson()
        }
        // Queries already sort timestamp DESC; rank = recency, take top K.
        return rows.take(TOP_K)
    }

    private fun boundingBox(lat: Double, lng: Double, radiusM: Double): Box {
        val dLat = radiusM / METERS_PER_DEG_LAT
        val dLng = radiusM / (METERS_PER_DEG_LAT * cos(Math.toRadians(lat)).coerceAtLeast(1e-6))
        return Box(lat - dLat, lat + dLat, lng - dLng, lng + dLng)
    }

    private data class Box(val minLat: Double, val maxLat: Double, val minLng: Double, val maxLng: Double)

    companion object {
        const val TOP_K = 5
        private const val METERS_PER_DEG_LAT = 111_320.0
        // min > max ⇒ every BETWEEN is false, so only the location_label clause can match.
        private val IMPOSSIBLE_BOX = Box(1.0, 0.0, 1.0, 0.0)
    }
}

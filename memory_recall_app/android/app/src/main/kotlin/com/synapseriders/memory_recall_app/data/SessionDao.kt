package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query

/**
 * A session row joined with its person's name, for timeline/debug display without
 * leaking embeddings into the join result.
 */
data class SessionWithPersonName(
    @Embedded val session: MemorySessionEntity,
    @ColumnInfo(name = "person_name") val personName: String?,
)

@Dao
interface SessionDao {

    @Insert
    suspend fun insert(session: MemorySessionEntity): Long

    /** Paging for the timeline — newest first (Section 13.6). */
    @Query(
        """
        SELECT s.*, p.name AS person_name
        FROM session_table s
        LEFT JOIN person_profile_table p ON s.person_id = p.person_id
        ORDER BY s.timestamp DESC
        LIMIT :limit OFFSET :offset
        """,
    )
    suspend fun getPaged(limit: Long, offset: Long): List<SessionWithPersonName>

    /** Person filter — candidate narrowing for Case A (Section 10.2). */
    @Query("SELECT * FROM session_table WHERE person_id = :personId ORDER BY timestamp DESC")
    suspend fun getByPerson(personId: Long): List<MemorySessionEntity>

    /** No-person filter — Case C. */
    @Query("SELECT * FROM session_table WHERE person_id IS NULL ORDER BY timestamp DESC")
    suspend fun getWithoutPerson(): List<MemorySessionEntity>

    /**
     * Vicinity filter for a known person (Section 10.2): same `location_label` OR within the
     * lat/lng bounding box derived from the vicinity radius. The repository computes the box.
     */
    @Query(
        """
        SELECT * FROM session_table
        WHERE person_id = :personId
          AND (
            (:locationLabel IS NOT NULL AND location_label = :locationLabel)
            OR (latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng)
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun getByPersonInVicinity(
        personId: Long,
        locationLabel: String?,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): List<MemorySessionEntity>

    /** Vicinity filter for the no-person case (Section 10.2 / 9.C). */
    @Query(
        """
        SELECT * FROM session_table
        WHERE person_id IS NULL
          AND (
            (:locationLabel IS NOT NULL AND location_label = :locationLabel)
            OR (latitude BETWEEN :minLat AND :maxLat AND longitude BETWEEN :minLng AND :maxLng)
          )
        ORDER BY timestamp DESC
        """,
    )
    suspend fun getWithoutPersonInVicinity(
        locationLabel: String?,
        minLat: Double,
        maxLat: Double,
        minLng: Double,
        maxLng: Double,
    ): List<MemorySessionEntity>

    @Query("SELECT COUNT(*) FROM session_table")
    suspend fun count(): Long

    @Query("DELETE FROM session_table")
    suspend fun clear()
}

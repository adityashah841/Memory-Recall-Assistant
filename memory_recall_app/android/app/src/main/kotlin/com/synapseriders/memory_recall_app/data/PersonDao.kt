package com.synapseriders.memory_recall_app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query

@Dao
interface PersonDao {

    @Insert
    suspend fun insert(person: PersonProfileEntity): Long

    /** Full table fetch for the landmark scan during identification (Section 7). */
    @Query("SELECT * FROM person_profile_table ORDER BY person_id")
    suspend fun findAll(): List<PersonProfileEntity>

    @Query("SELECT * FROM person_profile_table WHERE person_id = :personId")
    suspend fun findById(personId: Long): PersonProfileEntity?

    @Query("SELECT COUNT(*) FROM person_profile_table")
    suspend fun count(): Long

    /** Enrollment (Case B): set/update name + context on an existing profile. */
    @Query(
        "UPDATE person_profile_table SET name = :name, context_notes = :contextNotes, " +
            "updated_at = :updatedAt WHERE person_id = :personId",
    )
    suspend fun updateNameAndNotes(
        personId: Long,
        name: String?,
        contextNotes: String?,
        updatedAt: Long,
    )

    @Query("DELETE FROM person_profile_table")
    suspend fun clear()
}

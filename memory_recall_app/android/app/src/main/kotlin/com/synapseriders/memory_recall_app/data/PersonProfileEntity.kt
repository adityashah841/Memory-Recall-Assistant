package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `person_profile_table` — the canonical person index (Section 5.1, v2 landmark schema).
 *
 * Identity is matched via `landmarkVector` (face-mesh geometry), an L2-normalized float32[936]
 * blob (468 landmarks x (x, y)). The v2 schema stores **no face image and no learned embedding**
 * (Checkpoint 2.5): `reference_face_image`, `face_embedding`, and `voice_id` were removed.
 */
@Entity(tableName = "person_profile_table")
data class PersonProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "person_id") val personId: Long = 0L,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "landmark_vector", typeAffinity = ColumnInfo.BLOB) val landmarkVector: ByteArray,
    @ColumnInfo(name = "context_notes") val contextNotes: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    // The ByteArray field needs structural equals/hashCode for a correct data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonProfileEntity) return false
        return personId == other.personId &&
            name == other.name &&
            landmarkVector.contentEquals(other.landmarkVector) &&
            contextNotes == other.contextNotes &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + landmarkVector.contentHashCode()
        result = 31 * result + (contextNotes?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `person_profile_table` — the canonical person index (Section 5.1, v4 CavaFace embedding schema).
 *
 * Identity is matched via `faceEmbedding`, an L2-normalized `float32[512]` CavaFace face embedding,
 * compared against every profile in a linear scan (Section 7); because vectors are normalized,
 * cosine similarity reduces to a dot product.
 */
@Entity(tableName = "person_profile_table")
data class PersonProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "person_id") val personId: Long = 0L,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "face_embedding", typeAffinity = ColumnInfo.BLOB) val faceEmbedding: ByteArray,
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
            faceEmbedding.contentEquals(other.faceEmbedding) &&
            contextNotes == other.contextNotes &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + faceEmbedding.contentHashCode()
        result = 31 * result + (contextNotes?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

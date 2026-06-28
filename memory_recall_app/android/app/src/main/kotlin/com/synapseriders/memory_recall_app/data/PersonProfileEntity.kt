package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `person_profile_table` — the canonical face-identity index (Section 5.1).
 *
 * `faceEmbedding` is the ArcFace-space 512-d vector used **only** for identity matching,
 * stored as an L2-normalized float32 little-endian blob.
 */
@Entity(tableName = "person_profile_table")
data class PersonProfileEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "person_id") val personId: Long = 0L,
    @ColumnInfo(name = "name") val name: String? = null,
    @ColumnInfo(name = "reference_face_image") val referenceFaceImage: String? = null,
    @ColumnInfo(name = "face_embedding", typeAffinity = ColumnInfo.BLOB) val faceEmbedding: ByteArray,
    @ColumnInfo(name = "context_notes") val contextNotes: String? = null,
    @ColumnInfo(name = "voice_id") val voiceId: String? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
    @ColumnInfo(name = "updated_at") val updatedAt: Long,
) {
    // ByteArray fields need structural equals/hashCode for a correct data class.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PersonProfileEntity) return false
        return personId == other.personId &&
            name == other.name &&
            referenceFaceImage == other.referenceFaceImage &&
            faceEmbedding.contentEquals(other.faceEmbedding) &&
            contextNotes == other.contextNotes &&
            voiceId == other.voiceId &&
            createdAt == other.createdAt &&
            updatedAt == other.updatedAt
    }

    override fun hashCode(): Int {
        var result = personId.hashCode()
        result = 31 * result + (name?.hashCode() ?: 0)
        result = 31 * result + (referenceFaceImage?.hashCode() ?: 0)
        result = 31 * result + faceEmbedding.contentHashCode()
        result = 31 * result + (contextNotes?.hashCode() ?: 0)
        result = 31 * result + (voiceId?.hashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        result = 31 * result + updatedAt.hashCode()
        return result
    }
}

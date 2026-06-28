package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `session_table` — one row per persisted meaningful event (Section 5.2).
 *
 * `personId` is a nullable FK to [PersonProfileEntity] with `ON DELETE SET NULL` (Section 5.3):
 * NULL when no person was in the frame (Case C). `frameEmbedding` (CLIP image space) and
 * `voiceEmbedding` (CLIP text space) share the MobileCLIP space; both are L2-normalized blobs.
 */
@Entity(
    tableName = "session_table",
    foreignKeys = [
        ForeignKey(
            entity = PersonProfileEntity::class,
            parentColumns = ["person_id"],
            childColumns = ["person_id"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index("person_id"),
        Index("timestamp"),
        Index(value = ["latitude", "longitude"]),
    ],
)
data class MemorySessionEntity(
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "session_id") val sessionId: Long = 0L,
    @ColumnInfo(name = "person_id") val personId: Long? = null,
    @ColumnInfo(name = "location_label") val locationLabel: String? = null,
    @ColumnInfo(name = "latitude") val latitude: Double? = null,
    @ColumnInfo(name = "longitude") val longitude: Double? = null,
    @ColumnInfo(name = "timestamp") val timestamp: Long,
    @ColumnInfo(name = "frame_image") val frameImage: String,
    @ColumnInfo(name = "voice_clip") val voiceClip: String? = null,
    @ColumnInfo(name = "transcript") val transcript: String? = null,
    @ColumnInfo(name = "frame_embedding", typeAffinity = ColumnInfo.BLOB) val frameEmbedding: ByteArray,
    @ColumnInfo(name = "voice_embedding", typeAffinity = ColumnInfo.BLOB) val voiceEmbedding: ByteArray? = null,
    @ColumnInfo(name = "created_at") val createdAt: Long,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MemorySessionEntity) return false
        return sessionId == other.sessionId &&
            personId == other.personId &&
            locationLabel == other.locationLabel &&
            latitude == other.latitude &&
            longitude == other.longitude &&
            timestamp == other.timestamp &&
            frameImage == other.frameImage &&
            voiceClip == other.voiceClip &&
            transcript == other.transcript &&
            frameEmbedding.contentEquals(other.frameEmbedding) &&
            (voiceEmbedding?.contentEquals(other.voiceEmbedding ?: ByteArray(0)) ?: (other.voiceEmbedding == null)) &&
            createdAt == other.createdAt
    }

    override fun hashCode(): Int {
        var result = sessionId.hashCode()
        result = 31 * result + (personId?.hashCode() ?: 0)
        result = 31 * result + (locationLabel?.hashCode() ?: 0)
        result = 31 * result + (latitude?.hashCode() ?: 0)
        result = 31 * result + (longitude?.hashCode() ?: 0)
        result = 31 * result + timestamp.hashCode()
        result = 31 * result + frameImage.hashCode()
        result = 31 * result + (voiceClip?.hashCode() ?: 0)
        result = 31 * result + (transcript?.hashCode() ?: 0)
        result = 31 * result + frameEmbedding.contentHashCode()
        result = 31 * result + (voiceEmbedding?.contentHashCode() ?: 0)
        result = 31 * result + createdAt.hashCode()
        return result
    }
}

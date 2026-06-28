package com.synapseriders.memory_recall_app.data

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `session_table` — one row per persisted meaningful event (Section 5.2, v2 landmark schema).
 *
 * `personId` is a nullable FK to [PersonProfileEntity] with `ON DELETE SET NULL` (Section 5.3):
 * NULL when no person was in the frame (Case C). The v2 schema stores **no embeddings**
 * (Checkpoint 2.5): `frame_embedding` and `voice_embedding` were removed; the FK and the three
 * indices (person_id, timestamp, lat/lng) are retained.
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
    @ColumnInfo(name = "created_at") val createdAt: Long,
)

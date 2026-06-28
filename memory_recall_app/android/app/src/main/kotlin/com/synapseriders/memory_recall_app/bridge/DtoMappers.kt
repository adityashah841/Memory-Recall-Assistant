package com.synapseriders.memory_recall_app.bridge

import com.synapseriders.memory_recall_app.data.PersonProfileEntity
import com.synapseriders.memory_recall_app.data.SessionWithPersonName

/**
 * Room entity -> Pigeon DTO conversions (Section 13.2). Embeddings are deliberately never
 * mapped — the Flutter side only ever sees paths, text, and ids.
 */

fun SessionWithPersonName.toDto(): MemorySessionDto {
    val s = session
    return MemorySessionDto(
        sessionId = s.sessionId,
        personId = s.personId,
        personName = personName,
        locationLabel = s.locationLabel,
        timestampMs = s.timestamp,
        frameImagePath = s.frameImage,
        transcript = s.transcript,
    )
}

fun PersonProfileEntity.toDto(): PersonProfileDto = PersonProfileDto(
    personId = personId,
    name = name,
    referenceFaceImagePath = referenceFaceImage,
    contextNotes = contextNotes,
)

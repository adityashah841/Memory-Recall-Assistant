package com.synapseriders.memory_recall_app.data

import java.util.Random

/**
 * Populates a coherent demo knowledge graph (Section 15): 2 people with face embeddings and 5
 * sessions spread across a couple of locations and timestamps — enough to exercise all three
 * recall cases (known person, and a no-person Case C row). Embeddings are deterministic unit
 * vectors so demo runs are reproducible.
 */
class DemoSeeder(
    private val personDao: PersonDao,
    private val sessionDao: SessionDao,
) {

    suspend fun seed() {
        val now = System.currentTimeMillis()
        val hour = 3_600_000L

        val priyaId = personDao.insert(
            person(
                name = "Priya",
                notes = "Met at the office cafe; talked about her Kyoto trip.",
                seed = 1,
                now = now,
            ),
        )
        val marcoId = personDao.insert(
            person(
                name = "Marco",
                notes = "Climbing gym regular.",
                seed = 2,
                now = now,
            ),
        )

        // 5 sessions: Priya x2 + Marco x2 (Case A history) + a no-person row (Case C).
        sessionDao.insert(
            session(
                personId = priyaId,
                label = "office cafe",
                lat = 37.4219,
                lng = -122.0840,
                ts = now - 2 * 24 * hour,
                frame = "frames/priya_cafe_1.jpg",
                transcript = "Priya: Kyoto was incredible, the temples in Arashiyama were the highlight.",
                seed = 11,
            ),
        )
        sessionDao.insert(
            session(
                personId = priyaId,
                label = "office cafe",
                lat = 37.4220,
                lng = -122.0841,
                ts = now - 5 * hour,
                frame = "frames/priya_cafe_2.jpg",
                transcript = "Priya: I'm planning a winter trip to Hokkaido next.",
                seed = 12,
            ),
        )
        sessionDao.insert(
            session(
                personId = marcoId,
                label = "climbing gym",
                lat = 37.7840,
                lng = -122.4090,
                ts = now - 24 * hour,
                frame = "frames/marco_gym_1.jpg",
                transcript = "Marco: I finally sent that V6 on the overhang wall.",
                seed = 13,
            ),
        )
        sessionDao.insert(
            session(
                personId = marcoId,
                label = "climbing gym",
                lat = 37.7841,
                lng = -122.4091,
                ts = now - 3 * hour,
                frame = "frames/marco_gym_2.jpg",
                transcript = "Marco: Let's plan an outdoor trip to Bishop next month.",
                seed = 14,
            ),
        )
        sessionDao.insert(
            session(
                personId = null, // Case C: no person in frame
                label = "home desk",
                lat = 37.4000,
                lng = -122.1000,
                ts = now - 6 * hour,
                frame = "frames/desk_whiteboard.jpg",
                transcript = "Reminder note on the whiteboard about the Friday demo.",
                seed = 15,
            ),
        )
    }

    private fun person(name: String, notes: String, seed: Int, now: Long) = PersonProfileEntity(
        name = name,
        referenceFaceImage = "faces/${name.lowercase()}.jpg",
        faceEmbedding = EmbeddingCodec.toBlob(unitVector(FACE_DIM, seed)),
        contextNotes = notes,
        voiceId = null,
        createdAt = now,
        updatedAt = now,
    )

    private fun session(
        personId: Long?,
        label: String,
        lat: Double,
        lng: Double,
        ts: Long,
        frame: String,
        transcript: String,
        seed: Int,
    ) = MemorySessionEntity(
        personId = personId,
        locationLabel = label,
        latitude = lat,
        longitude = lng,
        timestamp = ts,
        frameImage = frame,
        voiceClip = "clips/clip_$seed.m4a",
        transcript = transcript,
        frameEmbedding = EmbeddingCodec.toBlob(unitVector(CLIP_DIM, seed)),
        voiceEmbedding = EmbeddingCodec.toBlob(unitVector(CLIP_DIM, seed + 100)),
        createdAt = ts,
    )

    private fun unitVector(dim: Int, seed: Int): FloatArray {
        val rnd = Random(seed.toLong())
        val v = FloatArray(dim) { rnd.nextFloat() * 2f - 1f }
        return EmbeddingCodec.l2Normalize(v)
    }

    private companion object {
        const val FACE_DIM = 512 // MobileFaceNet / ArcFace
        const val CLIP_DIM = 512 // MobileCLIP-S2
    }
}

package com.synapseriders.memory_recall_app.identity

/**
 * Single source of truth for identity/center-crop tuning (IMPLEMENTATION_PLAN.md §3.3, §24).
 *
 * Kept as one object so thresholds are tunable in exactly one place (DoD: "`MAX_CENTER_OFFSET_PX`
 * tunable in one place").
 */
object IdentityConfig {
    /** ArcFace cosine threshold: score ≥ this ⇒ known person (Case A). */
    const val TAU_KNOWN = 0.60f

    /** Max pixels a face bbox center may sit from the frame center to be the "target" (~25% of 640w). */
    const val MAX_CENTER_OFFSET_PX = 120f

    /** Ignore faces whose min(width,height) is below this (distant bystanders). */
    const val MIN_FACE_SIZE_PX = 80

    /** Frames averaged per enrollment (CP8). */
    const val ENROLLMENT_SAMPLES = 5

    /** CavaFace embedding dimensionality. */
    const val EMBEDDING_DIM = 512

    /** Location vicinity radius for recall retrieval (§12.2). */
    const val VICINITY_RADIUS_M = 75.0
}

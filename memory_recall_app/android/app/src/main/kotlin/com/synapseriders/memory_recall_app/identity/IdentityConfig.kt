package com.synapseriders.memory_recall_app.identity

/**
 * Single source of truth for identity/center-crop tuning (IMPLEMENTATION_PLAN.md §3.3, §24).
 *
 * Kept as one object so thresholds are tunable in exactly one place (DoD: "center offset tunable in
 * one place"). Geometry limits are fractions of the frame's shorter side so they hold at any camera
 * resolution (0.25 × 480 px = the original 120 px).
 */
object IdentityConfig {
    /** ArcFace/CavaFace cosine threshold: score ≥ this ⇒ known person (Case A). Each backend may
     *  override via `OnDeviceModelRunner.matchThreshold` (e.g. FaceNet uses a lower value). */
    const val TAU_KNOWN = 0.60f

    /** Max distance of a face bbox center from the frame center, as a fraction of the frame's shorter
     *  side, for it to be the "target" (0.25 × 480 px = 120 px). */
    const val MAX_CENTER_OFFSET_FRAC = 0.25f

    /** Ignore faces whose min(width,height) is below this fraction of the frame's shorter side
     *  (distant bystanders). */
    const val MIN_FACE_SIZE_FRAC = 0.12f

    /** Frames averaged per enrollment (CP8). */
    const val ENROLLMENT_SAMPLES = 5

    /** CavaFace embedding dimensionality. */
    const val EMBEDDING_DIM = 512

    /** Location vicinity radius for recall retrieval (§12.2). */
    const val VICINITY_RADIUS_M = 75.0
}

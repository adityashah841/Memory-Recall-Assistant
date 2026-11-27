package com.synapseriders.memory_recall_app.capture

import android.graphics.Bitmap

/** A location reading for capture/recall (label + fused coordinates). */
data class LocationFix(val label: String?, val latitude: Double?, val longitude: Double?)

/**
 * Process-wide latest camera frame + location (IMPLEMENTATION_PLAN.md §9.2).
 *
 * The foreground service's providers write here; both the [CaptureLoop] (write path) and the
 * `RecallEngine` (read path, via the Pigeon channel) read from here. This decouples the camera owner
 * (the service) from the recall consumer without passing handles across components.
 */
object CaptureState {
    @Volatile var latestFrame: Bitmap? = null
    @Volatile var latestLocation: LocationFix? = null

    /** `SystemClock.elapsedRealtime()` of the last live-preview request; while recent, the camera
     *  delivers frames faster so the on-screen preview is smooth. */
    @Volatile var previewRequestedAt: Long = 0L

    fun clear() {
        latestFrame = null
        latestLocation = null
    }
}

package com.synapseriders.memory_recall_app.data

/** One captured tick's metadata to persist (IMPLEMENTATION_PLAN.md §10). */
data class CaptureRecord(
    val timestamp: Long,
    val locationLabel: String?,
    val latitude: Double?,
    val longitude: Double?,
    val frameThumbPath: String,
    val transcript: String?,
)

/**
 * Write-path insertion contract for the capture loop (§10). Implemented by [MemoryRepository];
 * abstracted so `CaptureLoop` is unit-testable without Room.
 */
interface CaptureSink {
    /** Case A: known person → one session row, no profile change (§10.A). */
    suspend fun recordKnown(personId: Long, rec: CaptureRecord)

    /** Case B: unknown person → new profile (name null, given embedding) + session (§10.B). */
    suspend fun recordUnknown(faceEmbedding: ByteArray, rec: CaptureRecord): Long

    /** Case C: no person near center → session with person_id NULL (§10.C). */
    suspend fun recordNoPerson(rec: CaptureRecord)
}

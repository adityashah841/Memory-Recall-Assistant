package com.synapseriders.memory_recall_app.capture

import android.annotation.SuppressLint
import android.content.Context
import android.os.Looper
import android.util.Log
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority

/**
 * Fused location + manual room label (IMPLEMENTATION_PLAN.md §20.3). Writes the latest fix into
 * [CaptureState]. The caller must hold location permission; failures degrade to null (no crash).
 */
class LocationProvider(context: Context) {

    private val client = LocationServices.getFusedLocationProviderClient(context)
    @Volatile private var manualLabel: String? = null

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let {
                CaptureState.latestLocation = LocationFix(manualLabel, it.latitude, it.longitude)
            }
        }
    }

    @SuppressLint("MissingPermission")
    fun start() {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, INTERVAL_MS).build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (t: Throwable) {
            Log.w(TAG, "location updates unavailable", t)
        }
    }

    fun stop() {
        try { client.removeLocationUpdates(callback) } catch (_: Throwable) {}
    }

    /** Set a manual room/place label applied to subsequent fixes. */
    fun setLabel(label: String?) {
        manualLabel = label
        val cur = CaptureState.latestLocation
        CaptureState.latestLocation =
            if (cur != null) cur.copy(label = label) else LocationFix(label, null, null)
    }

    private companion object {
        const val TAG = "LocationProvider"
        const val INTERVAL_MS = 10_000L
    }
}

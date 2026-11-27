package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.util.Log

/**
 * Process-wide singleton for the on-device model runner (IMPLEMENTATION_PLAN.md §18, §20.2).
 *
 * Both the foreground service (warm-up + capture) and the Pigeon channel (recall) share one runner,
 * so models are loaded once and native handles aren't duplicated.
 *
 * Backend selection (the "hybrid" plan): use the original Qualcomm stack ([CompositeModelRunner]:
 * ExecuTorch + QNN + Genie) only when its native bridge is built **and** the CavaFace binary has been
 * pushed to the device; otherwise use [GoogleModelRunner] (ML Kit + FaceNet on LiteRT), which works on
 * any recent Android phone. Callers depend only on [OnDeviceModelRunner], so nothing else changes.
 */
object ModelRuntime {
    private const val TAG = "ModelRuntime"

    @Volatile private var runner: OnDeviceModelRunner? = null

    fun get(context: Context): OnDeviceModelRunner =
        runner ?: synchronized(this) {
            runner ?: create(context.applicationContext).also {
                runner = it
                Log.i(TAG, "using backend: ${it.backendName}")
            }
        }

    private fun create(context: Context): OnDeviceModelRunner {
        val qualcommReady = NativeInference.loaded && ModelAssets(context).hasCavaFace()
        return if (qualcommReady) CompositeModelRunner(context) else GoogleModelRunner(context)
    }
}

package com.synapseriders.memory_recall_app.capture

import android.content.Context
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraX frame source (IMPLEMENTATION_PLAN.md §20.3). Keeps the latest frame in [CaptureState] as a
 * `Bitmap`; both the capture loop and recall read it from there. Bound to the foreground service's
 * lifecycle. Degrades silently if the camera can't bind (no crash).
 */
class CameraFrameProvider(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()

    fun start(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        CaptureState.latestFrame = proxy.toBitmap()
                    } catch (t: Throwable) {
                        Log.w(TAG, "frame convert failed", t)
                    } finally {
                        proxy.close()
                    }
                }
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, analysis)
                Log.i(TAG, "camera bound")
            } catch (t: Throwable) {
                Log.w(TAG, "camera start failed — frames unavailable", t)
            }
        }, ContextCompat.getMainExecutor(context))
    }

    fun stop() {
        try { cameraProvider?.unbindAll() } catch (_: Throwable) {}
        cameraProvider = null
        CaptureState.latestFrame = null
    }

    private companion object {
        const val TAG = "CameraFrameProvider"
    }
}

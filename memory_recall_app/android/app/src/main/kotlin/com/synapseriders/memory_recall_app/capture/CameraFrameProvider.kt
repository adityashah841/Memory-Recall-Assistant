package com.synapseriders.memory_recall_app.capture

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.SystemClock
import android.util.Log
import android.util.Size
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.util.concurrent.Executors

/**
 * CameraX frame source (IMPLEMENTATION_PLAN.md §20.3). Keeps the latest **upright** frame in
 * [CaptureState] as a `Bitmap`; both the capture loop and recall read it from there. Bound to the
 * foreground service's lifecycle. Degrades silently if the camera can't bind (no crash).
 *
 * Frames are rotated by the sensor's `rotationDegrees` so faces are upright for detection/alignment,
 * requested at ~720p so faces at conversation distance are large enough to identify, and converted at
 * most every [MIN_FRAME_INTERVAL_MS] (consumers read every few seconds; converting all 30 fps would
 * just burn battery).
 */
class CameraFrameProvider(private val context: Context) {

    private var cameraProvider: ProcessCameraProvider? = null
    private val analysisExecutor = Executors.newSingleThreadExecutor()
    @Volatile private var lastFrameAt = 0L

    fun start(lifecycleOwner: LifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                val provider = future.get()
                cameraProvider = provider
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(
                        ResolutionSelector.Builder()
                            .setResolutionStrategy(
                                ResolutionStrategy(
                                    TARGET_SIZE,
                                    ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                                ),
                            )
                            .build(),
                    )
                    .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(analysisExecutor) { proxy ->
                    try {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastFrameAt >= MIN_FRAME_INTERVAL_MS) {
                            lastFrameAt = now
                            CaptureState.latestFrame = proxy.toUprightBitmap()
                        }
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

    private fun ImageProxy.toUprightBitmap(): Bitmap {
        val raw = toBitmap()
        val rotation = imageInfo.rotationDegrees
        if (rotation == 0) return raw
        val matrix = Matrix().apply { postRotate(rotation.toFloat()) }
        val upright = Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, true)
        if (upright !== raw) raw.recycle()
        return upright
    }

    private companion object {
        const val TAG = "CameraFrameProvider"
        val TARGET_SIZE = Size(1280, 720)
        const val MIN_FRAME_INTERVAL_MS = 250L
    }
}

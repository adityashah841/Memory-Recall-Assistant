package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * The production [OnDeviceModelRunner] — fans the six contract methods out to the three runtime
 * backends (IMPLEMENTATION_PLAN.md §8.2): ExecuTorch (detect/landmark), QNN (embed/transcribe),
 * Genie (generate). `FaceEmbeddingEngine` (§4) and the capture/recall layers depend only on the
 * [OnDeviceModelRunner] interface, so this facade is the single composition point.
 *
 * > §24 deviation: the plan lists the three backend runners + the interface but no explicit facade.
 * > `FaceEmbeddingEngine(runner: OnDeviceModelRunner)` (§4) requires one object exposing all methods,
 * > so this composite is introduced to satisfy the contract without merging the backends.
 */
class CompositeModelRunner(context: Context) : OnDeviceModelRunner {

    private val assets = ModelAssets(context.applicationContext)
    private val executorch = ExecuTorchModelRunner(assets)
    private val qnn = QnnModelRunner(context.applicationContext, assets)
    private val genie = GenieLlmRunner(assets)

    override fun detectFaces(frame: Bitmap): List<FaceBox> = executorch.detectFaces(frame)

    override fun extractLandmarks(faceCrop: Bitmap): FloatArray = executorch.extractLandmarks(faceCrop)

    override fun embedFace(alignedFace: Bitmap): FloatArray = qnn.embedFace(alignedFace)

    override fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String =
        qnn.transcribe(pcmAudio, sampleRate)

    override fun generate(prompt: String, maxTokens: Int): String = genie.generate(prompt, maxTokens)

    /** Warm every available backend once (call off the UI thread, §18). Never throws. */
    override fun warmUp() {
        assets.logReport()
        executorch.warmUp()
        qnn.warmUp()
        genie.warmUp()
        Log.i(TAG, "warm-up complete")
    }

    fun close() {
        executorch.close(); qnn.close(); genie.close()
    }

    private companion object {
        const val TAG = "CompositeRunner"
    }
}

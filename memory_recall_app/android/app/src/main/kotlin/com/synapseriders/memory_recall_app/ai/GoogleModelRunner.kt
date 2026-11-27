package com.synapseriders.memory_recall_app.ai

import android.content.Context
import android.graphics.Bitmap
import android.util.Log

/**
 * [OnDeviceModelRunner] built on Google's on-device stack, for phones without the Snapdragon/QAIRT
 * runtime (e.g. Pixel 10 / Tensor G5). Same contract as [CompositeModelRunner], different engines:
 *
 * | Method            | Qualcomm path (CompositeModelRunner)   | This runner                         |
 * |-------------------|----------------------------------------|-------------------------------------|
 * | detectFaces       | MediaPipe `.pte` (ExecuTorch)          | ML Kit face detection (+landmarks)  |
 * | extractLandmarks  | MediaPipe landmark `.pte`              | unused — ML Kit returns them        |
 * | embedFace         | CavaFace `.bin` (QNN), 112×112 ArcFace | FaceNet-512 `.tflite` (LiteRT), 160 |
 * | transcribe        | Whisper `.bin` (QNN)                   | "" — capture uses SpeechRecognizer  |
 * | generate          | Llama 3.2 1B (Genie)                   | "" until Gemini Nano is wired       |
 *
 * Everything runs fully on-device. Never throws; failures degrade to empty results like the other
 * runners.
 */
class GoogleModelRunner(context: Context) : OnDeviceModelRunner {

    private val faces = MlKitFaceDetector()
    private val faceNet = FaceNetEmbedder(context.applicationContext)

    override val backendName: String = "Google on-device (ML Kit + FaceNet/LiteRT)"

    override val faceAlignment: FaceAlignment = FaceAlignment.FACENET_160

    /**
     * FaceNet-512 cosine threshold. Standard FaceNet verification uses L2 distance ≈ 1.1 on
     * normalized embeddings, i.e. cosine ≈ 1 − 1.1²/2 ≈ 0.40. Tune on-device from the
     * `FaceEmbeddingEngine` logcat scores if needed.
     */
    override val matchThreshold: Float = 0.40f

    override fun detectFaces(frame: Bitmap): List<FaceBox> = faces.detect(frame)

    /** Not needed: [detectFaces] already attaches the 5 key points to each [FaceBox]. */
    override fun extractLandmarks(faceCrop: Bitmap): FloatArray = FloatArray(0)

    override fun embedFace(alignedFace: Bitmap): FloatArray = faceNet.embed(alignedFace)

    override fun transcribe(pcmAudio: ShortArray, sampleRate: Int): String = ""

    override fun generate(prompt: String, maxTokens: Int): String = ""

    override fun warmUp() {
        faces.warmUp()
        faceNet.warmUp()
        Log.i(TAG, "warm-up complete ($backendName)")
    }

    fun close() {
        faces.close()
        faceNet.close()
    }

    private companion object {
        const val TAG = "GoogleModelRunner"
    }
}

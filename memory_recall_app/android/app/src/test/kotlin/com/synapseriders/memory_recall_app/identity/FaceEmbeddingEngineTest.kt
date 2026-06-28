package com.synapseriders.memory_recall_app.identity

import com.synapseriders.memory_recall_app.ai.FaceBox
import com.synapseriders.memory_recall_app.ai.FakeModelRunner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import kotlin.math.sqrt

/**
 * CP4 DoD as JVM tests (IMPLEMENTATION_PLAN.md §22 CP4): center face chosen, edge/small faces
 * ignored, cosine/averaging correct. Pure geometry + math — no Android Bitmap needed.
 */
class FaceEmbeddingEngineTest {

    private val engine = FaceEmbeddingEngine(FakeModelRunner())

    private val frameW = 640
    private val frameH = 480 // center = (320, 240)

    @Test
    fun selectsCenterFaceOverCloserEdgeFace() {
        val centered = FaceBox(left = 300f, top = 200f, width = 100f, height = 100f, confidence = 0.90f)
        val edge = FaceBox(left = 10f, top = 10f, width = 100f, height = 100f, confidence = 0.99f)
        // Edge has higher confidence but center face wins on distance-to-center.
        assertSame(centered, engine.selectCenterFace(listOf(edge, centered), frameW, frameH))
    }

    @Test
    fun rejectsWhenNoFaceNearCenter() {
        val edge = FaceBox(left = 0f, top = 0f, width = 100f, height = 100f, confidence = 0.99f)
        assertNull(engine.selectCenterFace(listOf(edge), frameW, frameH))
    }

    @Test
    fun ignoresFacesBelowMinSize() {
        // Centered but smaller than MIN_FACE_SIZE_PX (80) → not a valid target.
        val tiny = FaceBox(left = 310f, top = 230f, width = 20f, height = 20f, confidence = 0.99f)
        assertNull(engine.selectCenterFace(listOf(tiny), frameW, frameH))
    }

    @Test
    fun cosineOfIdenticalVectorsIsOne() {
        val v = engine.l2Normalize(floatArrayOf(1f, 2f, 3f, 4f))
        assertEquals(1f, engine.cosineSimilarity(v, v), 1e-4f)
    }

    @Test
    fun cosineOfOrthogonalVectorsIsZero() {
        assertEquals(0f, engine.cosineSimilarity(floatArrayOf(1f, 0f), floatArrayOf(0f, 1f)), 1e-4f)
    }

    @Test
    fun averagedEmbeddingIsL2Normalized() {
        val avg = engine.averageEmbeddings(listOf(floatArrayOf(1f, 0f, 0f), floatArrayOf(0f, 1f, 0f)))
        val norm = sqrt(avg.fold(0f) { acc, x -> acc + x * x })
        assertEquals(1f, norm, 1e-4f)
    }
}

package com.synapseriders.memory_recall_app.capture

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP6 (§10.4): scene-change comparison core. */
class SceneChangeDetectorTest {

    @Test
    fun firstFrameCountsAsChange() {
        val d = SceneChangeDetector()
        assertTrue(d.changedSignature(IntArray(256) { 10 }))
    }

    @Test
    fun identicalFrameIsNotAChange() {
        val d = SceneChangeDetector()
        val sig = IntArray(256) { 100 }
        d.changedSignature(sig)
        assertFalse(d.changedSignature(sig.copyOf()))
    }

    @Test
    fun largeDifferenceIsAChange() {
        val d = SceneChangeDetector(threshold = 0.10f)
        d.changedSignature(IntArray(256) { 0 })
        assertTrue(d.changedSignature(IntArray(256) { 255 }))
    }

    @Test
    fun meanAbsNormalizedDiffIsOneForBlackVsWhite() {
        val d = SceneChangeDetector()
        val diff = d.meanAbsNormalizedDiff(IntArray(256) { 0 }, IntArray(256) { 255 })
        assertEquals(1.0f, diff, 1e-4f)
    }
}

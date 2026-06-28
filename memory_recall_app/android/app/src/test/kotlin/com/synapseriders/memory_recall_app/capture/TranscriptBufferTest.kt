package com.synapseriders.memory_recall_app.capture

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

/** CP5 (§6): rolling 30 s window semantics, using a tiny capacity for clarity. */
class TranscriptBufferTest {

    // windowSeconds=1, sampleRate=4 → capacity 4 samples.
    private fun buf() = TranscriptBuffer(windowSeconds = 1, sampleRate = 4)

    @Test
    fun keepsAppendedSamplesUntilFull() {
        val b = buf()
        b.append(shortArrayOf(1, 2, 3))
        assertEquals(3, b.size())
        assertArrayEquals(shortArrayOf(1, 2, 3), b.window())
    }

    @Test
    fun rollsOldestOffWhenOverCapacity() {
        val b = buf()
        b.append(shortArrayOf(1, 2, 3))
        b.append(shortArrayOf(4, 5))
        assertEquals(4, b.size())
        assertArrayEquals(shortArrayOf(2, 3, 4, 5), b.window())
    }

    @Test
    fun chunkLargerThanWindowKeepsTail() {
        val b = buf()
        b.append(shortArrayOf(1, 2, 3, 4, 5, 6))
        assertArrayEquals(shortArrayOf(3, 4, 5, 6), b.window())
    }

    @Test
    fun transcriptCacheRoundTrips() {
        val b = buf()
        assertEquals("", b.getTranscriptWindow())
        b.setTranscript("hello world")
        assertEquals("hello world", b.getTranscriptWindow())
    }
}

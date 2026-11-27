package com.synapseriders.memory_recall_app.capture

import org.junit.Assert.assertEquals
import org.junit.Test

class TranscriptMergeTest {

    @Test
    fun stripsRepeatedPreviousPhrase() {
        // Real on-device output seen on a Pixel 10.
        assertEquals(
            "I'm starting a new job next week",
            TranscriptMerge.novelTail(
                "How was your trip to Chicago",
                "How how is your trip to Chicago I'm starting a new job next week",
            ),
        )
    }

    @Test
    fun keepsUnrelatedPhrase() {
        assertEquals(
            "Let's grab coffee tomorrow",
            TranscriptMerge.novelTail("How was your trip to Chicago", "Let's grab coffee tomorrow"),
        )
    }

    @Test
    fun pureRepeatAddsNothing() {
        assertEquals("", TranscriptMerge.novelTail("How was your trip", "how was your trip"))
    }

    @Test
    fun noPreviousKeepsEverything() {
        assertEquals("Hello there", TranscriptMerge.novelTail(null, " Hello there "))
    }
}

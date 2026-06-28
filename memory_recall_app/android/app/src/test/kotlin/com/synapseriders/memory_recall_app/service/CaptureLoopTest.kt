package com.synapseriders.memory_recall_app.service

import com.synapseriders.memory_recall_app.ai.FaceBox
import com.synapseriders.memory_recall_app.capture.LocationFix
import com.synapseriders.memory_recall_app.data.CaptureRecord
import com.synapseriders.memory_recall_app.data.CaptureSink
import com.synapseriders.memory_recall_app.identity.MatchResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** CP6 (§10): tick branches to Case A/B/C and honors the §10.4 skip conditions. */
class CaptureLoopTest {

    private class FakeSink : CaptureSink {
        val calls = mutableListOf<String>()
        var lastEmbedding: ByteArray? = null
        override suspend fun recordKnown(personId: Long, rec: CaptureRecord) { calls += "known:$personId" }
        override suspend fun recordUnknown(faceEmbedding: ByteArray, rec: CaptureRecord): Long {
            lastEmbedding = faceEmbedding; calls += "unknown"; return 9L
        }
        override suspend fun recordNoPerson(rec: CaptureRecord) { calls += "noPerson" }
    }

    private val box = FaceBox(0f, 0f, 100f, 100f, 0.9f)

    private fun loop(
        sink: CaptureSink,
        match: MatchResult?,
        sceneChanged: Boolean = true,
        transcript: String? = null,
        frame: String? = "frame",
    ) = CaptureLoop<String>(
        identify = { _, _ -> match },
        sink = sink,
        sceneChanged = { sceneChanged },
        profilesProvider = { emptyList() },
        frameProvider = { frame },
        transcriptProvider = { transcript },
        locationProvider = { LocationFix("cafe", 1.0, 2.0) },
        thumbnailSaver = { "thumb.jpg" },
        clock = { 1000L },
    )

    @Test
    fun knownFaceInsertsCaseA() = runBlocking {
        val sink = FakeSink()
        val match = MatchResult(personId = 5, name = "Priya", similarityScore = 0.8f, isKnown = true, faceBox = box)
        assertEquals(TickOutcome.KNOWN, loop(sink, match).tick())
        assertEquals(listOf("known:5"), sink.calls)
    }

    @Test
    fun unknownFaceInsertsCaseBWithEmbedding() = runBlocking {
        val sink = FakeSink()
        val match = MatchResult(-1, null, 0.2f, isKnown = false, faceBox = box, embedding = floatArrayOf(0.1f, 0.2f))
        assertEquals(TickOutcome.UNKNOWN, loop(sink, match).tick())
        assertEquals(listOf("unknown"), sink.calls)
        assertTrue((sink.lastEmbedding?.size ?: 0) > 0)
    }

    @Test
    fun noFaceWithSceneChangeInsertsCaseC() = runBlocking {
        val sink = FakeSink()
        assertEquals(TickOutcome.NO_PERSON, loop(sink, match = null, sceneChanged = true).tick())
        assertEquals(listOf("noPerson"), sink.calls)
    }

    @Test
    fun skipsWhenNoFaceNoTranscriptNoSceneChange() = runBlocking {
        val sink = FakeSink()
        assertEquals(TickOutcome.SKIPPED, loop(sink, match = null, sceneChanged = false, transcript = null).tick())
        assertTrue(sink.calls.isEmpty())
    }

    @Test
    fun transcriptAloneStillPersistsCaseC() = runBlocking {
        val sink = FakeSink()
        val outcome = loop(sink, match = null, sceneChanged = false, transcript = "hello").tick()
        assertEquals(TickOutcome.NO_PERSON, outcome)
        assertEquals(listOf("noPerson"), sink.calls)
    }

    @Test
    fun noFrameReturnsNoFrame() = runBlocking {
        val sink = FakeSink()
        assertEquals(TickOutcome.NO_FRAME, loop(sink, match = null, frame = null).tick())
        assertTrue(sink.calls.isEmpty())
    }
}

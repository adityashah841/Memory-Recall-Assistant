package com.synapseriders.memory_recall_app.capture

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log

/**
 * Microphone PCM16 @ 16 kHz → [TranscriptBuffer] (IMPLEMENTATION_PLAN.md §6, §20.3). Feeds the rolling
 * 30 s ASR window. Requires RECORD_AUDIO; degrades silently if unavailable (no crash).
 */
class AudioCaptureProvider(private val buffer: TranscriptBuffer) {

    private var record: AudioRecord? = null
    private var thread: Thread? = null
    @Volatile private var running = false

    @SuppressLint("MissingPermission")
    fun start() {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL, ENCODING)
        if (minBuf <= 0) { Log.w(TAG, "mic config unsupported"); return }
        val rec = try {
            AudioRecord(MediaRecorder.AudioSource.MIC, SAMPLE_RATE, CHANNEL, ENCODING, maxOf(minBuf, SAMPLE_RATE))
        } catch (t: Throwable) {
            Log.w(TAG, "AudioRecord init failed", t); return
        }
        if (rec.state != AudioRecord.STATE_INITIALIZED) { rec.release(); return }
        record = rec
        running = true
        rec.startRecording()
        thread = Thread {
            val chunk = ShortArray(SAMPLE_RATE / 2)
            while (running) {
                val n = rec.read(chunk, 0, chunk.size)
                if (n > 0) buffer.append(chunk, n)
            }
        }.also { it.start() }
    }

    fun stop() {
        running = false
        try { thread?.join(500) } catch (_: Throwable) {}
        thread = null
        record?.let {
            try { it.stop() } catch (_: Throwable) {}
            it.release()
        }
        record = null
    }

    private companion object {
        const val TAG = "AudioCaptureProvider"
        const val SAMPLE_RATE = 16_000
        const val CHANNEL = AudioFormat.CHANNEL_IN_MONO
        const val ENCODING = AudioFormat.ENCODING_PCM_16BIT
    }
}

package com.synapseriders.memory_recall_app.service

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.graphics.Bitmap
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import com.synapseriders.memory_recall_app.ai.OnDeviceModelRunner
import com.synapseriders.memory_recall_app.ai.ModelRuntime
import com.synapseriders.memory_recall_app.capture.AudioCaptureProvider
import com.synapseriders.memory_recall_app.capture.CameraFrameProvider
import com.synapseriders.memory_recall_app.capture.CaptureState
import com.synapseriders.memory_recall_app.capture.LocationProvider
import com.synapseriders.memory_recall_app.capture.SceneChangeDetector
import com.synapseriders.memory_recall_app.capture.TranscriptBuffer
import com.synapseriders.memory_recall_app.data.MemoryDatabase
import com.synapseriders.memory_recall_app.data.MemoryRepository
import com.synapseriders.memory_recall_app.identity.FaceEmbeddingEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

/**
 * Foreground service owning the always-live camera + ~5 s capture loop (IMPLEMENTATION_PLAN.md §9.1,
 * §20.2). Warms the shared model runner once at start, runs [CaptureLoop] every [TICK_MS], and exposes
 * a manual `forceTick`. A [LifecycleService] so CameraX can bind to its lifecycle.
 *
 * Providers start only when their runtime permission is granted (Kotlin re-checks, §17); missing ones
 * degrade gracefully.
 */
class MemoryForegroundService : LifecycleService() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val sceneDetector = SceneChangeDetector()
    private lateinit var runner: OnDeviceModelRunner
    private lateinit var captureLoop: CaptureLoop<Bitmap>
    private lateinit var transcriptBuffer: TranscriptBuffer

    private val camera by lazy { CameraFrameProvider(this) }
    private val location by lazy { LocationProvider(this) }
    private var audio: AudioCaptureProvider? = null
    private var tickJob: Job? = null

    override fun onCreate() {
        super.onCreate()
        val db = MemoryDatabase.getInstance(this)
        val repository = MemoryRepository(db.personDao(), db.sessionDao())
        runner = ModelRuntime.get(this)
        transcriptBuffer = TranscriptBuffer()
        val faceEngine = FaceEmbeddingEngine(runner)
        captureLoop = CaptureLoop(
            identify = faceEngine::identify,
            sink = repository,
            sceneChanged = sceneDetector::changed,
            profilesProvider = { db.personDao().findAll() },
            frameProvider = { CaptureState.latestFrame },
            transcriptProvider = { transcribe() },
            locationProvider = { CaptureState.latestLocation },
            thumbnailSaver = ::saveThumb,
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) {
            stopCapture()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }
        startForegroundNotification()
        when (intent?.action) {
            ACTION_TICK -> scope.launch { captureLoop.tick() }
            else -> startCapture()
        }
        return START_STICKY
    }

    private fun startCapture() {
        scope.launch { runner.warmUp() }
        if (hasPerm(Manifest.permission.CAMERA)) camera.start(this)
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            location.start()
        }
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) {
            audio = AudioCaptureProvider(transcriptBuffer).also { it.start() }
        }
        if (tickJob == null) {
            tickJob = scope.launch {
                while (isActive) {
                    captureLoop.tick()
                    delay(TICK_MS)
                }
            }
        }
    }

    /** Whisper transcript of the rolling window ("" until the Whisper QNN libs are present). */
    private fun transcribe(): String {
        val window = transcriptBuffer.window()
        if (window.isEmpty()) return ""
        val text = runner.transcribe(window, SAMPLE_RATE)
        if (text.isNotBlank()) transcriptBuffer.setTranscript(text)
        return text
    }

    private fun saveThumb(bmp: Bitmap): String {
        val dir = File(filesDir, "frames").apply { mkdirs() }
        val file = File(dir, "${System.currentTimeMillis()}.jpg")
        try {
            FileOutputStream(file).use { bmp.compress(Bitmap.CompressFormat.JPEG, 80, it) }
        } catch (t: Throwable) {
            Log.w(TAG, "thumb save failed", t)
        }
        return file.absolutePath
    }

    private fun startForegroundNotification() {
        val mgr = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            mgr.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Memory capture", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Memory Recall active")
            .setContentText("Capturing context on-device")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .build()
        val types = foregroundTypes()
        if (types != 0) startForeground(NOTIF_ID, notification, types)
        else startForeground(NOTIF_ID, notification)
    }

    /** Only declare FGS types we actually hold permission for (Android 14 requirement). */
    private fun foregroundTypes(): Int {
        var t = 0
        if (hasPerm(Manifest.permission.CAMERA)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA
        if (hasPerm(Manifest.permission.RECORD_AUDIO)) t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
        if (hasPerm(Manifest.permission.ACCESS_FINE_LOCATION) ||
            hasPerm(Manifest.permission.ACCESS_COARSE_LOCATION)
        ) {
            t = t or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        return t
    }

    private fun stopCapture() {
        tickJob?.cancel(); tickJob = null
        try { camera.stop() } catch (_: Throwable) {}
        try { location.stop() } catch (_: Throwable) {}
        audio?.stop(); audio = null
        CaptureState.clear()
    }

    override fun onDestroy() {
        stopCapture()
        scope.cancel()
        super.onDestroy()
    }

    private fun hasPerm(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    companion object {
        const val ACTION_START = "com.synapseriders.memory_recall_app.START"
        const val ACTION_STOP = "com.synapseriders.memory_recall_app.STOP"
        const val ACTION_TICK = "com.synapseriders.memory_recall_app.TICK"
        private const val CHANNEL_ID = "memory_capture"
        private const val NOTIF_ID = 42
        private const val TICK_MS = 5_000L
        private const val SAMPLE_RATE = 16_000
        private const val TAG = "MemoryFgService"

        private fun intent(ctx: Context, action: String) =
            Intent(ctx, MemoryForegroundService::class.java).setAction(action)

        fun start(ctx: Context) = ContextCompat.startForegroundService(ctx, intent(ctx, ACTION_START))
        fun tick(ctx: Context) = ContextCompat.startForegroundService(ctx, intent(ctx, ACTION_TICK))
        fun stop(ctx: Context) {
            try { ctx.startService(intent(ctx, ACTION_STOP)) } catch (_: Throwable) {}
        }
    }
}

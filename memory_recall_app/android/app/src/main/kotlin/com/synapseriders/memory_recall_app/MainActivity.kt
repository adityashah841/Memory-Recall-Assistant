package com.synapseriders.memory_recall_app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.synapseriders.memory_recall_app.bridge.MemoryBackendApi
import com.synapseriders.memory_recall_app.bridge.MemoryBackendChannel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // CP6 (§17): request capture permissions up-front; the service re-checks and degrades safely.
        val needed = buildList {
            add(Manifest.permission.CAMERA)
            add(Manifest.permission.RECORD_AUDIO)
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }.filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (needed.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, needed.toTypedArray(), PERMISSION_REQUEST)
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register the Pigeon host so Flutter can call MemoryBackendApi (Section 11/13.2).
        val backend = MemoryBackendChannel(applicationContext)
        MemoryBackendApi.setUp(flutterEngine.dartExecutor.binaryMessenger, backend)
    }

    private companion object {
        const val PERMISSION_REQUEST = 1001
    }
}

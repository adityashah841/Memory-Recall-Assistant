package com.synapseriders.memory_recall_app

import com.synapseriders.memory_recall_app.bridge.MemoryBackendApi
import com.synapseriders.memory_recall_app.bridge.MemoryBackendChannel
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : FlutterActivity() {

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        // Register the Pigeon host so Flutter can call MemoryBackendApi (Section 11/13.2).
        val backend = MemoryBackendChannel(applicationContext)
        MemoryBackendApi.setUp(flutterEngine.dartExecutor.binaryMessenger, backend)
    }
}

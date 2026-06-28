package com.synapseriders.memory_recall_app.ai

import android.content.Context

/**
 * Process-wide singleton for the on-device model runner (IMPLEMENTATION_PLAN.md §18, §20.2).
 *
 * Both the foreground service (warm-up + capture) and the Pigeon channel (recall) share one
 * [CompositeModelRunner] instance, so models are loaded once and native handles aren't duplicated.
 */
object ModelRuntime {
    @Volatile private var runner: CompositeModelRunner? = null

    fun get(context: Context): CompositeModelRunner =
        runner ?: synchronized(this) {
            runner ?: CompositeModelRunner(context.applicationContext).also { runner = it }
        }
}

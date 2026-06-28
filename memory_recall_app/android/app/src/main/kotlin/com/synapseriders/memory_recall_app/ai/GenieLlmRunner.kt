package com.synapseriders.memory_recall_app.ai

import android.util.Log

/**
 * QAIRT **Genie** LLM runner (IMPLEMENTATION_PLAN.md §7, §8.2): Llama 3.2 1B Instruct, w4.
 *
 * The Genie dialog (created from `genie_config.json`) owns tokenization, KV-cache and sampling
 * internally, so this runner only forwards a fully-formatted Llama chat prompt (§7.1/§7.2) and returns
 * the completion. Runs through [NativeInference] (JNI → Genie). Returns "" on any failure so callers
 * apply the template fallback (§7.3 `"This is {name}."`) — **never crashes** (§22 CP3).
 *
 * Not an [OnDeviceModelRunner] itself — composed by [CompositeModelRunner].
 */
class GenieLlmRunner(private val assets: ModelAssets) {

    @Volatile private var handle = 0L

    /** Create the Genie dialog once from the pushed bundle. Never throws. */
    fun warmUp() {
        if (!NativeInference.loaded) {
            Log.i(TAG, "native Genie lib absent — LLM disabled (template fallback)")
            return
        }
        if (handle == 0L && assets.hasGenie()) {
            handle = try {
                NativeInference.genieCreate(assets.genieDir.absolutePath, GENIE_CONFIG)
            } catch (t: Throwable) {
                Log.w(TAG, "genieCreate failed", t); 0L
            }
            Log.i(TAG, "llama_genie loaded=${handle != 0L}")
        }
    }

    /** Generate a completion for the already-formatted [prompt]; "" when unavailable. */
    fun generate(prompt: String, maxTokens: Int = 256): String {
        if (handle == 0L) { warmUp(); if (handle == 0L) return "" }
        return StageLatency.measure(StageLatency.LLM_GENERATION) {
            try {
                NativeInference.genieGenerate(handle, prompt, maxTokens)
            } catch (t: Throwable) {
                Log.w(TAG, "generate failed", t); ""
            }
        }
    }

    fun close() {
        if (handle != 0L) {
            try { NativeInference.genieFree(handle) } catch (_: Throwable) {}
            handle = 0L
        }
    }

    private companion object {
        const val TAG = "GenieLlmRunner"
        const val GENIE_CONFIG = "genie_config.json"
    }
}

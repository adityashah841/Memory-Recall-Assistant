package com.synapseriders.memory_recall_app.ai

import android.util.Log
import java.lang.reflect.Array as ReflectArray

/**
 * Thin reflective wrapper over the ExecuTorch Android runtime (`org.pytorch.executorch.*`).
 *
 * Reflection (rather than a direct `import`) keeps the whole `ai/` module **compilable with zero
 * external artifacts** — the project builds and runs (falling back) even before
 * `executorch-android-release.aar` is dropped into `app/libs/` and the `.pte` models are pushed
 * (IMPLEMENTATION_PLAN.md §22 CP3 "no crash when any model is missing"; §24 deviation note).
 *
 * Once the AAR is present this transparently calls the real runtime:
 *   `Module.load(path)` → `module.forward(EValue.from(Tensor.fromBlob(data, shape)))` → `EValue[]`.
 *
 * If the runtime classes are absent or any call fails, [isAvailable] is false / [forward] returns
 * empty and the owning runner degrades gracefully. The class/method names match the published
 * ExecuTorch Java API; if a future AAR renames them, adjust the string literals below only.
 */
class ExecuTorchBackend private constructor(private val module: Any) {

    /** Run a single-input forward. Returns one float[] per output tensor; empty list on any failure. */
    fun forward(input: FloatArray, shape: LongArray): List<FloatArray> {
        val rt = runtime ?: return emptyList()
        return try {
            val tensor = rt.fromBlob.invoke(null, input, shape)
            val evalue = rt.evalueFrom.invoke(null, tensor)
            val inputs = ReflectArray.newInstance(rt.evalueClass, 1).also {
                ReflectArray.set(it, 0, evalue)
            }
            val outputs = rt.forward.invoke(module, inputs) as Array<*>
            outputs.mapNotNull { ev ->
                val t = rt.toTensor.invoke(ev) ?: return@mapNotNull null
                rt.dataAsFloatArray.invoke(t) as? FloatArray
            }
        } catch (t: Throwable) {
            Log.w(TAG, "ExecuTorch forward failed", t)
            emptyList()
        }
    }

    fun close() {
        try {
            runtime?.close?.invoke(module)
        } catch (_: Throwable) {
            // Older AARs have no close(); the module is GC'd anyway.
        }
    }

    companion object {
        private const val TAG = "ExecuTorchBackend"

        /** Resolved reflective handles, or null when the ExecuTorch runtime is not on the classpath. */
        private val runtime: Rt? by lazy { resolveRuntime() }

        /** True when the ExecuTorch AAR is present and its API resolved. */
        val isAvailable: Boolean get() = runtime != null

        /** Load a `.pte` module from an absolute path; null if unavailable or load fails. */
        fun load(absolutePath: String): ExecuTorchBackend? {
            val rt = runtime ?: return null
            return try {
                ExecuTorchBackend(rt.load.invoke(null, absolutePath))
            } catch (t: Throwable) {
                Log.w(TAG, "ExecuTorch load failed for $absolutePath", t)
                null
            }
        }

        private fun resolveRuntime(): Rt? = try {
            val moduleClass = Class.forName("org.pytorch.executorch.Module")
            val tensorClass = Class.forName("org.pytorch.executorch.Tensor")
            val evalueClass = Class.forName("org.pytorch.executorch.EValue")
            val evalueArrayClass = ReflectArray.newInstance(evalueClass, 0).javaClass
            Rt(
                evalueClass = evalueClass,
                load = moduleClass.getMethod("load", String::class.java),
                forward = moduleClass.getMethod("forward", evalueArrayClass),
                close = runCatching { moduleClass.getMethod("destroy") }.getOrNull(),
                fromBlob = tensorClass.getMethod("fromBlob", FloatArray::class.java, LongArray::class.java),
                evalueFrom = evalueClass.getMethod("from", tensorClass),
                toTensor = evalueClass.getMethod("toTensor"),
                dataAsFloatArray = tensorClass.getMethod("getDataAsFloatArray"),
            )
        } catch (t: Throwable) {
            Log.i(TAG, "ExecuTorch runtime not available (AAR missing) — face detection/landmark will fall back")
            null
        }
    }

    /** Resolved reflective method handles for one AAR. */
    private class Rt(
        val evalueClass: Class<*>,
        val load: java.lang.reflect.Method,
        val forward: java.lang.reflect.Method,
        val close: java.lang.reflect.Method?,
        val fromBlob: java.lang.reflect.Method,
        val evalueFrom: java.lang.reflect.Method,
        val toTensor: java.lang.reflect.Method,
        val dataAsFloatArray: java.lang.reflect.Method,
    )
}

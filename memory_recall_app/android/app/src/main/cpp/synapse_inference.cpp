// Native QNN + Genie bridge (IMPLEMENTATION_PLAN.md §2, §8.2, §22 CP3).
//
// Implements the JNI entry points declared in ai/NativeInference.kt. Two runtimes:
//   * Raw QNN context binaries  — CavaFace (cavaface.bin) and Whisper (encoder/decoder.bin),
//     loaded via the QNN System + HTP backend API (QnnContext_createFromBinary, QnnGraph_execute).
//   * QAIRT Genie               — Llama 3.2 1B dialog (GenieDialog_create/_query).
//
// This is a SKELETON: lifecycle (init/load/free) is wired against the real API surface; the actual
// graph I/O binding (tensor names, quant params) is marked TODO and must be confirmed against the
// delivered binaries on-device (cf. the CavaFace I/O caveat, §2.1). Every function returns a safe
// failure value so the Kotlin layer degrades gracefully — it must never abort.

#include <jni.h>
#include <android/log.h>
#include <string>
#include <vector>
#include <cstdint>

#define LOG_TAG "synapse_inference"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN, LOG_TAG, __VA_ARGS__)

// When building with the QAIRT SDK, include the real headers:
//   #include "QnnInterface.h"
//   #include "System/QnnSystemInterface.h"
//   #include "HTP/QnnHtpDevice.h"
//   #include "GenieDialog.h"
// and replace the opaque handles below with the SDK types.

namespace {

// Opaque per-model state. Replace the void* members with QNN/Genie handles once headers are included.
struct CavaFaceModel { void *context = nullptr; void *graph = nullptr; };
struct WhisperModel  { void *encoder = nullptr; void *decoder = nullptr; };
struct GenieModel    { void *dialog = nullptr; };

std::string jstr(JNIEnv *env, jstring s) {
    if (s == nullptr) return {};
    const char *c = env->GetStringUTFChars(s, nullptr);
    std::string out = c ? c : "";
    if (c) env->ReleaseStringUTFChars(s, c);
    return out;
}

} // namespace

extern "C" {

// ------------------------------------------------------------------ QNN backend lifecycle

JNIEXPORT jboolean JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_qnnInit(
        JNIEnv *env, jobject /*thiz*/, jstring nativeLibDir) {
    const std::string libDir = jstr(env, nativeLibDir);
    LOGI("qnnInit libDir=%s", libDir.c_str());
    // TODO: load libQnnHtp.so from libDir, resolve QnnInterface_getProviders(), create a device +
    //       backend + context handle for HTP. Return true once the backend is ready.
    return JNI_FALSE;
}

// ------------------------------------------------------------------ CavaFace (cavaface.bin)

JNIEXPORT jlong JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_cavaFaceLoad(
        JNIEnv *env, jobject /*thiz*/, jstring modelPath) {
    const std::string path = jstr(env, modelPath);
    LOGI("cavaFaceLoad %s", path.c_str());
    // TODO: read the file, QnnContext_createFromBinary(...), retrieve the graph handle.
    auto *m = new CavaFaceModel();
    // return reinterpret_cast<jlong>(m) once populated; for now report failure.
    delete m;
    return 0;
}

JNIEXPORT jfloatArray JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_cavaFaceEmbed(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray input) {
    if (handle == 0) return nullptr;
    // TODO: quantize `input` (float32[1,3,112,112]) per the model's input quant params, bind it,
    //       QnnGraph_execute, dequantize output_0 to float32[512], return it.
    (void) input;
    return nullptr;
}

JNIEXPORT void JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_cavaFaceFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<CavaFaceModel *>(handle);
}

// ------------------------------------------------------------------ Whisper (encoder/decoder.bin)

JNIEXPORT jlong JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_whisperLoad(
        JNIEnv *env, jobject /*thiz*/, jstring encoderPath, jstring decoderPath) {
    LOGI("whisperLoad enc=%s dec=%s", jstr(env, encoderPath).c_str(), jstr(env, decoderPath).c_str());
    // TODO: QnnContext_createFromBinary for both encoder and decoder. (Full decode loop = CP5.)
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_whisperTranscribe(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jfloatArray logMel) {
    (void) handle; (void) logMel;
    // TODO (CP5): run encoder → autoregressive decoder → detokenize.
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_whisperFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<WhisperModel *>(handle);
}

// ------------------------------------------------------------------ Genie LLM

JNIEXPORT jlong JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_genieCreate(
        JNIEnv *env, jobject /*thiz*/, jstring bundleDir, jstring configFileName) {
    const std::string dir = jstr(env, bundleDir);
    const std::string cfg = jstr(env, configFileName);
    LOGI("genieCreate dir=%s cfg=%s", dir.c_str(), cfg.c_str());
    // TODO: chdir(dir) so ctx-bins/tokenizer relative paths in genie_config.json resolve, read the
    //       config JSON, GenieDialogConfig_createFromJson(...), GenieDialog_create(...).
    return 0;
}

JNIEXPORT jstring JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_genieGenerate(
        JNIEnv *env, jobject /*thiz*/, jlong handle, jstring prompt, jint maxTokens) {
    (void) handle; (void) maxTokens;
    if (handle == 0) return env->NewStringUTF("");
    // TODO: GenieDialog_query(dialog, prompt, SENTINEL, callback) accumulating tokens into a string.
    return env->NewStringUTF("");
}

JNIEXPORT void JNICALL
Java_com_synapseriders_memory_1recall_1app_ai_NativeInference_genieFree(
        JNIEnv * /*env*/, jobject /*thiz*/, jlong handle) {
    if (handle == 0) return;
    delete reinterpret_cast<GenieModel *>(handle);
}

} // extern "C"

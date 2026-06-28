plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.google.devtools.ksp")
    // The Flutter Gradle Plugin must be applied after the Android and Kotlin Gradle plugins.
    id("dev.flutter.flutter-gradle-plugin")
}

// CP3 (§22): the QNN/Genie native bridge only builds when the QAIRT 2.45 SDK path is supplied,
// via `qnnSdkRoot` in local.properties or the QNN_SDK_ROOT env var. Without it the app still builds
// and runs (CavaFace/Whisper/LLM fall back) — see ai/NativeInference.kt.
val qnnSdkRoot: String? = (project.findProperty("qnnSdkRoot") as String?) ?: System.getenv("QNN_SDK_ROOT")

android {
    namespace = "com.synapseriders.memory_recall_app"
    compileSdk = flutter.compileSdkVersion
    ndkVersion = flutter.ndkVersion

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    defaultConfig {
        // TODO: Specify your own unique Application ID (https://developer.android.com/studio/build/application-id.html).
        applicationId = "com.synapseriders.memory_recall_app"
        // You can update the following values to match your application needs.
        // For more information, see: https://flutter.dev/to/review-gradle-config.
        // Section 2: minSdk 31 (Android 12), targetSdk 34+ for the FGS/permission model.
        minSdk = maxOf(31, flutter.minSdkVersion)
        targetSdk = maxOf(34, flutter.targetSdkVersion)
        versionCode = flutter.versionCode
        versionName = flutter.versionName

        // §2: Snapdragon 8 Elite NPU is arm64-only; QNN/Genie + ExecuTorch ship arm64-v8a .so only.
        ndk {
            abiFilters += "arm64-v8a"
        }
        if (qnnSdkRoot != null) {
            externalNativeBuild {
                cmake {
                    arguments += "-DQNN_SDK_ROOT=$qnnSdkRoot"
                }
            }
        }
    }

    // The QAIRT .so runtime libs in jniLibs must ship uncompressed so the native loader can mmap them.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // Only wire the C++ build when the QNN SDK is available (keeps a vanilla build green).
    if (qnnSdkRoot != null) {
        externalNativeBuild {
            cmake {
                path = file("src/main/cpp/CMakeLists.txt")
                version = "3.22.1"
            }
        }
    }

    buildTypes {
        release {
            // TODO: Add your own signing config for the release build.
            // Signing with the debug keys for now, so `flutter run --release` works.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

kotlin {
    compilerOptions {
        jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17
    }
}

flutter {
    source = "../.."
}

dependencies {
    val roomVersion = "2.8.4"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")

    // CP3 (§2, §16): ExecuTorch runtime for the face detection/landmark .pte models, from Maven
    // Central (org.pytorch:executorch-android). Loaded reflectively (ai/ExecuTorchBackend.kt), so the
    // app still compiles/runs (with fallback) if this is ever absent or version-mismatched.
    implementation("org.pytorch:executorch-android:1.3.1")
    // Optional override: drop a specific executorch AAR into app/libs/ to pin a matching runtime.
    implementation(fileTree("libs") { include("*.aar") })

    // CP4: JVM unit tests for the identity geometry/matching logic (src/test).
    testImplementation("junit:junit:4.13.2")
}

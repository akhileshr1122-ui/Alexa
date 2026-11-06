import java.net.URI
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val runNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()

android {
    namespace = "com.haloapps.halobot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.haloapps.halobot"
        minSdk = 29
        targetSdk = 34
        versionCode = runNumber
        versionName = "1.0.$runNumber"
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a") }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    // ML models are memory-mapped from the APK, so keep them uncompressed.
    androidResources {
        noCompress += listOf("tflite", "task")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

kotlin {
    compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    val camerax = "1.4.2"
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("com.google.mlkit:face-detection:16.1.7")
    implementation("com.google.mediapipe:tasks-vision:0.10.26")
    implementation("com.google.ai.edge.litert:litert:2.1.0")
}

// MediaPipe models (Apache 2.0), fetched at build time into assets/models if missing.
val mediapipeModels = mapOf(
    "gesture_recognizer.task" to "https://storage.googleapis.com/mediapipe-models/gesture_recognizer/gesture_recognizer/float16/latest/gesture_recognizer.task",
    "efficientdet_lite0.tflite" to "https://storage.googleapis.com/mediapipe-models/object_detector/efficientdet_lite0/int8/latest/efficientdet_lite0.tflite",
    "blaze_face_short_range.tflite" to "https://storage.googleapis.com/mediapipe-models/face_detector/blaze_face_short_range/float16/latest/blaze_face_short_range.tflite",
)
val modelDir = layout.projectDirectory.dir("src/main/assets/models")
val downloadModels by tasks.registering {
    val dir = modelDir.asFile
    val models = mediapipeModels
    outputs.files(models.keys.map { File(dir, it) })
    doLast {
        dir.mkdirs()
        models.forEach { (name, url) ->
            val f = File(dir, name)
            if (f.exists() && f.length() > 0) return@forEach
            logger.lifecycle("Downloading $name")
            val tmp = File(dir, "$name.part")
            URI(url).toURL().openStream().use { input -> tmp.outputStream().use { input.copyTo(it) } }
            if (tmp.length() < 1024) throw GradleException("Model download looks wrong: $name (${tmp.length()} bytes)")
            tmp.renameTo(f)
        }
    }
}
tasks.named("preBuild") { dependsOn(downloadModels) }

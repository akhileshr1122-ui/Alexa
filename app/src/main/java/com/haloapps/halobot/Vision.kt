package com.haloapps.halobot

import android.content.ContentValues
import android.provider.MediaStore
import android.util.Log
import android.util.Size
import androidx.activity.ComponentActivity
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/** Front camera eyes: on-device ML Kit face detection (works offline) + photo capture. */
class Vision(
    private val act: ComponentActivity,
    private val previewView: PreviewView,
    private val onSeen: (Seen) -> Unit,
) {
    data class FaceInfo(
        val id: Int?,
        val cx: Float,
        val cy: Float,
        val size: Float,
        val smile: Float,
        val leftOpen: Float,
        val rightOpen: Float,
        val roll: Float,
        val yaw: Float,
    )

    data class Seen(val faces: List<FaceInfo>) {
        val main: FaceInfo? get() = faces.maxByOrNull { it.size }
    }

    private val exec = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_FAST)
            .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)
            .setMinFaceSize(.12f)
            .enableTracking()
            .build()
    )
    private var capture: ImageCapture? = null
    var running = false; private set
    @Volatile var paused = false

    fun start() {
        if (running) return
        val future = ProcessCameraProvider.getInstance(act)
        future.addListener({
            try {
                val provider = future.get()
                val selector = ResolutionSelector.Builder()
                    .setResolutionStrategy(ResolutionStrategy(Size(640, 480), ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER))
                    .build()
                val analysis = ImageAnalysis.Builder()
                    .setResolutionSelector(selector)
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .build()
                analysis.setAnalyzer(exec) { proxy -> analyze(proxy) }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val cap = ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build()
                provider.unbindAll()
                try {
                    provider.bindToLifecycle(act, CameraSelector.DEFAULT_FRONT_CAMERA, preview, analysis, cap)
                    capture = cap
                } catch (e: Exception) {
                    Log.w("HaloBot", "3 use cases failed, falling back", e)
                    provider.unbindAll()
                    provider.bindToLifecycle(act, CameraSelector.DEFAULT_FRONT_CAMERA, analysis, cap)
                    capture = cap
                }
                running = true
            } catch (e: Exception) {
                Log.e("HaloBot", "camera start failed", e)
            }
        }, ContextCompat.getMainExecutor(act))
    }

    @androidx.annotation.OptIn(ExperimentalGetImage::class)
    private fun analyze(proxy: ImageProxy) {
        val media = proxy.image
        if (media == null || paused) { proxy.close(); return }
        val rot = proxy.imageInfo.rotationDegrees
        val w = (if (rot % 180 == 0) proxy.width else proxy.height).toFloat()
        val h = (if (rot % 180 == 0) proxy.height else proxy.width).toFloat()
        val img = InputImage.fromMediaImage(media, rot)
        detector.process(img)
            .addOnSuccessListener { faces ->
                val list = faces.map { f ->
                    val b = f.boundingBox
                    FaceInfo(
                        id = f.trackingId,
                        cx = b.exactCenterX() / w,
                        cy = b.exactCenterY() / h,
                        size = b.width() / w,
                        smile = f.smilingProbability ?: -1f,
                        leftOpen = f.leftEyeOpenProbability ?: -1f,
                        rightOpen = f.rightEyeOpenProbability ?: -1f,
                        roll = f.headEulerAngleZ,
                        yaw = f.headEulerAngleY,
                    )
                }
                onSeen(Seen(list))
            }
            .addOnCompleteListener { proxy.close() }
    }

    fun takePhoto(cb: (Boolean) -> Unit) {
        val cap = capture ?: run { cb(false); return }
        val name = "HaloBot_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, name)
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/HaloBot")
        }
        val meta = ImageCapture.Metadata().apply { isReversedHorizontal = true }
        val opts = ImageCapture.OutputFileOptions.Builder(act.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            .setMetadata(meta).build()
        cap.takePicture(opts, ContextCompat.getMainExecutor(act), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { cb(true) }
            override fun onError(exception: ImageCaptureException) { cb(false) }
        })
    }

    fun close() {
        try { detector.close() } catch (_: Exception) {}
        exec.shutdown()
    }
}

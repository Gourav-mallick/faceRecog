package com.example.facerec

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.facerec.data.AppDatabase
import com.example.facerec.data.Person
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetectorOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.Executors

class RecognitionActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var recognizer: FaceRecognizer
    private lateinit var previewView: androidx.camera.view.PreviewView
    private lateinit var resultText: TextView

    private val executor = Executors.newSingleThreadExecutor()
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .enableTracking()
        .build()

    private val detector = FaceDetection.getClient(detectorOptions)

    private var lastDetectionTime = 0L
    private var lastRecognizedName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_recognition)

        db = AppDatabase.getInstance(this)
        recognizer = FaceRecognizer(this)

        previewView = findViewById(R.id.iv_recognition_photo)
        resultText = findViewById(R.id.tv_result)

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener(Runnable {
            val cameraProvider = cameraProviderFuture.get()
            val preview = Preview.Builder().build().also {
                it.setSurfaceProvider(previewView.surfaceProvider)
            }

            val imageAnalyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    it.setAnalyzer(executor, FaceAnalyzer())
                }

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, preview, imageAnalyzer)
            } catch (exc: Exception) {
                Log.e("CameraX", "Binding failed", exc)
            }
        }, ContextCompat.getMainExecutor(this))
    }

    inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            val mediaImage = imageProxy.image ?: return
            val rotation = imageProxy.imageInfo.rotationDegrees
            val image = InputImage.fromMediaImage(mediaImage, rotation)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastDetectionTime > 4000) { // every 4 sec
                            lastDetectionTime = currentTime

                            val bmp = previewView.bitmap ?: return@addOnSuccessListener
                            val face = faces[0]
                            val rect = face.boundingBox
                            val padding = (rect.width() * 0.2).toInt()
                            val left = (rect.left - padding).coerceAtLeast(0)
                            val top = (rect.top - padding).coerceAtLeast(0)
                            val right = (rect.right + padding).coerceAtMost(bmp.width)
                            val bottom = (rect.bottom + padding).coerceAtMost(bmp.height)

                            val faceBmp = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                            recognizeFace(faceBmp)
                        }
                    }
                }
                .addOnFailureListener { Log.e("FaceDetection", "Error: ${it.message}") }
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    private fun recognizeFace(faceBmp: Bitmap) {
        lifecycleScope.launch {
            val persons = withContext(Dispatchers.IO) { db.personDao().getAll() }
            if (persons.isEmpty()) {
                resultText.text = "⚠️ No enrolled users found"
                return@launch
            }

            // ✅ Flip horizontally for front camera
            val matrix = Matrix().apply { preScale(-1f, 1f) }
            val flippedFace = Bitmap.createBitmap(faceBmp, 0, 0, faceBmp.width, faceBmp.height, matrix, true)

            val newEmbedding = recognizer.getEmbedding(flippedFace)
            var bestMatch: Person? = null
            var minDistance = Float.MAX_VALUE

            for (p in persons) {
                // ✅ Skip users without embeddings or photos
                if (p.embeddingJson.isNullOrEmpty() || p.photoPath.isNullOrEmpty()) continue

                val emb = recognizer.jsonToEmbedding(p.embeddingJson!!)
                val dist = recognizer.compareEmbeddings(newEmbedding, emb)
                if (dist < minDistance) {
                    minDistance = dist
                    bestMatch = p
                }
            }

            withContext(Dispatchers.Main) {
                if (bestMatch != null && minDistance < 1.0f) {
                    resultText.text = "✅ Recognized: ${bestMatch.studentName} (ID: ${bestMatch.studentId})\nDist=${"%.3f".format(minDistance)}"
                    lastRecognizedName = bestMatch.studentName
                } else {
                    resultText.text = "❌ Not enrolled\nDist=${"%.3f".format(minDistance)}"
                    lastRecognizedName = ""
                }
            }
        }
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 10
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

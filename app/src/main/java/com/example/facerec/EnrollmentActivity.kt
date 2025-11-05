package com.example.facerec

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Bundle
import android.util.Log
import android.widget.*
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
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class EnrollmentActivity : AppCompatActivity() {

    private lateinit var db: AppDatabase
    private lateinit var recognizer: FaceRecognizer

    private lateinit var nameInput: EditText
    private lateinit var startBtn: Button
    private lateinit var viewAllBtn: Button
    private lateinit var recognizeBtn: Button
    private lateinit var statusText: TextView
    private lateinit var previewView: androidx.camera.view.PreviewView

    private val executor = Executors.newSingleThreadExecutor()
    private val detector = FaceDetection.getClient(
        FaceDetectorOptions.Builder()
            .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
            .build()
    )

    private var isCapturing = false
    private var capturedEmbeddings = mutableListOf<FloatArray>()
    private var lastCaptureTime = 0L
    private var currentName = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_enrollment)

        db = AppDatabase.getInstance(this)
        recognizer = FaceRecognizer(this)

        nameInput = findViewById(R.id.et_name)
        previewView = findViewById(R.id.iv_photo)
        startBtn = findViewById(R.id.btn_capture)
        statusText = findViewById(R.id.tv_status)
        viewAllBtn = findViewById(R.id.btn_view_all)
        recognizeBtn = findViewById(R.id.btn_open_recognition)

        startBtn.setOnClickListener { startEnrollment() }
        viewAllBtn.setOnClickListener {
            startActivity(android.content.Intent(this, PersonListActivity::class.java))
        }
        recognizeBtn.setOnClickListener {
            startActivity(android.content.Intent(this, RecognitionActivity::class.java))
        }

        if (allPermissionsGranted()) startCamera()
        else ActivityCompat.requestPermissions(this, REQUIRED_PERMISSIONS, REQUEST_CODE_PERMISSIONS)
    }

    private fun allPermissionsGranted() = REQUIRED_PERMISSIONS.all {
        ContextCompat.checkSelfPermission(baseContext, it) == PackageManager.PERMISSION_GRANTED
    }

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .setTargetRotation(previewView.display.rotation)
                .build()

            preview.setSurfaceProvider(previewView.surfaceProvider)

            val analyzer = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also {
                    // Run analysis on background thread
                    it.setAnalyzer(Executors.newSingleThreadExecutor(), FaceAnalyzer())
                }

            try {
                cameraProvider.unbindAll()

                // Delay binding slightly to ensure surface is ready
                previewView.post {
                    cameraProvider.bindToLifecycle(
                        this,
                        CameraSelector.DEFAULT_FRONT_CAMERA,
                        preview,
                        analyzer
                    )
                }

            } catch (exc: Exception) {
                Log.e("CameraX", "Camera binding failed: ${exc.message}")
                Toast.makeText(this, "Camera failed to start", Toast.LENGTH_SHORT).show()
            }
        }, ContextCompat.getMainExecutor(this))
    }


    private fun startEnrollment() {
        currentName = nameInput.text.toString().trim()
        if (currentName.isEmpty()) {
            Toast.makeText(this, "Please enter a name first!", Toast.LENGTH_SHORT).show()
            return
        }

        capturedEmbeddings.clear()
        isCapturing = true
        statusText.text = "Capturing face data..."
        Toast.makeText(this, "Keep your face steady for 3 samples", Toast.LENGTH_SHORT).show()
    }

    inner class FaceAnalyzer : ImageAnalysis.Analyzer {
        override fun analyze(imageProxy: ImageProxy) {
            if (!isCapturing) {
                imageProxy.close()
                return
            }

            val mediaImage = imageProxy.image ?: return
            val image = InputImage.fromMediaImage(mediaImage, imageProxy.imageInfo.rotationDegrees)

            detector.process(image)
                .addOnSuccessListener { faces ->
                    if (faces.isNotEmpty()) {
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastCaptureTime > 1500 && capturedEmbeddings.size < 3) {
                            lastCaptureTime = currentTime

                            val bmp = previewView.bitmap ?: return@addOnSuccessListener
                            val face = faces[0]
                            val rect = face.boundingBox
                            val pad = (rect.width() * 0.2).toInt()
                            val left = (rect.left - pad).coerceAtLeast(0)
                            val top = (rect.top - pad).coerceAtLeast(0)
                            val right = (rect.right + pad).coerceAtMost(bmp.width)
                            val bottom = (rect.bottom + pad).coerceAtMost(bmp.height)

                            val faceBmp = Bitmap.createBitmap(bmp, left, top, right - left, bottom - top)
                            val matrix = Matrix().apply { preScale(-1f, 1f) }
                            val flippedFace = Bitmap.createBitmap(faceBmp, 0, 0, faceBmp.width, faceBmp.height, matrix, true)

                            val emb = recognizer.getEmbedding(flippedFace)
                            capturedEmbeddings.add(emb)
                            runOnUiThread {
                                statusText.text = "Captured: ${capturedEmbeddings.size}/3"
                            }

                            if (capturedEmbeddings.size == 3) {
                                isCapturing = false
                                saveEnrollment()
                            }
                        }
                    }
                }
                .addOnCompleteListener { imageProxy.close() }
        }
    }

    private fun saveEnrollment() {
        lifecycleScope.launch {
            val avg = FloatArray(capturedEmbeddings[0].size)
            for (emb in capturedEmbeddings)
                for (i in emb.indices) avg[i] += emb[i]
            for (i in avg.indices) avg[i] /= capturedEmbeddings.size

            val embJson = recognizer.embeddingToJson(avg)
            val allPersons = db.personDao().getAll()

            var matchedPerson: Person? = null
            var matchedEmb: FloatArray? = null
            var highestSim = 0f

            // Compare new face embedding with all existing ones
            for (p in allPersons) {
                val storedEmb = recognizer.jsonToEmbedding(p.embeddingJson)
                val sim = FaceRecognizer.cosineSimilarity(avg, storedEmb)
                if (sim > highestSim) {
                    highestSim = sim
                    matchedPerson = p
                    matchedEmb = storedEmb
                }
            }

            // More stable same-face detection (threshold 0.80)
            // Adaptive thresholding for more stable matching
            if (matchedPerson != null) {
                val confirmSim = FaceRecognizer.cosineSimilarity(avg, recognizer.jsonToEmbedding(matchedPerson!!.embeddingJson))

                // Tiered logic
                if (highestSim > 0.82f || (highestSim > 0.75f && confirmSim > 0.78f)) {
                    withContext(Dispatchers.Main) {
                        if (matchedPerson!!.name != currentName) {
                            Toast.makeText(
                                this@EnrollmentActivity,
                                " Face already enrolled as ${matchedPerson!!.name}",
                                Toast.LENGTH_LONG
                            ).show()
                            statusText.text = "Duplicate face detected"
                        } else {
                            Toast.makeText(
                                this@EnrollmentActivity,
                                "This person ($currentName) is already enrolled.",
                                Toast.LENGTH_LONG
                            ).show()
                            statusText.text = "Already enrolled"
                        }
                    }
                    return@launch
                }
            }


            // Save new enrollment
            val photoPath = saveBitmapToInternal(
                previewView.bitmap!!,
                "photo_${System.currentTimeMillis()}.jpg"
            )
            db.personDao().insert(
                Person(
                    name = currentName,
                    photoPath = photoPath,
                    embeddingJson = embJson
                )
            )

            withContext(Dispatchers.Main) {
                Toast.makeText(
                    this@EnrollmentActivity,
                    "✅ Enrollment saved for $currentName",
                    Toast.LENGTH_SHORT
                ).show()
                nameInput.text.clear()
                statusText.text = "Enrollment complete!"
            }
        }
    }

    private fun saveBitmapToInternal(bmp: Bitmap, filename: String): String {
        val file = File(filesDir, filename)
        FileOutputStream(file).use { out -> bmp.compress(Bitmap.CompressFormat.JPEG, 90, out) }
        return file.absolutePath
    }

    companion object {
        private const val REQUEST_CODE_PERMISSIONS = 101
        private val REQUIRED_PERMISSIONS = arrayOf(Manifest.permission.CAMERA)
    }
}

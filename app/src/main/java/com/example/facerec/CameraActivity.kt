package com.example.facerec

import android.annotation.SuppressLint
import android.graphics.*
import android.media.Image
import android.os.Bundle
import android.util.Size
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class CameraActivity : AppCompatActivity() {

    private lateinit var previewView: PreviewView
    private lateinit var textStatus: TextView
    private lateinit var db: AppDatabase
    private lateinit var recognizer: FaceRecognizer

    private var lastRecognizedText: String? = null

    // ✅ Use ACCURATE mode for better bounding box precision
    private val detectorOptions = FaceDetectorOptions.Builder()
        .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
        .build()

    private val detector by lazy { FaceDetection.getClient(detectorOptions) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_camera)

        previewView = findViewById(R.id.preview_view)
        textStatus = findViewById(R.id.tv_status)

        db = AppDatabase.getInstance(this)
        recognizer = FaceRecognizer(this)

        startCamera()
    }

    /** Initialize and start the front camera using CameraX */
    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(this)
        cameraProviderFuture.addListener({
            val cameraProvider = cameraProviderFuture.get()

            val preview = Preview.Builder()
                .build()
                .apply { setSurfaceProvider(previewView.surfaceProvider) }

            val analyzer = ImageAnalysis.Builder()
                .setTargetResolution(Size(640, 480))
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()

            analyzer.setAnalyzer(ContextCompat.getMainExecutor(this)) { imageProxy ->
                processImageProxy(imageProxy)
            }

            val cameraSelector = CameraSelector.DEFAULT_FRONT_CAMERA

            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, cameraSelector, preview, analyzer)
                textStatus.text = "Camera started. Look at the camera..."
            } catch (exc: Exception) {
                textStatus.text = "Camera initialization failed: ${exc.message}"
            }

        }, ContextCompat.getMainExecutor(this))
    }

    /** Process each camera frame and perform face recognition */
    @SuppressLint("UnsafeOptInUsageError")
    private fun processImageProxy(imageProxy: ImageProxy) {
        val mediaImage = imageProxy.image ?: return imageProxy.close()

        val rotation = imageProxy.imageInfo.rotationDegrees
        val inputImage = InputImage.fromMediaImage(mediaImage, rotation)

        detector.process(inputImage)
            .addOnSuccessListener { faces ->
                if (faces.isNotEmpty()) {
                    val face = faces[0]
                    val bmp = toBitmap(mediaImage)
                    val rotatedBmp = rotateBitmap(bmp, rotation.toFloat())

                    val box = face.boundingBox
                    val left = box.left.coerceAtLeast(0)
                    val top = box.top.coerceAtLeast(0)
                    val width = box.width().coerceAtMost(rotatedBmp.width - left)
                    val height = box.height().coerceAtMost(rotatedBmp.height - top)

                    if (width > 0 && height > 0) {
                        val faceBmp = Bitmap.createBitmap(rotatedBmp, left, top, width, height)
                        lifecycleScope.launch {
                            recognizeFace(faceBmp)
                        }
                    }
                } else {
                    updateStatusText("No face detected")
                }
            }
            .addOnFailureListener {
                updateStatusText("Detection failed")
            }
            .addOnCompleteListener {
                imageProxy.close()
            }
    }

    /** Convert the YUV camera frame into a regular Bitmap */
    private fun toBitmap(image: Image): Bitmap {
        val nv21 = yuv420ToNv21(image)
        val yuvImage = YuvImage(nv21, ImageFormat.NV21, image.width, image.height, null)
        val out = ByteArrayOutputStream()
        yuvImage.compressToJpeg(Rect(0, 0, image.width, image.height), 90, out)
        val bytes = out.toByteArray()
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.size)
    }

    private fun yuv420ToNv21(image: Image): ByteArray {
        val yBuffer = image.planes[0].buffer
        val uBuffer = image.planes[1].buffer
        val vBuffer = image.planes[2].buffer

        val ySize = yBuffer.remaining()
        val uSize = uBuffer.remaining()
        val vSize = vBuffer.remaining()

        val nv21 = ByteArray(ySize + uSize + vSize)
        yBuffer.get(nv21, 0, ySize)

        val chromaRowStride = image.planes[1].rowStride
        val chromaRowPadding = chromaRowStride - image.width / 2

        var pos = ySize
        if (chromaRowPadding == 0) {
            vBuffer.get(nv21, pos, vSize)
            pos += vSize
            uBuffer.get(nv21, pos, uSize)
        } else {
            val row = ByteArray(image.width / 2)
            for (i in 0 until image.height / 2) {
                vBuffer.get(row, 0, image.width / 2)
                System.arraycopy(row, 0, nv21, pos, image.width / 2)
                pos += image.width / 2
                if (i < image.height / 2 - 1) vBuffer.position(vBuffer.position() + chromaRowPadding)
            }
            for (i in 0 until image.height / 2) {
                uBuffer.get(row, 0, image.width / 2)
                System.arraycopy(row, 0, nv21, pos, image.width / 2)
                pos += image.width / 2
                if (i < image.height / 2 - 1) uBuffer.position(uBuffer.position() + chromaRowPadding)
            }
        }
        return nv21
    }

    private fun rotateBitmap(source: Bitmap, angle: Float): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(angle)
        return Bitmap.createBitmap(source, 0, 0, source.width, source.height, matrix, true)
    }

    /** Compare detected face embedding with stored ones */
    private suspend fun recognizeFace(faceBmp: Bitmap) = withContext(Dispatchers.Default) {
        val emb = recognizer.getEmbedding(faceBmp)
        val persons = db.personDao().getAll()

        var bestMatch: Person? = null
        var bestScore = 0f

        for (p in persons) {
            val storedEmb = recognizer.jsonToEmbedding(p.embeddingJson)
            val score = FaceRecognizer.cosineSimilarity(emb, storedEmb)
            if (score > bestScore) {
                bestScore = score
                bestMatch = p
            }
        }

        val resultText = if (bestScore > 0.55f && bestMatch != null) {
            "✅ Recognized: ${bestMatch.name}"
        } else {
            "❌ Unknown person"
        }

        // ✅ Always update UI from main thread
        withContext(Dispatchers.Main) {
            updateStatusText(resultText)
        }
    }

    /** Update the TextView without flicker */
    private fun updateStatusText(newText: String) {
        if (newText != lastRecognizedText) {
            textStatus.text = newText
            lastRecognizedText = newText
        }
    }
}

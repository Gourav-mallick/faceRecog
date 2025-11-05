package com.example.facerec

import android.content.Context
import android.graphics.Bitmap
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.support.common.FileUtil
import kotlin.math.sqrt
import org.json.JSONArray
import java.nio.MappedByteBuffer

class FaceRecognizer(context: Context, modelFilename: String = "facenet.tflite") {

    //interpreter: This runs the TFLite model.
    private val interpreter: Interpreter

    //INPUT_SIZE: Image size that the model expects (e.g., 160×160 pixels).
    private val INPUT_SIZE = 160

    //EMBEDDING_DIM: Length of the feature vector output (usually 128 for FaceNet).
    private val EMBEDDING_DIM = 128


    //Loads the .tflite file into memory.Creates a TensorFlow Lite Interpreter to run the model.
    init {
        val modelBuffer: MappedByteBuffer = FileUtil.loadMappedFile(context, modelFilename)
        interpreter = Interpreter(modelBuffer)
    }

    /** Preprocess the face bitmap before passing into TFLite model
     * This function:
     * Resizes the face image to 160×160.
     * Extracts the RGB color values for each pixel.
     * Normalizes them from [0,1] to [-1,1] — because FaceNet expects normalized input.
     * Packs the image into a 4D array [1, height, width, 3] (the shape the model expects).
     */
    private fun preprocess(bitmap: Bitmap): Array<Array<Array<FloatArray>>> {
        val scaled = Bitmap.createScaledBitmap(bitmap, INPUT_SIZE, INPUT_SIZE, true)
        val input = Array(1) { Array(INPUT_SIZE) { Array(INPUT_SIZE) { FloatArray(3) } } }

        for (y in 0 until INPUT_SIZE) {
            for (x in 0 until INPUT_SIZE) {
                val px = scaled.getPixel(x, y)
                val r = ((px shr 16) and 0xFF) / 255.0f
                val g = ((px shr 8) and 0xFF) / 255.0f
                val b = (px and 0xFF) / 255.0f

                // normalize between -1 and 1
                input[0][y][x][0] = (r - 0.5f) / 0.5f
                input[0][y][x][1] = (g - 0.5f) / 0.5f
                input[0][y][x][2] = (b - 0.5f) / 0.5f
            }
        }
        return input
    }

    /** Get normalized embedding from a face bitmap */
    fun getEmbedding(faceBitmap: Bitmap): FloatArray {
        //Sends the preprocessed image into the neural network.
        //Gets a 128-number array (called embedding) that represents the person’s face features.
        val input = preprocess(faceBitmap)
        val output = Array(1) { FloatArray(EMBEDDING_DIM) }
        interpreter.run(input, output)

        val emb = output[0]

        // L2-normalize
        var norm = 0f
        for (v in emb) norm += v * v
        norm = sqrt(norm)
        if (norm != 0f) {
            for (i in emb.indices) emb[i] /= norm
        }
        return emb
    }

    /** Convert embedding to JSON string for saving
     * Converts the embedding array into a JSON string, so it can be saved easily in a database.
     * */
    fun embeddingToJson(emb: FloatArray): String {
        val arr = JSONArray()
        for (v in emb) arr.put(v.toDouble())
        return arr.toString()
    }

    /** Convert JSON string back to embedding FloatArray
     * When you load a person from the database, this turns the JSON back into a FloatArray.
     */
    fun jsonToEmbedding(json: String): FloatArray {
        val ja = JSONArray(json)
        val emb = FloatArray(ja.length())
        for (i in 0 until ja.length()) emb[i] = ja.getDouble(i).toFloat()
        return emb
    }

    companion object {
        /** Compute cosine similarity between two embeddings
         * Option 1 — Cosine Similarity
         * Measures how similar two embeddings are.
         * 1.0 → identical faces   ,  0.0 → completely different faces
         * Usually threshold = 0.8–0.9 for “same person”.
         */
        fun cosineSimilarity(a: FloatArray, b: FloatArray): Float {
            var dot = 0f
            var na = 0f
            var nb = 0f
            for (i in a.indices) {
                dot += a[i] * b[i]
                na += a[i] * a[i]
                nb += b[i] * b[i]
            }
            if (na == 0f || nb == 0f) return 0f
            return dot / (sqrt(na) * sqrt(nb))
        }
    }

    //Option 2 — Euclidean Distance ,Measures the distance between two faces.
    fun compareEmbeddings(emb1: FloatArray, emb2: FloatArray): Float {
        var sum = 0f
        for (i in emb1.indices) {
            val diff = emb1[i] - emb2[i]
            sum += diff * diff
        }
        return sqrt(sum)
    }

}

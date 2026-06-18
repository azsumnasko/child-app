package com.childhelper.app.child.detection

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer

/**
 * TensorFlow Lite runner for cry detection inference.
 *
 * To use a real model, place `cry_detect_model.tflite` in `res/raw/`
 * and replace the stub return values with actual LiteRT Interpreter calls.
 *
 * Model requirements:
 * - Input:  float32[1, 32000]  (2 seconds @ 16kHz mono audio)
 * - Output: float32[1, 2]      (cry probability, no-cry probability)
 */
class TfliteRunner(
    private val context: Context,
    private val modelPath: String = "cry_detect_model.tflite"
) : AutoCloseable {

    var inputShape: IntArray = intArrayOf(1, 32000)
        private set

    var outputShape: IntArray = intArrayOf(1, 2)
        private set

    /**
     * Attempt to load the model from res/raw. If it exists, input/output
     * shapes are read from the model. If not, defaults are used.
     */
    init {
        try {
            val resourceId = context.resources.getIdentifier(
                modelPath.removeSuffix(".tflite"), "raw", context.packageName
            )
            if (resourceId != 0) {
                val model = loadModelBuffer(resourceId)
                if (model != null) {
                    // LiteRT Interpreter reads input/output shapes from model metadata
                    // val interpreter = com.google.ai.edge.litert.Interpreter(model)
                    // inputShape = interpreter.getInputTensor(0).shape()
                    // outputShape = interpreter.getOutputTensor(0).shape()
                    // interpreter.close()
                }
            }
        } catch (_: Exception) {
            // Model file exists but couldn't be parsed — use defaults
        }
    }

    private fun loadModelBuffer(resourceId: Int): java.nio.MappedByteBuffer? {
        return try {
            val afd = context.resources.openRawResourceFd(resourceId) ?: return null
            java.io.FileInputStream(afd.fileDescriptor).channel.use { channel ->
                channel.map(java.nio.channels.FileChannel.MapMode.READ_ONLY, afd.startOffset, afd.length)
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Run inference on raw audio buffer. Returns [cryProbability, noneProbability].
     * Stub returns [0f, 0f] until a real model is provided.
     */
    suspend fun runInference(inputBuffer: ByteBuffer): FloatArray = withContext(Dispatchers.Default) {
        // Replace with: interpreter.run(inputBuffer, outputArray)
        floatArrayOf(0f, 0f)
    }

    /**
     * Run inference on float array. Returns [cryProbability, noneProbability].
     */
    suspend fun runInference(input: FloatArray): FloatArray = withContext(Dispatchers.Default) {
        floatArrayOf(0f, 0f)
    }

    fun getExpectedInputSize(): Int = if (inputShape.size >= 2) inputShape[1] else 32000

    fun isModelLoaded(): Boolean = false

    override fun close() {}
}

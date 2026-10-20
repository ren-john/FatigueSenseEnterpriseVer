package com.example.fatiguesense.enterprise.ml
import android.graphics.Bitmap
import android.graphics.Rect
import org.tensorflow.lite.Interpreter
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.hypot

/** Original 12-point crop, decoding and ratios from commit c310954. */
object LegacyLandmarkModel {
    const val ASSET = "landmark_model_float32(ver3).tflite"
    fun ratio(p: List<LandmarkModel.Point>, s: Int): Float {
        val h = hypot(p[s].x - p[s+1].x, p[s].y - p[s+1].y)
        val v = hypot(p[s+2].x - p[s+3].x, p[s+2].y - p[s+3].y)
        return if (h < 1e-6f) 0f else v / h
    }
    fun ear(p: List<LandmarkModel.Point>) = (ratio(p, 0) + ratio(p, 4)) / 2f
    fun mar(p: List<LandmarkModel.Point>) = ratio(p, 8)
    fun infer(bitmap: Bitmap, boundingBox: Rect, interpreter: Interpreter): List<LandmarkModel.Point> {
            // Apply offsets and scale, matching Python test implementation
            val w = boundingBox.width()
            val h = boundingBox.height()

            val offsetX = 0.0f
            val offsetY = 0.10f
            val scalePct = 1.0f

            val size = (kotlin.math.max(w, h) * scalePct).toInt()
            val cx = boundingBox.left + w / 2 + (w * offsetX).toInt()
            val cy = boundingBox.top  + h / 2 + (h * offsetY).toInt()

            val x1 = (cx - size / 2).coerceAtLeast(0)
            val y1 = (cy - size / 2).coerceAtLeast(0)
            val x2 = (x1 + size).coerceAtMost(bitmap.width)
            val y2 = (y1 + size).coerceAtMost(bitmap.height)

            val cropWidth  = x2 - x1
            val cropHeight = y2 - y1

            require(cropWidth > 0 && cropHeight > 0)
            val faceCrop    = Bitmap.createBitmap(bitmap, x1, y1, cropWidth, cropHeight)
            val resizedFace = Bitmap.createScaledBitmap(faceCrop, 128, 128, true)

            // Build float32 RGB input buffer
            val inputBuffer = ByteBuffer.allocateDirect(128 * 128 * 3 * 4)
            inputBuffer.order(ByteOrder.nativeOrder())
            val intValues = IntArray(128 * 128)
            resizedFace.getPixels(intValues, 0, 128, 0, 0, 128, 128)
            for (pixel in intValues) {
                inputBuffer.putFloat(((pixel shr 16) and 0xFF) / 255.0f)
                inputBuffer.putFloat(((pixel shr 8)  and 0xFF) / 255.0f)
                inputBuffer.putFloat(( pixel          and 0xFF) / 255.0f)
            }
            inputBuffer.rewind()

            // Run inference → [1, 24] output (12 points × 2 coords)
            val outputArray = Array(1) { FloatArray(24) }
            try { interpreter.run(inputBuffer, outputArray) } finally {
                if (resizedFace !== faceCrop && resizedFace !== bitmap) resizedFace.recycle()
                if (faceCrop !== bitmap) faceCrop.recycle()
            }
            require(outputArray[0].all { it.isFinite() })
            // Decode landmark points back to original bitmap coordinates
            val flat = outputArray[0]
            val pts  = mutableListOf<LandmarkModel.Point>()
            for (i in 0 until 12) {
                val xOut = flat[i * 2]
                val yOut = flat[i * 2 + 1]
                val xRatio = if (xOut > 2.0f) xOut / 128 else xOut
                val yRatio = if (yOut > 2.0f) yOut / 128 else yOut
                pts.add(LandmarkModel.Point(x1 + xRatio * cropWidth, y1 + yRatio * cropHeight))
            }

        return pts
    }
}

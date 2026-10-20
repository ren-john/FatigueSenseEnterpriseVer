package com.example.fatiguesense.enterprise.ml

import java.nio.ByteBuffer
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.roundToInt

/** Contract of coordinates32_android_fp32_float32.tflite (iBUG indices 36..67). */
object LandmarkModel {
    const val ASSET = "coordinates32_android_fp32_float32.tflite"
    const val SIZE = 192
    const val POINT_COUNT = 32
    const val INPUT_BYTES = 3 * SIZE * SIZE * 4

    data class Point(val x: Float, val y: Float)
    enum class EyeSelection(val label: String) {
        NONE("EYES UNAVAILABLE"), BOTH("BOTH EYES"),
        RIGHT("RIGHT EYE (GEOMETRY)"), LEFT("LEFT EYE (GEOMETRY)")
    }
    data class EyeMeasurement(val ear: Float, val width: Float, val valid: Boolean)
    data class SelectedEye(val ear: Float, val selection: EyeSelection) {
        val valid: Boolean get() = selection != EyeSelection.NONE && ear.isFinite()
    }
    data class FrameEligibility(
        val runLandmarks: Boolean, val headTurned: Boolean, val faceClipped: Boolean,
    ) {
        val calibrate: Boolean get() = runLandmarks && !headTurned && !faceClipped
    }

    /** Pose and edge margins restrict baseline collection, never valid-box inference. */
    fun frameEligibility(left: Float, top: Float, right: Float, bottom: Float,
                         width: Int, height: Int, yaw: Float, pitch: Float, roll: Float): FrameEligibility {
        val validBox = listOf(left, top, right, bottom).all { it.isFinite() } &&
            right > left && bottom > top && right > 0 && bottom > 0 && left < width && top < height
        val turned = listOf(yaw, pitch, roll).any { !it.isFinite() || kotlin.math.abs(it) > 15f }
        val clipped = left < width * .02f || top < height * .02f ||
            right > width * .98f || bottom > height * .98f
        return FrameEligibility(validBox, turned, clipped)
    }
    data class Crop(val centerX: Float, val centerY: Float, val side: Float) {
        fun decode(x: Float, y: Float) = Point(
            centerX + (x - 0.5f) * side,
            centerY + (y - 0.5f) * side,
        )
    }

    fun crop(left: Float, top: Float, right: Float, bottom: Float): Crop {
        require(right > left && bottom > top)
        return Crop((left + right) / 2f, (top + bottom) / 2f,
            max(right - left, bottom - top) * 1.25f)
    }

    /**
     * Inverse-sample the notebook's affine crop at pixel centers 0..191.
     * Bilinear RGB sampling uses black outside the image, never clips the crop.
     * OpenCV INTER_LINEAR uses 1/32-pixel interpolation weights and uint8 output.
     * Write planar R, G, B float32 / 255 (NCHW), not interleaved RGB.
     */
    fun writeInput(pixels: IntArray, width: Int, height: Int, crop: Crop, buffer: ByteBuffer) {
        require(width > 0 && height > 0 && pixels.size >= width * height)
        require(buffer.capacity() >= INPUT_BYTES)
        val plane = SIZE * SIZE
        fun pixel(x: Int, y: Int): Int =
            if (x in 0 until width && y in 0 until height) pixels[y * width + x] else 0

        buffer.clear()
        for (y in 0 until SIZE) {
            val sy = ((crop.centerY - crop.side / 2f + y * crop.side / (SIZE - 1)) * 32f).roundToInt() / 32f
            val y0 = floor(sy).toInt()
            val fy = sy - y0
            for (x in 0 until SIZE) {
                val sx = ((crop.centerX - crop.side / 2f + x * crop.side / (SIZE - 1)) * 32f).roundToInt() / 32f
                val x0 = floor(sx).toInt()
                val fx = sx - x0
                val a = pixel(x0, y0)
                val b = pixel(x0 + 1, y0)
                val c = pixel(x0, y0 + 1)
                val d = pixel(x0 + 1, y0 + 1)
                for (channel in 0..2) {
                    val shift = (2 - channel) * 8
                    val top = ((a ushr shift) and 255) * (1f - fx) + ((b ushr shift) and 255) * fx
                    val bottom = ((c ushr shift) and 255) * (1f - fx) + ((d ushr shift) and 255) * fx
                    val value = (top * (1f - fy) + bottom * fy).roundToInt().coerceIn(0, 255) / 255f
                    buffer.putFloat((channel * plane + y * SIZE + x) * 4, value)
                }
            }
        }
        buffer.rewind()
    }

    fun decode(output: Array<FloatArray>, crop: Crop): List<Point> {
        require(output.size == POINT_COUNT && output.all { it.size == 2 && it.all(Float::isFinite) })
        return output.map { crop.decode(it[0], it[1]) }
    }

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /** Six-point EAR: mean of two eyelid separations divided by corner width. */
    fun eyeAspectRatio(points: List<Point>, start: Int): Float {
        val width = distance(points[start], points[start + 3])
        return if (width < 1e-6f) 0f else
            (distance(points[start + 1], points[start + 5]) +
                distance(points[start + 2], points[start + 4])) / (2f * width)
    }

    /** Python webcam MAR: three inner-lip separations / (3 * inner corner width). */
    fun mouthAspectRatio(points: List<Point>): Float {
        val width = distance(points[24], points[28])
        return if (width < 1e-6f) 0f else
            (distance(points[25], points[31]) + distance(points[26], points[30]) +
                distance(points[27], points[29])) / (3f * width)
    }

    fun inFrame(points: List<Point>, width: Int, height: Int): Boolean =
        points.all { it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.x < width && it.y >= 0 && it.y < height }

    /** Same heuristic as Python assess_eye; this does not estimate visibility. */
    fun assessEye(cropPoints: List<Point>, originalPoints: List<Point>, start: Int,
                  width: Int, height: Int): EyeMeasurement {
        val eye = cropPoints.subList(start, start + 6)
        val ear = eyeAspectRatio(cropPoints, start)
        val eyeWidth = distance(eye[0], eye[3])
        var valid = ear.isFinite() && eyeWidth >= 3f && ear <= .8f &&
            eye.all { it.x in 0f..191f && it.y in 0f..191f } &&
            inFrame(originalPoints.subList(start, start + 6), width, height)
        if (valid) {
            val dx = eye[3].x - eye[0].x
            val dy = eye[3].y - eye[0].y
            val along = eye.map { ((it.x - eye[0].x) * dx + (it.y - eye[0].y) * dy) / (eyeWidth * eyeWidth) }
            valid = along.all { it in -.15f..1.15f } &&
                along[1] <= along[2] + .1f && along[5] <= along[4] + .1f
        }
        return EyeMeasurement(ear, eyeWidth, valid)
    }

    /** Choose by valid geometry and width, never by which EAR is more open. */
    fun selectEyes(right: EyeMeasurement, left: EyeMeasurement): SelectedEye = when {
        !right.valid && !left.valid -> SelectedEye(Float.NaN, EyeSelection.NONE)
        !left.valid -> SelectedEye(right.ear, EyeSelection.RIGHT)
        !right.valid -> SelectedEye(left.ear, EyeSelection.LEFT)
        minOf(right.width, left.width) / maxOf(right.width, left.width) < .60f ->
            if (right.width >= left.width) SelectedEye(right.ear, EyeSelection.RIGHT)
            else SelectedEye(left.ear, EyeSelection.LEFT)
        else -> SelectedEye((right.ear + left.ear) / 2f, EyeSelection.BOTH)
    }

    fun validMouth(cropPoints: List<Point>, originalPoints: List<Point>, width: Int, height: Int): Boolean =
        distance(cropPoints[24], cropPoints[28]) >= 3f &&
            inFrame(originalPoints.subList(24, 32), width, height)
}

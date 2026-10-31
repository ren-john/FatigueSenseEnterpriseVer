package com.example.fatiguesense.enterprise.ml

import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.atan2
import kotlin.math.sqrt

/** Shared geometry for the 32 eye/lip points selected from the MediaPipe mesh. */
object LandmarkModel {
    const val SIZE = 192
    const val POINT_COUNT = 32

    data class Point(val x: Float, val y: Float)
    data class FaceBounds(val left: Float, val top: Float, val right: Float, val bottom: Float) {
        val area: Float get() = (right - left) * (bottom - top)
    }
    data class FaceGeometry(val bounds: FaceBounds, val points: List<Point>, val cropPoints: List<Point>)
    data class HeadPose(val yaw: Float, val pitch: Float, val roll: Float)

    /** Decode normalized full-frame coordinates before measuring on rectangular frames. */
    fun mediaPipeGeometry(mesh: List<Point>, width: Int, height: Int): FaceGeometry {
        require(width > 0 && height > 0 && mesh.size >= 468)
        val pixels = mesh.take(468).map {
            require(it.x.isFinite() && it.y.isFinite()) { "Non-finite MediaPipe mesh" }
            Point(it.x * width, it.y * height)
        }
        val bounds = FaceBounds(pixels.minOf { it.x }, pixels.minOf { it.y },
            pixels.maxOf { it.x }, pixels.maxOf { it.y })
        val reference = crop(bounds.left, bounds.top, bounds.right, bounds.bottom)
        val points = selectMediaPipePoints(pixels)
        // This virtual square preserves the established geometry validity thresholds.
        // MediaPipe performs its own detection/cropping directly on the full frame.
        val cropPoints = points.map {
            Point(((it.x - reference.centerX) / reference.side + .5f) * (SIZE - 1),
                ((it.y - reference.centerY) / reference.side + .5f) * (SIZE - 1))
        }
        return FaceGeometry(bounds, points, cropPoints)
    }

    /** MediaPipe's column-major canonical-to-camera transform; R = Rz * Ry * Rx. */
    fun mediaPipeHeadPose(matrix: FloatArray?): HeadPose {
        val unavailable = HeadPose(Float.NaN, Float.NaN, Float.NaN)
        if (matrix == null || matrix.size != 16 || matrix.any { !it.isFinite() }) return unavailable
        val scales = (0..2).map { column ->
            sqrt((0..2).sumOf { row -> matrix[column * 4 + row].toDouble().let { it * it } })
        }
        if (scales.any { it < 1e-6 }) return unavailable
        fun r(row: Int, column: Int) = matrix[column * 4 + row] / scales[column]
        fun degrees(radians: Double) = Math.toDegrees(radians).toFloat()
        return HeadPose(
            yaw = degrees(atan2(-r(2, 0), kotlin.math.hypot(r(0, 0), r(1, 0)))),
            pitch = degrees(atan2(r(2, 1), r(2, 2))),
            roll = degrees(atan2(r(1, 0), r(0, 0))),
        )
    }

    // MediaPipe face mesh topology, ordered like iBUG points 36..67.
    // https://github.com/google-ai-edge/mediapipe/blob/master/mediapipe/python/solutions/face_mesh_connections.py
    private val meshIndices = intArrayOf(
        33, 160, 158, 133, 153, 144, // right eye: corners and paired eyelids
        362, 385, 387, 263, 373, 380, // left eye
        61, 40, 37, 0, 267, 270, 291, 321, 314, 17, 84, 91, // outer lip
        78, 81, 13, 311, 308, 402, 14, 178, // inner lip
    )

    fun selectMediaPipePoints(mesh: List<Point>): List<Point> {
        require(mesh.size >= 468) { "Incomplete MediaPipe face mesh: ${mesh.size}" }
        return meshIndices.map { mesh[it] }.also { selected ->
            require(selected.all { it.x.isFinite() && it.y.isFinite() }) {
                "Non-finite MediaPipe coordinates"
            }
        }
    }
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

    private fun distance(a: Point, b: Point) = hypot(a.x - b.x, a.y - b.y)

    /** Six-point EAR: mean of two eyelid separations divided by corner width. */
    fun eyeAspectRatio(points: List<Point>, start: Int): Float {
        val width = distance(points[start], points[start + 3])
        return if (width < 1e-6f) 0f else
            (distance(points[start + 1], points[start + 5]) +
                distance(points[start + 2], points[start + 4])) / (2f * width)
    }

    /** MAR: three inner-lip separations / (3 * inner corner width). */
    fun mouthAspectRatio(points: List<Point>): Float {
        val width = distance(points[24], points[28])
        return if (width < 1e-6f) 0f else
            (distance(points[25], points[31]) + distance(points[26], points[30]) +
                distance(points[27], points[29])) / (3f * width)
    }

    fun inFrame(points: List<Point>, width: Int, height: Int): Boolean =
        points.all { it.x.isFinite() && it.y.isFinite() && it.x >= 0 && it.x < width && it.y >= 0 && it.y < height }

    /** Geometry heuristic; this does not estimate visibility. */
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

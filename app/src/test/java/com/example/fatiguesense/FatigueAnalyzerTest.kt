package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.LandmarkModel
import com.example.fatiguesense.enterprise.ml.LandmarkModel.Point
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test

class FatigueAnalyzerTest {
    @Test fun eyeRatioUsesBothEyelidPairsAndCorrectEyeOffset() {
        val points = MutableList(32) { Point(0f, 0f) }
        val eye = listOf(Point(0f, 0f), Point(1f, -1f), Point(3f, -2f),
            Point(4f, 0f), Point(3f, 2f), Point(1f, 1f))
        eye.forEachIndexed { i, p -> points[i + 6] = p }
        assertEquals(0.75f, LandmarkModel.eyeAspectRatio(points, 6), 1e-6f)
        assertEquals(0f, LandmarkModel.eyeAspectRatio(points, 0), 1e-6f)
    }

    @Test fun mouthRatioMatchesPythonInnerLipFormula() {
        val points = MutableList(32) { Point(99f, 99f) }
        points[24] = Point(0f, 0f)
        points[28] = Point(4f, 0f)
        points[25] = Point(1f, 0f)
        points[31] = Point(1f, 1f)
        points[26] = Point(2f, 0f)
        points[30] = Point(2f, 2f)
        points[27] = Point(3f, 0f)
        points[29] = Point(3f, 3f)
        assertEquals(0.5f, LandmarkModel.mouthAspectRatio(points), 1e-6f)
        points[28] = points[24]
        assertEquals(0f, LandmarkModel.mouthAspectRatio(points), 1e-6f)
    }

    @Test fun sideFacesStillRunLandmarksButDoNotCalibrate() {
        for (yaw in listOf(-80f, -35f, 35f, 80f)) {
            val policy = LandmarkModel.frameEligibility(100f, 80f, 300f, 300f, 640, 480, yaw, 0f, 0f)
            assertTrue(policy.runLandmarks)
            assertTrue(policy.headTurned)
            assertFalse(policy.calibrate)
        }
        val frontal = LandmarkModel.frameEligibility(100f, 80f, 300f, 300f, 640, 480, 0f, 0f, 0f)
        assertTrue(frontal.calibrate)
    }

    @Test fun clippedFacesUsePaddedInferenceButDoNotCalibrate() {
        val policy = LandmarkModel.frameEligibility(-20f, 10f, 150f, 200f, 640, 480, 0f, 0f, 0f)
        assertTrue(policy.runLandmarks)
        assertTrue(policy.faceClipped)
        assertFalse(policy.calibrate)
        assertFalse(LandmarkModel.frameEligibility(-80f, 10f, -20f, 200f, 640, 480, 0f, 0f, 0f).runLandmarks)
    }

    @Test fun profileFallbackUsesWiderEyeEvenWhenItIsClosed() {
        val chosen = LandmarkModel.selectEyes(
            LandmarkModel.EyeMeasurement(0f, 20f, true),
            LandmarkModel.EyeMeasurement(.5f, 6f, true),
        )
        assertTrue(chosen.valid)
        assertEquals(LandmarkModel.EyeSelection.RIGHT, chosen.selection)
        assertEquals(0f, chosen.ear, 0f)
        val mirrored = LandmarkModel.selectEyes(
            LandmarkModel.EyeMeasurement(.5f, 6f, true),
            LandmarkModel.EyeMeasurement(.1f, 20f, true),
        )
        assertEquals(LandmarkModel.EyeSelection.LEFT, mirrored.selection)
        assertEquals(.1f, mirrored.ear, 0f)
    }

    @Test fun invalidEyeIsExcludedAndNoEyesMeansUnavailable() {
        val invalid = LandmarkModel.EyeMeasurement(0f, 0f, false)
        val valid = LandmarkModel.EyeMeasurement(.2f, 10f, true)
        assertEquals(LandmarkModel.EyeSelection.LEFT, LandmarkModel.selectEyes(invalid, valid).selection)
        assertEquals(LandmarkModel.EyeSelection.RIGHT, LandmarkModel.selectEyes(valid, invalid).selection)
        assertFalse(LandmarkModel.selectEyes(invalid, invalid).valid)
    }

    @Test fun comparableEyesAreAveragedAtPythonWidthBoundary() {
        val selected = LandmarkModel.selectEyes(
            LandmarkModel.EyeMeasurement(.2f, 10f, true),
            LandmarkModel.EyeMeasurement(.4f, 6f, true),
        )
        assertEquals(LandmarkModel.EyeSelection.BOTH, selected.selection)
        assertEquals(.3f, selected.ear, 1e-6f)
    }

    @Test fun closedEyeGeometryRemainsEligibleButOffImageEyeDoesNot() {
        val points = MutableList(32) { Point(0f, 0f) }
        val closed = listOf(Point(10f, 20f), Point(13f, 20f), Point(17f, 20f),
            Point(20f, 20f), Point(17f, 20f), Point(13f, 20f))
        closed.forEachIndexed { i, point -> points[i] = point }
        val measured = LandmarkModel.assessEye(points, points, 0, 640, 480)
        assertTrue(measured.valid)
        assertEquals(0f, measured.ear, 0f)
        val original = points.toMutableList().apply { this[0] = Point(-1f, 20f) }
        assertFalse(LandmarkModel.assessEye(points, original, 0, 640, 480).valid)
    }

    @Test fun paddedCropAndInverseMappingMatchNotebook() {
        val crop = LandmarkModel.crop(10f, 20f, 90f, 60f)
        assertEquals(100f, crop.side, 0f)
        assertEquals(Point(0f, -10f), crop.decode(0f, 0f))
        assertEquals(Point(50f, 40f), crop.decode(0.5f, 0.5f))
        assertEquals(Point(100f, 90f), crop.decode(1f, 1f))
    }

    @Test fun inputUsesPlanarRgbAndZeroPaddingAtUnclippedCropEdges() {
        val buffer = ByteBuffer.allocateDirect(LandmarkModel.INPUT_BYTES).order(ByteOrder.nativeOrder())
        val pixels = IntArray(16 * 16) { 0xFF804020.toInt() }
        LandmarkModel.writeInput(pixels, 16, 16, LandmarkModel.crop(0f, 0f, 16f, 16f), buffer)
        val plane = 192 * 192
        val center = 96 * 192 + 96
        assertEquals(128f / 255, buffer.getFloat(center * 4), 1e-6f)
        assertEquals(64f / 255, buffer.getFloat((plane + center) * 4), 1e-6f)
        assertEquals(32f / 255, buffer.getFloat((2 * plane + center) * 4), 1e-6f)
        assertEquals(0f, buffer.getFloat(0), 0f)
        assertEquals(0f, buffer.getFloat((3 * plane - 1) * 4), 0f)
        assertEquals(0, buffer.position())
    }

    @Test fun bilinearSamplingUsesPixelCentersRatherThanResizeEdges() {
        val buffer = ByteBuffer.allocateDirect(LandmarkModel.INPUT_BYTES).order(ByteOrder.nativeOrder())
        // First output pixel samples (0.5, 0.5), the mean of four source pixels.
        LandmarkModel.writeInput(intArrayOf(0xFFFF0000.toInt(), 0, 0, 0), 2, 2,
            LandmarkModel.Crop(1f, 1f, 1f), buffer)
        assertEquals(64f / 255, buffer.getFloat(0), 1e-6f)
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteCoordinatesAreRejected() {
        val output = Array(32) { floatArrayOf(0.5f, 0.5f) }
        output[5][0] = Float.NaN
        LandmarkModel.decode(output, LandmarkModel.Crop(10f, 10f, 20f))
    }

    @Test fun affineSamplingMatchesOpenCvReferenceIncludingPartialPadding() {
        val pixels = IntArray(7 * 9) { i ->
            val x = i % 7
            val y = i / 7
            (255 shl 24) or (((x * 31 + y * 7) % 256) shl 16) or
                (((x * 13 + y * 23) % 256) shl 8) or ((x * 3 + y * 41) % 256)
        }
        val buffer = ByteBuffer.allocateDirect(LandmarkModel.INPUT_BYTES).order(ByteOrder.nativeOrder())
        LandmarkModel.writeInput(pixels, 7, 9, LandmarkModel.crop(0f, 1f, 7f, 8f), buffer)
        // Golden RGB samples from cv2.warpAffine using the notebook's crop_image.
        val reference = arrayOf(
            intArrayOf(0, 0, 0, 0, 1), intArrayOf(19, 10, 4, 14, 24),
            intArrayOf(40, 30, 41, 47, 64), intArrayOf(80, 60, 106, 102, 126),
            intArrayOf(95, 95, 139, 148, 194), intArrayOf(110, 120, 168, 183, 83),
            intArrayOf(140, 150, 220, 233, 48), intArrayOf(165, 170, 75, 9, 27),
            intArrayOf(191, 191, 0, 0, 0),
        )
        reference.forEach { sample ->
            for (channel in 0..2) {
                val offset = (channel * 192 * 192 + sample[1] * 192 + sample[0]) * 4
                assertEquals(sample[channel + 2] / 255f, buffer.getFloat(offset), 1f / 255f)
            }
        }
    }
}

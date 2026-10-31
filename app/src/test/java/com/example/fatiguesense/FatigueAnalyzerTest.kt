package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.LandmarkModel
import com.example.fatiguesense.enterprise.ml.LandmarkModel.Point
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

    @Test fun clippedFacesContinueMonitoringButDoNotCalibrate() {
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


    @Test fun mediaPipeMeshMapsBothEyesAndInnerLipsInRatioOrder() {
        val mesh = List(478) { Point(it / 478f, (478 - it) / 478f) }
        val points = LandmarkModel.selectMediaPipePoints(mesh)
        assertEquals(32, points.size)
        assertEquals(mesh[33], points[0])
        assertEquals(mesh[133], points[3])
        assertEquals(mesh[362], points[6])
        assertEquals(mesh[263], points[9])
        assertEquals(mesh[61], points[12])
        assertEquals(mesh[291], points[18])
        val lips = listOf(78, 81, 13, 311, 308, 402, 14, 178).map { mesh[it] }
        assertEquals(lips, points.subList(24, 32))
    }

    @Test fun mediaPipeRatiosUsePixelsFromSquareCropOnRectangularFrame() {
        val mesh = MutableList(478) { Point(.5f, .5f) }
        listOf(33, 160, 158, 133, 153, 144).zip(listOf(
            Point(.2f, .4f), Point(.3f, .35f), Point(.5f, .35f),
            Point(.6f, .4f), Point(.5f, .45f), Point(.3f, .45f),
        )).forEach { (index, point) -> mesh[index] = point }
        val points = LandmarkModel.selectMediaPipePoints(mesh)
        val crop = LandmarkModel.Crop(320f, 240f, 200f)
        val pixels = points.map { crop.decode(it.x, it.y) }
        assertEquals(.25f, LandmarkModel.eyeAspectRatio(pixels, 0), 1e-6f)
        assertEquals(Point(260f, 220f), pixels[0])
    }

    @Test(expected = IllegalArgumentException::class)
    fun incompleteMediaPipeMeshIsRejected() {
        LandmarkModel.selectMediaPipePoints(List(100) { Point(.5f, .5f) })
    }

    @Test(expected = IllegalArgumentException::class)
    fun nonFiniteMediaPipePointsAreRejected() {
        val mesh = MutableList(478) { Point(.5f, .5f) }
        mesh[33] = Point(Float.NaN, .5f)
        LandmarkModel.selectMediaPipePoints(mesh)
    }

    @Test fun mediaPipeSettingsDoNotReuseCustomModelCalibrationKeys() {
        val key = com.example.fatiguesense.enterprise.ml.ModelProfile.preferenceKey("adaptive_baseline_ear")
        assertEquals("adaptive_baseline_ear_mediapipe_v1", key)
        assertNotEquals("adaptive_baseline_ear_coordinates32_v1", key)
        assertNotEquals("adaptive_baseline_ear", key)
    }

    @Test fun fullFrameMediaPipeGeometryUsesOriginalPixelAspectRatio() {
        val mesh = MutableList(478) { Point(.5f, .5f) }
        mesh[10] = Point(.1f, .1f)
        mesh[152] = Point(.9f, .9f)
        listOf(33, 160, 158, 133, 153, 144).zip(listOf(
            Point(.2f, .4f), Point(.3f, .35f), Point(.5f, .35f),
            Point(.6f, .4f), Point(.5f, .45f), Point(.3f, .45f),
        )).forEach { (index, point) -> mesh[index] = point }
        val face = LandmarkModel.mediaPipeGeometry(mesh, 800, 400)
        assertEquals(LandmarkModel.FaceBounds(80f, 40f, 720f, 360f), face.bounds)
        assertEquals(Point(160f, 160f), face.points[0])
        // 40-pixel eyelid gap / 320-pixel eye width = 0.125, not 0.25.
        assertEquals(.125f, LandmarkModel.eyeAspectRatio(face.points, 0), 1e-6f)
        assertEquals(.125f, LandmarkModel.eyeAspectRatio(face.cropPoints, 0), 1e-6f)
    }

    @Test fun mediaPipeBoundsPreserveOutOfFrameEdgesForCalibrationGate() {
        val mesh = MutableList(478) { Point(.5f, .5f) }
        mesh[10] = Point(-.1f, .1f)
        mesh[152] = Point(.9f, 1.1f)
        val face = LandmarkModel.mediaPipeGeometry(mesh, 640, 480)
        assertEquals(-64f, face.bounds.left, 1e-6f)
        assertEquals(528f, face.bounds.bottom, 1e-4f)
        val policy = with(face.bounds) {
            LandmarkModel.frameEligibility(left, top, right, bottom, 640, 480, 0f, 0f, 0f)
        }
        assertTrue(policy.faceClipped)
        assertFalse(policy.calibrate)
    }

    @Test fun mediaPipeHeadPoseUsesColumnMajorRotationAndIgnoresScaleAndTranslation() {
        // Rz(-25 degrees) * Ry(30 degrees) * Rx(20 degrees), uniformly scaled by 2.
        val matrix = floatArrayOf(
            1.5697711f, -.7319963f, -1f, 0f,
            1.104238f, 1.5587575f, .5923963f, 0f,
            .5625628f, -1.0170823f, 1.6275954f, 0f,
            10f, 20f, -50f, 1f,
        )
        val pose = LandmarkModel.mediaPipeHeadPose(matrix)
        assertEquals(30f, pose.yaw, 1e-3f)
        assertEquals(20f, pose.pitch, 1e-3f)
        assertEquals(-25f, pose.roll, 1e-3f)
    }

    @Test fun missingOrDegenerateMediaPipePosePausesCalibration() {
        for (matrix in listOf(null, FloatArray(16), FloatArray(3), FloatArray(16) { Float.NaN })) {
            val pose = LandmarkModel.mediaPipeHeadPose(matrix)
            val policy = LandmarkModel.frameEligibility(100f, 80f, 300f, 300f,
                640, 480, pose.yaw, pose.pitch, pose.roll)
            assertTrue(policy.runLandmarks)
            assertFalse(policy.calibrate)
        }
    }
}

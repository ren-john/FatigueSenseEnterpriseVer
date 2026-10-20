package com.example.fatiguesense.enterprise
import com.example.fatiguesense.enterprise.ml.LegacyLandmarkModel
import com.example.fatiguesense.enterprise.ml.LandmarkModel.Point
import org.junit.Assert.assertEquals
import org.junit.Test

class LegacyLandmarkModelTest {
    @Test fun originalRatiosUseFourPointsAndAverageBothEyes() {
        val p = listOf(
            Point(0f,0f), Point(4f,0f), Point(2f,-1f), Point(2f,1f),
            Point(10f,0f), Point(14f,0f), Point(12f,-0.5f), Point(12f,0.5f),
            Point(0f,10f), Point(8f,10f), Point(4f,8f), Point(4f,12f))
        assertEquals(0.375f, LegacyLandmarkModel.ear(p), 1e-6f)
        assertEquals(0.5f, LegacyLandmarkModel.mar(p), 1e-6f)
    }
    @Test fun collapsedCornersReturnZero() {
        val p = List(12) { Point(0f, 0f) }
        assertEquals(0f, LegacyLandmarkModel.ear(p), 0f)
        assertEquals(0f, LegacyLandmarkModel.mar(p), 0f)
    }
}

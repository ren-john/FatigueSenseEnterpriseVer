package com.example.fatiguesense.enterprise

import android.graphics.Bitmap
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fatiguesense.enterprise.ml.MediaPipeLandmarker
import com.example.fatiguesense.enterprise.ml.FatigueAnalyzer
import com.example.fatiguesense.enterprise.ml.FrameQuality
import kotlinx.coroutines.runBlocking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LandmarkModelInstrumentedTest {
    @Test fun bundledMediaPipeModelDetectsRealFaceAndRejectsBlankFrame() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // Retained real validation crop, planar RGB float32 /255, 192 x 192.
        val bytes = instrumentation.context.assets.open("landmark32_input.bin").use { it.readBytes() }
        val rgb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        val plane = 192 * 192
        val pixels = IntArray(plane) { i ->
            fun channel(offset: Int) = (rgb.get(i + offset) * 255f).toInt().coerceIn(0, 255)
            (255 shl 24) or (channel(0) shl 16) or (channel(plane) shl 8) or channel(plane * 2)
        }
        val face = Bitmap.createBitmap(pixels, 192, 192, Bitmap.Config.ARGB_8888)
        val blank = Bitmap.createBitmap(192, 192, Bitmap.Config.ARGB_8888)
        try {
            MediaPipeLandmarker(instrumentation.targetContext).use { landmarker ->
                val detection = landmarker.detect(face)
                assertNotNull("Official model should find the real validation face", detection)
                assertEquals(32, detection!!.points.size)
                assertTrue(detection.points.all { it.x.isFinite() && it.y.isFinite() })
                assertEquals(32, detection.cropPoints.size)
                assertTrue(detection.boundingBox.width() > 0 && detection.boundingBox.height() > 0)
                assertTrue(detection.pose.yaw.isFinite())
                assertTrue(detection.pose.pitch.isFinite())
                assertTrue(detection.pose.roll.isFinite())
                assertFalse("Inference must preserve the preview bitmap", face.isRecycled)
                assertNull(landmarker.detect(blank))
                assertFalse(blank.isRecycled)
            }
            val analyzer = FatigueAnalyzer(instrumentation.targetContext)
            try {
                runBlocking { analyzer.processFrame(face) }
                assertNotNull(analyzer.result.value.boundingBox)
                assertEquals(32, analyzer.result.value.landmarks.size)
                assertTrue(analyzer.result.value.earValid)
                assertTrue(analyzer.result.value.marValid)
                assertTrue(analyzer.result.value.headYaw.isFinite())
                runBlocking { analyzer.processFrame(blank) }
                assertEquals(FrameQuality.NO_FACE, analyzer.result.value.qualityStatus)
                assertNull(analyzer.result.value.boundingBox)
                assertNotNull("No-face frames must reach absence tracking", analyzer.lastObservation)
                assertFalse(analyzer.result.value.isFatigueAlert)
            } finally {
                analyzer.close()
            }
        } finally {
            face.recycle()
            blank.recycle()
        }
    }
}

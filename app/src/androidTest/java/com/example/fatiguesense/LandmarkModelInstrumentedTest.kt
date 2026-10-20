package com.example.fatiguesense.enterprise

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.example.fatiguesense.enterprise.ml.LandmarkModel
import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.tensorflow.lite.DataType
import org.tensorflow.lite.Interpreter

@RunWith(AndroidJUnit4::class)
class LandmarkModelInstrumentedTest {
    @Test fun legacyModelContractAndInference() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val bytes = context.assets.open(com.example.fatiguesense.enterprise.ml.LegacyLandmarkModel.ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes).rewind()
        Interpreter(model, Interpreter.Options().apply { numThreads = 4 }).use { interpreter ->
            assertArrayEquals(intArrayOf(1, 128, 128, 3), interpreter.getInputTensor(0).shape())
            assertArrayEquals(intArrayOf(1, 24), interpreter.getOutputTensor(0).shape())
            assertEquals(DataType.FLOAT32, interpreter.getInputTensor(0).dataType())
            assertEquals(DataType.FLOAT32, interpreter.getOutputTensor(0).dataType())
            val bitmap = android.graphics.Bitmap.createBitmap(256, 256, android.graphics.Bitmap.Config.ARGB_8888)
            try {
                val points = com.example.fatiguesense.enterprise.ml.LegacyLandmarkModel.infer(
                    bitmap, android.graphics.Rect(40, 40, 200, 200), interpreter)
                assertEquals(12, points.size)
                assertTrue(points.all { it.x.isFinite() && it.y.isFinite() })
            } finally { bitmap.recycle() }
        }
    }

    @Test fun bundledModelMatchesDesktopValidationPrediction() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bytes = instrumentation.targetContext.assets.open(LandmarkModel.ASSET).use { it.readBytes() }
        val model = ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder())
        model.put(bytes).rewind()
        val inputBytes = instrumentation.context.assets.open("landmark32_input.bin").use { it.readBytes() }
        val expectedBytes = instrumentation.context.assets.open("landmark32_expected.bin").use { it.readBytes() }
        val input = ByteBuffer.allocateDirect(inputBytes.size).order(ByteOrder.nativeOrder())
        input.put(inputBytes).rewind()
        val expected = ByteBuffer.wrap(expectedBytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
        Interpreter(model, Interpreter.Options().apply { numThreads = 4 }).use { interpreter ->
            assertArrayEquals(intArrayOf(1, 3, 192, 192), interpreter.getInputTensor(0).shape())
            assertArrayEquals(intArrayOf(1, 32, 2), interpreter.getOutputTensor(0).shape())
            assertEquals(DataType.FLOAT32, interpreter.getInputTensor(0).dataType())
            assertEquals(DataType.FLOAT32, interpreter.getOutputTensor(0).dataType())
            val output = Array(1) { Array(32) { FloatArray(2) } }
            interpreter.run(input, output)
            output[0].forEach { point -> point.forEach { value ->
                assertTrue(value.isFinite())
                assertEquals(expected.get(), value, 1e-4f)
            } }
        }
    }
}

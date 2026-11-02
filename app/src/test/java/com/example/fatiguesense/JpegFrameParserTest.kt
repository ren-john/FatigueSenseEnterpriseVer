package com.example.fatiguesense.enterprise

import com.example.fatiguesense.enterprise.ml.JpegFrameParser
import java.io.IOException
import org.junit.Assert.*
import org.junit.Test

class JpegFrameParserTest {
    private val jpeg = byteArrayOf(-1, -40, 1, 2, -1, 0, 3, -1, -39)

    @Test fun handlesMarkersSplitAtEveryPossibleReadBoundary() {
        for (split in 1 until jpeg.size) {
            val parser = JpegFrameParser()
            val frames = mutableListOf<ByteArray>()
            parser.consume(jpeg.copyOfRange(0, split), split) { data, size -> frames.add(data.copyOf(size)) }
            assertTrue(frames.isEmpty())
            val rest = jpeg.copyOfRange(split, jpeg.size)
            parser.consume(rest, rest.size) { data, size -> frames.add(data.copyOf(size)) }
            assertEquals(1, frames.size)
            assertArrayEquals(jpeg, frames.single())
        }
    }

    @Test fun readsMultipleFramesAndIgnoresMultipartHeaders() {
        val input = "--boundary\r\nContent-Type: image/jpeg\r\n\r\n".toByteArray() + jpeg +
            "\r\n--boundary\r\n".toByteArray() + jpeg
        val frames = mutableListOf<ByteArray>()
        JpegFrameParser().consume(input, input.size) { data, size -> frames.add(data.copyOf(size)) }
        assertEquals(2, frames.size)
        frames.forEach { assertArrayEquals(jpeg, it) }
    }

    @Test fun growsStorageForLargeFrames() {
        val large = byteArrayOf(-1, -40) + ByteArray(70_000) { 1 } + byteArrayOf(-1, -39)
        JpegFrameParser().consume(large, large.size) { data, size -> assertArrayEquals(large, data.copyOf(size)) }
    }

    @Test(expected = IOException::class) fun rejectsOversizeIncompleteFrame() {
        val input = byteArrayOf(-1, -40) + ByteArray(20)
        JpegFrameParser(maxFrameBytes = 10).consume(input, input.size) { _, _ -> fail("Incomplete frame") }
    }
}

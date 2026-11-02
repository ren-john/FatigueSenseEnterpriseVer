package com.example.fatiguesense.enterprise.ml

import java.io.IOException

/** Reuses JPEG storage; the callback must consume bytes before returning. */
internal class JpegFrameParser(private val maxFrameBytes: Int = 1024 * 1024) {
    private var bytes = ByteArray(minOf(32 * 1024, maxFrameBytes))
    private var size = 0
    private var previous = -1

    fun consume(input: ByteArray, count: Int, onFrame: (ByteArray, Int) -> Unit) {
        for (index in 0 until count) {
            val value = input[index].toInt() and 0xff
            if (size == 0) {
                if (previous == 0xff && value == 0xd8) {
                    append(0xff)
                    append(value)
                }
            } else {
                append(value)
                if (previous == 0xff && value == 0xd9) {
                    onFrame(bytes, size)
                    size = 0
                }
            }
            previous = value
        }
    }

    private fun append(value: Int) {
        if (size >= maxFrameBytes) throw IOException("JPEG exceeds $maxFrameBytes bytes")
        if (size == bytes.size) bytes = bytes.copyOf(minOf(bytes.size * 2, maxFrameBytes))
        bytes[size++] = value.toByte()
    }
}

package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ImageEmbeddingBlobCodecTest {
    @Test
    fun roundTripUsesLittleEndianFloat32Bits() {
        val values = FloatArray(ImageEmbeddingBlobCodec.DIMENSION) { index ->
            when (index) {
                0 -> 1.5f
                1 -> -2.25f
                2 -> 0f
                else -> index.toFloat() / 17f
            }
        }

        val blob = ImageEmbeddingBlobCodec.encode(values)
        val decoded = ImageEmbeddingBlobCodec.decode(blob)

        assertEquals(1024, blob.size)
        assertEquals(
            java.lang.Float.floatToRawIntBits(values[0]),
            ByteBuffer.wrap(blob).order(ByteOrder.LITTLE_ENDIAN).getInt(0),
        )
        values.forEachIndexed { index, value ->
            assertEquals(
                java.lang.Float.floatToRawIntBits(value),
                java.lang.Float.floatToRawIntBits(decoded[index]),
            )
        }
    }

    @Test
    fun rejectsInvalidDimensionAndNonFiniteValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageEmbeddingBlobCodec.encode(FloatArray(255))
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageEmbeddingBlobCodec.encode(FloatArray(256) { if (it == 7) Float.NaN else 1f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            ImageEmbeddingBlobCodec.encode(FloatArray(256) { if (it == 7) Float.POSITIVE_INFINITY else 1f })
        }
    }

    @Test
    fun rejectsInvalidBlobLengthAndNonFiniteBlobValues() {
        assertThrows(IllegalArgumentException::class.java) {
            ImageEmbeddingBlobCodec.decode(ByteArray(1023))
        }
        val blob = ByteBuffer.allocate(1024)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(256) { putFloat(if (it == 5) Float.NaN else 1f) } }
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            ImageEmbeddingBlobCodec.decode(blob)
        }
        assertTrue(ImageEmbeddingBlobCodec.decode(ImageEmbeddingBlobCodec.encode(FloatArray(256) { 1f })).all(Float::isFinite))
    }
}

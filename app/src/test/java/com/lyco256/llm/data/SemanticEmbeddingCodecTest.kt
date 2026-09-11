package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SemanticEmbeddingCodecTest {
    @Test
    fun roundTripUsesLittleEndianFloat32Bits() {
        val values = FloatArray(SemanticEmbeddingBlobCodec.DIMENSION) { index ->
            when (index) {
                0 -> 1.5f
                1 -> -2.25f
                2 -> 0f
                else -> index.toFloat() / 17f
            }
        }

        val blob = SemanticEmbeddingBlobCodec.encode(values)
        val decoded = SemanticEmbeddingBlobCodec.decode(blob)

        assertEquals(3072, blob.size)
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
            SemanticEmbeddingBlobCodec.encode(FloatArray(767))
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticEmbeddingBlobCodec.encode(FloatArray(768) { if (it == 7) Float.NaN else 1f })
        }
        assertThrows(IllegalArgumentException::class.java) {
            SemanticEmbeddingBlobCodec.encode(FloatArray(768) { if (it == 7) Float.POSITIVE_INFINITY else 1f })
        }
    }

    @Test
    fun rejectsInvalidBlobLengthAndNonFiniteBlobValues() {
        assertThrows(IllegalArgumentException::class.java) {
            SemanticEmbeddingBlobCodec.decode(ByteArray(3071))
        }
        val blob = ByteBuffer.allocate(3072)
            .order(ByteOrder.LITTLE_ENDIAN)
            .apply { repeat(768) { putFloat(if (it == 5) Float.NaN else 1f) } }
            .array()
        assertThrows(IllegalArgumentException::class.java) {
            SemanticEmbeddingBlobCodec.decode(blob)
        }
    }
}

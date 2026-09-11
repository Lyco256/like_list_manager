package com.lyco256.llm.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SemanticTextChunkerTest {
    @Test
    fun chunksUseCodePointsAndKeepTheConfiguredOverlapUntilTheEnd() {
        val text = buildString {
            repeat(900) { index -> append(if (index % 3 == 0) "😀" else "界") }
        }

        val chunks = SemanticTextChunker.chunk(text)
        val expectedRanges = expectedRanges(text)

        assertEquals(expectedRanges.size, chunks.size)
        chunks.forEachIndexed { index, chunk ->
            val (startCodePoint, endCodePoint) = expectedRanges[index]
            val expected = substringByCodePoints(text, startCodePoint, endCodePoint).trim()
            assertEquals(index, chunk.sourceOrdinal)
            assertEquals(expected, chunk.text)
            assertTrue(chunk.text.codePointCount(0, chunk.text.length) <= SemanticTextChunker.MAX_CODE_POINTS)
            assertTrue(chunk.text.firstOrNull()?.let { !Character.isLowSurrogate(it) } != false)
            assertTrue(chunk.text.lastOrNull()?.let { !Character.isHighSurrogate(it) } != false)
        }

        chunks.zipWithNext().forEachIndexed { index, (left, right) ->
            val (_, leftEnd) = expectedRanges[index]
            val (rightStart, _) = expectedRanges[index + 1]
            assertEquals(
                SemanticTextChunker.OVERLAP_CODE_POINTS,
                leftEnd - rightStart,
            )
        }
        assertTrue(chunks.last().text.endsWith("界") || chunks.last().text.endsWith("😀"))
        assertEquals(900, expectedRanges.last().second)
    }

    @Test
    fun shortTextAndBlankTextHaveExpectedOutput() {
        val short = "  改行\n😀  "
        val chunks = SemanticTextChunker.chunk(short)

        assertEquals(listOf(SemanticTextChunk(0, "改行\n😀")), chunks)
        assertTrue(SemanticTextChunker.chunk(" \n\t ").isEmpty())
    }

    @Test
    fun chunkingIsDeterministicAndDoesNotSplitSurrogatePairs() {
        val text = "a".repeat(383) + "😀" + "b".repeat(20)

        val first = SemanticTextChunker.chunk(text)
        val second = SemanticTextChunker.chunk(text)

        assertEquals(first, second)
        assertEquals(2, first.size)
        first.forEach { chunk ->
            assertTrue(chunk.text.none { it == '\uFFFD' })
            assertTrue(chunk.text.toCharArray().none { Character.isLowSurrogate(it) && chunk.text.indexOf(it) == 0 })
        }
    }

    private fun expectedRanges(text: String): List<Pair<Int, Int>> {
        val count = text.codePointCount(0, text.length)
        val result = mutableListOf<Pair<Int, Int>>()
        var start = 0
        while (start < count) {
            val end = minOf(start + SemanticTextChunker.MAX_CODE_POINTS, count)
            result += start to end
            if (end == count) break
            start = end - SemanticTextChunker.OVERLAP_CODE_POINTS
        }
        return result
    }

    private fun substringByCodePoints(text: String, start: Int, end: Int): String =
        text.substring(text.offsetByCodePoints(0, start), text.offsetByCodePoints(0, end))
}

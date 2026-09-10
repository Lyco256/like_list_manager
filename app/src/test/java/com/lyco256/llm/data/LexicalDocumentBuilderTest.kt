package com.lyco256.llm.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicalDocumentBuilderTest {
    @Test
    fun buildsOneDeterministicDocumentPerNonBlankSource() = runBlocking {
        val clip = fixture()
        val analyzedTexts = mutableListOf<String>()
        val analyzer = LexicalTextAnalyzer { text ->
            analyzedTexts += text
            SudachiLexicalTextAnalysis(
                normalizedText = "normalized:$text",
                readingText = "reading:$text",
                romanizedText = "romanized:$text",
                compactText = "compact:$text",
            )
        }

        val documents = LexicalDocumentBuilder.build(clip, analyzer)

        assertEquals(
            listOf("本文", "概要", "OCR", "表示名", "ユーザー名"),
            analyzedTexts,
        )
        assertEquals(
            listOf("text", "summary", "ocr", "author_name", "username"),
            documents.map(LexicalDocument::sourceType),
        )
        assertEquals(listOf(0, 0, 0, 0, 0), documents.map(LexicalDocument::sourceOrdinal))
        assertEquals(
            listOf(
                "clip:7:text:0",
                "clip:7:summary:0",
                "clip:7:ocr:0",
                "clip:7:author_name:0",
                "clip:7:username:0",
            ),
            documents.map(LexicalDocument::documentId),
        )
        assertEquals("概要", documents[1].rawText)
        assertEquals("normalized:概要", documents[1].normalizedText)
        assertEquals("reading:概要", documents[1].readingText)
        assertEquals("romanized:概要", documents[1].romanizedText)
        assertEquals("compact:概要", documents[1].compactText)
    }

    @Test
    fun blankSourceIsSkippedWithoutCallingAnalyzer() = runBlocking {
        val clip = fixture().copy(summary = "", ocrText = " ")
        val analyzedTexts = mutableListOf<String>()
        val analyzer = LexicalTextAnalyzer { text ->
            analyzedTexts += text
            SudachiLexicalTextAnalysis(text, text, text, text)
        }

        val documents = LexicalDocumentBuilder.build(clip, analyzer)

        assertEquals(listOf("本文", "表示名", "ユーザー名"), analyzedTexts)
        assertEquals(listOf("text", "author_name", "username"), documents.map(LexicalDocument::sourceType))
    }

    @Test
    fun fingerprintOnlyUsesTheFiveSearchFieldsAndRevision() {
        val clip = fixture()
        val original = LexicalDocumentBuilder.fingerprint(clip)

        assertNotEquals(original, LexicalDocumentBuilder.fingerprint(clip.copy(text = "別の本文")))
        assertNotEquals(original, LexicalDocumentBuilder.fingerprint(clip.copy(summary = "別の概要")))
        assertNotEquals(original, LexicalDocumentBuilder.fingerprint(clip.copy(ocrText = "別のOCR")))
        assertNotEquals(original, LexicalDocumentBuilder.fingerprint(clip.copy(authorName = "別の表示名")))
        assertNotEquals(original, LexicalDocumentBuilder.fingerprint(clip.copy(authorUsername = "別の名前")))
        assertEquals(
            original,
            LexicalDocumentBuilder.fingerprint(
                clip.copy(
                    likeCount = 100,
                    xCreatedAt = "later",
                    savedAt = "later-saved",
                    syncedAt = "later-synced",
                ),
            ),
        )
        assertEquals(64, original.length)
        assertTrue(original.all { it in "0123456789abcdef" })
    }

    private fun fixture() = ClipEntity(
        id = 7,
        xPostId = "post-7",
        authorName = "表示名",
        authorUsername = "ユーザー名",
        text = "本文",
        postUrl = "https://example.test/7",
        xCreatedAt = "created",
        savedAt = "saved",
        syncedAt = "synced",
        summary = "概要",
        ocrText = "OCR",
    )
}


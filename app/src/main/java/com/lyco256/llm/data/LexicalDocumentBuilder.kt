package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.nio.charset.StandardCharsets
import java.security.MessageDigest

fun interface LexicalTextAnalyzer {
    suspend fun analyze(text: String): SudachiLexicalTextAnalysis
}

enum class LexicalSourceType(val storageValue: String) {
    TEXT("text"),
    SUMMARY("summary"),
    OCR("ocr"),
    AUTHOR_NAME("author_name"),
    USERNAME("username"),
}

object LexicalDocumentBuilder {
    const val DOCUMENT_REVISION = "lexical-documents-v1"

    suspend fun build(
        clip: ClipEntity,
        analyzer: LexicalTextAnalyzer,
    ): List<LexicalDocument> = buildList {
        sourceFields(clip).forEach { (sourceType, rawText) ->
            if (rawText.isBlank()) return@forEach
            val analysis = analyzer.analyze(rawText)
            add(
                LexicalDocument(
                    documentId = documentId(clip.id, sourceType),
                    clipId = clip.id,
                    sourceType = sourceType.storageValue,
                    sourceOrdinal = 0,
                    rawText = rawText,
                    normalizedText = analysis.normalizedText,
                    readingText = analysis.readingText,
                    romanizedText = analysis.romanizedText,
                    compactText = analysis.compactText,
                ),
            )
        }
    }

    fun fingerprint(clip: ClipEntity): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("revision", DOCUMENT_REVISION)
        sourceFields(clip).forEach { (sourceType, rawText) ->
            digest.updateField(sourceType.storageValue, rawText)
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun documentId(clipId: Long, sourceType: LexicalSourceType): String =
        "clip:$clipId:${sourceType.storageValue}:0"

    private fun sourceFields(clip: ClipEntity): List<Pair<LexicalSourceType, String>> = listOf(
        LexicalSourceType.TEXT to clip.text,
        LexicalSourceType.SUMMARY to clip.summary,
        LexicalSourceType.OCR to clip.ocrText,
        LexicalSourceType.AUTHOR_NAME to clip.authorName,
        LexicalSourceType.USERNAME to clip.authorUsername,
    )

    private fun MessageDigest.updateField(name: String, value: String) {
        val nameBytes = name.toByteArray(StandardCharsets.UTF_8)
        val valueBytes = value.toByteArray(StandardCharsets.UTF_8)
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(nameBytes.size.toLong()).array())
        update(nameBytes)
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(valueBytes.size.toLong()).array())
        update(valueBytes)
    }
}

package com.lyco256.llm.data

import java.nio.ByteBuffer
import java.security.MessageDigest

enum class SemanticSourceType(val storageValue: String) {
    TEXT("text"),
    SUMMARY("summary"),
    OCR("ocr"),
    ;

    companion object {
        fun fromStorageValue(value: String): SemanticSourceType =
            entries.firstOrNull { it.storageValue == value }
                ?: error("Unknown semantic source type: $value")
    }
}
data class SemanticTextSource(
    val sourceType: SemanticSourceType,
    val text: String,
)

data class SemanticTextChunk(
    val sourceOrdinal: Int,
    val text: String,
)

object SemanticTextChunker {
    const val MAX_CODE_POINTS = 384
    const val OVERLAP_CODE_POINTS = 48
    const val CHUNK_RULE_REVISION = "semantic-text-chunks-code-points-384-overlap-48-v1"
    const val DOCUMENT_PROMPT_REVISION = "embeddinggemma-document-prompt-v1"
    const val FINGERPRINT_REVISION = "semantic-source-fingerprint-v1"

    fun chunk(text: String): List<SemanticTextChunk> {
        if (text.isBlank()) return emptyList()

        val codePointCount = text.codePointCount(0, text.length)
        val chunks = mutableListOf<SemanticTextChunk>()
        var startCodePoint = 0
        while (startCodePoint < codePointCount) {
            val endCodePoint = minOf(startCodePoint + MAX_CODE_POINTS, codePointCount)
            val startChar = text.offsetByCodePoints(0, startCodePoint)
            val endChar = text.offsetByCodePoints(0, endCodePoint)
            val chunkText = text.substring(startChar, endChar).trim()
            if (chunkText.isNotBlank()) {
                chunks += SemanticTextChunk(chunks.size, chunkText)
            }
            if (endCodePoint == codePointCount) break
            val nextStartCodePoint = endCodePoint - OVERLAP_CODE_POINTS
            check(nextStartCodePoint > startCodePoint) { "Semantic text chunker made no progress" }
            startCodePoint = nextStartCodePoint
        }
        return chunks
    }

    fun sources(clip: ClipEntity): List<SemanticTextSource> = listOf(
        SemanticTextSource(SemanticSourceType.TEXT, clip.text),
        SemanticTextSource(SemanticSourceType.SUMMARY, clip.summary),
        SemanticTextSource(SemanticSourceType.OCR, clip.ocrText),
    )

    fun fingerprint(source: SemanticTextSource): String = fingerprint(source.sourceType, source.text)

    fun fingerprint(sourceType: SemanticSourceType, text: String): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.updateField("fingerprint_revision", FINGERPRINT_REVISION)
        digest.updateField("source_type", sourceType.storageValue)
        digest.updateField("source_text", text)
        digest.updateField("model_revision", EmbeddingGemmaModelSpec.REVISION)
        digest.updateField("document_prompt_revision", DOCUMENT_PROMPT_REVISION)
        digest.updateField("chunk_rule_revision", CHUNK_RULE_REVISION)
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }

    fun documentId(clipId: Long, sourceType: SemanticSourceType, sourceOrdinal: Int): String {
        require(sourceOrdinal >= 0) { "Semantic source ordinal must not be negative" }
        return "semantic:clip:$clipId:${sourceType.storageValue}:$sourceOrdinal"
    }

    private fun MessageDigest.updateField(name: String, value: String) {
        val nameBytes = name.toByteArray(Charsets.UTF_8)
        val valueBytes = value.toByteArray(Charsets.UTF_8)
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(nameBytes.size.toLong()).array())
        update(nameBytes)
        update(ByteBuffer.allocate(Long.SIZE_BYTES).putLong(valueBytes.size.toLong()).array())
        update(valueBytes)
    }
}

package com.lyco256.llm.data

data class SemanticDocument(
    val documentId: String,
    val clipId: Long,
    val sourceType: SemanticSourceType,
    val sourceOrdinal: Int,
    val embedding: FloatArray,
)
data class SemanticSourceKey(
    val clipId: Long,
    val sourceType: SemanticSourceType,
)

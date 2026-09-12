package com.lyco256.llm.data

import java.text.Normalizer
import java.util.Locale
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext

internal fun comparisonText(text: String): String =
    Normalizer.normalize(text, Normalizer.Form.NFKC).lowercase(Locale.ROOT).trim()

internal fun searchCompact(text: String): String = buildString {
    text.codePoints().forEach { point ->
        val type = Character.getType(point)
        if (!Character.isWhitespace(point) && !Character.isSpaceChar(point) && type !in setOf(
                Character.CONNECTOR_PUNCTUATION.toInt(), Character.DASH_PUNCTUATION.toInt(),
                Character.START_PUNCTUATION.toInt(), Character.END_PUNCTUATION.toInt(),
                Character.INITIAL_QUOTE_PUNCTUATION.toInt(), Character.FINAL_QUOTE_PUNCTUATION.toInt(),
                Character.OTHER_PUNCTUATION.toInt(),
            )
        ) appendCodePoint(point)
    }
}

internal data class SearchRepresentation(
    val raw: String,
    val normalized: String,
    val reading: String,
    val romanized: String,
    val compact: String,
) {
    val texts = listOf(raw, normalized, reading, romanized).filter(String::isNotBlank).distinct()
    // Keep repeated tokens inside a representation: multiset containment must preserve multiplicity.
    val tokenLists = texts.map { it.split(' ').filter(String::isNotBlank) }.distinct()
    val tokens = tokenLists.flatten().distinct()
    val allTexts = (texts + compact).filter(String::isNotBlank).distinct()

    companion object {
        fun query(raw: String, analysis: SudachiLexicalTextAnalysis) = SearchRepresentation(
            comparisonText(raw), analysis.normalizedText, analysis.readingText,
            analysis.romanizedText, analysis.compactText,
        )

        fun document(document: LexicalDocument) = SearchRepresentation(
            comparisonText(document.rawText), document.normalizedText, document.readingText,
            document.romanizedText, document.compactText,
        )
    }
}

internal data class Fts5LiteralQueries(val normal: List<String>, val trigram: List<String>)

internal object Fts5LiteralQueryBuilder {
    fun quote(text: String): String = "\"${text.replace("\"", "\"\"")}\""

    fun build(query: SearchRepresentation): Fts5LiteralQueries {
        val normal = linkedSetOf<String>()
        query.tokenLists.filter { it.isNotEmpty() }.forEach { tokens ->
            normal.add(quote(tokens.joinToString(" ")))
            if (tokens.size >= 2) normal.add(tokens.joinToString(" AND ", transform = ::quote))
        }
        val trigram = (listOf(query.compact) + query.texts.map(::searchCompact))
            .filter { it.codePointCount(0, it.length) >= 3 }.map(::quote).distinct()
        return Fts5LiteralQueries(normal.toList(), trigram)
    }
}

internal object LexicalFallback {
    private data class FuzzyEdge(val documentIndex: Int, val similarity: Float)

    fun fuzzyPoints(token: String): IntArray? {
        val count = token.codePointCount(0, token.length)
        return if (count in 3..64) token.codePoints().toArray() else null
    }

    fun anagramSignature(token: String): List<Int>? {
        val normalized = comparisonText(token)
        val count = normalized.codePointCount(0, normalized.length)
        return if (count in 4..32) normalized.codePoints().toArray().sorted() else null
    }

    /** Optimal String Alignment on bounded Unicode code points, using three rolling rows. */
    fun distance(left: IntArray, right: IntArray): Int {
        require(left.size <= 64 && right.size <= 64)
        var older = IntArray(right.size + 1)
        var previous = IntArray(right.size + 1) { it }
        var current = IntArray(right.size + 1)
        for (i in 1..left.size) {
            current[0] = i
            for (j in 1..right.size) {
                current[j] = minOf(previous[j] + 1, current[j - 1] + 1,
                    previous[j - 1] + if (left[i - 1] == right[j - 1]) 0 else 1)
                if (i > 1 && j > 1 && left[i - 1] == right[j - 2] && left[i - 2] == right[j - 1]) {
                    current[j] = minOf(current[j], older[j - 2] + 1)
                }
            }
            val scratch = older
            older = previous
            previous = current
            current = scratch
        }
        return previous[right.size]
    }

    suspend fun fuzzy(query: SearchRepresentation, document: SearchRepresentation): Float {
        val queryTokens = query.tokens.mapNotNull(::fuzzyPoints)
        if (queryTokens.isEmpty()) return 0f
        val documentTokens = document.tokens.mapNotNull { token ->
            if (token.codePointCount(0, token.length) in 1..64) token.codePoints().toArray() else null
        }
        val edges = queryTokens.map { token ->
            buildList {
                documentTokens.forEachIndexed { index, other ->
                    if (index % 32 == 0) coroutineContext.ensureActive()
                    // Length difference alone can prove the pair cannot reach the admission similarity.
                    if (kotlin.math.abs(token.size - other.size).toFloat() / maxOf(token.size, other.size) <= 0.34f) {
                        val similarity = 1f - distance(token, other).toFloat() / maxOf(token.size, other.size)
                        if (similarity >= 0.66f) add(FuzzyEdge(index, similarity))
                    }
                }
            }.sortedWith(compareByDescending<FuzzyEdge> { it.similarity }.thenBy { it.documentIndex })
        }
        // Maximum-cardinality matching prevents one document token from satisfying multiple
        // query tokens. The bounded edge lists keep the distance matrix out of the heap.
        val owners = IntArray(documentTokens.size) { -1 }
        val assignedDocuments = IntArray(queryTokens.size) { -1 }
        fun assign(queryIndex: Int, visited: BooleanArray): Boolean {
            edges[queryIndex].forEach { edge ->
                if (visited[edge.documentIndex]) return@forEach
                visited[edge.documentIndex] = true
                val previousOwner = owners[edge.documentIndex]
                if (previousOwner == -1 || assign(previousOwner, visited)) {
                    owners[edge.documentIndex] = queryIndex
                    assignedDocuments[queryIndex] = edge.documentIndex
                    return true
                }
            }
            return false
        }
        queryTokens.indices.forEach { index ->
            coroutineContext.ensureActive()
            assign(index, BooleanArray(documentTokens.size))
        }
        var matched = 0
        var total = 0f
        assignedDocuments.forEachIndexed { queryIndex, documentIndex ->
            if (documentIndex >= 0) {
                matched++
                total += edges[queryIndex].first { it.documentIndex == documentIndex }.similarity
            }
        }
        val coverage = matched.toFloat() / queryTokens.size
        return if (coverage < 0.5f) 0f else minOf(LocalSearchTuning.FUZZY_CAP,
            0.43f + 0.19f * total / queryTokens.size)
    }

    suspend fun anagram(query: SearchRepresentation, document: SearchRepresentation): Float {
        val eligible = query.tokens.mapNotNull { token -> anagramSignature(token)?.let { token to it } }
        if (eligible.isNotEmpty()) {
            val options = document.tokens.mapNotNull { token -> anagramSignature(token)?.let { token to it } }
            val targetsBySignature = options.groupBy { it.second }
            var complete = true
            for ((signature, group) in eligible.groupBy { it.second }) {
                coroutineContext.ensureActive()
                val targets = targetsBySignature[signature].orEmpty()
                val targetCounts = targets.groupingBy { comparisonText(it.first) }.eachCount()
                val queryCounts = group.groupingBy { comparisonText(it.first) }.eachCount()
                // Matching is complete bipartite except identical-token edges. These are the
                // Hall conditions for disjoint identity groups, without recursive/greedy matching.
                if (targets.size < group.size || queryCounts.any { (token, count) ->
                        count > targets.size - (targetCounts[token] ?: 0)
                    }) { complete = false; break }
            }
            if (complete) return LocalSearchTuning.ANAGRAM_CAP
        }
        val compact = anagramSignature(query.compact)
        return if (compact != null && comparisonText(query.compact) != comparisonText(document.compact) &&
            compact == anagramSignature(document.compact)) LocalSearchTuning.ANAGRAM_CAP else 0f
    }
}

internal fun lexicalSourceWeight(source: String): Float = when (source) {
    "text" -> 1f
    "summary", "author_name", "username" -> 0.95f
    "ocr" -> 0.90f
    else -> error("Unknown lexical source type: $source")
}

internal fun semanticSourceWeight(source: SemanticSourceType): Float = when (source) {
    SemanticSourceType.TEXT -> 1f
    SemanticSourceType.SUMMARY -> 0.95f
    SemanticSourceType.OCR -> 0.90f
}

internal object LocalLexicalScorer {
    fun ordinary(query: SearchRepresentation, document: SearchRepresentation): Float {
        if (query.raw.isNotBlank() && query.raw == document.raw) return 1f
        val queryDerived = listOf(query.normalized, query.reading, query.romanized, query.compact)
        val documentDerived = listOf(document.normalized, document.reading, document.romanized, document.compact)
        if (queryDerived.any { q -> q.isNotBlank() && documentDerived.any { d -> q == d } }) return 0.97f
        if (query.texts.any { q -> document.texts.any { d -> d.contains(q) } }) return 0.92f
        var best = 0f
        query.tokenLists.forEach { tokens ->
            document.tokenLists.forEach { other ->
                val available = other.groupingBy { it }.eachCount().toMutableMap()
                var matched = 0
                tokens.forEach { token ->
                    val count = available[token] ?: 0
                    if (count > 0) { matched++; available[token] = count - 1 }
                }
                if (matched == tokens.size) {
                    var next = 0
                    other.forEach { token -> if (next < tokens.size && token == tokens[next]) next++ }
                    best = maxOf(best, if (next == tokens.size) 0.88f else 0.82f)
                } else if (matched > 0) {
                    best = maxOf(best, 0.52f + 0.22f * matched / tokens.size)
                }
            }
        }
        if (query.compact.isNotBlank() && document.compact.contains(query.compact)) best = maxOf(best, 0.74f)
        return best
    }

    fun shortMatch(query: SearchRepresentation, document: SearchRepresentation): Boolean =
        query.compact.codePointCount(0, query.compact.length) in 1..2 &&
            query.allTexts.any { q -> document.allTexts.any { it.contains(q) } }
}

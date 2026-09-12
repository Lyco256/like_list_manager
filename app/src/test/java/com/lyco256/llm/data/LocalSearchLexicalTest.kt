package com.lyco256.llm.data

import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class LocalSearchLexicalTest {
    private fun representation(text: String, compact: String = searchCompact(text)) =
        SearchRepresentation(text, text, text, text, compact)

    @Test fun literalBuilderQuotesEscapesDeduplicatesAndBoundsTrigram() {
        assertEquals("\"a\"\"b\"", Fts5LiteralQueryBuilder.quote("a\"b"))
        val input = "AND OR NOT NEAR * ( ) : - \""
        val result = Fts5LiteralQueryBuilder.build(representation(input))
        assertEquals(2, result.normal.size)
        assertEquals(Fts5LiteralQueryBuilder.quote(input), result.normal.first())
        assertEquals(input.split(' ').joinToString(" AND ") { Fts5LiteralQueryBuilder.quote(it) }, result.normal.last())
        assertEquals(result.normal.distinct(), result.normal)
        assertTrue(Fts5LiteralQueryBuilder.build(representation("")).normal.isEmpty())
        assertTrue(Fts5LiteralQueryBuilder.build(representation("😀a")).trigram.isEmpty())
        assertEquals(listOf("\"😀ab\""), Fts5LiteralQueryBuilder.build(representation("😀ab")).trigram)
        assertEquals("abc😀", searchCompact("a\u3000b-c。😀"))
        assertEquals("abc", comparisonText(" ＡＢＣ "))
    }

    @Test fun representationPreservesTokenMultiplicityAndIgnoresEmptyAlternatives() {
        val value = SearchRepresentation("aa aa", "", "", "", "")
        assertEquals(listOf(listOf("aa", "aa")), value.tokenLists)
        assertEquals(listOf("aa"), value.tokens)
        val queries = Fts5LiteralQueryBuilder.build(value)
        assertEquals(listOf("\"aa aa\"", "\"aa\" AND \"aa\""), queries.normal)
    }

    @Test fun osaUsesBoundedCodePointsAndAdjacentTransposition() {
        fun distance(a: String, b: String) = LexicalFallback.distance(a.codePoints().toArray(), b.codePoints().toArray())
        assertEquals(1, distance("abcd", "acbd"))
        assertEquals(1, distance("a😀bc", "😀abc"))
        assertEquals(1, distance("abc", "abcd"))
        assertEquals(1, distance("abcd", "abxd"))
        assertEquals(0, distance("😀abc", "😀abc"))
        assertNull(LexicalFallback.fuzzyPoints("ab"))
        assertNotNull(LexicalFallback.fuzzyPoints("😀".repeat(64)))
        assertNull(LexicalFallback.fuzzyPoints("😀".repeat(65)))
        assertNull(LexicalFallback.fuzzyPoints("a".repeat(100_000)))
        assertTrue(runCatching { LexicalFallback.distance(IntArray(65), IntArray(3)) }.isFailure)
    }

    @Test fun anagramIsBoundedDeterministicAndRequiresDistinctTargets() = runBlocking {
        assertNull(LexicalFallback.anagramSignature("abc"))
        assertNull(LexicalFallback.anagramSignature("a".repeat(33)))
        assertEquals(LexicalFallback.anagramSignature("a😀bc"), LexicalFallback.anagramSignature("c😀ba"))
        assertEquals(0f, LexicalFallback.anagram(representation("abcd"), representation("abcd")), 0f)
        assertEquals(LocalSearchTuning.ANAGRAM_CAP,
            LexicalFallback.anagram(representation("abcd"), representation("dcba")), 0f)
        assertEquals(0f, LexicalFallback.anagram(representation("abcd abdc", ""), representation("dcba", "")), 0f)
        assertEquals(LocalSearchTuning.ANAGRAM_CAP,
            LexicalFallback.anagram(representation("abcd abdc", ""), representation("dcba dabc", "")), 0f)
        assertEquals(0f, LexicalFallback.anagram(representation("abcd"), representation("xxdcbaxx")), 0f)
    }

    @Test fun scoresFollowMechanicalClassesWithoutQualityFixtures() = runBlocking {
        assertEquals(1f, LocalLexicalScorer.ordinary(representation("abcd"), representation("abcd")), 0f)
        assertEquals(0.92f, LocalLexicalScorer.ordinary(representation("abcd"), representation("xabcdx")), 0f)
        assertEquals(0.88f, LocalLexicalScorer.ordinary(representation("aa bb"), representation("aa xx bb")), 0f)
        assertEquals(0.82f, LocalLexicalScorer.ordinary(representation("aa bb"), representation("bb aa")), 0f)
        assertEquals(0.63f, LocalLexicalScorer.ordinary(representation("aa aa", ""), representation("aa", "")), 0.00001f)
        val fuzzy = LexicalFallback.fuzzy(representation("abcd"), representation("acbd"))
        assertTrue(fuzzy > LocalSearchTuning.ANAGRAM_CAP && fuzzy <= LocalSearchTuning.FUZZY_CAP)
        assertTrue(LocalSearchTuning.FUZZY_CAP < 0.74f)
        assertTrue(LocalLexicalScorer.shortMatch(representation("😀"), representation("x😀y")))
        assertFalse(LocalLexicalScorer.shortMatch(representation("zz"), representation("abcd")))
        assertTrue(runCatching { lexicalSourceWeight("unknown") }.isFailure)
    }

    @Test fun fuzzyEvidenceCannotReuseOneDocumentTokenForMultipleQueryTokens() = runBlocking {
        val query = representation("abcd abce abcf", "")
        val oneTarget = representation("acbd", "")
        val twoTargets = representation("acbd abfe", "")
        assertEquals(0f, LexicalFallback.fuzzy(query, oneTarget), 0f)
        assertTrue(LexicalFallback.fuzzy(query, twoTargets) > 0f)
    }

    @Test fun derivedRepresentationExactMatchCanCrossFields() {
        val query = representation("query", "")
        val document = SearchRepresentation("different", "reading", "query", "romanized", "")
        assertEquals(0.97f, LocalLexicalScorer.ordinary(query, document), 0f)
    }
}

package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SudachiLexicalTextAnalyzerIntegrationTest {
    @Test
    fun bundledFullDictionarySupportsLocalReuseConcurrentAnalysisAndClose() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val analyzer = SudachiLexicalTextAnalyzer(context)
        val dictionaryFile = File(
            context.noBackupFilesDir,
            "sudachi/$SUDACHI_FULL_DICTIONARY_VERSION/system_full.dic",
        )
        try {
            val blank = analyzer.analyze(" \n\t")
            assertEquals(SudachiLexicalTextAnalysis("", "", "", ""), blank)
            assertEquals(0, analyzer.dictionaryInitializationCount)

            val input = "日本語 ひらがな カタカナ ＡＢＣ 123 ASCII https://x.com/a\n絵文字🙂 結合 e\u0301 か\u3099"
            val results = coroutineScope {
                List(6) { index -> async { analyzer.analyze("$input $index") } }.awaitAll()
            }

            assertEquals(1, analyzer.dictionaryInitializationCount)
            assertTrue(dictionaryFile.isFile)
            val metadata = context.assets.open(SUDACHI_FULL_DICTIONARY_METADATA_ASSET).use {
                SudachiDictionaryMetadata.parse(it)
            }
            assertEquals(metadata.systemDictionaryByteSize, dictionaryFile.length())
            assertEquals(metadata.systemDictionarySha256, dictionaryFile.sha256())
            results.forEach { result ->
                assertTrue(result.normalizedText.isNotBlank())
                assertTrue(result.readingText.isNotBlank())
                assertTrue(result.romanizedText.isNotBlank())
                assertTrue(result.compactText.isNotBlank())
            }

            val repeated = analyzer.analyze(input)
            assertNotEquals("", repeated.normalizedText)
            assertEquals(1, analyzer.dictionaryInitializationCount)

            val longInput = buildString {
                repeat(1024) {
                    append("日本語 e\u0301 か\u3099 ASCII 123 ")
                }
            }
            val longResult = analyzer.analyze(longInput)
            assertTrue(longResult.normalizedText.isNotBlank())
            assertTrue(longResult.readingText.isNotBlank())
            assertTrue(longResult.romanizedText.isNotBlank())
            assertTrue(longResult.compactText.isNotBlank())
            assertEquals(1, analyzer.dictionaryInitializationCount)
        } finally {
            analyzer.close()
            analyzer.close()
        }

        val failure = runCatching { analyzer.analyze("close後") }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
    }
}

private fun File.sha256(): String {
    val digest = MessageDigest.getInstance("SHA-256")
    FileInputStream(this).use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
}

package com.lyco256.llm.data

import android.content.Context
import android.content.res.AssetManager
import android.icu.text.Transliterator
import com.worksap.nlp.sudachi.Config
import com.worksap.nlp.sudachi.Dictionary
import com.worksap.nlp.sudachi.DictionaryFactory
import com.worksap.nlp.sudachi.Morpheme
import com.worksap.nlp.sudachi.Tokenizer
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream

const val SUDACHI_FULL_DICTIONARY_VERSION = "20260723"

internal const val SUDACHI_FULL_DICTIONARY_ARCHIVE_ASSET =
    "sudachi/20260723/sudachi-dictionary-20260723-full.zip"
internal const val SUDACHI_FULL_DICTIONARY_METADATA_ASSET = "sudachi/20260723/metadata.json"

private const val SYSTEM_DICTIONARY_FILE_NAME = "system_full.dic"
private const val SYSTEM_DICTIONARY_TEMP_FILE_NAME = "system_full.dic.tmp"

data class SudachiLexicalTextAnalysis(
    val normalizedText: String,
    val readingText: String,
    val romanizedText: String,
    val compactText: String,
)

internal data class SudachiDictionaryMetadata(
    val dictionaryVersion: String,
    val archiveFileName: String,
    val archiveUrl: String,
    val archiveSha256: String,
    val archiveByteSize: Long,
    val systemDictionaryEntryName: String,
    val systemDictionarySha256: String,
    val systemDictionaryByteSize: Long,
) {
    companion object {
        fun parse(input: InputStream): SudachiDictionaryMetadata {
            val json = input.bufferedReader(Charsets.UTF_8).use { JSONObject(it.readText()) }
            val metadata = SudachiDictionaryMetadata(
                dictionaryVersion = json.getString("dictionaryVersion"),
                archiveFileName = json.getString("archiveFileName"),
                archiveUrl = json.getString("archiveUrl"),
                archiveSha256 = json.getString("archiveSha256"),
                archiveByteSize = json.getLong("archiveByteSize"),
                systemDictionaryEntryName = json.getString("systemDictionaryEntryName"),
                systemDictionarySha256 = json.getString("systemDictionarySha256"),
                systemDictionaryByteSize = json.getLong("systemDictionaryByteSize"),
            )
            require(metadata.dictionaryVersion == SUDACHI_FULL_DICTIONARY_VERSION) {
                "Unexpected Sudachi dictionary version: ${metadata.dictionaryVersion}"
            }
            require(metadata.archiveFileName == "sudachi-dictionary-20260723-full.zip") {
                "Unexpected Sudachi dictionary archive name: ${metadata.archiveFileName}"
            }
            require(metadata.archiveSha256.isSha256() && metadata.systemDictionarySha256.isSha256()) {
                "Sudachi dictionary metadata contains an invalid SHA-256"
            }
            require(metadata.archiveByteSize > 0L && metadata.systemDictionaryByteSize > 0L) {
                "Sudachi dictionary metadata contains an invalid byte size"
            }
            require(
                metadata.systemDictionaryEntryName.substringAfterLast('/') == SYSTEM_DICTIONARY_FILE_NAME &&
                    metadata.systemDictionaryEntryName.isNotBlank() &&
                    !metadata.systemDictionaryEntryName.contains(".."),
            ) {
                "Sudachi dictionary metadata contains an invalid system dictionary entry"
            }
            return metadata
        }
    }
}

internal interface SudachiDictionaryAssetSource {
    fun openMetadata(): InputStream
    fun openArchive(): InputStream
}

private class AndroidSudachiDictionaryAssetSource(
    private val assets: AssetManager,
) : SudachiDictionaryAssetSource {
    override fun openMetadata(): InputStream = assets.open(SUDACHI_FULL_DICTIONARY_METADATA_ASSET)

    override fun openArchive(): InputStream = assets.open(
        SUDACHI_FULL_DICTIONARY_ARCHIVE_ASSET,
        AssetManager.ACCESS_STREAMING,
    )
}

internal class SudachiDictionaryInstaller(
    private val directory: File,
    private val assetSource: SudachiDictionaryAssetSource,
) {
    private val mutex = Mutex()
    private var cachedMetadata: SudachiDictionaryMetadata? = null

    suspend fun ensureInstalled(): File = mutex.withLock {
        val metadata = cachedMetadata ?: assetSource.openMetadata().use(SudachiDictionaryMetadata::parse).also {
            cachedMetadata = it
        }
        ensureInstalledLocked(metadata)
    }

    private fun ensureInstalledLocked(metadata: SudachiDictionaryMetadata): File {
        if (!directory.exists() && !directory.mkdirs()) {
            throw IllegalStateException("Failed to create Sudachi dictionary directory: ${directory.absolutePath}")
        }
        val target = File(directory, SYSTEM_DICTIONARY_FILE_NAME)
        val temporary = File(directory, SYSTEM_DICTIONARY_TEMP_FILE_NAME)

        if (target.exists()) {
            if (target.isFile && matchesMetadata(target, metadata)) {
                deleteExact(temporary)
                return target
            }
            deleteExact(target)
        }
        deleteExact(temporary)

        try {
            var found = false
            ZipInputStream(assetSource.openArchive().buffered()).use { archive ->
                while (true) {
                    val entry = archive.nextEntry ?: break
                    if (entry.name == metadata.systemDictionaryEntryName) {
                        if (found || entry.isDirectory) {
                            throw IllegalStateException("Sudachi Full archive contains an invalid system dictionary entry")
                        }
                        found = true
                        FileOutputStream(temporary).use { output ->
                            val buffer = ByteArray(64 * 1024)
                            var total = 0L
                            while (true) {
                                val read = archive.read(buffer)
                                if (read < 0) break
                                total += read.toLong()
                                output.write(buffer, 0, read)
                            }
                            output.fd.sync()
                            check(total == metadata.systemDictionaryByteSize) {
                                "Sudachi system dictionary byte size does not match metadata"
                            }
                        }
                    }
                    archive.closeEntry()
                }
            }
            check(found) { "Sudachi Full archive does not contain system_full.dic" }
            check(matchesMetadata(temporary, metadata)) {
                "Sudachi system dictionary SHA-256 does not match metadata"
            }
            atomicReplace(temporary, target)
            check(matchesMetadata(target, metadata)) {
                "Installed Sudachi system dictionary failed final verification"
            }
            return target
        } catch (error: Throwable) {
            runCatching { deleteExact(temporary) }
                .exceptionOrNull()
                ?.let(error::addSuppressed)
            throw error
        }
    }

    private fun matchesMetadata(file: File, metadata: SudachiDictionaryMetadata): Boolean {
        if (file.length() != metadata.systemDictionaryByteSize) return false
        return sha256(file).equals(metadata.systemDictionarySha256, ignoreCase = true)
    }

    private fun atomicReplace(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun deleteExact(file: File) {
        if (file.exists() && !file.delete()) {
            throw IllegalStateException("Failed to remove Sudachi temporary file: ${file.absolutePath}")
        }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(file).use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                digest.update(buffer, 0, read)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}

class SudachiLexicalTextAnalyzer internal constructor(
    private val installer: SudachiDictionaryInstaller,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : LexicalTextAnalyzer {
    constructor(context: Context) : this(
        installer = SudachiDictionaryInstaller(
            directory = File(
                context.noBackupFilesDir,
                "sudachi/$SUDACHI_FULL_DICTIONARY_VERSION",
            ),
            assetSource = AndroidSudachiDictionaryAssetSource(context.assets),
        ),
    )

    private val mutex = Mutex()
    private var dictionary: Dictionary? = null
    private var tokenizer: Tokenizer? = null
    @Volatile private var closed = false

    internal val dictionaryInitializationCount: Int
        get() = dictionaryLoadCount

    @Volatile private var dictionaryLoadCount = 0

    private val transliterators by lazy(LazyThreadSafetyMode.NONE) {
        TransliteratorSet(
            katakanaLatin = Transliterator.getInstance("Katakana-Latin"),
            katakanaLatinBgn = Transliterator.getInstance("Katakana-Latin/BGN"),
            latinAscii = Transliterator.getInstance("Latin-ASCII"),
        )
    }

    override suspend fun analyze(text: String): SudachiLexicalTextAnalysis = withContext(ioDispatcher) {
        mutex.withLock {
            check(!closed) { "SudachiLexicalTextAnalyzer is closed" }
            if (text.isBlank()) return@withLock EMPTY_ANALYSIS
            val currentTokenizer = tokenizer ?: loadTokenizerLocked()
            buildAnalysis(currentTokenizer, text)
        }
    }

    suspend fun close() = withContext(ioDispatcher) {
        mutex.withLock {
            if (closed) return@withLock
            closed = true
            val currentDictionary = dictionary
            dictionary = null
            tokenizer = null
            currentDictionary?.close()
        }
    }

    private suspend fun loadTokenizerLocked(): Tokenizer {
        val systemDictionary = installer.ensureInstalled()
        val loadedDictionary = DictionaryFactory().create(
            Config.defaultConfig().systemDictionary(systemDictionary.toPath()),
        )
        return try {
            val loadedTokenizer = loadedDictionary.create()
            dictionary = loadedDictionary
            tokenizer = loadedTokenizer
            dictionaryLoadCount += 1
            loadedTokenizer
        } catch (error: Throwable) {
            runCatching { loadedDictionary.close() }.exceptionOrNull()?.let(error::addSuppressed)
            throw error
        }
    }

    private fun buildAnalysis(tokenizer: Tokenizer, text: String): SudachiLexicalTextAnalysis {
        val cMorphemes = tokenizer.tokenize(Tokenizer.SplitMode.C, text).toList()
        val candidates = cMorphemes.flatMap(::candidatePairs)
        val normalizedText = candidates.joinToString(" ") { it.normalized }
        val readingText = candidates.joinToString(" ") { it.reading }
        val compactText = cMorphemes
            .mapNotNull { morpheme -> morpheme.normalizedForm().takeUnless(::isPunctuationOrWhitespaceOnly) }
            .joinToString("")
            .removeUnicodeSeparatorsAndPunctuation()
            .lowercase(Locale.ROOT)
        return SudachiLexicalTextAnalysis(
            normalizedText = normalizedText,
            readingText = readingText,
            romanizedText = romanize(readingText),
            compactText = compactText,
        )
    }

    private fun candidatePairs(morpheme: Morpheme): List<LexicalCandidate> {
        val cCandidate = morpheme.toCandidate() ?: return emptyList()
        val aMorphemes = morpheme.split(Tokenizer.SplitMode.A)
        if (aMorphemes.size <= 1) return listOf(cCandidate)

        val seen = mutableSetOf(cCandidate.normalized)
        return buildList {
            add(cCandidate)
            aMorphemes.forEach { aMorpheme ->
                val candidate = aMorpheme.toCandidate() ?: return@forEach
                if (seen.add(candidate.normalized)) add(candidate)
            }
        }
    }

    private fun Morpheme.toCandidate(): LexicalCandidate? {
        val normalized = normalizedForm()
        if (isPunctuationOrWhitespaceOnly(normalized)) return null
        val reading = readingForm().takeUnless(String::isBlank) ?: normalized
        return LexicalCandidate(normalized = normalized, reading = reading)
    }

    private fun romanize(readingText: String): String {
        if (readingText.isBlank()) return ""
        val result = mutableListOf<String>()
        readingText.split(' ').filter(String::isNotBlank).forEach { word ->
            val variants = linkedSetOf<String>()
            listOf(transliterators.katakanaLatin, transliterators.katakanaLatinBgn).forEach { transliterator ->
                val romanized = transliterators.latinAscii.transliterate(transliterator.transliterate(word))
                    .lowercase(Locale.ROOT)
                if (romanized.isNotBlank()) variants += romanized
            }
            result += variants
        }
        return result.joinToString(" ")
    }

    private data class LexicalCandidate(
        val normalized: String,
        val reading: String,
    )

    private data class TransliteratorSet(
        val katakanaLatin: Transliterator,
        val katakanaLatinBgn: Transliterator,
        val latinAscii: Transliterator,
    )

    companion object {
        private val EMPTY_ANALYSIS = SudachiLexicalTextAnalysis("", "", "", "")
    }
}

private fun String.isSha256(): Boolean = matches(Regex("[0-9a-fA-F]{64}"))

private fun isPunctuationOrWhitespaceOnly(value: String): Boolean {
    if (value.isEmpty()) return true
    var index = 0
    while (index < value.length) {
        val codePoint = value.codePointAt(index)
        if (!codePoint.isUnicodeWhitespaceOrPunctuation()) return false
        index += Character.charCount(codePoint)
    }
    return true
}

private fun String.removeUnicodeSeparatorsAndPunctuation(): String = buildString(length) {
    var index = 0
    while (index < this@removeUnicodeSeparatorsAndPunctuation.length) {
        val codePoint = this@removeUnicodeSeparatorsAndPunctuation.codePointAt(index)
        if (!codePoint.isUnicodeWhitespaceOrPunctuation()) appendCodePoint(codePoint)
        index += Character.charCount(codePoint)
    }
}

private fun Int.isUnicodeWhitespaceOrPunctuation(): Boolean {
    if (Character.isWhitespace(this)) return true
    return when (Character.getType(this)) {
        Character.SPACE_SEPARATOR.toInt(),
        Character.LINE_SEPARATOR.toInt(),
        Character.PARAGRAPH_SEPARATOR.toInt(),
        Character.CONNECTOR_PUNCTUATION.toInt(),
        Character.DASH_PUNCTUATION.toInt(),
        Character.START_PUNCTUATION.toInt(),
        Character.END_PUNCTUATION.toInt(),
        Character.INITIAL_QUOTE_PUNCTUATION.toInt(),
        Character.FINAL_QUOTE_PUNCTUATION.toInt(),
        Character.OTHER_PUNCTUATION.toInt(),
        -> true
        else -> false
    }
}

package com.lyco256.llm.data

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.content.res.AssetManager
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object EmbeddingGemmaModelSpec {
    const val REPOSITORY = "onnx-community/embeddinggemma-300m-ONNX"
    const val REVISION = "75a84c732f1884df76bec365346230e32f582c82"
    const val ASSET_ROOT = "embeddinggemma/$REVISION"
    const val MODEL_FILE = "model_q4.onnx"
    const val MODEL_EXTERNAL_DATA_FILE = "model_q4.onnx_data"
    const val TOKENIZER_FILE = "tokenizer.json"
    const val METADATA_FILE = "metadata.json"
    const val EMBEDDING_DIMENSION = 768
    const val MAX_TOKEN_LENGTH = 2048

    data class Asset(
        val name: String,
        val sha256: String,
    )

    val assets = listOf(
        Asset(
            MODEL_FILE,
            "ad1dfee81a70f7944b9b9d1cc6e48075b832881cf33fab2f2b248be78f3f0043",
        ),
        Asset(
            MODEL_EXTERNAL_DATA_FILE,
            "599962c3143b040de2dd05e5975be3e9091dd067cacc6a8f7186e3203bab9e02",
        ),
        Asset(
            TOKENIZER_FILE,
            "4dda02faaf32bc91031dc8c88457ac272b00c1016cc679757d1c441b248b9c47",
        ),
    )
}

object EmbeddingPrompts {
    fun query(content: String): String = "task: search result | query: $content"

    fun document(content: String): String = "title: none | text: $content"
}

class TextEmbedding private constructor(private val components: FloatArray) {
    val dimension: Int
        get() = components.size

    operator fun get(index: Int): Float = components[index]

    fun toFloatArray(): FloatArray = components.copyOf()

    companion object {
        internal fun fromModelOutput(output: FloatArray): TextEmbedding {
            require(output.size == EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION) {
                "Embedding output must have ${EmbeddingGemmaModelSpec.EMBEDDING_DIMENSION} values"
            }
            require(output.all { value -> value.isFinite() }) { "Embedding output contains non-finite values" }
            val norm = kotlin.math.sqrt(output.sumOf { value -> value.toDouble() * value.toDouble() })
            require(norm > 0.0 && norm.isFinite()) { "Embedding output has no positive finite L2 norm" }
            return TextEmbedding(output.map { value -> (value / norm).toFloat() }.toFloatArray())
        }
    }
}

fun interface DocumentEmbedder {
    suspend fun embedDocument(text: String): TextEmbedding
}

fun interface QueryEmbedder {
    suspend fun embedQuery(text: String): TextEmbedding
}

class EmbeddingRuntimeInitializationException(cause: Throwable) :
    IllegalStateException("EmbeddingGemma runtime initialization failed", cause)

internal interface EmbeddingAssetSource : LocalAssetSource

private class AssetManagerEmbeddingAssetSource(
    private val assetManager: AssetManager,
) : EmbeddingAssetSource {
    override fun open(path: String): InputStream = assetManager.open(path, AssetManager.ACCESS_STREAMING)
}

internal data class InstalledEmbeddingGemmaAssets(
    val modelFile: File,
    val tokenizerFile: File,
)

internal class EmbeddingGemmaAssetInstaller(
    private val source: EmbeddingAssetSource,
    private val modelDirectory: File,
    private val expectedAssets: List<EmbeddingGemmaModelSpec.Asset> = EmbeddingGemmaModelSpec.assets,
    private val expectedRepository: String = EmbeddingGemmaModelSpec.REPOSITORY,
    private val expectedRevision: String = EmbeddingGemmaModelSpec.REVISION,
    private val assetRoot: String = EmbeddingGemmaModelSpec.ASSET_ROOT,
) {
    fun install(): InstalledEmbeddingGemmaAssets {
        val files = LocalRuntimeAssetInstaller(
            source = source,
            modelDirectory = modelDirectory,
            expectedAssets = expectedAssets.map { LocalRuntimeAssetSpec(it.name, it.sha256) },
            expectedRepository = expectedRepository,
            expectedRevision = expectedRevision,
            assetRoot = assetRoot,
            metadataFileName = EmbeddingGemmaModelSpec.METADATA_FILE,
            label = "EmbeddingGemma",
        ).install()
        return InstalledEmbeddingGemmaAssets(
            modelFile = requireNotNull(files[EmbeddingGemmaModelSpec.MODEL_FILE]),
            tokenizerFile = requireNotNull(files[EmbeddingGemmaModelSpec.TOKENIZER_FILE]),
        )
    }
}

class LocalTextEmbedder internal constructor(
    private val assetSource: EmbeddingAssetSource,
    private val modelDirectory: File,
    private val ioDispatcher: CoroutineDispatcher,
) : DocumentEmbedder, QueryEmbedder, AutoCloseable {
    constructor(context: Context) : this(
        assetSource = AssetManagerEmbeddingAssetSource(context.assets),
        modelDirectory = File(
            context.noBackupFilesDir,
            "text_embedding/embeddinggemma/${EmbeddingGemmaModelSpec.REVISION}",
        ),
        ioDispatcher = Dispatchers.IO,
    )

    private val lock = ReentrantLock()
    private var runtime: Runtime? = null
    private var closed = false

    internal var initializationCountForTest: Int = 0
        private set

    internal val inputNamesForTest: Set<String>
        get() = lock.withLock { runtime?.inputNames.orEmpty() }

    internal val outputNamesForTest: Set<String>
        get() = lock.withLock { runtime?.outputNames.orEmpty() }

    override suspend fun embedQuery(text: String): TextEmbedding = embed(
        text = text,
        prompt = EmbeddingPrompts::query,
    )

    override suspend fun embedDocument(text: String): TextEmbedding = embed(
        text = text,
        prompt = EmbeddingPrompts::document,
    )

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            runtime?.close()
            runtime = null
        }
    }

    private suspend fun embed(text: String, prompt: (String) -> String): TextEmbedding {
        require(text.isNotBlank()) { "Embedding input must not be blank" }
        return withContext(ioDispatcher) {
            lock.withLock {
                check(!closed) { "LocalTextEmbedder is closed" }
                val currentRuntime = runtime ?: run {
                    try {
                        createRuntime()
                    } catch (error: CancellationException) {
                        throw error
                    } catch (error: Throwable) {
                        throw EmbeddingRuntimeInitializationException(error)
                    }.also {
                        runtime = it
                        initializationCountForTest += 1
                    }
                }
                currentRuntime.embed(prompt(text))
            }
        }
    }

    private fun createRuntime(): Runtime {
        val installed = EmbeddingGemmaAssetInstaller(assetSource, modelDirectory).install()
        configureOfflineDjl()
        var tokenizer: HuggingFaceTokenizer? = null
        var session: OrtSession? = null
        try {
            tokenizer = FileInputStream(installed.tokenizerFile).use { input ->
                HuggingFaceTokenizer.newInstance(
                    input,
                    mapOf(
                        "truncation" to "longest_first",
                        "padding" to "do_not_pad",
                        "maxLength" to EmbeddingGemmaModelSpec.MAX_TOKEN_LENGTH.toString(),
                        "modelMaxLength" to EmbeddingGemmaModelSpec.MAX_TOKEN_LENGTH.toString(),
                        "withOverflowingTokens" to "false",
                    ),
                )
            }
            val environment = OrtEnvironment.getEnvironment()
            val options = OrtSession.SessionOptions().apply {
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
                setIntraOpNumThreads(2)
            }
            session = try {
                environment.createSession(installed.modelFile.absolutePath, options)
            } finally {
                options.close()
            }
            val tokenizerValue = tokenizer ?: error("EmbeddingGemma tokenizer was not initialized")
            val sessionValue = session ?: error("EmbeddingGemma session was not initialized")
            return Runtime(environment, tokenizerValue, sessionValue).also {
                tokenizer = null
                session = null
            }
        } catch (failure: Throwable) {
            runCatching { session?.close() }
            runCatching { tokenizer?.close() }
            throw failure
        }
    }

    private fun configureOfflineDjl() {
        System.setProperty("ai.djl.offline", "true")
        System.setProperty("OPT_OUT_TRACKING", "true")
    }

    private class Runtime(
        private val environment: OrtEnvironment,
        private val tokenizer: HuggingFaceTokenizer,
        private val session: OrtSession,
    ) : AutoCloseable {
        private val inputInfo = session.inputInfo
        private val outputInfo = session.outputInfo
        private val outputName = "sentence_embedding"

        val inputNames: Set<String>
            get() = inputInfo.keys.toSet()

        val outputNames: Set<String>
            get() = outputInfo.keys.toSet()

        init {
            check(inputInfo.keys.containsAll(setOf("input_ids", "attention_mask"))) {
                "EmbeddingGemma model inputs do not expose input_ids and attention_mask: ${inputInfo.keys}"
            }
            check(inputInfo.keys.all { it in setOf("input_ids", "attention_mask", "token_type_ids") }) {
                "EmbeddingGemma model has unsupported input names: ${inputInfo.keys}"
            }
            val outputTensorInfo = outputInfo[outputName]?.info as? TensorInfo
            check(outputTensorInfo != null) {
                "EmbeddingGemma model does not expose $outputName: ${outputInfo.keys}"
            }
            check(outputTensorInfo.type == OnnxJavaType.FLOAT) {
                "EmbeddingGemma output must be float: ${outputTensorInfo.type}"
            }
        }

        fun embed(prompt: String): TextEmbedding {
            val encoding = tokenizer.encode(prompt)
            val ids = encoding.ids
            val attentionMask = encoding.attentionMask
            check(ids.size == attentionMask.size) { "Tokenizer returned mismatched input lengths" }
            check(ids.isNotEmpty() && ids.size <= EmbeddingGemmaModelSpec.MAX_TOKEN_LENGTH) {
                "Tokenizer returned an invalid token length: ${ids.size}"
            }
            return runModel(ids, attentionMask)
        }

        private fun runModel(ids: LongArray, attentionMask: LongArray): TextEmbedding {
            val tensors = linkedMapOf<String, OnnxTensor>()
            try {
                tensors["input_ids"] = createTokenTensor("input_ids", ids)
                tensors["attention_mask"] = createTokenTensor("attention_mask", attentionMask)
                if ("token_type_ids" in inputInfo.keys) {
                    tensors["token_type_ids"] = createTokenTensor("token_type_ids", LongArray(ids.size))
                }
                check(tensors.keys == inputInfo.keys) {
                    "EmbeddingGemma inputs do not match session metadata: ${tensors.keys} vs ${inputInfo.keys}"
                }
                val result = try {
                    session.run(tensors)
                } finally {
                    tensors.values.forEach { tensor -> tensor.close() }
                }
                return try {
                    val output = result.get(outputName).orElseThrow {
                        IllegalStateException("EmbeddingGemma returned no $outputName output")
                    }
                    val tensor = output as? OnnxTensor
                        ?: throw IllegalStateException("EmbeddingGemma output is not an ONNX tensor")
                    val buffer = tensor.floatBuffer.duplicate().apply { rewind() }
                    val values = FloatArray(buffer.remaining())
                    buffer.get(values)
                    TextEmbedding.fromModelOutput(values)
                } finally {
                    result.close()
                }
            } catch (failure: Throwable) {
                throw IllegalStateException("EmbeddingGemma inference failed", failure)
            }
        }

        private fun createTokenTensor(name: String, values: LongArray): OnnxTensor {
            val info = inputInfo[name]?.info as? TensorInfo
                ?: throw IllegalStateException("EmbeddingGemma input is not a tensor: $name")
            return when (info.type) {
                OnnxJavaType.INT64 -> OnnxTensor.createTensor(environment, arrayOf(values))
                OnnxJavaType.INT32 -> OnnxTensor.createTensor(
                    environment,
                    arrayOf(values.map { value -> value.toInt() }.toIntArray()),
                )
                else -> throw IllegalStateException("Unsupported EmbeddingGemma input type for $name: ${info.type}")
            }
        }

        override fun close() {
            try {
                session.close()
            } finally {
                tokenizer.close()
            }
        }
    }
}

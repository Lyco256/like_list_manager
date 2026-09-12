package com.lyco256.llm.data

import ai.djl.huggingface.tokenizers.HuggingFaceTokenizer
import ai.onnxruntime.OnnxJavaType
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import android.content.Context
import android.content.res.AssetManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import java.io.File
import java.io.FileInputStream
import java.io.InputStream
import java.nio.FloatBuffer
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

object JapaneseClipModelSpec {
    const val REPOSITORY = "AUXOUT-TEAM/clip-japanese-base-v2-onnx"
    const val REVISION = "c924148be2e25b6e4d98e66d8dc1768adb72d079"
    const val ASSET_ROOT = "japanese_clip/$REVISION"
    const val TEXT_MODEL_FILE = "text_model_q4f16.onnx"
    const val VISION_MODEL_FILE = "vision_model_q4f16.onnx"
    const val TOKENIZER_FILE = "tokenizer.json"
    const val METADATA_FILE = "metadata.json"
    const val EMBEDDING_DIMENSION = 256
    const val IMAGE_SIZE = 224
    const val MAX_TEXT_TOKEN_LENGTH = 77
    const val MAX_TOKENIZER_TOKEN_LENGTH = MAX_TEXT_TOKEN_LENGTH - 1
    const val CLS_TOKEN_ID = 4L

    val IMAGE_MEAN = floatArrayOf(0.48145466f, 0.4578275f, 0.40821073f)
    val IMAGE_STD = floatArrayOf(0.26862954f, 0.26130258f, 0.27577711f)

    data class Asset(
        val name: String,
        val sha256: String,
    )

    val assets = listOf(
        Asset(
            TEXT_MODEL_FILE,
            "653d6e3393d4c989cee268a5280fccd96dab1f6a93223e56bcb6cb9757503cb6",
        ),
        Asset(
            VISION_MODEL_FILE,
            "ef224e6127d6b66e79eab7ec2b4245acfa9ca070f0c66dbb27e93732bf09a76f",
        ),
        Asset(
            TOKENIZER_FILE,
            "c11bafa5bdbcc1470ef2c81c6f32686547acace5c5e5a6b67a1d3b8501df6b86",
        ),
    )
}

class MultimodalEmbedding private constructor(private val components: FloatArray) {
    val dimension: Int
        get() = components.size

    operator fun get(index: Int): Float = components[index]

    fun toFloatArray(): FloatArray = components.copyOf()

    companion object {
        internal fun fromModelOutput(output: FloatArray): MultimodalEmbedding {
            require(output.size == JapaneseClipModelSpec.EMBEDDING_DIMENSION) {
                "Japanese CLIP output must have ${JapaneseClipModelSpec.EMBEDDING_DIMENSION} values"
            }
            require(output.all(Float::isFinite)) {
                "Japanese CLIP output contains non-finite values"
            }
            val norm = kotlin.math.sqrt(output.sumOf { value -> value.toDouble() * value.toDouble() })
            require(norm > 0.0 && norm.isFinite()) {
                "Japanese CLIP output has no positive finite L2 norm"
            }
            return MultimodalEmbedding(output.map { value -> (value / norm).toFloat() }.toFloatArray())
        }
    }
}

internal interface JapaneseClipAssetSource : LocalAssetSource

private class AssetManagerJapaneseClipAssetSource(
    private val assetManager: AssetManager,
) : JapaneseClipAssetSource {
    override fun open(path: String): InputStream = assetManager.open(path, AssetManager.ACCESS_STREAMING)
}

internal data class InstalledJapaneseClipAssets(
    val textModelFile: File,
    val visionModelFile: File,
    val tokenizerFile: File,
)

internal class JapaneseClipAssetInstaller(
    private val source: JapaneseClipAssetSource,
    private val modelDirectory: File,
    private val expectedAssets: List<JapaneseClipModelSpec.Asset> = JapaneseClipModelSpec.assets,
    private val expectedRepository: String = JapaneseClipModelSpec.REPOSITORY,
    private val expectedRevision: String = JapaneseClipModelSpec.REVISION,
    private val assetRoot: String = JapaneseClipModelSpec.ASSET_ROOT,
) {
    fun install(): InstalledJapaneseClipAssets {
        val files = LocalRuntimeAssetInstaller(
            source = source,
            modelDirectory = modelDirectory,
            expectedAssets = expectedAssets.map { LocalRuntimeAssetSpec(it.name, it.sha256) },
            expectedRepository = expectedRepository,
            expectedRevision = expectedRevision,
            assetRoot = assetRoot,
            metadataFileName = JapaneseClipModelSpec.METADATA_FILE,
            label = "Japanese CLIP",
        ).install()
        return InstalledJapaneseClipAssets(
            textModelFile = requireNotNull(files[JapaneseClipModelSpec.TEXT_MODEL_FILE]),
            visionModelFile = requireNotNull(files[JapaneseClipModelSpec.VISION_MODEL_FILE]),
            tokenizerFile = requireNotNull(files[JapaneseClipModelSpec.TOKENIZER_FILE]),
        )
    }
}

internal data class JapaneseClipTokenInputs(
    val inputIds: LongArray,
    val attentionMask: LongArray,
    val positionIds: LongArray,
)

internal fun buildJapaneseClipTokenInputs(rawTokenIds: LongArray): JapaneseClipTokenInputs {
    require(rawTokenIds.isNotEmpty()) { "Japanese CLIP tokenizer returned no tokens" }
    require(rawTokenIds.size <= JapaneseClipModelSpec.MAX_TOKENIZER_TOKEN_LENGTH) {
        "Japanese CLIP tokenizer returned too many tokens: ${rawTokenIds.size}"
    }
    val inputIds = LongArray(rawTokenIds.size + 1)
    inputIds[0] = JapaneseClipModelSpec.CLS_TOKEN_ID
    rawTokenIds.copyInto(inputIds, destinationOffset = 1)
    return JapaneseClipTokenInputs(
        inputIds = inputIds,
        attentionMask = LongArray(inputIds.size) { 1L },
        positionIds = LongArray(inputIds.size) { it.toLong() },
    )
}

internal fun preprocessJapaneseClipImage(bitmap: Bitmap): FloatArray {
    require(!bitmap.isRecycled) { "Japanese CLIP image must not be recycled" }
    require(bitmap.width > 0 && bitmap.height > 0) { "Japanese CLIP image must have positive dimensions" }

    val imageSize = JapaneseClipModelSpec.IMAGE_SIZE
    val longSide = maxOf(bitmap.width, bitmap.height).toFloat()
    val shortSide = minOf(bitmap.width, bitmap.height)
    val scaledShortSide = kotlin.math.round(imageSize * shortSide / longSide).toInt().coerceIn(1, imageSize)
    val scaledWidth = if (bitmap.width >= bitmap.height) imageSize else scaledShortSide
    val scaledHeight = if (bitmap.height >= bitmap.width) imageSize else scaledShortSide
    val left = (imageSize - scaledWidth) / 2
    val top = (imageSize - scaledHeight) / 2
    val target = Bitmap.createBitmap(imageSize, imageSize, Bitmap.Config.ARGB_8888)
    try {
        Canvas(target).drawColor(android.graphics.Color.BLACK)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { isFilterBitmap = true }
        Canvas(target).drawBitmap(
            bitmap,
            null,
            android.graphics.Rect(left, top, left + scaledWidth, top + scaledHeight),
            paint,
        )
        val pixels = IntArray(imageSize * imageSize)
        target.getPixels(pixels, 0, imageSize, 0, 0, imageSize, imageSize)
        val planeSize = imageSize * imageSize
        val values = FloatArray(planeSize * 3)
        for (index in pixels.indices) {
            val pixel = pixels[index]
            val red = ((pixel ushr 16) and 0xff) / 255f
            val green = ((pixel ushr 8) and 0xff) / 255f
            val blue = (pixel and 0xff) / 255f
            values[index] = (red - JapaneseClipModelSpec.IMAGE_MEAN[0]) / JapaneseClipModelSpec.IMAGE_STD[0]
            values[planeSize + index] =
                (green - JapaneseClipModelSpec.IMAGE_MEAN[1]) / JapaneseClipModelSpec.IMAGE_STD[1]
            values[planeSize * 2 + index] =
                (blue - JapaneseClipModelSpec.IMAGE_MEAN[2]) / JapaneseClipModelSpec.IMAGE_STD[2]
        }
        return values
    } finally {
        target.recycle()
    }
}

fun interface MultimodalTextEmbedder {
    suspend fun embedText(text: String): MultimodalEmbedding
}

class LocalMultimodalEmbedder internal constructor(
    private val assetSource: JapaneseClipAssetSource,
    private val modelDirectory: File,
    private val ioDispatcher: CoroutineDispatcher,
) : AutoCloseable, ImageEmbedder, MultimodalTextEmbedder {
    constructor(context: Context) : this(
        assetSource = AssetManagerJapaneseClipAssetSource(context.assets),
        modelDirectory = File(
            context.noBackupFilesDir,
            "multimodal_embedding/japanese_clip/${JapaneseClipModelSpec.REVISION}",
        ),
        ioDispatcher = Dispatchers.IO,
    )

    private val lock = ReentrantLock()
    private var installed: InstalledJapaneseClipAssets? = null
    private var tokenizer: HuggingFaceTokenizer? = null
    private var textSession: JapaneseClipOnnxSession? = null
    private var visionSession: JapaneseClipOnnxSession? = null
    private var closed = false

    internal var assetInstallationCountForTest: Int = 0
        private set
    internal var tokenizerInitializationCountForTest: Int = 0
        private set
    internal var textSessionInitializationCountForTest: Int = 0
        private set
    internal var visionSessionInitializationCountForTest: Int = 0
        private set

    internal val textInputNamesForTest: Set<String>
        get() = lock.withLock { textSession?.inputNames.orEmpty() }
    internal val visionInputNamesForTest: Set<String>
        get() = lock.withLock { visionSession?.inputNames.orEmpty() }
    internal val textOutputNamesForTest: Set<String>
        get() = lock.withLock { textSession?.outputNames.orEmpty() }
    internal val visionOutputNamesForTest: Set<String>
        get() = lock.withLock { visionSession?.outputNames.orEmpty() }

    override suspend fun embedText(text: String): MultimodalEmbedding {
        require(text.isNotBlank()) { "Japanese CLIP text input must not be blank" }
        return withContext(ioDispatcher) {
            lock.withLock {
                check(!closed) { "LocalMultimodalEmbedder is closed" }
                val runtime = textSession ?: createTextSession().also { textSession = it }
                runtime.embedText(requireNotNull(tokenizer).encode(text, false, false))
            }
        }
    }

    override suspend fun embedImage(bitmap: Bitmap): MultimodalEmbedding {
        require(!bitmap.isRecycled) { "Japanese CLIP image must not be recycled" }
        require(bitmap.width > 0 && bitmap.height > 0) { "Japanese CLIP image must have positive dimensions" }
        return withContext(ioDispatcher) {
            lock.withLock {
                check(!closed) { "LocalMultimodalEmbedder is closed" }
                val runtime = visionSession ?: createVisionSession().also { visionSession = it }
                runtime.embedImage(preprocessJapaneseClipImage(bitmap))
            }
        }
    }

    override fun close() {
        lock.withLock {
            if (closed) return
            closed = true
            var failure: Throwable? = null
            runCatching { visionSession?.close() }.onFailure { failure = it }
            runCatching { textSession?.close() }.onFailure { if (failure == null) failure = it }
            runCatching { tokenizer?.close() }.onFailure { if (failure == null) failure = it }
            visionSession = null
            textSession = null
            tokenizer = null
            installed = null
            failure?.let { throw it }
        }
    }

    private fun installAssets(): InstalledJapaneseClipAssets = installed ?: JapaneseClipAssetInstaller(
        source = assetSource,
        modelDirectory = modelDirectory,
    ).install().also {
        installed = it
        assetInstallationCountForTest += 1
    }

    private fun createTextSession(): JapaneseClipOnnxSession {
        val assets = installAssets()
        configureOfflineDjl()
        val newTokenizer = FileInputStream(assets.tokenizerFile).use { input ->
            HuggingFaceTokenizer.newInstance(
                input,
                mapOf(
                    "truncation" to "longest_first",
                    "padding" to "do_not_pad",
                    "maxLength" to JapaneseClipModelSpec.MAX_TOKENIZER_TOKEN_LENGTH.toString(),
                    "modelMaxLength" to JapaneseClipModelSpec.MAX_TOKENIZER_TOKEN_LENGTH.toString(),
                    "withOverflowingTokens" to "false",
                ),
            )
        }
        return try {
            tokenizer = newTokenizer
            tokenizerInitializationCountForTest += 1
            createSession(assets.textModelFile, JapaneseClipSessionKind.TEXT).also {
                textSessionInitializationCountForTest += 1
            }
        } catch (failure: Throwable) {
            runCatching { newTokenizer.close() }
            tokenizer = null
            throw failure
        }
    }

    private fun createVisionSession(): JapaneseClipOnnxSession {
        val assets = installAssets()
        return createSession(assets.visionModelFile, JapaneseClipSessionKind.VISION).also {
            visionSessionInitializationCountForTest += 1
        }
    }

    private fun createSession(modelFile: File, kind: JapaneseClipSessionKind): JapaneseClipOnnxSession {
        val environment = OrtEnvironment.getEnvironment()
        val options = OrtSession.SessionOptions().apply {
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            setIntraOpNumThreads(2)
            setInterOpNumThreads(1)
        }
        val session = try {
            environment.createSession(modelFile.absolutePath, options)
        } finally {
            options.close()
        }
        return try {
            JapaneseClipOnnxSession(environment, session, kind)
        } catch (failure: Throwable) {
            runCatching { session.close() }
            throw failure
        }
    }

    private fun configureOfflineDjl() {
        System.setProperty("ai.djl.offline", "true")
        System.setProperty("OPT_OUT_TRACKING", "true")
    }
}

private enum class JapaneseClipSessionKind { TEXT, VISION }

private class JapaneseClipOnnxSession(
    private val environment: OrtEnvironment,
    private val session: OrtSession,
    private val kind: JapaneseClipSessionKind,
) : AutoCloseable {
    private val inputInfo = session.inputInfo
    private val outputInfo = session.outputInfo
    private val outputName: String

    val inputNames: Set<String>
        get() = inputInfo.keys.toSet()
    val outputNames: Set<String>
        get() = outputInfo.keys.toSet()

    init {
        outputName = findEmbeddingOutputName()
        when (kind) {
            JapaneseClipSessionKind.TEXT -> validateTextInputs()
            JapaneseClipSessionKind.VISION -> validateVisionInputs()
        }
    }

    fun embedText(encoding: ai.djl.huggingface.tokenizers.Encoding): MultimodalEmbedding {
        val inputs = buildJapaneseClipTokenInputs(encoding.ids)
        val tensors = linkedMapOf<String, OnnxTensor>()
        try {
            tensors["input_ids"] = createTokenTensor("input_ids", inputs.inputIds)
            tensors["attention_mask"] = createTokenTensor("attention_mask", inputs.attentionMask)
            tensors["position_ids"] = createTokenTensor("position_ids", inputs.positionIds)
            check(tensors.keys == inputInfo.keys) {
                "Japanese CLIP text inputs do not match session metadata: ${tensors.keys} vs ${inputInfo.keys}"
            }
            return runModel(tensors)
        } finally {
            tensors.values.forEach { it.close() }
        }
    }

    fun embedImage(values: FloatArray): MultimodalEmbedding {
        check(values.size == 3 * JapaneseClipModelSpec.IMAGE_SIZE * JapaneseClipModelSpec.IMAGE_SIZE)
        val tensor = OnnxTensor.createTensor(
            environment,
            FloatBuffer.wrap(values),
            longArrayOf(1, 3, JapaneseClipModelSpec.IMAGE_SIZE.toLong(), JapaneseClipModelSpec.IMAGE_SIZE.toLong()),
        )
        return try {
            runModel(linkedMapOf(inputInfo.keys.single() to tensor))
        } finally {
            tensor.close()
        }
    }

    private fun runModel(tensors: Map<String, OnnxTensor>): MultimodalEmbedding {
        val result = try {
            session.run(tensors)
        } catch (failure: Throwable) {
            throw IllegalStateException("Japanese CLIP ${kind.name.lowercase()} inference failed", failure)
        }
        return try {
            val output = result.get(outputName).orElseThrow {
                IllegalStateException("Japanese CLIP returned no $outputName output")
            }
            val tensor = output as? OnnxTensor
                ?: throw IllegalStateException("Japanese CLIP output is not an ONNX tensor")
            check((tensor.info as? TensorInfo)?.type == OnnxJavaType.FLOAT) {
                "Japanese CLIP output must be float"
            }
            val buffer = tensor.floatBuffer.duplicate().apply { rewind() }
            val values = FloatArray(buffer.remaining())
            buffer.get(values)
            MultimodalEmbedding.fromModelOutput(values)
        } finally {
            result.close()
        }
    }

    private fun validateTextInputs() {
        val expected = setOf("input_ids", "attention_mask", "position_ids")
        check(inputInfo.keys == expected) {
            "Japanese CLIP text model has unsupported inputs: ${inputInfo.keys}"
        }
        inputInfo.forEach { (name, node) ->
            val info = node.info as? TensorInfo
                ?: error("Japanese CLIP text input is not a tensor: $name")
            check(info.type == OnnxJavaType.INT64 || info.type == OnnxJavaType.INT32) {
                "Japanese CLIP text input has unsupported type for $name: ${info.type}"
            }
            validateTokenShape(name, info)
        }
    }

    private fun validateVisionInputs() {
        check(inputInfo.size == 1) {
            "Japanese CLIP vision model has unsupported inputs: ${inputInfo.keys}"
        }
        val name = inputInfo.keys.single()
        val info = inputInfo.getValue(name).info as? TensorInfo
            ?: error("Japanese CLIP vision input is not a tensor: $name")
        check(info.type == OnnxJavaType.FLOAT) {
            "Japanese CLIP vision input must be float: ${info.type}"
        }
        val shape = info.shape
        check(shape.size == 4) { "Japanese CLIP vision input must be rank 4: ${shape.contentToString()}" }
        check(shape[0] == -1L || shape[0] == 1L) { "Japanese CLIP vision batch must be 1" }
        check(shape[1] == -1L || shape[1] == 3L) { "Japanese CLIP vision channels must be 3" }
        check(shape[2] == -1L || shape[2] == JapaneseClipModelSpec.IMAGE_SIZE.toLong()) {
            "Japanese CLIP vision height must be 224"
        }
        check(shape[3] == -1L || shape[3] == JapaneseClipModelSpec.IMAGE_SIZE.toLong()) {
            "Japanese CLIP vision width must be 224"
        }
    }

    private fun validateTokenShape(name: String, info: TensorInfo) {
        val shape = info.shape
        check(shape.size == 2) { "Japanese CLIP text input must be rank 2: $name" }
        check(shape[0] == -1L || shape[0] == 1L) { "Japanese CLIP text batch must be 1: $name" }
        check(
            shape[1] == -1L ||
                (shape[1] in 1L..JapaneseClipModelSpec.MAX_TEXT_TOKEN_LENGTH.toLong()),
        ) { "Japanese CLIP text sequence length is unsupported: $name" }
    }

    private fun createTokenTensor(name: String, values: LongArray): OnnxTensor {
        val info = inputInfo[name]?.info as? TensorInfo
            ?: throw IllegalStateException("Japanese CLIP text input is not a tensor: $name")
        return when (info.type) {
            OnnxJavaType.INT64 -> OnnxTensor.createTensor(environment, arrayOf(values))
            OnnxJavaType.INT32 -> OnnxTensor.createTensor(
                environment,
                arrayOf(values.map(Long::toInt).toIntArray()),
            )
            else -> error("Unsupported Japanese CLIP text input type for $name: ${info.type}")
        }
    }

    private fun findEmbeddingOutputName(): String {
        val candidates = outputInfo.mapNotNull { (name, node) ->
            val info = node.info as? TensorInfo ?: return@mapNotNull null
            if (info.type != OnnxJavaType.FLOAT) return@mapNotNull null
            val shape = info.shape
            if (shape.lastOrNull() == JapaneseClipModelSpec.EMBEDDING_DIMENSION.toLong()) name else null
        }
        check(candidates.size == 1) {
            "Japanese CLIP must expose exactly one float 256-dimensional output: ${outputInfo.keys}"
        }
        return candidates.single()
    }

    override fun close() = session.close()
}

package com.lyco256.llm.data

import android.content.Context
import android.graphics.Bitmap
import com.paddle.ocr.EngineConfig
import com.paddle.ocr.PaddleOCR
import com.paddle.ocr.PaddleOCRConfig
import com.paddle.ocr.util.OpenCVUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal enum class PaddleOcrModelVariant(
    val detectorAssetPath: String,
    val recognizerAssetPath: String,
    val recognitionConfigAssetPath: String,
) {
    SMALL(
        detectorAssetPath = "models/small/det/inference.onnx",
        recognizerAssetPath = "models/small/rec/inference.onnx",
        recognitionConfigAssetPath = "models/small/rec/inference.yml",
    ),
    MEDIUM(
        detectorAssetPath = "models/medium/det/inference.onnx",
        recognizerAssetPath = "models/medium/rec/inference.onnx",
        recognitionConfigAssetPath = "models/medium/rec/inference.yml",
    ),
}

internal fun OcrQualityMode.modelVariant(): PaddleOcrModelVariant = when (this) {
    OcrQualityMode.FAST -> PaddleOcrModelVariant.SMALL
    OcrQualityMode.ACCURATE -> PaddleOcrModelVariant.MEDIUM
}

internal interface PaddleOcrEngine {
    suspend fun recognize(bitmap: Bitmap): List<PaddleOcrRawResult>
    suspend fun release()
}

internal fun interface PaddleOcrEngineFactory {
    suspend fun create(variant: PaddleOcrModelVariant): PaddleOcrEngine
}

internal class PaddleOcrEnginePool(
    private val factory: PaddleOcrEngineFactory,
) {
    private var activeVariant: PaddleOcrModelVariant? = null
    private var activeEngine: PaddleOcrEngine? = null

    suspend fun get(variant: PaddleOcrModelVariant): PaddleOcrEngine {
        if (activeVariant == variant) return checkNotNull(activeEngine)

        activeEngine?.release()
        activeEngine = null
        activeVariant = null
        return factory.create(variant).also {
            activeVariant = variant
            activeEngine = it
        }
    }

    suspend fun close() {
        activeEngine?.release()
        activeEngine = null
        activeVariant = null
    }
}

private class SdkPaddleOcrEngine(
    private val engine: PaddleOCR,
) : PaddleOcrEngine {
    override suspend fun recognize(bitmap: Bitmap): List<PaddleOcrRawResult> =
        engine.recognize(bitmap).results.map { result ->
            PaddleOcrRawResult(
                text = result.text,
                confidence = result.confidence,
                points = result.box.points.map { OcrPoint(it.x, it.y) },
            )
        }

    override suspend fun release() = engine.release()
}

class PaddleOcrTextGateway internal constructor(
    context: Context,
    engineFactory: PaddleOcrEngineFactory? = null,
) : OcrTextGateway {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private val factory = engineFactory ?: PaddleOcrEngineFactory { variant ->
        check(OpenCVUtils.init(appContext)) { "OpenCVの初期化に失敗しました" }
        SdkPaddleOcrEngine(
            PaddleOCR.create(
                context = appContext,
                config = PaddleOCRConfig(),
                engineConfig = EngineConfig(),
                detModelAssetPath = variant.detectorAssetPath,
                recModelAssetPath = variant.recognizerAssetPath,
                recConfigAssetPath = variant.recognitionConfigAssetPath,
            ),
        )
    }
    private val enginePool = PaddleOcrEnginePool(factory)

    override suspend fun recognize(bitmap: Bitmap, mode: OcrQualityMode): OcrRecognitionResult =
        withContext(Dispatchers.Default) {
            mutex.withLock {
                val rawResults = enginePool.get(mode.modelVariant()).recognize(bitmap)
                rawResults.toOcrRecognitionResult(bitmap.width, bitmap.height)
            }
        }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock { enginePool.close() }
    }
}

internal data class PaddleOcrRawResult(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
)

internal fun List<PaddleOcrRawResult>.toOcrRecognitionResult(
    imageWidth: Int,
    imageHeight: Int,
): OcrRecognitionResult {
    val regions = buildList {
        for (raw in this@toOcrRecognitionResult) {
            val text = raw.text.trim().takeIf(String::isNotBlank) ?: continue
            add(
                OcrTextRegion(
                    text = text,
                    polygon = normalizeOcrPolygon(raw.points),
                    confidence = raw.confidence,
                    precedingSeparator = if (isEmpty()) "" else "\n",
                ),
            )
        }
    }
    return OcrRecognitionResult(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        fullText = regions.joinToString("") { it.precedingSeparator + it.text },
        regions = regions,
    )
}

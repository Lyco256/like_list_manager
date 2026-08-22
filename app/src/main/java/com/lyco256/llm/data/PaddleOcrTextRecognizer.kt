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

/**
 * PP-OCRv6_small adapter. The official SDK owns preprocessing, BoxSorter, crop,
 * recognition, and ONNX/OpenCV resource management; this class only owns its
 * lazy process-scoped instance and converts the public result into our contract.
 */
class PaddleOcrTextGateway(context: Context) : OcrTextGateway {
    private val appContext = context.applicationContext
    private val mutex = Mutex()
    private var paddleOcr: PaddleOCR? = null

    override suspend fun recognize(bitmap: Bitmap): OcrRecognitionResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            val engine = paddleOcr ?: createEngine().also { paddleOcr = it }
            engine.recognize(bitmap).results
                .map { result ->
                    PaddleOcrRawResult(
                        text = result.text,
                        confidence = result.confidence,
                        points = result.box.points.map { OcrPoint(it.x, it.y) },
                    )
                }
                .toOcrRecognitionResult(bitmap.width, bitmap.height)
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            paddleOcr?.release()
            paddleOcr = null
        }
    }

    private suspend fun createEngine(): PaddleOCR {
        check(OpenCVUtils.init(appContext)) { "OpenCVの初期化に失敗しました" }
        return PaddleOCR.create(
            context = appContext,
            config = PaddleOCRConfig(),
            engineConfig = EngineConfig(),
            detModelAssetPath = "models/det/inference.onnx",
            recModelAssetPath = "models/rec/inference.onnx",
            recConfigAssetPath = "models/rec/inference.yml",
        )
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
    val regions = mapNotNull { result ->
        val text = result.text.trim()
        if (text.isBlank()) return@mapNotNull null
        val polygon = runCatching {
            normalizeOcrPolygon(result.points)
        }.getOrNull()
        OcrTextRegion(
            text = text,
            polygon = polygon,
            confidence = result.confidence,
            precedingSeparator = "",
        )
    }.mapIndexed { index, region ->
        if (index == 0) region else region.copy(precedingSeparator = "\n")
    }
    return OcrRecognitionResult(
        imageWidth = imageWidth,
        imageHeight = imageHeight,
        fullText = regions.joinToString(separator = "") { it.precedingSeparator + it.text },
        regions = regions,
    )
}

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
import java.util.EnumMap

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
    private val engines = EnumMap<PaddleOcrModelVariant, PaddleOcrEngine>(PaddleOcrModelVariant::class.java)

    suspend fun get(variant: PaddleOcrModelVariant): PaddleOcrEngine {
        engines[variant]?.let { return it }
        engines.filterKeys { it != variant }.values.toList().forEach { it.release() }
        engines.clear()
        return factory.create(variant).also { engines[variant] = it }
    }

    suspend fun close() {
        engines.values.toList().forEach { it.release() }
        engines.clear()
    }
}

private class SdkPaddleOcrEngine(private val engine: PaddleOCR) : PaddleOcrEngine {
    override suspend fun recognize(bitmap: Bitmap): List<PaddleOcrRawResult> = engine.recognize(bitmap).results.map { result ->
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

    override suspend fun recognize(bitmap: Bitmap): OcrRecognitionResult = withContext(Dispatchers.Default) {
        mutex.withLock {
            recognizeForVariant(bitmap, PaddleOcrModelVariant.SMALL).recognition
        }
    }

    override suspend fun recognizeForComparison(
        bitmap: Bitmap,
        engine: OcrEngine,
    ): OcrGatewayRecognition = withContext(Dispatchers.Default) {
        mutex.withLock {
            when (engine) {
                OcrEngine.ML_KIT -> OcrGatewayRecognition(recognizeWithEngine(bitmap, PaddleOcrModelVariant.SMALL))
                OcrEngine.PP_OCRV6_SMALL -> recognizeForVariant(bitmap, PaddleOcrModelVariant.SMALL)
                OcrEngine.PP_OCRV6_MEDIUM -> recognizeForVariant(bitmap, PaddleOcrModelVariant.MEDIUM)
                OcrEngine.PP_OCRV6_MEDIUM_TILE -> recognizeMediumWithTiles(bitmap)
            }
        }
    }

    suspend fun close() = withContext(Dispatchers.IO) {
        mutex.withLock {
            enginePool.close()
        }
    }

    private suspend fun recognizeForVariant(
        bitmap: Bitmap,
        variant: PaddleOcrModelVariant,
    ): OcrGatewayRecognition = OcrGatewayRecognition(
        recognition = recognizeWithEngine(bitmap, variant),
    )

    private suspend fun recognizeWithEngine(
        bitmap: Bitmap,
        variant: PaddleOcrModelVariant,
    ): OcrRecognitionResult {
        val candidates = engineFor(variant).recognize(bitmap)
            .toPaddleOcrCandidates(PaddleOcrCandidateSource.WHOLE_IMAGE)
        return candidates.toPaddleOcrRecognitionResult(bitmap.width, bitmap.height)
    }

    private suspend fun recognizeMediumWithTiles(bitmap: Bitmap): OcrGatewayRecognition {
        val engine = engineFor(PaddleOcrModelVariant.MEDIUM)
        val wholeCandidates = engine.recognize(bitmap)
            .toPaddleOcrCandidates(PaddleOcrCandidateSource.WHOLE_IMAGE)
        val tiles = buildPaddleOcrTiles(bitmap.width, bitmap.height)
        val tileCandidates = mutableListOf<PaddleOcrCandidate>()
        var tileFailureCount = 0
        tiles.forEach { tile ->
            try {
                val scale = paddleOcrScaleForTile(tile)
                val cropped = Bitmap.createBitmap(bitmap, tile.left, tile.top, tile.width, tile.height)
                val scaled = if (scale == 1f) {
                    cropped
                } else {
                    Bitmap.createScaledBitmap(
                        cropped,
                        (tile.width * scale).toInt().coerceAtLeast(1),
                        (tile.height * scale).toInt().coerceAtLeast(1),
                        true,
                    ).also { cropped.recycle() }
                }
                try {
                    engine.recognize(scaled).forEach { raw ->
                        mapPaddleOcrTileCandidate(
                            raw = raw,
                            tile = tile,
                            scale = scale,
                            imageWidth = bitmap.width,
                            imageHeight = bitmap.height,
                        )?.let(tileCandidates::add)
                    }
                } finally {
                    scaled.recycle()
                }
            } catch (_: Throwable) {
                tileFailureCount++
            }
        }
        return OcrGatewayRecognition(
            recognition = (wholeCandidates + tileCandidates)
                .toPaddleOcrRecognitionResult(bitmap.width, bitmap.height),
            tileCount = tiles.size,
            tileFailureCount = tileFailureCount,
        )
    }

    private suspend fun engineFor(variant: PaddleOcrModelVariant): PaddleOcrEngine =
        enginePool.get(variant)
}

internal data class PaddleOcrRawResult(
    val text: String,
    val confidence: Float,
    val points: List<OcrPoint>,
)

internal fun List<PaddleOcrRawResult>.toOcrRecognitionResult(
    imageWidth: Int,
    imageHeight: Int,
): OcrRecognitionResult = toPaddleOcrCandidates(PaddleOcrCandidateSource.WHOLE_IMAGE)
    .toPaddleOcrRecognitionResult(imageWidth, imageHeight)

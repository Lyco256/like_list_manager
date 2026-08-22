package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrQualityMode
import com.lyco256.llm.data.OcrPostRecognitionResult

internal data class OcrRegionKey(
    val assetId: Long,
    val regionIndex: Int,
)

internal typealias OcrLegacyDetectHandler = (
    ClipWithDetails,
    (String) -> Unit,
    (String) -> Unit,
) -> Unit

internal typealias OcrStructuredDetectHandler = (
    ClipWithDetails,
    OcrQualityMode,
    (OcrPostRecognitionResult) -> Unit,
    (String) -> Unit,
) -> Unit

internal typealias OcrSaveResultHandler = (
    ClipEntity,
    String,
    (String?) -> Unit,
) -> Unit

internal data class OcrImagePage(
    val assetId: Long,
    val localPath: String,
    val imageWidth: Int? = null,
    val imageHeight: Int? = null,
)

internal fun OcrPostRecognitionResult.assetFor(assetId: Long): OcrAssetRecognitionResult? =
    assets.firstOrNull { it.assetId == assetId }

internal fun OcrPostRecognitionResult.rebuildFromRegions(): OcrPostRecognitionResult {
    val rebuiltAssets = assets.map { asset ->
        asset.copy(
            recognition = asset.recognition.copy(
                fullText = asset.recognition.rebuildFullText(),
            ),
        )
    }
    return copy(
        assets = rebuiltAssets,
        fullText = rebuiltAssets
            .map { it.recognition.fullText.takeIf(String::isNotBlank) }
            .filterNotNull()
            .joinToString("\n\n"),
    )
}

private fun com.lyco256.llm.data.OcrRecognitionResult.rebuildFullText(): String {
    if (regions.isEmpty()) return fullText
    return buildString {
        var emittedRegion = false
        regions.forEach { region ->
            if (region.text.isBlank()) return@forEach
            if (emittedRegion) append(region.precedingSeparator)
            append(region.text)
            emittedRegion = true
        }
    }
}

internal fun OcrPostRecognitionResult.withRegionText(
    key: OcrRegionKey,
    text: String,
): OcrPostRecognitionResult? {
    var changed = false
    val updatedAssets = assets.map { asset ->
        if (asset.assetId != key.assetId) return@map asset
        val updatedRegions = asset.recognition.regions.mapIndexed { index, region ->
            if (index != key.regionIndex) return@mapIndexed region
            changed = true
            region.copy(text = text)
        }
        asset.copy(recognition = asset.recognition.copy(regions = updatedRegions))
    }
    return if (changed) copy(assets = updatedAssets).rebuildFromRegions() else null
}

internal data class OcrSessionState(
    val clipId: Long,
    val savedText: String,
    val draftText: String = savedText,
    val structuredResult: OcrPostRecognitionResult? = null,
    val structuredResultGeneration: Long = 0L,
    val qualityMode: OcrQualityMode = OcrQualityMode.FAST,
    val isDetecting: Boolean = false,
    val isSaving: Boolean = false,
    val errorMessage: String? = null,
)

internal class OcrSessionController(
    clip: ClipWithDetails,
    private val onStateChanged: (OcrSessionState) -> Unit = {},
) {
    private var active = true
    private var requestToken = 0L
    private var saveToken = 0L
    private var automaticDetectionStarted = false

    var state: OcrSessionState = OcrSessionState(
        clipId = clip.clip.id,
        savedText = clip.clip.ocrText,
    )
        private set

    private val clip = clip

    fun startAutomaticDetection(detect: OcrStructuredDetectHandler) {
        if (automaticDetectionStarted) return
        automaticDetectionStarted = true
        if (state.savedText.isBlank()) startDetection(detect, OcrQualityMode.FAST)
    }

    fun selectQualityMode(mode: OcrQualityMode) {
        if (!active || state.isDetecting || state.isSaving || state.qualityMode == mode) return
        publish(state.copy(qualityMode = mode, errorMessage = null))
    }

    fun redetect(detect: OcrStructuredDetectHandler) {
        startDetection(detect, state.qualityMode)
    }

    fun editDraft(value: String) {
        if (!active || state.isDetecting || state.isSaving || state.structuredResult != null) return
        publish(state.copy(draftText = value, errorMessage = null))
    }

    fun editRegion(key: OcrRegionKey, value: String) {
        if (!active || state.isDetecting || state.isSaving) return
        val current = state.structuredResult ?: return
        val updated = current.withRegionText(key, value) ?: return
        publish(state.copy(draftText = updated.fullText, structuredResult = updated, errorMessage = null))
    }

    fun save(save: OcrSaveResultHandler, onSaved: () -> Unit) {
        if (!active || state.isDetecting || state.isSaving) return
        val token = ++saveToken
        publish(state.copy(isSaving = true, errorMessage = null))
        runCatching {
            save(clip.clip, state.draftText) callback@{ errorMessage ->
                if (!active || !state.isSaving || token != saveToken) return@callback
                publish(state.copy(isSaving = false, errorMessage = errorMessage))
                if (errorMessage == null) onSaved()
            }
        }.onFailure { error ->
            if (active && state.isSaving && token == saveToken) {
                publish(state.copy(isSaving = false, errorMessage = error.message ?: "OCRの保存に失敗しました"))
            }
        }
    }

    fun dismiss(): Boolean {
        if (!active || state.isSaving) return false
        invalidate()
        return true
    }

    fun invalidate() {
        if (!active) return
        active = false
        requestToken++
        saveToken++
    }

    private fun startDetection(
        detect: OcrStructuredDetectHandler,
        mode: OcrQualityMode,
    ) {
        if (!active || state.isDetecting || state.isSaving) return
        val token = ++requestToken
        publish(state.copy(isDetecting = true, errorMessage = null))
        val onSuccess: (OcrPostRecognitionResult) -> Unit = success@{ result ->
            if (!active || !state.isDetecting || token != requestToken || result.clipId != state.clipId) return@success
            val rebuilt = result.rebuildFromRegions()
            publish(
                state.copy(
                    draftText = rebuilt.fullText,
                    structuredResult = rebuilt,
                    structuredResultGeneration = state.structuredResultGeneration + 1L,
                    isDetecting = false,
                    errorMessage = null,
                ),
            )
        }
        val onFailure: (String) -> Unit = failure@{ message ->
            if (!active || !state.isDetecting || token != requestToken) return@failure
            publish(state.copy(isDetecting = false, errorMessage = message))
        }
        runCatching { detect(clip, mode, onSuccess, onFailure) }
            .onFailure { error -> onFailure(error.message ?: "画像認識に失敗しました") }
    }

    private fun publish(value: OcrSessionState) {
        if (!active) return
        state = value
        onStateChanged(value)
    }
}

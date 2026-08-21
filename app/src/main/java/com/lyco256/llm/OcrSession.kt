package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPostRecognitionResult

internal typealias OcrLegacyDetectHandler = (
    ClipWithDetails,
    (String) -> Unit,
    (String) -> Unit,
) -> Unit

internal typealias OcrStructuredDetectHandler = (
    ClipWithDetails,
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

internal data class OcrSessionState(
    val clipId: Long,
    val savedText: String,
    val draftText: String = savedText,
    val structuredResult: OcrPostRecognitionResult? = null,
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
        if (state.savedText.isBlank()) startDetection(detect)
    }

    fun redetect(detect: OcrStructuredDetectHandler) {
        startDetection(detect)
    }

    fun editDraft(value: String) {
        if (!active || state.isDetecting || state.isSaving) return
        publish(state.copy(draftText = value, errorMessage = null))
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

    private fun startDetection(detect: OcrStructuredDetectHandler) {
        if (!active || state.isDetecting || state.isSaving) return
        val token = ++requestToken
        publish(state.copy(isDetecting = true, errorMessage = null))
        val onSuccess: (OcrPostRecognitionResult) -> Unit = success@{ result ->
            if (!active || !state.isDetecting || token != requestToken || result.clipId != state.clipId) return@success
            publish(
                state.copy(
                    draftText = result.fullText,
                    structuredResult = result,
                    isDetecting = false,
                    errorMessage = null,
                ),
            )
        }
        val onFailure: (String) -> Unit = failure@{ message ->
            if (!active || !state.isDetecting || token != requestToken) return@failure
            publish(state.copy(isDetecting = false, errorMessage = message))
        }
        runCatching { detect(clip, onSuccess, onFailure) }
            .onFailure { error -> onFailure(error.message ?: "画像認識に失敗しました") }
    }

    private fun publish(value: OcrSessionState) {
        if (!active) return
        state = value
        onStateChanged(value)
    }
}

package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OcrSessionTest {
    @Test
    fun savedTextStartsTheSessionWithoutAutomaticDetection() {
        val controller = OcrSessionController(clip(savedText = "saved"))
        var detectCalls = 0

        controller.startAutomaticDetection { _, _, _ -> detectCalls++ }

        assertEquals(0, detectCalls)
        assertEquals("saved", controller.state.savedText)
        assertEquals("saved", controller.state.draftText)
        assertFalse(controller.state.isDetecting)
    }

    @Test
    fun emptyTextStartsAutomaticDetectionOnceAndPublishesStructuredResult() {
        val controller = OcrSessionController(clip())
        var detectCalls = 0
        var success: ((OcrPostRecognitionResult) -> Unit)? = null
        val result = result(7, "detected")

        val detect: OcrStructuredDetectHandler = { _, onSuccess, _ ->
            detectCalls++
            success = onSuccess
        }
        controller.startAutomaticDetection(detect)
        controller.startAutomaticDetection(detect)

        assertEquals(1, detectCalls)
        assertTrue(controller.state.isDetecting)
        success!!.invoke(result)
        assertEquals("detected", controller.state.draftText)
        assertEquals(result, controller.state.structuredResult)
        assertFalse(controller.state.isDetecting)
    }

    @Test
    fun failedRedetectKeepsDraftAndPreviousStructuredResult() {
        val controller = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> firstSuccess = onSuccess }
        val firstResult = result(7, "first")
        firstSuccess!!.invoke(firstResult)
        controller.editDraft("manual edit")

        var failure: ((String) -> Unit)? = null
        controller.redetect { _, _, onFailure -> failure = onFailure }
        failure!!.invoke("recognition failed")

        assertEquals("manual edit", controller.state.draftText)
        assertEquals(firstResult, controller.state.structuredResult)
        assertEquals("recognition failed", controller.state.errorMessage)
        assertFalse(controller.state.isDetecting)
    }

    @Test
    fun staleRequestAndDismissedSessionCannotPublishResults() {
        val controller = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        var secondSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> firstSuccess = onSuccess }
        firstSuccess!!(result(7, "first"))
        controller.redetect { _, onSuccess, _ -> secondSuccess = onSuccess }
        val oldResult = result(7, "old")
        val newResult = result(7, "new")

        firstSuccess!!(oldResult)
        assertTrue(controller.state.isDetecting)
        secondSuccess!!(newResult)
        assertEquals("new", controller.state.draftText)

        var thirdSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.redetect { _, onSuccess, _ -> thirdSuccess = onSuccess }
        controller.dismiss()
        thirdSuccess!!(result(7, "after dismiss"))
        assertEquals("new", controller.state.draftText)
    }

    @Test
    fun saveIsTheOnlyPersistenceCallbackAndBlocksConcurrentOperations() {
        val controller = OcrSessionController(clip())
        controller.editDraft("draft")
        var saveCalls = 0
        var complete: ((String?) -> Unit)? = null
        var saved = 0
        val save: OcrSaveResultHandler = { _, text, onComplete ->
            saveCalls++
            assertEquals("draft", text)
            complete = onComplete
        }

        controller.save(save) { saved++ }
        controller.save(save) { saved++ }
        controller.editDraft("changed while saving")

        assertEquals(1, saveCalls)
        assertTrue(controller.state.isSaving)
        assertEquals("draft", controller.state.draftText)
        complete!!.invoke("save failed")
        assertFalse(controller.state.isSaving)
        assertEquals("save failed", controller.state.errorMessage)
        assertEquals(0, saved)

        controller.save(save) { saved++ }
        complete!!.invoke(null)
        assertEquals(1, saved)
        assertFalse(controller.state.isSaving)
    }

    @Test
    fun saveInProgressCannotBeDismissedBeforeItsResultArrives() {
        val controller = OcrSessionController(clip())
        var complete: ((String?) -> Unit)? = null
        var saved = 0
        controller.save({ _, _, onComplete -> complete = onComplete }) { saved++ }
        assertFalse(controller.dismiss())
        complete!!.invoke(null)

        assertEquals(1, saved)
    }

    private fun clip(savedText: String = ""): ClipWithDetails {
        val entity = ClipEntity(
            id = 7,
            xPostId = "ocr-session",
            authorName = "Author",
            authorUsername = "author",
            text = "text",
            postUrl = "https://example.test/post",
            xCreatedAt = "2026-01-01T00:00:00Z",
            savedAt = "2026-01-01T00:00:00Z",
            syncedAt = "2026-01-01T00:00:00Z",
            ocrText = savedText,
        )
        return ClipWithDetails(entity, emptyList(), emptyList())
    }

    private fun result(clipId: Long, text: String): OcrPostRecognitionResult =
        OcrPostRecognitionResult(
            clipId = clipId,
            assets = listOf(
                OcrAssetRecognitionResult(
                    assetId = 1,
                    localPath = "/tmp/ocr.webp",
                    recognition = OcrRecognitionResult(100, 80, text),
                ),
            ),
            fullText = text,
        )
}

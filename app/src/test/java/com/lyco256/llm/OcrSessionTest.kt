package com.lyco256.llm

import com.lyco256.llm.data.ClipEntity
import com.lyco256.llm.data.ClipWithDetails
import com.lyco256.llm.data.OcrAssetRecognitionResult
import com.lyco256.llm.data.OcrPoint
import com.lyco256.llm.data.OcrPostRecognitionResult
import com.lyco256.llm.data.OcrRecognitionResult
import com.lyco256.llm.data.OcrPolygon
import com.lyco256.llm.data.OcrTextRegion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
        controller.editRegion(OcrRegionKey(1L, 0), "manual edit")

        var failure: ((String) -> Unit)? = null
        controller.redetect { _, _, onFailure -> failure = onFailure }
        failure!!.invoke("recognition failed")

        assertEquals("manual edit", controller.state.draftText)
        assertEquals(
            "manual edit",
            controller.state.structuredResult!!.assets.single().recognition.regions.single().text,
        )
        assertEquals("recognition failed", controller.state.errorMessage)
        assertFalse(controller.state.isDetecting)
    }

    @Test
    fun successfulRedetectReplacesDraftAndStructuredResultWithoutPersistence() {
        val controller = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> firstSuccess = onSuccess }
        val firstResult = result(7, "first")
        firstSuccess!!.invoke(firstResult)
        controller.editRegion(OcrRegionKey(1L, 0), "manual edit")

        var redetectSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.redetect { _, onSuccess, _ -> redetectSuccess = onSuccess }
        val replacement = result(7, "replacement")
        redetectSuccess!!.invoke(replacement)

        assertEquals("replacement", controller.state.draftText)
        assertEquals(replacement, controller.state.structuredResult)
        assertFalse(controller.state.isDetecting)
    }

    @Test
    fun redetectWhileDetectingDoesNotStartASecondRequest() {
        val controller = OcrSessionController(clip())
        var detectCalls = 0

        controller.startAutomaticDetection { _, _, _ -> detectCalls++ }
        controller.redetect { _, _, _ -> detectCalls++ }

        assertEquals(1, detectCalls)
        assertTrue(controller.state.isDetecting)
    }

    @Test
    fun automaticDetectionFailureLeavesDraftAndStructuredResultUnchanged() {
        val controller = OcrSessionController(clip())
        var failure: ((String) -> Unit)? = null

        controller.startAutomaticDetection { _, _, onFailure -> failure = onFailure }
        failure!!.invoke("recognition failed")

        assertEquals("", controller.state.draftText)
        assertNull(controller.state.structuredResult)
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
    fun staleFailureCannotOverwriteANewerDetection() {
        val controller = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        var firstFailure: ((String) -> Unit)? = null
        var secondFailure: ((String) -> Unit)? = null

        controller.startAutomaticDetection { _, onSuccess, onFailure ->
            firstSuccess = onSuccess
            firstFailure = onFailure
        }
        firstSuccess!!.invoke(result(7, "baseline"))
        controller.redetect { _, _, onFailure -> secondFailure = onFailure }

        firstFailure!!.invoke("old failure")
        assertTrue(controller.state.isDetecting)
        assertNull(controller.state.errorMessage)

        secondFailure!!.invoke("current failure")
        assertFalse(controller.state.isDetecting)
        assertEquals("current failure", controller.state.errorMessage)
    }

    @Test
    fun mismatchedClipResultCannotPublishIntoTheCurrentSession() {
        val controller = OcrSessionController(clip())
        var success: ((OcrPostRecognitionResult) -> Unit)? = null

        controller.startAutomaticDetection { _, onSuccess, _ -> success = onSuccess }
        success!!.invoke(result(999, "other clip"))

        assertTrue(controller.state.isDetecting)
        assertEquals("", controller.state.draftText)
        assertNull(controller.state.structuredResult)
    }

    @Test
    fun reopeningTheSameClipStartsWithPersistedTextOnly() {
        val firstSession = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        firstSession.startAutomaticDetection { _, onSuccess, _ -> firstSuccess = onSuccess }
        firstSuccess!!.invoke(result(7, "discarded"))
        assertTrue(firstSession.dismiss())

        val reopenedSession = OcrSessionController(clip(savedText = "persisted"))
        var detectCalls = 0
        reopenedSession.startAutomaticDetection { _, _, _ -> detectCalls++ }

        assertEquals(0, detectCalls)
        assertEquals("persisted", reopenedSession.state.draftText)
        assertNull(reopenedSession.state.structuredResult)
    }

    @Test
    fun automaticDetectionCancelCannotPersistItsDraft() {
        val controller = OcrSessionController(clip())
        var success: ((OcrPostRecognitionResult) -> Unit)? = null
        var saveCalls = 0
        controller.startAutomaticDetection { _, onSuccess, _ -> success = onSuccess }
        success!!.invoke(result(7, "detected"))

        assertTrue(controller.dismiss())
        controller.save({ _, _, _ -> saveCalls++ }) {}

        assertEquals(0, saveCalls)
    }

    @Test
    fun manualEditCancelCannotPersistItsDraft() {
        val controller = OcrSessionController(clip(savedText = "persisted"))
        var saveCalls = 0
        controller.editRegion(OcrRegionKey(1L, 0), "manual edit")

        assertTrue(controller.dismiss())
        controller.save({ _, _, _ -> saveCalls++ }) {}

        assertEquals(0, saveCalls)
    }

    @Test
    fun successfulRedetectCancelCannotPersistItsDraft() {
        val controller = OcrSessionController(clip())
        var firstSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> firstSuccess = onSuccess }
        firstSuccess!!.invoke(result(7, "first"))
        var redetectSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.redetect { _, onSuccess, _ -> redetectSuccess = onSuccess }
        redetectSuccess!!.invoke(result(7, "redetected"))
        var saveCalls = 0

        assertTrue(controller.dismiss())
        controller.save({ _, _, _ -> saveCalls++ }) {}

        assertEquals(0, saveCalls)
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
    fun saveFailureKeepsTheCurrentDraftAndStructuredResultForRetry() {
        val controller = OcrSessionController(clip())
        var detectSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> detectSuccess = onSuccess }
        val recognized = result(7, "recognized")
        detectSuccess!!.invoke(recognized)
        controller.editRegion(OcrRegionKey(1L, 0), "edited")
        var complete: ((String?) -> Unit)? = null

        controller.save({ _, _, onComplete -> complete = onComplete }) {}
        complete!!.invoke("save failed")

        assertEquals("edited", controller.state.draftText)
        assertEquals(
            "edited",
            controller.state.structuredResult!!.assets.single().recognition.regions.single().text,
        )
        assertEquals("save failed", controller.state.errorMessage)
        assertFalse(controller.state.isSaving)
    }

    @Test
    fun savePassesTheRebuiltStructuredDraftToTheExistingSavePath() {
        val controller = OcrSessionController(clip())
        var detectSuccess: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> detectSuccess = onSuccess }
        detectSuccess!!.invoke(
            OcrPostRecognitionResult(
                clipId = 7L,
                assets = listOf(
                    OcrAssetRecognitionResult(
                        1L,
                        "/tmp/one.webp",
                        OcrRecognitionResult(
                            100,
                            100,
                            "one\ntwo",
                            listOf(
                                OcrTextRegion("one"),
                                OcrTextRegion("two", precedingSeparator = "\n"),
                            ),
                        ),
                    ),
                ),
                fullText = "one\ntwo",
            ),
        )
        controller.editRegion(OcrRegionKey(1L, 0), "edited")

        var saved = 0
        controller.save({ _, text, complete ->
            assertEquals("edited\ntwo", text)
            complete(null)
        }) { saved++ }

        assertEquals(1, saved)
    }

    @Test
    fun staleSaveCompletionCannotCompleteAReplacementSave() {
        val controller = OcrSessionController(clip(savedText = "draft"))
        var firstComplete: ((String?) -> Unit)? = null
        var secondComplete: ((String?) -> Unit)? = null
        var saved = 0

        controller.save({ _, _, complete -> firstComplete = complete }) { saved++ }
        firstComplete!!.invoke("first failure")
        controller.save({ _, _, complete -> secondComplete = complete }) { saved++ }
        firstComplete!!.invoke(null)

        assertTrue(controller.state.isSaving)
        assertEquals(0, saved)

        secondComplete!!.invoke(null)
        assertFalse(controller.state.isSaving)
        assertEquals(1, saved)
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

    @Test
    fun regionEditRebuildsAssetAndPostTextWithoutDroppingPolygonlessRegions() {
        val original = OcrPostRecognitionResult(
            clipId = 7L,
            assets = listOf(
                OcrAssetRecognitionResult(
                    assetId = 1L,
                    localPath = "/tmp/first.webp",
                    recognition = OcrRecognitionResult(
                        imageWidth = 100,
                        imageHeight = 100,
                        fullText = "first\nsecond",
                        regions = listOf(
                            OcrTextRegion("first", box(10f, 10f, 40f, 20f)),
                            OcrTextRegion("second", polygon = null, precedingSeparator = "\n"),
                            OcrTextRegion("", box(10f, 30f, 40f, 40f), precedingSeparator = "\n\n"),
                        ),
                    ),
                ),
                OcrAssetRecognitionResult(
                    assetId = 2L,
                    localPath = "/tmp/second.webp",
                    recognition = OcrRecognitionResult(100, 100, "third", listOf(OcrTextRegion("third"))),
                ),
            ),
            fullText = "first\nsecond\n\nthird",
        )

        val edited = original.withRegionText(OcrRegionKey(1L, 0), "edited")!!

        assertEquals("edited\nsecond", edited.assets[0].recognition.fullText)
        assertEquals("edited\nsecond\n\nthird", edited.fullText)
        assertEquals(original.assets[0].recognition.regions[0].polygon, edited.assets[0].recognition.regions[0].polygon)
        assertEquals(null, edited.assets[0].recognition.regions[1].polygon)
        assertEquals("", edited.assets[0].recognition.regions[2].text)
        assertEquals("third", edited.assets[1].recognition.fullText)
    }

    @Test
    fun emptyRegionRemainsSelectableButDoesNotLeaveAssetSeparators() {
        val original = OcrPostRecognitionResult(
            clipId = 7L,
            assets = listOf(
                OcrAssetRecognitionResult(
                    assetId = 1L,
                    localPath = "/tmp/first.webp",
                    recognition = OcrRecognitionResult(
                        100,
                        100,
                        "first",
                        listOf(OcrTextRegion("first", precedingSeparator = "\n")),
                    ),
                ),
                OcrAssetRecognitionResult(
                    assetId = 2L,
                    localPath = "/tmp/second.webp",
                    recognition = OcrRecognitionResult(
                        100,
                        100,
                        "second",
                        listOf(OcrTextRegion("second")),
                    ),
                ),
            ),
            fullText = "first\n\nsecond",
        )

        val edited = original.withRegionText(OcrRegionKey(1L, 0), "")!!

        assertEquals("", edited.assets[0].recognition.fullText)
        assertEquals("second", edited.fullText)
        assertEquals("", edited.assets[0].recognition.regions[0].text)
    }

    @Test
    fun emptyMiddleRegionDoesNotLeaveItsOwnSeparatorAsAnEmptyLine() {
        val original = OcrPostRecognitionResult(
            clipId = 7L,
            assets = listOf(
                OcrAssetRecognitionResult(
                    1L,
                    "/tmp/one.webp",
                    OcrRecognitionResult(
                        100,
                        100,
                        "first\n\n\nthird",
                        listOf(
                            OcrTextRegion("first"),
                            OcrTextRegion("second", precedingSeparator = "\n\n"),
                            OcrTextRegion("third", precedingSeparator = "\n"),
                        ),
                    ),
                ),
            ),
            fullText = "first\n\n\nthird",
        )

        val edited = original.withRegionText(OcrRegionKey(1L, 1), "")!!

        assertEquals("first\nthird", edited.assets.single().recognition.fullText)
        assertEquals("first\nthird", edited.fullText)
        assertEquals("", edited.assets.single().recognition.regions[1].text)
    }

    @Test
    fun controllerRegionEditUsesAssetIdAndRegionIndexAndBlocksWholeTextEditing() {
        val controller = OcrSessionController(clip())
        var success: ((OcrPostRecognitionResult) -> Unit)? = null
        controller.startAutomaticDetection { _, onSuccess, _ -> success = onSuccess }
        success!!.invoke(
            OcrPostRecognitionResult(
                clipId = 7L,
                assets = listOf(
                    OcrAssetRecognitionResult(1L, "/tmp/one.webp", OcrRecognitionResult(10, 10, "one", listOf(OcrTextRegion("one")))),
                    OcrAssetRecognitionResult(2L, "/tmp/two.webp", OcrRecognitionResult(10, 10, "two", listOf(OcrTextRegion("two")))),
                ),
                fullText = "one\n\ntwo",
            ),
        )

        controller.editDraft("must be ignored")
        controller.editRegion(OcrRegionKey(2L, 0), "changed")

        assertEquals("one\n\nchanged", controller.state.draftText)
        assertEquals("one", controller.state.structuredResult!!.assets[0].recognition.regions[0].text)
        assertEquals("changed", controller.state.structuredResult!!.assets[1].recognition.regions[0].text)
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
                    recognition = OcrRecognitionResult(
                        100,
                        80,
                        text,
                        regions = listOf(com.lyco256.llm.data.OcrTextRegion(text = text)),
                    ),
                ),
            ),
            fullText = text,
        )

    private fun box(left: Float, top: Float, right: Float, bottom: Float) = OcrPolygon(
        listOf(
            OcrPoint(left, top),
            OcrPoint(right, top),
            OcrPoint(right, bottom),
            OcrPoint(left, bottom),
        ),
    )
}

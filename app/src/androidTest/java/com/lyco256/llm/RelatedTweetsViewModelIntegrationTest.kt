package com.lyco256.llm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.ImageEmbeddingSyncState
import com.lyco256.llm.data.ImageEmbeddingSyncStatus
import com.lyco256.llm.data.RelatedTweetResult
import com.lyco256.llm.data.RelatedTweetsEngine
import com.lyco256.llm.data.RelatedTweetsProgress
import com.lyco256.llm.data.SemanticIndexSyncState
import com.lyco256.llm.data.SemanticIndexSyncStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RelatedTweetsViewModelIntegrationTest {
    private lateinit var owner: TestViewModelOwner
    private lateinit var viewModel: MainViewModel
    private lateinit var relatedEngine: ControlledRelatedTweetsEngine
    private lateinit var semanticState: MutableStateFlow<SemanticIndexSyncState>
    private lateinit var imageState: MutableStateFlow<ImageEmbeddingSyncState>
    private lateinit var clipIds: List<Long>

    @Before
    fun setUp() {
        owner = TestViewModelOwner()
        relatedEngine = ControlledRelatedTweetsEngine()
        semanticState = MutableStateFlow(SemanticIndexSyncState(status = SemanticIndexSyncStatus.SYNCING))
        imageState = MutableStateFlow(ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.SYNCING))
        val application = ApplicationProvider.getApplicationContext<LikeListManagerApp>()
        runBlocking { application.container.repository.ensureSeedData() }
        clipIds = runBlocking {
            application.container.repository.clipsWithDetails
                .first { it.size >= 3 }
                .take(3)
                .map { it.clip.id }
        }
        viewModel = ViewModelProvider(
            owner,
            MainViewModel.factory(
                application = application,
                relatedTweetsEngineOverride = relatedEngine,
                semanticIndexSyncStateOverride = semanticState,
                imageEmbeddingSyncStateOverride = imageState,
            ),
        )[MainViewModel::class.java]
    }

    @After
    fun tearDown() {
        owner.store.clear()
    }

    @Test
    fun selectedClipLoadsBeforeRelatedSearchAndWaitsOnlyInRelatedSection() {
        viewModel.openMediaGridTweetDialog(clipIds[0])
        assertTrue(awaitDialogState { it is MediaGridTweetDialogState.Loaded } is MediaGridTweetDialogState.Loaded)
        assertEquals(RelatedTweetsStatus.WAITING_FOR_INDEX, awaitRelatedState { it.status == RelatedTweetsStatus.WAITING_FOR_INDEX }.status)
        assertEquals(0, relatedEngine.requestCount())

        semanticState.value = SemanticIndexSyncState(status = SemanticIndexSyncStatus.COMPLETE)
        imageState.value = ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.FAILED, lastError = "image sync failed")
        val request = relatedEngine.awaitRequest()
        assertEquals(RelatedTweetsStatus.LOADING, awaitRelatedState { it.status == RelatedTweetsStatus.LOADING }.status)
        request.progress(RelatedTweetsProgress(com.lyco256.llm.data.RelatedTweetsProgressPhase.RETRIEVING, 1, 2))
        assertEquals(1, awaitRelatedState { it.processed == 1 }.processed)
        request.complete(listOf(RelatedTweetResult(clipIds[1], .8f)))
        val ready = awaitRelatedState { it.status == RelatedTweetsStatus.READY }
        assertEquals(clipIds[0], ready.referenceClipId)
        assertEquals(listOf(clipIds[1]), ready.rankedClipIds)
    }

    @Test
    fun retryAndNewSelectionCancelOldGeneration() {
        semanticState.value = SemanticIndexSyncState(status = SemanticIndexSyncStatus.COMPLETE)
        imageState.value = ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.COMPLETE)
        viewModel.openMediaGridTweetDialog(clipIds[0])
        val first = relatedEngine.awaitRequest()
        first.fail(IllegalStateException("first failure"))
        assertEquals(RelatedTweetsStatus.FAILED, awaitRelatedState { it.status == RelatedTweetsStatus.FAILED }.status)

        viewModel.retryRelatedTweets()
        val retry = relatedEngine.awaitRequest(1)
        viewModel.openMediaGridTweetDialog(clipIds[1])
        val second = relatedEngine.awaitRequest(2)
        retry.complete(listOf(RelatedTweetResult(99L, .99f)))
        second.complete(listOf(RelatedTweetResult(clipIds[2], .7f)))

        val ready = awaitRelatedState { it.status == RelatedTweetsStatus.READY }
        assertEquals(clipIds[1], ready.referenceClipId)
        assertEquals(listOf(clipIds[2]), ready.rankedClipIds)
        assertTrue(awaitDialogState { it is MediaGridTweetDialogState.Loaded } is MediaGridTweetDialogState.Loaded)

        viewModel.closeMediaGridTweetDialog()
        assertEquals(RelatedTweetsStatus.INACTIVE, viewModel.relatedTweetsUiState.value.status)
    }

    private fun awaitDialogState(predicate: (MediaGridTweetDialogState) -> Boolean): MediaGridTweetDialogState = runBlocking {
        withTimeout(5_000L) { viewModel.mediaGridTweetDialogState.first(predicate) }
    }

    private fun awaitRelatedState(predicate: (RelatedTweetsUiState) -> Boolean): RelatedTweetsUiState = runBlocking {
        withTimeout(5_000L) { viewModel.relatedTweetsUiState.first(predicate) }
    }

    private class ControlledRelatedTweetsEngine : RelatedTweetsEngine {
        private val requests = mutableListOf<Request>()

        override suspend fun findRelated(
            clipId: Long,
            onProgress: (RelatedTweetsProgress) -> Unit,
        ): List<RelatedTweetResult> {
            val request = Request(clipId, onProgress)
            synchronized(requests) { requests += request }
            return request.result.await()
        }

        fun requestCount(): Int = synchronized(requests) { requests.size }

        fun awaitRequest(occurrence: Int = 0): Request = runBlocking {
            withTimeout(5_000L) {
                var request: Request? = null
                while (request == null) {
                    request = synchronized(requests) { requests.getOrNull(occurrence) }
                    if (request == null) {
                        delay(10L)
                    }
                }
                request
            }
        }

        class Request(
            val clipId: Long,
            private val onProgress: (RelatedTweetsProgress) -> Unit,
        ) {
            val result = CompletableDeferred<List<RelatedTweetResult>>()
            fun progress(value: RelatedTweetsProgress) = onProgress(value)
            fun complete(value: List<RelatedTweetResult>) { result.complete(value) }
            fun fail(error: Throwable) { result.completeExceptionally(error) }
        }
    }

    private class TestViewModelOwner : ViewModelStoreOwner {
        val store = ViewModelStore()
        override val viewModelStore: ViewModelStore = store
    }
}

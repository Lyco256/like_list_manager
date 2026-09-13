package com.lyco256.llm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.ImageDuplicateSearchEngine
import com.lyco256.llm.data.ImageDuplicateSearchProgress
import com.lyco256.llm.data.ImageDuplicateSearchResult
import com.lyco256.llm.data.ImageEmbeddingSyncState
import com.lyco256.llm.data.ImageEmbeddingSyncStatus
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
class ClassifiedImageDuplicateSearchViewModelIntegrationTest {
    private lateinit var engine: ControlledImageDuplicateSearchEngine
    private lateinit var owner: TestViewModelOwner
    private lateinit var syncState: MutableStateFlow<ImageEmbeddingSyncState>
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        engine = ControlledImageDuplicateSearchEngine()
        owner = TestViewModelOwner()
        syncState = MutableStateFlow(ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.COMPLETE, targetAssetCount = 4))
        val application = ApplicationProvider.getApplicationContext<LikeListManagerApp>()
        viewModel = ViewModelProvider(
            owner,
            MainViewModel.factory(
                application = application,
                imageDuplicateSearchEngineOverride = engine,
                imageEmbeddingSyncStateOverride = syncState,
            ),
        )[MainViewModel::class.java]
    }

    @After
    fun tearDown() {
        owner.store.clear()
    }

    @Test
    fun waitsForSyncThenReportsProgressReadyAndPreservesSourceRevision() {
        syncState.value = ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.SYNCING, targetAssetCount = 4)
        viewModel.startImageDuplicateSearch()
        awaitDuplicateState { it.isLoading }
        assertEquals(0, engine.requestCount())

        syncState.value = ImageEmbeddingSyncState(status = ImageEmbeddingSyncStatus.COMPLETE, targetAssetCount = 4, processedAssetCount = 4)
        val request = engine.awaitRequest()
        request.progress(ImageDuplicateSearchProgress(2, 4))
        awaitDuplicateState { it.isLoading && it.processedAssetCount == 2 }
        request.complete(ImageDuplicateSearchResult(listOf(20L, 10L), 1, 99L))

        val ready = awaitDuplicateState { it.isReady }
        assertEquals(listOf(20L, 10L), ready.orderedAssetIds)
        assertEquals(1, ready.groupCount)
        assertEquals(99L, ready.sourceRevision)
        assertEquals(2, ready.processedAssetCount)
        assertEquals(4, ready.totalAssetCount)
    }

    @Test
    fun failedAndNotStartedSyncStatesStillRunAndFailureCanRetryOrClear() {
        listOf(ImageEmbeddingSyncStatus.NOT_STARTED, ImageEmbeddingSyncStatus.FAILED).forEach { status ->
            syncState.value = ImageEmbeddingSyncState(status = status, targetAssetCount = 0)
            viewModel.startImageDuplicateSearch()
            val request = engine.awaitRequest(engine.requestCount())
            request.fail(IllegalStateException("fake failure"))
            val failed = awaitDuplicateState { it.isFailed }
            assertEquals("fake failure", failed.errorMessage)

            viewModel.retryImageDuplicateSearch()
            val retry = engine.awaitRequest(engine.requestCount())
            retry.complete(ImageDuplicateSearchResult(emptyList(), 0, 100L + engine.requestCount()))
            assertTrue(awaitDuplicateState { it.isReady }.orderedAssetIds.isEmpty())
            viewModel.clearImageDuplicateSearch()
            assertTrue(awaitDuplicateState { !it.isActive }.orderedAssetIds.isEmpty())
        }
    }

    @Test
    fun duplicateSearchClearsTextSearchAndNewGenerationWinsOverCancelledRequest() {
        viewModel.applySearch(
            ClassifiedSearchCriteria(
                query = "needle",
                mode = SearchMode.Regex,
                regexTargets = setOf(SearchTarget.Text),
            ),
        )
        awaitSearchState { it.isActive }

        viewModel.startImageDuplicateSearch()
        val first = engine.awaitRequest()
        val firstGeneration = awaitDuplicateState { it.isLoading }.requestGeneration
        assertTrue(awaitSearchState { !it.isActive }.criteria.normalizedQuery.isBlank())

        viewModel.startImageDuplicateSearch()
        val second = engine.awaitRequest(1)
        second.complete(ImageDuplicateSearchResult(listOf(30L), 1, 7L))
        val ready = awaitDuplicateState { it.isReady }
        assertEquals(listOf(30L), ready.orderedAssetIds)
        assertEquals(firstGeneration + 1L, ready.requestGeneration)

        first.complete(ImageDuplicateSearchResult(listOf(99L), 1, 8L))
        runBlocking { delay(50L) }
        assertEquals(listOf(30L), viewModel.uiState.value.imageDuplicateSearchState.orderedAssetIds)
    }

    private fun awaitSearchState(predicate: (ClassifiedSearchState) -> Boolean): ClassifiedSearchState = runBlocking {
        withTimeout(5_000L) { viewModel.uiState.first { predicate(it.searchState) }.searchState }
    }

    private fun awaitDuplicateState(
        predicate: (ClassifiedImageDuplicateSearchState) -> Boolean,
    ): ClassifiedImageDuplicateSearchState = runBlocking {
        withTimeout(5_000L) { viewModel.uiState.first { predicate(it.imageDuplicateSearchState) }.imageDuplicateSearchState }
    }

    private class ControlledImageDuplicateSearchEngine : ImageDuplicateSearchEngine {
        private val requests = mutableListOf<Request>()

        override suspend fun search(onProgress: (ImageDuplicateSearchProgress) -> Unit): ImageDuplicateSearchResult {
            val request = Request(onProgress)
            synchronized(requests) { requests += request }
            return request.result.await()
        }

        fun requestCount(): Int = synchronized(requests) { requests.size }

        fun awaitRequest(occurrence: Int = 0): Request = runBlocking {
            withTimeout(5_000L) { findRequest(occurrence) }
        }

        private suspend fun findRequest(occurrence: Int): Request {
            while (true) {
                val request = synchronized(requests) { requests.getOrNull(occurrence) }
                if (request != null) return request
                delay(10L)
            }
        }

        class Request(val progress: (ImageDuplicateSearchProgress) -> Unit) {
            val result = CompletableDeferred<ImageDuplicateSearchResult>()
            fun complete(value: ImageDuplicateSearchResult) { result.complete(value) }
            fun fail(error: Throwable) { result.completeExceptionally(error) }
        }
    }

    private class TestViewModelOwner : ViewModelStoreOwner {
        val store = ViewModelStore()
        override val viewModelStore: ViewModelStore = store
    }
}

package com.lyco256.llm

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.LocalSearchResult
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
class ClassifiedSearchViewModelIntegrationTest {
    private lateinit var engine: ControlledClassifiedSearchEngine
    private lateinit var owner: TestViewModelOwner
    private lateinit var viewModel: MainViewModel

    @Before
    fun setUp() {
        engine = ControlledClassifiedSearchEngine()
        owner = TestViewModelOwner()
        val application = ApplicationProvider.getApplicationContext<LikeListManagerApp>()
        viewModel = ViewModelProvider(owner, MainViewModel.factory(application, engine))[MainViewModel::class.java]
    }

    @After
    fun tearDown() {
        owner.store.clear()
    }

    @Test
    fun smartSearchTransitionsLoadingToReadyAndDropsStaleResult() {
        viewModel.applySearch(ClassifiedSearchCriteria(query = "first"))
        awaitSearchState { it.status == ClassifiedSearchExecutionStatus.LOADING && it.criteria.normalizedQuery == "first" }
        val first = engine.awaitRequest("first")

        viewModel.applySearch(ClassifiedSearchCriteria(query = "second"))
        awaitSearchState { it.status == ClassifiedSearchExecutionStatus.LOADING && it.criteria.normalizedQuery == "second" }
        val second = engine.awaitRequest("second")
        engine.complete(
            second,
            listOf(
                LocalSearchResult(30L, 0.95f),
                LocalSearchResult(10L, 0.80f),
                LocalSearchResult(30L, 0.70f),
            ),
        )
        val ready = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.READY }
        assertEquals("second", ready.criteria.normalizedQuery)
        assertEquals(listOf(30L, 10L), ready.rankedClipIds)

        engine.complete(first, listOf(LocalSearchResult(99L, 1.0f)))
        Thread.sleep(50L)
        assertEquals("second", viewModel.uiState.value.searchState.criteria.normalizedQuery)
        assertEquals(listOf(30L, 10L), viewModel.uiState.value.searchState.rankedClipIds)
    }

    @Test
    fun smartSearchFailureRetriesAndClearReturnsInactive() {
        viewModel.applySearch(ClassifiedSearchCriteria(query = "unstable"))
        val failedRequest = engine.awaitRequest("unstable")
        engine.fail(failedRequest, IllegalStateException("fake failure"))
        val failed = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.FAILED }
        assertEquals("fake failure", failed.errorMessage)

        viewModel.retrySearch()
        val retry = engine.awaitRequest("unstable", occurrence = 1)
        engine.complete(retry, listOf(LocalSearchResult(7L, 0.5f)))
        val ready = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.READY }
        assertEquals(listOf(7L), ready.rankedClipIds)

        viewModel.clearSearch()
        val inactive = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.INACTIVE }
        assertTrue(inactive.criteria.normalizedQuery.isBlank())
        assertTrue(inactive.rankedClipIds.isEmpty())
    }

    @Test
    fun regexSearchIsReadyWithoutCallingEngineAndBlankClears() {
        viewModel.applySearch(
            ClassifiedSearchCriteria(
                query = "needle",
                mode = SearchMode.Regex,
                regexTargets = setOf(SearchTarget.Text),
            ),
        )
        val ready = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.READY }
        assertEquals(SearchMode.Regex, ready.criteria.mode)
        assertEquals(0, engine.requestCount())

        viewModel.applySearch(ClassifiedSearchCriteria(query = "  "))
        val inactive = awaitSearchState { it.status == ClassifiedSearchExecutionStatus.INACTIVE }
        assertTrue(inactive.criteria.normalizedQuery.isBlank())
    }

    private fun awaitSearchState(predicate: (ClassifiedSearchState) -> Boolean): ClassifiedSearchState = runBlocking {
        withTimeout(5_000L) {
            viewModel.uiState.first { predicate(it.searchState) }.searchState
        }
    }

    private class TestViewModelOwner : ViewModelStoreOwner {
        val store = ViewModelStore()
        override val viewModelStore: ViewModelStore = store
    }
}

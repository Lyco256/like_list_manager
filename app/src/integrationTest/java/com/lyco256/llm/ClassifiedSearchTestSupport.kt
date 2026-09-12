package com.lyco256.llm

import androidx.lifecycle.ViewModelProvider
import com.lyco256.llm.data.ClassifiedSearchEngine
import com.lyco256.llm.data.LocalSearchResult
import kotlinx.coroutines.CompletableDeferred
import java.util.concurrent.CopyOnWriteArrayList

internal data class ControlledSearchRequest(
    val query: String,
    val result: CompletableDeferred<List<LocalSearchResult>>,
)

internal class ControlledClassifiedSearchEngine : ClassifiedSearchEngine {
    private val requests = CopyOnWriteArrayList<ControlledSearchRequest>()

    override suspend fun search(query: String): List<LocalSearchResult> {
        val request = ControlledSearchRequest(query, CompletableDeferred())
        requests += request
        return request.result.await()
    }

    fun reset() = requests.clear()

    fun requestCount(): Int = requests.size

    fun awaitRequest(query: String, occurrence: Int = 0, timeoutMs: Long = 5_000L): ControlledSearchRequest {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val matching = requests.filter { it.query == query }
            if (matching.size > occurrence) return matching[occurrence]
            Thread.sleep(10L)
        }
        error("Timed out waiting for fake search request query=$query occurrence=$occurrence")
    }

    fun complete(request: ControlledSearchRequest, results: List<LocalSearchResult>) {
        request.result.complete(results)
    }

    fun fail(request: ControlledSearchRequest, error: Throwable) {
        request.result.completeExceptionally(error)
    }
}

internal object ClassifiedSearchTestHarness {
    val engine = ControlledClassifiedSearchEngine()
}

class ClassifiedSearchTestActivity : MainActivity() {
    override val initialAppTab: AppTab = AppTab.Classified
    override val initialClassifiedDisplayMode: ClassifiedDisplayMode = ClassifiedDisplayMode.Card
    override val persistScreenState: Boolean = false

    override fun createMainViewModelFactory(): ViewModelProvider.Factory =
        MainViewModel.factory(application, ClassifiedSearchTestHarness.engine)
}

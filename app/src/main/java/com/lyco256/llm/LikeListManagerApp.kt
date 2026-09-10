package com.lyco256.llm

import android.app.Application
import android.content.ComponentCallbacks2
import com.lyco256.llm.data.AppContainer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class LikeListManagerApp : Application() {
    private val resourceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        if (!BuildConfig.TEST_HARNESS) {
            container.lexicalIndexSynchronizer.start(resourceScope)
        }
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        if (level >= ComponentCallbacks2.TRIM_MEMORY_COMPLETE) {
            resourceScope.launch { container.closePaddleOcr() }
        }
    }
}

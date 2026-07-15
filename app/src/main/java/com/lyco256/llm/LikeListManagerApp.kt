package com.lyco256.llm

import android.app.Application
import com.lyco256.llm.data.AppContainer
import com.lyco256.llm.data.MediaGridBenchmarkSettings
import com.lyco256.llm.BuildConfig

class LikeListManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        if (BuildConfig.BUILD_TYPE != "benchmark") container = AppContainer(this)
        var startedActivities = 0
        registerActivityLifecycleCallbacks(object : ActivityLifecycleCallbacks {
            override fun onActivityStarted(activity: android.app.Activity) {
                startedActivities++
                if (::container.isInitialized) container.mediaGridThumbnailManager.setForeground(true)
            }
            override fun onActivityStopped(activity: android.app.Activity) {
                startedActivities = (startedActivities - 1).coerceAtLeast(0)
                if (startedActivities == 0 && ::container.isInitialized) container.mediaGridThumbnailManager.setForeground(false)
            }
            override fun onActivityCreated(a: android.app.Activity, s: android.os.Bundle?) = Unit
            override fun onActivityResumed(a: android.app.Activity) = Unit
            override fun onActivityPaused(a: android.app.Activity) = Unit
            override fun onActivitySaveInstanceState(a: android.app.Activity, s: android.os.Bundle) = Unit
            override fun onActivityDestroyed(a: android.app.Activity) = Unit
        })
    }

    fun initializeForActivity(intent: android.content.Intent?) {
        if (!::container.isInitialized) {
            if (BuildConfig.BUILD_TYPE == "benchmark") {
                BenchmarkSnapshotImporter.prepareBenchmarkStorage(this)
                BenchmarkSnapshotImporter.requirePreparedSnapshot(this)
            }
            container = AppContainer(this, MediaGridBenchmarkSettings.resolve(intent))
        }
    }
}

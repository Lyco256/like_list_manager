package com.lyco256.llm

import android.app.Application
import com.lyco256.llm.data.AppContainer

class LikeListManagerApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

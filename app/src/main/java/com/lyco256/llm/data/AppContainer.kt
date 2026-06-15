package com.lyco256.llm.data

import android.content.Context

class AppContainer(context: Context) {
    val postStorageManager = PostStorageManager(context)
    val apiSettingsStore = ApiSettingsStore(context)
    val xOAuthManager = XOAuthManager(context)
    val repository = ClipRepository(
        context = context,
        postStorageManager = postStorageManager,
        apiSettingsStore = apiSettingsStore,
        xOAuthManager = xOAuthManager,
    )
}

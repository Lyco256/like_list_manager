package com.lyco256.llm.data

import android.content.Context
import com.lyco256.llm.BuildConfig

class AppContainer(context: Context) {
    val postStorageManager = PostStorageManager(
        context,
        PostStorageConfig(
            databaseName = BuildConfig.STORAGE_DATABASE_NAME,
            imagesDirectory = BuildConfig.STORAGE_IMAGES_DIRECTORY,
            dataDirectory = BuildConfig.STORAGE_DATA_DIRECTORY,
            preferencesName = BuildConfig.STORAGE_PREFERENCES_NAME,
        ),
    )
    val apiSettingsStore: SettingsStore = if (BuildConfig.TEST_HARNESS) {
        InMemorySettingsStore()
    } else {
        ApiSettingsStore(context, BuildConfig.API_PREFERENCES_NAME)
    }
    val xOAuthManager: OAuthGateway = if (BuildConfig.TEST_HARNESS) DisabledOAuthGateway() else XOAuthManager(context)
    val xApiClient: XApiGateway = if (BuildConfig.TEST_HARNESS) DisabledXApiGateway() else XApiClient(BuildConfig.X_API_BASE_URL)
    val repository = ClipRepository(
        context = context,
        postStorageManager = postStorageManager,
        apiSettingsStore = apiSettingsStore,
        xOAuthManager = xOAuthManager,
        xApiClient = xApiClient,
        includeSeedMedia = !BuildConfig.TEST_HARNESS,
    )
}

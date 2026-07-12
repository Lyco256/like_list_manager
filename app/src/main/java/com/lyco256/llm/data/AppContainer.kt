package com.lyco256.llm.data

import android.content.Context
import com.lyco256.llm.BuildConfig

class AppContainer(context: Context) {
    val mediaGridThumbnailStore = MediaGridThumbnailStore(context)
    val mediaGridThumbnailManager = MediaGridThumbnailManager(mediaGridThumbnailStore, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + kotlinx.coroutines.Dispatchers.Main.immediate))
    val mediaGridImageLoader = coil.ImageLoader.Builder(context)
        .crossfade(false)
        .diskCachePolicy(coil.request.CachePolicy.DISABLED)
        .memoryCache { coil.memory.MemoryCache.Builder(context).maxSizePercent(0.08).maxSizeBytes(32 * 1024 * 1024).build() }
        .build()
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
    val ocrTextGateway: OcrTextGateway = if (BuildConfig.TEST_HARNESS) {
        FakeOcrTextGateway { bitmap ->
            when {
                bitmap.width == 1 && bitmap.height == 1 -> ""
                bitmap.width == 2 && bitmap.height == 2 -> throw IllegalStateException("Fake OCR failure")
                bitmap.width > bitmap.height -> "Landscape OCR\nSecond line"
                else -> "Portrait OCR"
            }
        }
    } else {
        MlKitOcrTextGateway()
    }
    val repository = ClipRepository(
        context = context,
        postStorageManager = postStorageManager,
        apiSettingsStore = apiSettingsStore,
        xOAuthManager = xOAuthManager,
        xApiClient = xApiClient,
        ocrTextGateway = ocrTextGateway,
        includeSeedMedia = !BuildConfig.TEST_HARNESS,
    )
}

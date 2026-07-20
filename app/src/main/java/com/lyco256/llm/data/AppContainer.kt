package com.lyco256.llm.data

import android.content.Context
import com.lyco256.llm.BuildConfig

class AppContainer(context: Context) {
    val mediaGridImageLoader = coil.ImageLoader.Builder(context)
        .crossfade(false)
        .diskCache {
            coil.disk.DiskCache.Builder()
                .directory(java.io.File(context.cacheDir, "media_grid_coil_cache"))
                .maxSizeBytes(128L * 1024L * 1024L)
                .build()
        }
        .memoryCache {
            val cache = coil.memory.MemoryCache.Builder(context)
            val activityManager = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
            val memoryInfo = android.app.ActivityManager.MemoryInfo()
            activityManager.getMemoryInfo(memoryInfo)
            cache.maxSizeBytes(minOf(memoryInfo.totalMem / 8L, 64L * 1024L * 1024L).toInt())
            cache.build()
        }
        .decoderDispatcher(kotlinx.coroutines.Dispatchers.IO.limitedParallelism(2))
        .fetcherDispatcher(kotlinx.coroutines.Dispatchers.IO)
        .build()
    internal val mediaGridImagePreparer = MediaGridImagePreparer(
        MediaGridPersistentPreviewStore(context.filesDir),
    )
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
        mediaGridPreviewEnqueuer = WorkManagerMediaGridPreviewEnqueuer(context),
    )
}

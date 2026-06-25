package com.lyco256.llm

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.data.DisabledOAuthGateway
import com.lyco256.llm.data.DisabledXApiGateway
import com.lyco256.llm.data.InMemorySettingsStore
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class TestEnvironmentIsolationTest {
    @Test
    fun integrationVariantIsFailClosedAndSeparatedFromProduction() {
        val application = ApplicationProvider.getApplicationContext<LikeListManagerApp>()

        assertTrue(BuildConfig.TEST_HARNESS)
        assertEquals("com.lyco256.llm.test", BuildConfig.APPLICATION_ID)
        assertFalse(BuildConfig.STORAGE_DATABASE_NAME == "like_list_manager.db")
        assertFalse(BuildConfig.STORAGE_IMAGES_DIRECTORY == "images")
        assertFalse(BuildConfig.STORAGE_PREFERENCES_NAME == "post_storage_settings")
        assertFalse(BuildConfig.API_PREFERENCES_NAME == "api_settings")
        assertFalse(BuildConfig.X_API_BASE_URL.startsWith("https://api.x.com"))
        assertTrue(application.container.apiSettingsStore is InMemorySettingsStore)
        assertTrue(application.container.xOAuthManager is DisabledOAuthGateway)
        assertTrue(application.container.xApiClient is DisabledXApiGateway)

        @Suppress("DEPRECATION")
        val productionInfo = application.packageManager.getApplicationInfo("com.lyco256.llm", 0)
        assertNotEquals(application.applicationInfo.uid, productionInfo.uid)

        val denied = runCatching { application.container.xApiClient.getMyUser("must-not-be-used") }.exceptionOrNull()
        assertTrue(denied is IllegalStateException)
    }
}

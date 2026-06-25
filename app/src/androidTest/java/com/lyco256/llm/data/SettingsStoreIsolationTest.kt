package com.lyco256.llm.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.lyco256.llm.BuildConfig
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SettingsStoreIsolationTest {
    private val preferencesName = "api_settings_integration_test"
    private lateinit var context: Context
    private lateinit var store: ApiSettingsStore

    @Before
    fun setUp() {
        check(BuildConfig.TEST_HARNESS && BuildConfig.APPLICATION_ID == "com.lyco256.llm.test")
        check(preferencesName != BuildConfig.API_PREFERENCES_NAME)
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
        store = ApiSettingsStore(context, preferencesName)
        store.clear()
    }

    @After
    fun tearDown() {
        store.clear()
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE).edit().clear().commit()
    }

    @Test
    fun encryptedTestSettingsRoundTripAndClearWithoutProductionPreferences() {
        val session = OAuthSession(
            accessToken = "test-access-token",
            refreshToken = "test-refresh-token",
            expiresAtEpochMillis = System.currentTimeMillis() + 3_600_000,
            scopes = "tweet.read users.read like.read offline.access",
            xUserId = "test-user",
            username = "tester",
            displayName = "Test User",
        )

        store.save(ApiSettings("  test-client-id  "))
        store.saveSession(session)

        assertEquals(ApiSettings("test-client-id"), store.load())
        assertEquals(session, store.loadSession())
        assertTrue(store.loadSession()?.accessToken?.startsWith("test-") == true)

        store.clearSession()
        assertNull(store.loadSession())
        assertEquals(ApiSettings("test-client-id"), store.load())

        store.clear()
        assertEquals(ApiSettings(), store.load())
        assertNull(store.loadSession())
    }
}

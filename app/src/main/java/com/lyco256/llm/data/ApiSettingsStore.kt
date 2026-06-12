package com.lyco256.llm.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ApiSettings(
    val clientId: String = "",
)

data class OAuthSession(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long?,
    val scopes: String,
    val xUserId: String,
    val username: String,
    val displayName: String,
) {
    val isExpired: Boolean
        get() = expiresAtEpochMillis?.let { it <= System.currentTimeMillis() + 60_000 } ?: false
}

class ApiSettingsStore(context: Context) {
    private val appContext = context.applicationContext
    private val preferences: SharedPreferences by lazy {
        EncryptedSharedPreferences.create(
            appContext,
            "api_settings",
            MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build(),
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }

    fun load(): ApiSettings = ApiSettings(
        clientId = preferences.getString("clientId", "").orEmpty(),
    )

    fun save(settings: ApiSettings) {
        preferences.edit()
            .putString("clientId", settings.clientId.trim())
            .remove("authMode")
            .remove("xUserId")
            .remove("apiKey")
            .remove("apiKeySecret")
            .remove("accessToken")
            .remove("accessTokenSecret")
            .commit()
    }

    fun loadSession(): OAuthSession? {
        val accessToken = preferences.getString("oauth2AccessToken", "").orEmpty()
        if (accessToken.isBlank()) return null
        return OAuthSession(
            accessToken = accessToken,
            refreshToken = preferences.getString("oauth2RefreshToken", null),
            expiresAtEpochMillis = preferences.getLong("oauth2ExpiresAt", -1L).takeIf { it > 0 },
            scopes = preferences.getString("oauth2Scopes", "").orEmpty(),
            xUserId = preferences.getString("oauth2UserId", "").orEmpty(),
            username = preferences.getString("oauth2Username", "").orEmpty(),
            displayName = preferences.getString("oauth2DisplayName", "").orEmpty(),
        )
    }

    fun saveSession(session: OAuthSession) {
        preferences.edit()
            .putString("oauth2AccessToken", session.accessToken)
            .putString("oauth2RefreshToken", session.refreshToken)
            .putLong("oauth2ExpiresAt", session.expiresAtEpochMillis ?: -1L)
            .putString("oauth2Scopes", session.scopes)
            .putString("oauth2UserId", session.xUserId)
            .putString("oauth2Username", session.username)
            .putString("oauth2DisplayName", session.displayName)
            .commit()
    }

    fun clearSession() {
        preferences.edit()
            .remove("oauth2AccessToken")
            .remove("oauth2RefreshToken")
            .remove("oauth2ExpiresAt")
            .remove("oauth2Scopes")
            .remove("oauth2UserId")
            .remove("oauth2Username")
            .remove("oauth2DisplayName")
            .commit()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }
}

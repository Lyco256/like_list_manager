package com.lyco256.llm.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

data class ApiSettings(
    val authMode: String = "oauth2",
    val xUserId: String = "",
    val clientId: String = "",
    val apiKey: String = "",
    val apiKeySecret: String = "",
    val accessToken: String = "",
    val accessTokenSecret: String = "",
) {
    val hasAnyCredential: Boolean
        get() = clientId.isNotBlank() ||
            apiKey.isNotBlank() ||
            accessToken.isNotBlank()

    val hasCompleteOAuth1Credentials: Boolean
        get() = xUserId.isNotBlank() &&
            apiKey.isNotBlank() &&
            apiKeySecret.isNotBlank() &&
            accessToken.isNotBlank() &&
            accessTokenSecret.isNotBlank()
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
        authMode = preferences.getString("authMode", "oauth2").orEmpty(),
        xUserId = preferences.getString("xUserId", "").orEmpty(),
        clientId = preferences.getString("clientId", "").orEmpty(),
        apiKey = preferences.getString("apiKey", "").orEmpty(),
        apiKeySecret = preferences.getString("apiKeySecret", "").orEmpty(),
        accessToken = preferences.getString("accessToken", "").orEmpty(),
        accessTokenSecret = preferences.getString("accessTokenSecret", "").orEmpty(),
    )

    fun save(settings: ApiSettings) {
        preferences.edit()
            .putString("authMode", settings.authMode)
            .putString("xUserId", settings.xUserId)
            .putString("clientId", settings.clientId)
            .putString("apiKey", settings.apiKey)
            .putString("apiKeySecret", settings.apiKeySecret)
            .putString("accessToken", settings.accessToken)
            .putString("accessTokenSecret", settings.accessTokenSecret)
            .commit()
    }

    fun clear() {
        preferences.edit().clear().commit()
    }
}

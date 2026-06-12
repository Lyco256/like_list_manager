package com.lyco256.llm.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.suspendCancellableCoroutine
import net.openid.appauth.AuthorizationException
import net.openid.appauth.AuthorizationRequest
import net.openid.appauth.AuthorizationResponse
import net.openid.appauth.AuthorizationService
import net.openid.appauth.AuthorizationServiceConfiguration
import net.openid.appauth.NoClientAuthentication
import net.openid.appauth.ResponseTypeValues
import net.openid.appauth.TokenRequest
import net.openid.appauth.TokenResponse
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

data class OAuthTokens(
    val accessToken: String,
    val refreshToken: String?,
    val expiresAtEpochMillis: Long?,
    val scopes: String,
)

class XOAuthManager(context: Context) {
    private val service = AuthorizationService(context.applicationContext)

    fun createAuthorizationIntent(clientId: String): Intent {
        require(clientId.isNotBlank()) { "OAuth 2.0 Client IDを先に保存してください" }
        val request = AuthorizationRequest.Builder(
            configuration,
            clientId,
            ResponseTypeValues.CODE,
            REDIRECT_URI,
        )
            .setScope(SCOPES)
            .build()
        return service.getAuthorizationRequestIntent(request)
    }

    suspend fun exchangeAuthorizationResult(intent: Intent): OAuthTokens {
        val error = AuthorizationException.fromIntent(intent)
        if (error != null) throw IllegalStateException(error.errorDescription ?: "Xの認証がキャンセルされました", error)
        val response = AuthorizationResponse.fromIntent(intent)
            ?: throw IllegalStateException("Xから認証結果を受け取れませんでした")
        return performTokenRequest(response.createTokenExchangeRequest())
    }

    suspend fun refresh(clientId: String, refreshToken: String): OAuthTokens {
        val request = TokenRequest.Builder(configuration, clientId)
            .setGrantType("refresh_token")
            .setRefreshToken(refreshToken)
            .setScope(SCOPES)
            .build()
        return performTokenRequest(request)
    }

    private suspend fun performTokenRequest(request: TokenRequest): OAuthTokens =
        suspendCancellableCoroutine { continuation ->
            service.performTokenRequest(request, NoClientAuthentication.INSTANCE) { response, error ->
                when {
                    error != null -> continuation.resumeWithException(
                        IllegalStateException(error.errorDescription ?: "トークンの取得に失敗しました", error),
                    )
                    response != null -> continuation.resume(response.toTokens())
                    else -> continuation.resumeWithException(IllegalStateException("トークン応答が空でした"))
                }
            }
        }

    private fun TokenResponse.toTokens(): OAuthTokens = OAuthTokens(
        accessToken = accessToken ?: throw IllegalStateException("アクセストークンがありません"),
        refreshToken = refreshToken,
        expiresAtEpochMillis = accessTokenExpirationTime,
        scopes = scope ?: SCOPES,
    )

    companion object {
        val REDIRECT_URI: Uri = Uri.parse("likelistmanager://oauth/x/callback")
        const val SCOPES = "tweet.read users.read like.read offline.access"
        private val configuration = AuthorizationServiceConfiguration(
            Uri.parse("https://x.com/i/oauth2/authorize"),
            Uri.parse("https://api.x.com/2/oauth2/token"),
        )
    }
}

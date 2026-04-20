package com.mingeek.studiopop.data.auth

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

sealed interface AuthOutcome {
    data class Success(val accessToken: String) : AuthOutcome
    data class NeedsUserConsent(val pendingIntent: PendingIntent) : AuthOutcome
    data class Failure(val error: Throwable) : AuthOutcome
}

class GoogleAuthManager(private val context: Context) {

    private val client = Identity.getAuthorizationClient(context)

    suspend fun requestYouTubeUploadAuthorization(): AuthOutcome =
        suspendCancellableCoroutine { cont ->
            val request = AuthorizationRequest.builder()
                .setRequestedScopes(
                    listOf(
                        Scope(SCOPE_YOUTUBE_UPLOAD),
                        Scope(SCOPE_YOUTUBE_READONLY),
                    )
                )
                .build()

            client.authorize(request)
                .addOnSuccessListener { result: AuthorizationResult ->
                    val token = result.accessToken
                    val pendingIntent = result.pendingIntent
                    cont.resume(
                        when {
                            token != null -> AuthOutcome.Success(token)
                            pendingIntent != null -> AuthOutcome.NeedsUserConsent(pendingIntent)
                            else -> AuthOutcome.Failure(IllegalStateException("No token and no pendingIntent"))
                        }
                    )
                }
                .addOnFailureListener { e ->
                    cont.resume(AuthOutcome.Failure(e))
                }
        }

    /**
     * 동의 화면에서 돌아온 Intent 로부터 액세스 토큰을 추출한다.
     */
    fun extractAccessTokenFromConsent(data: Intent?): String? {
        if (data == null) return null
        return try {
            client.getAuthorizationResultFromIntent(data).accessToken
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        const val SCOPE_YOUTUBE_UPLOAD = "https://www.googleapis.com/auth/youtube.upload"
        const val SCOPE_YOUTUBE_READONLY = "https://www.googleapis.com/auth/youtube.readonly"
    }
}

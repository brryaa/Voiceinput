package com.lboard.aiime.auth

import android.content.Context
import androidx.credentials.ClearCredentialStateRequest
import androidx.credentials.CredentialManager
import androidx.credentials.CustomCredential
import androidx.credentials.GetCredentialRequest
import androidx.credentials.GetCredentialResponse
import com.google.android.libraries.identity.googleid.GetGoogleIdOption
import com.google.android.libraries.identity.googleid.GoogleIdTokenCredential
import com.google.android.libraries.identity.googleid.GoogleIdTokenParsingException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Gemini OAuth 认证管理器
 *
 * 使用 Android Credential Manager + Google ID 实现 Sign-in with Google。
 * 获取的 ID Token 用于后续 Gemini API 调用授权。
 */
class GeminiAuthManager(private val context: Context) {

    companion object {
        const val WEB_CLIENT_ID = "677432025123-vauk4hhcl0373o3t94gluo123i0m8341.apps.googleusercontent.com"

        private const val PREFS_NAME = "lboard_auth"
        private const val KEY_ID_TOKEN = "id_token"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_DISPLAY_NAME = "display_name"
        private const val KEY_EMAIL = "email"
        private const val KEY_PHOTO_URL = "photo_url"
    }

    private val credentialManager = CredentialManager.create(context)
    private val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _authState = MutableStateFlow<AuthState>(AuthState.LoggedOut)
    val authState: StateFlow<AuthState> = _authState.asStateFlow()

    init {
        // 恢复保存的登录状态
        restoreSavedState()
    }

    /**
     * 发起 Google 登录
     * @param activityContext 必须是 Activity Context
     */
    suspend fun signIn(activityContext: Context) {
        _authState.value = AuthState.Loading

        try {
            val googleIdOption = GetGoogleIdOption.Builder()
                .setFilterByAuthorizedAccounts(false)
                .setServerClientId(WEB_CLIENT_ID)
                .setAutoSelectEnabled(false)
                .build()

            val request = GetCredentialRequest.Builder()
                .addCredentialOption(googleIdOption)
                .build()

            val result: GetCredentialResponse = credentialManager.getCredential(
                request = request,
                context = activityContext
            )

            handleSignInResult(result)

        } catch (e: androidx.credentials.exceptions.GetCredentialCancellationException) {
            _authState.value = AuthState.Error(message = "登录已取消")
        } catch (e: androidx.credentials.exceptions.NoCredentialException) {
            _authState.value = AuthState.Error(
                message = "未找到可用的 Google 账号，请确认设备已登录 Google 账户"
            )
        } catch (e: androidx.credentials.exceptions.GetCredentialException) {
            val errorMsg = when {
                e.message?.contains("28444") == true ||
                e.message?.contains("developer console") == true ->
                    "Google Cloud 控制台未正确配置。\n请在项目中配置 OAuth 同意屏幕，并确保 Android 客户端 ID 的包名和 SHA-1 指纹正确。"
                e.message?.contains("16") == true ->
                    "Google Play Services 版本过低，请更新"
                else -> "登录失败: ${e.message ?: "未知错误"}"
            }
            _authState.value = AuthState.Error(message = errorMsg)
        } catch (e: Exception) {
            _authState.value = AuthState.Error(
                message = "登录失败: ${e.localizedMessage ?: "请重试"}"
            )
        }
    }

    /**
     * 登出
     */
    suspend fun signOut() {
        try {
            credentialManager.clearCredentialState(ClearCredentialStateRequest())
        } catch (_: Exception) {
            // 忽略清除失败
        }
        clearSavedState()
        _authState.value = AuthState.LoggedOut
    }

    /**
     * 获取当前 ID Token（用于 API 调用）
     */
    fun getIdToken(): String? {
        val state = _authState.value
        return if (state is AuthState.LoggedIn) state.idToken else null
    }

    /**
     * 是否已登录
     */
    fun isLoggedIn(): Boolean = _authState.value is AuthState.LoggedIn

    /**
     * 处理登录结果
     */
    private fun handleSignInResult(result: GetCredentialResponse) {
        val credential = result.credential

        when (credential) {
            is CustomCredential -> {
                if (credential.type == GoogleIdTokenCredential.TYPE_GOOGLE_ID_TOKEN_CREDENTIAL) {
                    try {
                        val googleIdTokenCredential = GoogleIdTokenCredential
                            .createFrom(credential.data)

                        val loggedIn = AuthState.LoggedIn(
                            userId = googleIdTokenCredential.id,
                            displayName = googleIdTokenCredential.displayName,
                            email = googleIdTokenCredential.id, // email is the id
                            photoUrl = googleIdTokenCredential.profilePictureUri?.toString(),
                            idToken = googleIdTokenCredential.idToken
                        )

                        saveState(loggedIn)
                        _authState.value = loggedIn

                    } catch (e: GoogleIdTokenParsingException) {
                        _authState.value = AuthState.Error("Token 解析失败: ${e.message}")
                    }
                } else {
                    _authState.value = AuthState.Error("不支持的凭证类型")
                }
            }
            else -> {
                _authState.value = AuthState.Error("不支持的凭证类型")
            }
        }
    }

    /**
     * 保存登录状态
     */
    private fun saveState(state: AuthState.LoggedIn) {
        prefs.edit()
            .putString(KEY_ID_TOKEN, state.idToken)
            .putString(KEY_USER_ID, state.userId)
            .putString(KEY_DISPLAY_NAME, state.displayName)
            .putString(KEY_EMAIL, state.email)
            .putString(KEY_PHOTO_URL, state.photoUrl)
            .apply()
    }

    /**
     * 恢复保存的登录状态
     */
    private fun restoreSavedState() {
        val idToken = prefs.getString(KEY_ID_TOKEN, null)
        val userId = prefs.getString(KEY_USER_ID, null)

        if (idToken != null && userId != null) {
            _authState.value = AuthState.LoggedIn(
                userId = userId,
                displayName = prefs.getString(KEY_DISPLAY_NAME, null),
                email = prefs.getString(KEY_EMAIL, null),
                photoUrl = prefs.getString(KEY_PHOTO_URL, null),
                idToken = idToken
            )
        }
    }

    /**
     * 清除保存的状态
     */
    private fun clearSavedState() {
        prefs.edit().clear().apply()
    }
}

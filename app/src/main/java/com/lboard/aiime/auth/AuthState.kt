package com.lboard.aiime.auth

/**
 * 认证状态
 */
sealed class AuthState {
    /** 未登录 */
    data object LoggedOut : AuthState()

    /** 登录中 */
    data object Loading : AuthState()

    /** 已登录 */
    data class LoggedIn(
        val userId: String,
        val displayName: String?,
        val email: String?,
        val photoUrl: String?,
        val idToken: String
    ) : AuthState()

    /** 登录失败 */
    data class Error(val message: String) : AuthState()
}

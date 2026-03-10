package com.lvoice.aiime.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

// 扩展属性：DataStore 实例（全局唯一，绑定到 Application Context）
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "lboard_settings")

/**
 * 用户偏好设置管理
 *
 * 使用 DataStore 持久化存储用户配置。
 * 使用单例模式确保不会创建多个 DataStore 实例（否则会崩溃）。
 */
class UserPreferences private constructor(context: Context) {

    // 始终使用 applicationContext 确保 DataStore 是单例
    private val appContext = context.applicationContext

    enum class AuthMethod {
        API_KEY, OAUTH
    }

    companion object {
        private val KEY_GEMINI_API_KEY = stringPreferencesKey("gemini_api_key")
        private val KEY_AUTH_METHOD = stringPreferencesKey("auth_method")
        private val KEY_AI_MODEL = stringPreferencesKey("ai_model")

        const val DEFAULT_MODEL = "gemini-2.0-flash"

        @Volatile
        private var INSTANCE: UserPreferences? = null

        fun getInstance(context: Context): UserPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: UserPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    // ===== Gemini API Key =====

    val geminiApiKeyFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[KEY_GEMINI_API_KEY] ?: ""
    }

    suspend fun getGeminiApiKey(): String {
        return try {
            appContext.dataStore.data.first()[KEY_GEMINI_API_KEY] ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    suspend fun setGeminiApiKey(apiKey: String) {
        try {
            appContext.dataStore.edit { prefs ->
                prefs[KEY_GEMINI_API_KEY] = apiKey.trim()
            }
        } catch (_: Exception) {
            // 防止崩溃
        }
    }

    // ===== Auth Method =====

    val authMethodFlow: Flow<AuthMethod> = appContext.dataStore.data.map { prefs ->
        val savedMethod = prefs[KEY_AUTH_METHOD] ?: AuthMethod.API_KEY.name
        try { AuthMethod.valueOf(savedMethod) } catch (e: Exception) { AuthMethod.API_KEY }
    }

    suspend fun getAuthMethod(): AuthMethod {
        return try {
            val savedMethod = appContext.dataStore.data.first()[KEY_AUTH_METHOD] ?: AuthMethod.API_KEY.name
            AuthMethod.valueOf(savedMethod)
        } catch (e: Exception) {
            AuthMethod.API_KEY
        }
    }

    suspend fun setAuthMethod(method: AuthMethod) {
        try {
            appContext.dataStore.edit { prefs ->
                prefs[KEY_AUTH_METHOD] = method.name
            }
        } catch (_: Exception) {
            // 防止崩溃
        }
    }

    // ===== AI Model =====

    val aiModelFlow: Flow<String> = appContext.dataStore.data.map { prefs ->
        prefs[KEY_AI_MODEL] ?: DEFAULT_MODEL
    }

    suspend fun setAiModel(model: String) {
        try {
            appContext.dataStore.edit { prefs ->
                prefs[KEY_AI_MODEL] = model
            }
        } catch (_: Exception) {
            // 防止崩溃
        }
    }
}

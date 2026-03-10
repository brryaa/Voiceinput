package com.lvoice.aiime.ai

import android.util.Log
import com.lvoice.aiime.auth.GeminiAuthManager
import com.lvoice.aiime.data.UserPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/**
 * 诊断工具：嗅探当前 API Key/OAuth 下可用的所有 Gemini 模型及其能力。
 */
class ModelSniffer(private val userPreferences: UserPreferences, private val authManager: GeminiAuthManager) {

    suspend fun sniffModels() = withContext(Dispatchers.IO) {
        val apiKey = userPreferences.getGeminiApiKey()
        val oauthToken = authManager.getIdToken()
        val authMethod = userPreferences.getAuthMethod()

        Log.d("ModelSniffer", "Starting sniff. Method: $authMethod")

        if (authMethod == UserPreferences.AuthMethod.API_KEY && apiKey.isNotBlank()) {
            fetchViaApiKey(apiKey)
        } else if (authMethod == UserPreferences.AuthMethod.OAUTH && oauthToken != null) {
            fetchViaOAuth(oauthToken)
        } else {
            Log.e("ModelSniffer", "No valid credentials found to sniff")
        }
    }

    private fun fetchViaApiKey(key: String) {
        val urlStr = "https://generativelanguage.googleapis.com/v1beta/models?key=$key"
        executeFetch(urlStr, null)
    }

    private fun fetchViaOAuth(token: String) {
        val urlStr = "https://generativelanguage.googleapis.com/v1beta/models"
        executeFetch(urlStr, token)
    }

    private fun executeFetch(urlStr: String, token: String?) {
        try {
            val url = URL(urlStr)
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "GET"
            if (token != null) {
                conn.setRequestProperty("Authorization", "Bearer $token")
            }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val response = conn.inputStream.bufferedReader().use { it.readText() }
                parseAndLogModels(response)
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("ModelSniffer", "Error $responseCode: $error")
            }
        } catch (e: Exception) {
            Log.e("ModelSniffer", "Fetch failed", e)
        }
    }

    private fun parseAndLogModels(jsonStr: String) {
        try {
            val root = JSONObject(jsonStr)
            val models = root.optJSONArray("models") ?: return
            
            Log.i("ModelSniffer", "--- Available Models Report ---")
            for (i in 0 until models.length()) {
                val m = models.getJSONObject(i)
                val name = m.getString("name")
                val methods = m.optJSONArray("supportedGenerationMethods")?.let { arr ->
                    (0 until arr.length()).map { arr.getString(it) }
                } ?: emptyList()

                // Check if it's a model we care about (Flash/Pro)
                if (name.contains("flash") || name.contains("pro")) {
                    Log.i("ModelSniffer", "Model: $name")
                    Log.i("ModelSniffer", "  Supports: $methods")
                }
            }
            Log.i("ModelSniffer", "--- End of Report ---")
        } catch (e: Exception) {
            Log.e("ModelSniffer", "Parsing failed", e)
        }
    }
}

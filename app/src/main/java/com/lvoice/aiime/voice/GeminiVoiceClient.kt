package com.lvoice.aiime.voice

import android.util.Base64
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * Gemini 语音客户端 (高精度改进版)
 * 使用从诊断中确定的正确模型名称：models/gemini-1.5-flash-latest
 */
class GeminiVoiceClient(private val apiKey: String) {

    // 基于诊断报告，使用确切支持的 Flash 模型别名
    private val MODEL_NAME = "models/gemini-flash-latest"

    /**
     * 将一段音频发送给 Gemini 进行高精度转录
     * @param audioFile 录制的 WAV 文件
     * @param draftText 原生引擎识别出的“草稿”文字（供 AI 参考纠偏）
     */
    suspend fun refineSTT(audioFile: File, draftText: String): String? = withContext(Dispatchers.IO) {
        if (apiKey.isBlank()) {
            Log.e("GeminiVoiceClient", "API Key is empty")
            return@withContext null
        }
        if (!audioFile.exists() || audioFile.length() < 100) {
            Log.e("GeminiVoiceClient", "Audio file invalid: exists=${audioFile.exists()}, size=${audioFile.length()}")
            return@withContext null
        }

        try {
            Log.d("GeminiVoiceClient", "Sending refinement request (file size: ${audioFile.length()} bytes)...")
            val audioBase64 = Base64.encodeToString(audioFile.readBytes(), Base64.NO_WRAP)
            
            val url = URL("https://generativelanguage.googleapis.com/v1beta/$MODEL_NAME:generateContent?key=$apiKey")
            val conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.setRequestProperty("Content-Type", "application/json")
            conn.doOutput = true

            val jsonBody = JSONObject().apply {
                val contents = JSONArray().apply {
                    val content = JSONObject().apply {
                        val parts = JSONArray().apply {
                            put(JSONObject().apply {
                                put("text", "你是一个语音识别引擎。请将音频精准转录。参考草稿文字：'$draftText'。只返回文字结果。")
                            })
                            put(JSONObject().apply {
                                val inlineData = JSONObject().apply {
                                    put("mimeType", "audio/wav")
                                    put("data", audioBase64)
                                }
                                put("inlineData", inlineData)
                            })
                        }
                        put("parts", parts)
                    }
                    put(content)
                }
                put("contents", contents)
            }

            conn.outputStream.use { it.write(jsonBody.toString().toByteArray()) }

            val responseCode = conn.responseCode
            if (responseCode == 200) {
                val responseStr = conn.inputStream.bufferedReader().use { it.readText() }
                val root = JSONObject(responseStr)
                val text = root.optJSONArray("candidates")?.getJSONObject(0)
                    ?.getJSONObject("content")?.getJSONArray("parts")?.getJSONObject(0)
                    ?.optString("text")

                Log.d("GeminiVoiceClient", "Refined result: '$text'")
                return@withContext text?.trim()
            } else {
                val error = conn.errorStream?.bufferedReader()?.use { it.readText() }
                Log.e("GeminiVoiceClient", "HTTP $responseCode: $error")
                return@withContext null
            }
        } catch (e: Exception) {
            Log.e("GeminiVoiceClient", "Exception during refinement: ${e.message}", e)
            return@withContext null
        }
    }
}

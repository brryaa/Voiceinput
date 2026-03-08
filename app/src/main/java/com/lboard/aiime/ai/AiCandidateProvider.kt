package com.lboard.aiime.ai

import com.lboard.aiime.engine.PinyinDictionary
import kotlinx.coroutines.*

/**
 * AI 候选词提供者
 *
 * 整合本地拼音词典和 Gemini AI 候选词，提供统一的候选词列表。
 * 实现防抖、离线降级等策略。
 */
class AiCandidateProvider {

    private val geminiClient = GeminiApiClient()
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    /** 当前正在进行的 AI 请求 */
    private var currentJob: Job? = null

    /** 上下文缓冲 — 记录最近提交的文字 */
    private val contextBuffer = StringBuilder()
    private val maxContextLength = 50

    /**
     * 更新 API Key
     */
    fun updateApiKey(apiKey: String) {
        geminiClient.updateApiKey(apiKey)
    }

    /**
     * 获取合并后的候选词（本地 + AI）
     *
     * @param pinyin 当前拼音
     * @param onLocalResult 本地候选词回调（立即返回）
     * @param onAiResult AI 候选词回调（异步返回）
     */
    fun getCandidates(
        pinyin: String,
        onLocalResult: (List<String>) -> Unit,
        onAiResult: (List<String>) -> Unit
    ) {
        // 1. 立即返回本地候选
        val localCandidates = PinyinDictionary.lookup(pinyin)
        onLocalResult(localCandidates)

        // 2. 取消之前的 AI 请求
        currentJob?.cancel()

        // 3. 如果 API 未配置或拼音太短，不请求 AI
        if (!geminiClient.isConfigured() || pinyin.length < 2) {
            return
        }

        // 4. 防抖 300ms 后请求 AI
        currentJob = scope.launch {
            delay(300)

            val context = contextBuffer.toString()
            val result = geminiClient.getAiCandidates(pinyin, context)

            result.onSuccess { aiCandidates ->
                if (isActive) {
                    // 合并去重：本地候选在前，AI 候选在后
                    val merged = mergeCandidates(localCandidates, aiCandidates)
                    withContext(Dispatchers.Main) {
                        onAiResult(merged)
                    }
                }
            }
            // AI 失败时静默降级，保持本地候选
        }
    }

    /**
     * 记录已提交的文字到上下文
     */
    fun appendContext(text: String) {
        contextBuffer.append(text)
        if (contextBuffer.length > maxContextLength) {
            contextBuffer.delete(0, contextBuffer.length - maxContextLength)
        }
    }

    /**
     * 清除上下文
     */
    fun clearContext() {
        contextBuffer.clear()
    }

    /**
     * 取消当前 AI 请求
     */
    fun cancelCurrentRequest() {
        currentJob?.cancel()
    }

    /**
     * 释放资源
     */
    fun dispose() {
        scope.cancel()
    }

    /**
     * AI 是否已配置
     */
    fun isAiAvailable(): Boolean = geminiClient.isConfigured()

    /**
     * 合并本地候选和 AI 候选
     */
    private fun mergeCandidates(
        local: List<String>,
        ai: List<String>
    ): List<String> {
        val localSet = local.toSet()
        val aiOnly = ai.filter { it !in localSet }
        return local + aiOnly
    }
}

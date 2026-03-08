package com.lboard.aiime.ai

import com.google.ai.client.generativeai.GenerativeModel
import com.google.ai.client.generativeai.type.generationConfig
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Gemini API 客户端
 *
 * 封装 Generative AI SDK 调用，为输入法提供 AI 候选词。
 * API Key 由用户在 App 设置中配置，不硬编码。
 */
class GeminiApiClient {

    companion object {
        // 使用 flash 模型以获得最快响应
        private const val MODEL_NAME = "gemini-2.0-flash"

        // 最大缓存条目数
        private const val MAX_CACHE_SIZE = 100
    }

    /** 当前配置的 API Key */
    private var apiKey: String = ""

    /** GenerativeModel 实例（API Key 变化时重建） */
    private var model: GenerativeModel? = null
    private var modelApiKey: String = "" // 记录 model 对应的 key

    // 简单的内存缓存
    private val cache = LinkedHashMap<String, List<String>>(MAX_CACHE_SIZE, 0.75f, true)
    private val cacheMutex = Mutex()

    /**
     * 更新 API Key
     */
    fun updateApiKey(newApiKey: String) {
        apiKey = newApiKey.trim()
        // Key 变化时清除缓存，model 会在下次使用时重建
        if (apiKey != modelApiKey) {
            model = null
            cache.clear()
        }
    }

    /**
     * 获取或创建 GenerativeModel
     */
    private fun getOrCreateModel(): GenerativeModel? {
        if (apiKey.isBlank()) return null

        if (model == null || modelApiKey != apiKey) {
            model = GenerativeModel(
                modelName = MODEL_NAME,
                apiKey = apiKey,
                generationConfig = generationConfig {
                    temperature = 0.3f
                    topK = 40
                    topP = 0.8f
                    maxOutputTokens = 100
                },
                systemInstruction = com.google.ai.client.generativeai.type.content {
                    text(SYSTEM_PROMPT)
                }
            )
            modelApiKey = apiKey
        }

        return model
    }

    /**
     * 获取 AI 候选词
     *
     * @param pinyin 当前输入的拼音
     * @param context 上下文文本（已输入的内容）
     * @return AI 推荐的候选字/词列表
     */
    suspend fun getAiCandidates(
        pinyin: String,
        context: String = ""
    ): Result<List<String>> {
        if (pinyin.isBlank()) return Result.success(emptyList())

        val currentModel = getOrCreateModel()
            ?: return Result.failure(Exception("API Key 未配置"))

        val cacheKey = "$pinyin|$context"

        // 检查缓存
        cacheMutex.withLock {
            cache[cacheKey]?.let { return Result.success(it) }
        }

        return try {
            val prompt = buildPrompt(pinyin, context)

            val response = withTimeout(3000L) {
                currentModel.generateContent(prompt)
            }

            val text = response.text ?: return Result.success(emptyList())
            val candidates = parseResponse(text)

            // 写入缓存
            cacheMutex.withLock {
                if (cache.size >= MAX_CACHE_SIZE) {
                    val firstKey = cache.keys.first()
                    cache.remove(firstKey)
                }
                cache[cacheKey] = candidates
            }

            Result.success(candidates)

        } catch (e: TimeoutCancellationException) {
            Result.failure(Exception("AI 响应超时"))
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * 清除缓存
     */
    suspend fun clearCache() {
        cacheMutex.withLock { cache.clear() }
    }

    /**
     * 检查 API Key 是否已配置
     */
    fun isConfigured(): Boolean {
        return apiKey.isNotBlank()
    }

    /**
     * 构建提示词
     */
    private fun buildPrompt(pinyin: String, context: String): String {
        return if (context.isNotBlank()) {
            "上下文：「${context}」\n拼音：$pinyin"
        } else {
            "拼音：$pinyin"
        }
    }

    /**
     * 解析 AI 响应为候选词列表
     */
    private fun parseResponse(text: String): List<String> {
        return text
            .replace("，", ",")
            .replace("、", ",")
            .replace("\n", ",")
            .split(",")
            .map { it.trim() }
            .filter { it.isNotBlank() && it.length <= 10 }
            .distinct()
            .take(10)
    }
}

/**
 * 系统提示词 — 指导 Gemini 作为中文输入法 AI 引擎
 */
private const val SYSTEM_PROMPT = """你是一个中文输入法的AI候选词引擎。

用户会给你拼音和可能的上下文，你需要返回最合适的中文候选字/词。

规则：
1. 返回 5-10 个最可能的候选词，用逗号分隔
2. 按概率从高到低排序
3. 优先返回词组，其次是单字
4. 考虑上下文语境选择最合适的候选
5. 只返回候选词，不要其他任何解释文字
6. 如果拼音不完整，猜测可能的完整词

示例：
输入：拼音：nihao
输出：你好,你号,你豪

输入：上下文：「今天天气」 拼音：henhao
输出：很好,很好的,很浩"""

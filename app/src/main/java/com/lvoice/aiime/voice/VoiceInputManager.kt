package com.lvoice.aiime.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import com.lvoice.aiime.data.UserPreferences
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 语音输入状态
 */
sealed class VoiceState {
    data object Idle : VoiceState()
    data object Listening : VoiceState()
    data class PartialResult(val text: String) : VoiceState()
    data class Result(val text: String) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/**
 * 语音输入管理器
 *
 * 封装 Android SpeechRecognizer API，提供中文语音识别功能。
 */
class VoiceInputManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    /** 回调：最终识别结果 */
    var onFinalResult: ((String) -> Unit)? = null

    /** 当前语言 */
    private var language: String = "zh-CN"

    /** 是否处于连续听写状态 */
    private var isContinuousListening: Boolean = false

    fun updateSettings(apiKey: String, authMethod: UserPreferences.AuthMethod, idToken: String?) {
        // Kept for structure alignment, but not needed for native STT
    }

    /**
     * 检查语音识别是否可用
     */
    fun isAvailable(): Boolean {
        return SpeechRecognizer.isRecognitionAvailable(context)
    }

    /**
     * 开始语音识别
     * @param isChineseMode 是否为中文模式
     */
    fun startListening(isChineseMode: Boolean = true) {
        language = if (isChineseMode) "zh-CN" else "en-US"
        isContinuousListening = true
        Log.d("VoiceInputManager", "startListening: isChineseMode=$isChineseMode, continuous=true")
        startRecognizer()
    }

    private fun startRecognizer() {
        if (speechRecognizer == null) {
            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)
            speechRecognizer?.setRecognitionListener(recognitionListener)
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
            // 1200ms of silence counts as "complete" silence
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1200L)
            // 400ms of silence counts as "possibly complete" silence (results in onResults)
            putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
        }

        _state.value = VoiceState.Listening
        speechRecognizer?.startListening(intent)
        Log.d("VoiceInputManager", "SpeechRecognizer started")
    }

    /**
     * 停止语音识别 (手动点击停止按钮时调用)
     */
    fun stopListeningAndProcess() {
        Log.d("VoiceInputManager", "stopListeningAndProcess")
        isContinuousListening = false
        speechRecognizer?.stopListening()
    }

    /**
     * 取消语音识别
     */
    fun cancel() {
        Log.d("VoiceInputManager", "cancel")
        isContinuousListening = false
        speechRecognizer?.cancel()
        _state.value = VoiceState.Idle
    }

    /**
     * 释放资源
     */
    fun destroy() {
        Log.d("VoiceInputManager", "destroy")
        isContinuousListening = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        _state.value = VoiceState.Idle
    }

    /**
     * 是否正在录音
     */
    fun isListening(): Boolean = _state.value is VoiceState.Listening ||
            _state.value is VoiceState.PartialResult

    fun forceError(message: String) {
        _state.value = VoiceState.Error(message)
    }

    // ========== RecognitionListener ==========

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            Log.d("VoiceInputManager", "onReadyForSpeech")
            _state.value = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {
            Log.d("VoiceInputManager", "onBeginningOfSpeech")
        }

        override fun onRmsChanged(rmsdB: Float) {}

        override fun onBufferReceived(buffer: ByteArray?) {}

        override fun onEndOfSpeech() {
            Log.d("VoiceInputManager", "onEndOfSpeech")
        }

        override fun onError(error: Int) {
            val isTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            Log.d("VoiceInputManager", "onError: $error, isTimeout=$isTimeout")
            
            val message = when (error) {
                SpeechRecognizer.ERROR_AUDIO -> "录音错误"
                SpeechRecognizer.ERROR_CLIENT -> "" // Client error, usually silent recovery
                SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "请授予麦克风权限"
                SpeechRecognizer.ERROR_NETWORK -> "网络错误"
                SpeechRecognizer.ERROR_NETWORK_TIMEOUT -> "网络超时"
                SpeechRecognizer.ERROR_NO_MATCH -> "" // No match, silent recovery in continuous
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> "识别器忙碌"
                SpeechRecognizer.ERROR_SERVER -> "服务器错误"
                SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> "语音输入结束" // 1200ms reached
                else -> "识别失败 ($error)"
            }
            
            if (isTimeout) {
                // 1200ms reached, stop continuous mode and exit
                Log.d("VoiceInputManager", "1200ms timeout reached, stopping...")
                isContinuousListening = false
                _state.value = VoiceState.Idle
            } else if (message.isNotBlank()) {
                // Actual crash/error
                Log.e("VoiceInputManager", "Critical error: $message")
                _state.value = VoiceState.Error(message)
                isContinuousListening = false
            } else {
                // Recoverable errors (NO_MATCH, CLIENT)
                if (isContinuousListening) {
                    Log.d("VoiceInputManager", "Silent error, restarting for continuous mode")
                    speechRecognizer?.destroy()
                    speechRecognizer = null
                    startRecognizer()
                } else {
                    _state.value = VoiceState.Idle
                }
            }
        }

        override fun onResults(results: Bundle?) {
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            Log.d("VoiceInputManager", "onResults: text='$text'")

            if (text.isNotBlank()) {
                // Apply punctuation (400ms pause usually triggers this)
                val punctuation = if (language == "zh-CN") "，" else ", "
                val finalSentence = text + punctuation
                
                _state.value = VoiceState.Result(finalSentence)
                onFinalResult?.invoke(finalSentence)
            }
            
            if (isContinuousListening) {
                Log.d("VoiceInputManager", "Restarting for next sentence...")
                startRecognizer()
            } else {
                _state.value = VoiceState.Idle
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                _state.value = VoiceState.PartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

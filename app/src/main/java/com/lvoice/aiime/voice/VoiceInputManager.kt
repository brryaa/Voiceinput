package com.lvoice.aiime.voice

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File

/**
 * 语音输入状态
 */
sealed class VoiceState {
    data object Idle : VoiceState()
    data object Listening : VoiceState()
    data class PartialResult(val text: String) : VoiceState()
    data class Result(val text: String, val audioFile: File? = null) : VoiceState()
    data class Error(val message: String) : VoiceState()
}

/**
 * 语音输入管理器 (Hybrid Version)
 */
class VoiceInputManager(private val context: Context) {

    private var speechRecognizer: SpeechRecognizer? = null
    private val audioRecorder = AudioRecorder()
    private val mainHandler = Handler(Looper.getMainLooper())

    private val _state = MutableStateFlow<VoiceState>(VoiceState.Idle)
    val state: StateFlow<VoiceState> = _state.asStateFlow()

    var onFinalResult: ((String, File?) -> Unit)? = null

    private var language: String = "zh-CN"
    private var isContinuousListening: Boolean = false
    private var currentAudioFile: File? = null
    private var isStarting = false
    
    // 我们需要一个绝对的定时器来管控所有超时，屏蔽底层引擎的差异
    private var timeoutRunnable: Runnable? = null
    private var hasReceivedValidTextThisSession = false
    private var forceExitAfterResult = false // 用于强制终止并在 onResults 后彻底退出

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(isChineseMode: Boolean = true) {
        language = if (isChineseMode) "zh-CN" else "en-US"
        isContinuousListening = true
        hasReceivedValidTextThisSession = false
        forceExitAfterResult = false
        isStarting = false
        
        // 规则1：初始化后2s没有语音输入才退出 (Initial Silence)
        startAbsoluteTimeout(2000L) {
            Log.d("VoiceInputManager", "Rule 1: Initial 2s silence timeout reached.")
            forceStopAndIdle()
        }
        
        startRecognizer()
    }

    private fun startRecognizer() {
        if (isStarting) return
        isStarting = true
        
        mainHandler.post {
            try {
                // 每次启动倾向使用新实例，防止部分机型底层死锁
                speechRecognizer?.destroy()
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(context).apply {
                    setRecognitionListener(recognitionListener)
                }

                currentAudioFile = File(context.cacheDir, "voice_chunk_${System.currentTimeMillis()}.wav")
                audioRecorder.startRecording(currentAudioFile!!)

                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                    putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                    putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                    putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)
                    // 放宽底层引擎的内部超时，将生命周期生死大权完全交接给我们的 absolute timer
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 4000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 4000L)
                }

                speechRecognizer?.startListening(intent)
                Log.d("VoiceInputManager", "Session internal recognizer started.")
            } catch (e: Exception) {
                Log.e("VoiceInputManager", "Failed to start recognizer", e)
                _state.value = VoiceState.Error("启动失败: ${e.message}")
            } finally {
                isStarting = false
            }
        }
    }

    private fun startAbsoluteTimeout(delayMs: Long, action: () -> Unit) {
        cancelAbsoluteTimeout()
        Log.d("VoiceInputManager", "Starting absolute timeout: ${delayMs}ms")
        timeoutRunnable = Runnable {
            if (isContinuousListening) {
                action()
            }
        }
        mainHandler.postDelayed(timeoutRunnable!!, delayMs)
    }

    private fun cancelAbsoluteTimeout() {
        timeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        timeoutRunnable = null
    }

    fun stopListeningAndProcess() {
        isContinuousListening = false
        forceExitAfterResult = true
        cancelAbsoluteTimeout()
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.stopListening() // 强制结束以输出文字
        }
    }

    fun forceStopAndIdle() {
        Log.d("VoiceInputManager", "forceStopAndIdle executing.")
        cancelAbsoluteTimeout()
        isContinuousListening = false
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            _state.value = VoiceState.Idle
        }
    }

    fun cancel() { forceStopAndIdle() }
    fun destroy() { forceStopAndIdle() }

    fun isListening(): Boolean = _state.value is VoiceState.Listening ||
            _state.value is VoiceState.PartialResult

    fun forceError(message: String) {
        _state.value = VoiceState.Error(message)
    }

    // ========== RecognitionListener ==========

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening
        }

        override fun onBeginningOfSpeech() {
            // 规则2：首次听到声音（可能只是噪音），如果1.2s内没有识别出有效文字，强制退出
            if (!hasReceivedValidTextThisSession) {
                startAbsoluteTimeout(1200L) {
                    Log.d("VoiceInputManager", "Rule 2: Initial 1.2s noise timeout reached (No valid text).")
                    forceStopAndIdle()
                }
            }
        }

        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val isTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val isNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH
            
            Log.d("VoiceInputManager", "onError: $error (timeout=$isTimeout, noMatch=$isNoMatch)")
            
            if (isTimeout) {
                // 如果底层的纯静音超时真的触发了，直接强退（作为最后的兜底防护）
                Log.d("VoiceInputManager", "Engine internal silence timeout reached, session end")
                forceStopAndIdle()
            } else if (isNoMatch || error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 11) {
                // 短周期的环境噪音或者网络波动，只要我们的 absolute timer 没走到头，就不断重启保持生命力！
                if (isContinuousListening) {
                    Log.d("VoiceInputManager", "Transient error/NoMatch ($error). Restarting chunk underneath absolute timer...")
                    mainHandler.postDelayed({ startRecognizer() }, 100)
                } else {
                    forceStopAndIdle()
                }
            } else {
                _state.value = VoiceState.Error("识别器错误 ($error)")
                forceStopAndIdle()
            }
        }

        override fun onResults(results: Bundle?) {
            // 一句话处理结束
            cancelAbsoluteTimeout()
            audioRecorder.stopRecording()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                hasReceivedValidTextThisSession = true
                val punctuation = if (language == "zh-CN") "，" else ", "
                val finalSentence = text + punctuation
                
                _state.value = VoiceState.Result(finalSentence, currentAudioFile)
                onFinalResult?.invoke(finalSentence, currentAudioFile)
            }
            
            if (isContinuousListening && !forceExitAfterResult) {
                // 用户还在听写态。现在重启下一个 chunk。
                // 规则3：有识别到有效内容之后，安静状态没有后续语音输入 800ms 退出。
                startAbsoluteTimeout(800L) {
                    Log.d("VoiceInputManager", "Rule 3: Post-text 800ms strict silence timeout reached.")
                    forceStopAndIdle()
                }
                startRecognizer()
            } else {
                Log.d("VoiceInputManager", "Finalized session completely.")
                forceStopAndIdle()
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                hasReceivedValidTextThisSession = true
                
                // 只要一有字，就重置规则4：有效内容之后连续无效内容（不管吵不吵）1s后退出
                startAbsoluteTimeout(1000L) {
                    Log.d("VoiceInputManager", "Rule 4: Post-valid-text silence/noise timeout (1s). Forcing process.")
                    stopListeningAndProcess() 
                }
                
                _state.value = VoiceState.PartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

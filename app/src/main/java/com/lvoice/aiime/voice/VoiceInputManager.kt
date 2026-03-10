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
 * 语音输入管理器 (Hybrid Version - Stable Watchdog)
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
    
    // --- 严苛的独立看门狗机制 (Watchdog Timer) ---
    private var sessionStartTime = 0L
    private var lastValidTextTime = 0L
    private var firstNoiseTime = 0L
    private var hasSpeechStarted = false
    private var watchdogActive = false
    private var forceExitAfterResult = false // 用于强制终止并在 onResults 后彻底退出
    
    // 我们完全禁用底层的自动断句，把生死大权交给 Watchdog
    private val NATIVE_TIMEOUT = 10000L

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(isChineseMode: Boolean = true) {
        language = if (isChineseMode) "zh-CN" else "en-US"
        isContinuousListening = true
        isStarting = false
        forceExitAfterResult = false
        
        sessionStartTime = System.currentTimeMillis()
        lastValidTextTime = 0L
        firstNoiseTime = 0L
        hasSpeechStarted = false
        startWatchdog()
        
        startRecognizer()
    }

    private fun startRecognizer() {
        if (isStarting) return
        isStarting = true
        
        mainHandler.post {
            try {
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
                    // 完全放宽底层限制，防止系统误杀
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, NATIVE_TIMEOUT)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, NATIVE_TIMEOUT)
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

    // ========== Watchdog Timer Logic ==========

    private fun startWatchdog() {
        watchdogActive = true
        mainHandler.removeCallbacks(watchdogRunnable)
        mainHandler.postDelayed(watchdogRunnable, 100)
    }

    private fun stopWatchdog() {
        watchdogActive = false
        mainHandler.removeCallbacks(watchdogRunnable)
    }

    private val watchdogRunnable = object : Runnable {
        override fun run() {
            if (!watchdogActive || !isContinuousListening) return
            
            val now = System.currentTimeMillis()
            val elapsedSinceStart = now - sessionStartTime
            val elapsedSinceLastText = if (lastValidTextTime > 0) now - lastValidTextTime else 0L

            if (lastValidTextTime == 0L) {
                // 初期阶段：连半个有效字都没出来
                // ★ 绝对免死金牌：前 3000ms 无论环境有多吵，无论发生什么，绝对不准因为“没字”而退出！
                // 这给了用户充足的启动、缓冲、深呼吸和开口时间。
                if (elapsedSinceStart < 3000L) return

                if (hasSpeechStarted) {
                    // 听到了声音信号，但一直是噪音
                    // 规则2: 听到语音没有识别文字 1.2s后退出 (但有了免死金牌，最早也要等 3.0s 才能处决)
                    val elapsedSinceNoise = now - firstNoiseTime
                    if (elapsedSinceNoise >= 1200L) {
                        Log.d("VoiceInputManager", "Watchdog Rule 2: Initial noise timeout reached.")
                        forceStopAndIdle() // 完全没输入过有效文字，不用提词，直接暴力退出
                        return
                    }
                } else {
                    // 安安静静什么都没听到
                    // 规则1: 初始化无言超时 (也受金牌保护，所以真正执行时已经是 >= 3s 了)
                    Log.d("VoiceInputManager", "Watchdog Rule 1: Initial silence timeout reached.")
                    forceStopAndIdle()
                    return
                }
            } else {
                // 已经有稳定的文字输出了
                // 规则3 & 4 (语段间绝对超时)：有字之后，无论环境多吵，只要超过 1000ms 没有提取出新内容，强制断句！
                if (elapsedSinceLastText >= 1000L) {
                    Log.d("VoiceInputManager", "Watchdog Rule 3/4: Post-text stable silence/noise timeout (1s) reached.")
                    stopListeningAndProcess() // 通知识别器收工，把草稿转正
                    return
                }
            }

            // 继续巡视
            mainHandler.postDelayed(this, 100)
        }
    }

    // ========== Session Management ==========

    fun stopListeningAndProcess() {
        isContinuousListening = false
        forceExitAfterResult = true
        stopWatchdog()
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.stopListening() // 强制进入 onResults 环节
        }
    }

    fun forceStopAndIdle() {
        stopWatchdog()
        isContinuousListening = false
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            _state.value = VoiceState.Idle
        }
    }

    fun forceStop() = forceStopAndIdle()
    fun cancel() = forceStopAndIdle()
    fun destroy() = forceStopAndIdle()

    fun isListening(): Boolean = _state.value is VoiceState.Listening ||
            _state.value is VoiceState.PartialResult

    fun forceError(message: String) {
        _state.value = VoiceState.Error(message)
    }

    // ========== RecognitionListener ==========

    private val recognitionListener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) {
            _state.value = VoiceState.Listening
            // 每次准备好，把倒计时原点重置一下，免得开机卡顿吃掉时间
            if (lastValidTextTime == 0L) {
                sessionStartTime = System.currentTimeMillis()
                firstNoiseTime = 0L
                hasSpeechStarted = false
            }
        }

        override fun onBeginningOfSpeech() {
            if (!hasSpeechStarted) {
                hasSpeechStarted = true
                firstNoiseTime = System.currentTimeMillis()
            }
            Log.d("VoiceInputManager", "onBeginningOfSpeech detected noise/voice.")
        }
        
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            val isTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val isNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH
            Log.d("VoiceInputManager", "onError: $error (timeout=$isTimeout, noMatch=$isNoMatch)")
            
            // 各种报错如果在 watchdog 的控制期内，我们直接无视并立刻重启引擎续命！
            if (isContinuousListening && !forceExitAfterResult) {
                Log.d("VoiceInputManager", "Transient error/NoMatch ($error). Restarting chunk underneath watchdog...")
                mainHandler.postDelayed({ startRecognizer() }, 50)
            } else {
                forceStopAndIdle()
            }
        }

        override fun onResults(results: Bundle?) {
            // 一个分片处理结束
            audioRecorder.stopRecording()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                val punctuation = if (language == "zh-CN") "，" else ", "
                val finalSentence = text + punctuation
                _state.value = VoiceState.Result(finalSentence, currentAudioFile)
                onFinalResult?.invoke(finalSentence, currentAudioFile)
            }
            
            if (isContinuousListening && !forceExitAfterResult) {
                // Chunk 自动结束（比如网络极好提前返回），而且时间还没到，我们就重启继续录
                Log.d("VoiceInputManager", "Result arrived early. Re-starting chunk...")
                startRecognizer()
            } else {
                Log.d("VoiceInputManager", "Finalized session completely.")
                forceStopAndIdle() // 触发 Idle 切出键盘
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                // 收到货真价实的字了，喂食看门狗！
                lastValidTextTime = System.currentTimeMillis()
                _state.value = VoiceState.PartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }
}

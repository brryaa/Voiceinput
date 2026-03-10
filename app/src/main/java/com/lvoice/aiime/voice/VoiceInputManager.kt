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
import com.lvoice.aiime.data.UserPreferences
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
    
    private var silentRetryCount = 0
    private val MAX_SILENT_RETRIES = 2
    
    private var noiseTimeoutRunnable: Runnable? = null
    private val NOISE_TIMEOUT_MS = 800L

    fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    fun startListening(isChineseMode: Boolean = true) {
        language = if (isChineseMode) "zh-CN" else "en-US"
        isContinuousListening = true
        silentRetryCount = 0
        isStarting = false
        startRecognizer()
    }

    private fun startRecognizer() {
        if (isStarting) return
        isStarting = true
        resetNoiseTimeout()
        
        mainHandler.post {
            try {
                // 每次启动都倾向于使用新的实例，防止 ERROR_CLIENT 锁死
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
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 800L)
                    putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 400L)
                }

                speechRecognizer?.startListening(intent)
                Log.d("VoiceInputManager", "Session started (re-created), silentRetry=$silentRetryCount")
            } catch (e: Exception) {
                Log.e("VoiceInputManager", "Failed to start recognizer", e)
                _state.value = VoiceState.Error("启动失败: ${e.message}")
            } finally {
                isStarting = false
            }
        }
    }

    fun stopListeningAndProcess() {
        isContinuousListening = false
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.stopListening()
        }
    }

    fun forceStop() {
        Log.d("VoiceInputManager", "forceStop requested")
        isAlwaysStop()
    }

    private fun isAlwaysStop() {
        cancelNoiseTimeout()
        isContinuousListening = false
        audioRecorder.stopRecording()
        mainHandler.post {
            speechRecognizer?.cancel()
            speechRecognizer?.destroy()
            speechRecognizer = null
            _state.value = VoiceState.Idle
        }
    }

    fun cancel() {
        isAlwaysStop()
    }

    fun destroy() {
        isAlwaysStop()
    }

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

        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}

        override fun onError(error: Int) {
            cancelNoiseTimeout()
            audioRecorder.stopRecording()
            val isTimeout = error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
            val isNoMatch = error == SpeechRecognizer.ERROR_NO_MATCH
            
            Log.d("VoiceInputManager", "onError: $error (timeout=$isTimeout, noMatch=$isNoMatch)")
            
            if (isTimeout || isNoMatch) {
                if (isContinuousListening && silentRetryCount < MAX_SILENT_RETRIES) {
                    silentRetryCount++
                    Log.d("VoiceInputManager", "Silence detected, retrying ($silentRetryCount/$MAX_SILENT_RETRIES)")
                    startRecognizer()
                } else {
                    Log.d("VoiceInputManager", "Max silence reached, session end")
                    isContinuousListening = false
                    _state.value = VoiceState.Idle
                }
            } else if (error == SpeechRecognizer.ERROR_CLIENT || error == SpeechRecognizer.ERROR_RECOGNIZER_BUSY || error == 11) {
                // 11 通常是暂时的连接问题或语言包校验失败，尝试重连
                if (isContinuousListening) {
                    Log.d("VoiceInputManager", "Recoverable error ($error), post-delayed restart...")
                    mainHandler.postDelayed({ startRecognizer() }, 1000)
                } else {
                    _state.value = VoiceState.Idle
                }
            } else {
                _state.value = VoiceState.Error("识别器错误 ($error)")
                isContinuousListening = false
            }
        }

        override fun onResults(results: Bundle?) {
            cancelNoiseTimeout()
            audioRecorder.stopRecording()
            val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""

            if (text.isNotBlank()) {
                silentRetryCount = 0
                val punctuation = if (language == "zh-CN") "，" else ", "
                val finalSentence = text + punctuation
                
                _state.value = VoiceState.Result(finalSentence, currentAudioFile)
                onFinalResult?.invoke(finalSentence, currentAudioFile)
            } else {
                silentRetryCount++
            }
            
            if (isContinuousListening && silentRetryCount <= MAX_SILENT_RETRIES) {
                startRecognizer()
            } else {
                Log.d("VoiceInputManager", "Finalized session naturally")
                isContinuousListening = false
                _state.value = VoiceState.Idle
            }
        }

        override fun onPartialResults(partialResults: Bundle?) {
            val matches = partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
            val text = matches?.firstOrNull() ?: ""
            if (text.isNotBlank()) {
                resetNoiseTimeout()
                _state.value = VoiceState.PartialResult(text)
            }
        }

        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun resetNoiseTimeout() {
        cancelNoiseTimeout()
        noiseTimeoutRunnable = Runnable {
            if (isContinuousListening) {
                Log.d("VoiceInputManager", "Noise timeout reached (${NOISE_TIMEOUT_MS}ms without valid text)")
                isContinuousListening = false
                audioRecorder.stopRecording()
                speechRecognizer?.cancel()
                _state.value = VoiceState.Idle
            }
        }
        mainHandler.postDelayed(noiseTimeoutRunnable!!, NOISE_TIMEOUT_MS)
    }

    private fun cancelNoiseTimeout() {
        noiseTimeoutRunnable?.let { mainHandler.removeCallbacks(it) }
        noiseTimeoutRunnable = null
    }
}

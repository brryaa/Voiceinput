package com.lvoice.aiime.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.runtime.*
import androidx.compose.ui.platform.ComposeView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.lvoice.aiime.auth.GeminiAuthManager
import com.lvoice.aiime.data.UserPreferences
import com.lvoice.aiime.ime.keyboard.VoiceScreen
import com.lvoice.aiime.voice.GeminiVoiceClient
import com.lvoice.aiime.voice.VoiceInputManager
import com.lvoice.aiime.voice.VoiceState
import kotlinx.coroutines.*
import java.io.File

class LvoiceIME : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private lateinit var voiceInputManager: VoiceInputManager
    private lateinit var userPreferences: UserPreferences
    private lateinit var authManager: GeminiAuthManager
    private var geminiVoiceClient: GeminiVoiceClient? = null
    
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var stateObserverJob: Job? = null
    private var hasActiveSession = false
    
    private var lastDraftLength = 0
    private val _isRefining = mutableStateOf(false)

    override fun onCreate() {
        super.onCreate()
        android.util.Log.d("LvoiceIME", "onCreate")
        
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        userPreferences = UserPreferences.getInstance(applicationContext)
        voiceInputManager = VoiceInputManager(this)
        authManager = GeminiAuthManager(this)

        // Sync settings
        scope.launch {
            userPreferences.geminiApiKeyFlow.collect { apiKey ->
                if (apiKey.isNotBlank()) {
                    geminiVoiceClient = GeminiVoiceClient(apiKey)
                }
            }
        }

        // Handle transcription result
        voiceInputManager.onFinalResult = { draftText, audioFile ->
            if (draftText.isNotBlank()) {
                currentInputConnection?.commitText(draftText, 1)
                lastDraftLength = draftText.length
                
                if (audioFile != null && geminiVoiceClient != null) {
                    refineWithGemini(audioFile, draftText)
                }
            }
        }
    }

    private fun startObservingState() {
        stateObserverJob?.cancel()
        stateObserverJob = scope.launch {
            voiceInputManager.state.collect { state ->
                android.util.Log.d("LvoiceIME", "Observed state: $state, currentSessionActive=$hasActiveSession")
                when (state) {
                    is VoiceState.Listening -> {
                        hasActiveSession = true
                        android.util.Log.d("LvoiceIME", "Session confirmed active")
                    }
                    is VoiceState.PartialResult, is VoiceState.Result -> {
                        // Keep hasActiveSession = true
                    }
                    is VoiceState.Idle -> {
                        if (hasActiveSession) {
                            android.util.Log.d("LvoiceIME", "Session reached Idle. Preparing auto-exit...")
                            hasActiveSession = false
                            
                            scope.launch {
                                var retry = 0
                                // 等待精修完成，或者等待文字被 commit
                                while (_isRefining.value && retry < 25) { 
                                    delay(100)
                                    retry++
                                }
                                android.util.Log.d("LvoiceIME", "Switching back after refinement/timeout (retry=$retry)")
                                delay(200)
                                switchToPreviousInputMethod()
                            }
                        }
                    }
                    is VoiceState.Error -> {
                        android.util.Log.e("LvoiceIME", "Session error: ${state.message}")
                        hasActiveSession = false
                        delay(1500)
                        switchToPreviousInputMethod()
                    }
                }
            }
        }
    }

    private fun refineWithGemini(audioFile: File, draftText: String) {
        // 使用 GlobalScope 确保纠偏过程不会因为 IME 实例被销毁而取消
        @OptIn(DelicateCoroutinesApi::class)
        GlobalScope.launch(Dispatchers.Main) {
            _isRefining.value = true
            try {
                android.util.Log.d("LvoiceIME", "Refinement started for: '$draftText'")
                val refinedText = geminiVoiceClient?.refineSTT(audioFile, draftText)
                
                if (refinedText != null && refinedText != draftText && refinedText.isNotBlank()) {
                    android.util.Log.d("LvoiceIME", "Applying refined text: '$refinedText'")
                    currentInputConnection?.let { conn ->
                        conn.deleteSurroundingText(lastDraftLength, 0)
                        conn.commitText(refinedText, 1)
                    } ?: android.util.Log.e("LvoiceIME", "InputConnection null, refinement lost")
                }
            } catch (e: Exception) {
                android.util.Log.e("LvoiceIME", "Refinement launch exception", e)
            } finally {
                _isRefining.value = false
                audioFile.delete()
                android.util.Log.d("LvoiceIME", "Refinement task finished")
            }
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LvoiceIME)
            setViewTreeSavedStateRegistryOwner(this@LvoiceIME)
            setViewTreeViewModelStoreOwner(this@LvoiceIME)
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@LvoiceIME)
            )

            setContent {
                val state = voiceInputManager.state.collectAsState().value
                VoiceScreen(
                    voiceState = state,
                    onMicClick = {
                        if (voiceInputManager.isListening()) {
                            voiceInputManager.stopListeningAndProcess()
                        } else {
                            startVoiceInput()
                        }
                    },
                    onCloseClick = {
                        cleanupSession()
                        switchToPreviousInputMethod()
                    }
                )
            }
        }
        return composeView
    }

    override fun onWindowShown() {
        super.onWindowShown()
        android.util.Log.d("LvoiceIME", "onWindowShown")
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        
        // 关键：每次显示窗口都强制重置状态，并开始观察
        hasActiveSession = false
        startObservingState()
        startVoiceInput()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        android.util.Log.d("LvoiceIME", "onWindowHidden")
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        cleanupSession()
    }

    private fun cleanupSession() {
        hasActiveSession = false
        stateObserverJob?.cancel()
        voiceInputManager.forceStop()
    }

    override fun onDestroy() {
        super.onDestroy()
        android.util.Log.d("LvoiceIME", "onDestroy")
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        cleanupSession()
        scope.cancel()
        voiceInputManager.destroy()
    }

    private fun startVoiceInput() {
        val hasPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (hasPermission) {
            voiceInputManager.startListening(true)
        } else {
            voiceInputManager.forceError("缺少录音权限")
        }
    }
}

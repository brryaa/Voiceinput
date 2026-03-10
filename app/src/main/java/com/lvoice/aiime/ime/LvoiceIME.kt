package com.lvoice.aiime.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import androidx.compose.runtime.collectAsState
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
import com.lvoice.aiime.voice.VoiceInputManager
import com.lvoice.aiime.voice.VoiceState
import kotlinx.coroutines.*

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
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private var hasActiveSession = false

    override fun onCreate() {
        super.onCreate()
        
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        userPreferences = UserPreferences.getInstance(applicationContext)
        voiceInputManager = VoiceInputManager(this)
        authManager = GeminiAuthManager(this)

        // Sync settings
        scope.launch {
            userPreferences.geminiApiKeyFlow.collect { apiKey ->
                val authMethod = userPreferences.getAuthMethod()
                val idToken = authManager.getIdToken()
                voiceInputManager.updateSettings(apiKey, authMethod, idToken)
            }
        }

        // Handle transcription result (Continuous mode: one sentence finished)
        voiceInputManager.onFinalResult = { text ->
            if (text.isNotBlank()) {
                currentInputConnection?.commitText(text, 1)
            }
            // NO auto-switch here, wait for Idle
        }

        // Observe VoiceState for session completion
        scope.launch {
            voiceInputManager.state.collect { state ->
                when (state) {
                    is VoiceState.Listening, is VoiceState.PartialResult, is VoiceState.Result -> {
                        hasActiveSession = true
                    }
                    is VoiceState.Idle -> {
                        if (hasActiveSession) {
                            // Session ended via timeout (1200ms) or stop
                            android.util.Log.d("LvoiceIME", "Voice session IDLE, switching back...")
                            hasActiveSession = false
                            delay(500) // Brief delay to show final result if any
                            switchToPreviousInputMethod()
                        }
                    }
                    is VoiceState.Error -> {
                        hasActiveSession = false
                        // Keep the error on screen for a moment
                        delay(2000)
                        switchToPreviousInputMethod()
                    }
                }
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
                        hasActiveSession = false
                        voiceInputManager.cancel()
                        switchToPreviousInputMethod()
                    }
                )
            }
        }
        return composeView
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
        
        hasActiveSession = false // Reset for new entry
        startVoiceInput()
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
        scope.launch {
            hasActiveSession = false
            voiceInputManager.cancel()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
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

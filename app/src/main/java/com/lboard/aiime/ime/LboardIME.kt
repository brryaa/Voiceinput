package com.lboard.aiime.ime

import android.Manifest
import android.content.pm.PackageManager
import android.inputmethodservice.InputMethodService
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.runtime.mutableStateOf
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
import com.lboard.aiime.ai.AiCandidateProvider
import com.lboard.aiime.data.UserPreferences
import com.lboard.aiime.engine.PinyinEngine
import com.lboard.aiime.engine.PinyinState
import com.lboard.aiime.ime.keyboard.KeyboardScreen
import com.lboard.aiime.voice.VoiceInputManager
import com.lboard.aiime.voice.VoiceState
import kotlinx.coroutines.*
import java.util.Locale

/**
 * Lboard 输入法服务
 */
class LboardIME : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    private val pinyinEngine = PinyinEngine()
    private val aiCandidateProvider = AiCandidateProvider()
    private var voiceInputManager: VoiceInputManager? = null

    private lateinit var userPreferences: UserPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    private val pinyinState = mutableStateOf(PinyinState())

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        try {
            userPreferences = UserPreferences.getInstance(applicationContext)
            loadApiKey()
        } catch (e: Exception) {
            android.util.Log.e("LboardIME", "Failed to init preferences", e)
        }

        try {
            initVoiceInput()
        } catch (e: Exception) {
            android.util.Log.e("LboardIME", "Failed to init voice", e)
        }
    }

    override fun onCreateInputView(): View {
        window?.window?.decorView?.let { decorView ->
            decorView.setViewTreeLifecycleOwner(this)
            decorView.setViewTreeViewModelStoreOwner(this)
            decorView.setViewTreeSavedStateRegistryOwner(this)
        }

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LboardIME)
            setViewTreeSavedStateRegistryOwner(this@LboardIME)
            setViewTreeViewModelStoreOwner(this@LboardIME)
            setViewCompositionStrategy(
                androidx.compose.ui.platform.ViewCompositionStrategy.DisposeOnLifecycleDestroyed(this@LboardIME)
            )

            setContent {
                KeyboardScreen(
                    pinyinState = pinyinState.value,
                    onKeyPress = ::handleKeyPress,
                    onBackspace = ::handleBackspace,
                    onEnter = ::handleEnter,
                    onSpacePress = ::handleSpace,
                    onCandidateSelect = ::handleCandidateSelect,
                    onToggleLanguage = ::handleToggleLanguage,
                    onToggleShift = ::handleToggleShift,
                    onVoiceInput = ::handleVoiceInput,
                    onVoiceCancel = ::handleVoiceCancel
                )
            }
        }

        return composeView
    }

    override fun onWindowShown() {
        super.onWindowShown()
        if (lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    override fun onWindowHidden() {
        super.onWindowHidden()
        if (!lifecycleRegistry.currentState.isAtLeast(Lifecycle.State.STARTED)) return
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        aiCandidateProvider.dispose()
        voiceInputManager?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ========== 按键处理 ==========

    private fun handleKeyPress(key: String) {
        val currentState = pinyinState.value
        
        // 处理大小写转换
        val actualKey = if (currentState.isShifted) {
            key.uppercase(Locale.ROOT)
        } else {
            key.lowercase(Locale.ROOT)
        }

        if (pinyinEngine.isChineseMode && actualKey.length == 1 && actualKey[0].lowercaseChar().isLetter()) {
            val state = pinyinEngine.inputLetter(actualKey[0])
            pinyinState.value = state.copy(isAiLoading = aiCandidateProvider.isAiAvailable())
            requestAiCandidates()
        } else {
            // 提交已有输入
            if (pinyinEngine.hasInput()) {
                val state = pinyinEngine.getCurrentState()
                val text = if (state.candidates.isNotEmpty()) state.candidates[0] else state.inputPinyin
                commitText(text)
                pinyinEngine.clearInput()
                aiCandidateProvider.cancelCurrentRequest()
            }
            commitText(actualKey)
            pinyinState.value = pinyinEngine.getCurrentState()
        }
    }

    private fun handleBackspace() {
        if (pinyinEngine.hasInput()) {
            val state = pinyinEngine.deleteLastInput()
            pinyinState.value = state
            if (pinyinEngine.hasInput()) requestAiCandidates()
            else aiCandidateProvider.cancelCurrentRequest()
        } else {
            currentInputConnection?.deleteSurroundingText(1, 0)
        }
    }

    private fun handleToggleShift() {
        pinyinEngine.toggleShift()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    private fun handleToggleLanguage() {
        pinyinEngine.toggleChineseMode()
        aiCandidateProvider.cancelCurrentRequest()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    private fun handleEnter() {
        if (pinyinEngine.hasInput()) {
            commitText(pinyinEngine.getCurrentPinyin())
            pinyinEngine.clearInput()
            pinyinState.value = pinyinEngine.getCurrentState()
        } else {
            currentInputConnection?.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
        }
    }

    private fun handleSpace() {
        if (pinyinEngine.hasInput()) {
            val state = pinyinEngine.getCurrentState()
            val text = if (state.candidates.isNotEmpty()) state.candidates[0] else state.inputPinyin
            commitText(text)
            pinyinEngine.clearInput()
            pinyinState.value = pinyinEngine.getCurrentState()
        } else {
            commitText(" ")
        }
    }

    private fun handleCandidateSelect(index: Int) {
        val state = pinyinEngine.getCurrentState()
        if (index < 0 || index >= state.candidates.size) return
        commitText(state.candidates[index])
        pinyinEngine.clearInput()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    private fun commitText(text: String) {
        currentInputConnection?.commitText(text, 1)
        aiCandidateProvider.appendContext(text)
    }

    private fun loadApiKey() {
        serviceScope.launch {
            try {
                val apiKey = userPreferences.getGeminiApiKey()
                aiCandidateProvider.updateApiKey(apiKey)
            } catch (_: Exception) {}
        }
    }

    private fun initVoiceInput() {
        voiceInputManager = VoiceInputManager(this)
        voiceInputManager?.onFinalResult = { text ->
            commitText(text)
            pinyinState.value = pinyinEngine.getCurrentState()
        }
        serviceScope.launch {
            voiceInputManager?.state?.collect { voiceState ->
                val current = pinyinState.value
                pinyinState.value = when (voiceState) {
                    is VoiceState.Idle -> current.copy(isVoiceListening = false)
                    is VoiceState.Listening -> current.copy(isVoiceListening = true, voicePartialResult = "")
                    is VoiceState.PartialResult -> current.copy(voicePartialResult = voiceState.text)
                    is VoiceState.Result -> current.copy(isVoiceListening = false)
                    is VoiceState.Error -> current.copy(voiceError = voiceState.message)
                }
            }
        }
    }

    private fun handleVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        voiceInputManager?.startListening(pinyinEngine.isChineseMode)
    }

    private fun handleVoiceCancel() {
        voiceInputManager?.cancel()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    private fun requestAiCandidates() {
        val pinyin = pinyinEngine.getCurrentPinyin()
        if (pinyin.isBlank()) return
        aiCandidateProvider.getCandidates(pinyin, {}, { merged ->
            if (pinyinEngine.getCurrentPinyin() == pinyin) {
                pinyinEngine.updateCandidates(merged)
                pinyinState.value = pinyinEngine.getCurrentState().copy(hasAiCandidates = true)
            }
        })
    }
}

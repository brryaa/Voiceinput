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

/**
 * Lboard 输入法服务
 *
 * 继承 InputMethodService，作为整个输入法的核心入口。
 * 管理键盘视图、拼音引擎、AI 候选词和语音输入。
 */
class LboardIME : InputMethodService(), LifecycleOwner, SavedStateRegistryOwner, ViewModelStoreOwner {

    // ===== Lifecycle =====
    private val lifecycleRegistry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = lifecycleRegistry

    // ===== SavedStateRegistry =====
    private val savedStateRegistryController = SavedStateRegistryController.create(this)
    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateRegistryController.savedStateRegistry

    // ===== ViewModelStore =====
    private val store = ViewModelStore()
    override val viewModelStore: ViewModelStore get() = store

    // ===== 拼音引擎 =====
    private val pinyinEngine = PinyinEngine()

    // ===== AI 候选词 =====
    private val aiCandidateProvider = AiCandidateProvider()

    // ===== 语音输入 =====
    private var voiceInputManager: VoiceInputManager? = null

    // ===== 用户偏好 =====
    private lateinit var userPreferences: UserPreferences
    private val serviceScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // ===== UI 状态 =====
    private val pinyinState = mutableStateOf(PinyinState())

    override fun onCreate() {
        super.onCreate()
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)

        try {
            // 初始化用户偏好并加载 API Key
            userPreferences = UserPreferences.getInstance(applicationContext)
            loadApiKey()
        } catch (e: Exception) {
            // 防止 DataStore 初始化失败导致崩溃
            android.util.Log.e("LboardIME", "Failed to init preferences", e)
        }

        try {
            // 初始化语音输入
            initVoiceInput()
        } catch (e: Exception) {
            android.util.Log.e("LboardIME", "Failed to init voice", e)
        }
    }

    override fun onCreateInputView(): View {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)

        try {
            loadApiKey()
        } catch (_: Exception) {}

        val composeView = ComposeView(this).apply {
            setViewTreeLifecycleOwner(this@LboardIME)
            setViewTreeSavedStateRegistryOwner(this@LboardIME)
            setViewTreeViewModelStoreOwner(this@LboardIME)

            setContent {
                KeyboardScreen(
                    pinyinState = pinyinState.value,
                    onKeyPress = ::handleKeyPress,
                    onBackspace = ::handleBackspace,
                    onEnter = ::handleEnter,
                    onSpacePress = ::handleSpace,
                    onCandidateSelect = ::handleCandidateSelect,
                    onToggleLanguage = ::handleToggleLanguage,
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

    override fun onStartInput(attribute: EditorInfo?, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        pinyinEngine.clearInput()
        aiCandidateProvider.cancelCurrentRequest()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    override fun onFinishInput() {
        super.onFinishInput()
        pinyinEngine.clearInput()
        aiCandidateProvider.cancelCurrentRequest()
        aiCandidateProvider.clearContext()
        voiceInputManager?.cancel()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    override fun onDestroy() {
        lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
        store.clear()
        aiCandidateProvider.dispose()
        voiceInputManager?.destroy()
        serviceScope.cancel()
        super.onDestroy()
    }

    // ========== 初始化 ==========

    private fun loadApiKey() {
        if (!::userPreferences.isInitialized) return
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
            // 语音识别完成，提交文字
            commitText(text)
            pinyinState.value = pinyinEngine.getCurrentState()
        }

        // 监听语音状态变化
        serviceScope.launch {
            voiceInputManager?.state?.collect { voiceState ->
                val currentState = pinyinState.value
                pinyinState.value = when (voiceState) {
                    is VoiceState.Idle -> currentState.copy(
                        isVoiceListening = false,
                        voicePartialResult = "",
                        voiceError = ""
                    )
                    is VoiceState.Listening -> currentState.copy(
                        isVoiceListening = true,
                        voicePartialResult = "",
                        voiceError = ""
                    )
                    is VoiceState.PartialResult -> currentState.copy(
                        isVoiceListening = true,
                        voicePartialResult = voiceState.text,
                        voiceError = ""
                    )
                    is VoiceState.Result -> currentState.copy(
                        isVoiceListening = false,
                        voicePartialResult = "",
                        voiceError = ""
                    )
                    is VoiceState.Error -> currentState.copy(
                        isVoiceListening = true, // 保持面板可见以显示错误
                        voicePartialResult = "",
                        voiceError = voiceState.message
                    )
                }
            }
        }
    }

    // ========== 按键处理 ==========

    private fun handleKeyPress(key: String) {
        if (pinyinEngine.isChineseMode && key.length == 1 && key[0].isLetter()) {
            val state = pinyinEngine.inputLetter(key[0])
            pinyinState.value = state.copy(isAiLoading = aiCandidateProvider.isAiAvailable())
            requestAiCandidates()
        } else {
            if (pinyinEngine.hasInput()) {
                val state = pinyinEngine.getCurrentState()
                if (state.candidates.isNotEmpty()) {
                    commitText(state.candidates[0])
                } else {
                    commitText(state.inputPinyin)
                }
                pinyinEngine.clearInput()
                aiCandidateProvider.cancelCurrentRequest()
            }
            commitText(key)
            pinyinState.value = pinyinEngine.getCurrentState()
        }
    }

    private fun handleBackspace() {
        if (pinyinEngine.hasInput()) {
            val state = pinyinEngine.deleteLastInput()
            pinyinState.value = state

            if (pinyinEngine.hasInput()) {
                requestAiCandidates()
            } else {
                aiCandidateProvider.cancelCurrentRequest()
            }
        } else {
            val ic = currentInputConnection ?: return
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun handleEnter() {
        if (pinyinEngine.hasInput()) {
            val pinyin = pinyinEngine.getCurrentState().inputPinyin
            commitText(pinyin)
            pinyinEngine.clearInput()
            aiCandidateProvider.cancelCurrentRequest()
            pinyinState.value = pinyinEngine.getCurrentState()
        } else {
            val ic = currentInputConnection ?: return
            ic.performEditorAction(EditorInfo.IME_ACTION_UNSPECIFIED)
        }
    }

    private fun handleSpace() {
        if (pinyinEngine.hasInput()) {
            val state = pinyinEngine.getCurrentState()
            val text = if (state.candidates.isNotEmpty()) {
                state.candidates[0]
            } else {
                state.inputPinyin
            }
            commitText(text)
            pinyinEngine.clearInput()
            aiCandidateProvider.cancelCurrentRequest()
            pinyinState.value = pinyinEngine.getCurrentState()
        } else {
            commitText(" ")
        }
    }

    private fun handleCandidateSelect(index: Int) {
        val state = pinyinEngine.getCurrentState()
        if (index < 0 || index >= state.candidates.size) return

        val selected = state.candidates[index]
        commitText(selected)
        pinyinEngine.clearInput()
        aiCandidateProvider.cancelCurrentRequest()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    private fun handleToggleLanguage() {
        pinyinEngine.toggleChineseMode()
        aiCandidateProvider.cancelCurrentRequest()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    // ========== 语音输入 ==========

    /**
     * 处理语音输入按钮
     */
    private fun handleVoiceInput() {
        val manager = voiceInputManager ?: return

        // 如果已经在监听，停止
        if (manager.isListening()) {
            manager.stopListening()
            return
        }

        // 检查权限
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            pinyinState.value = pinyinState.value.copy(
                isVoiceListening = true,
                voiceError = "请在系统设置中授予 Lboard 麦克风权限"
            )
            return
        }

        // 检查语音识别是否可用
        if (!manager.isAvailable()) {
            pinyinState.value = pinyinState.value.copy(
                isVoiceListening = true,
                voiceError = "此设备不支持语音识别"
            )
            return
        }

        // 先提交当前输入
        if (pinyinEngine.hasInput()) {
            val state = pinyinEngine.getCurrentState()
            if (state.candidates.isNotEmpty()) {
                commitText(state.candidates[0])
            }
            pinyinEngine.clearInput()
            aiCandidateProvider.cancelCurrentRequest()
        }

        // 开始语音识别
        manager.startListening(pinyinEngine.isChineseMode)
    }

    /**
     * 取消语音输入
     */
    private fun handleVoiceCancel() {
        voiceInputManager?.cancel()
        pinyinState.value = pinyinEngine.getCurrentState()
    }

    // ========== AI 候选词 ==========

    private fun requestAiCandidates() {
        val pinyin = pinyinEngine.getCurrentPinyin()
        if (pinyin.isBlank()) return

        aiCandidateProvider.getCandidates(
            pinyin = pinyin,
            onLocalResult = { },
            onAiResult = { mergedCandidates ->
                if (pinyinEngine.getCurrentPinyin() == pinyin) {
                    pinyinEngine.updateCandidates(mergedCandidates)
                    pinyinState.value = pinyinEngine.getCurrentState().copy(
                        isAiLoading = false,
                        hasAiCandidates = true
                    )
                }
            }
        )
    }

    // ========== 辅助方法 ==========

    private fun commitText(text: String) {
        val ic = currentInputConnection ?: return
        ic.commitText(text, 1)
        aiCandidateProvider.appendContext(text)
    }
}

package com.lboard.aiime.engine

/**
 * 拼音输入引擎 — 处理用户的按键输入，解析拼音并提供候选字/词
 */
class PinyinEngine {

    /** 当前输入的拼音缓冲区 */
    private val inputBuffer = StringBuilder()

    /** 当前候选词列表 */
    private var candidates: List<String> = emptyList()

    /** 是否处于中文输入模式 */
    var isChineseMode: Boolean = true
        private set

    /** 是否开启大写/Shift */
    var isShifted: Boolean = false
        private set

    /**
     * 输入一个字母
     * @return 更新后的候选词列表
     */
    fun inputLetter(letter: Char): PinyinState {
        // 中文模式下始终存入小写进行匹配
        if (isChineseMode) {
            inputBuffer.append(letter.lowercaseChar())
            candidates = PinyinDictionary.lookup(inputBuffer.toString())
        } else {
            // 英文模式直接追加（IME 会处理大小写逻辑，但 buffer 记录原始输入）
            inputBuffer.append(letter)
        }
        return getCurrentState()
    }

    /**
     * 删除最后一个输入字符
     * @return 更新后的候选词列表
     */
    fun deleteLastInput(): PinyinState {
        if (inputBuffer.isNotEmpty()) {
            inputBuffer.deleteCharAt(inputBuffer.length - 1)
            candidates = if (inputBuffer.isNotEmpty() && isChineseMode) {
                PinyinDictionary.lookup(inputBuffer.toString())
            } else {
                emptyList()
            }
        }
        return getCurrentState()
    }

    /**
     * 选择候选词
     */
    fun selectCandidate(index: Int): String? {
        if (index < 0 || index >= candidates.size) return null
        val selected = candidates[index]
        clearInput()
        return selected
    }

    /**
     * 更新候选词列表（用于 AI 候选词合并）
     */
    fun updateCandidates(newCandidates: List<String>) {
        candidates = newCandidates
    }

    /**
     * 清除当前输入
     */
    fun clearInput() {
        inputBuffer.clear()
        candidates = emptyList()
    }

    /**
     * 切换中英文模式
     */
    fun toggleChineseMode(): Boolean {
        isChineseMode = !isChineseMode
        clearInput()
        return isChineseMode
    }

    /**
     * 切换 Shift 状态
     */
    fun toggleShift(): Boolean {
        isShifted = !isShifted
        return isShifted
    }

    /**
     * 获取当前输入状态
     */
    fun getCurrentState(): PinyinState {
        return PinyinState(
            inputPinyin = inputBuffer.toString(),
            candidates = candidates,
            isChineseMode = isChineseMode,
            isShifted = isShifted
        )
    }

    /**
     * 是否有正在输入的拼音
     */
    fun hasInput(): Boolean = inputBuffer.isNotEmpty()

    /**
     * 获取当前拼音
     */
    fun getCurrentPinyin(): String = inputBuffer.toString()
}

/**
 * 拼音输入状态
 */
data class PinyinState(
    val inputPinyin: String = "",
    val candidates: List<String> = emptyList(),
    val isChineseMode: Boolean = true,
    val isShifted: Boolean = false,
    val isAiLoading: Boolean = false,
    val hasAiCandidates: Boolean = false,
    val isVoiceListening: Boolean = false,
    val voicePartialResult: String = "",
    val voiceError: String = ""
)

package com.lboard.aiime.ime.keyboard

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.material3.ripple
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lboard.aiime.engine.PinyinState

// ========== 配色方案 ==========
private object KeyboardColors {
    val keyboardBackground = Color(0xFF1A1B1E)
    val keyBackground = Color(0xFF2D2E33)
    val keyBackgroundPressed = Color(0xFF3D3E43)
    val specialKeyBackground = Color(0xFF3A3B40)
    val spaceKeyBackground = Color(0xFF2D2E33)

    val keyText = Color(0xFFE8E8E8)
    val specialKeyText = Color(0xFFB0B0B0)
    val candidateText = Color(0xFFFFFFFF)
    val candidateTextSecondary = Color(0xFFB0B0B0)
    val pinyinText = Color(0xFF8AB4F8)

    val candidateBarBackground = Color(0xFF222327)
    val candidateDivider = Color(0xFF3A3B40)

    val accentColor = Color(0xFF8AB4F8)
    val accentGradientStart = Color(0xFF8AB4F8)
    val accentGradientEnd = Color(0xFFAECBFA)

    val chineseModeColor = Color(0xFF8AB4F8)
    val englishModeColor = Color(0xFFB0B0B0)

    // AI 候选词颜色
    val aiAccent = Color(0xFFBB86FC)
    val aiLoadingColor = Color(0xFF8AB4F8)

    // 语音输入颜色
    val voiceActiveColor = Color(0xFFEF5350)
    val voicePanelBackground = Color(0xFF1E1F24)
    val voicePartialText = Color(0xFFE0E0E0)
}

/**
 * 键盘主界面
 */
@Composable
fun KeyboardScreen(
    pinyinState: PinyinState,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpacePress: () -> Unit,
    onCandidateSelect: (Int) -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleShift: () -> Unit, // 新增：Shift 切换
    onVoiceInput: () -> Unit,
    onVoiceCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KeyboardColors.keyboardBackground)
            .navigationBarsPadding()
    ) {
        // 语音输入面板
        if (pinyinState.isVoiceListening) {
            VoiceInputPanel(
                partialResult = pinyinState.voicePartialResult,
                error = pinyinState.voiceError,
                onCancel = onVoiceCancel
            )
        } else {
            // 候选词栏
            if (pinyinState.isChineseMode) {
                CandidateBar(
                    pinyin = pinyinState.inputPinyin,
                    candidates = pinyinState.candidates,
                    isAiLoading = pinyinState.isAiLoading,
                    hasAiCandidates = pinyinState.hasAiCandidates,
                    onCandidateSelect = onCandidateSelect
                )
            }

            // 键盘主体
            KeyboardBody(
                isChineseMode = pinyinState.isChineseMode,
                isShifted = pinyinState.isShifted,
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onEnter = onEnter,
                onSpacePress = onSpacePress,
                onToggleLanguage = onToggleLanguage,
                onToggleShift = onToggleShift,
                onVoiceInput = onVoiceInput
            )
            
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

// ========== 语音输入面板 (省略部分逻辑以节省篇幅) ==========
@Composable
private fun VoiceInputPanel(
    partialResult: String,
    error: String,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(260.dp)
            .background(KeyboardColors.voicePanelBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(64.dp)
                .clip(CircleShape)
                .background(KeyboardColors.voiceActiveColor.copy(alpha = 0.9f)),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "🎤", fontSize = 28.sp)
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = if (error.isNotBlank()) error else if (partialResult.isNotBlank()) partialResult else "正在倾听...",
            color = if (error.isNotBlank()) KeyboardColors.voiceActiveColor else KeyboardColors.voicePartialText,
            fontSize = 18.sp,
            modifier = Modifier.padding(horizontal = 32.dp)
        )

        Spacer(modifier = Modifier.height(24.dp))

        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(KeyboardColors.specialKeyBackground)
                .clickable { onCancel() }
                .padding(horizontal = 28.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(text = "停止", color = KeyboardColors.specialKeyText, fontSize = 14.sp)
        }
    }
}

// ========== 候选词栏 ==========
@Composable
private fun CandidateBar(
    pinyin: String,
    candidates: List<String>,
    isAiLoading: Boolean,
    hasAiCandidates: Boolean,
    onCandidateSelect: (Int) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(KeyboardColors.candidateBarBackground)
    ) {
        if (pinyin.isNotEmpty()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, top = 6.dp, bottom = 2.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(text = pinyin, color = KeyboardColors.pinyinText, fontSize = 14.sp)
                Spacer(modifier = Modifier.weight(1f))
                if (isAiLoading) Text(text = "✨ AI...", color = KeyboardColors.aiLoadingColor, fontSize = 11.sp)
                else if (hasAiCandidates) Text(text = "✨ AI", color = KeyboardColors.aiAccent, fontSize = 11.sp)
            }
        }

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .height(48.dp),
            contentPadding = PaddingValues(horizontal = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            itemsIndexed(candidates) { index, candidate ->
                CandidateItem(text = candidate, index = index, onClick = { onCandidateSelect(index) })
            }
        }

        Box(modifier = Modifier.fillMaxWidth().height(0.5.dp).background(KeyboardColors.candidateDivider))
    }
}

@Composable
private fun CandidateItem(text: String, index: Int, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (index == 0) KeyboardColors.candidateText else KeyboardColors.candidateTextSecondary,
            fontSize = if (index == 0) 20.sp else 18.sp,
            fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
        )
    }
}

// ========== 键盘主体 ==========
@Composable
private fun KeyboardBody(
    isChineseMode: Boolean,
    isShifted: Boolean,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpacePress: () -> Unit,
    onToggleLanguage: () -> Unit,
    onToggleShift: () -> Unit,
    onVoiceInput: () -> Unit
) {
    val rows = KeyboardLayout.qwertyRows

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        // 第一行
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            rows[0].forEach { key ->
                LetterKey(text = if (isShifted) key.display else key.value, onClick = { onKeyPress(key.value) }, modifier = Modifier.weight(1f))
            }
        }

        // 第二行
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Spacer(modifier = Modifier.weight(0.5f))
            rows[1].forEach { key ->
                LetterKey(text = if (isShifted) key.display else key.value, onClick = { onKeyPress(key.value) }, modifier = Modifier.weight(1f))
            }
            Spacer(modifier = Modifier.weight(0.5f))
        }

        // 第三行
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            SpecialKey(
                text = "⇧",
                active = isShifted,
                onClick = onToggleShift,
                modifier = Modifier.weight(1.5f)
            )
            rows[2].forEach { key ->
                LetterKey(text = if (isShifted) key.display else key.value, onClick = { onKeyPress(key.value) }, modifier = Modifier.weight(1f))
            }
            SpecialKey(text = "⌫", onClick = onBackspace, modifier = Modifier.weight(1.5f))
        }

        // 第四行
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            LanguageToggleKey(isChineseMode = isChineseMode, onClick = onToggleLanguage, modifier = Modifier.weight(1.2f))
            VoiceKey(onClick = onVoiceInput, modifier = Modifier.weight(1.3f))
            SpecialKey(text = if (isChineseMode) "，" else ",", onClick = { onKeyPress(if (isChineseMode) "，" else ",") }, modifier = Modifier.weight(1f))
            SpaceKey(isChineseMode = isChineseMode, onClick = onSpacePress, modifier = Modifier.weight(3f))
            SpecialKey(text = if (isChineseMode) "。" else ".", onClick = { onKeyPress(if (isChineseMode) "。" else ".") }, modifier = Modifier.weight(1f))
            EnterKey(onClick = onEnter, modifier = Modifier.weight(1.5f))
        }
    }
}

@Composable
private fun LetterKey(text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(KeyboardColors.keyBackground)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = KeyboardColors.keyText, fontSize = 20.sp)
    }
}

@Composable
private fun SpecialKey(text: String, active: Boolean = false, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(if (active) KeyboardColors.accentColor else KeyboardColors.specialKeyBackground)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = text, color = if (active) Color.Black else KeyboardColors.specialKeyText, fontSize = 18.sp)
    }
}

@Composable
private fun LanguageToggleKey(isChineseMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KeyboardColors.specialKeyBackground)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = if (isChineseMode) "中" else "EN", color = if (isChineseMode) KeyboardColors.chineseModeColor else KeyboardColors.englishModeColor, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun VoiceKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KeyboardColors.specialKeyBackground)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "🎤", fontSize = 24.sp)
    }
}

@Composable
private fun SpaceKey(isChineseMode: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .shadow(1.dp, RoundedCornerShape(8.dp))
            .clip(RoundedCornerShape(8.dp))
            .background(KeyboardColors.keyBackground)
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = if (isChineseMode) "Lboard" else "space", color = KeyboardColors.specialKeyText, fontSize = 14.sp)
    }
}

@Composable
private fun EnterKey(onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .height(50.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Brush.horizontalGradient(listOf(KeyboardColors.accentGradientStart, KeyboardColors.accentGradientEnd)))
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(text = "⏎", color = Color(0xFF1A1B1E), fontSize = 20.sp, fontWeight = FontWeight.Bold)
    }
}

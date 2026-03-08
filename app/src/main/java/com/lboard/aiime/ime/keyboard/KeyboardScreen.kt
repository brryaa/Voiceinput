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
    onVoiceInput: () -> Unit,
    onVoiceCancel: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(KeyboardColors.keyboardBackground)
    ) {
        // 语音输入面板（覆盖键盘）
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
                onKeyPress = onKeyPress,
                onBackspace = onBackspace,
                onEnter = onEnter,
                onSpacePress = onSpacePress,
                onToggleLanguage = onToggleLanguage,
                onVoiceInput = onVoiceInput
            )
        }
    }
}

// ========== 语音输入面板 ==========

@Composable
private fun VoiceInputPanel(
    partialResult: String,
    error: String,
    onCancel: () -> Unit
) {
    // 脉冲动画
    val infiniteTransition = rememberInfiniteTransition(label = "voice_pulse")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.3f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_pulse_scale"
    )
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.6f,
        targetValue = 0.15f,
        animationSpec = infiniteRepeatable(
            animation = tween(800, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "voice_pulse_alpha"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .height(220.dp)
            .background(KeyboardColors.voicePanelBackground),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        // 脉冲动画背景 + 麦克风图标
        Box(contentAlignment = Alignment.Center) {
            // 外圈脉冲
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .scale(pulseScale)
                    .clip(CircleShape)
                    .background(KeyboardColors.voiceActiveColor.copy(alpha = pulseAlpha))
            )
            // 内圈
            Box(
                modifier = Modifier
                    .size(64.dp)
                    .clip(CircleShape)
                    .background(KeyboardColors.voiceActiveColor.copy(alpha = 0.9f)),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "🎤",
                    fontSize = 28.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // 识别中文字 / 错误信息
        if (error.isNotBlank()) {
            Text(
                text = error,
                color = KeyboardColors.voiceActiveColor,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
        } else if (partialResult.isNotBlank()) {
            Text(
                text = partialResult,
                color = KeyboardColors.voicePartialText,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        } else {
            Text(
                text = "请说话...",
                color = KeyboardColors.specialKeyText,
                fontSize = 15.sp,
                fontWeight = FontWeight.Normal
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // 取消按钮
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(20.dp))
                .background(KeyboardColors.specialKeyBackground)
                .clickable { onCancel() }
                .padding(horizontal = 28.dp, vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "取消",
                color = KeyboardColors.specialKeyText,
                fontSize = 14.sp,
                fontWeight = FontWeight.Medium
            )
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
                Text(
                    text = pinyin,
                    color = KeyboardColors.pinyinText,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Medium
                )

                Spacer(modifier = Modifier.weight(1f))

                AnimatedVisibility(
                    visible = isAiLoading || hasAiCandidates,
                    enter = fadeIn() + slideInHorizontally { it },
                    exit = fadeOut()
                ) {
                    if (isAiLoading) {
                        AiLoadingIndicator()
                    } else if (hasAiCandidates) {
                        Text(
                            text = "✨ AI",
                            color = KeyboardColors.aiAccent,
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        if (candidates.isNotEmpty()) {
            LazyRow(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(42.dp),
                contentPadding = PaddingValues(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                itemsIndexed(candidates) { index, candidate ->
                    CandidateItem(
                        text = candidate,
                        index = index,
                        onClick = { onCandidateSelect(index) }
                    )
                }
            }
        } else if (pinyin.isEmpty()) {
            Spacer(modifier = Modifier.height(42.dp))
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(0.5.dp)
                .background(KeyboardColors.candidateDivider)
        )
    }
}

@Composable
private fun AiLoadingIndicator() {
    val infiniteTransition = rememberInfiniteTransition(label = "ai_loading")
    val alpha by infiniteTransition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "ai_loading_alpha"
    )

    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(text = "✨", fontSize = 11.sp, modifier = Modifier.padding(end = 2.dp))
        Text(
            text = "AI",
            color = KeyboardColors.aiLoadingColor.copy(alpha = alpha),
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold
        )
    }
}

@Composable
private fun CandidateItem(
    text: String,
    index: Int,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }

    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = KeyboardColors.accentColor)
            ) { onClick() }
            .padding(horizontal = 14.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = text,
                color = if (index == 0) KeyboardColors.candidateText else KeyboardColors.candidateTextSecondary,
                fontSize = if (index == 0) 20.sp else 18.sp,
                fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal
            )
            if (index < 5) {
                Text(
                    text = "${index + 1}",
                    color = KeyboardColors.candidateDivider,
                    fontSize = 10.sp,
                    modifier = Modifier.padding(start = 2.dp, bottom = 2.dp)
                )
            }
        }
    }
}

// ========== 键盘主体 ==========

@Composable
private fun KeyboardBody(
    isChineseMode: Boolean,
    onKeyPress: (String) -> Unit,
    onBackspace: () -> Unit,
    onEnter: () -> Unit,
    onSpacePress: () -> Unit,
    onToggleLanguage: () -> Unit,
    onVoiceInput: () -> Unit
) {
    val rows = KeyboardLayout.qwertyRows

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 3.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        // 第一行 Q-P
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            rows[0].forEach { key ->
                LetterKey(
                    keyData = key,
                    onClick = { onKeyPress(key.value) },
                    modifier = Modifier.weight(1f)
                )
            }
        }

        // 第二行 A-L
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Spacer(modifier = Modifier.weight(0.5f))
            rows[1].forEach { key ->
                LetterKey(
                    keyData = key,
                    onClick = { onKeyPress(key.value) },
                    modifier = Modifier.weight(1f)
                )
            }
            Spacer(modifier = Modifier.weight(0.5f))
        }

        // 第三行 Z-M + 退格
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            SpecialKey(
                text = "⇧",
                onClick = { /* TODO: Shift */ },
                modifier = Modifier.weight(1.5f)
            )

            rows[2].forEach { key ->
                LetterKey(
                    keyData = key,
                    onClick = { onKeyPress(key.value) },
                    modifier = Modifier.weight(1f)
                )
            }

            SpecialKey(
                text = "⌫",
                onClick = onBackspace,
                modifier = Modifier.weight(1.5f)
            )
        }

        // 第四行 — 功能键行
        // 布局: [中/1.2] [🎤/1.3] [，/1] [___Lboard___/3] [。/1] [⏎/1.5]
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            LanguageToggleKey(
                isChineseMode = isChineseMode,
                onClick = onToggleLanguage,
                modifier = Modifier.weight(1.2f)
            )

            // 🎤 语音键 — 底部靠左第二位，大图标
            VoiceKey(
                onClick = onVoiceInput,
                modifier = Modifier.weight(1.3f)
            )

            SpecialKey(
                text = if (isChineseMode) "，" else ",",
                onClick = { onKeyPress(if (isChineseMode) "，" else ",") },
                modifier = Modifier.weight(1f)
            )

            SpaceKey(
                isChineseMode = isChineseMode,
                onClick = onSpacePress,
                modifier = Modifier.weight(3f)
            )

            SpecialKey(
                text = if (isChineseMode) "。" else ".",
                onClick = { onKeyPress(if (isChineseMode) "。" else ".") },
                modifier = Modifier.weight(1f)
            )

            EnterKey(
                onClick = onEnter,
                modifier = Modifier.weight(1.5f)
            )
        }
    }
}

// ========== 按键组件 ==========

@Composable
private fun LetterKey(
    keyData: KeyData,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) KeyboardColors.keyBackgroundPressed else KeyboardColors.keyBackground,
        animationSpec = tween(durationMillis = 80),
        label = "key_bg"
    )

    Box(
        modifier = modifier
            .height(46.dp)
            .shadow(
                elevation = if (isPressed) 0.dp else 1.dp,
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = keyData.display,
            color = KeyboardColors.keyText,
            fontSize = 20.sp,
            fontWeight = FontWeight.Normal,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SpecialKey(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) KeyboardColors.keyBackgroundPressed else KeyboardColors.specialKeyBackground,
        animationSpec = tween(durationMillis = 80),
        label = "special_key_bg"
    )

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = KeyboardColors.specialKeyText,
            fontSize = 18.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun LanguageToggleKey(
    isChineseMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }

    val textColor by animateColorAsState(
        targetValue = if (isChineseMode) KeyboardColors.chineseModeColor else KeyboardColors.englishModeColor,
        animationSpec = tween(durationMillis = 200),
        label = "lang_color"
    )

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(KeyboardColors.specialKeyBackground)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = KeyboardColors.accentColor)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isChineseMode) "中" else "EN",
            color = textColor,
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

/**
 * 语音按键 — 底部靠左第二位，大图标
 */
@Composable
private fun VoiceKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) KeyboardColors.voiceActiveColor.copy(alpha = 0.3f)
            else KeyboardColors.specialKeyBackground,
        animationSpec = tween(durationMillis = 80),
        label = "voice_key_bg"
    )

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = ripple(color = KeyboardColors.voiceActiveColor)
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "🎤",
            fontSize = 24.sp,
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun SpaceKey(
    isChineseMode: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor by animateColorAsState(
        targetValue = if (isPressed) KeyboardColors.keyBackgroundPressed else KeyboardColors.spaceKeyBackground,
        animationSpec = tween(durationMillis = 80),
        label = "space_bg"
    )

    Box(
        modifier = modifier
            .height(46.dp)
            .shadow(
                elevation = if (isPressed) 0.dp else 1.dp,
                shape = RoundedCornerShape(8.dp)
            )
            .clip(RoundedCornerShape(8.dp))
            .background(bgColor)
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = if (isChineseMode) "Lboard" else "space",
            color = KeyboardColors.specialKeyText,
            fontSize = 14.sp,
            fontWeight = FontWeight.Medium,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

@Composable
private fun EnterKey(
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .height(46.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(
                brush = Brush.horizontalGradient(
                    colors = if (isPressed) {
                        listOf(
                            KeyboardColors.accentGradientStart.copy(alpha = 0.6f),
                            KeyboardColors.accentGradientEnd.copy(alpha = 0.6f)
                        )
                    } else {
                        listOf(
                            KeyboardColors.accentGradientStart,
                            KeyboardColors.accentGradientEnd
                        )
                    }
                )
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null
            ) { onClick() },
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "⏎",
            color = Color(0xFF1A1B1E),
            fontSize = 20.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
    }
}

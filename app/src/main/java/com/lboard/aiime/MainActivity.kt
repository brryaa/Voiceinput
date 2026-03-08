package com.lboard.aiime

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lboard.aiime.auth.AuthState
import com.lboard.aiime.auth.GeminiAuthManager
import com.lboard.aiime.data.UserPreferences
import com.lboard.aiime.ui.theme.LboardTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var authManager: GeminiAuthManager
    private lateinit var userPreferences: UserPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        authManager = GeminiAuthManager(applicationContext)
        userPreferences = UserPreferences.getInstance(applicationContext)

        enableEdgeToEdge()
        setContent {
            LboardTheme {
                SetupScreen(
                    authManager = authManager,
                    userPreferences = userPreferences
                )
            }
        }
    }
}

// ========== 配色 ==========
private object SetupColors {
    val background = Color(0xFF0F1014)
    val cardBackground = Color(0xFF1A1B20)
    val accentBlue = Color(0xFF8AB4F8)
    val accentPurple = Color(0xFFBB86FC)
    val textPrimary = Color(0xFFE8E8E8)
    val textSecondary = Color(0xFF9E9E9E)
    val successGreen = Color(0xFF81C784)
    val warningOrange = Color(0xFFFFB74D)
    val gradientStart = Color(0xFF8AB4F8)
    val gradientEnd = Color(0xFFBB86FC)
    val errorRed = Color(0xFFEF5350)
    val inputFieldBg = Color(0xFF2A2B30)
}

@Composable
fun SetupScreen(
    authManager: GeminiAuthManager,
    userPreferences: UserPreferences
) {
    val context = LocalContext.current
    val scrollState = rememberScrollState()
    val coroutineScope = rememberCoroutineScope()

    var isImeEnabled by remember { mutableStateOf(false) }
    var isImeSelected by remember { mutableStateOf(false) }

    val authState by authManager.authState.collectAsState()

    // API Key 状态
    val savedApiKey by userPreferences.geminiApiKeyFlow.collectAsState(initial = "")
    var apiKeyInput by remember { mutableStateOf("") }
    var showApiKey by remember { mutableStateOf(false) }
    var apiKeySaved by remember { mutableStateOf(false) }

    // 同步 apiKeyInput 与 savedApiKey
    LaunchedEffect(savedApiKey) {
        apiKeyInput = savedApiKey
        apiKeySaved = savedApiKey.isNotBlank()
    }

    LaunchedEffect(Unit) {
        while (true) {
            isImeEnabled = checkImeEnabled(context)
            isImeSelected = checkImeSelected(context)
            kotlinx.coroutines.delay(1000)
        }
    }

    Scaffold(
        containerColor = SetupColors.background
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(scrollState)
                .padding(horizontal = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Spacer(modifier = Modifier.height(48.dp))

            LogoSection()

            Spacer(modifier = Modifier.height(40.dp))

            // Step 1: 启用输入法
            SetupStepCard(
                stepNumber = 1,
                title = "启用 Lboard 输入法",
                description = "在系统设置中开启 Lboard 键盘",
                isCompleted = isImeEnabled,
                buttonText = "前往设置",
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_INPUT_METHOD_SETTINGS))
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 2: 切换输入法
            SetupStepCard(
                stepNumber = 2,
                title = "切换到 Lboard",
                description = "选择 Lboard 作为当前输入法",
                isCompleted = isImeSelected,
                buttonText = "切换输入法",
                onClick = {
                    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
                    imm.showInputMethodPicker()
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 3: Google 登录
            GeminiLoginCard(
                authState = authState,
                onSignIn = {
                    coroutineScope.launch { authManager.signIn(context) }
                },
                onSignOut = {
                    coroutineScope.launch { authManager.signOut() }
                }
            )

            Spacer(modifier = Modifier.height(16.dp))

            // Step 4: API Key 设置
            ApiKeyCard(
                apiKeyInput = apiKeyInput,
                onApiKeyChange = {
                    apiKeyInput = it
                    apiKeySaved = false
                },
                showApiKey = showApiKey,
                onToggleVisibility = { showApiKey = !showApiKey },
                isSaved = apiKeySaved,
                onSave = {
                    coroutineScope.launch {
                        userPreferences.setGeminiApiKey(apiKeyInput)
                        apiKeySaved = true
                    }
                },
                onClear = {
                    coroutineScope.launch {
                        apiKeyInput = ""
                        userPreferences.setGeminiApiKey("")
                        apiKeySaved = false
                    }
                }
            )

            Spacer(modifier = Modifier.height(32.dp))

            FeaturePreviewSection()

            Spacer(modifier = Modifier.height(40.dp))
        }
    }
}

// ========== API Key 设置卡片 ==========

@Composable
private fun ApiKeyCard(
    apiKeyInput: String,
    onApiKeyChange: (String) -> Unit,
    showApiKey: Boolean,
    onToggleVisibility: () -> Unit,
    isSaved: Boolean,
    onSave: () -> Unit,
    onClear: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SetupColors.cardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            if (isSaved && apiKeyInput.isNotBlank())
                                SetupColors.successGreen.copy(alpha = 0.2f)
                            else SetupColors.warningOrange.copy(alpha = 0.15f)
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = if (isSaved && apiKeyInput.isNotBlank()) "✓" else "4",
                        color = if (isSaved && apiKeyInput.isNotBlank())
                            SetupColors.successGreen else SetupColors.warningOrange,
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column {
                    Text(
                        text = "Gemini API Key",
                        color = SetupColors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isSaved && apiKeyInput.isNotBlank())
                            "已配置，AI 智能候选词已启用"
                        else "输入 API Key 以启用 AI 候选词功能",
                        color = if (isSaved && apiKeyInput.isNotBlank())
                            SetupColors.successGreen else SetupColors.textSecondary,
                        fontSize = 13.sp
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            // API Key 输入框
            OutlinedTextField(
                value = apiKeyInput,
                onValueChange = onApiKeyChange,
                modifier = Modifier.fillMaxWidth(),
                placeholder = {
                    Text(
                        text = "粘贴你的 Gemini API Key",
                        color = SetupColors.textSecondary.copy(alpha = 0.5f),
                        fontSize = 14.sp
                    )
                },
                visualTransformation = if (showApiKey) VisualTransformation.None
                    else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedTextColor = SetupColors.textPrimary,
                    unfocusedTextColor = SetupColors.textPrimary,
                    focusedBorderColor = SetupColors.accentBlue,
                    unfocusedBorderColor = SetupColors.inputFieldBg,
                    focusedContainerColor = SetupColors.inputFieldBg,
                    unfocusedContainerColor = SetupColors.inputFieldBg,
                    cursorColor = SetupColors.accentBlue
                ),
                shape = RoundedCornerShape(12.dp),
                trailingIcon = {
                    TextButton(onClick = onToggleVisibility) {
                        Text(
                            text = if (showApiKey) "隐藏" else "显示",
                            color = SetupColors.accentBlue,
                            fontSize = 12.sp
                        )
                    }
                }
            )

            Spacer(modifier = Modifier.height(12.dp))

            // 操作按钮
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                // 保存按钮
                Button(
                    onClick = onSave,
                    enabled = apiKeyInput.isNotBlank() && !isSaved,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SetupColors.accentBlue,
                        disabledContainerColor = SetupColors.accentBlue.copy(alpha = 0.3f)
                    )
                ) {
                    Text(
                        text = if (isSaved) "✓ 已保存" else "保存",
                        fontSize = 14.sp,
                        fontWeight = FontWeight.Medium
                    )
                }

                // 清除按钮
                if (apiKeyInput.isNotBlank()) {
                    OutlinedButton(
                        onClick = onClear,
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.outlinedButtonColors(
                            contentColor = SetupColors.textSecondary
                        )
                    ) {
                        Text(text = "清除", fontSize = 14.sp)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // 获取链接
            Text(
                text = "📎 从 aistudio.google.com/apikey 获取",
                color = SetupColors.textSecondary,
                fontSize = 12.sp
            )
        }
    }
}

// ========== 其他 UI 组件 ==========

@Composable
private fun LogoSection() {
    val infiniteTransition = rememberInfiniteTransition(label = "logo_anim")
    val scale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = EaseInOutSine),
            repeatMode = RepeatMode.Reverse
        ),
        label = "logo_scale"
    )

    Box(
        modifier = Modifier
            .size(88.dp)
            .scale(scale)
            .clip(CircleShape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(SetupColors.gradientStart, SetupColors.gradientEnd)
                )
            ),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "L",
            color = Color.White,
            fontSize = 40.sp,
            fontWeight = FontWeight.Bold
        )
    }

    Spacer(modifier = Modifier.height(20.dp))

    Text(
        text = "Lboard",
        color = SetupColors.textPrimary,
        fontSize = 32.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "AI 中文输入法 · Powered by Gemini",
        color = SetupColors.textSecondary,
        fontSize = 14.sp,
        fontWeight = FontWeight.Normal
    )
}

@Composable
private fun GeminiLoginCard(
    authState: AuthState,
    onSignIn: () -> Unit,
    onSignOut: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SetupColors.cardBackground)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(
                            when (authState) {
                                is AuthState.LoggedIn -> SetupColors.successGreen.copy(alpha = 0.2f)
                                is AuthState.Error -> SetupColors.errorRed.copy(alpha = 0.2f)
                                else -> SetupColors.accentPurple.copy(alpha = 0.15f)
                            }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = when (authState) {
                            is AuthState.LoggedIn -> "✓"
                            is AuthState.Error -> "!"
                            else -> "3"
                        },
                        color = when (authState) {
                            is AuthState.LoggedIn -> SetupColors.successGreen
                            is AuthState.Error -> SetupColors.errorRed
                            else -> SetupColors.accentPurple
                        },
                        fontSize = 18.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "登录 Gemini AI",
                        color = SetupColors.textPrimary,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = when (authState) {
                            is AuthState.LoggedIn -> "已登录：${authState.displayName ?: authState.email}"
                            is AuthState.Loading -> "正在登录..."
                            is AuthState.Error -> authState.message
                            else -> "使用 Google 账号授权"
                        },
                        color = when (authState) {
                            is AuthState.Error -> SetupColors.errorRed
                            else -> SetupColors.textSecondary
                        },
                        fontSize = 13.sp,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            when (authState) {
                is AuthState.LoggedIn -> {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(36.dp)
                                    .clip(CircleShape)
                                    .background(
                                        Brush.linearGradient(
                                            listOf(SetupColors.gradientStart, SetupColors.gradientEnd)
                                        )
                                    ),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = (authState.displayName?.firstOrNull() ?: "U").toString(),
                                    color = Color.White,
                                    fontSize = 16.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = authState.displayName ?: "User",
                                    color = SetupColors.textPrimary,
                                    fontSize = 14.sp,
                                    fontWeight = FontWeight.Medium
                                )
                                authState.email?.let {
                                    Text(text = it, color = SetupColors.textSecondary, fontSize = 12.sp)
                                }
                            }
                        }

                        OutlinedButton(
                            onClick = onSignOut,
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.outlinedButtonColors(contentColor = SetupColors.textSecondary)
                        ) {
                            Text(text = "登出", fontSize = 13.sp)
                        }
                    }
                }

                is AuthState.Loading -> {
                    Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(32.dp),
                            color = SetupColors.accentPurple,
                            strokeWidth = 3.dp
                        )
                    }
                }

                else -> {
                    Button(
                        onClick = onSignIn,
                        modifier = Modifier.fillMaxWidth(),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
                        contentPadding = PaddingValues()
                    ) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(
                                    brush = Brush.horizontalGradient(
                                        listOf(SetupColors.gradientStart, SetupColors.gradientEnd)
                                    ),
                                    shape = RoundedCornerShape(12.dp)
                                )
                                .padding(vertical = 14.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "🔐  Sign in with Google",
                                color = Color.White,
                                fontSize = 15.sp,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                    }

                    if (authState is AuthState.Error) {
                        Spacer(modifier = Modifier.height(8.dp))
                        TextButton(onClick = onSignIn) {
                            Text(text = "重试", color = SetupColors.accentBlue, fontSize = 13.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SetupStepCard(
    stepNumber: Int,
    title: String,
    description: String,
    isCompleted: Boolean,
    buttonText: String,
    enabled: Boolean = true,
    onClick: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = SetupColors.cardBackground)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(
                        if (isCompleted) SetupColors.successGreen.copy(alpha = 0.2f)
                        else SetupColors.accentBlue.copy(alpha = 0.15f)
                    ),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = if (isCompleted) "✓" else "$stepNumber",
                    color = if (isCompleted) SetupColors.successGreen else SetupColors.accentBlue,
                    fontSize = if (isCompleted) 20.sp else 18.sp,
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(text = title, color = SetupColors.textPrimary, fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(4.dp))
                Text(text = description, color = SetupColors.textSecondary, fontSize = 13.sp)
            }

            Spacer(modifier = Modifier.width(12.dp))

            if (!isCompleted) {
                Button(
                    onClick = onClick,
                    enabled = enabled,
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = SetupColors.accentBlue,
                        disabledContainerColor = SetupColors.accentBlue.copy(alpha = 0.3f)
                    ),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                ) {
                    Text(text = buttonText, fontSize = 13.sp, fontWeight = FontWeight.Medium)
                }
            }
        }
    }
}

@Composable
private fun FeaturePreviewSection() {
    Text(
        text = "功能亮点",
        color = SetupColors.textPrimary,
        fontSize = 20.sp,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(16.dp))

    val features = listOf(
        Triple("🧠", "AI 智能输入", "Gemini 驱动，超越传统拼音输入"),
        Triple("🎤", "语音输入", "高精度中文语音识别"),
        Triple("⚡", "极速响应", "离线拼音 + 在线 AI 双引擎"),
        Triple("🌙", "精美暗色主题", "舒适护眼的键盘设计")
    )

    features.forEach { (emoji, title, desc) ->
        FeatureCard(emoji = emoji, title = title, description = desc)
        Spacer(modifier = Modifier.height(12.dp))
    }
}

@Composable
private fun FeatureCard(emoji: String, title: String, description: String) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = SetupColors.cardBackground.copy(alpha = 0.7f))
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(text = emoji, fontSize = 28.sp, modifier = Modifier.padding(end = 16.dp))
            Column {
                Text(text = title, color = SetupColors.textPrimary, fontSize = 15.sp, fontWeight = FontWeight.SemiBold)
                Spacer(modifier = Modifier.height(2.dp))
                Text(text = description, color = SetupColors.textSecondary, fontSize = 13.sp)
            }
        }
    }
}

// ========== 工具函数 ==========

private fun checkImeEnabled(context: Context): Boolean {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
    return imm.enabledInputMethodList.any { it.packageName == context.packageName }
}

private fun checkImeSelected(context: Context): Boolean {
    val currentIme = Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
    return currentIme?.contains(context.packageName) == true
}
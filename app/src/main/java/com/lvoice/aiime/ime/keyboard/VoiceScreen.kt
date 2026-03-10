package com.lvoice.aiime.ime.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lvoice.aiime.R
import com.lvoice.aiime.voice.VoiceState

@Composable
fun VoiceScreen(
    voiceState: VoiceState,
    onMicClick: () -> Unit,
    onCloseClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(280.dp)
            .background(Color(0xFFEBEFF2), RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp)),
        contentAlignment = Alignment.Center
    ) {
        // Close button at top right
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp),
            contentAlignment = Alignment.TopEnd
        ) {
            Icon(
                painter = painterResource(id = android.R.drawable.ic_menu_close_clear_cancel),
                contentDescription = "Close",
                tint = Color.Gray,
                modifier = Modifier
                    .size(28.dp)
                    .clickable { onCloseClick() }
            )
        }

        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            val isListening = voiceState is VoiceState.Listening || voiceState is VoiceState.PartialResult
            
            Box(
                modifier = Modifier
                    .size(80.dp)
                    .clip(CircleShape)
                    .background(if (isListening) Color(0xFFCDEAE1) else Color(0xFFE0E0E0))
                    .clickable { 
                        android.util.Log.d("VoiceScreen", "Mic clicked, state: $voiceState")
                        onMicClick() 
                    },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    painter = painterResource(id = android.R.drawable.ic_btn_speak_now),
                    contentDescription = "Mic",
                    tint = if (isListening) Color(0xFF004D40) else Color.DarkGray,
                    modifier = Modifier.size(40.dp)
                )
            }
            
            Spacer(modifier = Modifier.height(16.dp))
            
            val textToDisplay = when (voiceState) {
                is VoiceState.Idle -> "准备就绪 (点击开始)"
                is VoiceState.Listening -> "正在聆听..."
                is VoiceState.PartialResult -> voiceState.text
                is VoiceState.Result -> voiceState.text
                is VoiceState.Error -> voiceState.message
            }
            
            Text(
                text = textToDisplay,
                fontSize = 18.sp,
                color = if (voiceState is VoiceState.Error) Color.Red else Color.DarkGray,
                modifier = Modifier.padding(horizontal = 24.dp)
            )
        }
    }
}

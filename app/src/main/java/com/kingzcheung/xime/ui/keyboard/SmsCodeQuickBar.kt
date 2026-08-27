package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * 候选栏上方的短信验证码快捷插入条。
 *
 * 收到验证码短信后显示：标签 + 验证码 + 「插入」按钮 + 关闭按钮。
 * 点击「插入」走 IME 既有 commitText 路径上屏当前输入框。
 */
@Composable
fun SmsCodeQuickBar(
    code: String,
    textColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    onInsert: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(backgroundColor)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "短信验证码",
                fontSize = 12.sp,
                color = textColor.copy(alpha = 0.6f),
            )
            Text(
                text = "  $code",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = accentColor,
            )
            Box(
                modifier = Modifier
                    .padding(start = 12.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(accentColor.copy(alpha = 0.15f))
                    .clickable { onInsert() }
                    .padding(horizontal = 12.dp, vertical = 4.dp),
            ) {
                Text(
                    text = "插入",
                    fontSize = 13.sp,
                    fontWeight = FontWeight.Medium,
                    color = accentColor,
                )
            }
        }
        Box(
            modifier = Modifier
                .size(24.dp)
                .clip(RoundedCornerShape(6.dp))
                .clickable { onDismiss() },
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                imageVector = Icons.Default.Close,
                contentDescription = "关闭验证码提示",
                tint = textColor.copy(alpha = 0.5f),
                modifier = Modifier.size(16.dp),
            )
        }
    }
}

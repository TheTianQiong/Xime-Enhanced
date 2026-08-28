package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
 * 双拼分解提示条：候选栏上方的「先声母后韵母」实时分解。
 *
 * 例如小鹤双拼键入 `vc` 时显示：`zh + ao`，与候选栏的拼音回显互补。
 *
 * @param parts 每个音节的分解（如 `["zh + ao"]`）
 */
@Composable
fun ShuangpinHintBar(
    parts: List<String>,
    textColor: Color,
    accentColor: Color,
    backgroundColor: Color,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(34.dp)
            .background(backgroundColor)
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Start,
    ) {
        Box(
            modifier = Modifier
                .clip(RoundedCornerShape(6.dp))
                .background(accentColor.copy(alpha = 0.15f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
        ) {
            Text(
                text = "双拼",
                fontSize = 11.sp,
                color = accentColor,
            )
        }
        Text(
            text = "  " + parts.joinToString("　"),
            fontSize = 15.sp,
            fontWeight = FontWeight.Medium,
            color = textColor,
        )
    }
}

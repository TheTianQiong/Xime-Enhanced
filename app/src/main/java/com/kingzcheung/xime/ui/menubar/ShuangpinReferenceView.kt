package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.shuangpin.ShuangpinScheme
import com.kingzcheung.xime.shuangpin.ShuangpinSchemes

/**
 * IME 目录菜单中的「双拼对照」参考面板。
 *
 * 展示当前检测到的双拼方案键位表（分「声母 / 韵母」两组，先声母、后韵母），
 * 缺省为小鹤双拼；双韵母键以 "iang/uang" 形式显示。
 */
@Composable
fun ShuangpinReferenceView(
    backgroundColor: Color,
    keyTextColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    onBack: () -> Unit,
    bottomPaddingDp: Int = 0,
    scheme: ShuangpinScheme = ShuangpinSchemes.FLYPY,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
    val iconButtonContainer = androidx.compose.ui.graphics.lerp(keyBgColor, accentColor, 0.25f)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(backgroundColor),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(50.dp)
                .padding(horizontal = if (isLandscape) 50.dp else 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(CircleShape)
                    .background(iconButtonContainer)
                    .clickable { onBack() },
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.KeyboardArrowDown,
                    contentDescription = "返回",
                    tint = keyTextColor,
                    modifier = Modifier.size(24.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                text = "双拼对照（${scheme.displayName}）",
                color = keyTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "${scheme.displayName}键位表：先按声母键，再按韵母键，两键即可输入一个音节。",
                color = keyTextColor.copy(alpha = 0.6f),
                fontSize = 12.sp,
            )
            scheme.groups().forEach { group ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(keyBgColor)
                        .padding(12.dp),
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = group.title,
                            color = accentColor,
                            fontSize = 14.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        FlowRow(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                            maxItemsInEachRow = 5,
                        ) {
                            group.items.forEach { (pinyin, key) ->
                                Row(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(8.dp))
                                        .background(accentColor.copy(alpha = 0.10f))
                                        .padding(horizontal = 8.dp, vertical = 4.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Text(
                                        text = pinyin,
                                        color = keyTextColor,
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        text = " → ",
                                        color = keyTextColor.copy(alpha = 0.4f),
                                        fontSize = 12.sp,
                                    )
                                    Text(
                                        text = key,
                                        color = accentColor,
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.Bold,
                                    )
                                }
                            }
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(if (isLandscape) 15.dp else bottomPaddingDp.dp))
        }
    }
}

package com.kingzcheung.xime.ui.menubar

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.kingzcheung.xime.ui.permission.PermissionManagerContent
import com.kingzcheung.xime.util.PermissionHelper

/**
 * IME 目录菜单中的「管理权限」覆盖页。
 *
 * 在 IME 进程内展示权限状态；点击「去授权」通过 [PermissionHelper.requestPermission]
 * 拉起 MainActivity 弹系统权限框。授权完成后返回输入法窗口，[onWindowFocusChanged]
 * 触发 [refreshKey] 自增，状态自动刷新。
 */
@Composable
fun PermissionManagerView(
    backgroundColor: Color,
    keyTextColor: Color,
    accentColor: Color,
    keyBgColor: Color,
    onBack: () -> Unit,
    bottomPaddingDp: Int = 0,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var refreshKey by remember { mutableIntStateOf(0) }

    // 授权页返回后窗口重新聚焦 → 重算权限状态
    val view = LocalView.current
    DisposableEffect(view) {
        val observer = android.view.ViewTreeObserver.OnWindowFocusChangeListener { hasFocus ->
            if (hasFocus) refreshKey++
        }
        view.viewTreeObserver.addOnWindowFocusChangeListener(observer)
        onDispose {
            view.viewTreeObserver.removeOnWindowFocusChangeListener(observer)
        }
    }

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
                text = "管理权限",
                color = keyTextColor,
                fontSize = 16.sp,
                fontWeight = FontWeight.Medium,
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f)
                .padding(horizontal = 16.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(keyBgColor),
        ) {
            PermissionManagerContent(
                refreshKey = refreshKey,
                requestPermission = { permission -> PermissionHelper.requestPermission(context, permission) },
                modifier = Modifier.padding(vertical = 8.dp),
            )
        }

        Spacer(modifier = Modifier.height(if (isLandscape) 15.dp else bottomPaddingDp.dp))
    }
}

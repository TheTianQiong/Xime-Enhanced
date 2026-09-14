package com.kingzcheung.xime.ui.keyboard

import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.input.pointer.changedToUp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.unit.dp

/**
 * 宽容点击：语义同 Modifier.clickable，但点击判定改为「抬起时累计位移仍在
 * 容差内即触发」，不因事件被父级滚动/翻页手势消费而静默取消。
 *
 * 背景：候选页、表情页、符号页的按钮外层是 Pager/滚动容器。系统手势判定下，
 * 按下后位移超过 touch slop 即被判为滚动/翻页，clickable 随之取消点击；
 * 部分 ROM 的 slop 更小、采样更敏感，轻点的小位移也会超限，表现为
 * 「点了没反应」。容差取 2 倍 touch slop 与 12dp 的较大者：轻点不再丢点击，
 * 真实滚动/翻页的位移远超容差，不受影响。
 *
 * 本手势不消费任何指针事件，父级 Pager/滚动的行为保持系统默认。
 * showRipple = false 时涟漪反馈交给调用方自绘（配合 interactionSource 的
 * Press/Release/Cancel 事件，语义与 clickable 一致）。
 */
fun Modifier.tolerantClick(
    enabled: Boolean = true,
    showRipple: Boolean = true,
    interactionSource: MutableInteractionSource? = null,
    onClick: () -> Unit,
): Modifier = composed {
    val currentOnClick by rememberUpdatedState(onClick)
    val currentEnabled by rememberUpdatedState(enabled)
    val source = interactionSource ?: remember { MutableInteractionSource() }
    val resolvedIndication = if (showRipple) LocalIndication.current else null

    val indicationModifier = if (resolvedIndication != null) {
        Modifier.indication(source, resolvedIndication)
    } else {
        Modifier
    }

    indicationModifier.then(
        Modifier.pointerInput(currentEnabled) {
            if (!currentEnabled) return@pointerInput
            val slopPx = maxOf(2f * viewConfiguration.touchSlop, 12.dp.toPx())
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val press = PressInteraction.Press(down.position)
                source.tryEmit(press)
                var travelled = 0f
                while (true) {
                    val event = awaitPointerEvent()
                    val pressedChange = event.changes.firstOrNull { it.pressed }
                    val upChange = event.changes.firstOrNull { it.changedToUp() }
                    travelled += (pressedChange ?: upChange)?.positionChange()?.getDistance() ?: 0f
                    if (pressedChange == null) {
                        if (upChange != null && travelled <= slopPx) {
                            currentOnClick()
                            source.tryEmit(PressInteraction.Release(press))
                        } else {
                            source.tryEmit(PressInteraction.Cancel(press))
                        }
                        break
                    }
                }
            }
        }
    )
}

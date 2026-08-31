package com.kingzcheung.xime.ui.keyboard

import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.kingzcheung.xime.service.ToolPanelEditTextHolder

internal val TOOL_PANEL_HEIGHT = 170

/**
 * AI 工具插件输入面板（候选栏上方）。
 *
 * 仅承载输入与生成触发：输入框（预填上下文，键盘按键路由可注入）+ 生成状态提示。
 * 生成结果不再显示在此面板，多条结果由 passive 面板（InfoPanel 内 items 点选上屏）承载。
 */
@Composable
fun ToolPanel(
    title: String,
    isFocused: Boolean,
    isLoading: Boolean = false,
    initialText: String = "",
    backgroundColor: Color,
    textColor: Color,
    accentColor: Color,
    cardBgColor: Color,
    onClose: () -> Unit,
    onFocusChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val closeButtonBg = androidx.compose.ui.graphics.lerp(
        MaterialTheme.colorScheme.surface,
        MaterialTheme.colorScheme.primary,
        0.35f
    )

    CandidateBarOverlayPanel(
        heightDp = TOOL_PANEL_HEIGHT,
        backgroundColor = backgroundColor,
        cardBgColor = cardBgColor,
        closeButtonBg = closeButtonBg,
        closeButtonColor = accentColor,
        title = title,
        titleColor = textColor,
        modifier = modifier,
        onCloseClick = onClose,
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            AndroidView(
                factory = { context ->
                    android.widget.EditText(context).apply {
                        setBackgroundColor(android.graphics.Color.TRANSPARENT)
                        setTextColor(textColor.hashCode())
                        setHintTextColor((textColor.copy(alpha = 0.4f)).hashCode())
                        hint = "输入上下文或指令"
                        textSize = 16f
                        isSingleLine = false
                        gravity = Gravity.TOP or Gravity.START
                        setPadding(4, 2, 4, 2)

                        setImeActionLabel("生成", EditorInfo.IME_ACTION_DONE)
                        imeOptions = EditorInfo.IME_FLAG_NO_ENTER_ACTION or
                            EditorInfo.IME_ACTION_DONE

                        onFocusChangeListener = View.OnFocusChangeListener { _, hasFocus ->
                            onFocusChange(hasFocus)
                        }
                        setOnClickListener { onFocusChange(true) }
                        ToolPanelEditTextHolder.editText = this
                        if (isFocused) {
                            post { requestFocus() }
                        }
                    }
                },
                update = { editText ->
                    if (initialText.isNotEmpty() && !editText.text.toString().equals(initialText)) {
                        editText.setText(initialText)
                        editText.setSelection(initialText.length)
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = if (isLoading) "生成中..." else "输入上下文后按回车生成",
                    color = textColor.copy(alpha = 0.5f),
                    fontSize = 11.sp,
                )
            }
        }
    }
}

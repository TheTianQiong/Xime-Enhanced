package com.kingzcheung.xime.ui.keyboard

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.kingzcheung.xime.settings.SettingsPreferences

/** 上/下滑提示显示开关（键面提示与滑动气泡是否显示）。 */
data class SwipeHintsEnabled(val up: Boolean, val down: Boolean)

/**
 * 记忆并监听「上滑/下滑提示」设置开关（与 26 键 KeyboardLayout 同款逻辑），
 * 供九键/笔画布局复用；设置页切换后即时生效。
 */
@Composable
internal fun rememberSwipeHintsEnabled(): SwipeHintsEnabled {
    val context = LocalContext.current
    var swipeUpHintsEnabled by remember {
        mutableStateOf(SettingsPreferences.isSwipeUpHintsEnabled(context))
    }
    var swipeDownHintsEnabled by remember {
        mutableStateOf(SettingsPreferences.isSwipeDownHintsEnabled(context))
    }
    DisposableEffect(context) {
        val prefs = SettingsPreferences.getPrefsPublic(context)
        val listener =
            android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                when (key) {
                    SettingsPreferences.KEY_SWIPE_UP_HINTS_ENABLED ->
                        swipeUpHintsEnabled = SettingsPreferences.isSwipeUpHintsEnabled(context)

                    SettingsPreferences.KEY_SWIPE_DOWN_HINTS_ENABLED ->
                        swipeDownHintsEnabled = SettingsPreferences.isSwipeDownHintsEnabled(context)
                }
            }
        prefs.registerOnSharedPreferenceChangeListener(listener)
        onDispose { prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return SwipeHintsEnabled(swipeUpHintsEnabled, swipeDownHintsEnabled)
}

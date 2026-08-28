package com.kingzcheung.xime.shuangpin

import androidx.compose.runtime.compositionLocalOf

/**
 * 双拼动态键面提示状态。
 *
 * - [active]：当前方案为双拼方案且开关开启，需要动态键面；
 * - [showYunmu]：已输入声母（奇数键），键面应显示韵母映射；
 *   否则键面显示声母映射；
 * - [scheme]：检测到的双拼方案（小鹤/自然码/微软…），null 表示非双拼。
 */
data class ShuangpinKeyHint(
    val active: Boolean = false,
    val showYunmu: Boolean = false,
    val scheme: ShuangpinScheme? = null,
)

/** 键盘渲染层读取的 CompositionLocal，避免层层传参。 */
val LocalShuangpinKeyHint = compositionLocalOf { ShuangpinKeyHint() }

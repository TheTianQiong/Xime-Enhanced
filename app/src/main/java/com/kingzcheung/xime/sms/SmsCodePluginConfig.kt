package com.kingzcheung.xime.sms

import android.content.Context

/**
 * sms-code 插件配置的 Java 侧访问入口。
 *
 * 与插件 main.lua 的 `host.config` 共享同一份 SharedPreferences
 * （`plugin_cfg_com.kingzcheung.xime.plugin.sms_code`），
 * 因此「管理权限」页的正则输入框与插件设置页编辑的是同一个配置，互为同步。
 */
object SmsCodePluginConfig {

    const val PLUGIN_ID = "com.kingzcheung.xime.plugin.sms_code"
    const val KEY_REGEX = "regex"

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("plugin_cfg_$PLUGIN_ID", Context.MODE_PRIVATE)

    /** 返回配置的提取正则；未配置或空白时返回 null。 */
    fun getRegex(context: Context): String? =
        prefs(context).getString(KEY_REGEX, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** 保存提取正则（自动去首尾空白）。 */
    fun setRegex(context: Context, regex: String) {
        prefs(context).edit().putString(KEY_REGEX, regex.trim()).apply()
    }
}

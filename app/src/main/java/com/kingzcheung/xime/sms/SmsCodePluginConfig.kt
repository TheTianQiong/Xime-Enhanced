package com.kingzcheung.xime.sms

import android.content.Context
import com.kingzcheung.xime.settings.SettingsPreferences

/**
 * sms-code 插件配置的 Java 侧访问入口。
 *
 * 与插件 main.lua 的 `host.config` 共享同一份 SharedPreferences
 * （`plugin_cfg_com.kingzcheung.xime.plugin.sms_code`），因此插件设置页
 * 与宿主功能读取的是同一份配置。
 *
 * 全部验证码相关设置（开关、自动复制、有效期、提取正则）都由该插件承载，
 * 在「插件管理 → 短信验证码（增强）→ 设置」中配置。
 * 早期的宿主设置项（[SettingsPreferences] 中的 sms_code_* 键）通过
 * [migrateLegacySettingsIfNeeded] 一次性迁移过来，避免升级后配置丢失。
 *
 * 表单以字符串存取（switch 存 "true"/"false"，number 存数字文本）。
 */
object SmsCodePluginConfig {

    const val PLUGIN_ID = "com.kingzcheung.xime.plugin.sms_code"
    const val KEY_REGEX = "regex"
    const val KEY_ENABLED = "enabled"
    const val KEY_AUTO_COPY = "autoCopy"
    const val KEY_TTL_SECONDS = "ttlSeconds"

    /** 有效期默认值（秒）：只显示最近 1 分钟内的验证码。 */
    const val DEFAULT_TTL_SECONDS = 60L

    private const val MIN_TTL_SECONDS = 10L
    private const val MAX_TTL_SECONDS = 600L

    /** 迁移完成标记（写在宿主偏好中，与插件配置分离）。 */
    private const val KEY_MIGRATED = "sms_code_settings_migrated_to_plugin"

    private fun prefs(context: Context): android.content.SharedPreferences =
        context.getSharedPreferences("plugin_cfg_$PLUGIN_ID", Context.MODE_PRIVATE)

    // ── 提取正则 ──

    /** 返回配置的提取正则；未配置或空白时返回 null（回退内置智能提取）。 */
    fun getRegex(context: Context): String? =
        prefs(context).getString(KEY_REGEX, null)?.trim()?.takeIf { it.isNotEmpty() }

    /** 保存提取正则（自动去首尾空白）。 */
    fun setRegex(context: Context, regex: String) {
        prefs(context).edit().putString(KEY_REGEX, regex.trim()).apply()
    }

    // ── 功能开关 ──

    /** 短信验证码功能总开关（默认关闭）。 */
    fun isEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_ENABLED, null)?.toBooleanStrictOrNull() ?: false

    /** 收到验证码后是否自动复制到剪贴板（默认关闭）。 */
    fun isAutoCopyEnabled(context: Context): Boolean =
        prefs(context).getString(KEY_AUTO_COPY, null)?.toBooleanStrictOrNull() ?: false

    /**
     * 验证码有效期（秒）：仅显示接收时间在有效期内的验证码。
     * 取值钳制在 10–600 秒；未配置或非法时用默认值。
     */
    fun getTtlSeconds(context: Context): Long =
        prefs(context).getString(KEY_TTL_SECONDS, null)
            ?.toLongOrNull()
            ?.coerceIn(MIN_TTL_SECONDS, MAX_TTL_SECONDS)
            ?: DEFAULT_TTL_SECONDS

    /**
     * 把早期宿主设置迁移到插件配置（幂等，只在首次执行）。
     *
     * 早期版本把开关、自动复制、有效期存在宿主偏好中；迁到插件后
     * 若不做迁移，升级用户的既有配置会被默认值（关闭）覆盖。
     * 只补齐插件侧尚不存在的键，不覆盖用户在插件页已设置的值。
     */
    fun migrateLegacySettingsIfNeeded(context: Context) {
        val hostPrefs = SettingsPreferences.getPrefsPublic(context)
        if (hostPrefs.getBoolean(KEY_MIGRATED, false)) return
        try {
            val target = prefs(context)
            val editor = target.edit()
            if (!target.contains(KEY_ENABLED)) {
                editor.putString(KEY_ENABLED, SettingsPreferences.isSmsCodeEnabled(context).toString())
            }
            if (!target.contains(KEY_AUTO_COPY)) {
                editor.putString(KEY_AUTO_COPY, SettingsPreferences.isSmsAutoCopyEnabled(context).toString())
            }
            if (!target.contains(KEY_TTL_SECONDS)) {
                editor.putString(KEY_TTL_SECONDS, SettingsPreferences.getSmsCodeTtlSeconds(context).toString())
            }
            editor.apply()
            hostPrefs.edit().putBoolean(KEY_MIGRATED, true).apply()
        } catch (e: Exception) {
            // 迁移失败不应影响启动：下次启动会重试（标记仅在成功后写入）
            com.kingzcheung.xime.util.FileLogger.e("SmsCodePluginConfig", "迁移验证码设置失败", e)
        }
    }
}

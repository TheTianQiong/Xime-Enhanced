package com.kingzcheung.xime.sms

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * 短信验证码内存 + 轻量持久化存储。
 *
 * - [codes] 为全局 StateFlow，供 IME 候选栏快捷插入实时订阅；
 * - 历史以 MODE_PRIVATE 的 SharedPreferences 保存（仅本应用可读，不上传），
 *   进程重启后仍可恢复最近若干条。
 */
object SmsCodeStore {

    private const val PREFS_NAME = "sms_code_store"
    private const val KEY_HISTORY = "history"
    private const val MAX_CODES = 10

    private val _codes = MutableStateFlow<List<SmsCode>>(emptyList())
    val codes: StateFlow<List<SmsCode>> = _codes.asStateFlow()

    @Volatile
    private var loaded = false

    /** 首次调用时从持久化恢复；幂等。 */
    fun init(context: Context) {
        if (loaded) return
        loaded = true
        _codes.value = load(context)
    }

    /** 加入一条新验证码（去重：相同 code 置顶）；持久化最近 [MAX_CODES] 条。 */
    fun add(context: Context, code: String, sender: String) {
        val entry = SmsCode(code, sender, System.currentTimeMillis())
        val updated = (listOf(entry) + _codes.value.filter { it.code != code }).take(MAX_CODES)
        _codes.value = updated
        save(context, updated)
    }

    /** 消费（移除）某条验证码，用于「已插入 / 已关闭」。 */
    fun consume(context: Context, code: String) {
        val updated = _codes.value.filter { it.code != code }
        _codes.value = updated
        save(context, updated)
    }

    /** 清空全部验证码记录。 */
    fun clear(context: Context) {
        _codes.value = emptyList()
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().remove(KEY_HISTORY).apply()
    }

    private fun load(context: Context): List<SmsCode> {
        val raw = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_HISTORY, null)
            ?: return emptyList()
        return raw.lines().mapNotNull { line ->
            val parts = line.split('|', limit = 3)
            if (parts.size == 3 && parts[0].isNotBlank()) {
                SmsCode(parts[0], parts[1], parts[2].toLongOrNull() ?: 0L)
            } else null
        }
    }

    private fun save(context: Context, list: List<SmsCode>) {
        val text = list.joinToString("\n") { "${it.code}|${it.sender}|${it.timestamp}" }
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit().putString(KEY_HISTORY, text).apply()
    }
}

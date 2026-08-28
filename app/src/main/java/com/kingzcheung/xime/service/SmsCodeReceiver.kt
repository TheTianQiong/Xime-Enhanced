package com.kingzcheung.xime.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import com.kingzcheung.xime.settings.SettingsPreferences
import com.kingzcheung.xime.sms.SmsCodeExtractor
import com.kingzcheung.xime.sms.SmsCodePluginConfig
import com.kingzcheung.xime.sms.SmsCodeStore

/**
 * 短信验证码接收器。
 *
 * 需要用户在「管理权限」中授予 [android.Manifest.permission.RECEIVE_SMS] 后才会收到广播。
 * 收到含验证码的短信后：
 * 1. 本地提取验证码并写入 [SmsCodeStore]（IME 候选栏快捷插入据此显示）；
 * 2. 若开启「自动复制到剪贴板」则同时写入系统剪贴板。
 *
 * 短信内容仅在设备本地正则解析，不进行任何网络上传。
 */
class SmsCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Telephony.Sms.Intents.SMS_RECEIVED_ACTION) return
        if (!SettingsPreferences.isSmsCodeEnabled(context)) return

        val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
        if (messages.isEmpty()) return

        val body = messages.joinToString("") { it.messageBody ?: "" }
        val sender = messages.firstOrNull()?.originatingAddress ?: ""
        // 优先使用 sms-code 插件配置的自定义正则；未配置则用内置智能提取
        val customRegex = SmsCodePluginConfig.getRegex(context)
        val code = customRegex?.let { SmsCodeExtractor.extractWithRegex(body, it) }
            ?: SmsCodeExtractor.extract(body)
            ?: return

        SmsCodeStore.init(context)
        SmsCodeStore.add(context, code, sender)

        if (SettingsPreferences.isSmsAutoCopyEnabled(context)) {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
            clipboard.setPrimaryClip(android.content.ClipData.newPlainText("sms_code", code))
        }
    }
}

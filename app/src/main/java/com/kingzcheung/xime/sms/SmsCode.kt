package com.kingzcheung.xime.sms

/**
 * 一条短信验证码记录。
 *
 * @param code      提取到的验证码（4-6 位数字）
 * @param sender    发送者号码（originatingAddress，可能为空）
 * @param timestamp 接收时间（System.currentTimeMillis）
 */
data class SmsCode(
    val code: String,
    val sender: String,
    val timestamp: Long,
)

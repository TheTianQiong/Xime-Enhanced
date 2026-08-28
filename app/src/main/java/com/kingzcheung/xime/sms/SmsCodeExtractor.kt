package com.kingzcheung.xime.sms

/**
 * 从短信正文中提取验证码。
 *
 * 策略（按优先级）：
 * 1. 在「验证码 / 动态码 / 安全码 / 校验码 / code / verification」等关键词附近的
 *    一段窗口内，找独立的 4-6 位数字组（前后都不是数字，排除手机号等长串）。
 * 2. 兜底：取正文中最后一个独立 4-6 位数字组。
 *
 * 数字仅本地正则匹配，不涉及任何网络上传。
 */
object SmsCodeExtractor {

    private val KEYWORDS = listOf(
        "验证码", "动态码", "安全码", "校验码", "确认码", "取件码",
        "登录码", "授权码", "短信码", "一次性", "验证",
        "code", "verification", "verify", "otp", "auth",
    )

    /** 独立的 4-6 位数字组：前后均不能是数字，避免匹配到手机号 / 长订单号的一部分。 */
    private val DIGIT_GROUP = Regex("(?<!\\d)\\d{4,6}(?!\\d)")

    /**
     * 使用用户自定义正则提取验证码。
     *
     * 规则：
     * - 若正则有捕获组，返回第一个非空捕获组的内容；
     * - 否则返回整个匹配文本；
     * - 正则非法或未匹配返回 null。
     */
    fun extractWithRegex(body: String?, regex: String): String? {
        if (body.isNullOrBlank()) return null
        return try {
            val r = Regex(regex)
            val m = r.find(body) ?: return null
            val groupCount = m.groupValues.size - 1
            for (i in 1..groupCount) {
                if (m.groupValues[i].isNotBlank()) return m.groupValues[i]
            }
            m.value.takeIf { it.isNotBlank() }
        } catch (_: Exception) {
            null
        }
    }

    /** 关键词上下文窗口半径（字符数）。 */
    private const val WINDOW = 20

    fun extract(body: String?): String? {
        if (body.isNullOrBlank()) return null

        // 1. 关键词附近的数字优先
        for (kw in KEYWORDS) {
            val idx = body.indexOf(kw, ignoreCase = true)
            if (idx < 0) continue
            val start = (idx - WINDOW).coerceAtLeast(0)
            val end = (idx + kw.length + WINDOW).coerceAtMost(body.length)
            val window = body.substring(start, end)
            val hit = DIGIT_GROUP.find(window)?.value
            if (hit != null) return hit
        }

        // 2. 兜底：最后一个独立数字组
        return DIGIT_GROUP.findAll(body).lastOrNull()?.value
    }
}

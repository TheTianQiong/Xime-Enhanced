package com.kingzcheung.xime.sms

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SmsCodeExtractorTest {

    @Test
    fun `提取关键词附近的验证码`() {
        assertEquals("123456", SmsCodeExtractor.extract("【某应用】验证码为 123456，5分钟内有效，请勿泄露。"))
        assertEquals("8888", SmsCodeExtractor.extract("您的动态码是8888，请尽快输入"))
        assertEquals("9527", SmsCodeExtractor.extract("安全码 9527，用于登录确认"))
    }

    @Test
    fun `提取英文关键词附近的验证码`() {
        assertEquals("456789", SmsCodeExtractor.extract("Your verification code is 456789. Do not share."))
        assertEquals("1122", SmsCodeExtractor.extract("code: 1122 for login"))
    }

    @Test
    fun `兜底提取独立数字组`() {
        assertEquals("334455", SmsCodeExtractor.extract("登录确认：334455，请勿转发"))
        assertEquals("123456", SmsCodeExtractor.extract("这是你的验证码：123456"))
    }

    @Test
    fun `手机号长串不应被当作验证码`() {
        // 手机号 13 位数字串不应匹配出子串
        assertEquals("9999", SmsCodeExtractor.extract("验证码9999，账号 13812345678 正在登录"))
    }

    @Test
    fun `无验证码时返回空`() {
        assertNull(SmsCodeExtractor.extract("这是一条普通短信，没有数字验证码。"))
        assertNull(SmsCodeExtractor.extract(""))
        assertNull(SmsCodeExtractor.extract(null))
    }

    @Test
    fun `自定义正则提取`() {
        // 简单数字组正则
        assertEquals("123456", SmsCodeExtractor.extractWithRegex("验证码 123456，请勿泄露", "\\d{6}"))
        // 捕获组：取第一个非空分组
        assertEquals("8888", SmsCodeExtractor.extractWithRegex("您的验证码为8888", "(\\d{4,6})"))
        // 命名捕获组
        assertEquals("9527", SmsCodeExtractor.extractWithRegex("code=9527 end", "code=(?<c>\\d{4})"))
        // 前后缀正则
        assertEquals("334455", SmsCodeExtractor.extractWithRegex("安全码 334455 已发送", "安全码 (\\d{6})"))
    }

    @Test
    fun `自定义正则异常与未匹配返回空`() {
        assertNull(SmsCodeExtractor.extractWithRegex("验证码 123456", "[无效正则("))
        assertNull(SmsCodeExtractor.extractWithRegex("没有数字", "\\d{4,6}"))
        assertNull(SmsCodeExtractor.extractWithRegex(null, "\\d{4,6}"))
        assertNull(SmsCodeExtractor.extractWithRegex("", "\\d{4,6}"))
    }
}

package com.kingzcheung.xime.plugin.core.lua

import com.kingzcheung.xime.plugin.core.config.PluginConfigStore
import com.kingzcheung.xime.plugin.core.lua.crypto.CryptoHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpHostApi
import com.kingzcheung.xime.plugin.core.lua.http.HttpResponse
import com.kingzcheung.xime.plugin.core.lua.sdk.LuaHostApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 验证 s3-clipboard-sync 插件：S3 协议逻辑（SigV4 签名、寻址方式、URI 编码、
 * ETag 条件拉取）全在 Lua，宿主只提供 host.http / host.crypto / host.config / host.json 原语。
 *
 * SigV4 正确性用 [AwsSigV4] 作为对照实现（按 AWS 规范独立编写，非 Lua 侧代码的翻译），
 * 并以公开常量（空串 SHA-256）交叉校验摘要链路。
 */
class LuaS3ClipboardSyncPluginTest {

    private companion object {
        /** SHA-256("")，公开常量：用于校验 GET/无体请求的 x-amz-content-sha256。 */
        const val EMPTY_SHA256 =
            "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"

        const val ACCESS_KEY = "AKIAIOSFODNN7EXAMPLE"
        const val SECRET_KEY = "wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY"
        const val REGION = "us-east-1"

        /** 固定签名时刻，使签名可复现。 */
        const val AMZ_DATE = "20130524T000000Z"
        const val DATE_STAMP = "20130524"
    }

    private class InMemoryConfigStore : PluginConfigStore {
        private val map = HashMap<String, String>()
        override fun get(key: String): String? = map[key]
        override fun set(key: String, value: String) { map[key] = value }
        override fun remove(key: String) { map.remove(key) }
        override fun keys(): Set<String> = map.keys.toSet()
    }

    /** 记录请求、可编程响应的 mock HTTP 宿主。 */
    private class MockHttpHostApi : HttpHostApi {
        val requests = mutableListOf<Triple<String, String, Map<String, String>>>()
        val requestBodies = mutableListOf<String>()
        val responseQueue = ArrayDeque<HttpResponse>()
        var lastErrorMsg: String? = null

        override fun request(
            method: String,
            url: String,
            headers: Map<String, String>,
            body: ByteArray?,
            timeoutMillis: Int?
        ): HttpResponse? {
            requests.add(Triple(method, url, headers))
            requestBodies.add(body?.toString(Charsets.UTF_8) ?: "")
            return responseQueue.removeFirstOrNull()
        }

        override fun lastError(): String? = lastErrorMsg
    }

    /** 真实摘要/HMAC 实现（SigV4 需要可验证的密码学结果），时间固定。 */
    private class RealCryptoHostApi : CryptoHostApi {
        override fun sha256(data: ByteArray): ByteArray =
            MessageDigest.getInstance("SHA-256").digest(data)

        override fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data)
        }

        override fun hmacSha1(key: ByteArray, data: ByteArray): ByteArray {
            val mac = Mac.getInstance("HmacSHA1")
            mac.init(SecretKeySpec(key, "HmacSHA1"))
            return mac.doFinal(data)
        }

        override fun hex(data: ByteArray): String =
            data.joinToString("") { "%02x".format(it) }

        override fun base64(data: ByteArray): String =
            java.util.Base64.getEncoder().encodeToString(data)

        override fun utcTime(format: String): String =
            if (format == "YYYYMMDD") DATE_STAMP else AMZ_DATE

        override fun epochSeconds(): Long = 1369353600
    }

    /** 测试专用宿主 API：log 输出到 stdout（unit test 中 android.util.Log 是 stub）。 */
    private class DebugHostApi(private val store: PluginConfigStore) : LuaHostApi {
        override val sdkVersion = "0.1.0"
        override fun log(message: String) { System.out.println("LUA_LOG: $message") }
        override fun logError(message: String) { System.err.println("LUA_ERROR: $message") }
        override fun configGet(key: String) = store.get(key)
        override fun configSet(key: String, value: String) { store.set(key, value) }
        override fun configRemove(key: String) { store.remove(key) }
        override fun configKeys() = store.keys()
        override fun resourcePath(name: String) = null
        override fun resourceList(dir: String) = emptyList<String>()
        override fun jsonEncode(obj: Any?) = com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.encode(obj)
        override fun jsonDecode(json: String) = try {
            com.kingzcheung.xime.plugin.core.lua.sdk.SimpleJson.decode(json)
        } catch (_: Exception) {
            null
        }
        override fun uuid() = "uuid"
    }

    /**
     * AWS Signature V4 对照实现（按 AWS 规范独立编写）。
     * 仅用于测试：为给定请求重算签名，与插件产出的 Authorization 头比对。
     */
    private object AwsSigV4 {
        fun hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

        fun sha256Hex(text: String): String =
            hex(MessageDigest.getInstance("SHA-256").digest(text.toByteArray(Charsets.UTF_8)))

        fun hmac(key: ByteArray, data: String): ByteArray {
            val mac = Mac.getInstance("HmacSHA256")
            mac.init(SecretKeySpec(key, "HmacSHA256"))
            return mac.doFinal(data.toByteArray(Charsets.UTF_8))
        }

        private fun scope(region: String) = "$DATE_STAMP/$region/s3/aws4_request"

        /**
         * 返回完整 Authorization 头值。
         * @param extraHeaders 额外参与签名的头（键不区分大小写，内部转小写后排序）
         */
        fun authorization(
            method: String,
            host: String,
            canonicalPath: String,
            payload: String,
            region: String = REGION,
            extraHeaders: Map<String, String> = emptyMap()
        ): String {
            val payloadHash = sha256Hex(payload)
            val entries = sortedMapOf<String, String>()
            entries["host"] = host
            entries["x-amz-content-sha256"] = payloadHash
            entries["x-amz-date"] = AMZ_DATE
            extraHeaders.forEach { (k, v) -> entries[k.lowercase()] = v }

            val canonicalHeaders = entries.entries.joinToString("") { "${it.key}:${it.value}\n" }
            val signedHeaders = entries.keys.joinToString(";")
            val canonicalRequest = "$method\n$canonicalPath\n\n$canonicalHeaders\n$signedHeaders\n$payloadHash"
            val stringToSign =
                "AWS4-HMAC-SHA256\n$AMZ_DATE\n${scope(region)}\n${sha256Hex(canonicalRequest)}"

            val kDate = hmac("AWS4$SECRET_KEY".toByteArray(Charsets.UTF_8), DATE_STAMP)
            val kRegion = hmac(kDate, region)
            val kService = hmac(kRegion, "s3")
            val kSigning = hmac(kService, "aws4_request")
            val signature = hex(hmac(kSigning, stringToSign))

            return "AWS4-HMAC-SHA256 Credential=$ACCESS_KEY/${scope(region)}" +
                ", SignedHeaders=$signedHeaders, Signature=$signature"
        }
    }

    private fun newRuntime(store: PluginConfigStore, http: MockHttpHostApi): LuaScriptRuntime {
        val dir = File("../plugins/s3-clipboard-sync")
        assertTrue("插件目录应存在: ${dir.absolutePath}", dir.exists())
        val runtime = LuaScriptRuntime(
            "com.kingzcheung.xime.plugin.s3_clipboard_sync",
            dir,
            "main.lua",
            store,
            hostApi = DebugHostApi(store),
            httpHostApi = http,
            cryptoHostApi = RealCryptoHostApi()
        )
        assertTrue("main.lua 应能加载", runtime.load())
        return runtime
    }

    private fun profileTable(text: String, hash: String): org.luaj.vm2.LuaTable {
        val table = org.luaj.vm2.LuaTable()
        table.set("type", "text")
        table.set("hash", hash)
        table.set("text", text)
        table.set("has_data", org.luaj.vm2.LuaValue.FALSE)
        table.set("size", org.luaj.vm2.LuaValue.valueOf(text.length.toDouble()))
        return table
    }

    /** 写入一套可用的 S3 凭证配置。 */
    private fun InMemoryConfigStore.withCredentials() {
        set("accessKeyId", ACCESS_KEY)
        set("secretAccessKey", SECRET_KEY)
        set("region", REGION)
    }

    // ================= 契约 =================

    @Test
    fun `main lua loads and exposes sync contract`() {
        val runtime = newRuntime(InMemoryConfigStore(), MockHttpHostApi())
        val schema = runtime.call("getSettingsSchema")
        assertTrue("应导出 getSettingsSchema", schema.istable())
        assertEquals(9, LuaScriptRuntime.tableToList(schema).size)
    }

    // ================= 寻址与签名 =================

    @Test
    fun `path style signs request with sigv4 and encodes object key`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "http://192.168.1.50:9000")
        store.set("bucket", "mybucket")
        store.set("objectKey", "xime/my file\$1.json")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val runtime = newRuntime(store, http)

        assertTrue(runtime.call("push", profileTable("hello", "abc")).toboolean())

        val (method, url, headers) = http.requests[0]
        assertEquals("PUT", method)
        // 路径式寻址：endpoint/bucket/key，键内空格与 $ 需百分号编码（'$' → %24）
        assertEquals("http://192.168.1.50:9000/mybucket/xime/my%20file%241.json", url)
        assertEquals("192.168.1.50:9000", headers["Host"])

        val expected = AwsSigV4.authorization(
            method = "PUT",
            host = "192.168.1.50:9000",
            canonicalPath = "/mybucket/xime/my%20file%241.json",
            payload = http.requestBodies[0]
        )
        assertEquals(expected, headers["Authorization"])
        assertEquals(
            "host;x-amz-content-sha256;x-amz-date",
            headers["Authorization"]!!.substringAfter("SignedHeaders=").substringBefore(",")
        )
        assertEquals("application/json", headers["Content-Type"])
    }

    @Test
    fun `virtual hosted style puts bucket in subdomain`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.set("objectKey", "test\$file.text")
        store.set("pathStyle", "virtual")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val runtime = newRuntime(store, http)

        assertTrue(runtime.call("push", profileTable("Welcome", "h")).toboolean())

        val (_, url, headers) = http.requests[0]
        // 与 AWS SigV4 文档示例一致的规范化路径：'$' → %24
        assertEquals("https://examplebucket.s3.amazonaws.com/test%24file.text", url)
        assertEquals("examplebucket.s3.amazonaws.com", headers["Host"])
        assertEquals(
            AwsSigV4.authorization(
                method = "PUT",
                host = "examplebucket.s3.amazonaws.com",
                canonicalPath = "/test%24file.text",
                payload = http.requestBodies[0]
            ),
            headers["Authorization"]
        )
    }

    @Test
    fun `endpoint subpath is preserved for reverse proxy`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://example.com/storage/")
        store.set("bucket", "b1")
        store.set("objectKey", "clip.json")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200))
        val runtime = newRuntime(store, http)

        assertTrue(runtime.call("push", profileTable("x", "h")).toboolean())

        val (_, url, headers) = http.requests[0]
        assertEquals("https://example.com/storage/b1/clip.json", url)
        assertEquals("example.com", headers["Host"])
    }

    @Test
    fun `push fails when endpoint or bucket missing`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.withCredentials()
        val http = MockHttpHostApi()
        val runtime = newRuntime(store, http)

        assertTrue("缺 bucket 时 push 应失败", !runtime.call("push", profileTable("x", "h")).toboolean())
        assertTrue("不应发出请求", http.requests.isEmpty())
    }

    // ================= 拉取 =================

    @Test
    fun `pull sends empty payload hash and caches etag`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.set("objectKey", "clip.json")
        store.set("pathStyle", "virtual")
        store.withCredentials()
        val http = MockHttpHostApi()
        val profileJson =
            """{"type":"text","hash":"abc123","text":"远端内容","has_data":false,"data_name":null,"size":12,"source":"desktop"}"""
        http.responseQueue.addLast(
            HttpResponse(200, mapOf("ETag" to "\"s3-etag-1\""), profileJson.toByteArray())
        )
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("pull 应返回 table", result.istable())
        val map = LuaScriptRuntime.tableToMap(result)
        assertEquals("远端内容", map["text"]?.tojstring())
        assertEquals("abc123", map["hash"]?.tojstring())
        assertEquals("desktop", map["source"]?.tojstring())
        assertEquals("\"s3-etag-1\"", store.get("lastEtag"))

        val (method, _, headers) = http.requests[0]
        assertEquals("GET", method)
        // 公开常量：空请求体的 SHA-256
        assertEquals(EMPTY_SHA256, headers["x-amz-content-sha256"])
        assertTrue("空体请求不应带 Content-Type", !headers.containsKey("Content-Type"))
        assertEquals(
            AwsSigV4.authorization(
                method = "GET",
                host = "examplebucket.s3.amazonaws.com",
                canonicalPath = "/clip.json",
                payload = ""
            ),
            headers["Authorization"]
        )
    }

    @Test
    fun `pull sends if-none-match and skips on 304`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.set("pathStyle", "virtual")
        store.withCredentials()
        store.set("lastEtag", "\"cached-etag\"")
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(304))
        val runtime = newRuntime(store, http)

        val result = runtime.call("pull")

        assertTrue("304 应视为无变更", result.isnil())
        val (_, _, headers) = http.requests[0]
        assertEquals("\"cached-etag\"", headers["If-None-Match"])
        // If-None-Match 参与签名
        assertTrue(headers["Authorization"]!!.contains("if-none-match"))
        assertEquals(
            AwsSigV4.authorization(
                method = "GET",
                host = "examplebucket.s3.amazonaws.com",
                canonicalPath = "/xime/clipboard/current.json",
                payload = "",
                extraHeaders = mapOf("If-None-Match" to "\"cached-etag\"")
            ),
            headers["Authorization"]
        )
    }

    @Test
    fun `pull returns nil when object absent`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        assertTrue("404 应视为无变更", runtime.call("pull").isnil())
    }

    @Test
    fun `pull falls back to plain text for non json object`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(200, emptyMap(), "旧版纯文本".toByteArray()))
        val runtime = newRuntime(store, http)

        val map = LuaScriptRuntime.tableToMap(runtime.call("pull"))
        assertEquals("旧版纯文本", map["text"]?.tojstring())
    }

    // ================= 连接测试 =================

    @Test
    fun `test connection succeeds on 404 because object may not exist yet`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        assertTrue("404 应视为连接可用", runtime.call("testConnection").isnil())
    }

    @Test
    fun `test connection reports auth failure with s3 error code`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(
            HttpResponse(
                403,
                emptyMap(),
                "<Error><Code>SignatureDoesNotMatch</Code></Error>".toByteArray()
            )
        )
        val runtime = newRuntime(store, http)

        val message = runtime.call("testConnection").tojstring()
        assertTrue("应提示认证失败: $message", message.contains("认证失败"))
        assertTrue("应带 S3 错误码: $message", message.contains("SignatureDoesNotMatch"))
    }

    @Test
    fun `test connection reports region mismatch on redirect`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(301))
        val runtime = newRuntime(store, http)

        assertTrue(runtime.call("testConnection").tojstring().contains("区域不匹配"))
    }

    @Test
    fun `test connection requires credentials without sending request`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        val http = MockHttpHostApi()
        val runtime = newRuntime(store, http)

        val message = runtime.call("testConnection").tojstring()
        assertTrue("应提示缺少凭证: $message", message.contains("Access Key"))
        assertTrue("不应发出请求", http.requests.isEmpty())
    }

    @Test
    fun `session token is signed and sent when configured`() {
        val store = InMemoryConfigStore()
        store.set("endpoint", "https://s3.amazonaws.com")
        store.set("bucket", "examplebucket")
        store.set("sessionToken", "FQoGZXIvYXdzEBYaDA==")
        store.withCredentials()
        val http = MockHttpHostApi()
        http.responseQueue.addLast(HttpResponse(404))
        val runtime = newRuntime(store, http)

        runtime.call("pull")

        val (_, _, headers) = http.requests[0]
        assertEquals("FQoGZXIvYXdzEBYaDA==", headers["x-amz-security-token"])
        assertTrue(
            "会话令牌应参与签名",
            headers["Authorization"]!!.contains("x-amz-security-token")
        )
    }

    @Test
    fun `unconfigured plugin never issues request`() {
        val http = MockHttpHostApi()
        val runtime = newRuntime(InMemoryConfigStore(), http)

        assertTrue(runtime.call("pull").isnil())
        assertNull("未配置时不应有请求", http.requests.firstOrNull())
        assertTrue(!runtime.call("push", profileTable("x", "h")).toboolean())
    }
}

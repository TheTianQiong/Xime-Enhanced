-- S3 剪贴板同步插件（Lua 脚本插件）
--
-- 通过 S3 兼容对象存储上的单个 JSON 对象实现剪贴板多设备双向同步。
-- 兼容 AWS S3 / Cloudflare R2 / MinIO / Backblaze B2 等 S3 兼容服务。
--
-- 职责划分：
--   Lua   = 协议逻辑（SigV4 签名、PUT/GET、ETag 条件拉取、Profile JSON）
--   宿主  = 同步引擎（轮询/去重/回声抑制）+ 通用原语：
--     host.http        请求原语（PUT/GET，域名经用户授权）
--     host.crypto      SigV4 原语（sha256/hmacSha256/hex/utcTime）
--     host.json        JSON 编解码
--     host.config      配置存储（含 ETag 缓存）
--
-- 对象地址：
--   path-style      {endpoint}/{bucket}/{objectKey}      （推荐：MinIO/R2/B2 及自建）
--   virtual-hosted  https://{bucket}.{host}/{objectKey}  （AWS 默认）
-- 认证：AWS Signature Version 4（Authorization 头签名，签名头集合随请求动态生成）

local plugin = {}

local KEY_ENDPOINT = "endpoint"
local KEY_REGION = "region"
local KEY_BUCKET = "bucket"
local KEY_ACCESS_KEY = "accessKeyId"
local KEY_SECRET_KEY = "secretAccessKey"
local KEY_OBJECT_KEY = "objectKey"
local KEY_PATH_STYLE = "pathStyle"
local KEY_SESSION_TOKEN = "sessionToken"
local KEY_LAST_ETAG = "lastEtag"

local DEFAULT_OBJECT_KEY = "xime/clipboard/current.json"
local ALGORITHM = "AWS4-HMAC-SHA256"
local SERVICE = "s3"

-- ================= 工具函数 =================

local function trim(s)
    return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

-- 读取配置项：nil / 空串一律回退默认值
local function configOr(key, fallback)
    local v = host.config.get(key)
    if v == nil then return fallback end
    v = trim(v)
    if v == "" then return fallback end
    return v
end

-- RFC3986 编码：非保留字符（A-Za-z0-9-_.~）不编码。
-- encodeSlash=false 时保留 '/'，用于 CanonicalURI 与请求路径。
local function uriEncode(s, encodeSlash)
    local out = {}
    for i = 1, #s do
        local c = s:sub(i, i)
        local b = string.byte(c)
        if (b >= 65 and b <= 90) or (b >= 97 and b <= 122) or (b >= 48 and b <= 57)
            or c == "-" or c == "_" or c == "." or c == "~" then
            out[#out + 1] = c
        elseif c == "/" and not encodeSlash then
            out[#out + 1] = c
        else
            out[#out + 1] = string.format("%%%02X", b)
        end
    end
    return table.concat(out)
end

local function isPathStyle()
    return configOr(KEY_PATH_STYLE, "path") ~= "virtual"
end

-- 解析对象地址，返回 { url, host, canonicalPath }；未配置完整时返回 nil
local function objectUrl()
    local endpoint = configOr(KEY_ENDPOINT, "")
    local bucket = configOr(KEY_BUCKET, "")
    if endpoint == "" or bucket == "" then return nil end

    local scheme, authority = endpoint:match("^(https?)://(.+)$")
    if scheme == nil then return nil end
    authority = authority:gsub("/+$", "")

    -- 允许 endpoint 携带子路径（如反向代理到 MinIO：https://host/storage）
    local basePath = ""
    local slash = authority:find("/")
    if slash ~= nil then
        basePath = authority:sub(slash):gsub("/+$", "")
        authority = authority:sub(1, slash - 1)
    end

    local objectKey = configOr(KEY_OBJECT_KEY, DEFAULT_OBJECT_KEY):gsub("^/+", "")
    local hostHeader, rawPath
    if isPathStyle() then
        hostHeader = authority
        rawPath = basePath .. "/" .. bucket .. "/" .. objectKey
    else
        hostHeader = bucket .. "." .. authority
        rawPath = basePath .. "/" .. objectKey
    end
    if rawPath:sub(1, 1) ~= "/" then rawPath = "/" .. rawPath end

    local canonicalPath = uriEncode(rawPath, false)
    return {
        url = scheme .. "://" .. hostHeader .. canonicalPath,
        host = hostHeader,
        canonicalPath = canonicalPath,
    }
end

-- 从 S3 错误 XML 中提取 <Code>xxx</Code>，便于定位 SignatureDoesNotMatch 之类问题
local function s3ErrorHint(resp)
    if resp == nil or resp.text == nil then return "" end
    local code = resp.text:match("<Code>([^<]+)</Code>")
    if code == nil then return "" end
    return "（" .. code .. "）"
end

-- 构造 SigV4 签名头，返回可直接交给 host.http.request 的 headers 表。
-- extra 为额外参与签名的头（如 { ["If-None-Match"] = etag }），可为 nil。
-- 说明：Content-Type 只发送不参与签名（SigV4 仅要求 host 与 x-amz-* 必须签名），
-- 使签名与请求体类型解耦。
local function signedHeaders(method, hostHeader, canonicalPath, payload, contentType, extra)
    local amzDate = host.crypto.utcTime("YYYYMMDDTHHMMSSZ")
    local dateStamp = host.crypto.utcTime("YYYYMMDD")
    local payloadHash = host.crypto.hex(host.crypto.sha256(payload or ""))

    -- 参与签名的头集合（排序后逐行拼进 CanonicalHeaders；未签名的头不参与）
    local entries = {
        { "host", hostHeader },
        { "x-amz-content-sha256", payloadHash },
        { "x-amz-date", amzDate },
    }
    local token = configOr(KEY_SESSION_TOKEN, "")
    if token ~= "" then
        entries[#entries + 1] = { "x-amz-security-token", token }
    end
    if extra ~= nil then
        for k, v in pairs(extra) do
            entries[#entries + 1] = { k:lower(), v }
        end
    end
    table.sort(entries, function(a, b) return a[1] < b[1] end)

    local canonicalHeaders = ""
    local names = {}
    for _, h in ipairs(entries) do
        canonicalHeaders = canonicalHeaders .. h[1] .. ":" .. h[2] .. "\n"
        names[#names + 1] = h[1]
    end
    local signedNames = table.concat(names, ";")

    -- CanonicalRequest：CanonicalHeaders 末尾已带 \n，其后还需一个空行分隔
    local canonicalRequest = method .. "\n" .. canonicalPath .. "\n" .. "\n"
        .. canonicalHeaders .. "\n" .. signedNames .. "\n" .. payloadHash

    local region = configOr(KEY_REGION, "us-east-1")
    local scope = dateStamp .. "/" .. region .. "/" .. SERVICE .. "/aws4_request"
    local stringToSign = ALGORITHM .. "\n" .. amzDate .. "\n" .. scope .. "\n"
        .. host.crypto.hex(host.crypto.sha256(canonicalRequest))

    -- 派生签名密钥：kDate → kRegion → kService → kSigning
    local secretKey = configOr(KEY_SECRET_KEY, "")
    local kDate = host.crypto.hmacSha256("AWS4" .. secretKey, dateStamp)
    local kRegion = host.crypto.hmacSha256(kDate, region)
    local kService = host.crypto.hmacSha256(kRegion, SERVICE)
    local kSigning = host.crypto.hmacSha256(kService, "aws4_request")
    local signature = host.crypto.hex(host.crypto.hmacSha256(kSigning, stringToSign))

    local accessKey = configOr(KEY_ACCESS_KEY, "")
    local headers = {
        ["Host"] = hostHeader,
        ["x-amz-content-sha256"] = payloadHash,
        ["x-amz-date"] = amzDate,
        ["Authorization"] = ALGORITHM .. " Credential=" .. accessKey .. "/" .. scope
            .. ", SignedHeaders=" .. signedNames .. ", Signature=" .. signature,
    }
    if contentType ~= nil then headers["Content-Type"] = contentType end
    if token ~= "" then headers["x-amz-security-token"] = token end
    if extra ~= nil then
        for k, v in pairs(extra) do headers[k] = v end
    end
    return headers
end

local function cacheEtag(resp)
    if resp == nil or resp.headers == nil then return end
    local etag = resp.headers["ETag"]
    if etag == nil then etag = resp.headers["etag"] end
    if etag ~= nil and etag ~= "" then
        host.config.set(KEY_LAST_ETAG, etag)
    end
end

-- ================= 配置 schema（与 manifest 一致） =================

function plugin.getSettingsSchema()
    return {
        {
            key = KEY_ENDPOINT,
            label = "服务地址（Endpoint）",
            type = "text",
            placeholder = "https://s3.us-east-1.amazonaws.com",
            helpText = "S3 兼容服务地址。AWS：https://s3.<区域>.amazonaws.com；"
                .. "R2：https://<账户ID>.r2.cloudflarestorage.com；MinIO：http://<主机>:9000",
        },
        {
            key = KEY_REGION,
            label = "区域（Region）",
            type = "text",
            placeholder = "us-east-1",
            helpText = "AWS 填实际区域（如 us-east-1、ap-northeast-1）；Cloudflare R2 填 auto",
        },
        {
            key = KEY_BUCKET,
            label = "存储桶（Bucket）",
            type = "text",
            placeholder = "my-bucket",
        },
        {
            key = KEY_ACCESS_KEY,
            label = "Access Key ID",
            type = "text",
        },
        {
            key = KEY_SECRET_KEY,
            label = "Secret Access Key",
            type = "secret",
        },
        {
            key = KEY_OBJECT_KEY,
            label = "对象路径",
            type = "text",
            placeholder = DEFAULT_OBJECT_KEY,
            required = false,
            helpText = "剪贴板对象在桶内的键名（留空为 " .. DEFAULT_OBJECT_KEY .. "）",
        },
        {
            key = KEY_PATH_STYLE,
            label = "寻址方式",
            type = "select",
            options = { "path", "virtual" },
            helpText = "path：路径式（兼容 MinIO/R2/B2 与自建，推荐）；virtual：虚拟主机式（AWS 默认）",
        },
        {
            key = KEY_SESSION_TOKEN,
            label = "会话令牌（可选）",
            type = "secret",
            required = false,
            helpText = "仅使用 STS 临时凭证时需要填写",
        },
        {
            key = "testConnection",
            label = "测试连接",
            type = "button",
            required = false,
        },
    }
end

-- ================= 生命周期 =================

function plugin.onLoad()
    return true
end

function plugin.onUnload()
    return true
end

-- ================= 同步接口 =================

-- 推送本地 profile 到远端对象（宿主剪贴板变化时调用）
function plugin.push(profile)
    local u = objectUrl()
    if u == nil then
        host.logError("push failed: 未配置服务地址或存储桶")
        return false
    end
    local body = host.json.encode(profile)
    if body == nil then
        host.logError("push failed: Profile JSON 序列化失败")
        return false
    end
    local headers = signedHeaders("PUT", u.host, u.canonicalPath, body, "application/json", nil)
    local resp = host.http.request("PUT", u.url, headers, body)
    if resp == nil then
        host.logError("push failed: 请求失败 " .. (host.http.lastError() or ""))
        return false
    end
    if resp.status >= 200 and resp.status < 300 then
        cacheEtag(resp)
        host.log("push ok: PUT " .. u.url .. " -> " .. resp.status)
        return true
    end
    host.logError("push failed: PUT " .. u.url .. " -> HTTP " .. resp.status .. s3ErrorHint(resp))
    return false
end

-- 拉取远端 profile（宿主轮询调用）；无变更/无文件返回 nil
function plugin.pull()
    local u = objectUrl()
    if u == nil then
        host.log("pull: 未配置服务地址或存储桶")
        return nil
    end
    -- ETag 条件拉取：未变更时服务端返回 304，省流量
    local extra = nil
    local etag = host.config.get(KEY_LAST_ETAG)
    if etag ~= nil and etag ~= "" then
        extra = { ["If-None-Match"] = etag }
    end
    local headers = signedHeaders("GET", u.host, u.canonicalPath, "", nil, extra)
    local resp = host.http.request("GET", u.url, headers, nil)
    if resp == nil then
        host.logError("pull failed: 请求失败 " .. (host.http.lastError() or ""))
        return nil
    end
    if resp.status == 304 then
        return nil
    end
    if resp.status == 404 then
        -- 远端尚无对象，视为无变更
        return nil
    end
    if resp.status >= 200 and resp.status < 300 then
        cacheEtag(resp)
        local text = resp.text
        if text == nil or text == "" then
            host.log("pull: 远端对象为空")
            return nil
        end
        local decoded = host.json.decode(text)
        if decoded ~= nil and type(decoded) == "table" and decoded.text ~= nil then
            -- JSON Profile（与 ximed Profile 同构）
            return decoded
        end
        -- 远端为纯文本：构造 profile，hash 留空让宿主计算
        host.log("pull: 远端非 JSON，按纯文本兼容处理")
        return {
            type = "text",
            hash = "",
            text = text,
            has_data = false,
            data_name = nil,
            size = #text,
            source = nil,
        }
    end
    host.logError("pull failed: GET " .. u.url .. " -> HTTP " .. resp.status .. s3ErrorHint(resp))
    return nil
end

-- 校验配置可用性（连接测试）；返回错误消息，nil 表示成功
function plugin.testConnection()
    local u = objectUrl()
    if u == nil then return "未配置服务地址（Endpoint）或存储桶（Bucket）" end
    if configOr(KEY_ACCESS_KEY, "") == "" then return "未配置 Access Key ID" end
    if configOr(KEY_SECRET_KEY, "") == "" then return "未配置 Secret Access Key" end

    local headers = signedHeaders("GET", u.host, u.canonicalPath, "", nil, nil)
    local resp = host.http.request("GET", u.url, headers, nil)
    if resp == nil then
        return host.http.lastError() or "连接失败"
    end
    if resp.status >= 200 and resp.status < 300 then
        return nil
    end
    -- 404：对象尚未创建（但签名已被服务端接受；签名错误会返回 403）
    if resp.status == 404 then
        return nil
    end
    if resp.status == 403 then
        return "认证失败（HTTP 403）" .. s3ErrorHint(resp)
            .. "：请检查 Access Key / Secret Key / 区域与时钟"
    end
    if resp.status == 301 or resp.status == 307 then
        return "区域不匹配（HTTP " .. resp.status .. "）：请填写存储桶实际所在区域"
    end
    return "连接失败（HTTP " .. resp.status .. "）" .. s3ErrorHint(resp)
end

return plugin

-- AI 翻译插件（Lua 脚本插件，SSE 流式）
--
-- 职责划分：
--   Lua   = prompt 模板组装（{context}/{targetLang}）+ 发起流式请求（host.http.stream）+ 累积译文
--   宿主  = 通用工具面板 + 结果交互自适应（1 条自动上屏替换选区 / 多条候选选择）
--     host.http.stream   SSE 流式（异步回调 onData/onDone/onError）
--     host.json          JSON 编解码
--     host.config        配置存储

local plugin = {}

local KEY_API_KEY = "apiKey"
local KEY_BASE_URL = "baseUrl"
local KEY_MODEL = "model"
local KEY_TARGET_LANG = "targetLang"
local KEY_PROMPT = "prompt"

local DEFAULTS = {
  baseUrl = "https://api.openai.com/v1",
  model = "gpt-4o-mini",
  targetLang = "简体中文",
  prompt = "你是专业翻译。请把下面的内容翻译成{targetLang}，只输出译文，不要解释、不要引号。\n{context}",
}

local lastContext = ""
local buffer = ""
local generating = false
local sessionId = -1

-- ================= 配置 schema（与 manifest 一致，插件中心表单数据源） =================

function plugin.getSettingsSchema()
  return {
    {
      key = KEY_API_KEY,
      label = "API Key",
      type = "secret",
      placeholder = "输入 LLM API Key",
      helpText = "OpenAI 兼容接口的 API Key",
    },
    {
      key = KEY_BASE_URL,
      label = "接口地址",
      type = "text",
      defaultValue = DEFAULTS.baseUrl,
      helpText = "OpenAI 兼容接口地址（/chat/completions 前缀），域名将自动获得联网授权",
    },
    {
      key = KEY_MODEL,
      label = "模型",
      type = "text",
      defaultValue = DEFAULTS.model,
    },
    {
      key = KEY_TARGET_LANG,
      label = "目标语言",
      type = "text",
      defaultValue = DEFAULTS.targetLang,
      helpText = "翻译目标语言（如 简体中文 / English / 日本語）",
    },
    {
      key = KEY_PROMPT,
      label = "翻译模板",
      type = "textarea",
      defaultValue = DEFAULTS.prompt,
      helpText = "翻译 prompt 模板，{context} 替换为待翻译文本，{targetLang} 替换为目标语言",
    },
  }
end

local function buildItems()
  if buffer == "" then return {} end
  return { { id = "result", text = buffer } }
end

function plugin.getPanelState(inputText)
  return {
    inputText = inputText,
    items = buildItems(),
    loading = generating,
  }
end

function plugin.onPanelInput(text)
  lastContext = text or ""
end

function plugin.onPanelAction(actionId)
  if actionId ~= "generate" then return end
  local context = lastContext
  if context == "" then
    host.logError("请先输入待翻译内容")
    return
  end
  if generating then return end

  local apiKey = host.config.get(KEY_API_KEY) or ""
  if apiKey == "" then
    host.logError("AI 翻译未配置 API Key")
    return
  end

  buffer = ""
  generating = true

  local baseUrl = host.config.get(KEY_BASE_URL) or DEFAULTS.baseUrl
  baseUrl = baseUrl:gsub("/+$", "")
  local model = host.config.get(KEY_MODEL) or DEFAULTS.model
  local targetLang = host.config.get(KEY_TARGET_LANG) or DEFAULTS.targetLang
  local prompt = (host.config.get(KEY_PROMPT) or DEFAULTS.prompt)
  prompt = string.gsub(prompt, "{context}", context)
  prompt = string.gsub(prompt, "{targetLang}", targetLang)

  local body = host.json.encode({
    model = model,
    messages = {
      { role = "system", content = "你是专业翻译，只输出译文。" },
      { role = "user", content = prompt },
    },
    temperature = 0.3,
    stream = true,
    -- qwen3 等推理模型默认把输出放进 reasoning_content（content 为空），
    -- 关闭思考模式让译文直接走 content；非推理模型（如 gpt-4o-mini）会忽略此字段
    enable_thinking = false,
  })
  local headers = {
    ["Content-Type"] = "application/json",
    ["Authorization"] = "Bearer " .. apiKey,
  }
  local url = baseUrl .. "/chat/completions"

  sessionId = host.http.stream(url, headers, {
    onData = function(text)
      if text == nil or text == "" or text == "[DONE]" then return end
      local data = host.json.decode(text)
      if data ~= nil and data.choices ~= nil and #data.choices > 0 then
        local delta = data.choices[1].delta
        if delta ~= nil and delta.content ~= nil then
          buffer = buffer .. delta.content
        end
      end
    end,
    onDone = function(fullText)
      if fullText ~= nil and fullText ~= "" and buffer == "" then
        buffer = fullText
      end
      generating = false
      sessionId = -1
    end,
    onError = function(message)
      host.logError("AI 流式请求失败: " .. (message or "未知错误"))
      generating = false
      sessionId = -1
    end,
  }, 0, "POST", body)
  if sessionId < 0 then
    generating = false
    host.logError("AI 流式连接被拒绝: " .. (host.http.lastError() or "未知错误"))
  end
end

function plugin.onPanelItemClick(itemId)
  -- 上屏由宿主完成（选区替换）
end

return plugin
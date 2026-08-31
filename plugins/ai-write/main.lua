-- AI 帮写插件（Lua 脚本插件，SSE 流式）
--
-- 职责划分：
--   Lua   = prompt 模板组装 + 发起流式请求（host.http.stream）+ 累积增量文本 + 停止
--   宿主  = 通用工具面板（输入框/候选渲染/选区替换上屏）+ 通用原语：
--     host.http.stream   SSE 流式（异步回调 onData/onDone/onError）
--     host.http.closeStream  主动中断流
--     host.json          JSON 编解码
--     host.config        配置存储
--
-- 工具面板契约（host 调用）：
--   getPanelState(inputText)  返回面板状态 { inputText, items, actions, loading }
--   onPanelInput(text)        输入变化
--   onPanelAction(actionId)   generate 发起流式生成 / stop 中断
--   onPanelItemClick(itemId)  点候选（上屏由宿主完成）

local plugin = {}

local KEY_API_KEY = "apiKey"
local KEY_BASE_URL = "baseUrl"
local KEY_MODEL = "model"
local KEY_PROMPT = "prompt"

local DEFAULTS = {
  baseUrl = "https://api.openai.com/v1",
  model = "gpt-4o-mini",
  prompt = "你是我的写作助手。请根据以下上下文与要求写一段通顺的中文文字：\n{context}",
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
      key = KEY_PROMPT,
      label = "写作模板",
      type = "textarea",
      defaultValue = DEFAULTS.prompt,
      helpText = "写作 prompt 模板，{context} 会被替换为面板输入内容",
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
  if actionId == "stop" then
    if sessionId >= 0 then
      host.http.closeStream(sessionId)
      sessionId = -1
    end
    generating = false
    return
  end
  if actionId ~= "generate" then return end
  if generating then return end

  local context = lastContext
  if context == "" then
    host.logError("请先输入写作要求")
    return
  end
  local apiKey = host.config.get(KEY_API_KEY) or ""
  if apiKey == "" then
    host.logError("AI 帮写未配置 API Key")
    return
  end

  buffer = ""
  generating = true

  local baseUrl = host.config.get(KEY_BASE_URL) or DEFAULTS.baseUrl
  baseUrl = baseUrl:gsub("/+$", "")
  local model = host.config.get(KEY_MODEL) or DEFAULTS.model
  local prompt = (host.config.get(KEY_PROMPT) or DEFAULTS.prompt)
  prompt = string.gsub(prompt, "{context}", context)

  local body = host.json.encode({
    model = model,
    messages = {
      { role = "system", content = "你是写作助手，直接输出正文，不要解释。" },
      { role = "user", content = prompt },
    },
    stream = true,
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
  -- 上屏由宿主完成
end

return plugin
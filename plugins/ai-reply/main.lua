-- AI 智能回复插件（Lua 脚本插件，同步全链路）
--
-- 职责划分：
--   Lua   = prompt 模板组装 + 调用 LLM（host.http.request）+ 解析候选列表
--   宿主  = 全屏纯展示面板（InfoPanel：ui 节点树 + items 候选点选上屏）+ 通用原语：
--     host.http.request  同步 HTTP（第 5 参 timeoutMillis 覆盖长生成超时）
--     host.json          JSON 编解码
--     host.config        配置存储
--
-- 工具面板契约（host 调用，display: passive）：
--   getPanelState(inputText)  返回面板状态 { items, ui, loading }（入参为宿主收集的上下文）
--   onPanelInput(text)        输入变化（passive 无输入框，保留兼容）
--   onPanelAction(actionId)   ui 节点 action 点击（generate = 调用 LLM）
--   onPanelItemClick(itemId)  点候选（上屏由宿主完成）

local plugin = {}

local KEY_API_KEY = "apiKey"
local KEY_BASE_URL = "baseUrl"
local KEY_MODEL = "model"
local KEY_PROMPT = "prompt"

local DEFAULTS = {
  baseUrl = "https://api.openai.com/v1",
  model = "gpt-4o-mini",
  prompt = "你是我的智能回复助手。请根据对方消息生成 3~5 条简洁、自然、符合我语气的中文回复候选，每条不超过 30 字。严格按以下格式输出：只输出一个 JSON 对象，格式为 {\"candidates\": [\"好的呀\", \"马上来\", \"稍等片刻\"]}，candidates 是候选文本数组，不要编号、不要解释、不要任何其他文字。对方消息：{context}",
}

local lastContext = ""
local lastError = ""
local cachedItems = {}
local generating = false

-- ================= 工具函数 =================

local function trim(s)
  return (s:gsub("^%s+", ""):gsub("%s+$", ""))
end

-- 把 LLM 输出的多行文本拆成候选列表（去除编号/引号前缀，JSON 解析失败的兜底）
local function splitCandidates(text)
  local lines = {}
  for line in text:gmatch("[^\n]+") do
    local t = trim(line)
    t = t:gsub("^%s*[-%d%.%)%]%s]*", ""):gsub("^[\"']", ""):gsub("[\"']$", "")
    t = trim(t)
    if t ~= "" then lines[#lines + 1] = t end
  end
  return lines
end

-- 从模型输出中提取 JSON（剥离 ```json 代码块包裹，取首个 { 或 [ 到末尾对应括号的区间）
local function extractJson(text)
  local t = text:gsub("```[%w]*", ""):gsub("```", "")
  t = trim(t)
  local startIdx = t:find("[{%[]")
  if startIdx == nil then return nil end
  local endIdx = t:reverse():find("[}%]]")
  if endIdx == nil then return nil end
  local lastIdx = #t - endIdx + 1
  if lastIdx <= startIdx then return nil end
  return t:sub(startIdx, lastIdx)
end

-- 解析候选：优先 JSON 对象（prompt 已要求 {"candidates": [...]}），兼容直接数组，失败时按行拆分兜底
local function parseCandidates(content)
  local items = {}
  local json = extractJson(content or "")
  if json ~= nil then
    local data = host.json.decode(json)
    local list = data
    if type(data) == "table" and data.candidates ~= nil then
      list = data.candidates
    end
    if type(list) == "table" then
      for _, v in ipairs(list) do
        if type(v) == "string" then
          local t = trim(v)
          if t ~= "" then items[#items + 1] = { id = tostring(#items + 1), text = t } end
        end
      end
    end
    if #items > 0 then return items end
  end
  for _, line in ipairs(splitCandidates(content or "")) do
    items[#items + 1] = { id = tostring(#items + 1), text = line }
  end
  return items
end

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
      label = "回复模板",
      type = "textarea",
      defaultValue = DEFAULTS.prompt,
      helpText = "生成候选的 prompt 模板，{context} 会被替换为对方消息",
    },
  }
end

-- ================= 工具面板契约 =================

-- ui 节点树（白名单 type：section/text/metric/divider/action）：
--   未生成 → 对方消息预览 + "生成回复"按钮；失败 → 错误提示 + 重试按钮；
--   生成中 → 空 ui（loading 字段驱动宿主显示加载态）；有候选 → 提示文字（items 由宿主渲染）
local function buildUi()
  local ui = {}
  if #cachedItems > 0 then
    ui[#ui + 1] = { type = "section", title = "回复候选" }
    ui[#ui + 1] = { type = "text", content = "点击候选直接上屏", style = "caption" }
  elseif generating then
    -- loading 态：宿主显示"加载中..."
  else
    ui[#ui + 1] = { type = "section", title = "对方消息" }
    if lastContext ~= "" then
      ui[#ui + 1] = { type = "text", content = lastContext }
    else
      ui[#ui + 1] = { type = "text", content = "暂无上下文：先复制对方消息，再从工具栏打开本面板", style = "caption" }
    end
    if lastError ~= "" then
      ui[#ui + 1] = { type = "text", content = lastError, style = "caption" }
    end
    ui[#ui + 1] = { type = "action", label = lastError ~= "" and "重新生成" or "生成回复", actionId = "generate" }
  end
  return ui
end

function plugin.getPanelState(inputText)
  -- openToolPanel 时宿主传入收集的上下文（选区 > 输入框 > 剪贴板）；
  -- action 点击后的单次重拉传空串，不覆盖已有上下文
  if inputText ~= nil and trim(inputText) ~= "" then
    lastContext = trim(inputText)
  end
  return {
    items = cachedItems,
    loading = generating,
    ui = buildUi(),
  }
end

function plugin.onPanelInput(text)
  lastContext = text or ""
end

function plugin.onPanelAction(actionId)
  if actionId ~= "generate" then return end
  local context = trim(lastContext)
  if context == "" then
    lastError = "请先复制对方消息再打开本面板"
    return
  end
  if generating then return end

  local apiKey = host.config.get(KEY_API_KEY) or ""
  if apiKey == "" then
    lastError = "未配置 API Key，请到插件中心配置"
    return
  end

  generating = true
  lastError = ""
  cachedItems = {}

  local baseUrl = host.config.get(KEY_BASE_URL) or DEFAULTS.baseUrl
  baseUrl = baseUrl:gsub("/+$", "")
  local model = host.config.get(KEY_MODEL) or DEFAULTS.model
  local prompt = (host.config.get(KEY_PROMPT) or DEFAULTS.prompt)
  prompt = string.gsub(prompt, "{context}", context)

  local body = host.json.encode({
    model = model,
    messages = {
      { role = "system", content = "你是智能回复助手。严格按格式输出：只输出一个 JSON 对象，格式为 {\"candidates\": [\"回复一\", \"回复二\"]}，不要任何其他文字。" },
      { role = "user", content = prompt },
    },
    temperature = 0.8,
  })
  local headers = {
    ["Content-Type"] = "application/json",
    ["Authorization"] = "Bearer " .. apiKey,
  }
  local url = baseUrl .. "/chat/completions"

  local resp = host.http.request("POST", url, headers, body, 60000)
  if resp == nil then
    lastError = "AI 请求失败: " .. (host.http.lastError() or "未知错误")
    host.logError(lastError)
    generating = false
    return
  end
  if resp.status ~= 200 then
    lastError = "AI 接口返回 " .. tostring(resp.status)
    host.logError(lastError .. ": " .. resp.text)
    generating = false
    return
  end

  local data = host.json.decode(resp.text)
  local items = {}
  if data ~= nil and data.choices ~= nil then
    for _, choice in ipairs(data.choices) do
      local content = choice.message and choice.message.content or ""
      for _, item in ipairs(parseCandidates(content)) do
        items[#items + 1] = item
      end
    end
  end
  if #items == 0 then
    lastError = "未解析到候选，请重试"
  end
  cachedItems = items
  generating = false
end

function plugin.onPanelItemClick(itemId)
  -- 上屏由宿主完成，插件无需处理
end

return plugin
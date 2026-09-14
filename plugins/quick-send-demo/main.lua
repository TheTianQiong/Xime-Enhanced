-- 快捷发送示例插件：把宿主快捷发送内容（Room 数据，含触发编码 code）匹配进候选栏。
--
-- 数据流：
--   onLoad           首次拉取 host.quickSend.list() 到内存缓存
--   quick_send_changed  宿主推送变更事件（conflated 只保最新）→ 刷新缓存
--   transformCandidates 纯内存匹配缓存（hotPath 硬超时 15ms，禁止网络/文件 IO）
--
-- 匹配与排序规则：
--   1. 内容命中：快捷条目 text 以某个引擎候选词开头（如候选"电话"、条目"电话号码：135..."）
--      → 该条目紧跟在该候选词之后
--   2. 编码命中：用户输入编码是条目 code 的前缀（code 非空，如输入 dh/d 命中 code=dh）
--      → 条目插到"引擎第一候选之后"（即候选列表第二候选位；引擎无候选则放第一）
--   3. 去重：同一条目只出现一次（内容命中优先于编码命中的位置）
-- 未命中返回 nil（不干预）。

local plugin = {}

local quickSends = {}  -- host.quickSend.list() 的缓存 { {id,text,code,timestamp,isPinned}, ... }

local function refresh()
  if host.quickSend == nil then return end
  quickSends = host.quickSend.list() or {}
end

function plugin.onLoad()
  refresh()
end

function plugin.onPluginEvent(eventType, payload)
  if eventType == "quick_send_changed" then
    refresh()
  end
end

-- 快捷条目的候选注释：有 code 显示 code（提示触发码），否则"快捷"
local function commentOf(q)
  local code = (q.code or ""):gsub("%s", "")
  if code ~= "" then return code end
  return "快捷"
end

function plugin.transformCandidates(request)
  local input = request.input_text or ""
  local engineCands = request.candidates or {}
  if input == "" or #quickSends == 0 then return nil end

  local result = {}
  local inserted = {}      -- 按 id 去重
  local firstEnginePos = nil  -- result 中第一个引擎候选项的最终位置

  -- 1. 引擎候选按序保留；每条候选后跟随 text 以其开头的快捷条目
  for i, c in ipairs(engineCands) do
    table.insert(result, { engine_index = i - 1 })
    if firstEnginePos == nil then firstEnginePos = #result end
    local ctext = c.text or ""
    for _, q in ipairs(quickSends) do
      local text = q.text or ""
      if not inserted[q.id] and text ~= "" and ctext ~= "" and text:sub(1, #ctext) == ctext then
        table.insert(result, { text = text, comment = commentOf(q) })
        inserted[q.id] = true
      end
    end
  end

  -- 2. 编码命中：输入编码是 code 前缀（code 非空，未插入过）
  local codeHits = {}
  for _, q in ipairs(quickSends) do
    local code = (q.code or ""):gsub("%s", "")
    if not inserted[q.id] and code ~= "" and input:sub(1, #code) == code then
      table.insert(codeHits, q)
    end
  end
  if #codeHits > 0 then
    local insertAt = firstEnginePos == nil and 1 or (firstEnginePos + 1)
    local tail = {}
    for k = insertAt, #result do tail[#tail + 1] = result[k] end
    for k = #result, insertAt, -1 do result[k] = nil end
    for _, q in ipairs(codeHits) do
      table.insert(result, { text = q.text, comment = commentOf(q) })
    end
    for _, t in ipairs(tail) do result[#result + 1] = t end
  end

  if #result == 0 then return nil end
  return { candidates = result }
end

return plugin
-- bibi(asr-keyboard) AIDL 桥 语音识别插件
--
-- 职责划分：
--   Lua   = 会话状态机、host.ipc 调度、错误码映射、结果上报
--   宿主  = 录音（Xime 固定 16kHz/mono/PCM16LE，100ms/帧）+ host.ipc AIDL 桥原语
--   说点啥 = 实际识别引擎（外部调用跟随说点啥内部设置，本插件不选择供应商）
--
-- 数据流：
--   Xime 录音线程 → plugin.processAudioChunk(pcm) → host.ipc.writePcm(sessionId, pcm, 16000, 1)
--   → 说点啥识别 → onPartial/onFinal 回调 → host.asr.emitPartial/emitFinal → Xime 上屏
--
-- 依赖宿主已集成 host.ipc（IpcHostApi 桥），否则 host.ipc 为 nil，initialize 会失败。

local plugin = {}

local SAMPLE_RATE = 16000
local CHANNELS = 1

-- startPcmSession 错误码 → 用户提示（-2/-3/-5 为说点啥返回；-100~-102 为宿主本地错误）
local ERROR_MSGS = {
    [-2]   = "系统忙碌，请稍后再试",
    [-3]   = "请在说点啥中启用「外部输入法联动」",
    [-5]   = "当前识别供应商不支持外部推送 PCM",
    [-100] = "未检测到说点啥（asr-keyboard），请先安装",
    [-101] = "绑定说点啥服务超时",
    [-102] = "说点啥服务未连接",
}

local sessionId = nil
local currentState = 0 -- 0=IDLE 1=LISTENING 2=PROCESSING 3=ERROR（与 host.asr.emitState 一致）

-- ================= 元信息 =================

function plugin.getProviderId()
    return "asrkb"
end

function plugin.getDisplayName()
    return "bibi 语音输入（AIDL）"
end

function plugin.getIcon()
    return { text = "🎤" }
end

function plugin.getCapabilities()
    return {
        inputMode = "streaming",             -- 说点啥 onPartial 实时回显
        supportsPartialResults = true,
        maxRecordDurationMillis = 10 * 60 * 1000,
        requiresNetwork = false,             -- 识别在说点啥进程内，插件自身不联网
    }
end

function plugin.isConfigured()
    -- 无独立配置项：识别供应商与设置完全跟随说点啥内部配置
    return true
end

function plugin.getSettingsSchema()
    return {}
end

-- ================= 初始化 =================

function plugin.initialize()
    if host.ipc == nil then
        host.asr.emitError("当前宿主未集成 host.ipc 桥，无法联动说点啥")
        return false
    end
    -- 绑定说点啥外部语音服务（Pro → 开源顺序），并注册会话回调
    local ok = host.ipc.connect({
        onState = function(sid, state, message)
            currentState = state
            host.asr.emitState(state)   -- 0=IDLE 1=Recording 2=Processing 3=Error
        end,
        onPartial = function(sid, text)
            if text and text ~= "" then host.asr.emitPartial(text) end
        end,
        onFinal = function(sid, text)
            if text and text ~= "" then host.asr.emitFinal(text) end
            currentState = 0
        end,
        onError = function(sid, code, message)
            currentState = 3
            host.asr.emitError(message)
        end,
        onAmplitude = function(sid, amplitude) end,  -- 振幅回调（可选，供波形动画）
    })
    if not ok then
        host.asr.emitError(host.ipc.lastError() or "绑定说点啥服务失败")
        return false
    end
    return true
end

function plugin.onUnload()
    host.ipc.close()   -- 解绑并清理（插件卸载/停用时）
end

-- ================= 会话生命周期 =================

function plugin.start()
    -- 阻塞等待绑定完成（宿主 ≤5s 超时），返回 sessionId(>0) 或错误码
    sessionId = host.ipc.startPcmSession()
    if sessionId == nil or sessionId <= 0 then
        local msg = ERROR_MSGS[sessionId] or (host.ipc.lastError() or "启动识别失败")
        host.asr.emitError(msg)
        return false
    end
    return true
end

function plugin.processAudioChunk(pcm)
    if sessionId and sessionId > 0 then
        -- Xime 每帧 0.1s（3200B）；如需贴合说点啥建议的 ~200ms 一包，可在 Lua 侧攒两帧再推
        host.ipc.writePcm(sessionId, pcm, SAMPLE_RATE, CHANNELS)
    end
end

function plugin.stop()
    if sessionId and sessionId > 0 then
        host.ipc.finishPcm(sessionId)   -- 音频输入结束，等待最终结果
    end
    sessionId = nil
end

function plugin.cancel()
    if sessionId and sessionId > 0 then
        host.ipc.cancelSession(sessionId)
    end
    sessionId = nil
    currentState = 0
end

function plugin.getState()
    return currentState
end

return plugin

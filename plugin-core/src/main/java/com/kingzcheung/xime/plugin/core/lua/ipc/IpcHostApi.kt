package com.kingzcheung.xime.plugin.core.lua.ipc

/**
 * 宿主提供的通用 AIDL/Binder 桥 API（协议无关）。
 *
 * 设计原则（与 [com.kingzcheung.xime.plugin.core.lua.ws.WsHostApi] 一致）：
 * - 宿主只提供"绑定外部 AIDL 服务 + 推送 PCM + 会话控制"原语，**不含任何业务逻辑**
 * - 绑定目标由宿主固定（说点啥/asr-keyboard 的 `ExternalSpeechService`，Pro → 开源顺序尝试），
 *   插件无法自行指定任意组件名
 * - 会话语义跟随 bibi 的 push-PCM 模式：Xime 自身录音，PCM 由 [writePcm] 推送，
 *   识别结果经 [IpcHostListener] 回传（onPartial/onFinal/onError），由插件 Lua 决定如何上报宿主
 *
 * Lua 侧注入为 `host.ipc`：
 *   host.ipc.connect({onState,onPartial,onFinal,onError,onAmplitude})   -- 绑定（异步，返回是否发起）
 *   host.ipc.startPcmSession()     -- 阻塞等待绑定完成（≤5s），返回 sessionId(>0) 或错误码
 *   host.ipc.writePcm(sid, pcm, sampleRate, channels)
 *   host.ipc.finishPcm(sid)  /  host.ipc.cancelSession(sid)
 *   host.ipc.isRecording(sid) / isAnyRecording() / getVersion()
 *   host.ipc.getState()            -- 0=IDLE 1=CONNECTING 2=BOUND 3=CLOSED
 *   host.ipc.lastError()
 *   host.ipc.close()               -- 解绑并清理（插件卸载时调用）
 *
 * 错误码约定（[startPcmSession] 返回值）：
 * - `>0`：服务端生成的 sessionId
 * - `-2`：系统忙碌（已有会话录音）
 * - `-3`：功能未启用（说点啥未打开「外部输入法联动」）
 * - `-5`：当前识别供应商不支持推送 PCM
 * - `-100`~`-102`：宿主本地错误（见 [IpcHostApi] 常量）
 */
interface IpcHostApi {

    companion object {
        /** 说点啥未安装或服务不可见。 */
        const val ERR_NO_SERVICE = -100

        /** 绑定说点啥服务超时。 */
        const val ERR_BIND_TIMEOUT = -101

        /** 服务未连接（未发起绑定 / 服务已断开 / transact 失败）。 */
        const val ERR_NOT_BOUND = -102
    }

    /**
     * 绑定说点啥外部语音服务（Pro → 开源顺序），并注册事件监听。
     *
     * @param listener 会话事件回调（onPartial/onFinal/onError 等）
     * @return 是否成功发起绑定；`false` 时用 [lastError] 读取原因
     */
    fun connect(listener: IpcHostListener): Boolean

    /**
     * 启动推送 PCM 会话（阻塞等待绑定完成，超时约 5s）。
     *
     * @return 成功返回服务端生成的 `sessionId`（`>0`）；否则返回错误码（-2/-3/-5/-100~-102）
     */
    fun startPcmSession(): Int

    /** 推送一帧 PCM 音频数据（建议 PCM16LE / 16000Hz / mono）。 */
    fun writePcm(sessionId: Int, pcm: ByteArray, sampleRate: Int, channels: Int)

    /** 结束音频输入并进入处理阶段，等待最终结果（等价于 stopSession）。 */
    fun finishPcm(sessionId: Int)

    /** 取消并清理会话。 */
    fun cancelSession(sessionId: Int)

    /** 指定会话是否正在录音/输入中。 */
    fun isRecording(sessionId: Int): Boolean

    /** 是否存在任意活动会话。 */
    fun isAnyRecording(): Boolean

    /** 获取说点啥版本名（如 "1.6.0"）。 */
    fun getVersion(): String

    /** 绑定状态：0=IDLE 1=CONNECTING 2=BOUND 3=CLOSED。 */
    fun getState(): Int

    /** 最近一次拒绝/失败原因（connect 返回 false 或 startPcmSession 返回负数时读取）。 */
    fun lastError(): String?

    /** 解绑服务并清理资源（插件卸载时调用，幂等）。 */
    fun close()
}

/**
 * 说点啥 ISpeechCallback 事件回调（对应 AIDL 回调接口的五个方法）。
 */
interface IpcHostListener {
    fun onState(sessionId: Int, state: Int, message: String)
    fun onPartial(sessionId: Int, text: String)
    fun onFinal(sessionId: Int, text: String)
    fun onError(sessionId: Int, code: Int, message: String)
    fun onAmplitude(sessionId: Int, amplitude: Float)
}

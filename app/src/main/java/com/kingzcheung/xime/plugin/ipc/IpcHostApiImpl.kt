package com.kingzcheung.xime.plugin.ipc

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Binder
import android.os.IBinder
import android.os.Parcel
import android.util.Log
import com.kingzcheung.xime.plugin.core.lua.ipc.IpcHostApi
import com.kingzcheung.xime.plugin.core.lua.ipc.IpcHostListener
import com.kingzcheung.xime.util.FileLogger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * 说点啥/asr-keyboard 外部语音服务的 AIDL 桥实现。
 *
 * 参照 bibi（小企鹅输入法）参考实现 `AsrkbSpeechClient.kt` 的做法：
 * 使用**纯 Binder `transact` 调用**，不依赖 AIDL 生成类，事务码与接口描述符
 * 固定对齐 `com.brycewg.asrkb.aidl.IExternalSpeechService` / `ISpeechCallback`。
 *
 * 会话模式：**推送 PCM 模式**（startPcmSession → 循环 writePcm → finishPcm/cancelSession），
 * 录音由 Xime 宿主完成，服务端不做录音、不校验录音权限。
 *
 * 线程模型：
 * - [connect] 可任意线程调用（bindService 回调经 ContextImpl 落到主线程 Looper）
 * - [startPcmSession] 阻塞等待 onServiceConnected（CountDownLatch，≤5s），应在后台线程调用
 *   （Xime 的 RecordingThread 调用 plugin.start() 即满足）
 * - [writePcm] 同步 transact，每次 ~0.1ms 级，录音线程 100ms 一帧无压力
 */
class IpcHostApiImpl(
    private val context: Context,
    private val pluginId: String
) : IpcHostApi {

    companion object {
        private const val TAG = "IpcHostApi"

        // ---------- AIDL 接口描述符 ----------
        private const val IFACE = "com.brycewg.asrkb.aidl.IExternalSpeechService"
        private const val IFACE_CALLBACK = "com.brycewg.asrkb.aidl.ISpeechCallback"

        // 绑定包名顺序：Pro → 开源
        private const val PKG_PRO = "com.brycewg.asrkb.pro"
        private const val PKG_OPEN = "com.brycewg.asrkb"
        private const val SERVICE_CLS = "com.brycewg.asrkb.api.ExternalSpeechService"

        // ---------- IExternalSpeechService 事务码（FIRST_CALL_TRANSACTION + n） ----------
        private const val TRANS_START_SESSION = 1
        private const val TRANS_STOP_SESSION = 2
        private const val TRANS_CANCEL_SESSION = 3
        private const val TRANS_IS_RECORDING = 4
        private const val TRANS_IS_ANY_RECORDING = 5
        private const val TRANS_GET_VERSION = 6
        private const val TRANS_START_PCM = 7
        private const val TRANS_WRITE_PCM = 8
        private const val TRANS_FINISH_PCM = 9

        // ---------- ISpeechCallback 回调事务码 ----------
        private const val CB_STATE = 1
        private const val CB_PARTIAL = 2
        private const val CB_FINAL = 3
        private const val CB_ERROR = 4
        private const val CB_AMPLITUDE = 5

        // ---------- 状态 ----------
        private const val STATE_IDLE = 0
        private const val STATE_CONNECTING = 1
        private const val STATE_BOUND = 2
        private const val STATE_CLOSED = 3

        private const val BIND_TIMEOUT_MS = 5_000L

        /** finishPcm 阻塞等待最终结果的最大时长。 */
        private const val FINISH_TIMEOUT_MS = 5_000L
    }

    @Volatile
    private var service: IBinder? = null

    @Volatile
    private var bound = false

    @Volatile
    private var state = STATE_IDLE

    @Volatile
    private var lastErrorMsg: String? = null

    @Volatile
    private var listener: IpcHostListener? = null

    private val bindLatch = CountDownLatch(1)

    /** finishPcm 等待该会话最终结果时，回调到达后 countDown（消除 stop→release 竞态）。 */
    @Volatile
    private var pendingFinalLatch: CountDownLatch? = null

    /** 等待最终结果的目标会话 id。 */
    @Volatile
    private var pendingFinalSessionId = -1

    /** 已收到 onFinal/onError 的会话 id（供 finishPcm 前置判断，避免重复阻塞）。 */
    @Volatile
    private var finalReceivedSessionId = -1

    /** writePcm 成功调用计数（用于记录"音频开始流动"的里程碑）。 */
    @Volatile
    private var writePcmCount = 0

    private val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, binder: IBinder?) {
            Log.d(TAG, "[$pluginId] 已绑定说点啥服务 $name")
            FileLogger.i(TAG, "[$pluginId] 已绑定说点啥服务 $name")
            service = binder
            state = STATE_BOUND
            lastErrorMsg = null
            bindLatch.countDown()
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            Log.w(TAG, "[$pluginId] 说点啥服务已断开")
            service = null
            bound = false
            state = STATE_CLOSED
            bindLatch.countDown()
        }

        override fun onBindingDied(name: ComponentName?) {
            Log.w(TAG, "[$pluginId] 说点啥绑定进程死亡")
            service = null
            bound = false
            state = STATE_CLOSED
            bindLatch.countDown()
        }

        override fun onNullBinding(name: ComponentName?) {
            Log.w(TAG, "[$pluginId] 说点啥服务返回 null Binder")
            lastErrorMsg = "说点啥服务返回 null Binder"
            service = null
            bound = false
            state = STATE_CLOSED
            bindLatch.countDown()
        }
    }

    override fun connect(listener: IpcHostListener): Boolean {
        this.listener = listener
        if (bound && service != null) {
            // 已绑定：仅刷新监听，不重复绑定
            return true
        }
        lastErrorMsg = null

        val pkg = if (isPackageInstalled(PKG_PRO)) PKG_PRO else PKG_OPEN
        val intent = Intent().setComponent(ComponentName(pkg, SERVICE_CLS))
        val result = try {
            bound = context.bindService(intent, connection, Context.BIND_AUTO_CREATE)
            if (bound) {
                state = STATE_CONNECTING
                Log.d(TAG, "[$pluginId] 正在绑定说点啥服务（$pkg）")
                true
            } else {
                lastErrorMsg = "无法绑定说点啥服务：$pkg"
                bindLatch.countDown()
                false
            }
        } catch (e: Exception) {
            lastErrorMsg = "绑定说点啥服务异常: ${e.message}"
            Log.e(TAG, "[$pluginId] bindService failed", e)
            bindLatch.countDown()
            false
        }
        FileLogger.i(TAG, "[$pluginId] connect($pkg) -> bound=$result")
        return result
    }

    override fun startPcmSession(): Int {
        // 尚未绑定：等待 onServiceConnected（≤5s）
        if (service == null) {
            try {
                if (!bindLatch.await(BIND_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                    lastErrorMsg = "绑定说点啥服务超时"
                    return IpcHostApi.ERR_BIND_TIMEOUT
                }
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                lastErrorMsg = "等待绑定被中断"
                return IpcHostApi.ERR_BIND_TIMEOUT
            }
        }
        val svc = service ?: run {
            lastErrorMsg = "说点啥服务未连接"
            return IpcHostApi.ERR_NOT_BOUND
        }

        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        val result = try {
            data.writeInterfaceToken(IFACE)
            // SpeechConfig 传 null（服务端忽略全部配置，仅 vendorId=="mock" 特殊）
            data.writeInt(0)
            data.writeStrongBinder(callbackBinder)
            if (!svc.transact(TRANS_START_PCM, data, reply, 0)) {
                lastErrorMsg = "startPcmSession 事务失败"
                IpcHostApi.ERR_NOT_BOUND
            } else {
                reply.readException()
                reply.readInt()
            }
        } catch (e: Exception) {
            lastErrorMsg = "startPcmSession 异常: ${e.message}"
            Log.e(TAG, "[$pluginId] startPcmSession failed", e)
            IpcHostApi.ERR_NOT_BOUND
        } finally {
            data.recycle()
            reply.recycle()
        }
        if (result > 0) finalReceivedSessionId = -1
        FileLogger.i(TAG, "[$pluginId] startPcmSession -> $result")
        return result
    }

    override fun writePcm(sessionId: Int, pcm: ByteArray, sampleRate: Int, channels: Int) {
        val svc = service ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE)
            data.writeInt(sessionId)
            data.writeByteArray(pcm)
            data.writeInt(sampleRate)
            data.writeInt(channels)
            if (svc.transact(TRANS_WRITE_PCM, data, reply, 0)) {
                reply.readException()
                val n = ++writePcmCount
                if (n == 1) {
                    FileLogger.i(TAG, "[$pluginId] writePcm 首次成功(sid=$sessionId, ${pcm.size}B)")
                }
            } else {
                Log.w(TAG, "[$pluginId] writePcm transact 返回 false")
                FileLogger.w(TAG, "[$pluginId] writePcm transact 返回 false")
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] writePcm failed", e)
            FileLogger.w(TAG, "[$pluginId] writePcm failed: ${e.message}")
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun finishPcm(sessionId: Int) {
        sessionVoidTransact(TRANS_FINISH_PCM, sessionId)

        // 结果可能已在录音期间送达（如自动停），无需再等
        if (finalReceivedSessionId == sessionId) {
            FileLogger.i(TAG, "[$pluginId] finishPcm($sessionId): 结果已在会话期间送达")
            return
        }

        // 阻塞等待该会话的 onFinal/onError，确保结果在 Xime release() 清空回调前送达
        val latch = CountDownLatch(1)
        pendingFinalLatch = latch
        pendingFinalSessionId = sessionId
        val done = try {
            latch.await(FINISH_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
        pendingFinalLatch = null
        pendingFinalSessionId = -1
        if (!done) {
            val msg = "finishPcm($sessionId) 等待最终结果超时(${FINISH_TIMEOUT_MS}ms)"
            Log.w(TAG, "[$pluginId] $msg")
            FileLogger.w(TAG, "[$pluginId] $msg")
        }
    }

    override fun cancelSession(sessionId: Int) {
        sessionVoidTransact(TRANS_CANCEL_SESSION, sessionId)
    }

    override fun isRecording(sessionId: Int): Boolean {
        val svc = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            data.writeInt(sessionId)
            if (!svc.transact(TRANS_IS_RECORDING, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0  // writeBoolean 底层即 writeInt(0/1)，readInt 全版本兼容（minSdk 28）
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] isRecording failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun isAnyRecording(): Boolean {
        val svc = service ?: return false
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            if (!svc.transact(TRANS_IS_ANY_RECORDING, data, reply, 0)) return false
            reply.readException()
            reply.readInt() != 0
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] isAnyRecording failed", e)
            false
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun getVersion(): String {
        val svc = service ?: return ""
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        return try {
            data.writeInterfaceToken(IFACE)
            if (!svc.transact(TRANS_GET_VERSION, data, reply, 0)) return ""
            reply.readException()
            reply.readString() ?: ""
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] getVersion failed", e)
            ""
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    override fun getState(): Int = state

    override fun lastError(): String? = lastErrorMsg

    override fun close() {
        listener = null
        if (bound) {
            try {
                context.unbindService(connection)
            } catch (e: Exception) {
                Log.w(TAG, "[$pluginId] unbindService failed", e)
            }
        }
        service = null
        bound = false
        state = STATE_CLOSED
        bindLatch.countDown()
    }

    // ================= 内部 =================

    /**
     * 记录会话结果已到达，并唤醒 finishPcm 的等待（如等待中）。
     * bibi 的服务级回调可能带 sid=-1（如 403/401 错误），此时也视为当前会话结果。
     */
    private fun notifySessionResult(sid: Int) {
        finalReceivedSessionId = sid
        val latch = pendingFinalLatch
        if (latch != null && (sid == pendingFinalSessionId || sid <= 0)) {
            latch.countDown()
        }
    }

    private fun sessionVoidTransact(code: Int, sessionId: Int) {
        val svc = service ?: return
        val data = Parcel.obtain()
        val reply = Parcel.obtain()
        try {
            data.writeInterfaceToken(IFACE)
            data.writeInt(sessionId)
            if (svc.transact(code, data, reply, 0)) {
                reply.readException()
            }
        } catch (e: Exception) {
            Log.w(TAG, "[$pluginId] transact($code) failed", e)
        } finally {
            data.recycle()
            reply.recycle()
        }
    }

    private fun isPackageInstalled(pkg: String): Boolean {
        return try {
            context.packageManager.getPackageInfo(pkg, 0)
            true
        } catch (e: Exception) {
            false
        }
    }

    /** 供服务端回调的 Binder：把 ISpeechCallback 的 onTransact 转发给 Lua 监听。 */
    private val callbackBinder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            try {
                when (code) {
                    CB_STATE -> {
                        data.enforceInterface(IFACE_CALLBACK)
                        val sid = data.readInt()
                        val st = data.readInt()
                        val msg = data.readString() ?: ""
                        listener?.onState(sid, st, msg)
                    }
                    CB_PARTIAL -> {
                        data.enforceInterface(IFACE_CALLBACK)
                        val sid = data.readInt()
                        val text = data.readString() ?: ""
                        listener?.onPartial(sid, text)
                    }
                    CB_FINAL -> {
                        data.enforceInterface(IFACE_CALLBACK)
                        val sid = data.readInt()
                        val text = data.readString() ?: ""
                        FileLogger.i(TAG, "[$pluginId] 收到 onFinal(sid=$sid): $text")
                        listener?.onFinal(sid, text)
                        notifySessionResult(sid)
                    }
                    CB_ERROR -> {
                        data.enforceInterface(IFACE_CALLBACK)
                        val sid = data.readInt()
                        val c = data.readInt()
                        val msg = data.readString() ?: ""
                        FileLogger.e(TAG, "[$pluginId] 收到 onError(sid=$sid, code=$c): $msg")
                        listener?.onError(sid, c, msg)
                        notifySessionResult(sid)
                    }
                    CB_AMPLITUDE -> {
                        data.enforceInterface(IFACE_CALLBACK)
                        val sid = data.readInt()
                        val amp = data.readFloat()
                        listener?.onAmplitude(sid, amp)
                    }
                    else -> return super.onTransact(code, data, reply, flags)
                }
                return true
            } catch (e: Exception) {
                Log.w(TAG, "[$pluginId] callback onTransact($code) failed", e)
                return super.onTransact(code, data, reply, flags)
            }
        }
    }
}

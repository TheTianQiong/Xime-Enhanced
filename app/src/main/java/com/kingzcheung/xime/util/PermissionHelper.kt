package com.kingzcheung.xime.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val PERMISSION_RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
    const val PERMISSION_RECEIVE_SMS = android.Manifest.permission.RECEIVE_SMS
    const val PERMISSION_VIBRATE = android.Manifest.permission.VIBRATE
    const val PERMISSION_INTERNET = android.Manifest.permission.INTERNET
    const val PERMISSION_ACCESS_NETWORK_STATE = android.Manifest.permission.ACCESS_NETWORK_STATE
    const val PERMISSION_ACCESS_WIFI_STATE = android.Manifest.permission.ACCESS_WIFI_STATE
    const val PERMISSION_WAKE_LOCK = android.Manifest.permission.WAKE_LOCK

    const val REQUEST_CODE_RECORD_AUDIO = 1001
    const val REQUEST_CODE_RECEIVE_SMS = 1002

    fun hasPermission(context: Context, permission: String): Boolean {
        return ContextCompat.checkSelfPermission(
            context,
            permission
        ) == PackageManager.PERMISSION_GRANTED
    }

    fun hasRecordAudioPermission(context: Context): Boolean {
        return hasPermission(context, PERMISSION_RECORD_AUDIO)
    }

    fun hasSmsPermission(context: Context): Boolean {
        return hasPermission(context, PERMISSION_RECEIVE_SMS)
    }

    /**
     * 该权限是否属于「运行时权限」（危险权限）：需用户显式授权，
     * 未授权时功能不可用。普通权限（网络、震动等）安装即授予，
     * 仅作展示，不提供授权按钮。
     */
    fun isRuntimePermission(permission: String): Boolean = permission in RUNTIME_PERMISSIONS

    private val RUNTIME_PERMISSIONS = setOf(
        PERMISSION_RECORD_AUDIO,
        PERMISSION_RECEIVE_SMS,
    )

    /**
     * 判断权限名是否等价：允许插件用短名（`RECEIVE_SMS`）或全名
     * （`android.permission.RECEIVE_SMS`）声明。
     */
    fun permissionMatches(declared: String, permission: String): Boolean {
        if (declared.isBlank()) return false
        return declared == permission || declared.substringAfterLast('.') == permission.substringAfterLast('.')
    }

    /**
     * 请求任意运行时权限。
     *
     * IME 进程内不能直接弹权限对话框，因此拉起 [com.kingzcheung.xime.MainActivity]
     * 承载 [androidx.activity.result.ActivityResultContracts.RequestPermission]。
     */
    fun requestPermission(context: Context, permission: String) {
        val intent = Intent(context, com.kingzcheung.xime.MainActivity::class.java)
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        intent.putExtra("request_permission", permission)
        context.startActivity(intent)
    }

    fun requestRecordAudioPermission(context: Context) {
        requestPermission(context, PERMISSION_RECORD_AUDIO)
    }

    fun requestSmsPermission(context: Context) {
        requestPermission(context, PERMISSION_RECEIVE_SMS)
    }
}

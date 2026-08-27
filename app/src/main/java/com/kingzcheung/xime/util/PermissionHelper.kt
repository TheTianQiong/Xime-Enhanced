package com.kingzcheung.xime.util

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

object PermissionHelper {
    const val PERMISSION_RECORD_AUDIO = android.Manifest.permission.RECORD_AUDIO
    const val PERMISSION_RECEIVE_SMS = android.Manifest.permission.RECEIVE_SMS
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
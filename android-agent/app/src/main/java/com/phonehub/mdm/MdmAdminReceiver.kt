package com.phonehub.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build

class MdmAdminReceiver : DeviceAdminReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        when (intent.action) {
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            ACTION_DEVICE_ADMIN_ENABLED -> {
                val svc = Intent(context, MdmAgentService::class.java).apply {
                    putExtra("boot_completed", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(svc)
                } else {
                    context.startService(svc)
                }
            }
            Intent.ACTION_SHUTDOWN,
            "android.intent.action.QUICKBOOT_POWEROFF" -> {
                val svc = Intent(context, MdmAgentService::class.java).apply {
                    putExtra("shutdown_initiated", true)
                }
                context.startService(svc)
            }
        }
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Silent install completed callback
    }
}

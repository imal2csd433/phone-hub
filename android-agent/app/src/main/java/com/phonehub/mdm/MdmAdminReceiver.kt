package com.phonehub.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MdmAdminReceiver : DeviceAdminReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        try {
            when (intent.action) {
                Intent.ACTION_BOOT_COMPLETED,
                Intent.ACTION_LOCKED_BOOT_COMPLETED,
                Intent.ACTION_MY_PACKAGE_REPLACED,
                ACTION_DEVICE_ADMIN_ENABLED -> {
                    MdmAgentService.scheduleKeepAliveAlarm(context)
                    MdmAgentService.startPersistentService(context, isBoot = true)
                }
                Intent.ACTION_USER_PRESENT,
                Intent.ACTION_POWER_CONNECTED,
                MdmAgentService.ACTION_KEEP_ALIVE -> {
                    MdmAgentService.scheduleKeepAliveAlarm(context)
                    MdmAgentService.startPersistentService(context, isBoot = false)
                }
                Intent.ACTION_SHUTDOWN,
                "android.intent.action.QUICKBOOT_POWEROFF" -> {
                    MdmAgentService.startPersistentService(context, isShutdown = true)
                }
            }
        } catch (_: Throwable) {}
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
    }
}

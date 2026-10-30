package com.phonehub.mdm

import android.app.admin.DeviceAdminReceiver
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class MdmAdminReceiver : DeviceAdminReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        if (intent.action == Intent.ACTION_BOOT_COMPLETED ||
            intent.action == Intent.ACTION_LOCKED_BOOT_COMPLETED ||
            intent.action == ACTION_DEVICE_ADMIN_ENABLED) {
            val svc = Intent(context, MdmAgentService::class.java).apply {
                putExtra("boot_completed", true)
            }
            context.startService(svc)
        }
    }
}

class ApkInstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        // Silent install completed callback
    }
}

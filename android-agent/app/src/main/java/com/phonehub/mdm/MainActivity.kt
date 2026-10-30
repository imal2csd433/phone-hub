package com.phonehub.mdm

import android.Manifest
import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {

    private lateinit var connStatusText: TextView
    private lateinit var statusView: TextView

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val ok = intent.getBooleanExtra("ok", false)
            val msg = intent.getStringExtra("msg") ?: ""
            runOnUiThread {
                connStatusText.text = msg
                connStatusText.setTextColor(if (ok) Color.parseColor("#10B981") else Color.parseColor("#EF4444"))
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission on Android 13+ so Foreground Service notification stays permanent
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                    requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 101)
                }
            } catch (_: Throwable) {}
        }

        // Automatically prompt once to disable battery optimization so service runs 24/7 with app closed
        requestIgnoreBatteryOptimizationsIfNeeded()

        val savedUrl = MdmAgentService.getSavedServerUrl(this)
        val savedName = MdmAgentService.getSavedDeviceName(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 72, 48, 48)
            setBackgroundColor(Color.parseColor("#090D16"))
        }

        val title = TextView(this).apply {
            text = "🛡️ Org Device Guard (24/7 Background)"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 16)
        }
        refreshPermissionSummary()

        connStatusText = TextView(this).apply {
            text = "🔄 กำลังส่งสัญญาณเบื้องหลังเข้าเซิร์ฟเวอร์..."
            textSize = 14f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 0, 0, 24)
        }

        val nameLabel = TextView(this).apply {
            text = "1. ชื่อเครื่องในองค์กร (แสดงในหน้าแอดมิน):"
            setTextColor(Color.WHITE)
        }
        val nameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val urlLabel = TextView(this).apply {
            text = "2. URL เซิร์ฟเวอร์ควบคุม:"
            setTextColor(Color.WHITE)
            setPadding(0, 16, 0, 0)
        }
        val urlInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val saveBtn = Button(this).apply {
            text = "💾 บันทึก & รันเบื้องหลังตลอดเวลา (ปิดแอปได้เลย)"
            setOnClickListener {
                connStatusText.text = "🔄 กำลังส่งสัญญาณไปที่เซิร์ฟเวอร์..."
                connStatusText.setTextColor(Color.parseColor("#F59E0B"))
                MdmAgentService.saveConfig(
                    this@MainActivity,
                    urlInput.text.toString().trim().removeSuffix("/"),
                    nameInput.text.toString().trim()
                )
                MdmAgentService.scheduleKeepAliveAlarm(this@MainActivity)
                MdmAgentService.startPersistentService(this@MainActivity, isBoot = true)
                Toast.makeText(this@MainActivity, "บันทึกแล้ว! ปัดปิดแอปหรือล็อกหน้าจอได้เลย ระบบจะทำงานเบื้องหลังต่ออัตโนมัติ", Toast.LENGTH_LONG).show()
            }
        }

        val batteryBtn = Button(this).apply {
            text = "⚡ อนุญาตทำงานเบื้องหลังไม่จำกัดแบต (กด 1 ครั้ง)"
            setOnClickListener {
                requestIgnoreBatteryOptimizationsIfNeeded(forceOpen = true)
            }
        }

        val adminBtn = Button(this).apply {
            text = "🔐 เปิดสิทธิ์ Device Admin (เพื่อรับคำสั่งล็อกเครื่อง)"
            setOnClickListener {
                try {
                    val adminComp = ComponentName(this@MainActivity, MdmAdminReceiver::class.java)
                    val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                        putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                        putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "อนุญาตให้องค์กรล็อกหน้าจอและจัดการเครื่องระยะไกล")
                    }
                    startActivity(intent)
                } catch (e: Throwable) {
                    Toast.makeText(this@MainActivity, "ไม่สามารถเปิดหน้าตั้งค่าได้: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(connStatusText)
        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(saveBtn)
        layout.addView(batteryBtn)
        layout.addView(adminBtn)
        scroll.addView(layout)
        setContentView(scroll)

        MdmAgentService.scheduleKeepAliveAlarm(this)
        MdmAgentService.startPersistentService(this, isBoot = true)
    }

    private fun requestIgnoreBatteryOptimizationsIfNeeded(forceOpen: Boolean = false) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                if (!pm.isIgnoringBatteryOptimizations(packageName) || forceOpen) {
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } else if (forceOpen) {
                    Toast.makeText(this, "✅ เปิดทำงานเบื้องหลังแบบไม่จำกัดแบตอยู่แล้ว", Toast.LENGTH_SHORT).show()
                }
            }
        } catch (_: Throwable) {}
    }

    private fun refreshPermissionSummary() {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, MdmAdminReceiver::class.java)
        val isOwner = try { dpm.isDeviceOwnerApp(packageName) } catch (_: Throwable) { false }
        val isAdmin = try { dpm.isAdminActive(adminComp) } catch (_: Throwable) { false }
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        val noBatLimit = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) pm.isIgnoringBatteryOptimizations(packageName) else true
        val devId = MdmAgentService.getOrCreateUniqueDeviceId(this)

        statusView.text = "รหัสเครื่อง: $devId\n" +
            "ทำงานเบื้องหลัง 24 ชม.: ${if (noBatLimit) "✅ เปิดแล้ว (ปิดแอปก็ยังออนไลน์)" else "⚠️ กดปุ่ม 'อนุญาตทำงานเบื้องหลัง' ด้านล่าง"}\n" +
            "สิทธิ์ควบคุมเครื่อง: ${when {
                isOwner -> "✅ Device Owner (เต็มรูปแบบ)"
                isAdmin -> "✅ Device Admin (สั่งล็อกหน้าจอได้)"
                else -> "⚠️ ยังไม่ได้เปิดสิทธิ์ (กดปุ่มเปิดสิทธิ์ด้านล่าง)"
            }}"
    }

    override fun onResume() {
        super.onResume()
        refreshPermissionSummary()
        try {
            val filter = IntentFilter(MdmAgentService.ACTION_STATUS_UPDATE)
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(statusReceiver, filter)
            }
        } catch (_: Throwable) {}
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Throwable) {}
        super.onPause()
    }
}

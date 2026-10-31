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
    private lateinit var snInput: EditText
    private lateinit var phoneInput: EditText

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

        requestRuntimePermissionsIfNeeded()
        requestIgnoreBatteryOptimizationsIfNeeded()

        val savedUrl = MdmAgentService.getSavedServerUrl(this)
        val savedName = MdmAgentService.getSavedDeviceName(this)
        val detectedSn = MdmAgentService.detectDeviceSerial(this)
        val detectedPhone = MdmAgentService.detectPhoneNumber(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.parseColor("#090D16"))
            isFillViewport = true
        }

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#090D16"))
        }

        val title = TextView(this).apply {
            text = "🛡️ Org Device Guard (v1.0.4)"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        statusView = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 12, 0, 12)
        }

        connStatusText = TextView(this).apply {
            text = "🔄 กำลังส่งข้อมูลรุ่น/SN/เบอร์ เข้าเซิร์ฟเวอร์..."
            textSize = 14f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 0, 0, 20)
        }

        val nameLabel = TextView(this).apply {
            text = "1. ชื่อเครื่องในองค์กร (เช่น เครื่องฝ่ายขาย #1):"
            setTextColor(Color.WHITE)
        }
        val nameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val snLabel = TextView(this).apply {
            text = "2. Serial Number (SN ที่ดึงได้จากเครื่อง / แก้ไขได้):"
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        snInput = EditText(this).apply {
            setText(detectedSn)
            setTextColor(Color.parseColor("#FCD34D"))
            setHintTextColor(Color.GRAY)
        }

        val phoneLabel = TextView(this).apply {
            text = "3. เบอร์โทรศัพท์ซิมในเครื่อง (ดึงจากซิมอัตโนมัติ / แก้ไขได้):"
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        phoneInput = EditText(this).apply {
            setText(detectedPhone)
            hint = "เช่น 081-234-5678"
            setTextColor(Color.parseColor("#6EE7B7"))
            setHintTextColor(Color.GRAY)
        }

        val urlLabel = TextView(this).apply {
            text = "4. URL เซิร์ฟเวอร์ควบคุม:"
            setTextColor(Color.WHITE)
            setPadding(0, 12, 0, 0)
        }
        val urlInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val saveBtn = Button(this).apply {
            text = "💾 บันทึกข้อมูลเครื่อง & รันเบื้องหลัง 24 ชม."
            setOnClickListener {
                connStatusText.text = "🔄 กำลังส่งสัญญาณไปที่เซิร์ฟเวอร์..."
                connStatusText.setTextColor(Color.parseColor("#F59E0B"))
                MdmAgentService.saveConfig(
                    this@MainActivity,
                    urlInput.text.toString().trim().removeSuffix("/"),
                    nameInput.text.toString().trim(),
                    snInput.text.toString().trim(),
                    phoneInput.text.toString().trim()
                )
                refreshPermissionSummary()
                MdmAgentService.scheduleKeepAliveAlarm(this@MainActivity)
                MdmAgentService.startPersistentService(this@MainActivity, isBoot = true)
                Toast.makeText(this@MainActivity, "บันทึกและส่งข้อมูลรุ่น/SN/เบอร์เข้าหน้าแอดมินแล้ว!", Toast.LENGTH_LONG).show()
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
        layout.addView(snLabel)
        layout.addView(snInput)
        layout.addView(phoneLabel)
        layout.addView(phoneInput)
        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(saveBtn)
        layout.addView(batteryBtn)
        layout.addView(adminBtn)
        scroll.addView(layout)
        setContentView(scroll)

        refreshPermissionSummary()
        MdmAgentService.scheduleKeepAliveAlarm(this)
        MdmAgentService.startPersistentService(this, isBoot = true)
    }

    private fun requestRuntimePermissionsIfNeeded() {
        try {
            val perms = mutableListOf(
                Manifest.permission.READ_PHONE_STATE,
                Manifest.permission.READ_SMS
            )
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                perms.add(Manifest.permission.READ_PHONE_NUMBERS)
            }
            if (Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS)
            }
            val missing = perms.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
            if (missing.isNotEmpty()) {
                requestPermissions(missing.toTypedArray(), 102)
            }
        } catch (_: Throwable) {}
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val autoPhone = MdmAgentService.detectPhoneNumber(this)
        if (autoPhone.isNotEmpty() && phoneInput.text.toString().isBlank()) {
            phoneInput.setText(autoPhone)
        }
        MdmAgentService.startPersistentService(this, isBoot = false)
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
        val hwModel = MdmAgentService.getFullHardwareModel()

        statusView.text = "รุ่นเครื่อง: $hwModel (Android ${Build.VERSION.RELEASE})\n" +
            "รหัสระบบ: $devId\n" +
            "ทำงานเบื้องหลัง 24 ชม.: ${if (noBatLimit) "✅ เปิดแล้ว (ปิดแอปก็ยังออนไลน์)" else "⚠️ กดปุ่มอนุญาตทำงานเบื้องหลังด้านล่าง"}\n" +
            "สิทธิ์ควบคุม: ${when {
                isOwner -> "✅ Device Owner (เต็มรูปแบบ)"
                isAdmin -> "✅ Device Admin (สั่งล็อกหน้าจอได้)"
                else -> "⚠️ ยังไม่ได้เปิดสิทธิ์"
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

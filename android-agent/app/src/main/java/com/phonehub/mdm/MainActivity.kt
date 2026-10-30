package com.phonehub.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.*

class MainActivity : Activity() {

    private lateinit var connStatusText: TextView

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

        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", MdmAgentService.DEFAULT_SERVER_URL) ?: MdmAgentService.DEFAULT_SERVER_URL
        val savedName = prefs.getString("device_name", "${Build.MANUFACTURER} ${Build.MODEL}") ?: ""

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComp = ComponentName(this, MdmAdminReceiver::class.java)
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        val isAdmin = dpm.isAdminActive(adminComp)
        val devId = MdmAgentService.getOrCreateUniqueDeviceId(this)

        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(Color.parseColor("#090D16"))
        }

        val title = TextView(this).apply {
            text = "🛡️ Org Device Guard (MDM Agent)"
            textSize = 20f
            setTextColor(Color.WHITE)
        }
        val statusView = TextView(this).apply {
            text = "รหัสประจำเครื่อง (ไม่ซ้ำ): $devId\nสิทธิ์ควบคุม: ${when {
                isOwner -> "✅ Device Owner (เต็มรูปแบบ)"
                isAdmin -> "✅ Device Admin (สั่งล็อกหน้าจอได้)"
                else -> "⚠️ ยังไม่ได้เปิดสิทธิ์ (กดปุ่มเปิดสิทธิ์ด้านล่าง)"
            }}"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 16)
        }

        connStatusText = TextView(this).apply {
            text = "🔄 กำลังเชื่อมต่อเซิร์ฟเวอร์..."
            textSize = 14f
            setTextColor(Color.parseColor("#F59E0B"))
            setPadding(0, 0, 0, 28)
        }

        val nameLabel = TextView(this).apply {
            text = "ชื่อเครื่องในองค์กร (แสดงในหน้าแอดมิน):"
            setTextColor(Color.WHITE)
        }
        val nameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val urlLabel = TextView(this).apply {
            text = "URL เซิร์ฟเวอร์ควบคุม:"
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 0)
        }
        val urlInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val saveBtn = Button(this).apply {
            text = "💾 บันทึก & เชื่อมเข้าสู่ระบบถาวรทันที"
            setOnClickListener {
                connStatusText.text = "🔄 กำลังส่งสัญญาณไปที่เซิร์ฟเวอร์..."
                connStatusText.setTextColor(Color.parseColor("#F59E0B"))
                prefs.edit()
                    .putString("server_url", urlInput.text.toString().trim().removeSuffix("/"))
                    .putString("device_name", nameInput.text.toString().trim())
                    .apply()
                val svc = Intent(this@MainActivity, MdmAgentService::class.java).apply {
                    putExtra("boot_completed", true)
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(svc)
                } else {
                    startService(svc)
                }
            }
        }

        val adminBtn = Button(this).apply {
            text = "🔐 เปิดสิทธิ์ Device Admin (เพื่อให้สั่งล็อกเครื่องได้)"
            setOnClickListener {
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, adminComp)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "อนุญาตให้องค์กรล็อกหน้าจอและจัดการเครื่องระยะไกล")
                }
                startActivity(intent)
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
        layout.addView(adminBtn)
        setContentView(layout)

        val svc = Intent(this, MdmAgentService::class.java).apply {
            putExtra("boot_completed", true)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc)
        } else {
            startService(svc)
        }
    }

    override fun onResume() {
        super.onResume()
        val filter = IntentFilter(MdmAgentService.ACTION_STATUS_UPDATE)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            registerReceiver(statusReceiver, filter)
        }
    }

    override fun onPause() {
        try { unregisterReceiver(statusReceiver) } catch (_: Exception) {}
        super.onPause()
    }
}

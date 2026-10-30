package com.phonehub.mdm

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.provider.Settings
import android.widget.*

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("server_url", MdmAgentService.DEFAULT_SERVER_URL) ?: MdmAgentService.DEFAULT_SERVER_URL
        val savedName = prefs.getString("device_name", "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}") ?: ""

        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val isOwner = dpm.isDeviceOwnerApp(packageName)
        val devId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-DEV"

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
            text = "Device ID: $devId\nสิทธิ์ Device Owner: ${if (isOwner) "✅ เปิดใช้งานแล้ว" else "⚠️ ยังไม่ได้ตั้งค่า (รัน ADB หรือกดปุ่มด้านล่าง)"}"
            textSize = 13f
            setTextColor(Color.parseColor("#94A3B8"))
            setPadding(0, 16, 0, 32)
        }

        val nameLabel = TextView(this).apply {
            text = "ชื่อเครื่องในองค์กร (เช่น เครื่องฝ่ายขาย #1):"
            setTextColor(Color.WHITE)
        }
        val nameInput = EditText(this).apply {
            setText(savedName)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val urlLabel = TextView(this).apply {
            text = "URL เซิร์ฟเวอร์ควบคุม (MDM Server URL):"
            setTextColor(Color.WHITE)
            setPadding(0, 20, 0, 0)
        }
        val urlInput = EditText(this).apply {
            setText(savedUrl)
            setTextColor(Color.WHITE)
            setHintTextColor(Color.GRAY)
        }

        val saveBtn = Button(this).apply {
            text = "💾 บันทึก & ส่งสัญญาณเปิดเครื่องทันที"
            setOnClickListener {
                prefs.edit()
                    .putString("server_url", urlInput.text.toString().trim().removeSuffix("/"))
                    .putString("device_name", nameInput.text.toString().trim())
                    .apply()
                val svc = Intent(this@MainActivity, MdmAgentService::class.java).apply {
                    putExtra("boot_completed", true)
                }
                startService(svc)
                Toast.makeText(this@MainActivity, "ส่งสัญญาณเข้าเซิร์ฟเวอร์แล้ว!", Toast.LENGTH_SHORT).show()
            }
        }

        val adminBtn = Button(this).apply {
            text = "🔐 เปิดสิทธิ์ Device Admin (กรณีไม่ใช้สาย USB)"
            setOnClickListener {
                val comp = ComponentName(this@MainActivity, MdmAdminReceiver::class.java)
                val intent = Intent(DevicePolicyManager.ACTION_ADD_DEVICE_ADMIN).apply {
                    putExtra(DevicePolicyManager.EXTRA_DEVICE_ADMIN, comp)
                    putExtra(DevicePolicyManager.EXTRA_ADD_EXPLANATION, "อนุญาตให้องค์กรล็อกหน้าจอและจัดการเครื่องระยะไกล")
                }
                startActivity(intent)
            }
        }

        layout.addView(title)
        layout.addView(statusView)
        layout.addView(nameLabel)
        layout.addView(nameInput)
        layout.addView(urlLabel)
        layout.addView(urlInput)
        layout.addView(saveBtn)
        layout.addView(adminBtn)
        setContentView(layout)

        // Start service automatically on launch
        startService(Intent(this, MdmAgentService::class.java).apply {
            putExtra("boot_completed", true)
        })
    }
}

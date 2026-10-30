package com.phonehub.mdm

import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.BatteryManager
import android.os.Build
import android.os.IBinder
import android.provider.Settings
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MdmAgentService : Service() {

    companion object {
        const val DEFAULT_SERVER_URL = "https://6c77e3dcaae6ad.lhr.life"
        const val AGENT_KEY = "org-secret-agent-key-2026"
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    private fun getDeviceName(): String {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_name", "${Build.MANUFACTURER} ${Build.MODEL}") ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var bootReported = false

    override fun onCreate() {
        super.onCreate()
        scheduler.scheduleAtFixedRate({ checkInWithServer() }, 0, 10, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("boot_completed", false) == true) {
            bootReported = false
            scheduler.execute { checkInWithServer() }
        }
        return START_STICKY
    }

    private fun checkInWithServer(ackIds: List<Int> = emptyList()) {
        try {
            val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID) ?: "ANDROID-DEV"
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)

            val serverUrl = getServerUrl()
            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("name", getDeviceName())
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("battery", battery)
                put("boot_completed", !bootReported)
                put("ack_commands", JSONArray(ackIds))
            }

            val conn = (URL("$serverUrl/api/mdm/agent/checkin").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("x-agent-key", AGENT_KEY)
                doOutput = true
                connectTimeout = 8000
                readTimeout = 8000
            }
            conn.outputStream.use { it.write(payload.toString().toByteArray()) }

            if (conn.responseCode == 200) {
                bootReported = true
                val respText = conn.inputStream.bufferedReader().readText()
                val json = JSONObject(respText)
                val cmds = json.optJSONArray("commands") ?: JSONArray()
                val executed = mutableListOf<Int>()

                for (i in 0 until cmds.length()) {
                    val cmdObj = cmds.getJSONObject(i)
                    val id = cmdObj.getInt("id")
                    val command = cmdObj.getString("command")
                    val cmdPayload = cmdObj.optJSONObject("payload") ?: JSONObject()
                    executeDeviceCommand(command, cmdPayload)
                    executed.add(id)
                }

                if (executed.isNotEmpty()) {
                    checkInWithServer(executed)
                }
            }
        } catch (_: Exception) {}
    }

    private fun executeDeviceCommand(command: String, payload: JSONObject) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MdmAdminReceiver::class.java)

        when (command) {
            "LOCK_NOW" -> dpm.lockNow()
            "REBOOT" -> if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                dpm.reboot(admin)
            }
            "SHUTDOWN" -> {
                // 1. หากเครื่อง Root หรือเป็น System App จะดับเครื่องสนิททันที
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")) } catch (_: Exception) {}
                // 2. หากเป็นเครื่องมาตรฐาน (Device Owner) จะล็อกหน้าจอทันทีและบล็อกการใช้งานทั้งหมด
                dpm.lockNow()
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setDeviceOwnerLockScreenInfo(admin, "เครื่องนี้ถูกสั่งปิดการใช้งานโดยผู้ดูแลองค์กร")
                }
            }
            "UNLOCK" -> {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                    dpm.setDeviceOwnerLockScreenInfo(admin, null)
                }
            }
            "INSTALL_APK" -> {
                val dlPath = payload.optString("download_url", "")
                if (dlPath.isNotEmpty()) {
                    silentInstallApk("${getServerUrl()}$dlPath")
                }
            }
        }
    }

    // ติดตั้งไฟล์ APK ระยะไกลแบบเงียบ (Silent Install โดยไม่ต้องมีคนกดยืนยัน)
    private fun silentInstallApk(apkUrl: String) {
        val installer = packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL)
        val sessionId = installer.createSession(params)
        val session = installer.openSession(sessionId)

        URL(apkUrl).openStream().use { input ->
            session.openWrite("remote_push.apk", 0, -1).use { output ->
                BufferedInputStream(input).copyTo(output)
                session.fsync(output)
            }
        }

        val intent = Intent(this, ApkInstallReceiver::class.java)
        val pi = PendingIntent.getBroadcast(
            this, sessionId, intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        session.commit(pi.intentSender)
        session.close()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

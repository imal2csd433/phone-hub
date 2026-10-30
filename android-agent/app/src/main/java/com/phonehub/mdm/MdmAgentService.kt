package com.phonehub.mdm

import android.app.PendingIntent
import android.app.Service
import android.app.admin.DevicePolicyManager
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
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
import java.util.UUID
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class MdmAgentService : Service() {

    companion object {
        const val DEFAULT_SERVER_URL = "https://mechanics-pointer-omaha-broadcasting.trycloudflare.com"
        const val AGENT_KEY = "org-secret-agent-key-2026"
        const val ACTION_STATUS_UPDATE = "com.phonehub.mdm.STATUS_UPDATE"

        fun getOrCreateUniqueDeviceId(ctx: Context): String {
            val prefs = ctx.getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
            var id = prefs.getString("unique_device_id", null)
            if (id.isNullOrBlank()) {
                val hwId = try { Settings.Secure.getString(ctx.contentResolver, Settings.Secure.ANDROID_ID) ?: "" } catch (_: Throwable) { "" }
                val rand = UUID.randomUUID().toString().substring(0, 6).uppercase()
                val cleanModel = Build.MODEL.replace(Regex("[^a-zA-Z0-9]"), "")
                id = if (hwId.length >= 6 && hwId != "9774d56d682e549c") {
                    "AND-$cleanModel-${hwId.takeLast(4).uppercase()}-$rand"
                } else {
                    "AND-$cleanModel-$rand"
                }
                prefs.edit().putString("unique_device_id", id).apply()
            }
            return id
        }
    }

    private val scheduler = Executors.newSingleThreadScheduledExecutor()
    private var bootReported = false

    private val shutdownReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == Intent.ACTION_SHUTDOWN || intent.action == "android.intent.action.QUICKBOOT_POWEROFF") {
                scheduler.execute { checkInWithServer(isShutdown = true) }
            }
        }
    }

    private fun getServerUrl(): String {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        return prefs.getString("server_url", DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
    }

    private fun getDeviceName(): String {
        val prefs = getSharedPreferences("mdm_prefs", Context.MODE_PRIVATE)
        return prefs.getString("device_name", "${Build.MANUFACTURER} ${Build.MODEL}") ?: "${Build.MANUFACTURER} ${Build.MODEL}"
    }

    override fun onCreate() {
        super.onCreate()
        try {
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_SHUTDOWN)
                addAction("android.intent.action.QUICKBOOT_POWEROFF")
            }
            if (Build.VERSION.SDK_INT >= 33) {
                registerReceiver(shutdownReceiver, filter, Context.RECEIVER_EXPORTED)
            } else {
                registerReceiver(shutdownReceiver, filter)
            }
        } catch (_: Throwable) {}
        scheduler.scheduleAtFixedRate({ checkInWithServer() }, 0, 10, TimeUnit.SECONDS)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.getBooleanExtra("shutdown_initiated", false) == true) {
            scheduler.execute { checkInWithServer(isShutdown = true) }
            return START_STICKY
        }
        if (intent?.getBooleanExtra("boot_completed", false) == true) {
            bootReported = false
            scheduler.execute { checkInWithServer() }
        }
        return START_STICKY
    }

    private fun checkInWithServer(ackIds: List<Int> = emptyList(), isShutdown: Boolean = false) {
        try {
            val deviceId = getOrCreateUniqueDeviceId(this)
            val bm = getSystemService(Context.BATTERY_SERVICE) as BatteryManager
            val battery = bm.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
            val serverUrl = getServerUrl()

            val payload = JSONObject().apply {
                put("device_id", deviceId)
                put("name", getDeviceName())
                put("model", Build.MODEL)
                put("android_version", Build.VERSION.RELEASE)
                put("battery", battery)
                put("boot_completed", !bootReported && !isShutdown)
                put("shutdown_initiated", isShutdown)
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

            val code = conn.responseCode
            if (code == 200) {
                bootReported = true
                sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra("ok", true)
                    putExtra("msg", "✅ เชื่อมต่อสำเร็จ (${java.text.SimpleDateFormat("HH:mm:ss").format(java.util.Date())})")
                })
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
            } else {
                sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                    setPackage(packageName)
                    putExtra("ok", false)
                    putExtra("msg", "❌ เซิร์ฟเวอร์ตอบกลับ HTTP $code")
                })
            }
        } catch (e: Throwable) {
            sendBroadcast(Intent(ACTION_STATUS_UPDATE).apply {
                setPackage(packageName)
                putExtra("ok", false)
                putExtra("msg", "❌ เชื่อมต่อไม่สำเร็จ: ${e.localizedMessage ?: "ตรวจสอบ URL"}")
            })
        }
    }

    private fun executeDeviceCommand(command: String, payload: JSONObject) {
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(this, MdmAdminReceiver::class.java)

        when (command) {
            "LOCK_NOW" -> try { dpm.lockNow() } catch (_: Throwable) {}
            "REBOOT" -> try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                    dpm.reboot(admin)
                }
            } catch (_: Throwable) {}
            "SHUTDOWN" -> {
                try { Runtime.getRuntime().exec(arrayOf("su", "-c", "reboot -p")) } catch (_: Throwable) {}
                try { dpm.lockNow() } catch (_: Throwable) {}
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                        dpm.setDeviceOwnerLockScreenInfo(admin, "เครื่องนี้ถูกสั่งปิดการใช้งานโดยผู้ดูแลองค์กร")
                    }
                } catch (_: Throwable) {}
            }
            "UNLOCK" -> {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && dpm.isDeviceOwnerApp(packageName)) {
                        dpm.setDeviceOwnerLockScreenInfo(admin, null)
                    }
                } catch (_: Throwable) {}
            }
            "INSTALL_APK" -> {
                val dlPath = payload.optString("download_url", "")
                if (dlPath.isNotEmpty()) {
                    silentInstallApk("${getServerUrl()}$dlPath")
                }
            }
        }
    }

    private fun silentInstallApk(apkUrl: String) {
        try {
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
        } catch (_: Throwable) {}
    }

    override fun onDestroy() {
        try { unregisterReceiver(shutdownReceiver) } catch (_: Throwable) {}
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}

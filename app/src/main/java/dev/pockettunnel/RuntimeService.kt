package dev.pockettunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Process as AndroidProcess
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import java.io.File

class RuntimeService : Service() {
    private var tunnelProcess: Process? = null
    private var wakeLock: PowerManager.WakeLock? = null
    private val channelId = "pocket_tunnel_runtime"

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) {
            tunnelProcess?.destroy()
            stopForeground(true)
            stopSelf()
            Handler(Looper.getMainLooper()).postDelayed({ AndroidProcess.killProcess(AndroidProcess.myPid()) }, 500)
            return START_NOT_STICKY
        }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Backend and tunnel", NotificationManager.IMPORTANCE_LOW))
        startForeground(27, buildNotification())
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketTunnel:runtime").apply { acquire() }
        }
        when (intent?.action) {
            START_BACKEND -> startBackend(intent)
            START_TUNNEL -> startTunnel(intent)
        }
        return START_NOT_STICKY
    }

    private fun startBackend(intent: Intent) {
        val dir = File(intent.getStringExtra(PROJECT_DIR) ?: return)
        val entry = intent.getStringExtra(ENTRY) ?: return
        val port = intent.getStringExtra(PORT) ?: "3000"
        if (!File(dir, entry).isFile) { Log.e(TAG, "Backend entry file was not found: ${File(dir, entry)}"); return }
        Thread {
            try { Log.i(TAG, "Node exited with ${NodeEngine.startNode(arrayOf("node", entry), dir.absolutePath, port)}") }
            catch (error: Throwable) { Log.e(TAG, "Node runtime could not start", error) }
        }.start()
    }

    private fun startTunnel(intent: Intent) {
        val token = intent.getStringExtra(TUNNEL_TOKEN) ?: return
        Thread {
            try {
                val binary = File(filesDir, "runtime/cloudflared")
                if (!binary.isFile) {
                    binary.parentFile?.mkdirs()
                    assets.open("runtime/arm64-v8a/cloudflared").use { input -> binary.outputStream().use { input.copyTo(it) } }
                }
                binary.setExecutable(true, true)
                tunnelProcess = ProcessBuilder(binary.absolutePath, "tunnel", "--no-autoupdate", "run", "--token", token)
                    .redirectErrorStream(true).redirectOutput(File(filesDir, "cloudflared.log")).start()
                tunnelProcess?.waitFor()
            } catch (error: Throwable) { Log.e(TAG, "Cloudflare tunnel could not start", error) }
        }.start()
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, RuntimeService::class.java).setAction(STOP)
        val pending = PendingIntent.getService(this, 28, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pocket Tunnel").setContentText("Backend and Cloudflare tunnel are running")
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", pending).build()).build()
    }

    override fun onDestroy() { tunnelProcess?.destroy(); if (wakeLock?.isHeld == true) wakeLock?.release(); super.onDestroy() }

    companion object {
        private const val TAG = "PocketTunnelRuntime"
        const val START_BACKEND = "dev.pockettunnel.START_BACKEND"
        const val START_TUNNEL = "dev.pockettunnel.START_TUNNEL"
        const val STOP = "dev.pockettunnel.STOP"
        const val PROJECT_DIR = "project_dir"
        const val ENTRY = "entry"
        const val PORT = "port"
        const val TUNNEL_TOKEN = "tunnel_token"

        fun startBackend(activity: android.app.Activity, project: File, entry: String, port: String) {
            val intent = Intent(activity, RuntimeService::class.java).setAction(START_BACKEND)
                .putExtra(PROJECT_DIR, project.absolutePath).putExtra(ENTRY, entry).putExtra(PORT, port)
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
        }

        fun startTunnel(activity: android.app.Activity, token: String) {
            val intent = Intent(activity, RuntimeService::class.java).setAction(START_TUNNEL).putExtra(TUNNEL_TOKEN, token)
            if (android.os.Build.VERSION.SDK_INT >= 26) activity.startForegroundService(intent) else activity.startService(intent)
        }
    }
}

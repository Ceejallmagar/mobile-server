package dev.pockettunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.Process as AndroidProcess
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import org.json.JSONObject
import java.io.File

/** Tracks which tunnels are running. Written only by the runtime processes, read by the UI. */
object RunState {
    private const val FILE = "runtime_status"
    fun mark(context: Context, key: String, name: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().putString(key, name).commit()
    }
    fun clear(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().remove(key).commit()
    }
    fun seed(context: Context): Map<String, String> =
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).all.filterValues { it is String }.mapValues { it.value as String }
}

/** In-memory running map for the UI, seeded once and updated by runtime broadcasts. */
object RunStatus {
    private val map = java.util.Collections.synchronizedMap(mutableMapOf<String, String>())
    fun seed(context: Context) { map.putAll(RunState.seed(context)) }
    fun put(key: String, name: String) { map[key] = name }
    fun remove(key: String) { map.remove(key) }
    fun name(key: String): String? = map[key]
    fun isRunning(key: String): Boolean = map.containsKey(key)
    fun runningCount(): Int = map.size
    fun all(): Map<String, String> = map.toMap()
    fun clearAll() { map.clear() }
}

/** Assigns each running tunnel an isolated runtime process slot. Slots are claimed and released by the UI. */
object RunSlots {
    private const val FILE = "runtime_slots"
    private const val KEY = "slots"
    fun slotFor(context: Context, tunnelId: String): Int {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val map = decode(prefs.getString(KEY, "{}") ?: "{}")
        map[tunnelId]?.let { return it }
        val used = map.values.toMutableSet()
        val free = (0..3).firstOrNull { it !in used } ?: return -1
        map[tunnelId] = free
        prefs.edit().putString(KEY, encode(map)).commit()
        return free
    }
    fun release(context: Context, tunnelId: String) {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val map = decode(prefs.getString(KEY, "{}") ?: "{}")
        if (map.remove(tunnelId) != null) prefs.edit().putString(KEY, encode(map)).commit()
    }
    fun slotOf(context: Context, tunnelId: String): Int? = decode(context.getSharedPreferences(FILE, Context.MODE_PRIVATE).getString(KEY, "{}") ?: "{}")[tunnelId]
    fun clearAll(context: Context) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit().clear().commit()
    }
    fun serviceClass(slot: Int): Class<*> = when (slot) {
        0 -> RuntimeService::class.java
        1 -> RuntimeSlot1::class.java
        2 -> RuntimeSlot2::class.java
        else -> RuntimeSlot3::class.java
    }
    private fun encode(map: Map<String, Int>): String {
        val json = JSONObject()
        map.forEach { (key, slot) -> json.put(key, slot) }
        return json.toString()
    }
    private fun decode(raw: String): MutableMap<String, Int> {
        val result = mutableMapOf<String, Int>()
        try {
            val json = JSONObject(raw)
            json.keys().forEach { key -> result[key] = json.optInt(key) }
        } catch (_: Exception) { }
        return result
    }
}

/**
 * One isolated runtime process runs one tunnel (Node backend + cloudflared connector).
 * The manifest registers several subclasses in separate processes so multiple tunnels
 * can run at the same time.
 */
open class RuntimeService : Service() {
    private var tunnelProcess: Process? = null
    private var nodeThread: Thread? = null
    private var tunnelKey = ""
    private var tunnelName = ""
    private var backendRunning = false
    private var wakeLock: PowerManager.WakeLock? = null
    private val channelId = "pocket_tunnel_runtime"
    private val notificationId = 27

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopAll(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Backend and tunnel", NotificationManager.IMPORTANCE_LOW))
        startForeground(notificationId, buildNotification())
        intent?.getStringExtra(KEY)?.takeIf { it.isNotBlank() }?.let { tunnelKey = it }
        intent?.getStringExtra(NAME)?.takeIf { it.isNotBlank() }?.let { tunnelName = it }
        holdWakeLock()
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
        if (nodeThread?.isAlive == true) { Log.i(TAG, "Backend already running in this runtime slot."); return }
        backendRunning = true
        if (tunnelKey.isNotBlank()) RunState.mark(this, tunnelKey, tunnelName)
        refreshNotification()
        nodeThread = Thread {
            val code = try {
                NodeEngine.startNode(arrayOf("node", entry), dir.absolutePath, port)
            } catch (error: Throwable) {
                Log.e(TAG, "Node runtime could not start", error)
                -1
            }
            Log.i(TAG, "Node exited with $code")
            backendRunning = false
            refreshNotification()
            if (tunnelProcess == null) stopAll()
        }.also { it.start() }
    }

    private fun startTunnel(intent: Intent) {
        val token = intent.getStringExtra(TUNNEL_TOKEN) ?: return
        tunnelProcess?.destroy()
        tunnelProcess = null
        refreshNotification()
        Thread {
            try {
                val binary = File(filesDir, "runtime/cloudflared")
                if (!binary.isFile) {
                    binary.parentFile?.mkdirs()
                    assets.open("runtime/arm64-v8a/cloudflared").use { input -> binary.outputStream().use { input.copyTo(it) } }
                }
                binary.setExecutable(true, true)
                val process = ProcessBuilder(binary.absolutePath, "tunnel", "--no-autoupdate", "run", "--token", token)
                    .redirectErrorStream(true)
                    .redirectOutput(File(filesDir, "cloudflared/${tunnelKey.take(12)}.log").apply { parentFile?.mkdirs() })
                    .start()
                tunnelProcess = process
                refreshNotification()
                process.waitFor()
                if (tunnelProcess === process) {
                    tunnelProcess = null
                    refreshNotification()
                    if (!backendRunning) stopAll()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Cloudflare tunnel could not start", error)
            }
        }.start()
    }

    private fun stopAll() {
        val key = tunnelKey
        tunnelProcess?.destroy()
        tunnelProcess = null
        if (key.isNotBlank()) { RunState.clear(this, key); broadcast(stop = true) }
        stopForeground(true)
        stopSelf()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        Handler(Looper.getMainLooper()).postDelayed({ AndroidProcess.killProcess(AndroidProcess.myPid()) }, 400)
    }

    private fun holdWakeLock() {
        if (wakeLock?.isHeld != true) {
            wakeLock = (getSystemService(POWER_SERVICE) as PowerManager)
                .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "PocketTunnel:runtime:${tunnelKey.take(8)}")
                .apply { acquire() }
        }
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification())
    }

    private fun broadcast(stop: Boolean) {
        val intent = Intent(BROADCAST).setPackage(packageName)
            .putExtra(KEY, tunnelKey).putExtra(NAME, tunnelName)
            .putExtra(STATE, if (stop) STATE_STOPPED else STATE_RUNNING)
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, javaClass).setAction(STOP)
        val pending = PendingIntent.getService(this, 28, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            backendRunning && tunnelProcess != null -> "$tunnelName is running"
            backendRunning -> "$tunnelName backend is running"
            tunnelProcess != null -> "$tunnelName tunnel is running"
            else -> "Nothing is running"
        }
        return Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pocket Tunnel").setContentText(text)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", pending).build()).build()
    }

    override fun onDestroy() {
        tunnelProcess?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PocketTunnelRuntime"
        const val START_BACKEND = "dev.pockettunnel.START_BACKEND"
        const val START_TUNNEL = "dev.pockettunnel.START_TUNNEL"
        const val STOP = "dev.pockettunnel.STOP"
        const val BROADCAST = "dev.pockettunnel.RUNTIME_STATE"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val PROJECT_DIR = "project_dir"
        const val ENTRY = "entry"
        const val PORT = "port"
        const val TUNNEL_TOKEN = "tunnel_token"
        const val KEY = "tunnel_key"
        const val NAME = "tunnel_name"
    }
}

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
import java.net.InetSocketAddress
import java.net.Socket

/** Tracks which tunnels are running. Written only by the runtime processes, read by the UI. */
object RunState {
    private const val FILE = "runtime_status"
    private const val HEARTBEAT_PREFIX = "heartbeat:"
    private const val MAX_AGE_MS = 12_000L
    fun mark(context: Context, key: String, name: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(key, name).putLong(HEARTBEAT_PREFIX + key, System.currentTimeMillis()).commit()
    }
    fun update(context: Context, key: String, name: String, server: String, tunnel: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putString(key, name).putString("server:$key", server).putString("tunnel:$key", tunnel)
            .putLong(HEARTBEAT_PREFIX + key, System.currentTimeMillis()).commit()
    }
    fun heartbeat(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .putLong(HEARTBEAT_PREFIX + key, System.currentTimeMillis()).apply()
    }
    fun clear(context: Context, key: String) {
        context.getSharedPreferences(FILE, Context.MODE_PRIVATE).edit()
            .remove(key).remove("server:$key").remove("tunnel:$key").remove(HEARTBEAT_PREFIX + key).commit()
    }
    fun seed(context: Context): Map<String, RuntimeSnapshot> {
        val prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE)
        val now = System.currentTimeMillis()
        val valid = mutableMapOf<String, RuntimeSnapshot>()
        val cleanup = prefs.edit()
        prefs.all.forEach { (key, value) ->
            if (key.startsWith(HEARTBEAT_PREFIX) || key.startsWith("server:") || key.startsWith("tunnel:") || value !is String) return@forEach
            val heartbeat = prefs.getLong(HEARTBEAT_PREFIX + key, 0L)
            if (heartbeat > 0L && now - heartbeat <= MAX_AGE_MS) valid[key] = RuntimeSnapshot(
                value,
                prefs.getString("server:$key", "stopped") ?: "stopped",
                prefs.getString("tunnel:$key", "stopped") ?: "stopped")
            else cleanup.remove(key).remove("server:$key").remove("tunnel:$key").remove(HEARTBEAT_PREFIX + key)
        }
        cleanup.commit()
        return valid
    }
}

data class RuntimeSnapshot(val name: String, val server: String = "stopped", val tunnel: String = "stopped") {
    val active: Boolean get() = listOf(server, tunnel).any { !it.startsWith("stopped") && !it.startsWith("failed") }
}

/** In-memory running map for the UI, seeded once and updated by runtime broadcasts. */
object RunStatus {
    private val map = java.util.Collections.synchronizedMap(mutableMapOf<String, RuntimeSnapshot>())
    fun seed(context: Context) { map.clear(); map.putAll(RunState.seed(context)) }
    fun put(key: String, name: String, server: String = "stopped", tunnel: String = "stopped") { map[key] = RuntimeSnapshot(name, server, tunnel) }
    fun remove(key: String) { map.remove(key) }
    fun snapshot(key: String): RuntimeSnapshot? = map[key]
    fun isRunning(key: String): Boolean = map[key]?.active == true
    fun runningCount(): Int = map.values.count { it.active }
    fun all(): Map<String, RuntimeSnapshot> = map.toMap().filterValues { it.active }
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
    private var tunnelStarting = false
    private var backendStatus = "stopped"
    private var tunnelStatus = "stopped"
    private var serviceLabel = "backend"
    private var wakeLock: PowerManager.WakeLock? = null
    private val channelId = "pocket_tunnel_runtime"
    private val notificationId = 27
    private val heartbeatHandler = Handler(Looper.getMainLooper())
    private val heartbeatTask = object : Runnable {
        override fun run() {
            if (tunnelKey.isNotBlank()) {
                RunState.update(this@RuntimeService, tunnelKey, tunnelName.ifBlank { tunnelKey }, backendStatus, tunnelStatus)
                broadcast(stop = false)
            }
            heartbeatHandler.postDelayed(this, 5_000L)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == STOP) { stopAll(); return START_NOT_STICKY }
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(channelId, "Backend and tunnel", NotificationManager.IMPORTANCE_LOW))
        startForeground(notificationId, buildNotification())
        intent?.getStringExtra(KEY)?.takeIf { it.isNotBlank() }?.let { tunnelKey = it }
        intent?.getStringExtra(NAME)?.takeIf { it.isNotBlank() }?.let { tunnelName = it }
        intent?.getStringExtra(SERVICE_LABEL)?.takeIf { it.isNotBlank() }?.let { serviceLabel = it }
        if (tunnelKey.isNotBlank()) {
            RunState.mark(this, tunnelKey, tunnelName.ifBlank { tunnelKey })
            heartbeatHandler.removeCallbacks(heartbeatTask)
            heartbeatHandler.postDelayed(heartbeatTask, 5_000L)
        }
        holdWakeLock()
        when (intent?.action) {
            START_BACKEND -> startBackend(intent)
            START_FRONTEND -> startFrontend(intent)
            START_TUNNEL -> startTunnel(intent)
            RESTART_TUNNEL -> restartTunnel(intent)
        }
        return START_NOT_STICKY
    }

    private fun startBackend(intent: Intent) {
        serviceLabel = "backend"
        val dir = File(intent.getStringExtra(PROJECT_DIR) ?: return).canonicalFile
        val entry = intent.getStringExtra(ENTRY) ?: return
        val port = intent.getStringExtra(PORT) ?: "3000"
        val entryFile = File(dir, entry).canonicalFile
        if (!entryFile.path.startsWith(dir.path + File.separator) || !entryFile.isFile) {
            backendStatus = "failed (entry file missing)"
            Log.e(TAG, "Backend entry file was not found inside $dir: $entry")
            refreshNotification()
            finishRuntimeIfIdle()
            return
        }
        // Projects are often zipped with a wrapper folder (for example server/).
        // Run from the closest package root so dotenv.config(), package.json and
        // relative config paths resolve the same way they do from that project.
        val workingDir = generateSequence(entryFile.parentFile) { it.parentFile }
            .takeWhile { it.path == dir.path || it.path.startsWith(dir.path + File.separator) }
            .firstOrNull { File(it, "package.json").isFile }
            ?: dir
        val script = entryFile.relativeTo(workingDir).path
        if (nodeThread?.isAlive == true) { Log.i(TAG, "Backend already running in this runtime slot."); return }
        backendRunning = true
        backendStatus = "starting"
        refreshNotification()
        val logFile = File(filesDir, "cloudflared/${tunnelKey.take(12)}.node.log")
        try {
            logFile.parentFile?.mkdirs()
            if (!logFile.exists()) logFile.createNewFile()
            logFile.appendText("\n--- Starting Node: $script (entry: $entry), cwd: $workingDir, port: $port ---\n")
            val configuredDbHost = File(workingDir, ".env").takeIf { it.isFile }
                ?.useLines { lines -> lines.firstNotNullOfOrNull { line ->
                    val trimmed = line.trim()
                    if (!trimmed.startsWith("DB_HOST=")) null else trimmed.substringAfter('=').trim().trim('"', '\'')
                } }
            if (configuredDbHost.equals("localhost", ignoreCase = true) || configuredDbHost == "127.0.0.1" || configuredDbHost == "::1") {
                logFile.appendText("NOTICE: .env DB_HOST points to this Android phone. MySQL must be running on the phone, or change DB_HOST to a database host reachable from the phone.\n")
            }
            val errorCode = NodeEngine.redirectOutput(logFile.absolutePath)
            if (errorCode != 0) {
                Log.e(TAG, "Could not open backend output log (errno=$errorCode): ${logFile.absolutePath}")
            }
        } catch (error: Throwable) {
            Log.e(TAG, "Could not prepare backend output log", error)
        }
        nodeThread = Thread {
            val startedAt = System.currentTimeMillis()
            val code = try {
                if (!ensureLocalMySql(workingDir, logFile)) -2 else {
                    backendRunning = true
                    backendStatus = "starting"
                    refreshNotification()
                    monitorServerPort(port)
                    val tempDir = File(cacheDir, "node-tmp").apply { mkdirs() }
                    NodeEngine.startNode(arrayOf("node", script), workingDir.absolutePath, port, filesDir.absolutePath, tempDir.absolutePath)
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Node runtime could not start", error)
                appendBackendLog(logFile, "Node runtime could not start: ${Log.getStackTraceString(error)}")
                -1
            }
            Log.i(TAG, "Node exited with $code")
            appendBackendLog(logFile, "Node process exited with code $code after ${(System.currentTimeMillis() - startedAt) / 1000.0}s.")
            backendRunning = false
            backendStatus = if (code == 0) "stopped" else "failed · exit code $code"
            stopTunnelForOriginFailure(logFile)
            refreshNotification()
            finishRuntimeIfIdle()
        }.also { it.start() }
    }

    private fun startFrontend(intent: Intent) {
        serviceLabel = "frontend"
        val root = File(intent.getStringExtra(WEB_ROOT) ?: return).canonicalFile
        val port = intent.getStringExtra(PORT) ?: "8080"
        if (!root.isDirectory || !File(root, "index.html").isFile) {
            backendStatus = "failed (site index.html missing)"
            appendBackendLog(File(filesDir, "cloudflared/${tunnelKey.take(12)}.node.log"), "Frontend site folder must contain index.html: $root\n")
            refreshNotification()
            finishRuntimeIfIdle()
            return
        }
        if (nodeThread?.isAlive == true) { Log.i(TAG, "Frontend already running in this runtime slot."); return }
        val logFile = File(filesDir, "cloudflared/${tunnelKey.take(12)}.node.log")
        logFile.parentFile?.mkdirs()
        if (!logFile.exists()) logFile.createNewFile()
        val script = File(filesDir, "runtime/static-site-server.cjs")
        script.parentFile?.mkdirs()
        script.writeText(STATIC_SITE_SERVER)
        backendRunning = true
        backendStatus = "starting frontend"
        refreshNotification()
        nodeThread = Thread {
            val startedAt = System.currentTimeMillis()
            appendBackendLog(logFile, "\n--- Starting static frontend: root=$root, port=$port ---\n")
            val code = try {
                NodeEngine.redirectOutput(logFile.absolutePath)
                monitorServerPort(port)
                NodeEngine.startNode(arrayOf("node", script.absolutePath, root.absolutePath), root.absolutePath, port, filesDir.absolutePath, File(cacheDir, "node-tmp").apply { mkdirs() }.absolutePath)
            } catch (error: Throwable) {
                Log.e(TAG, "Static frontend could not start", error)
                appendBackendLog(logFile, "Static frontend could not start: ${Log.getStackTraceString(error)}")
                -1
            }
            appendBackendLog(logFile, "Static frontend exited with code $code after ${(System.currentTimeMillis() - startedAt) / 1000.0}s.\n")
            backendRunning = false
            backendStatus = if (code == 0) "stopped" else "failed · exit code $code"
            stopTunnelForOriginFailure(logFile)
            refreshNotification()
            finishRuntimeIfIdle()
        }.also { it.start() }
    }

    private fun monitorServerPort(port: String) {
        Thread {
            val startedAt = System.currentTimeMillis()
            while (backendRunning) {
                val listening = runCatching {
                    Socket().use { it.connect(InetSocketAddress("127.0.0.1", port.toInt()), 500) }
                }.isSuccess
                val next = when {
                    listening -> "running · port $port responding"
                    System.currentTimeMillis() - startedAt < 30_000L -> "starting · waiting for port $port"
                    else -> "process active · port $port not responding"
                }
                if (backendStatus != next) {
                    backendStatus = next
                    refreshNotification()
                }
                try { Thread.sleep(1_500L) } catch (_: InterruptedException) { return@Thread }
            }
        }.start()
    }

    private fun ensureLocalMySql(workingDir: File, logFile: File): Boolean {
        val env = File(workingDir, ".env")
        if (!env.isFile) return true
        val values = env.useLines { lines -> lines.mapNotNull { raw ->
            val line = raw.trim().removePrefix("export ")
            val split = line.indexOf('=')
            if (split <= 0 || line.startsWith('#')) null
            else line.substring(0, split).trim() to line.substring(split + 1).trim().trim('"', '\'')
        }.toMap() }
        val url = values["DATABASE_URL"] ?: values["DATABASE_URI"] ?: values["DB_URL"].orEmpty()
        val dbType = (values["DB_TYPE"] ?: values["DB_DIALECT"] ?: "").lowercase()
        val host = values["DB_HOST"] ?: values["MYSQL_HOST"] ?: runCatching { java.net.URI(url).host }.getOrNull().orEmpty()
        val port = (values["DB_PORT"] ?: values["MYSQL_PORT"] ?: runCatching { java.net.URI(url).port.takeIf { it > 0 }?.toString() }.getOrNull() ?: "3306").toIntOrNull() ?: 3306
        val isMysql = dbType.contains("mysql") || dbType.contains("maria") || url.startsWith("mysql:", true) || url.startsWith("mariadb:", true)
        if (!isMysql || host !in setOf("localhost", "127.0.0.1") || port != 3306) return true

        if (!getSharedPreferences("setup", MODE_PRIVATE).getBoolean("mariadb_admin_configured", false)) {
            logFile.appendText("\nLocal MariaDB is required at 127.0.0.1:3306, but its administrator password has not been set. Open Database, set the root password, and start MariaDB.\n")
            return false
        }
        logFile.appendText("\nStarting the on-device MariaDB dependency before the backend…\n")
        try {
            val intent = Intent(this, MariaDbService::class.java).setAction(MariaDbService.ACTION_START)
            if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
        } catch (error: Throwable) {
            logFile.appendText("Could not start on-device MariaDB: ${error.message}\n")
            return false
        }
        val deadline = System.currentTimeMillis() + 60_000L
        while (System.currentTimeMillis() < deadline) {
            val connected = runCatching {
                Socket().use { it.connect(InetSocketAddress("127.0.0.1", 3306), 500) }
            }.isSuccess
            if (connected) {
                logFile.appendText("On-device MariaDB is reachable on 127.0.0.1:3306.\n")
                return true
            }
            Thread.sleep(500)
        }
        logFile.appendText("MariaDB did not open 127.0.0.1:3306 in 60 seconds; backend was not started.\n")
        return false
    }

    private fun appendBackendLog(file: File, message: String) {
        try {
            file.appendText("\n$message\n")
        } catch (error: Throwable) {
            Log.e(TAG, "Could not append backend diagnostic to ${file.absolutePath}", error)
        }
    }

    private fun startTunnel(intent: Intent) {
        val token = intent.getStringExtra(TUNNEL_TOKEN) ?: return
        if (tunnelProcess?.isAlive == true || tunnelStarting) {
            Log.i(TAG, "Cloudflare tunnel is already starting or running; keeping its current connection.")
            return
        }
        val originPort = intent.getStringExtra(PORT)?.toIntOrNull()
        if (originPort == null || originPort !in 1..65535) {
            tunnelStatus = "failed · origin port is invalid"
            refreshNotification()
            finishRuntimeIfIdle()
            return
        }
        tunnelStarting = true
        tunnelStatus = "waiting for local server · port $originPort"
        refreshNotification()
        if (!originResponds(originPort)) {
            when {
                intent.getStringExtra(WEB_ROOT).isNullOrBlank().not() && nodeThread?.isAlive != true -> startFrontend(intent)
                intent.getStringExtra(PROJECT_DIR).isNullOrBlank().not() && !intent.getStringExtra(ENTRY).isNullOrBlank() && nodeThread?.isAlive != true -> startBackend(intent)
                intent.getStringExtra(WEB_ROOT).isNullOrBlank() && intent.getStringExtra(PROJECT_DIR).isNullOrBlank() -> {
                    tunnelStarting = false
                    tunnelStatus = "failed · local server is stopped"
                    refreshNotification()
                    finishRuntimeIfIdle()
                    broadcast(stop = false)
                    return
                }
            }
        }
        Thread {
            val logFile = File(filesDir, "cloudflared/${tunnelKey.take(12)}.log").apply { parentFile?.mkdirs() }
            try {
                if (!waitForOrigin(originPort, logFile)) {
                    tunnelStarting = false
                    tunnelStatus = "failed · origin port $originPort is not responding"
                    logFile.appendText("\nCloudflare tunnel not started: local origin 127.0.0.1:$originPort did not become ready.\n")
                    refreshNotification()
                    finishRuntimeIfIdle()
                    return@Thread
                }
                tunnelStatus = "connecting"
                refreshNotification()
                val binary = File(filesDir, "runtime/cloudflared")
                val runtimeDir = binary.parentFile!!.apply { mkdirs() }
                val versionFile = File(runtimeDir, "cloudflared.version")
                if (!binary.isFile || versionFile.takeIf { it.isFile }?.readText() != CLOUDFLARED_BUNDLE_VERSION) {
                    val staged = File(runtimeDir, "cloudflared.new")
                    assets.open("runtime/arm64-v8a/cloudflared").use { input -> staged.outputStream().use { input.copyTo(it) } }
                    check(staged.setExecutable(true, true)) { "Could not mark the bundled cloudflared executable." }
                    if (binary.exists()) check(binary.delete()) { "Could not replace the old cloudflared runtime." }
                    check(staged.renameTo(binary)) { "Could not install the bundled cloudflared runtime." }
                    versionFile.writeText(CLOUDFLARED_BUNDLE_VERSION)
                }
                binary.setExecutable(true, true)
                val process = ProcessBuilder(binary.absolutePath, "tunnel", "--no-autoupdate", "run", "--protocol", "http2", "--token", token)
                    .redirectErrorStream(true).start()
                tunnelProcess = process
                tunnelStarting = false
                refreshNotification()
                process.inputStream.bufferedReader().useLines { lines ->
                    lines.forEach { line ->
                        runCatching { logFile.appendText(line + "\n") }
                        if (line.contains("Registered tunnel connection", ignoreCase = true) && tunnelStatus != "running") {
                            tunnelStatus = "running"
                            refreshNotification()
                        } else if (line.contains("ERR", ignoreCase = true) && tunnelStatus != "running") {
                            tunnelStatus = "connecting · network error"
                            refreshNotification()
                        }
                    }
                }
                val code = process.waitFor()
                if (tunnelProcess === process) {
                    tunnelProcess = null
                    tunnelStatus = if (code == 0) "stopped" else "failed · exit code $code"
                    logFile.appendText("\ncloudflared exited with code $code\n")
                    refreshNotification()
                    finishRuntimeIfIdle()
                }
            } catch (error: Throwable) {
                Log.e(TAG, "Cloudflare tunnel could not start", error)
                tunnelProcess = null
                tunnelStarting = false
                tunnelStatus = "failed · could not start"
                runCatching { logFile.appendText("\nCould not start cloudflared: ${Log.getStackTraceString(error)}\n") }
                refreshNotification()
                finishRuntimeIfIdle()
            }
        }.start()
    }

    private fun originResponds(port: Int): Boolean = runCatching {
        Socket().use { socket -> socket.connect(InetSocketAddress("127.0.0.1", port), 500) }
        true
    }.getOrDefault(false)

    private fun waitForOrigin(port: Int, logFile: File): Boolean {
        val started = System.currentTimeMillis()
        val deadline = started + 90_000L
        logFile.appendText("Waiting for the local server at 127.0.0.1:$port before connecting Cloudflare…\n")
        while (System.currentTimeMillis() < deadline) {
            if (originResponds(port)) {
                logFile.appendText("Local origin 127.0.0.1:$port is responding. Starting Cloudflare tunnel.\n")
                return true
            }
            if (System.currentTimeMillis() - started > 8_000L && nodeThread?.isAlive != true) return false
            try { Thread.sleep(500L) } catch (_: InterruptedException) { return false }
        }
        return false
    }

    private fun stopTunnelForOriginFailure(logFile: File) {
        val process = tunnelProcess ?: return
        tunnelProcess = null
        tunnelStatus = "stopped · origin server stopped"
        runCatching { logFile.appendText("\nCloudflare tunnel stopped because its local origin server exited.\n") }
        process.destroy()
    }

    private fun restartTunnel(intent: Intent) {
        val token = intent.getStringExtra(TUNNEL_TOKEN) ?: return
        val previous = tunnelProcess
        tunnelProcess = null
        tunnelStarting = true
        tunnelStatus = "restarting"
        refreshNotification()
        Thread {
            previous?.destroy()
            if (previous != null) runCatching { previous.waitFor(5, java.util.concurrent.TimeUnit.SECONDS) }
            previous?.takeIf { it.isAlive }?.destroyForcibly()
            tunnelStarting = false
            intent.putExtra(TUNNEL_TOKEN, token)
            startTunnel(intent)
        }.start()
    }

    private fun stopAll() {
        val key = tunnelKey
        heartbeatHandler.removeCallbacks(heartbeatTask)
        tunnelProcess?.destroy()
        tunnelProcess = null
        backendRunning = false
        tunnelStarting = false
        backendStatus = "stopped"
        tunnelStatus = "stopped"
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

    private fun finishRuntimeIfIdle() {
        if (!backendRunning && tunnelProcess == null && !tunnelStarting) {
            if (tunnelKey.isNotBlank()) {
                RunState.clear(this, tunnelKey)
                broadcast(stop = true)
            }
            wakeLock?.let { if (it.isHeld) it.release() }
            wakeLock = null
            stopForeground(true)
            stopSelf()
        }
    }

    private fun refreshNotification() {
        if (tunnelKey.isNotBlank()) {
            RunState.update(this, tunnelKey, tunnelName.ifBlank { tunnelKey }, backendStatus, tunnelStatus)
            broadcast(stop = false)
        }
        getSystemService(NotificationManager::class.java).notify(notificationId, buildNotification())
    }

    private fun broadcast(stop: Boolean) {
        val intent = Intent(BROADCAST).setPackage(packageName)
            .putExtra(KEY, tunnelKey).putExtra(NAME, tunnelName)
            .putExtra(SERVER_STATE, backendStatus).putExtra(TUNNEL_STATE, tunnelStatus)
            .putExtra(STATE, if (stop || (!backendRunning && tunnelProcess == null && !tunnelStarting)) STATE_STOPPED else STATE_RUNNING)
        sendBroadcast(intent)
    }

    private fun buildNotification(): Notification {
        val stop = Intent(this, javaClass).setAction(STOP)
        val pending = PendingIntent.getService(this, 28, stop, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val text = when {
            else -> "$tunnelName · $serviceLabel: $backendStatus · tunnel: $tunnelStatus"
        }
        return Notification.Builder(this, channelId).setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle("Pocket Tunnel").setContentText(text)
            .setOngoing(true).addAction(Notification.Action.Builder(null, "Stop", pending).build()).build()
    }

    override fun onDestroy() {
        heartbeatHandler.removeCallbacks(heartbeatTask)
        if (tunnelKey.isNotBlank()) {
            if (backendRunning || tunnelProcess != null || tunnelStarting) {
                backendRunning = false
                tunnelStarting = false
                backendStatus = "stopped"
                tunnelStatus = "stopped"
            }
            RunState.clear(this, tunnelKey)
            broadcast(stop = true)
        }
        tunnelProcess?.destroy()
        wakeLock?.let { if (it.isHeld) it.release() }
        super.onDestroy()
    }

    companion object {
        private const val TAG = "PocketTunnelRuntime"
        const val START_BACKEND = "dev.pockettunnel.START_BACKEND"
        const val START_FRONTEND = "dev.pockettunnel.START_FRONTEND"
        const val START_TUNNEL = "dev.pockettunnel.START_TUNNEL"
        const val RESTART_TUNNEL = "dev.pockettunnel.RESTART_TUNNEL"
        const val STOP = "dev.pockettunnel.STOP"
        const val BROADCAST = "dev.pockettunnel.RUNTIME_STATE"
        const val STATE = "runtime_state"
        const val SERVER_STATE = "server_state"
        const val TUNNEL_STATE = "tunnel_state"
        const val STATE_RUNNING = "running"
        const val STATE_STOPPED = "stopped"
        const val PROJECT_DIR = "project_dir"
        const val ENTRY = "entry"
        const val WEB_ROOT = "web_root"
        const val PORT = "port"
        const val TUNNEL_TOKEN = "tunnel_token"
        const val SERVICE_LABEL = "service_label"
        const val KEY = "tunnel_key"
        const val NAME = "tunnel_name"
        private const val CLOUDFLARED_BUNDLE_VERSION = "cloudflared-2026.10.0-android-netcgo-1"
        private val STATIC_SITE_SERVER = """
            'use strict';
            const http = require('http');
            const fs = require('fs');
            const path = require('path');
            const root = fs.realpathSync(process.argv[2]);
            const types = {'.html':'text/html; charset=utf-8','.htm':'text/html; charset=utf-8','.css':'text/css; charset=utf-8','.js':'text/javascript; charset=utf-8','.mjs':'text/javascript; charset=utf-8','.json':'application/json; charset=utf-8','.svg':'image/svg+xml','.png':'image/png','.jpg':'image/jpeg','.jpeg':'image/jpeg','.gif':'image/gif','.webp':'image/webp','.ico':'image/x-icon','.woff':'font/woff','.woff2':'font/woff2','.ttf':'font/ttf','.txt':'text/plain; charset=utf-8','.xml':'application/xml'};
            const server = http.createServer((req, res) => {
              let pathname;
              try { pathname = decodeURIComponent(new URL(req.url, 'http://localhost').pathname); } catch (_) { res.writeHead(400); return res.end('Bad request'); }
              let candidate = path.resolve(root, '.' + pathname);
              if (candidate !== root && !candidate.startsWith(root + path.sep)) { res.writeHead(403); return res.end('Forbidden'); }
              try { if (fs.statSync(candidate).isDirectory()) candidate = path.join(candidate, 'index.html'); } catch (_) {}
              if (!fs.existsSync(candidate) || !fs.statSync(candidate).isFile()) {
                if (path.extname(pathname)) { res.writeHead(404); return res.end('Not found'); }
                candidate = path.join(root, 'index.html');
              }
              res.setHeader('Content-Type', types[path.extname(candidate).toLowerCase()] || 'application/octet-stream');
              res.setHeader('X-Content-Type-Options', 'nosniff');
              if (req.method === 'HEAD') { res.writeHead(200); return res.end(); }
              if (req.method !== 'GET') { res.writeHead(405, {'Allow':'GET, HEAD'}); return res.end('Method not allowed'); }
              fs.createReadStream(candidate).on('error', () => { if (!res.headersSent) res.writeHead(404); res.end('Not found'); }).pipe(res);
            });
            server.listen(Number(process.env.PORT || 8080), '127.0.0.1', () => console.log('Pocket Tunnel frontend listening on http://127.0.0.1:' + (process.env.PORT || 8080) + ' from ' + root));
        """.trimIndent()
    }
}

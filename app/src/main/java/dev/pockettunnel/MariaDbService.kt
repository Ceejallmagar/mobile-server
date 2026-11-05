package dev.pockettunnel

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.zip.ZipInputStream

/** Runs the bundled Android ARM64 MariaDB server inside this app's private storage. */
class MariaDbService : Service() {
    @Volatile private var server: Process? = null
    @Volatile private var monitoring = false
    @Volatile private var monitorThread: Thread? = null
    @Volatile private var monitorGeneration = 0
    private val channel = "pocket_tunnel_mariadb"
    private val port = 3306
    private val statusPrefs by lazy { getSharedPreferences("mariadb_status", MODE_PRIVATE) }

    private fun setRunning(running: Boolean) {
        statusPrefs.edit().putBoolean("running", running)
            .putLong("heartbeat", if (running) System.currentTimeMillis() else 0L).apply()
    }

    private fun databasePid(): Int? = runCatching {
        File(filesDir, "mariadb/run/mariadbd.pid").readText().trim().toInt().takeIf { it > 1 }
    }.getOrNull()

    private fun isManagedPidAlive(pid: Int): Boolean = runCatching {
        val expected = File(prefixDir(), "bin/mariadbd").canonicalFile
        File("/proc/$pid/exe").canonicalFile == expected
    }.getOrDefault(false)

    private fun isDatabaseProcessAlive(): Boolean = server?.isAlive == true || databasePid()?.let(::isManagedPidAlive) == true

    private fun stopMariaDbProcess() {
        val process = server
        // Ask MariaDB to flush and shut down through its own admin client first. A
        // SIGKILL here can interrupt InnoDB recovery and make the next boot appear
        // to be a fresh installation, so only use signals as a bounded fallback.
        val pid = databasePid()?.takeIf(::isManagedPidAlive)
        val rootPassword = SecretStore(this).get(ROOT_SECRET)
        if (pid != null && !rootPassword.isNullOrBlank()) {
            runCatching {
                val prefix = prefixDir()
                processBuilder(
                    listOf(File(prefix, "bin/mariadb-admin").absolutePath, "--no-defaults", "--protocol=socket",
                        "--socket=${File(filesDir, "mariadb/run/mysql.sock").absolutePath}", "-uroot", "-p$rootPassword", "shutdown"),
                    prefix, File(filesDir, "mariadb/shutdown.log")
                ).start().waitFor(5, java.util.concurrent.TimeUnit.SECONDS)
            }
        }
        val deadline = System.currentTimeMillis() + 12_000L
        while (System.currentTimeMillis() < deadline && pid?.let(::isManagedPidAlive) == true) Thread.sleep(250L)
        if (process?.isAlive == true) {
            process.destroy()
            runCatching { process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) }
        } else if (pid?.let(::isManagedPidAlive) == true) {
            android.os.Process.sendSignal(pid, 15)
        }
        if (process?.isAlive == true) process.destroyForcibly()
        if (pid?.let(::isManagedPidAlive) == true) android.os.Process.sendSignal(pid, 15)
        server = null
        if (pid == null || !isManagedPidAlive(pid)) File(filesDir, "mariadb/run/mariadbd.pid").delete()
    }

    private fun publishRunning() {
        setRunning(true)
        updateNotification("MariaDB running · 127.0.0.1:$port")
        broadcast(true, "On-device MariaDB is running on 127.0.0.1:$port.")
        startStatusMonitor()
    }

    private fun broadcastProgress(message: String) {
        sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
            .putExtra(EXTRA_OK, false).putExtra(EXTRA_RUNNING, isDatabaseProcessAlive())
            .putExtra(EXTRA_PROGRESS, true).putExtra(EXTRA_MESSAGE, message))
    }

    private fun reportStatus() {
        val running = isDatabaseProcessAlive()
        if (running) {
            setRunning(true)
            updateNotification("MariaDB running · 127.0.0.1:$port")
            startStatusMonitor()
            broadcast(true, "MariaDB is running and accepting connections.", statusOnly = true, runningOverride = true)
        } else {
            setRunning(false)
            broadcast(false, "MariaDB is not accepting connections.", statusOnly = true, runningOverride = false)
            if (!isDatabaseProcessAlive()) {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        ensureChannel()
        startForeground(74, notification("Checking local MariaDB status…"))
        when (intent?.action) {
            ACTION_STATUS -> Thread { reportStatus() }.start()
            ACTION_STOP -> {
                monitoring = false
                monitorGeneration++
                broadcastProgress("Stopping MariaDB safely and keeping its database files…")
                Thread {
                    stopMariaDbProcess()
                    setRunning(false)
                    broadcast(false, "MariaDB stopped. Your database files were kept on this phone.")
                    stopForeground(true)
                    stopSelf()
                }.start()
            }
            ACTION_CREATE -> {
                val name = intent.getStringExtra(EXTRA_DB_NAME).orEmpty()
                val username = intent.getStringExtra(EXTRA_DB_USER).orEmpty()
                val password = intent.getStringExtra(EXTRA_DB_PASSWORD).orEmpty()
                val root = intent.getStringExtra(EXTRA_PROJECT_ROOT).orEmpty()
                Thread { createDatabase(name, username, password, root) }.start()
            }
            ACTION_LIST -> Thread {
                try {
                    ensureServer()
                    val databases = listDatabases()
                    broadcast(true, "${databases.size} on-device database${if (databases.size == 1) "" else "s"}.", databases)
                } catch (error: Throwable) {
                    broadcast(false, error.message ?: "Could not list databases.", emptyList())
                }
            }.start()
            ACTION_CONNECT -> {
                val name = intent.getStringExtra(EXTRA_DB_NAME).orEmpty()
                val username = intent.getStringExtra(EXTRA_DB_USER).orEmpty()
                val password = intent.getStringExtra(EXTRA_DB_PASSWORD).orEmpty()
                val root = intent.getStringExtra(EXTRA_PROJECT_ROOT).orEmpty()
                val remember = intent.getBooleanExtra(EXTRA_REMEMBER, false)
                Thread { connectDatabase(name, username, password, root, remember) }.start()
            }
            ACTION_DELETE -> {
                val name = intent.getStringExtra(EXTRA_DB_NAME).orEmpty()
                Thread { dropLocalDatabase(name) }.start()
            }
            else -> {
                val data = File(filesDir, "mariadb/data")
                val runtimePresent = File(prefixDir(), "bin/mariadbd").isFile
                broadcastProgress(when {
                    !runtimePresent -> "Installing the bundled MariaDB runtime for first use…"
                    data.listFiles().isNullOrEmpty() -> "Initializing MariaDB system files for first use…"
                    else -> "Starting the existing MariaDB data directory…"
                })
                Thread {
                try { ensureServer(intent?.getStringExtra(EXTRA_ADMIN_PASSWORD)) } catch (error: Throwable) {
                    setRunning(false)
                    updateNotification("MariaDB failed to start")
                    broadcast(false, error.message ?: "MariaDB failed to start.")
                }
                }.start()
            }
        }
        return START_STICKY
    }

    private fun createDatabase(name: String, username: String, password: String, projectRoot: String) {
        try {
            require(name.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Use letters, numbers, and underscores for the database name." }
            require(username.matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Use letters, numbers, and underscores for the database username." }
            require(password.isNotBlank()) { "Set a password for the database user." }
            val root = File(projectRoot).canonicalFile
            require(root.isDirectory) { "Extract the project ZIP before connecting a database." }
            ensureServer()
            require(name !in listDatabases()) { "Database '$name' already exists. Choose it from the list and connect it to a project." }
            val prefix = prefixDir()
            val socket = File(filesDir, "mariadb/run/mysql.sock")
            val rootPassword = SecretStore(this).get(ROOT_SECRET) ?: error("The local database admin credential is missing. Restart MariaDB to repair it.")
            val sql = "CREATE DATABASE IF NOT EXISTS `$name`; CREATE USER IF NOT EXISTS '$username'@'127.0.0.1' IDENTIFIED BY '${sqlString(password)}'; ALTER USER '$username'@'127.0.0.1' IDENTIFIED BY '${sqlString(password)}'; GRANT ALL PRIVILEGES ON `$name`.* TO '$username'@'127.0.0.1'; CREATE USER IF NOT EXISTS '$username'@'localhost' IDENTIFIED BY '${sqlString(password)}'; ALTER USER '$username'@'localhost' IDENTIFIED BY '${sqlString(password)}'; GRANT ALL PRIVILEGES ON `$name`.* TO '$username'@'localhost'; FLUSH PRIVILEGES;"
            val cli = File(prefix, "bin/mariadb")
            val output = File(filesDir, "mariadb/setup.log")
            val process = processBuilder(listOf(cli.absolutePath, "--no-defaults", "--protocol=socket", "--socket=${socket.absolutePath}", "-uroot", "-p$rootPassword", "-e", sql), prefix, output).start()
            val code = process.waitFor()
            require(code == 0) { "MariaDB could not create the database. See ${output.absolutePath}" }
            writeProjectEnv(root, name, username, password)
            SecretStore(this).put(userSecret(name), username)
            SecretStore(this).put(passwordSecret(name), password)
            broadcast(true, "Database '$name' is ready. Project .env now points to the on-phone server.")
        } catch (error: Throwable) {
            broadcast(false, error.message ?: "Could not create the local database.")
        }
    }

    private fun connectDatabase(name: String, username: String, password: String, projectRoot: String, remember: Boolean) {
        try {
            require(name.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Invalid database name." }
            val secrets = SecretStore(this)
            val savedLogin = username.isBlank() && password.isBlank()
            val actualUsername = username.ifBlank { secrets.get(userSecret(name)).orEmpty() }
            val actualPassword = password.ifBlank { secrets.get(passwordSecret(name)).orEmpty() }
            require(actualUsername.matches(Regex("[A-Za-z0-9_]{1,32}"))) { "Enter the database username, or use a saved login." }
            require(actualPassword.isNotBlank()) { "Enter the database password, or use a saved login." }
            val root = File(projectRoot).canonicalFile
            require(root.isDirectory) { "Extract the project ZIP before connecting a database." }
            ensureServer()
            val prefix = prefixDir()
            val cli = File(prefix, "bin/mariadb")
            val output = File(filesDir, "mariadb/connect.log")
            val result = processBuilder(listOf(cli.absolutePath, "--no-defaults", "--protocol=tcp", "-h127.0.0.1", "-P$port", "-u$actualUsername", "-p$actualPassword", name, "-e", "SELECT 1"), prefix, output).start().waitFor()
            require(result == 0) { "Could not connect with that database user and password. Check the credentials and database name." }
            writeProjectEnv(root, name, actualUsername, actualPassword)
            if (remember || savedLogin) {
                secrets.put(userSecret(name), actualUsername)
                secrets.put(passwordSecret(name), actualPassword)
            } else {
                secrets.clear(userSecret(name))
                secrets.clear(passwordSecret(name))
            }
            broadcast(true, "Connected database '$name' to the selected project's .env.")
        } catch (error: Throwable) {
            broadcast(false, error.message ?: "Could not connect the database to this project.")
        }
    }

    private fun listDatabases(): List<String> {
        val prefix = prefixDir()
        val rootPassword = SecretStore(this).get(ROOT_SECRET) ?: error("The local database admin credential is missing.")
        val cli = File(prefix, "bin/mariadb")
        val sql = "SELECT SCHEMA_NAME FROM information_schema.SCHEMATA WHERE SCHEMA_NAME NOT IN ('information_schema','mysql','performance_schema','sys') ORDER BY SCHEMA_NAME"
        val process = ProcessBuilder(cli.absolutePath, "--no-defaults", "--protocol=socket", "--socket=${File(filesDir, "mariadb/run/mysql.sock").absolutePath}", "-uroot", "-p$rootPassword", "--batch", "--skip-column-names", "-e", sql)
            .redirectErrorStream(true)
            .apply {
                environment()["LD_LIBRARY_PATH"] = "${File(prefix, "lib").absolutePath}:/system/lib64"
                environment()["PATH"] = "${File(prefix, "bin").absolutePath}:/system/bin"
                environment()["HOME"] = filesDir.absolutePath
                environment()["TMPDIR"] = File(filesDir, "mariadb/tmp").absolutePath
            }.start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        require(process.waitFor() == 0) { "Could not list MariaDB databases. ${output.takeLast(500)}" }
        return output.lineSequence().map(String::trim).filter { it.matches(Regex("[A-Za-z0-9_]{1,64}")) }.toList()
    }

    private fun dropLocalDatabase(name: String) {
        try {
            require(name.matches(Regex("[A-Za-z0-9_]{1,64}"))) { "Invalid database name." }
            ensureServer()
            require(name in listDatabases()) { "Database '$name' was not found." }
            val rootPassword = SecretStore(this).get(ROOT_SECRET) ?: error("The local database admin credential is missing.")
            val cli = File(prefixDir(), "bin/mariadb")
            val socket = File(filesDir, "mariadb/run/mysql.sock")
            val sql = "DROP DATABASE `${name.replace("`", "``")}`;"
            val output = File(filesDir, "mariadb/delete.log")
            val result = processBuilder(listOf(cli.absolutePath, "--no-defaults", "--protocol=socket", "--socket=${socket.absolutePath}", "-uroot", "-p$rootPassword", "-e", sql), prefixDir(), output).start().waitFor()
            require(result == 0) { "MariaDB could not delete '$name'. See ${output.absolutePath}" }
            val secrets = SecretStore(this)
            secrets.clear(userSecret(name))
            secrets.clear(passwordSecret(name))
            broadcast(true, "Database '$name' and all its data were deleted.")
        } catch (error: Throwable) {
            broadcast(false, error.message ?: "Could not delete the database.")
        }
    }

    @Synchronized private fun ensureServer(adminPassword: String? = null) {
        if (isDatabaseProcessAlive() && canConnect()) {
            secureRootAccount(adminPassword)
            publishRunning()
            return
        }
        if (!isDatabaseProcessAlive() && canConnect()) {
            error("Port 3306 is serving another MariaDB process that Pocket Tunnel cannot manage. Its data was left untouched.")
        }
        val prefix = prefixDir()
        if (!File(prefix, "bin/mariadbd").isFile) {
            broadcastProgress("Installing the bundled MariaDB runtime…")
            installRuntime(prefix)
        }
        val data = File(filesDir, "mariadb/data")
        val run = File(filesDir, "mariadb/run").apply { mkdirs() }
        val tmp = File(filesDir, "mariadb/tmp").apply { mkdirs() }
        val log = File(filesDir, "mariadb/server.log")
        val mysqlSystemTables = listOf("global_priv.frm", "global_priv.MAD", "user.frm", "user.MAD")
            .any { File(data, "mysql/$it").isFile }
        if (!mysqlSystemTables) {
            if (data.listFiles().isNullOrEmpty()) {
                broadcastProgress("Initializing MariaDB system files for first use…")
                initializeData(prefix, data, log)
            } else {
                error("MariaDB found existing files but could not verify its system tables. It left the data directory untouched to protect your databases. Check files/mariadb/server.log before recovery.")
            }
        }
        broadcastProgress("Starting MariaDB and checking your existing databases…")
        val executable = File(prefix, "bin/mariadbd")
        val args = listOf(
            executable.absolutePath, "--no-defaults", "--basedir=$prefix", "--datadir=${data.absolutePath}",
            "--plugin-dir=${File(prefix, "lib/mysql/plugin").absolutePath}", "--lc-messages-dir=${File(prefix, "share/mariadb").absolutePath}",
            "--character-sets-dir=${File(prefix, "share/mariadb/charsets").absolutePath}", "--tmpdir=${tmp.absolutePath}",
            "--socket=${File(run, "mysql.sock").absolutePath}", "--pid-file=${File(run, "mariadbd.pid").absolutePath}",
            "--port=$port", "--bind-address=127.0.0.1", "--skip-name-resolve", "--console"
        )
        val logStart = log.length()
        server = processBuilder(args, prefix, log).start()
        val deadline = System.currentTimeMillis() + 45_000
        while (System.currentTimeMillis() < deadline) {
            if (server?.isAlive != true) break
            if (logContainsSince(log, logStart, "ready for connections")) {
                secureRootAccount(adminPassword)
                publishRunning()
                return
            }
            Thread.sleep(450)
        }
        val detail = if (log.isFile) log.readText().takeLast(1800) else "No server log was created."
        server?.destroy(); server = null
        setRunning(false)
        error("MariaDB failed to start. $detail")
    }

    private fun secureRootAccount(requestedPassword: String? = null) {
        val secrets = SecretStore(this)
        val previousPassword = secrets.get(ROOT_SECRET)
        if (requestedPassword.isNullOrBlank() && !previousPassword.isNullOrBlank()) return
        val password = requestedPassword ?: previousPassword ?: ByteArray(32).also(SecureRandom()::nextBytes).joinToString("") { "%02x".format(it) }
        val sql = "ALTER USER 'root'@'localhost' IDENTIFIED BY '${sqlString(password)}'; CREATE USER IF NOT EXISTS 'root'@'127.0.0.1' IDENTIFIED BY '${sqlString(password)}'; ALTER USER 'root'@'127.0.0.1' IDENTIFIED BY '${sqlString(password)}';"
        val prefix = prefixDir()
        val socket = File(filesDir, "mariadb/run/mysql.sock")
        val client = File(prefix, "bin/mariadb")
        val log = File(filesDir, "mariadb/setup.log")
        val args = mutableListOf(client.absolutePath, "--no-defaults", "--protocol=socket", "--socket=${socket.absolutePath}", "-uroot")
        if (!previousPassword.isNullOrBlank()) args.add("-p$previousPassword")
        args.addAll(listOf("-e", sql))
        val process = processBuilder(args, prefix, log).start()
        val code = process.waitFor()
        require(code == 0) { "Could not secure the local MariaDB administrator account. ${if (log.isFile) log.readText().takeLast(900) else ""}" }
        secrets.put(ROOT_SECRET, password)
    }

    private fun initializeData(prefix: File, data: File, log: File) {
        data.mkdirs()
        val installer = File(prefix, "bin/mariadb-install-db")
        val args = listOf("/system/bin/sh", installer.absolutePath, "--no-defaults", "--basedir=$prefix", "--datadir=${data.absolutePath}", "--auth-root-authentication-method=normal", "--skip-test-db", "--force")
        val process = processBuilder(args, prefix, log).start()
        if (!process.waitFor(120, java.util.concurrent.TimeUnit.SECONDS)) {
            process.destroyForcibly()
            error("MariaDB system initialization took longer than 2 minutes and was stopped. Existing data files were preserved; inspect files/mariadb/server.log.")
        }
        val code = process.exitValue()
        require(code == 0) { "MariaDB data initialization failed. ${if (log.isFile) log.readText().takeLast(1400) else ""}" }
    }

    private fun logContainsSince(log: File, startOffset: Long, text: String): Boolean = runCatching {
        java.io.RandomAccessFile(log, "r").use { file ->
            file.seek(startOffset.coerceAtMost(file.length()))
            val bytes = ByteArray((file.length() - file.filePointer).coerceAtMost(16_384L).toInt())
            file.readFully(bytes)
            String(bytes, Charsets.UTF_8).contains(text, ignoreCase = true)
        }
    }.getOrDefault(false)

    private fun installRuntime(prefix: File) {
        val target = File(filesDir, "mariadb")
        target.mkdirs()
        assets.open("runtime/mariadb-arm64.zip").use { raw -> ZipInputStream(raw).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                require(out.path.startsWith(target.canonicalPath + File.separator)) { "Invalid database runtime archive." }
                if (entry.isDirectory) out.mkdirs() else {
                    out.parentFile?.mkdirs()
                    out.outputStream().use { output -> zip.copyTo(output) }
                    if (entry.name.startsWith("usr/bin/")) out.setExecutable(true, true)
                }
                zip.closeEntry()
            }
        } }
        // Termux packages contain helper script shebangs and defaults for their own prefix.
        listOf("mariadb-install-db", "mysql_install_db").forEach { scriptName ->
            val script = File(prefix, "bin/$scriptName")
            if (script.isFile) script.writeText(script.readText()
                .replace("#!/data/data/com.termux/files/usr/bin/sh", "#!/system/bin/sh")
                .replace("\$dirname0//data/data/com.termux/files/usr/bin/mariadbd", "\$dirname0/bin/mariadbd")
                .replace("\$basedir//data/data/com.termux/files/usr/bin/mariadbd", "\$basedir/bin/mariadbd"))
        }
        require(File(prefix, "bin/mariadbd").setExecutable(true, true)) { "Could not mark the database server executable." }
    }

    private fun prefixDir() = File(filesDir, "mariadb/usr")

    private fun processBuilder(args: List<String>, prefix: File, log: File): ProcessBuilder = ProcessBuilder(args)
        .redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.appendTo(log.apply { parentFile?.mkdirs() }))
        .apply {
            environment()["LD_LIBRARY_PATH"] = "${File(prefix, "lib").absolutePath}:/system/lib64"
            environment()["PATH"] = "${File(prefix, "bin").absolutePath}:/system/bin"
            environment()["HOME"] = filesDir.absolutePath
            environment()["TMPDIR"] = File(filesDir, "mariadb/tmp").absolutePath
        }

    private fun canConnect(): Boolean = try {
        Socket().use { socket ->
            socket.soTimeout = 1500
            socket.connect(InetSocketAddress("127.0.0.1", port), 500)
            val header = ByteArray(4)
            var offset = 0
            while (offset < header.size) {
                val count = socket.getInputStream().read(header, offset, header.size - offset)
                if (count < 0) return false
                offset += count
            }
            val packetSize = (header[0].toInt() and 0xff) or ((header[1].toInt() and 0xff) shl 8) or ((header[2].toInt() and 0xff) shl 16)
            if (packetSize <= 0 || packetSize > 4096) return false
            val payload = ByteArray(packetSize)
            offset = 0
            while (offset < payload.size) {
                val count = socket.getInputStream().read(payload, offset, payload.size - offset)
                if (count < 0) return false
                offset += count
            }
            payload[0].toInt() == 10 && String(payload, 1, payload.size - 1, Charsets.US_ASCII).substringBefore('\u0000').contains("MariaDB", ignoreCase = true)
        }
    } catch (_: Exception) { false }

    private fun startStatusMonitor() {
        if (monitoring && monitorThread?.isAlive == true) return
        monitoring = true
        val generation = ++monitorGeneration
        monitorThread = Thread {
            while (monitoring && generation == monitorGeneration) {
                val running = isDatabaseProcessAlive()
                setRunning(running)
                if (!running) {
                    if (generation != monitorGeneration) break
                    monitoring = false
                    updateNotification("MariaDB stopped unexpectedly · tap Start in Pocket Tunnel")
                    broadcast(false, "MariaDB stopped unexpectedly. Start it again from the Database section.")
                    break
                }
                broadcast(true, "MariaDB running · 127.0.0.1:$port", statusOnly = true, runningOverride = true)
                try { Thread.sleep(3_000L) } catch (_: InterruptedException) { return@Thread }
            }
        }.also { it.start() }
    }

    private fun writeProjectEnv(root: File, database: String, username: String, password: String) {
        val file = File(root, ".env")
        val old = file.takeIf { it.isFile }?.readLines().orEmpty()
        val managed = setOf("DB_TYPE", "DB_NAME", "DB_DATABASE", "DB_HOST", "DB_PORT", "DB_USER", "DB_USERNAME", "DB_PASSWORD", "DATABASE_URL", "MYSQL_HOST", "MYSQL_PORT", "MYSQL_DATABASE", "MYSQL_USER", "MYSQL_PASSWORD")
        val keep = old.filterNot { line -> managed.any { line.trim().startsWith("$it=") } }
        val url = "mysql://${encode(username)}:${encode(password)}@127.0.0.1:$port/$database"
        val config = listOf("DB_TYPE=mysql", "DB_HOST=127.0.0.1", "DB_PORT=$port", "DB_NAME=$database", "DB_DATABASE=$database", "DB_USER=$username", "DB_USERNAME=$username", "DB_PASSWORD=$password", "DATABASE_URL=$url")
        file.writeText((keep + config).joinToString("\n") + "\n")
    }

    private fun encode(value: String) = java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    private fun sqlString(value: String) = value.replace("\\", "\\\\").replace("'", "''").replace("\u0000", "")

    private fun broadcast(ok: Boolean, message: String, databases: List<String>? = null, statusOnly: Boolean = false, runningOverride: Boolean? = null) {
        val running = isDatabaseProcessAlive()
        val adminConfigured = !SecretStore(this).get(ROOT_SECRET).isNullOrBlank()
        statusPrefs.edit().putBoolean("admin_configured", adminConfigured).apply()
        val result = Intent(ACTION_RESULT).setPackage(packageName).putExtra(EXTRA_OK, ok)
            .putExtra(EXTRA_RUNNING, runningOverride ?: running).putExtra(EXTRA_MESSAGE, message)
            .putExtra(EXTRA_ADMIN_CONFIGURED, adminConfigured)
        if (databases != null) result.putExtra(EXTRA_DATABASES, databases.toTypedArray())
        result.putExtra(EXTRA_STATUS_ONLY, statusOnly)
        sendBroadcast(result)
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= 26) getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(channel, "On-device database", NotificationManager.IMPORTANCE_LOW))
    }

    private fun notification(message: String): Notification = Notification.Builder(this, channel)
        .setSmallIcon(android.R.drawable.stat_notify_sync).setContentTitle("Pocket Tunnel · Database")
        .setContentText(message).setOngoing(true).build()

    private fun updateNotification(message: String) = getSystemService(NotificationManager::class.java).notify(74, notification(message))

    override fun onDestroy() {
        monitoring = false
        monitorGeneration++
        server?.let { process ->
            if (process.isAlive) {
                process.destroy()
                runCatching { process.waitFor(8, java.util.concurrent.TimeUnit.SECONDS) }
            }
        }
        server = null
        setRunning(false)
        super.onDestroy()
    }

    companion object {
        const val ACTION_START = "dev.pockettunnel.MARIADB_START"
        const val ACTION_STOP = "dev.pockettunnel.MARIADB_STOP"
        const val ACTION_CREATE = "dev.pockettunnel.MARIADB_CREATE"
        const val ACTION_LIST = "dev.pockettunnel.MARIADB_LIST"
        const val ACTION_CONNECT = "dev.pockettunnel.MARIADB_CONNECT"
        const val ACTION_DELETE = "dev.pockettunnel.MARIADB_DELETE"
        const val ACTION_RESULT = "dev.pockettunnel.MARIADB_RESULT"
        const val EXTRA_OK = "ok"
        const val EXTRA_RUNNING = "running"
        const val EXTRA_MESSAGE = "message"
        const val EXTRA_DB_NAME = "database_name"
        const val EXTRA_DB_USER = "database_user"
        const val EXTRA_DB_PASSWORD = "database_password"
        const val EXTRA_PROJECT_ROOT = "project_root"
        const val EXTRA_REMEMBER = "remember_database_credentials"
        const val EXTRA_DATABASES = "database_names"
        const val EXTRA_ADMIN_PASSWORD = "admin_password"
        const val EXTRA_ADMIN_CONFIGURED = "admin_configured"
        const val EXTRA_PROGRESS = "progress"
        const val EXTRA_STATUS_ONLY = "status_only"
        const val ACTION_STATUS = "dev.pockettunnel.MARIADB_STATUS"
        private const val ROOT_SECRET = "local-mariadb-root"
        private fun userSecret(name: String) = "local-db-user:$name"
        private fun passwordSecret(name: String) = "local-db-password:$name"
    }
}

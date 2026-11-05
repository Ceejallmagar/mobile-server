package dev.pockettunnel

import android.app.Activity
import android.app.AlertDialog
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.ZipInputStream
import org.json.JSONArray
import org.json.JSONObject

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }
    private val store by lazy { TunnelStore(this) }
    private lateinit var body: LinearLayout
    private var screen = "home"
    private var detailId = ""
    private var editingComplete = false
    private var draft: TunnelProfile? = null
    private var fileRelativePath = ""
    private var previewRelativePath = ""
    private var databaseProvider = ""
    private var databaseName = ""
    private var databaseHost = ""
    private var databaseError = ""
    private var databaseTables = JSONArray()
    private var databaseColumns = JSONArray()
    private var databaseRows = JSONArray()
    private var databaseLoading = false
    private var databaseAction = "tables"
    private var databaseSchema = ""
    private var databaseTable = ""
    private var databaseRequestId = ""
    private val green = Color.rgb(38, 102, 69)
    private val ink = Color.rgb(28, 43, 35)
    private val muted = Color.rgb(112, 127, 117)
    private val paper = Color.rgb(246, 248, 245)
    private val accentSoft = Color.rgb(225, 243, 230)
    private val alert = Color.rgb(196, 133, 42)
    private val running = Color.rgb(46, 145, 84)

    private val accountId get() = prefs.getString("account_id", "") ?: ""

    private val receiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            val key = intent?.getStringExtra(RuntimeService.KEY) ?: return
            if (intent.getStringExtra(RuntimeService.STATE) == RuntimeService.STATE_RUNNING) {
                RunStatus.put(key, intent.getStringExtra(RuntimeService.NAME) ?: key)
            } else {
                RunStatus.remove(key)
                RunSlots.release(this@MainActivity, key)
            }
            render()
        }
    }

    private val databaseReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.getStringExtra(DatabaseInspectorService.EXTRA_REQUEST_ID) != databaseRequestId) return
            databaseLoading = false
            val result = runCatching { JSONObject(intent.getStringExtra(DatabaseInspectorService.EXTRA_RESULT).orEmpty()) }.getOrNull()
            if (result == null || !result.optBoolean("ok")) {
                databaseError = result?.optString("error")?.takeIf { it.isNotBlank() } ?: "Database inspection failed."
            } else {
                databaseError = ""
                databaseProvider = result.optString("provider", databaseProvider)
                databaseName = result.optString("database", databaseName)
                databaseHost = result.optString("host", databaseHost)
                if (databaseAction == "tables") databaseTables = result.optJSONArray("tables") ?: JSONArray()
                databaseColumns = result.optJSONArray("columns") ?: JSONArray()
                databaseRows = result.optJSONArray("rows") ?: JSONArray()
            }
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            getSystemService(android.app.NotificationManager::class.java).createNotificationChannel(
                android.app.NotificationChannel("pocket_tunnel_runtime", "Backend and tunnel", android.app.NotificationManager.IMPORTANCE_LOW)
            )
        }
        store.migrateLegacy(prefs, secrets)
        RunStatus.seed(this)
        registerReceiver(receiver, IntentFilter(RuntimeService.BROADCAST))
        registerReceiver(databaseReceiver, IntentFilter(DatabaseInspectorService.ACTION_RESULT))
        render()
    }

    override fun onDestroy() {
        runCatching { unregisterReceiver(receiver) }
        runCatching { unregisterReceiver(databaseReceiver) }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (screen != "home") {
            screen = when (screen) {
                "wizard" -> if (editingComplete) "detail" else "home"
                "detail" -> "home"
                "files", "databases" -> "detail"
                "file_preview" -> "files"
                "database_rows" -> "databases"
                else -> "home"
            }
            render()
        } else {
            super.onBackPressed()
        }
    }

    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(32)) }
        scroll.addView(body)
        root.addView(header(), LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        when (screen) {
            "home" -> homeScreen()
            "profile" -> profileScreen()
            "wizard" -> wizardScreen()
            "detail" -> detailScreen()
            "files" -> projectFilesScreen()
            "file_preview" -> projectFilePreviewScreen()
            "databases" -> databaseScreen()
            "database_rows" -> databaseRowsScreen()
        }
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(18), 0, dp(18), 0); setBackgroundColor(Color.WHITE)
        if (screen == "home") {
            val mark = TextView(this@MainActivity).apply { text = "↗"; textSize = 19f; setTextColor(green); gravity = Gravity.CENTER; setBackgroundColor(accentSoft) }
            addView(mark, LinearLayout.LayoutParams(dp(34), dp(34)))
            val name = TextView(this@MainActivity).apply { text = "  Pocket Tunnel"; textSize = 17f; setTextColor(ink); setTypeface(null, 1) }
            addView(name)
            val menu = TextView(this@MainActivity).apply { text = "PROFILE"; textSize = 10f; setTextColor(green); gravity = Gravity.END or Gravity.CENTER_VERTICAL; setOnClickListener { openProfile() } }
            addView(menu, LinearLayout.LayoutParams(0, -1, 1f))
        } else {
            val back = TextView(this@MainActivity).apply {
                text = when (screen) {
                    "profile" -> "‹  Tunnels"
                    "wizard" -> "‹  " + if (editingComplete) "Tunnel" else "Tunnels"
                    "files" -> "‹  ${store.get(detailId)?.name ?: "Project"}"
                    "file_preview" -> "‹  Project files"
                    "databases" -> "‹  ${store.get(detailId)?.name ?: "Database"}"
                    "database_rows" -> "‹  Tables"
                    else -> "‹  Tunnels"
                }
                textSize = 14f; setTextColor(green); setTypeface(null, 1); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { goBackScreen() }
            }
            addView(back, LinearLayout.LayoutParams(-2, -1))
            val spacer = View(this@MainActivity)
            addView(spacer, LinearLayout.LayoutParams(0, 1, 1f))
        }
    }

    private fun goBackScreen() {
        when (screen) {
            "wizard" -> screen = if (editingComplete) "detail" else "home"
            "files", "databases" -> screen = "detail"
            "file_preview" -> screen = "files"
            "database_rows" -> screen = "databases"
            else -> screen = "home"
        }
        render()
    }

    private fun openProfile() { screen = "profile"; render() }
    private fun openDetail(id: String) { detailId = id; screen = "detail"; editingComplete = false; render() }

    private fun homeScreen() {
        text(body, "POCKET TUNNEL  ·  ANDROID", 11, green, true, 0, 8)
        text(body, "Your tunnels", 28, ink, true, 0, 8)
        text(body, "Create, run, edit and delete Node.js tunnels from this phone.", 13, muted, false, 0, 17)
        accountStrip()
        val tunnels = store.all().sortedByDescending { it.createdAt }
        if (tunnels.isEmpty()) {
            emptyCard()
        } else {
            text(body, "${tunnels.size} tunnel${if (tunnels.size == 1) "" else "s"}  ·  ${RunStatus.runningCount()} running", 12, muted, true, 17, 10)
            tunnels.forEach { tunnel -> tunnelCard(tunnel) }
        }
        button("+ New tunnel", true) { openWizard(null) }
        caption("Everything is saved on this phone. Tap a tunnel for details, start, edit or delete.")
    }

    private fun accountStrip() {
        val token = secrets.get("api-token")
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); setBackgroundColor(Color.WHITE); setOnClickListener { openProfile() } }
        box.addView(TextView(this).apply {
            text = if (token.isNullOrBlank()) "Connect Cloudflare account  →" else "Cloudflare connected  ·  Account …${accountId.takeLast(6)}"
            textSize = 13f; setTextColor(if (token.isNullOrBlank()) ink else green); setTypeface(null, 1)
        })
        box.addView(TextView(this).apply {
            text = if (token.isNullOrBlank()) "Tap to add your account ID and API token." else "Tap to view your profile, verify the token, or delete all data."
            textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, 0)
        })
        body.addView(box, match(10))
    }

    private fun emptyCard() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); setBackgroundColor(Color.WHITE) }
        box.addView(TextView(this).apply { text = "No tunnels yet"; textSize = 14f; setTextColor(ink); setTypeface(null, 1) })
        box.addView(TextView(this).apply {
            text = "A tunnel serves one Node.js backend through its own Cloudflare hostname. You can run several tunnels at the same time."
            textSize = 12f; setTextColor(muted); setPadding(0, dp(6), 0, 0)
        })
        body.addView(box, match(10))
    }

    private fun tunnelCard(tunnel: TunnelProfile) {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(13), dp(15), dp(13)); setBackgroundColor(Color.WHITE)
            setOnClickListener { if (tunnel.complete) openDetail(tunnel.id) else openWizard(tunnel) }
        }
        val statusRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        statusRow.addView(TextView(this).apply { text = tunnel.name; textSize = 15f; setTextColor(ink); setTypeface(null, 1) })
        statusRow.addView(TextView(this).apply {
            text = if (RunStatus.isRunning(tunnel.id)) "   ● Running" else if (tunnel.complete) "   ○ Stopped" else "   ● Setup incomplete"
            textSize = 11f; setTextColor(if (RunStatus.isRunning(tunnel.id)) running else if (tunnel.complete) muted else alert); setTypeface(null, 1)
        }, LinearLayout.LayoutParams(0, -2, 1f))
        card.addView(statusRow)
        card.addView(TextView(this).apply {
            text = when {
                tunnel.hostname.isNotBlank() -> "https://${tunnel.hostname}"
                else -> "Hostname not set yet"
            }
            textSize = 12f; setTextColor(if (tunnel.hostname.isBlank()) muted else green); setPadding(0, dp(4), 0, 0)
        })
        card.addView(TextView(this).apply {
            text = "Port ${tunnel.port}  ·  ${tunnel.entry}  ·  ${tunnel.projectName}"
            textSize = 11f; setTextColor(muted); setPadding(0, dp(3), 0, 0)
        })
        body.addView(card, match(10))
    }

    private fun profileScreen() {
        text(body, "POCKET TUNNEL  ·  ANDROID", 11, green, true, 0, 8)
        text(body, "Profile", 28, ink, true, 0, 8)
        val token = secrets.get("api-token")
        card("Cloudflare account")
        rowCopy("Account ID ${if (accountId.isNotBlank()) "" else "(not connected)"}", accountId.ifBlank { "Not connected" }, "Account ID")
        if (token.isNullOrBlank()) {
            text(body, "No API token saved yet. It is added while creating your first tunnel.", 12, muted, false, 6, 10)
            button("Connect Cloudflare", true) { openWizard(null, 2) }
        } else {
            var hidden = true
            val maskView = TextView(this).apply {
                text = "API token: •${"•".repeat(23)}• (hidden)"
                textSize = 13f; setTextColor(ink); setPadding(dp(14), dp(12), dp(14), dp(12)); setBackgroundColor(Color.WHITE)
                setOnClickListener { hidden = !hidden; text = if (hidden) "API token: •${"•".repeat(23)}• (hidden)" else "API token: $token (tap to hide)" }
            }
            body.addView(maskView, match(6))
            button("Verify token now", true) {
                val key = secrets.get("api-token")
                if (key.isNullOrBlank()) { note("No token saved."); return@button }
                cloudflareAction("Checking Cloudflare token…", { CloudflareApi(key).verify() }) { error -> if (error == null) note("Token is valid and active.") }
            }
            caption("Cloudflare keeps the token encrypted in Android Keystore. It is scoped to Tunnel Write, Zone Read and DNS Write.")
        }
        card("On this phone")
        row("Tunnels saved", store.count().toString() + (if (RunStatus.runningCount() > 0) "  ·  ${RunStatus.runningCount()} running" else ""))
        row("Data location", "App storage on this device")
        rowCopy("App version", appVersion(), "Version")
        button("Delete all app data", false) { deleteAllDialog() }
        caption("Deleting all data removes every tunnel profile, token and extracted ZIP from this phone. Cloudflare keeps its own tunnels online.")
    }

    private fun wizardScreen() {
        val tunnel = draft ?: return
        val page = tunnel.page.coerceIn(0, 6)
        text(body, "POCKET TUNNEL  ·  ANDROID", 11, green, true, 0, 8)
        val titles = listOf("Add your backend", "Choose how it starts", "Connect Cloudflare", "Create your tunnel", "Create the subdomain", "Write the tunnel config", "Start your backend")
        text(body, titles[page], 28, ink, true, 0, 8)
        text(body, if (editingComplete) "Editing ${tunnel.name}" else "Step ${page + 1} of 7", 12, muted, false, 0, 17)
        progress(page)
        when (page) {
            0 -> projectPage(tunnel); 1 -> runtimePage(tunnel); 2 -> loginPage(tunnel); 3 -> tunnelPage(tunnel)
            4 -> routePage(tunnel); 5 -> configPage(tunnel); 6 -> startPage(tunnel)
        }
        if (page <= 2 && !tunnel.complete) button("Discard setup", false) { discardDialog(tunnel) }
    }

    private fun progress(page: Int) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(7) { i -> val bar = View(this).apply { setBackgroundColor(if (i <= page) green else Color.rgb(222, 229, 223)) }; val lp = LinearLayout.LayoutParams(0, dp(3), 1f); lp.marginEnd = dp(5); row.addView(bar, lp) }
        body.addView(row, LinearLayout.LayoutParams(-1, dp(3))); gap(body, 17)
    }

    private fun projectPage(tunnel: TunnelProfile) {
        card("This runs on your phone")
        text(body, "Your Android phone runs the Node backend locally. Cloudflare Tunnel gives it a public hostname and forwards requests back to the phone.", 14, muted, false, 0, 15)
        val name = input("Project name", tunnel.projectName)
        val file = TextView(this).apply {
            text = if (tunnel.zipPath.isBlank()) "Choose backend ZIP  →" else "ZIP selected: ${tunnel.zipName}  ·  Change"
            textSize = 13f; setTextColor(green); setPadding(dp(14), dp(15), dp(14), dp(15)); setBackgroundColor(Color.WHITE); setOnClickListener { chooseZip() }
        }
        body.addView(file, match(12))
        button("Continue", true) {
            val slug = safeSlug(name.text.toString()); if (slug.isBlank()) { note("Enter a project name first."); return@button }
            val oldDir = store.projectDirOf(tunnel)
            tunnel.projectName = store.uniqueSlug(slug, tunnel.id) { it.projectName }
            val newDir = store.projectDirOf(tunnel)
            if (oldDir.exists() && oldDir.canonicalPath != newDir.canonicalPath) oldDir.renameTo(newDir)
            advance()
        }
        caption("The ZIP is copied into this app's private storage and unpacked by the app. No terminal app is used.")
    }

    private fun runtimePage(tunnel: TunnelProfile) {
        card("Set the local server details")
        text(body, "These values are used in the tunnel config. Your backend must listen on 0.0.0.0 or localhost at this port.", 13, muted, false, 0, 10)
        val portField = input("Backend port", tunnel.port)
        val entryField = input("Node entry file", tunnel.entry)
        button("Save and continue", true) {
            val p = portField.text.toString().toIntOrNull(); if (p == null || p !in 1..65535) { note("Enter a valid port from 1 to 65535."); return@button }
            val entry = entryField.text.toString().trim()
            if (entry.isBlank() || entry.startsWith("/") || entry.split('/').any { it == ".." }) { note("Enter a safe project-relative Node entry file, such as server.js."); return@button }
            tunnel.port = p.toString(); tunnel.entry = entry
            advance()
        }
        caption("The ZIP must include its dependencies in node_modules. The app runs the selected JavaScript file with embedded Node.js.")
    }

    private fun loginPage(tunnel: TunnelProfile) {
        card("Connect this app to Cloudflare")
        text(body, "Paste a scoped Cloudflare API token and your account ID. The app uses Cloudflare's API directly; it does not open a terminal.", 14, muted, false, 0, 15)
        val account = input("Cloudflare account ID", accountId)
        val token = input("Cloudflare API token", "").apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
            setText(secrets.get("api-token").orEmpty())
        }
        button("Verify and continue", true) {
            val id = account.text.toString().trim(); val key = token.text.toString().trim()
            if (!id.matches(Regex("[a-fA-F0-9]{32}")) || key.isBlank()) { note("Enter a 32-character account ID and API token."); return@button }
            cloudflareAction("Checking Cloudflare token…", {
                CloudflareApi(key).verify()
                secrets.put("api-token", key)
                prefs.edit().putString("account_id", id).apply()
                tunnel.accountId = id
            }) { error -> if (error == null) advance() }
        }
        caption("Use a restricted token with Cloudflare Tunnel Write, Zone Read, and DNS Write for this account. The account is shared by all tunnels on this phone.")
    }

    private fun tunnelPage(tunnel: TunnelProfile) {
        card("Create one named tunnel in your account")
        if (tunnel.tunnelId.isBlank()) {
            val field = input("Tunnel name", tunnel.name)
            button("Create tunnel", true) {
                val id = safeSlug(field.text.toString()); if (id.isBlank()) { note("Enter a tunnel name."); return@button }
                tunnel.name = store.uniqueSlug(id, tunnel.id) { it.name }
                val account = accountId
                if (account.isBlank() || secrets.get("api-token").isNullOrBlank()) { note("Connect Cloudflare first."); return@button }
                cloudflareAction("Creating tunnel and retrieving its connector token…", {
                    val (uuid, connectorToken) = CloudflareApi(secrets.get("api-token")!!).createTunnel(account, tunnel.name)
                    tunnel.tunnelId = uuid
                    secrets.put("tunnel-token:${tunnel.id}", connectorToken)
                    store.save(tunnel)
                }) { error -> if (error == null) { note("Tunnel created in Cloudflare."); render() } }
            }
        } else {
            text(body, "Tunnel ID saved: ${tunnel.tunnelId}", 12, green, true, 0, 10)
            caption("This tunnel is already registered in Cloudflare. Delete it from the app to create a new Cloudflare tunnel.")
        }
        button("Continue", false) { if (tunnel.tunnelId.isBlank()) note("Create the tunnel first.") else advance() }
        caption("The connector token is encrypted on this phone and is only used to start the connector.")
    }

    private fun routePage(tunnel: TunnelProfile) {
        card("Choose the exact public hostname")
        text(body, "Enter the subdomain and domain already connected to your Cloudflare account. Example: api.example.com", 13, muted, false, 0, 10)
        val field = input("Hostname", tunnel.hostname)
        button("Create subdomain DNS route", true) {
            val host = field.text.toString().trim().lowercase(Locale.US).trimEnd('.')
            if (!host.matches(Regex("(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))) { note("Enter a full hostname, such as api.example.com."); return@button }
            if (tunnel.tunnelId.isBlank()) { note("Create and save the tunnel ID first."); return@button }
            tunnel.hostname = host
            tunnel.dnsCreated = false
            cloudflareAction("Creating the DNS hostname…", {
                val zoneName = CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).routeHostname(accountId, tunnel.tunnelId, host)
                tunnel.zoneName = zoneName
                tunnel.dnsCreated = true
                tunnel.hostname = host
                store.save(tunnel)
            }) { error -> if (error == null) note("DNS route created in Cloudflare.") }
        }
        button("DNS route created — continue", false) { if (!tunnel.dnsCreated) note("Create the DNS route first.") else advance() }
        caption("The app adds the proxied DNS record in the matching active Cloudflare zone. Next it applies the hostname and port to the tunnel config.")
    }

    private fun configPage(tunnel: TunnelProfile) {
        card("Write config.yml on this phone")
        text(body, "This config snapshot records the tunnel and hostname. The app separately applies the live ingress rule to Cloudflare.", 13, muted, false, 0, 10)
        val config = "tunnel: ${tunnel.tunnelId}\ningress:\n  - hostname: ${tunnel.hostname}\n    service: http://127.0.0.1:${tunnel.port}\n  - service: http_status:404\n"
        val preview = TextView(this).apply {
            text = config; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(ink); setPadding(dp(13), dp(13), dp(13), dp(13)); setBackgroundColor(Color.rgb(235, 240, 235))
        }
        body.addView(preview, match(8))
        button("Apply tunnel config", true) {
            if (tunnel.tunnelId.isBlank() || tunnel.hostname.isBlank()) { note("Finish tunnel and subdomain steps first."); return@button }
            cloudflareAction("Applying hostname and local port…", {
                CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).configureHostname(accountId, tunnel.tunnelId, tunnel.hostname, tunnel.port.toInt())
                val configFile = store.configSnapshotOf(tunnel)
                configFile.parentFile?.mkdirs()
                configFile.writeText(config)
                tunnel.configApplied = true
                store.save(tunnel)
            }) { error -> if (error == null) note("Tunnel config applied and saved on this phone.") }
        }
        button("Config saved — continue", false) { if (!tunnel.configApplied) note("Apply the tunnel config first.") else advance() }
        caption("Cloudflare stores the active tunnel route. Pocket Tunnel also keeps a config.yml copy inside app storage.")
    }

    private fun startPage(tunnel: TunnelProfile) {
        card("Run your backend and tunnel from this app")
        text(body, "The APK runs Node.js and cloudflared itself. Android will show an ongoing notification while the services run. Keep the phone powered and online.", 13, muted, false, 0, 12)
        text(body, "Project ZIP: ${tunnel.zipName.ifBlank { "not selected" }}", 12, ink, true, 0, 4)
        button("Prepare project ZIP", false) {
            if (tunnel.zipPath.isBlank()) { chooseZip(); return@button }
            extractAndReport(tunnel)
        }
        button("Test local server  ·  port ${tunnel.port}", false) { testLocalServer(tunnel) }
        button("Start backend  ·  port ${tunnel.port}", true) {
            val dir = store.projectDirOf(tunnel)
            val entryFile = File(dir, tunnel.entry).canonicalFile
            if (!entryFile.path.startsWith(dir.canonicalPath + File.separator) || !entryFile.isFile) { note("Extract the ZIP and check the Node entry file path first."); return@button }
            startBackendService(tunnel)
            note("Starting the backend on port ${tunnel.port}.")
        }
        button("Start Cloudflare Tunnel", true) {
            val connectorToken = secrets.get("tunnel-token:${tunnel.id}")
            if (connectorToken.isNullOrBlank()) { note("Create the Cloudflare tunnel first."); return@button }
            startTunnelService(tunnel)
            note("Starting the Cloudflare connector.")
        }
        button("View backend and tunnel output", false) { showBackendLog(tunnel) }
        text(body, if (tunnel.hostname.isBlank()) "Your public address will appear here after you finish the hostname step." else "Public address: https://${tunnel.hostname}", 14, green, true, 0, 14)
        if (!tunnel.complete) {
            button("Save tunnel", true) { tunnel.complete = true; store.save(tunnel); openDetail(tunnel.id); note("Tunnel saved. You can start it any time from the tunnel list.") }
        } else {
            button("Done", true) { store.save(tunnel); openDetail(tunnel.id) }
        }
        button("Back", false) { wizardBack() }
    }

    private fun detailScreen() {
        val tunnel = store.get(detailId) ?: run { screen = "home"; render(); return }
        text(body, "POCKET TUNNEL  ·  ANDROID", 11, green, true, 0, 8)
        text(body, tunnel.name, 28, ink, true, 0, 8)
        val isRunning = RunStatus.isRunning(tunnel.id)
        text(body, if (isRunning) "● Running now" else if (tunnel.complete) "○ Stopped" else "● Setup incomplete", 13, if (isRunning) running else muted, true, 0, 17)
        card("Public address")
        rowCopy("URL", if (tunnel.hostname.isBlank()) "Hostname not set yet" else "https://${tunnel.hostname}", "URL")
        card("Backend")
        rowCopy("Node entry file", tunnel.entry, "Entry file")
        rowCopy("Local port", tunnel.port, "Port")
        row("Project folder", tunnel.projectName)
        row("Working directory", store.projectDirOf(tunnel).absolutePath)
        row("Backend ZIP", tunnel.zipName.ifBlank { "not selected" })
        card("Project tools")
        button("Browse extracted project files", false) { openProjectFiles(tunnel) }
        button("Database manager", false) { openDatabaseManager(tunnel) }
        caption("Browse every extracted file or inspect the database configured for this backend.")
        card("Cloudflare tunnel")
        rowCopy("Tunnel ID", tunnel.tunnelId.ifBlank { "not created yet" }, "Tunnel ID")
        row("DNS zone", tunnel.zoneName.ifBlank { "not routed yet" })
        rowCopy("Account ID", tunnel.accountId.ifBlank { accountId }, "Account ID")
        row("Created", DateFormat.getDateTimeInstance().format(Date(tunnel.createdAt)))
        gap(body, 6)
        if (isRunning) {
            button("Stop tunnel", true) { stopTunnel(tunnel); note("Stopping ${tunnel.name} on this phone.") }
            button("Test local server  ·  port ${tunnel.port}", false) { testLocalServer(tunnel) }
            button("View backend output", false) { showBackendLog(tunnel) }
            caption("Stop ends both the Node backend and the cloudflared connector for this tunnel.")
        } else {
            button("Extract project ZIP", false) {
                if (tunnel.zipPath.isBlank() || !File(tunnel.zipPath).isFile) { note("The selected ZIP file is missing. Edit the tunnel and choose the ZIP again."); return@button }
                extractAndReport(tunnel)
            }
            button("Test local server  ·  port ${tunnel.port}", false) { testLocalServer(tunnel) }
            button("Start backend  ·  port ${tunnel.port}", true) {
                val dir = store.projectDirOf(tunnel)
                val entryFile = File(dir, tunnel.entry).canonicalFile
                if (!entryFile.path.startsWith(dir.canonicalPath + File.separator) || !entryFile.isFile) { note("Extract the ZIP and check the Node entry file path first."); return@button }
                startBackendService(tunnel)
                note("Starting the backend on port ${tunnel.port}.")
            }
            button("Start Cloudflare Tunnel", true) {
                val connectorToken = secrets.get("tunnel-token:${tunnel.id}")
                if (connectorToken.isNullOrBlank()) { note("The connector token is missing. Delete this tunnel and create it again."); return@button }
                startTunnelService(tunnel)
                note("Starting the Cloudflare connector.")
            }
            button("View backend output", false) { showBackendLog(tunnel) }
            caption("If the public URL reports 502, run Test local server. A refused connection means the backend is not listening; a valid page means the port or hostname config is the problem.")
            caption("Keep the phone powered and online. Android battery controls may still stop services.")
        }
        button("Edit tunnel", false) { openWizard(tunnel) }
        button("Delete tunnel", false) { deleteDialog(tunnel) }
    }

    private fun openProjectFiles(tunnel: TunnelProfile) {
        detailId = tunnel.id
        fileRelativePath = ""
        previewRelativePath = ""
        screen = "files"
        render()
    }

    private fun projectFilesScreen() {
        val tunnel = store.get(detailId) ?: run { screen = "home"; render(); return }
        val root = store.projectDirOf(tunnel).canonicalFile
        text(body, "PROJECT FILES", 11, green, true, 0, 8)
        text(body, tunnel.projectName, 26, ink, true, 0, 5)
        text(body, "Everything extracted from ${tunnel.zipName.ifBlank { "the backend ZIP" }} is shown here. Open folders to browse their contents.", 13, muted, false, 0, 14)
        if (!root.isDirectory) {
            card("Project ZIP not extracted")
            button("Extract project ZIP", true) {
                if (tunnel.zipPath.isBlank() || !File(tunnel.zipPath).isFile) {
                    note("The selected ZIP is missing. Edit the tunnel and choose it again.")
                } else {
                    extractAndReport(tunnel)
                    fileRelativePath = ""
                    render()
                }
            }
            return
        }

        val current = File(root, fileRelativePath).canonicalFile
        if (!current.path.startsWith(root.path)) {
            fileRelativePath = ""
            render()
            return
        }
        text(body, if (fileRelativePath.isBlank()) "/${root.name}" else "/${root.name}/$fileRelativePath", 11, muted, false, 2, 10)
        if (fileRelativePath.isNotBlank()) button("‹  Parent folder", false) {
            fileRelativePath = File(fileRelativePath).parent?.takeIf { it != "." }.orEmpty()
            render()
        }

        val entries = current.listFiles()?.sortedWith(compareBy<File>({ !it.isDirectory }, { it.name.lowercase(Locale.US) })).orEmpty()
        if (entries.isEmpty()) {
            card("This folder is empty")
            return
        }
        entries.forEach { entry ->
            val relative = entry.relativeTo(root).path
            val item = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(14), dp(11), dp(14), dp(11))
                setBackgroundColor(Color.WHITE)
                isClickable = true
                isFocusable = true
                setOnClickListener {
                    if (entry.isDirectory) {
                        fileRelativePath = relative
                        render()
                    } else {
                        previewRelativePath = relative
                        screen = "file_preview"
                        render()
                    }
                }
            }
            item.addView(TextView(this).apply {
                text = (if (entry.isDirectory) "📁  " else "📄  ") + entry.name
                textSize = 13f
                setTextColor(ink)
            })
            item.addView(TextView(this).apply {
                text = if (entry.isDirectory) "Folder" else formatFileSize(entry.length())
                textSize = 11f
                setTextColor(muted)
                setPadding(0, dp(3), 0, 0)
            })
            body.addView(item, match(5))
        }
    }

    private fun projectFilePreviewScreen() {
        val tunnel = store.get(detailId) ?: run { screen = "home"; render(); return }
        val root = store.projectDirOf(tunnel).canonicalFile
        val file = File(root, previewRelativePath).canonicalFile
        text(body, "PROJECT FILE", 11, green, true, 0, 8)
        text(body, file.name, 24, ink, true, 0, 6)
        text(body, "/${tunnel.projectName}/$previewRelativePath  ·  ${formatFileSize(file.length())}", 11, muted, false, 0, 12)
        if (!file.path.startsWith(root.path + File.separator) || !file.isFile) {
            card("File unavailable")
            caption("This file is no longer inside the extracted project folder.")
            return
        }
        val extension = file.extension.lowercase(Locale.US)
        val textExtensions = setOf("txt", "md", "json", "js", "cjs", "mjs", "ts", "tsx", "jsx", "html", "css", "xml", "yml", "yaml", "env", "sql", "py", "java", "kt", "properties", "sh", "log", "conf", "ini", "toml", "csv")
        if (extension !in textExtensions) {
            card("No text preview")
            caption("This file type can be browsed, but Pocket Tunnel does not render it as text.")
            return
        }
        if (file.length() > 512 * 1024) {
            card("File is too large to preview")
            caption("The file is ${formatFileSize(file.length())}. The browser still shows it in the project folder.")
            return
        }
        val bytes = ByteArray(128 * 1024)
        val count = runCatching { file.inputStream().use { it.read(bytes) } }.getOrElse {
            card("Could not read file")
            caption(it.message ?: "File read failed.")
            return
        }
        if (count <= 0) {
            card("Empty file")
            return
        }
        if (bytes.take(count).any { it == 0.toByte() }) {
            card("Binary file")
            caption("This file contains binary data and is not shown as text.")
            return
        }
        val content = String(bytes, 0, count, Charsets.UTF_8)
        val preview = TextView(this).apply {
            text = content
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ink)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setBackgroundColor(Color.WHITE)
        }
        body.addView(preview, match(8))
        if (file.length() > count) caption("Preview is limited to the first 128 KB.")
        if (extension in textExtensions) button("Edit this file", true) {
            val editor = EditText(this).apply {
                setText(file.readText(Charsets.UTF_8))
                typeface = android.graphics.Typeface.MONOSPACE
                gravity = Gravity.TOP or Gravity.START
                minLines = 12
                setPadding(dp(12), dp(10), dp(12), dp(10))
            }
            AlertDialog.Builder(this).setTitle("Edit ${file.name}").setView(ScrollView(this).apply { addView(editor) })
                .setNegativeButton("Cancel", null)
                .setPositiveButton("Save") { _, _ ->
                    runCatching { file.writeText(editor.text.toString(), Charsets.UTF_8) }
                        .onSuccess { note("Saved ${file.name}."); render() }
                        .onFailure { note("Could not save file: ${it.message}") }
                }.show()
        }
    }

    private fun formatFileSize(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "${bytes / 1024} KB"
        else -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024.0))
    }

    private fun openDatabaseManager(tunnel: TunnelProfile) {
        detailId = tunnel.id
        databaseProvider = ""
        databaseName = ""
        databaseHost = ""
        databaseError = ""
        databaseTables = JSONArray()
        databaseColumns = JSONArray()
        databaseRows = JSONArray()
        databaseSchema = ""
        databaseTable = ""
        screen = "databases"
        requestDatabaseInspection("tables")
    }

    private fun requestDatabaseInspection(action: String, schema: String = "", table: String = "") {
        val tunnel = store.get(detailId) ?: return
        val projectRoot = databaseWorkingDirectory(tunnel)
        if (!projectRoot.isDirectory) {
            databaseError = "Extract the backend ZIP first, then open Database manager again."
            databaseLoading = false
            render()
            return
        }
        if (!File(projectRoot, ".env").isFile) {
            databaseError = "Set up a database connection first."
            databaseLoading = false
            render()
            return
        }
        databaseAction = action
        databaseSchema = schema
        databaseTable = table
        databaseLoading = true
        databaseError = ""
        databaseRequestId = UUID.randomUUID().toString()
        render()
        startService(Intent(this, DatabaseInspectorService::class.java).apply {
            this.action = DatabaseInspectorService.ACTION_INSPECT
            putExtra(DatabaseInspectorService.EXTRA_REQUEST_ID, databaseRequestId)
            putExtra(DatabaseInspectorService.EXTRA_PROJECT_ROOT, projectRoot.absolutePath)
            putExtra(DatabaseInspectorService.EXTRA_ACTION, action)
            putExtra(DatabaseInspectorService.EXTRA_SCHEMA, schema)
            putExtra(DatabaseInspectorService.EXTRA_TABLE, table)
        })
    }

    private fun databaseWorkingDirectory(tunnel: TunnelProfile): File {
        val root = store.projectDirOf(tunnel).canonicalFile
        val entry = File(root, tunnel.entry).canonicalFile
        if (!entry.path.startsWith(root.path + File.separator) || !entry.isFile) return root
        return generateSequence(entry.parentFile) { it.parentFile }
            .takeWhile { it.path == root.path || it.path.startsWith(root.path + File.separator) }
            .firstOrNull { File(it, "package.json").isFile } ?: root
    }

    private fun databaseScreen() {
        val tunnel = store.get(detailId) ?: run { screen = "home"; render(); return }
        text(body, "DATABASE MANAGER", 11, green, true, 0, 8)
        text(body, "${tunnel.projectName} database", 26, ink, true, 0, 6)
        text(body, "Set up the connection for this project, then browse its tables and data here.", 13, muted, false, 0, 12)
        button("Configure database connection", true) { showDatabaseConfig(tunnel) }
        caption("SQLite is stored on this phone. MySQL, PostgreSQL and MongoDB settings connect to a server you already run; this app does not yet install those server engines on Android.")
        card("Connection")
        if (databaseProvider.isNotBlank()) row("Database type", databaseProvider)
        if (databaseName.isNotBlank()) row("Database", databaseName)
        if (databaseHost.isNotBlank()) row("Host", databaseHost)
        button(if (databaseLoading) "Connecting to database…" else "Refresh tables", true) {
            if (!databaseLoading) requestDatabaseInspection("tables")
        }
        if (databaseLoading) loadingRow(if (databaseAction == "rows") "Loading up to 100 rows" else "Connecting and loading tables")
        if (databaseProvider.isNotBlank() && databaseError.isBlank() && !databaseLoading) statusPill("Connected · $databaseProvider", running)
        if (databaseError.isNotBlank()) {
            card("Could not open database")
            text(body, databaseError, 12, alert, false, 0, 8)
        }
        if (!databaseLoading && databaseAction == "tables" && databaseError.isBlank()) {
            card("Tables and collections")
            if (databaseTables.length() == 0) {
                text(body, "No tables were returned. Check the database name and the account's read permissions.", 12, muted, false, 0, 8)
            } else {
                for (i in 0 until databaseTables.length()) {
                    val item = databaseTables.optJSONObject(i) ?: continue
                    val tableName = item.optString("name")
                    val schema = item.optString("schema")
                    val kind = item.optString("kind", "table")
                    val button = TextView(this).apply {
                        text = "$tableName  ·  $kind"
                        textSize = 13f
                        setTextColor(green)
                        setPadding(dp(14), dp(13), dp(14), dp(13))
                        setBackgroundColor(Color.WHITE)
                        isClickable = true
                        isFocusable = true
                        setOnClickListener {
                            databaseSchema = schema
                            databaseTable = tableName
                            screen = "database_rows"
                            requestDatabaseInspection("rows", schema, tableName)
                        }
                    }
                    body.addView(button, match(5))
                }
            }
        }
        caption("MySQL/MariaDB, PostgreSQL and MongoDB use the matching driver in the ZIP's node_modules. SQLite files are opened directly by Android. Queries are read-only and limited to 100 rows per table.")
    }

    private fun showDatabaseConfig(tunnel: TunnelProfile) {
        val root = databaseWorkingDirectory(tunnel)
        if (!root.isDirectory) { note("Extract the project ZIP first."); return }
        val envFile = File(root, ".env")
        val env = envFile.takeIf { it.isFile }?.readLines().orEmpty()
        fun current(key: String, fallback: String = ""): String = env.firstOrNull { it.trim().startsWith("$key=") }?.substringAfter('=')?.trim()?.trim('"', '\'') ?: fallback
        val panel = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(8), dp(4), dp(8), dp(4)) }
        val provider = Spinner(this)
        provider.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, listOf("SQLite (on this phone)", "MySQL / MariaDB", "PostgreSQL", "MongoDB"))
        val type = current("DB_TYPE").lowercase()
        provider.setSelection(when { type.contains("mysql") || type.contains("maria") -> 1; type.contains("postgres") || type == "pg" -> 2; type.contains("mongo") -> 3; else -> 0 })
        panel.addView(provider, match(4))
        val name = EditText(this).apply { hint = "Database name (or SQLite path)"; setText(current("DB_NAME", if (provider.selectedItemPosition == 0) "pocket.db" else "appdb")); setSingleLine(true) }
        val host = EditText(this).apply { hint = "Database server host (e.g. 192.168.1.20)"; setText(current("DB_HOST")); setSingleLine(true) }
        val port = EditText(this).apply { hint = "Port"; setText(current("DB_PORT", "3306")); setSingleLine(true); inputType = 2 }
        val user = EditText(this).apply { hint = "Username"; setText(current("DB_USER")); setSingleLine(true) }
        val password = EditText(this).apply { hint = "Password"; setText(current("DB_PASSWORD")); setSingleLine(true); inputType = 129 }
        listOf(name, host, port, user, password).forEach { panel.addView(it, match(3)) }
        provider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) = Unit
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, view: View?, position: Int, id: Long) {
                port.setText(when (position) { 1 -> "3306"; 2 -> "5432"; 3 -> "27017"; else -> "" })
                host.isEnabled = position != 0; port.isEnabled = position != 0; user.isEnabled = position != 0; password.isEnabled = position != 0
                name.hint = if (position == 0) "SQLite file path (e.g. pocket.db)" else "Database name"
            }
        }
        AlertDialog.Builder(this).setTitle("Database connection")
            .setMessage("This writes database connection settings to the project .env. Use SQLite for a database file on this phone, or enter a reachable server for MySQL, PostgreSQL or MongoDB.")
            .setView(ScrollView(this).apply { addView(panel) }).setNegativeButton("Cancel", null)
            .setPositiveButton("Save settings") { _, _ ->
                val kind = when (provider.selectedItemPosition) { 1 -> "mysql"; 2 -> "postgres"; 3 -> "mongodb"; else -> "sqlite" }
                val dbName = name.text.toString().trim()
                val dbHost = host.text.toString().trim()
                val dbPort = port.text.toString().trim()
                val dbUser = user.text.toString().trim()
                val dbPassword = password.text.toString()
                if (kind != "sqlite" && dbHost.isBlank()) { note("Enter the database server host. 127.0.0.1 means this Android phone."); return@setPositiveButton }
                val values = linkedMapOf("DB_TYPE" to kind, "DB_NAME" to dbName, "DB_HOST" to dbHost, "DB_PORT" to dbPort, "DB_USER" to dbUser, "DB_PASSWORD" to dbPassword)
                if (kind == "sqlite") values["DB_PATH"] = dbName
                if (kind != "sqlite") {
                    val encodedUser = java.net.URLEncoder.encode(dbUser, "UTF-8").replace("+", "%20")
                    val encodedPassword = java.net.URLEncoder.encode(dbPassword, "UTF-8").replace("+", "%20")
                    val credentials = if (dbUser.isBlank()) "" else "$encodedUser:$encodedPassword@"
                    val scheme = when (kind) { "mysql" -> "mysql"; "postgres" -> "postgresql"; else -> "mongodb" }
                    values["DATABASE_URL"] = "$scheme://$credentials$dbHost:$dbPort/$dbName"
                    if (kind == "mongodb") values["MONGODB_URI"] = values["DATABASE_URL"].orEmpty()
                }
                val managedKeys = setOf("DB_TYPE", "DB_NAME", "DB_HOST", "DB_PORT", "DB_USER", "DB_PASSWORD", "DB_PATH", "DATABASE_URL", "MONGODB_URI")
                val remaining = env.filterNot { line -> managedKeys.any { line.trim().startsWith("$it=") } }
                val escaped = values.map { (k, v) -> "$k=${v.replace("\\", "\\\\").replace("\n", "")}" }
                runCatching { root.mkdirs(); envFile.writeText((remaining + escaped).joinToString("\n") + "\n") }
                    .onSuccess { note("Database settings saved to ${envFile.name}. Restart the backend to apply them."); databaseError = "Settings saved. Tap Refresh tables to connect."; databaseAction = "tables"; render() }
                    .onFailure { note("Could not save database settings: ${it.message}") }
            }.show()
    }

    private fun databaseRowsScreen() {
        text(body, "DATABASE TABLE", 11, green, true, 0, 8)
        text(body, databaseTable, 26, ink, true, 0, 6)
        val scope = listOf(databaseSchema, databaseName).filter { it.isNotBlank() }.joinToString(" · ")
        if (scope.isNotBlank()) text(body, scope, 12, muted, false, 0, 10)
        if (databaseLoading) {
            text(body, databaseTable, 26, ink, true, 0, 6)
            loadingRow("Loading up to 100 rows from $databaseTable")
            return
        }
        if (databaseError.isNotBlank()) {
            card("Could not read this table")
            text(body, databaseError, 12, alert, false, 0, 8)
            button("Retry", true) { requestDatabaseInspection("rows", databaseSchema, databaseTable) }
            return
        }
        val columnNames = (0 until databaseColumns.length()).map { databaseColumns.optString(it) }
        val display = StringBuilder()
        for (rowIndex in 0 until databaseRows.length()) {
            val values = databaseRows.optJSONArray(rowIndex) ?: continue
            display.append("ROW ").append(rowIndex + 1).append('\n')
            for (columnIndex in columnNames.indices) {
                val value = values.opt(columnIndex)
                display.append(columnNames[columnIndex]).append(": ")
                    .append(if (value == null || value == JSONObject.NULL) "NULL" else value.toString())
                    .append('\n')
            }
            display.append('\n')
            if (display.length > 96_000) break
        }
        if (databaseRows.length() == 0) {
            card("No rows")
            caption("This table exists but is currently empty.")
            return
        }
        val values = TextView(this).apply {
            text = display.toString().take(96_000)
            textSize = 11f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ink)
            setPadding(dp(13), dp(13), dp(13), dp(13))
            setBackgroundColor(Color.WHITE)
        }
        body.addView(values, match(8))
        caption("Showing up to 100 rows. Values are clipped to 500 characters each.")
    }

    private fun openWizard(existing: TunnelProfile?, startPage: Int = 0) {
        draft = existing ?: newDraft()
        editingComplete = existing?.complete == true
        draft!!.page = startPage
        store.save(draft!!)
        screen = "wizard"
        render()
    }

    private fun newDraft(): TunnelProfile {
        return TunnelProfile(
            id = UUID.randomUUID().toString(),
            name = store.uniqueSlug("phone-backend") { it.name },
            projectName = store.uniqueSlug("my-backend") { it.projectName },
            zipName = "",
            zipPath = "",
            entry = "server.js",
            port = "3000",
            hostname = "",
            tunnelId = "",
            zoneName = "",
            dnsCreated = false,
            configApplied = false,
            accountId = accountId,
            page = 0,
            createdAt = System.currentTimeMillis(),
            complete = false)
    }

    private fun discardDialog(tunnel: TunnelProfile) {
        AlertDialog.Builder(this)
            .setTitle("Discard this setup?")
            .setMessage("The saved entries of this unfinished tunnel will be removed from the phone.")
            .setPositiveButton("Discard") { _, _ -> deleteLocal(tunnel) }
            .setNegativeButton("Keep", null)
            .show()
    }

    private fun deleteDialog(tunnel: TunnelProfile) {        AlertDialog.Builder(this)
            .setTitle("Delete ${tunnel.name}")
            .setItems(arrayOf("Delete on this phone", "Delete on this phone and in Cloudflare", "Cancel")) { _, which ->
                when (which) {
                    0 -> { stopTunnel(tunnel); deleteLocal(tunnel) }
                    1 -> {
                        cloudflareAction("Deleting tunnel from Cloudflare…", {
                            CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).deleteTunnel(tunnel.accountId.ifBlank { accountId }, tunnel.tunnelId)
                        }) { error ->
                            if (error == null) note("Tunnel deleted from Cloudflare.")
                            deleteLocal(tunnel)
                        }
                    }
                }
            }
            .show()
    }

    private fun deleteLocal(tunnel: TunnelProfile) {
        RunSlots.release(this, tunnel.id)
        RunStatus.remove(tunnel.id)
        store.deleteFiles(this, tunnel)
        secrets.clear("tunnel-token:${tunnel.id}")
        store.delete(tunnel.id)
        note("Tunnel removed from this phone.")
        screen = "home"
        render()
    }

    private fun deleteAllDialog() {
        AlertDialog.Builder(this)
            .setTitle("Delete all app data")
            .setMessage("This removes every tunnel profile, the Cloudflare token, extracted projects and uploads from this phone. Tunnels in your Cloudflare account stay online.")
            .setPositiveButton("Delete everything") { _, _ -> deleteAll() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteAll() {
        stopAllTunnels()
        store.all().forEach { tunnel ->
            store.deleteFiles(this, tunnel)
            secrets.clear("tunnel-token:${tunnel.id}")
        }
        store.all().forEach { store.delete(it.id) }
        secrets.clearAll()
        prefs.edit().clear().apply()
        getSharedPreferences("tunnels", MODE_PRIVATE).edit().clear().apply()
        getSharedPreferences("runtime_status", MODE_PRIVATE).edit().clear().apply()
        RunSlots.clearAll(this)
        RunStatus.clearAll()
        draft = null
        screen = "home"
        render()
        note("All app data deleted.")
    }

    private fun stopTunnel(tunnel: TunnelProfile) {
        val slot = RunSlots.slotOf(this, tunnel.id)
        if (slot != null) sendStopTo(RunSlots.serviceClass(slot), tunnel) else sendStopToAll(tunnel)
        RunStatus.remove(tunnel.id)
    }

    private fun stopAllTunnels() {
        RunStatus.all().keys.forEach { key ->
            val tunnel = store.get(key) ?: return@forEach
            val slot = RunSlots.slotOf(this, key)
            if (slot != null) sendStopTo(RunSlots.serviceClass(slot), tunnel) else sendStopToAll(tunnel)
            RunStatus.remove(key)
            RunSlots.release(this, key)
        }
    }

    private fun sendStopTo(cls: Class<*>, tunnel: TunnelProfile) {
        val intent = Intent(this, cls).setAction(RuntimeService.STOP)
            .putExtra(RuntimeService.KEY, tunnel.id).putExtra(RuntimeService.NAME, tunnel.name)
        startService(intent)
    }

    private fun sendStopToAll(tunnel: TunnelProfile) {
        listOf(RuntimeService::class.java, RuntimeSlot1::class.java, RuntimeSlot2::class.java, RuntimeSlot3::class.java).forEach { sendStopTo(it, tunnel) }
    }

    private fun startBackendService(tunnel: TunnelProfile) {
        val slot = RunSlots.slotFor(this, tunnel.id)
        if (slot < 0) { note("All runtime slots are busy. Stop a running tunnel first."); return }
        val intent = Intent(this, RunSlots.serviceClass(slot)).setAction(RuntimeService.START_BACKEND)
            .putExtra(RuntimeService.KEY, tunnel.id).putExtra(RuntimeService.NAME, tunnel.name)
            .putExtra(RuntimeService.PROJECT_DIR, store.projectDirOf(tunnel).absolutePath)
            .putExtra(RuntimeService.ENTRY, tunnel.entry).putExtra(RuntimeService.PORT, tunnel.port)
        startForegroundServiceCompat(intent)
        RunStatus.put(tunnel.id, tunnel.name)
    }

    private fun startTunnelService(tunnel: TunnelProfile) {
        val slot = RunSlots.slotFor(this, tunnel.id)
        if (slot < 0) { note("All runtime slots are busy. Stop a running tunnel first."); return }
        val connectorToken = secrets.get("tunnel-token:${tunnel.id}") ?: return
        val intent = Intent(this, RunSlots.serviceClass(slot)).setAction(RuntimeService.START_TUNNEL)
            .putExtra(RuntimeService.KEY, tunnel.id).putExtra(RuntimeService.NAME, tunnel.name)
            .putExtra(RuntimeService.TUNNEL_TOKEN, connectorToken)
        startForegroundServiceCompat(intent)
        RunStatus.put(tunnel.id, tunnel.name)
    }

    private fun startForegroundServiceCompat(intent: Intent) {
        if (android.os.Build.VERSION.SDK_INT >= 33 && !getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            note("Android has notifications disabled for Pocket Tunnel. Enable app notifications in Android Settings to see the runtime notification.")
        }
        if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(intent) else startService(intent)
    }

    private fun advance() {
        val tunnel = draft ?: return
        tunnel.page = (tunnel.page + 1).coerceAtMost(6)
        store.save(tunnel)
        render()
    }

    private fun wizardBack() {
        val tunnel = draft ?: return
        if (tunnel.page <= 0) { goBackScreen(); return }
        tunnel.page = (tunnel.page - 1).coerceAtLeast(0)
        store.save(tunnel)
        render()
    }

    private fun chooseZip() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip" }
        startActivityForResult(intent, 41)
    }

    @Deprecated("Deprecated by platform; retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK || data?.data == null) return
        try {
            val source = data.data!!
            val displayName = File(source.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "backend.zip").name
            if (!displayName.endsWith(".zip", true)) error("Choose a ZIP file.")
            val target = File(File(filesDir, "uploads"), "${(draft?.id ?: UUID.randomUUID().toString()).take(8)}-$displayName").apply { parentFile?.mkdirs() }
            contentResolver.openInputStream(source).use { input -> FileOutputStream(target).use { output -> input?.copyTo(output) ?: error("Could not read the selected ZIP.") } }
            draft?.zipPath = target.absolutePath
            draft?.zipName = displayName
            draft?.let { store.save(it) }
            render()
        } catch (e: Exception) { note(e.message ?: "Could not read that file location.") }
    }

    private fun cloudflareAction(message: String, work: () -> Unit, onDone: (Throwable?) -> Unit = {}) {
        note(message)
        Thread {
            val failure = try { work(); null } catch (e: Throwable) { e }
            runOnUiThread { if (failure != null) note(failure.message ?: "Cloudflare request failed."); onDone(failure) }
        }.start()
    }

    private fun extractAndReport(tunnel: TunnelProfile) {
        try {
            val dir = store.projectDirOf(tunnel)
            extractProject(File(tunnel.zipPath), dir)
            val entryFile = File(dir, tunnel.entry)
            if (entryFile.isFile) {
                note("Project extracted into app storage.")
                return
            }
            val matches = dir.walkTopDown().filter { it.isFile && !it.path.contains("/node_modules/") && it.name == tunnel.entry }.take(3).toList()
            if (matches.size == 1) {
                val relative = matches[0].toRelativeString(dir)
                note("Extracted, but ${tunnel.entry} is at $relative — edit the Node entry file to this path.")
            } else {
                note("Project extracted, but ${tunnel.entry} was not found${if (matches.size > 1) " (it matches several files)" else ""}. Check the entry file name.")
            }
        } catch (e: Exception) {
            note(e.message ?: "Could not extract ZIP.")
        }
    }

    private fun testLocalServer(tunnel: TunnelProfile) {
        note("Testing http://127.0.0.1:${tunnel.port} …")
        Thread {
            val result = try {
                val connection = URL("http://127.0.0.1:${tunnel.port}/").openConnection() as HttpURLConnection
                connection.connectTimeout = 5000
                connection.readTimeout = 5000
                connection.setRequestProperty("User-Agent", "PocketTunnel")
                val code = connection.responseCode
                val stream = if (code in 200..399) connection.inputStream else connection.errorStream
                val body = stream?.bufferedReader()?.use { it.readText() }?.take(140)?.replace(Regex("\\s+"), " ") ?: ""
                connection.disconnect()
                "HTTP $code  ·  $body" to null
            } catch (error: Throwable) {
                val logFile = File(filesDir, "cloudflared/${tunnel.id.take(12)}.node.log")
                val log = if (logFile.isFile) runCatching { logFile.readText().takeLast(5000) }.getOrDefault("") else ""
                val tunnelLogFile = File(filesDir, "cloudflared/${tunnel.id.take(12)}.log")
                val tunnelLog = if (tunnelLogFile.isFile) runCatching { tunnelLogFile.readText().takeLast(3000) }.getOrDefault("") else ""
                val message = buildString {
                    append("Nothing answered on port ${tunnel.port}.\n\n")
                    append("Connection error: ${error.message ?: "connection failed"}\n\n")
                    append("Start the backend and make sure it listens on port ${tunnel.port}.")
                    if (log.isNotBlank()) append("\n\nRecent backend output:\n$log")
                    else append("\n\nNo backend output log exists yet. Start backend once, then check View backend output.")
                    if (tunnelLog.isNotBlank()) append("\n\nRecent Cloudflare output:\n$tunnelLog")
                }
                null to message
            }
            runOnUiThread {
                val failure = result.second
                if (failure == null) note(result.first ?: "Local server responded.") else showRuntimeFailure(tunnel, failure)
            }
        }.start()
    }

    private fun showRuntimeFailure(tunnel: TunnelProfile, message: String) {
        val view = TextView(this).apply {
            text = message
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(ink)
            setPadding(dp(14), dp(14), dp(14), dp(14))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this)
            .setTitle("Local server is not responding")
            .setView(scroll)
            .setPositiveButton("View all output") { _, _ -> showBackendLog(tunnel) }
            .setNegativeButton("Close", null)
            .show()
    }

    private fun showBackendLog(tunnel: TunnelProfile) {
        val backendFile = File(filesDir, "cloudflared/${tunnel.id.take(12)}.node.log")
        val tunnelFile = File(filesDir, "cloudflared/${tunnel.id.take(12)}.log")
        val backendLog = if (backendFile.isFile) backendFile.readText().takeLast(6000) else "No backend output captured yet."
        val tunnelLog = if (tunnelFile.isFile) tunnelFile.readText().takeLast(6000) else "No Cloudflare connector output captured yet."
        val logText = "BACKEND OUTPUT\n$backendLog\n\nCLOUDFLARE TUNNEL OUTPUT\n$tunnelLog".takeLast(12000)
        val view = TextView(this).apply {
            setText(logText); textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(ink); setPadding(dp(13), dp(13), dp(13), dp(13))
        }
        val scroll = ScrollView(this).apply { addView(view) }
        AlertDialog.Builder(this).setTitle("Backend output · ${tunnel.name}").setView(scroll).setPositiveButton("Close", null).show()
    }

    private fun extractProject(zip: File, root: File) {
        val target = root.canonicalFile
        target.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val out = File(target, entry.name).canonicalFile
                require(out.path.startsWith(target.path + File.separator) || out == target) { "ZIP contains an invalid path." }
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); FileOutputStream(out).use { stream.copyTo(it) } }
                stream.closeEntry()
            }
        }
    }

    private fun safeSlug(value: String) = value.lowercase(Locale.US).trim().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(48)
    private fun appVersion() = runCatching { packageManager.getPackageInfo(packageName, 0).versionName }.getOrNull() ?: "0.3.0"

    private fun copyValue(label: String, value: String) {
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
        note("$label copied.")
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun match(bottom: Int = 0) = LinearLayout.LayoutParams(-1, -2).apply { bottomMargin = dp(bottom) }
    private fun text(parent: LinearLayout, value: String, size: Int, color: Int, bold: Boolean, top: Int, bottom: Int) {
        val view = TextView(this).apply { text = value; textSize = size.toFloat(); setTextColor(color); if (bold) setTypeface(null, 1); setPadding(0, dp(top), 0, dp(bottom)); setLineSpacing(dp(3).toFloat(), 1f) }
        parent.addView(view, match())
    }
    private fun input(label: String, value: String): EditText {
        text(body, label, 12, ink, true, 9, 5)
        return EditText(this).apply { setSingleLine(true); textSize = 14f; setTextColor(ink); setHintTextColor(muted); hint = label; setText(value); setPadding(dp(13), dp(12), dp(13), dp(12)); setBackgroundColor(Color.WHITE); body.addView(this, match(4)) }
    }
    private fun button(label: String, primary: Boolean, action: () -> Unit) {
        val view = TextView(this).apply {
            text = label; textSize = 14f; setTypeface(null, 1); gravity = Gravity.CENTER
            setTextColor(if (primary) Color.WHITE else green)
            background = android.graphics.drawable.GradientDrawable().apply {
                setColor(if (primary) green else Color.WHITE)
                cornerRadius = dp(14).toFloat()
                if (!primary) setStroke(dp(1), Color.rgb(223, 231, 224))
            }
            setPadding(dp(15), dp(14), dp(15), dp(14)); isClickable = true; isFocusable = true
            setOnClickListener { action() }
        }
        val lp = match(9); lp.topMargin = dp(9); body.addView(view, lp)
    }
    private fun loadingRow(label: String) {
        val line = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(Color.WHITE); cornerRadius = dp(14).toFloat() }
        }
        line.addView(ProgressBar(this).apply { isIndeterminate = true }, LinearLayout.LayoutParams(dp(24), dp(24)))
        line.addView(TextView(this).apply { text = label; textSize = 13f; setTextColor(ink); setPadding(dp(11), 0, 0, 0) }, LinearLayout.LayoutParams(0, -2, 1f))
        body.addView(line, match(10))
    }
    private fun statusPill(label: String, color: Int) {
        val pill = TextView(this).apply {
            text = "●  $label"; textSize = 12f; setTextColor(color); setTypeface(null, 1)
            setPadding(dp(12), dp(9), dp(12), dp(9))
            background = android.graphics.drawable.GradientDrawable().apply { setColor(accentSoft); cornerRadius = dp(18).toFloat() }
        }
        body.addView(pill, match(6))
    }
    private fun row(label: String, value: String) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE) }
        line.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(muted) })
        line.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(ink); setPadding(0, dp(2), 0, 0) })
        body.addView(line, match(4))
    }
    private fun rowCopy(label: String, value: String, copyName: String) {
        val line = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(14), dp(10), dp(14), dp(10)); setBackgroundColor(Color.WHITE); setOnClickListener { if (value.isNotBlank()) copyValue(copyName, value) } }
        line.addView(TextView(this).apply { text = label; textSize = 11f; setTextColor(muted) })
        line.addView(TextView(this).apply { text = value; textSize = 13f; setTextColor(ink); setPadding(0, dp(2), 0, 0) })
        body.addView(line, match(4))
    }
    private fun card(title: String) {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(15), dp(14), dp(15), dp(14)); setBackgroundColor(Color.WHITE) }
        box.addView(TextView(this).apply { text = title; textSize = 13f; setTextColor(ink); setTypeface(null, 1) })
        val lp = match(10); body.addView(box, lp)
    }
    private fun caption(value: String) { text(body, value, 11, muted, false, 15, 5) }
    private fun gap(parent: LinearLayout, h: Int) { parent.addView(View(this), LinearLayout.LayoutParams(1, dp(h))) }
    private fun note(message: String) { Toast.makeText(this, message, Toast.LENGTH_LONG).show() }
}

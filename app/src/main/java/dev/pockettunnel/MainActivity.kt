package dev.pockettunnel

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.*
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipInputStream
import java.util.Locale

class MainActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("setup", MODE_PRIVATE) }
    private val secrets by lazy { SecretStore(this) }
    private lateinit var body: LinearLayout
    private var page = 0
    private var zipPath = ""
    private val green = Color.rgb(38, 102, 69)
    private val ink = Color.rgb(28, 43, 35)
    private val muted = Color.rgb(112, 127, 117)
    private val paper = Color.rgb(246, 248, 245)

    private val projectName get() = prefs.getString("name", "my-backend") ?: "my-backend"
    private val tunnelName get() = prefs.getString("tunnel", "phone-backend") ?: "phone-backend"
    private val hostname get() = prefs.getString("hostname", "") ?: ""
    private val port get() = prefs.getString("port", "3000") ?: "3000"
    private val backendEntry get() = prefs.getString("entry", "server.js") ?: "server.js"
    private val tunnelId get() = prefs.getString("tunnel_id", "") ?: ""
    private val accountId get() = prefs.getString("account_id", "") ?: ""
    private val projectDir get() = File(filesDir, "projects/$projectName")

    override fun onCreate(savedInstanceState: Bundle?) { super.onCreate(savedInstanceState); zipPath = prefs.getString("zip_path", "") ?: ""; render() }

    private fun render() {
        page = prefs.getInt("page", 0).coerceIn(0, 6)
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setBackgroundColor(paper) }
        val scroll = ScrollView(this)
        body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(dp(22), dp(20), dp(22), dp(32)) }
        scroll.addView(body)
        root.addView(header(), LinearLayout.LayoutParams(-1, dp(60)))
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        text(body, "POCKET TUNNEL  ·  ANDROID", 11, green, true, 0, 8)
        val titles = listOf("Add your backend", "Choose how it starts", "Connect Cloudflare", "Create your tunnel", "Create the subdomain", "Write the tunnel config", "Start your backend")
        text(body, titles[page], 28, ink, true, 0, 8)
        text(body, "Step ${page + 1} of 7", 12, muted, false, 0, 17)
        progress(body)
        when (page) { 0 -> projectPage(); 1 -> runtimePage(); 2 -> loginPage(); 3 -> tunnelPage(); 4 -> routePage(); 5 -> configPage(); 6 -> startPage() }
    }

    private fun header(): View = LinearLayout(this).apply {
        gravity = Gravity.CENTER_VERTICAL; setPadding(dp(22), 0, dp(22), 0); setBackgroundColor(Color.WHITE)
        val mark = TextView(this@MainActivity).apply { text = "↗"; textSize = 19f; setTextColor(green); gravity = Gravity.CENTER; setBackgroundColor(Color.rgb(225, 243, 230)) }
        addView(mark, LinearLayout.LayoutParams(dp(34), dp(34)))
        val name = TextView(this@MainActivity).apply { text = "  Pocket Tunnel"; textSize = 17f; setTextColor(ink); setTypeface(null, 1) }
        addView(name)
        val menu = TextView(this@MainActivity).apply { text = "RESET"; textSize = 10f; setTextColor(muted); gravity = Gravity.END or Gravity.CENTER_VERTICAL; setOnClickListener { reset() } }
        addView(menu, LinearLayout.LayoutParams(0, -1, 1f))
    }

    private fun progress(parent: LinearLayout) {
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        repeat(7) { i -> val bar = View(this).apply { setBackgroundColor(if (i <= page) green else Color.rgb(222, 229, 223)) }; val lp = LinearLayout.LayoutParams(0, dp(3), 1f); lp.marginEnd = dp(5); row.addView(bar, lp) }
        parent.addView(row, LinearLayout.LayoutParams(-1, dp(3))); gap(parent, 17)
    }

    private fun projectPage() {
        card("This runs on your phone")
        text(body, "Your Android phone runs the Node backend locally. Cloudflare Tunnel gives it a public hostname and forwards requests back to the phone.", 14, muted, false, 0, 15)
        val name = input("Project name", projectName)
        val file = TextView(this).apply { text = if (zipPath.isBlank()) "Choose backend ZIP  →" else "ZIP selected: ${zipPath.substringAfterLast('/')}  ·  Change"; textSize = 13f; setTextColor(green); setPadding(dp(14), dp(15), dp(14), dp(15)); setBackgroundColor(Color.WHITE); setOnClickListener { chooseZip() } }
        body.addView(file, match(12));
        button("Continue", true) {
            val slug = safeSlug(name.text.toString()); if (slug.isBlank()) { note("Enter a project name first."); return@button }
            prefs.edit().putString("name", slug).apply()
            if (zipPath.isNotBlank()) prefs.edit().putString("zip_path", zipPath).apply()
            advance()
        }
        caption("The ZIP is copied into this app’s private storage and unpacked by the app. No terminal app is used.")
    }

    private fun runtimePage() {
        card("Set the local server details")
        text(body, "These values are used in the tunnel config. Your backend must listen on 0.0.0.0 or localhost at this port.", 13, muted, false, 0, 10)
        val portField = input("Backend port", port)
        val entryField = input("Node entry file", backendEntry)
        button("Save and continue", true) {
            val p = portField.text.toString().toIntOrNull(); if (p == null || p !in 1..65535) { note("Enter a valid port from 1 to 65535."); return@button }
            val entry = entryField.text.toString().trim()
            if (entry.isBlank() || entry.startsWith("/") || entry.split('/').any { it == ".." }) { note("Enter a safe project-relative Node entry file, such as server.js."); return@button }
            prefs.edit().putString("port", p.toString()).putString("entry", entry).apply(); advance()
        }
        caption("The ZIP must include its dependencies in node_modules. The app runs the selected JavaScript file with embedded Node.js.")
    }

    private fun loginPage() {
        card("Connect this app to Cloudflare")
        text(body, "Paste a scoped Cloudflare API token and your account ID. The app uses Cloudflare’s API directly; it does not open a terminal.", 14, muted, false, 0, 15)
        val account = input("Cloudflare account ID", accountId)
        val token = input("Cloudflare API token", "").apply { inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD; setText(secrets.get("api-token").orEmpty()) }
        button("Verify and continue", true) {
            val id = account.text.toString().trim(); val key = token.text.toString().trim()
            if (!id.matches(Regex("[a-fA-F0-9]{32}")) || key.isBlank()) { note("Enter a 32-character account ID and API token."); return@button }
            cloudflareAction("Checking Cloudflare token…", {
                CloudflareApi(key).verify()
                secrets.put("api-token", key); prefs.edit().putString("account_id", id).apply()
            }) { error -> if (error == null) advance() }
        }
        caption("Use a restricted token with Cloudflare Tunnel Write, Zone Read, and DNS Write for this account. Cloudflare keeps the token encrypted in Android Keystore.")
    }

    private fun tunnelPage() {
        card("Create one named tunnel in your account")
        val field = input("Tunnel name", tunnelName)
        if (tunnelId.isNotBlank()) text(body, "Tunnel ID saved: $tunnelId", 12, green, true, 0, 10)
        button("Create tunnel", true) {
            val id = safeSlug(field.text.toString()); if (id.isBlank()) { note("Enter a tunnel name."); return@button }
            prefs.edit().putString("tunnel", id).apply()
            cloudflareAction("Creating tunnel and retrieving its connector token…", {
                val (uuid, connectorToken) = CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).createTunnel(accountId, id)
                prefs.edit().putString("tunnel_id", uuid).apply(); secrets.put("tunnel-token", connectorToken)
            }) { error -> if (error == null) render() }
        }
        button("Continue", false) { if (tunnelId.isBlank()) note("Create the tunnel first.") else advance() }
        caption("The tunnel and its secret connector token are created directly through Cloudflare. The connector token is encrypted on this phone.")
    }

    private fun routePage() {
        card("Choose the exact public hostname")
        text(body, "Enter the subdomain and domain already connected to your Cloudflare account. Example: api.example.com", 13, muted, false, 0, 10)
        val field = input("Hostname", hostname)
        button("Create subdomain DNS route", true) {
            val host = field.text.toString().trim().lowercase(Locale.US).trimEnd('.')
            if (!host.matches(Regex("(?=.{4,253}$)([a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z]{2,63}"))) { note("Enter a full hostname, such as api.example.com."); return@button }
            if (tunnelId.isBlank()) { note("Create and save the tunnel ID first."); return@button }
            prefs.edit().putString("hostname", host).apply()
            prefs.edit().putBoolean("dns_created", false).apply()
            cloudflareAction("Creating the DNS hostname…", {
                val zoneName = CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).routeHostname(accountId, tunnelId, host)
                prefs.edit().putString("zone_name", zoneName).putBoolean("dns_created", true).apply()
            }) { error -> if (error == null) note("DNS route created in Cloudflare.") }
        }
        button("DNS route created — continue", false) { if (!prefs.getBoolean("dns_created", false)) note("Create the DNS route first.") else advance() }
        caption("The app adds the proxied DNS record in the matching active Cloudflare zone. Next it applies the hostname and port to the tunnel config.")
    }

    private fun configPage() {
        card("Write config.yml on this phone")
        text(body, "This config snapshot records the tunnel and hostname. The app separately applies the live ingress rule to Cloudflare.", 13, muted, false, 0, 10)
        val config = "tunnel: $tunnelId\ningress:\n  - hostname: $hostname\n    service: http://127.0.0.1:$port\n  - service: http_status:404\n"
        val preview = TextView(this).apply { text = config; textSize = 11f; typeface = android.graphics.Typeface.MONOSPACE; setTextColor(ink); setPadding(dp(13), dp(13), dp(13), dp(13)); setBackgroundColor(Color.rgb(235, 240, 235)) }
        body.addView(preview, match(8))
        button("Apply tunnel config", true) {
            if (tunnelId.isBlank() || hostname.isBlank()) { note("Finish tunnel and subdomain steps first."); return@button }
            cloudflareAction("Applying hostname and local port…", {
                CloudflareApi(secrets.get("api-token") ?: error("Connect Cloudflare first.")).configureHostname(accountId, tunnelId, hostname, port.toInt())
                val configFile = File(filesDir, "cloudflared/config.yml"); configFile.parentFile?.mkdirs(); configFile.writeText(config)
                prefs.edit().putBoolean("config_saved", true).apply()
            }) { error -> if (error == null) note("Tunnel config applied and saved on this phone.") }
        }
        button("Config saved — continue", false) { if (!prefs.getBoolean("config_saved", false)) note("Apply the tunnel config first.") else advance() }
        caption("Cloudflare stores the active tunnel route. Pocket Tunnel also keeps a config.yml copy inside app storage.")
    }

    private fun startPage() {
        card("Run your backend and tunnel from this app")
        text(body, "The APK runs Node.js and cloudflared itself. Android will show an ongoing notification while the services run. Keep the phone powered and online.", 13, muted, false, 0, 12)
        val path = prefs.getString("zip_path", "") ?: ""
        if (path.isNotBlank()) text(body, "Project ZIP: ${path.substringAfterLast('/')}", 12, ink, true, 0, 4)
        button("Prepare project ZIP", false) {
            if (path.isBlank()) { chooseZip(); return@button }
            try { extractProject(File(path)); note("Project extracted into app storage.") } catch (e: Exception) { note(e.message ?: "Could not extract ZIP.") }
        }
        button("Start backend  ·  port $port", true) {
            val entryFile = File(projectDir, backendEntry).canonicalFile
            if (!entryFile.path.startsWith(projectDir.canonicalPath + File.separator) || !entryFile.isFile) { note("Extract the ZIP and check the Node entry file path first."); return@button }
            RuntimeService.startBackend(this, projectDir, backendEntry, port)
            note("Starting the backend on port $port.")
        }
        button("Start Cloudflare Tunnel", true) {
            val connectorToken = secrets.get("tunnel-token")
            if (connectorToken.isNullOrBlank()) { note("Create the Cloudflare tunnel first."); return@button }
            RuntimeService.startTunnel(this, connectorToken)
            note("Starting the Cloudflare connector.")
        }
        text(body, if (hostname.isBlank()) "Your public address will appear here after you finish the hostname step." else "Public address: https://$hostname", 14, green, true, 0, 14)
        button("Back", false) { goBack() }
    }

    private fun advance() { prefs.edit().putInt("page", (page + 1).coerceAtMost(6)).apply(); render() }
    private fun goBack() { prefs.edit().putInt("page", (page - 1).coerceAtLeast(0)).apply(); render() }
    private fun reset() { prefs.edit().clear().apply(); secrets.clear("api-token"); secrets.clear("tunnel-token"); zipPath = ""; render() }
    private fun chooseZip() { val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply { addCategory(Intent.CATEGORY_OPENABLE); type = "application/zip" }; startActivityForResult(intent, 41) }

    @Deprecated("Deprecated by platform; retained for broad Android compatibility")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 41 || resultCode != RESULT_OK || data?.data == null) return
        try {
            val source = data.data!!
            val displayName = File(source.lastPathSegment?.substringAfterLast('/')?.substringAfterLast(':') ?: "backend.zip").name
            if (!displayName.endsWith(".zip", true)) error("Choose a ZIP file.")
            val target = File(File(filesDir, "uploads"), displayName).apply { parentFile?.mkdirs() }
            contentResolver.openInputStream(source).use { input -> FileOutputStream(target).use { output -> input?.copyTo(output) ?: error("Could not read the selected ZIP.") } }
            zipPath = target.absolutePath
            prefs.edit().putString("zip_path", zipPath).apply(); render()
        } catch (e: Exception) { note(e.message ?: "Could not read that file location.") }
    }

    private fun cloudflareAction(message: String, work: () -> Unit, onDone: (Throwable?) -> Unit = {}) {
        note(message)
        Thread {
            val failure = try { work(); null } catch (e: Throwable) { e }
            runOnUiThread { if (failure != null) note(failure.message ?: "Cloudflare request failed."); onDone(failure) }
        }.start()
    }

    private fun extractProject(zip: File) {
        val root = projectDir.canonicalFile; root.mkdirs()
        ZipInputStream(zip.inputStream().buffered()).use { stream ->
            while (true) {
                val entry = stream.nextEntry ?: break
                val out = File(root, entry.name).canonicalFile
                require(out.path.startsWith(root.path + File.separator) || out == root) { "ZIP contains an invalid path." }
                if (entry.isDirectory) out.mkdirs() else { out.parentFile?.mkdirs(); FileOutputStream(out).use { stream.copyTo(it) } }
                stream.closeEntry()
            }
        }
    }

    private fun safeSlug(value: String) = value.lowercase(Locale.US).trim().replace(Regex("[^a-z0-9-]+"), "-").trim('-').take(48)
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
        val view = TextView(this).apply { text = label; textSize = 14f; setTypeface(null, 1); gravity = Gravity.CENTER; setTextColor(if (primary) Color.WHITE else green); setBackgroundColor(if (primary) green else Color.WHITE); setPadding(dp(15), dp(15), dp(15), dp(15)); setOnClickListener { action() } }
        val lp = match(9); lp.topMargin = dp(9); body.addView(view, lp)
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

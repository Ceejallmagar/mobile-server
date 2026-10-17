package dev.pockettunnel

import android.content.Context
import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

data class TunnelProfile(
    val id: String,
    var name: String,
    var projectName: String,
    var zipName: String,
    var zipPath: String,
    var entry: String,
    var port: String,
    var hostname: String,
    var tunnelId: String,
    var zoneName: String,
    var dnsCreated: Boolean,
    var configApplied: Boolean,
    var accountId: String,
    var page: Int,
    var createdAt: Long,
    var complete: Boolean
)

/** Stores every tunnel profile as JSON in local storage so all info survives app restarts. */
class TunnelStore(private val context: Context) {
    private val prefs get() = context.getSharedPreferences("tunnels", Context.MODE_PRIVATE)

    fun all(): MutableList<TunnelProfile> = decode(prefs.getString("tunnels", null))
    fun get(id: String): TunnelProfile? = all().firstOrNull { it.id == id }
    fun save(tunnel: TunnelProfile) {
        val list = all()
        val index = list.indexOfFirst { it.id == tunnel.id }
        if (index >= 0) list[index] = tunnel else list.add(tunnel)
        persist(list)
    }
    fun delete(id: String) {
        val list = all()
        list.removeAll { it.id == id }
        persist(list)
    }
    fun count() = all().size

    fun projectDirOf(tunnel: TunnelProfile): File = File(context.filesDir, "projects/${tunnel.projectName}")
    fun configSnapshotOf(tunnel: TunnelProfile): File = File(context.filesDir, "cloudflared/${tunnel.id}.yml")

    fun deleteFiles(context: Context, tunnel: TunnelProfile) {
        projectDirOf(tunnel).deleteRecursively()
        configSnapshotOf(tunnel).delete()
        if (tunnel.zipPath.isNotBlank()) File(tunnel.zipPath).delete()
    }

    fun uniqueSlug(desired: String, excludingId: String = "", existing: (TunnelProfile) -> String): String {
        val base = desired.take(40)
        var candidate = base
        var suffix = 2
        while (all().any { it.id != excludingId && existing(it) == candidate }) {
            candidate = "$base-${++suffix}"
            if (suffix > 500) return "$base-${UUID.randomUUID().toString().take(6)}"
        }
        return candidate
    }

    private fun persist(list: List<TunnelProfile>) {
        prefs.edit().putString("tunnels", encode(list)).commit()
    }

    private fun encode(list: List<TunnelProfile>): String {
        val array = JSONArray()
        list.forEach { tunnel ->
            array.put(JSONObject()
                .put("id", tunnel.id)
                .put("name", tunnel.name)
                .put("project_name", tunnel.projectName)
                .put("zip_name", tunnel.zipName)
                .put("zip_path", tunnel.zipPath)
                .put("entry", tunnel.entry)
                .put("port", tunnel.port)
                .put("hostname", tunnel.hostname)
                .put("tunnel_id", tunnel.tunnelId)
                .put("zone_name", tunnel.zoneName)
                .put("dns_created", tunnel.dnsCreated)
                .put("config_applied", tunnel.configApplied)
                .put("account_id", tunnel.accountId)
                .put("page", tunnel.page)
                .put("created_at", tunnel.createdAt)
                .put("complete", tunnel.complete))
        }
        return array.toString()
    }

    private fun decode(raw: String?): MutableList<TunnelProfile> {
        val list = mutableListOf<TunnelProfile>()
        if (raw.isNullOrBlank()) return list
        try {
            val array = JSONArray(raw)
            for (i in 0 until array.length()) {
                val item = array.getJSONObject(i)
                list.add(TunnelProfile(
                    id = item.optString("id"),
                    name = item.optString("name"),
                    projectName = item.optString("project_name"),
                    zipName = item.optString("zip_name"),
                    zipPath = item.optString("zip_path"),
                    entry = item.optString("entry", "server.js"),
                    port = item.optString("port", "3000"),
                    hostname = item.optString("hostname"),
                    tunnelId = item.optString("tunnel_id"),
                    zoneName = item.optString("zone_name"),
                    dnsCreated = item.optBoolean("dns_created"),
                    configApplied = item.optBoolean("config_applied"),
                    accountId = item.optString("account_id"),
                    page = item.optInt("page", 0),
                    createdAt = item.optLong("created_at", if (item.has("created_at")) 0L else System.currentTimeMillis()),
                    complete = item.optBoolean("complete")))
            }
        } catch (_: Exception) { }
        return list
    }

    fun migrateLegacy(setup: SharedPreferences, secrets: SecretStore): Boolean {
        if (prefs.getString("tunnels", null)?.isNotBlank() == true) return false
        val legacyTunnelId = setup.getString("tunnel_id", null) ?: return false
        val appName = setup.getString("name", "my-backend") ?: "my-backend"
        val zip = setup.getString("zip_path", "") ?: ""
        val profile = TunnelProfile(
            id = legacyTunnelId,
            name = setup.getString("tunnel", "phone-backend") ?: "phone-backend",
            projectName = appName,
            zipName = File(zip).takeIf { it.isFile }?.name ?: "",
            zipPath = zip,
            entry = setup.getString("entry", "server.js") ?: "server.js",
            port = setup.getString("port", "3000") ?: "3000",
            hostname = setup.getString("hostname", "") ?: "",
            tunnelId = legacyTunnelId,
            zoneName = setup.getString("zone_name", "") ?: "",
            dnsCreated = setup.getBoolean("dns_created", false),
            configApplied = setup.getBoolean("config_saved", false),
            accountId = setup.getString("account_id", "") ?: "",
            page = 6,
            createdAt = System.currentTimeMillis(),
            complete = setup.getBoolean("config_saved", false) && setup.getBoolean("dns_created", false))
        save(profile)
        secrets.rename("tunnel-token", "tunnel-token:$legacyTunnelId")
        setup.edit()
            .remove("name").remove("tunnel").remove("hostname").remove("port").remove("entry")
            .remove("tunnel_id").remove("zip_path").remove("page").remove("dns_created")
            .remove("config_saved").remove("zone_name")
            .apply()
        return true
    }
}

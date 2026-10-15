package dev.pockettunnel

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.security.SecureRandom
import android.util.Base64

/** Small Cloudflare API client. Calls must run off the UI thread. */
class CloudflareApi(private val token: String) {
    private val root = "https://api.cloudflare.com/client/v4"

    private fun request(method: String, path: String, body: JSONObject? = null): JSONObject {
        val connection = URL(root + path).openConnection() as HttpURLConnection
        connection.requestMethod = method
        connection.connectTimeout = 15_000
        connection.readTimeout = 30_000
        connection.setRequestProperty("Authorization", "Bearer $token")
        connection.setRequestProperty("Content-Type", "application/json")
        if (body != null) {
            connection.doOutput = true
            connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
        }
        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
        connection.disconnect()
        val json = try { JSONObject(response) } catch (_: Exception) { JSONObject() }
        if (code !in 200..299 || !json.optBoolean("success", false)) {
            val errors = json.optJSONArray("errors") ?: JSONArray()
            val message = if (errors.length() > 0) errors.optJSONObject(0)?.optString("message") else null
            throw IllegalStateException(message?.takeIf { it.isNotBlank() } ?: "Cloudflare API request failed ($code).")
        }
        return json
    }

    fun verify() { request("GET", "/user/tokens/verify") }

    fun createTunnel(accountId: String, name: String): Pair<String, String> {
        val secretBytes = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val secret = Base64.encodeToString(secretBytes, Base64.NO_WRAP)
        val result = request("POST", "/accounts/$accountId/cfd_tunnel", JSONObject()
            .put("name", name).put("config_src", "cloudflare").put("tunnel_secret", secret)).getJSONObject("result")
        val id = result.getString("id")
        val tokenResult = request("GET", "/accounts/$accountId/cfd_tunnel/$id/token").getString("result")
        return id to tokenResult
    }

    fun routeHostname(accountId: String, tunnelId: String, hostname: String): String {
        val zone = findZone(accountId, hostname)
        val recordsPath = "/zones/${zone.getString("id")}/dns_records?name=$hostname&type=CNAME"
        val existing = request("GET", recordsPath).getJSONArray("result")
        val cname = "$tunnelId.cfargotunnel.com"
        val record = JSONObject().put("type", "CNAME").put("name", hostname).put("content", cname).put("proxied", true)
        if (existing.length() > 0) {
            val current = existing.getJSONObject(0)
            if (current.optString("content") != cname) throw IllegalStateException("A different CNAME already exists for $hostname. Change or remove it in Cloudflare first.")
            val recordId = current.getString("id")
            request("PATCH", "/zones/${zone.getString("id")}/dns_records/$recordId", record)
        } else {
            request("POST", "/zones/${zone.getString("id")}/dns_records", record)
        }
        return zone.getString("name")
    }

    fun configureHostname(accountId: String, tunnelId: String, hostname: String, port: Int) {
        val ingress = JSONArray()
            .put(JSONObject().put("hostname", hostname).put("service", "http://127.0.0.1:$port"))
            .put(JSONObject().put("service", "http_status:404"))
        request("PUT", "/accounts/$accountId/cfd_tunnel/$tunnelId/configurations", JSONObject().put("config", JSONObject().put("ingress", ingress)))
    }

    private fun findZone(accountId: String, hostname: String): JSONObject {
        val labels = hostname.split('.')
        for (start in 0 until labels.lastIndex) {
            val candidate = labels.drop(start).joinToString(".")
            val path = "/zones?name=$candidate&status=active&account.id=$accountId"
            val zones = request("GET", path).getJSONArray("result")
            if (zones.length() > 0) return zones.getJSONObject(0)
        }
        throw IllegalStateException("No active Cloudflare zone found for $hostname in this account.")
    }
}

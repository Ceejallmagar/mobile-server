package dev.pockettunnel

import android.app.Service
import android.content.Intent
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.os.IBinder
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Runs database reads in an isolated process so they cannot interfere with a running backend. */
class DatabaseInspectorService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action != ACTION_INSPECT) return START_NOT_STICKY
        val requestId = intent.getStringExtra(EXTRA_REQUEST_ID).orEmpty()
        val root = intent.getStringExtra(EXTRA_PROJECT_ROOT)?.let(::File)?.canonicalFile
        val action = intent.getStringExtra(EXTRA_ACTION).orEmpty()
        val schema = intent.getStringExtra(EXTRA_SCHEMA).orEmpty()
        val table = intent.getStringExtra(EXTRA_TABLE).orEmpty()

        Thread {
            val result = runCatching {
                val projectRoot = root ?: error("Project folder is unavailable.")
                require(projectRoot.isDirectory) { "Extract the project ZIP before opening its database." }
                val env = readEnv(File(projectRoot, ".env"))
                val source = databaseSource(env)
                if (source.provider == "SQLite") {
                    inspectSqlite(projectRoot, source.path, action, table)
                } else {
                    inspectNodeDatabase(projectRoot, source.provider, action, schema, table, requestId)
                }
            }.getOrElse { JSONObject().put("ok", false).put("error", safeMessage(it)) }.toString()

            sendBroadcast(Intent(ACTION_RESULT).setPackage(packageName)
                .putExtra(EXTRA_REQUEST_ID, requestId).putExtra(EXTRA_RESULT, result))
            stopSelf(startId)
            android.os.Handler(mainLooper).postDelayed({
                android.os.Process.killProcess(android.os.Process.myPid())
            }, 250)
        }.start()
        return START_NOT_STICKY
    }

    private data class DatabaseSource(val provider: String, val path: String = "")

    private fun readEnv(file: File): Map<String, String> {
        if (!file.isFile) return emptyMap()
        return file.readLines().mapNotNull { raw ->
            val line = raw.trim().removePrefix("export ")
            if (line.isBlank() || line.startsWith("#")) return@mapNotNull null
            val split = line.indexOf('=')
            if (split <= 0) return@mapNotNull null
            val key = line.substring(0, split).trim()
            val value = line.substring(split + 1).trim().removeSurrounding("\"").removeSurrounding("'")
            key to value
        }.toMap()
    }

    private fun databaseSource(env: Map<String, String>): DatabaseSource {
        val url = env["DATABASE_URL"] ?: env["DATABASE_URI"] ?: env["DB_URL"] ?: env["MYSQL_URL"] ?: env["PG_URL"] ?: env["SQLITE_URL"] ?: ""
        val type = (env["DB_TYPE"] ?: env["DB_DIALECT"] ?: env["DATABASE_CLIENT"] ?: "").lowercase()
        val provider = when {
            (env["MONGODB_URI"] ?: env["MONGO_URI"] ?: url).startsWith("mongodb", true) || type.contains("mongo") -> "MongoDB"
            url.startsWith("postgres://", true) || url.startsWith("postgresql://", true) || type.contains("postgres") || type == "pg" -> "PostgreSQL"
            url.startsWith("mysql://", true) || url.startsWith("mariadb://", true) || type.contains("mysql") || type.contains("maria") -> "MySQL"
            url.startsWith("file:", true) || url.startsWith("sqlite:", true) || type.contains("sqlite") || env["DB_PATH"] != null || env["SQLITE_PATH"] != null || env["DB_FILE"] != null || env["SQLITE_URL"] != null -> "SQLite"
            else -> error("No supported database config found in the project .env. Set DATABASE_URL or DB_TYPE with database connection fields.")
        }
        if (provider != "SQLite") return DatabaseSource(provider)

        val pathFromUrl = when {
            url.startsWith("file:", true) -> url.substringAfter(':')
            url.startsWith("sqlite:", true) -> url.substringAfter(':')
            else -> ""
        }.substringBefore('?')
        val configured = env["DB_PATH"] ?: env["SQLITE_PATH"] ?: env["DB_FILE"] ?: pathFromUrl.ifBlank { env["DB_NAME"].orEmpty() }
        require(configured.isNotBlank()) { "Set DB_PATH or DATABASE_URL=file:... for SQLite." }
        return DatabaseSource(provider, configured)
    }

    private fun inspectSqlite(root: File, configuredPath: String, action: String, table: String): String {
        val path = File(configuredPath).let { if (it.isAbsolute) it else File(root, configuredPath) }
        val db = SQLiteDatabase.openDatabase(path.canonicalPath, null, SQLiteDatabase.OPEN_READONLY)
        try {
            val tables = JSONArray()
            if (action == "tables") {
                db.rawQuery("SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%' ORDER BY name", null).use { cursor ->
                    while (cursor.moveToNext()) tables.put(JSONObject().put("name", cursor.getString(0)).put("schema", "").put("kind", "table"))
                }
                return JSONObject().put("ok", true).put("provider", "SQLite").put("database", path.name).put("host", "On device")
                    .put("tables", tables).put("columns", JSONArray()).put("rows", JSONArray()).toString()
            }
            require(action == "rows" && table.isNotBlank()) { "Choose a table to view its rows." }
            val safeTable = "\"${table.replace("\"", "\"\"")}\""
            val columns = JSONArray()
            val rows = JSONArray()
            db.rawQuery("SELECT * FROM $safeTable LIMIT 100", null).use { cursor -> appendCursor(cursor, columns, rows) }
            return JSONObject().put("ok", true).put("provider", "SQLite").put("database", path.name).put("host", "On device")
                .put("tables", tables).put("columns", columns).put("rows", rows).toString()
        } finally {
            db.close()
        }
    }

    private fun appendCursor(cursor: Cursor, columns: JSONArray, rows: JSONArray) {
        for (i in 0 until cursor.columnCount) columns.put(cursor.getColumnName(i))
        while (cursor.moveToNext() && rows.length() < 100) {
            val row = JSONArray()
            for (i in 0 until cursor.columnCount) {
                val value = when (cursor.getType(i)) {
                    Cursor.FIELD_TYPE_NULL -> JSONObject.NULL
                    Cursor.FIELD_TYPE_BLOB -> "[binary ${cursor.getBlob(i).size} bytes]"
                    Cursor.FIELD_TYPE_INTEGER -> cursor.getLong(i)
                    Cursor.FIELD_TYPE_FLOAT -> cursor.getDouble(i)
                    else -> cursor.getString(i)?.take(500) ?: JSONObject.NULL
                }
                row.put(value)
            }
            rows.put(row)
        }
    }

    private fun inspectNodeDatabase(root: File, provider: String, action: String, schema: String, table: String, requestId: String): String {
        require(action == "tables" || action == "rows") { "Unsupported database action." }
        val script = File(cacheDir, "database-inspector.cjs")
        assets.open("database-inspector.cjs").bufferedReader().use { script.writeText(it.readText()) }
        val resultFile = File(cacheDir, "database-result-${requestId.ifBlank { UUID.randomUUID().toString() }}.json")
        resultFile.delete()
        val code = NodeEngine.startNode(
            arrayOf("node", script.absolutePath, root.absolutePath, resultFile.absolutePath, action, provider, schema, table),
            root.absolutePath,
            "0",
            filesDir.absolutePath,
            cacheDir.absolutePath
        )
        if (!resultFile.isFile) return JSONObject().put("ok", false)
            .put("error", "Database inspection returned no result (Node exit code $code). Check that the matching DB driver is included in the ZIP's node_modules.").toString()
        return resultFile.readText().also { resultFile.delete() }
    }

    private fun safeMessage(error: Throwable): String = (error.message ?: "Database inspection failed.")
        .replace(Regex("(?i)(password\\s*[:=]\\s*)[^,\\s]+"), "\$1•••")
        .replace(Regex("(://[^:/\\s]+:)[^@/\\s]+@"), "\$1•••@")

    companion object {
        const val ACTION_INSPECT = "dev.pockettunnel.DATABASE_INSPECT"
        const val ACTION_RESULT = "dev.pockettunnel.DATABASE_RESULT"
        const val EXTRA_REQUEST_ID = "request_id"
        const val EXTRA_PROJECT_ROOT = "project_root"
        const val EXTRA_ACTION = "database_action"
        const val EXTRA_SCHEMA = "database_schema"
        const val EXTRA_TABLE = "database_table"
        const val EXTRA_RESULT = "database_result"
    }
}

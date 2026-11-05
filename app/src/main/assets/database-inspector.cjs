const fs = require('fs');
const path = require('path');
const Module = require('module');

const root = process.argv[2];
const resultPath = process.argv[3];
const action = process.argv[4];
const requestedProvider = process.argv[5];
const requestedSchema = process.argv[6] || '';
const requestedTable = process.argv[7] || '';

function readEnv() {
  const values = {};
  const file = path.join(root, '.env');
  if (!fs.existsSync(file)) return values;
  for (const raw of fs.readFileSync(file, 'utf8').split(/\r?\n/)) {
    const line = raw.trim().replace(/^export\s+/, '');
    if (!line || line.startsWith('#')) continue;
    const split = line.indexOf('=');
    if (split <= 0) continue;
    values[line.slice(0, split).trim()] = line.slice(split + 1).trim().replace(/^(['"])(.*)\1$/, '$2');
  }
  return values;
}

function quotePg(value) { return `"${String(value).replace(/"/g, '""')}"`; }
function quoteMysql(value) { return `\`${String(value).replace(/`/g, '``')}\``; }
function serializeRows(rows) {
  return rows.map(row => Object.values(row).map(value => {
    if (value == null) return null;
    if (Buffer.isBuffer(value)) return `[binary ${value.length} bytes]`;
    if (typeof value === 'object') return JSON.stringify(value).slice(0, 500);
    return String(value).slice(0, 500);
  }));
}

function connectionOptions(env, provider) {
  const connectionString = env.MONGODB_URI || env.MONGO_URI || env.DATABASE_URL || env.DATABASE_URI || env.DB_URL || env.MYSQL_URL || env.PG_URL || '';
  const url = connectionString ? new URL(connectionString) : null;
  const fromUrl = url ? decodeURIComponent(url.pathname.replace(/^\//, '')) : '';
  const common = {
    host: env.DB_HOST || env.MYSQL_HOST || env.PGHOST || url?.hostname,
    port: Number(env.DB_PORT || env.MYSQL_PORT || env.PGPORT || url?.port || (provider === 'PostgreSQL' ? 5432 : 3306)),
    database: env.DB_DATABASE || env.DB_NAME || env.DATABASE || env.PGDATABASE || env.MYSQL_DATABASE || fromUrl,
    user: env.DB_USERNAME || env.DB_USER || env.MYSQL_USER || env.PGUSER || (url?.username ? decodeURIComponent(url.username) : undefined),
    password: env.DB_PASSWORD || env.DB_PASS || env.MYSQL_PASSWORD || env.PGPASSWORD || (url?.password ? decodeURIComponent(url.password) : undefined)
  };
  return { connectionString, common };
}

async function run() {
  const env = readEnv();
  const projectRequire = Module.createRequire(path.join(root, 'package.json'));
  let provider = requestedProvider;
  let database = '';
  let host = '';
  let tables = [];
  let columns = [];
  let rows = [];

  if (provider === 'PostgreSQL') {
    const { Client } = projectRequire('pg');
    const { connectionString, common } = connectionOptions(env, provider);
    database = common.database || '';
    host = common.host || '';
    const client = new Client(connectionString ? { connectionString, connectionTimeoutMillis: 8000, query_timeout: 15000 } : { ...common, connectionTimeoutMillis: 8000, query_timeout: 15000 });
    await client.connect();
    try {
      await client.query('BEGIN READ ONLY');
      if (action === 'tables') {
        const result = await client.query("SELECT table_schema, table_name FROM information_schema.tables WHERE table_type='BASE TABLE' AND table_schema NOT IN ('pg_catalog','information_schema') ORDER BY table_schema, table_name");
        tables = result.rows.map(row => ({ name: row.table_name, schema: row.table_schema, kind: 'table' }));
        database = database || (await client.query('SELECT current_database() AS name')).rows[0].name;
      } else {
        const result = await client.query(`SELECT * FROM ${quotePg(requestedSchema || 'public')}.${quotePg(requestedTable)} LIMIT 100`);
        columns = result.fields.map(field => field.name);
        rows = result.rows.map(row => columns.map(name => row[name] == null ? null : (typeof row[name] === 'object' ? JSON.stringify(row[name]).slice(0, 500) : String(row[name]).slice(0, 500))));
      }
    } finally {
      await client.query('ROLLBACK').catch(() => {});
      await client.end();
    }
  } else if (provider === 'MySQL') {
    let mysql;
    try { mysql = projectRequire('mysql2/promise'); }
    catch (_) { throw new Error('MySQL support needs mysql2 in the extracted project node_modules.'); }
    const { connectionString, common } = connectionOptions(env, provider);
    database = common.database || '';
    host = common.host || '';
    const connection = await mysql.createConnection({ ...common, connectTimeout: 8000, multipleStatements: false });
    try {
      if (action === 'tables') {
        const [result] = await connection.query('SHOW FULL TABLES');
        tables = result.map(row => {
          const key = Object.keys(row)[0];
          return { name: row[key], schema: '', kind: row[Object.keys(row)[1]] || 'table' };
        });
        if (!database) {
          const [current] = await connection.query('SELECT DATABASE() AS name');
          database = current[0]?.name || '';
        }
      } else {
        const [result, fields] = await connection.query(`SELECT * FROM ${quoteMysql(requestedTable)} LIMIT 100`);
        columns = fields.map(field => field.name);
        rows = serializeRows(result);
      }
    } finally {
      await connection.end();
    }
  } else if (provider === 'MongoDB') {
    const { MongoClient } = projectRequire('mongodb');
    const uri = env.MONGODB_URI || env.MONGO_URI || env.DATABASE_URL || env.DB_URL;
    if (!uri) throw new Error('Set MONGODB_URI or MONGO_URI in the project .env.');
    const parsed = new URL(uri);
    database = parsed.pathname.replace(/^\//, '');
    host = parsed.hostname;
    const client = new MongoClient(uri, { serverSelectionTimeoutMS: 8000 });
    await client.connect();
    try {
      const db = client.db(database || undefined);
      if (action === 'tables') {
        tables = (await db.listCollections({}, { nameOnly: true }).toArray()).map(item => ({ name: item.name, schema: '', kind: 'collection' }));
        database = db.databaseName;
      } else {
        const docs = await db.collection(requestedTable).find({}).limit(100).toArray();
        columns = [...new Set(docs.flatMap(doc => Object.keys(doc)))];
        rows = docs.map(doc => columns.map(name => doc[name] == null ? null : (typeof doc[name] === 'object' ? JSON.stringify(doc[name]).slice(0, 500) : String(doc[name]).slice(0, 500))));
      }
    } finally {
      await client.close();
    }
  } else {
    throw new Error('Only MySQL/MariaDB, PostgreSQL and MongoDB network drivers are supported. SQLite files are opened read-only by Android.');
  }

  return { ok: true, provider, database, host, tables, columns, rows };
}

run().then(result => fs.writeFileSync(resultPath, JSON.stringify(result))).catch(error => {
  const message = String(error?.message || error || 'Database connection failed.')
    .replace(/(password\s*[:=]\s*)[^,\s]+/ig, '$1•••')
    .replace(/(\/\/[^:/\s]+:)[^@/\s]+@/g, '$1•••@');
  fs.writeFileSync(resultPath, JSON.stringify({ ok: false, error: message }));
});

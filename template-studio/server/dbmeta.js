/**
 * DB 接続 → メタデータ抽出 → JSON 保存
 * 対応: PostgreSQL / MySQL / SQL Server / Oracle / SQLite
 * ドライバは動的 import（未インストールでもサーバー起動は可能）。
 * パスワードは JSON に保存しない。
 * テーブル数・列数に上限は設けない（DB の全件をそのまま保持する）。
 */
import fs from 'node:fs';
import path from 'node:path';
import { createRequire } from 'node:module';
import { fileURLToPath } from 'node:url';
import {
  DIALECTS, dialectInfo, normalizeMetadata, makeTableId, SAMPLE_METADATA,
} from '../shared/dbmeta.js';

const require = createRequire(import.meta.url);
const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIR = path.join(ROOT, 'data', 'db-metadata');

const L = (v) => `'${String(v ?? '').replace(/'/g, "''")}'`;
const text = (v) => (v === undefined || v === null ? '' : String(v));
const truthy = (v) => v === true || v === 1 || String(v).toUpperCase() === 'TRUE' || String(v) === '1';

function row(r, key) {
  if (!r) return undefined;
  if (r[key] !== undefined) return r[key];
  const found = Object.keys(r).find((k) => k.toLowerCase() === key.toLowerCase());
  return found ? r[found] : undefined;
}
const rv = (r, key) => text(row(r, key));

/* ---------------- JSON ストア ---------------- */
function ensureDir() {
  fs.mkdirSync(DIR, { recursive: true });
  const sample = path.join(DIR, 'sample-fms.json');
  if (!fs.existsSync(sample)) {
    fs.writeFileSync(sample, JSON.stringify(SAMPLE_METADATA, null, 2), 'utf8');
  }
}

function safeId(id) {
  return /^[A-Za-z0-9_\-.]+$/.test(String(id || '')) ? String(id) : null;
}

function fileOf(id) {
  const safe = safeId(id);
  if (!safe) throw new Error('不正なメタデータ ID です');
  return path.join(DIR, `${safe}.json`);
}

function summary(id, meta, size) {
  // 一覧は巨大 JSON を正規化せず、生データから件数だけ数える（列数無制限でも高速）
  const tables = Array.isArray(meta.tables) ? meta.tables
    : Array.isArray(meta.TABLES) ? meta.TABLES : [];
  const colsOf = (t) => (Array.isArray(t.columns) ? t.columns : t.COLUMNS) || [];
  return {
    id,
    name: meta.name || meta.title || id,
    dialect: meta.dialect || meta.dbType,
    source: meta.source,
    database: meta.database || meta.db,
    schema: meta.schema,
    extractedAt: meta.extractedAt || meta.generatedAt,
    tableCount: tables.length,
    columnCount: tables.reduce((n, t) => n + colsOf(t).length, 0),
    size,
  };
}

export function listMetadata() {
  ensureDir();
  return fs.readdirSync(DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => {
      try {
        const p = path.join(DIR, f);
        const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
        return summary(f.slice(0, -5), raw, fs.statSync(p).size);
      } catch {
        return null;
      }
    })
    .filter(Boolean)
    .sort((a, b) => String(b.extractedAt).localeCompare(String(a.extractedAt)));
}

export function getMetadata(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return null;
  return normalizeMetadata(JSON.parse(fs.readFileSync(p, 'utf8')));
}

/**
 * 画面のテーブル選択用に、列の名前 / 型 / コメントだけを返す軽量版。
 * 巨大なメタデータ（列数無制限）でも、全文をブラウザーへ送らずに済む。
 */
export function tableSummaries(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return null;
  const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
  const rawTables = Array.isArray(raw.tables) ? raw.tables
    : Array.isArray(raw.TABLES) ? raw.TABLES
      : Array.isArray(raw.schemas) ? raw.schemas.flatMap((s) => s.tables || []) : [];
  const tables = rawTables.map((t, i) => {
    const schema = text(t?.schema ?? t?.table_schema ?? t?.TABLE_SCHEMA ?? '');
    const name = text(t?.name ?? t?.table_name ?? t?.TABLE_NAME ?? '');
    if (!name) return null;
    const pkRaw = t?.primaryKey || t?.primary_key || t?.pk || [];
    const pkSet = new Set((Array.isArray(pkRaw) ? pkRaw : [pkRaw])
      .map((x) => text(typeof x === 'string' ? x : (x?.name || x?.column_name || ''))).filter(Boolean));
    const colsRaw = t?.columns || t?.COLUMNS || [];
    const columns = (Array.isArray(colsRaw) ? colsRaw : []).map((c, ci) => {
      if (typeof c === 'string') {
        return { name: c, type: '', nullable: true, primaryKey: pkSet.has(c), comment: '', ordinal: ci + 1 };
      }
      const n = text(c?.name ?? c?.column_name ?? c?.COLUMN_NAME ?? c?.columnName ?? '');
      if (!n) return null;
      return {
        name: n,
        type: text(c?.type ?? c?.data_type ?? c?.DATA_TYPE ?? c?.column_type ?? ''),
        nullable: c?.nullable ?? text(c?.is_nullable ?? c?.IS_NULLABLE).toUpperCase() !== 'NO',
        primaryKey: pkSet.has(n) || Boolean(c?.primaryKey)
          || text(c?.column_key ?? c?.COLUMN_KEY).toUpperCase() === 'PRI',
        comment: text(c?.comment ?? c?.column_comment ?? c?.COLUMN_COMMENT ?? c?.description ?? ''),
        ordinal: Number(c?.ordinal ?? c?.ordinal_position ?? c?.ORDINAL_POSITION ?? ci + 1) || ci + 1,
      };
    }).filter(Boolean).sort((a, b) => a.ordinal - b.ordinal);
    return {
      id: makeTableId(schema, name),
      schema,
      name,
      comment: text(t?.comment ?? t?.table_comment ?? t?.TABLE_COMMENT ?? t?.description ?? ''),
      primaryKey: columns.filter((c) => c.primaryKey).map((c) => c.name),
      columns,
      ordinal: i + 1,
    };
  }).filter(Boolean);
  return {
    id,
    name: text(raw.name || raw.title || id),
    dialect: text(raw.dialect || raw.dbType || 'postgres'),
    tables,
  };
}

function slug(s) {
  // ファイル名は ASCII のみ（日本語名は安全に除去し、タイムスタンプで一意化する）
  const ascii = String(s || '')
    .normalize('NFKD')
    .replace(/[^\x20-\x7E]/g, '')
    .replace(/[^A-Za-z0-9_\-]+/g, '-')
    .replace(/^-+|-+$/g, '')
    .slice(0, 32);
  return ascii || 'metadata';
}

export function saveMetadata(raw, requestedId) {
  ensureDir();
  const meta = normalizeMetadata(raw);
  if (!meta.tables.length) throw new Error('テーブルが 1 件もありません');
  // 列数・テーブル数の上限チェックは行わない（20000 列を超えても全件保存する）
  const id = safeId(requestedId) || `${slug(meta.name)}-${Date.now().toString(36)}`;
  meta.id = id;
  meta.extractedAt = raw?.extractedAt || new Date().toISOString();
  const p = fileOf(id);
  fs.writeFileSync(p, JSON.stringify(meta, null, 2), 'utf8');
  return { id, metadata: meta, info: summary(id, meta, fs.statSync(p).size) };
}

export function deleteMetadata(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return false;
  fs.unlinkSync(p);
  return true;
}

export function sampleMetadata() {
  return SAMPLE_METADATA;
}

/* ---------------- ドライバ ---------------- */
export function driverStatus() {
  return DIALECTS.map((d) => {
    let installed = d.id === 'sqlite';
    if (d.driver) {
      try { require.resolve(d.driver); installed = true; } catch { installed = false; }
    }
    return { id: d.id, label: d.label, driver: d.driver, install: d.install, installed };
  });
}

async function openSession(conn) {
  const dialect = dialectInfo(conn.dialect).id;
  const info = dialectInfo(dialect);
  const host = conn.host || 'localhost';
  const port = Number(conn.port) || info.port;

  if (dialect === 'sqlite') {
    const filePath = String(conn.filePath || '').trim();
    if (!filePath) throw new Error('SQLite ファイルパスを指定してください');
    const abs = path.isAbsolute(filePath) ? filePath : path.join(ROOT, filePath);
    if (!fs.existsSync(abs)) throw new Error(`SQLite ファイルが見つかりません: ${abs}`);
    let DatabaseSync;
    try { ({ DatabaseSync } = await import('node:sqlite')); }
    catch { throw new Error('この Node.js では node:sqlite を利用できません（Node 22.5 以降が必要）'); }
    const db = new DatabaseSync(abs, { readOnly: true });
    return {
      dialect,
      query: async (sql) => db.prepare(sql).all(),
      info: async () => rv((await db.prepare('SELECT sqlite_version() AS version').all())[0], 'version'),
      close: async () => db.close(),
    };
  }

  let mod;
  try { mod = await import(info.driver); }
  catch {
    throw new Error(`${info.label} 用ドライバが未インストールです。プロジェクト直下で「${info.install}」を実行してください。`);
  }

  if (dialect === 'postgres') {
    const pg = mod.default || mod;
    const client = new pg.Client({
      host, port,
      user: conn.user, password: conn.password,
      database: conn.database || undefined,
      ssl: conn.ssl ? { rejectUnauthorized: false } : false,
      connectionTimeoutMillis: 8000,
      query_timeout: 30000,
    });
    await client.connect();
    return {
      dialect,
      query: async (sql) => (await client.query(sql)).rows,
      info: async () => rv((await client.query('SELECT version() AS version')).rows[0], 'version'),
      close: async () => client.end(),
    };
  }

  if (dialect === 'mysql') {
    const mysql = mod.default || mod;
    const link = await mysql.createConnection({
      host, port,
      user: conn.user, password: conn.password,
      database: conn.database || undefined,
      connectTimeout: 8000,
      multipleStatements: false,
    });
    return {
      dialect,
      query: async (sql) => (await link.query(sql))[0],
      info: async () => rv((await link.query('SELECT VERSION() AS version'))[0][0], 'version'),
      close: async () => link.end(),
    };
  }

  if (dialect === 'mssql') {
    const sql = mod.default || mod;
    const pool = await new sql.ConnectionPool({
      server: host, port,
      user: conn.user, password: conn.password,
      database: conn.database || undefined,
      options: { encrypt: Boolean(conn.encrypt), trustServerCertificate: true },
      connectionTimeout: 8000, requestTimeout: 30000,
    }).connect();
    return {
      dialect,
      query: async (q) => (await pool.request().query(q)).recordset || [],
      info: async () => rv((await pool.request().query('SELECT @@VERSION AS version')).recordset[0], 'version'),
      close: async () => pool.close(),
    };
  }

  if (dialect === 'oracle') {
    const oracledb = mod.default || mod;
    const connectString = conn.connectString
      || `${host}:${port}/${conn.serviceName || conn.database || 'orclpdb1'}`;
    const link = await oracledb.getConnection({ user: conn.user, password: conn.password, connectString });
    return {
      dialect,
      query: async (q) => (await link.execute(q, [], { outFormat: oracledb.OUT_FORMAT_OBJECT })).rows || [],
      info: async () => {
        try {
          const r = await link.execute('SELECT banner AS version FROM v$version WHERE ROWNUM = 1', [], { outFormat: oracledb.OUT_FORMAT_OBJECT });
          return rv(r.rows[0], 'version') || text(conn.user);
        } catch { return text(conn.user); }
      },
      close: async () => link.close(),
    };
  }

  throw new Error(`未対応の DB 種別です: ${dialect}`);
}

/* ---------------- 方言別クエリ ---------------- */
const PG_SQL = {
  tables: `SELECT n.nspname AS "schema", c.relname AS "name",
       obj_description(c.oid, 'pg_class') AS "comment",
       CASE WHEN c.relkind = 'v' THEN 'VIEW' ELSE 'TABLE' END AS "type"
FROM pg_class c JOIN pg_namespace n ON n.oid = c.relnamespace
WHERE c.relkind IN ('r','p','v')
  AND n.nspname NOT IN ('pg_catalog','information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1, 2`,
  columns: `SELECT n.nspname AS "schema", c.relname AS "table", a.attnum AS "ordinal",
       a.attname AS "name", format_type(a.atttypid, a.atttypmod) AS "type",
       (NOT a.attnotnull) AS "nullable",
       pg_get_expr(d.adbin, d.adrelid) AS "defaultvalue",
       col_description(c.oid, a.attnum) AS "comment"
FROM pg_class c
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped
LEFT JOIN pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
WHERE c.relkind IN ('r','p','v')
  AND n.nspname NOT IN ('pg_catalog','information_schema')
  AND n.nspname NOT LIKE 'pg_toast%'
ORDER BY 1, 2, 3`,
  pk: `SELECT n.nspname AS "schema", c.relname AS "table", a.attname AS "column"
FROM pg_constraint k
JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = kc.attnum
WHERE k.contype = 'p'`,
  fk: `SELECT n.nspname AS "schema", c.relname AS "table", a.attname AS "column",
       rn.nspname AS "refschema", rc.relname AS "reftable", ra.attname AS "refcolumn"
FROM pg_constraint k
JOIN pg_class c ON c.oid = k.conrelid
JOIN pg_namespace n ON n.oid = c.relnamespace
JOIN pg_class rc ON rc.oid = k.confrelid
JOIN pg_namespace rn ON rn.oid = rc.relnamespace
JOIN unnest(k.conkey) WITH ORDINALITY AS kc(attnum, ord) ON true
JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = kc.attnum
JOIN unnest(k.confkey) WITH ORDINALITY AS fc(attnum, ord) ON fc.ord = kc.ord
JOIN pg_attribute ra ON ra.attrelid = rc.oid AND ra.attnum = fc.attnum
WHERE k.contype = 'f'`,
};

function mysqlSql(conn) {
  const db = conn.database ? L(conn.database) : 'DATABASE()';
  return {
    tables: `SELECT TABLE_SCHEMA AS \`schema\`, TABLE_NAME AS \`name\`,
       TABLE_COMMENT AS \`comment\`, TABLE_TYPE AS \`type\`
FROM information_schema.TABLES
WHERE TABLE_SCHEMA = ${db} AND TABLE_TYPE IN ('BASE TABLE','VIEW')
ORDER BY 2`,
    columns: `SELECT TABLE_SCHEMA AS \`schema\`, TABLE_NAME AS \`table\`, ORDINAL_POSITION AS \`ordinal\`,
       COLUMN_NAME AS \`name\`, COLUMN_TYPE AS \`type\`,
       CASE WHEN IS_NULLABLE = 'YES' THEN 1 ELSE 0 END AS \`nullable\`,
       COLUMN_DEFAULT AS \`defaultvalue\`, COLUMN_COMMENT AS \`comment\`, COLUMN_KEY AS \`column_key\`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = ${db}
ORDER BY 2, 3`,
    pk: `SELECT TABLE_SCHEMA AS \`schema\`, TABLE_NAME AS \`table\`, COLUMN_NAME AS \`column\`
FROM information_schema.COLUMNS
WHERE TABLE_SCHEMA = ${db} AND COLUMN_KEY = 'PRI'`,
    fk: `SELECT TABLE_SCHEMA AS \`schema\`, TABLE_NAME AS \`table\`, COLUMN_NAME AS \`column\`,
       REFERENCED_TABLE_SCHEMA AS \`refschema\`, REFERENCED_TABLE_NAME AS \`reftable\`,
       REFERENCED_COLUMN_NAME AS \`refcolumn\`
FROM information_schema.KEY_COLUMN_USAGE
WHERE TABLE_SCHEMA = ${db} AND REFERENCED_TABLE_NAME IS NOT NULL`,
  };
}

const MSSQL_SQL = {
  tables: `SELECT s.name AS [schema], t.name AS [name],
       CAST(ep.value AS nvarchar(max)) AS [comment], 'TABLE' AS [type]
FROM sys.tables t
JOIN sys.schemas s ON s.schema_id = t.schema_id
LEFT JOIN sys.extended_properties ep
  ON ep.major_id = t.object_id AND ep.minor_id = 0 AND ep.name = 'MS_Description'
ORDER BY 1, 2`,
  columns: `SELECT c.TABLE_SCHEMA AS [schema], c.TABLE_NAME AS [table], c.ORDINAL_POSITION AS [ordinal],
       c.COLUMN_NAME AS [name], c.DATA_TYPE AS [type],
       CASE WHEN c.IS_NULLABLE = 'YES' THEN 1 ELSE 0 END AS [nullable],
       c.COLUMN_DEFAULT AS [defaultvalue], CAST(ep.value AS nvarchar(max)) AS [comment]
FROM INFORMATION_SCHEMA.COLUMNS c
LEFT JOIN sys.tables st ON st.name = c.TABLE_NAME
LEFT JOIN sys.schemas ss ON ss.schema_id = st.schema_id AND ss.name = c.TABLE_SCHEMA
LEFT JOIN sys.columns sc ON sc.object_id = st.object_id AND sc.name = c.COLUMN_NAME
LEFT JOIN sys.extended_properties ep
  ON ep.major_id = sc.object_id AND ep.minor_id = sc.column_id AND ep.name = 'MS_Description'
ORDER BY 1, 2, 3`,
  pk: `SELECT tc.TABLE_SCHEMA AS [schema], tc.TABLE_NAME AS [table], kcu.COLUMN_NAME AS [column]
FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
  ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
WHERE tc.CONSTRAINT_TYPE = 'PRIMARY KEY'`,
  fk: `SELECT s.name AS [schema], t.name AS [table], c.name AS [column],
       rs.name AS [refschema], rt.name AS [reftable], rc.name AS [refcolumn]
FROM sys.foreign_keys fk
JOIN sys.foreign_key_columns fkc ON fk.object_id = fkc.constraint_object_id
JOIN sys.tables t ON t.object_id = fkc.parent_object_id
JOIN sys.schemas s ON s.schema_id = t.schema_id
JOIN sys.columns c ON c.object_id = fkc.parent_object_id AND c.column_id = fkc.parent_column_id
JOIN sys.tables rt ON rt.object_id = fkc.referenced_object_id
JOIN sys.schemas rs ON rs.schema_id = rt.schema_id
JOIN sys.columns rc ON rc.object_id = fkc.referenced_object_id AND rc.column_id = fkc.referenced_column_id`,
};

function oracleSql(conn) {
  const owner = L(String(conn.schema || conn.user || '').toUpperCase());
  return {
    tables: `SELECT t.OWNER AS "schema", t.TABLE_NAME AS "name", c.COMMENTS AS "comment", 'TABLE' AS "type"
FROM ALL_TABLES t
LEFT JOIN ALL_TAB_COMMENTS c ON c.OWNER = t.OWNER AND c.TABLE_NAME = t.TABLE_NAME AND c.TABLE_TYPE = 'TABLE'
WHERE t.OWNER = ${owner}
ORDER BY 2`,
    columns: `SELECT c.OWNER AS "schema", c.TABLE_NAME AS "table", c.COLUMN_ID AS "ordinal",
       c.COLUMN_NAME AS "name",
       c.DATA_TYPE || CASE WHEN c.DATA_TYPE IN ('VARCHAR2','CHAR','NVARCHAR2') AND c.DATA_LENGTH IS NOT NULL
         THEN '(' || c.DATA_LENGTH || ')' ELSE '' END AS "type",
       CASE WHEN c.NULLABLE = 'Y' THEN 1 ELSE 0 END AS "nullable",
       c.DATA_DEFAULT AS "defaultvalue", cc.COMMENTS AS "comment"
FROM ALL_TAB_COLUMNS c
LEFT JOIN ALL_COL_COMMENTS cc ON cc.OWNER = c.OWNER AND cc.TABLE_NAME = c.TABLE_NAME AND cc.COLUMN_NAME = c.COLUMN_NAME
WHERE c.OWNER = ${owner}
ORDER BY 2, 3`,
    pk: `SELECT ac.OWNER AS "schema", ac.TABLE_NAME AS "table", cc.COLUMN_NAME AS "column"
FROM ALL_CONSTRAINTS ac
JOIN ALL_CONS_COLUMNS cc ON ac.OWNER = cc.OWNER AND ac.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
WHERE ac.CONSTRAINT_TYPE = 'P' AND ac.OWNER = ${owner}`,
    fk: `SELECT ac.OWNER AS "schema", ac.TABLE_NAME AS "table", cc.COLUMN_NAME AS "column",
       rc.OWNER AS "refschema", rc.TABLE_NAME AS "reftable", rcc.COLUMN_NAME AS "refcolumn"
FROM ALL_CONSTRAINTS ac
JOIN ALL_CONS_COLUMNS cc ON ac.OWNER = cc.OWNER AND ac.CONSTRAINT_NAME = cc.CONSTRAINT_NAME
JOIN ALL_CONSTRAINTS rc ON rc.OWNER = ac.R_OWNER AND rc.CONSTRAINT_NAME = ac.R_CONSTRAINT_NAME
JOIN ALL_CONS_COLUMNS rcc ON rcc.OWNER = rc.OWNER AND rcc.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND rcc.POSITION = cc.POSITION
WHERE ac.CONSTRAINT_TYPE = 'R' AND ac.OWNER = ${owner}`,
  };
}

function globToRegExp(pattern) {
  const escaped = String(pattern).replace(/[.+^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`^${escaped.replace(/\*/g, '.*').replace(/\?/g, '.')}$`, 'i');
}

async function sqliteRows(conn, session, filter) {
  const tables = await session.query("SELECT name, type FROM sqlite_master WHERE type IN ('table','view') AND name NOT LIKE 'sqlite_%' ORDER BY name");
  const rows = { tables: [], columns: [], pk: [], fk: [] };
  for (const t of tables) {
    const name = rv(t, 'name');
    const type = rv(t, 'type').toUpperCase() === 'VIEW' ? 'VIEW' : 'TABLE';
    if (!filter('main', name, type)) continue;
    rows.tables.push({ schema: 'main', name, comment: '', type });
    const escaped = name.replace(/"/g, '""');
    const cols = await session.query(`PRAGMA table_info("${escaped}")`);
    cols.forEach((c) => {
      const cname = rv(c, 'name');
      const pkOrd = Number(rv(c, 'pk') || 0);
      rows.columns.push({
        schema: 'main', table: name, ordinal: Number(rv(c, 'cid') || 0) + 1,
        name: cname, type: rv(c, 'type'),
        nullable: !truthy(rv(c, 'notnull')),
        defaultvalue: rv(c, 'dflt_value'), comment: '',
        column_key: pkOrd > 0 ? 'PRI' : '',
      });
      if (pkOrd > 0) rows.pk.push({ schema: 'main', table: name, column: cname, ordinal: pkOrd });
    });
    const fks = await session.query(`PRAGMA foreign_key_list("${escaped}")`);
    fks.forEach((f) => rows.fk.push({
      schema: 'main', table: name, column: rv(f, 'from'),
      refschema: 'main', reftable: rv(f, 'table'), refcolumn: rv(f, 'to'),
    }));
  }
  return rows;
}

export function assemble(rows, dialect, conn, name) {
  const schemas = String(conn.schema || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
  const pattern = String(conn.tablePattern || '').trim() ? globToRegExp(conn.tablePattern) : null;
  const tables = new Map();

  for (const r of rows.tables) {
    const schema = rv(r, 'schema');
    const tname = rv(r, 'name');
    const type = rv(r, 'type').toUpperCase() || 'TABLE';
    if (!tname) continue;
    if (schemas.length && !schemas.includes(schema.toLowerCase()) && schema.toLowerCase() !== 'main') continue;
    if (pattern && !pattern.test(tname)) continue;
    if (!conn.includeViews && type === 'VIEW') continue;
    const id = makeTableId(schema, tname);
    tables.set(id, {
      schema, name: tname, comment: rv(r, 'comment'), type,
      columns: [], primaryKey: [], foreignKeys: [],
    });
  }

  for (const r of rows.columns) {
    const t = tables.get(makeTableId(rv(r, 'schema'), rv(r, 'table')));
    if (!t) continue;
    t.columns.push({
      name: rv(r, 'name'),
      type: rv(r, 'type'),
      nullable: rv(r, 'nullable') === '' ? true : truthy(rv(r, 'nullable')),
      defaultValue: rv(r, 'defaultvalue'),
      comment: rv(r, 'comment'),
      ordinal: Number(rv(r, 'ordinal') || 0) || t.columns.length + 1,
      primaryKey: rv(r, 'column_key').toUpperCase() === 'PRI',
    });
  }
  for (const r of rows.pk) {
    const t = tables.get(makeTableId(rv(r, 'schema'), rv(r, 'table')));
    if (!t) continue;
    const cname = rv(r, 'column');
    const col = t.columns.find((c) => c.name === cname);
    if (col) col.primaryKey = true;
    if (!t.primaryKey.includes(cname)) t.primaryKey.push(cname);
  }
  for (const r of rows.fk) {
    const t = tables.get(makeTableId(rv(r, 'schema'), rv(r, 'table')));
    if (!t) continue;
    t.foreignKeys.push({
      column: rv(r, 'column'),
      refSchema: rv(r, 'refschema'),
      refTable: rv(r, 'reftable'),
      refColumn: rv(r, 'refcolumn'),
    });
  }

  // テーブル数 / 列数とも切り詰めない
  const list = [...tables.entries()]
    .map(([id, t]) => {
      t.columns.sort((a, b) => a.ordinal - b.ordinal);
      return { id, ...t };
    })
    .filter((t) => t.columns.length);

  return normalizeMetadata({
    name,
    dialect,
    source: 'database',
    database: conn.database || conn.filePath || '',
    host: conn.host || '',
    schema: conn.schema || '',
    connection: {
      dialect, host: conn.host || '', port: conn.port, database: conn.database || '',
      schema: conn.schema || '', user: conn.user || '', filePath: conn.filePath || '',
      serviceName: conn.serviceName || '',
    },
    extractedAt: new Date().toISOString(),
    tables: list,
  });
}

export async function testConnection(conn) {
  const session = await openSession(conn);
  try {
    const version = await session.info();
    return { dialect: session.dialect, version, connected: true };
  } finally {
    await session.close().catch(() => {});
  }
}

export async function extractMetadata(conn, options = {}) {
  const session = await openSession(conn);
  let rows;
  try {
    const version = await session.info().catch(() => '');
    const schemas = String(conn.schema || '').split(',').map((s) => s.trim().toLowerCase()).filter(Boolean);
    const pattern = String(conn.tablePattern || '').trim() ? globToRegExp(conn.tablePattern) : null;
    const filter = (schema, name, type) => {
      if (schemas.length && !schemas.includes(String(schema).toLowerCase()) && String(schema).toLowerCase() !== 'main') return false;
      if (pattern && !pattern.test(name)) return false;
      if (!conn.includeViews && String(type).toUpperCase() === 'VIEW') return false;
      return true;
    };
    if (session.dialect === 'sqlite') {
      rows = await sqliteRows(conn, session, filter);
    } else {
      const q = session.dialect === 'postgres' ? PG_SQL
        : session.dialect === 'mysql' ? mysqlSql(conn)
          : session.dialect === 'mssql' ? MSSQL_SQL : oracleSql(conn);
      rows = {
        tables: await session.query(q.tables),
        columns: await session.query(q.columns),
        pk: await session.query(q.pk),
        fk: await session.query(q.fk),
      };
    }
    const meta = assemble(rows, session.dialect, conn, options.name || `${dialectInfo(session.dialect).label} メタデータ`);
    if (!meta.tables.length) {
      throw new Error('条件に一致するテーブルがありません。スキーマ / テーブル名フィルタを確認してください。');
    }
    meta.serverVersion = String(version || '');
    return { metadata: meta, version: String(version || '') };
  } finally {
    await session.close().catch(() => {});
  }
}

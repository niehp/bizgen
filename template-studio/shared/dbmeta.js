/**
 * DB メタデータの共通モデル（サーバー / ブラウザー共用）
 *
 * 正規形:
 * {
 *   id, name, dialect, source, database, host, schema, extractedAt,
 *   tables: [{
 *     id, schema, name, comment,
 *     primaryKey: [columnName],
 *     foreignKeys: [{ column, refSchema, refTable, refColumn }],
 *     columns: [{ name, type, nullable, defaultValue, primaryKey, comment, ordinal }]
 *   }]
 * }
 */

/** このアプリが接続・SQL 出力の対象とする DB（固定） */
export const APP_DB_DIALECT = 'postgres';

export const DIALECTS = [
  {
    id: 'postgres', label: 'PostgreSQL', port: 5432,
    quote: ['"', '"'], tableAliasAs: true,
    driver: 'pg', install: 'npm install pg',
    fields: ['host', 'port', 'user', 'password', 'database', 'schema', 'ssl'],
    schemaHint: 'public（カンマ区切りで複数可）',
  },
  {
    id: 'mysql', label: 'MySQL / MariaDB', port: 3306,
    quote: ['`', '`'], tableAliasAs: true,
    driver: 'mysql2', install: 'npm install mysql2',
    fields: ['host', 'port', 'user', 'password', 'database', 'schema', 'ssl'],
    schemaHint: '空欄＝接続先データベース',
  },
  {
    id: 'mssql', label: 'SQL Server', port: 1433,
    quote: ['[', ']'], tableAliasAs: true,
    driver: 'mssql', install: 'npm install mssql',
    fields: ['host', 'port', 'user', 'password', 'database', 'schema', 'encrypt'],
    schemaHint: 'dbo',
  },
  {
    id: 'oracle', label: 'Oracle', port: 1521,
    quote: ['"', '"'], tableAliasAs: false,
    driver: 'oracledb', install: 'npm install oracledb',
    fields: ['host', 'port', 'user', 'password', 'serviceName', 'schema', 'ssl'],
    schemaHint: 'スキーマ（＝ユーザー）',
  },
  {
    id: 'sqlite', label: 'SQLite（ローカルファイル）', port: 0,
    quote: ['"', '"'], tableAliasAs: true,
    driver: null, install: '',
    fields: ['filePath', 'schema'],
    schemaHint: 'main（通常は空欄）',
  },
];

export const DEFAULT_PLACEHOLDER_STYLE = 'comment';

export const PLACEHOLDER_STYLES = [
  { id: 'comment', label: "/*camelCase*/'' / 99 / '2026-12-01'（列型別・既定）" },
  { id: 'named', label: ':name（旧形式・命名パラメータ）' },
  { id: 'mybatis', label: '#{name}（MyBatis）' },
  { id: 'at', label: '@name' },
  { id: 'question', label: '?（位置パラメータ）' },
];

export const CONDITION_OPS = [
  { id: '=', label: '=', needsValue: true },
  { id: '<>', label: '<>', needsValue: true },
  { id: '>', label: '>', needsValue: true },
  { id: '>=', label: '>=', needsValue: true },
  { id: '<', label: '<', needsValue: true },
  { id: '<=', label: '<=', needsValue: true },
  { id: 'LIKE', label: 'LIKE', needsValue: true },
  { id: 'IN', label: 'IN', needsValue: true },
  { id: 'BETWEEN', label: 'BETWEEN', needsValue: true },
  { id: 'IS NULL', label: 'IS NULL', needsValue: false },
  { id: 'IS NOT NULL', label: 'IS NOT NULL', needsValue: false },
];

export const JOIN_TYPES = ['INNER', 'LEFT', 'RIGHT', 'FULL'];

const NUMERIC_SQL_TYPES = new Set([
  'smallint', 'integer', 'int', 'int2', 'int4', 'int8', 'bigint',
  'decimal', 'numeric', 'dec', 'number', 'fixed', 'real',
  'double', 'float', 'float4', 'float8', 'money',
  'serial', 'serial2', 'serial4', 'serial8', 'smallserial', 'bigserial',
  'tinyint', 'mediumint',
]);

/**
 * 列の型名が数値型か。numeric(10,2) / double precision / integer[] なども吸収する。
 * character varying / timestamp / boolean / date は false。
 */
export function isNumericType(type) {
  const head = sqlTypeHead(type);
  return NUMERIC_SQL_TYPES.has(head);
}

const DATE_SQL_TYPES = new Set([
  'date', 'datetime', 'datetime2', 'smalldatetime', 'datetimeoffset',
  'timestamp', 'timestamptz', 'time', 'timetz',
]);

/** 型名の先頭語を取り出す。numeric(15,2) / timestamp without time zone などを吸収する */
function sqlTypeHead(type) {
  return String(type ?? '').trim().toLowerCase().match(/^([a-z_]+)/)?.[1] || '';
}

/**
 * 列の型名が日付 / 日時型か。
 * date / timestamp without time zone / timestamptz / datetime2(3) / Oracle DATE などを吸収する。
 * 数値型・文字列型は false（'' / 99 の側に回る）。
 */
export function isDateType(type) {
  return DATE_SQL_TYPES.has(sqlTypeHead(type));
}

const matches = (values, q) => values.some((v) => String(v ?? '').toLowerCase().includes(q));

/** テーブル名 / スキーマ / 表コメントの部分一致（大小文字無視）。表示のみに使う */
export function filterTables(tables, query) {
  const q = String(query ?? '').trim().toLowerCase();
  if (!q) return tables || [];
  return (tables || []).filter((t) => matches([t.id, t.name, t.schema, t.comment], q));
}

/** 列名 / 型 / 列コメントの部分一致（大小文字無視）。表示のみに使う */
export function filterColumns(columns, query) {
  const q = String(query ?? '').trim().toLowerCase();
  if (!q) return columns || [];
  return (columns || []).filter((c) => matches([c.name, c.type, c.comment], q));
}

export function dialectInfo(id) {
  return DIALECTS.find((d) => d.id === id) || DIALECTS[0];
}

export function defaultConnection(dialectId = 'postgres') {
  const info = dialectInfo(dialectId);
  const base = {
    dialect: info.id,
    host: 'localhost', port: info.port,
    user: '', password: '', database: '', schema: '',
    serviceName: '', filePath: '',
    ssl: false, encrypt: false,
    includeViews: true, tablePattern: '',
  };
  if (info.id === 'postgres') { base.user = 'postgres'; base.database = 'postgres'; base.schema = 'public'; }
  if (info.id === 'mysql') { base.user = 'root'; }
  if (info.id === 'mssql') { base.schema = 'dbo'; base.encrypt = false; }
  if (info.id === 'oracle') { base.serviceName = 'orclpdb1'; }
  if (info.id === 'sqlite') { base.filePath = 'data/sample.sqlite'; }
  return base;
}

export function makeTableId(schema, name) {
  const s = String(schema ?? '').trim();
  const n = String(name ?? '').trim();
  return s && s !== 'main' ? `${s}.${n}` : n;
}

export function splitTableId(id) {
  const s = String(id ?? '');
  const i = s.lastIndexOf('.');
  return i < 0 ? { schema: '', name: s } : { schema: s.slice(0, i), name: s.slice(i + 1) };
}

export function findTable(metadata, id) {
  if (!metadata?.tables) return null;
  return metadata.tables.find((t) => t.id === id) || null;
}

const pick = (obj, keys, fallback = '') => {
  for (const k of keys) {
    const v = obj?.[k];
    if (v !== undefined && v !== null && v !== '') return v;
  }
  return fallback;
};

const asBool = (v, defaultValue = true) => {
  if (v === undefined || v === null || v === '') return defaultValue;
  if (typeof v === 'boolean') return v;
  const s = String(v).trim().toUpperCase();
  return ['1', 'TRUE', 'Y', 'YES', '可'].includes(s) ? true
    : ['0', 'FALSE', 'N', 'NO', '不可'].includes(s) ? false : defaultValue;
};

const asText = (v) => (v === undefined || v === null ? '' : String(v));

function normalizeColumn(raw, ordinal, pkSet) {
  if (typeof raw === 'string') raw = { name: raw };
  const source = raw && typeof raw === 'object' ? raw : {};
  const name = asText(pick(source, ['name', 'column_name', 'COLUMN_NAME', 'columnName', 'column']));
  const type = asText(pick(source, ['type', 'data_type', 'DATA_TYPE', 'dataType', 'column_type']));
  const nullable = asBool(
    pick(source, ['nullable', 'is_nullable', 'IS_NULLABLE', 'isNullable'], undefined),
    true,
  );
  const keyFlag = asText(pick(source, ['column_key', 'COLUMN_KEY', 'columnKey', 'pk'], '')).toUpperCase();
  return {
    name,
    type,
    nullable,
    defaultValue: asText(pick(source, ['defaultValue', 'default_value', 'default', 'COLUMN_DEFAULT', 'dflt_value'])),
    primaryKey: pkSet.has(name) || asBool(source.primaryKey, false) || keyFlag === 'PRI',
    comment: asText(pick(source, ['comment', 'comments', 'column_comment', 'COLUMN_COMMENT', 'description', '備考'])),
    ordinal: Number(pick(source, ['ordinal', 'ordinal_position', 'ORDINAL_POSITION', 'column_id'], ordinal)) || ordinal,
  };
}

function normalizeForeignKeys(raw, schema) {
  const list = Array.isArray(raw) ? raw : [];
  return list.map((fk) => {
    if (typeof fk === 'string') return { column: fk, refSchema: '', refTable: '', refColumn: '' };
    return {
      column: asText(pick(fk, ['column', 'column_name', 'COLUMN_NAME', 'from'])),
      refSchema: asText(pick(fk, ['refSchema', 'referenced_table_schema', 'refschema', 'ref_schema'])),
      refTable: asText(pick(fk, ['refTable', 'referenced_table_name', 'reftable', 'ref_table', 'table'])),
      refColumn: asText(pick(fk, ['refColumn', 'referenced_column_name', 'refcolumn', 'ref_column', 'to'])),
      ...(schema ? {} : {}),
    };
  }).filter((fk) => fk.column);
}

function normalizeTable(raw, index) {
  if (typeof raw === 'string') raw = { name: raw };
  const source = raw && typeof raw === 'object' ? raw : {};
  const schema = asText(pick(source, ['schema', 'table_schema', 'TABLE_SCHEMA', 'owner', 'OWNER', 'tableSchema'], ''));
  const name = asText(pick(source, ['name', 'table_name', 'TABLE_NAME', 'tableName']));
  const columnsRaw = source.columns || source.COLUMNS || [];
  const pkRaw = source.primaryKey || source.primary_key || source.pk || [];
  const pkList = (Array.isArray(pkRaw) ? pkRaw : [pkRaw])
    .map((p) => (typeof p === 'string' ? p : asText(p?.name || p?.column_name || p?.column)))
    .filter(Boolean);
  const pkSet = new Set(pkList);
  const columns = (Array.isArray(columnsRaw) ? columnsRaw : [])
    .map((c, i) => normalizeColumn(c, i + 1, pkSet))
    .filter((c) => c.name)
    .sort((a, b) => a.ordinal - b.ordinal);
  for (const c of columns) if (c.primaryKey) pkSet.add(c.name);
  const id = makeTableId(schema, name);
  return {
    id,
    schema,
    name,
    comment: asText(pick(source, ['comment', 'comments', 'table_comment', 'TABLE_COMMENT', 'description', '備考'])),
    primaryKey: columns.filter((c) => c.primaryKey).map((c) => c.name),
    foreignKeys: normalizeForeignKeys(source.foreignKeys || source.foreign_keys || source.fks, schema),
    columns,
    ordinal: index + 1,
  };
}

/** 外部 JSON / DB ドライバの結果を正規形に揃える */
export function normalizeMetadata(raw) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const tablesRaw = src.tables || src.TABLES || src.schemas?.flatMap((s) => s.tables || []) || [];
  const tables = (Array.isArray(tablesRaw) ? tablesRaw : [])
    .map((t, i) => normalizeTable(t, i))
    .filter((t) => t.name);
  const dialect = dialectInfo(src.dialect || src.dbType || 'postgres').id;
  return {
    id: asText(src.id || ''),
    name: asText(src.name || src.title || 'DB メタデータ'),
    dialect,
    source: asText(src.source || 'file'),
    database: asText(src.database || src.db || ''),
    host: asText(src.host || ''),
    schema: asText(src.schema || ''),
    connection: src.connection && typeof src.connection === 'object' ? { ...src.connection, password: undefined } : null,
    serverVersion: asText(src.serverVersion || ''),
    extractedAt: asText(src.extractedAt || src.generatedAt || new Date().toISOString()),
    tables,
  };
}

/** 結合キーの自動推定（外部キー → 同名キー → 同名列） */
export function inferJoinPairs(metadata, leftTable, rightTable) {
  if (!leftTable || !rightTable || leftTable.id === rightTable.id) return [];
  const pairs = [];
  const seen = new Set();
  // 列数が無制限なので、列存在チェックは O(1) の Set で行う（O(n^2) を避ける）
  const leftNames = new Set((leftTable.columns || []).map((c) => c.name));
  const rightNames = new Set((rightTable.columns || []).map((c) => c.name));
  const push = (leftCol, rightCol, source, rank) => {
    const key = `${leftCol}=${rightCol}`;
    if (seen.has(key)) return;
    if (!leftNames.has(leftCol) || !rightNames.has(rightCol)) return;
    seen.add(key);
    pairs.push({ leftCol, rightCol, source, rank });
  };
  const leftId = leftTable.id;
  const rightId = rightTable.id;
  for (const fk of leftTable.foreignKeys || []) {
    if (makeTableId(fk.refSchema || rightTable.schema, fk.refTable) === rightId) {
      push(fk.column, fk.refColumn, 'FK', 0);
    }
  }
  for (const fk of rightTable.foreignKeys || []) {
    if (makeTableId(fk.refSchema || leftTable.schema, fk.refTable) === leftId) {
      push(fk.refColumn, fk.column, 'FK', 0);
    }
  }
  const rightByName = new Map((rightTable.columns || []).map((c) => [c.name, c]));
  for (const c of leftTable.columns || []) {
    const r = rightByName.get(c.name);
    if (!r) continue;
    const isKey = c.primaryKey || r.primaryKey;
    push(c.name, c.name, isKey ? '同名キー' : '同名列', isKey ? 1 : 2);
  }
  return pairs.sort((a, b) => a.rank - b.rank);
}

/** 選択テーブル列から FROM + JOIN の実行順を組み立てる */
export function autoJoinPlan(metadata, tableIds) {
  const tables = (tableIds || []).map((id) => findTable(metadata, id)).filter(Boolean);
  const aliases = {};
  tables.forEach((t, i) => { aliases[t.id] = `t${i + 1}`; });
  const joins = tables.map((t, i) => {
    const alias = aliases[t.id];
    if (i === 0) {
      return { tableId: t.id, alias, joinType: '', parentAlias: '', on: [], unresolved: false, inferred: [] };
    }
    let best = null;
    for (let j = 0; j < i; j += 1) {
      const prev = tables[j];
      const all = inferJoinPairs(metadata, prev, t);
      const fk = all.filter((p) => p.source === 'FK');
      const use = fk.length ? fk : all.slice(0, 1);
      if (!use.length) continue;
      const score = fk.length ? 0 : (use[0].rank || 2);
      if (!best || score < best.score) {
        best = { parentAlias: aliases[prev.id], pairs: use, score };
      }
    }
    const inferred = best?.pairs || [];
    return {
      tableId: t.id,
      alias,
      joinType: 'INNER',
      parentAlias: best?.parentAlias || aliases[tables[0].id],
      on: inferred.map((p) => ({
        left: `${best.parentAlias}.${p.leftCol}`,
        right: `${alias}.${p.rightCol}`,
        source: p.source,
      })),
      inferred,
      unresolved: !best,
    };
  });
  return { tables, aliases, joins };
}

/* ---------------- 内蔵サンプルは廃止 ----------------
 * アプリは保存済みの実 DB メタデータのみを扱う。
 * サンプルスキーマは提供しないため、DB ページで抽出 / JSON 読込を行うこと。
 */

/**
 * Excel 列 ↔ DB テーブル列のマッピングと INSERT SQL 生成
 * （サーバー / ブラウザー共用の純関数。副作用・ファイル I/O は行わない）
 *
 * 1 つの Excel ファイルを複数テーブルへマッピングできる。
 * 保存時は「1 テーブル = 1 JSON ファイル」。ファイル名は
 *   <保存名>__<schema.table>.json
 * で、保存時の名前に紐づく。同じ保存名のファイル群は 1 つのグループとして扱う。
 * 正規形のマッピング:
 * {
 *   id, name, groupName, groupFileCount, tableIndex,
 *   metadataId, sourceFile, sheet,
 *   headerMode: 'auto'|'on'|'off', skipRows,
 *   tables: [{                          // マッピング対象テーブル（複数可）
 *     tableId,                          // 'schema.name'
 *     pairs: [{ index, source, target }], // index = Excel の列位置（0 起点・同一 index の複数割当可）
 *     defaults: [{ column, value }],    // Excel に無い DB 列の既定値（空欄は使用しない）
 *   }],
 *   tableId, pairs,                     // 互換用（先頭テーブルの複製）
 *   options: { mode, batch, emptyAsNull, includeSchema, quoteIdentifiers,
 *              convertSerial, placeholderStyle, rowLimit, startRow },
 *   createdAt, updatedAt
 * }
 */
import { isNumericType, isDateType, makeTableId } from './dbmeta.js';
import {
  ident, qualifiedTable, placeholder, literalFlags,
} from './sqlbuilder.js';

export const DEFAULT_MAPPING_OPTIONS = {
  mode: 'values',            // 'values' = Excel の実データ / 'template' = 埋め込みコメント雛形
  batch: 'each',             // 'each' = 1 行 1 INSERT / 'multi' = 複数 VALUES を 1 INSERT に
  emptyAsNull: true,
  includeSchema: true,
  quoteIdentifiers: false,
  convertSerial: true,       // 日付型の数値を Excel シリアル日付として変換
  placeholderStyle: 'comment',
  rowLimit: 50,              // 0 = 全件（開始行からの最大出力行数）
  startRow: 1,               // データ行の何行目から出力するか（1 起点）
};

const MODES = ['values', 'template'];
const BATCHES = ['each', 'multi'];
const HEADER_MODES = ['auto', 'on', 'off'];
const MAX_WARNINGS = 25;

const text = (v) => (v === undefined || v === null ? '' : String(v));
const clip = (v, n = 36) => {
  const s = text(v).replace(/\s+/g, ' ').trim();
  return s.length > n ? `${s.slice(0, n)}…` : s;
};

const WINDOWS_RESERVED = /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i;

/**
 * JSON ファイル名の断片として使える文字だけを残す。
 * 日本語・中国語などの Unicode 文字はそのまま残す（保存名との対応を目で確認できるように）。
 * Windows 禁則文字 / 制御文字 / cmd で特殊な記号は _ へ置換し、パス区切りは残さない。
 */
export function safeFileStem(value, fallback = 'mapping', maxLength = 64) {
  let s = text(value).normalize('NFC')
    .replace(/[\u0000-\u001f\u007f\\/:*?"<>|]/g, '_')
    .replace(/[^\p{L}\p{N} ._()\-]/gu, '_')
    .replace(/\s+/g, ' ')
    .replace(/_+/g, '_')
    .trim()
    .replace(/^\.+/, '')
    .replace(/[. ]+$/g, '');
  if (WINDOWS_RESERVED.test(s)) s = `_${s}`;
  s = s.slice(0, maxLength).trim().replace(/[. ]+$/g, '');
  return s || fallback;
}

/**
 * 1 テーブル = 1 JSON。保存名がファイル名の先頭に入る。
 * 例: name='受注取込', tableId='public.t_order' → '受注取込__public.t_order'
 */
export function mappingFileId(name, tableId) {
  return `${safeFileStem(name, 'mapping', 64)}__${safeFileStem(tableId, 'table', 90)}`;
}

const pairsOf = (arr) => (Array.isArray(arr) ? arr : [])
  .map((p, i) => ({
    index: Number.isInteger(p?.index) ? p.index : i,
    source: text(p?.source ?? p?.name ?? p?.label),
    target: text(p?.target ?? p?.column ?? ''),
  }))
  .filter((p) => Number.isInteger(p.index) && p.index >= 0);

const defaultsOf = (arr) => (Array.isArray(arr) ? arr : [])
  .map((d) => ({
    column: text(d?.column ?? d?.name),
    value: d?.value === undefined || d?.value === null ? '' : String(d.value),
  }))
  .filter((d) => d.column);

const tablesOf = (src) => {
  if (Array.isArray(src.tables) && src.tables.length) {
    return src.tables
      .map((t) => ({
        tableId: text(t?.tableId ?? t?.table),
        pairs: pairsOf(t?.pairs),
        defaults: defaultsOf(t?.defaults),
      }))
      .filter((t) => t.tableId);
  }
  // 旧形式（単一テーブル）からの変換
  const pairs = pairsOf(src.pairs);
  const defaults = defaultsOf(src.defaults);
  const tableId = text(src.tableId);
  if (tableId || pairs.length || defaults.length) {
    return [{ tableId, pairs, defaults }];
  }
  return [];
};

/** 外部 JSON / UI の入力を正規形に揃える */
export function normalizeMapping(raw = {}) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const tables = tablesOf(src);
  const o = src.options && typeof src.options === 'object' ? src.options : {};
  const bool = (v, d) => (v === undefined || v === null ? d : Boolean(v));
  const rowLimit = Number(o.rowLimit);
  const startRow = Number(o.startRow);
  return {
    id: text(src.id),
    name: text(src.name),
    // 1 テーブル = 1 JSON のグループ情報（旧 JSON は name をグループ名として扱う）
    groupName: text(src.groupName) || text(src.name),
    groupFileCount: Math.max(0, Number(src.groupFileCount) || 0),
    tableIndex: Math.max(0, Number(src.tableIndex) || 0),
    metadataId: text(src.metadataId),
    sourceFile: text(src.sourceFile ?? src.filename),
    sheet: text(src.sheet),
    headerMode: HEADER_MODES.includes(src.headerMode) ? src.headerMode : 'auto',
    skipRows: Math.max(0, Number(src.skipRows) || 0),
    tables,
    // 互換用: 先頭テーブルを単一テーブル形式としても参照できるようにする
    tableId: tables[0]?.tableId || '',
    pairs: tables[0]?.pairs || [],
    defaults: tables[0]?.defaults || [],
    options: {
      mode: MODES.includes(o.mode) ? o.mode : DEFAULT_MAPPING_OPTIONS.mode,
      batch: BATCHES.includes(o.batch) ? o.batch : DEFAULT_MAPPING_OPTIONS.batch,
      emptyAsNull: bool(o.emptyAsNull, DEFAULT_MAPPING_OPTIONS.emptyAsNull),
      includeSchema: bool(o.includeSchema, DEFAULT_MAPPING_OPTIONS.includeSchema),
      quoteIdentifiers: bool(o.quoteIdentifiers, DEFAULT_MAPPING_OPTIONS.quoteIdentifiers),
      convertSerial: bool(o.convertSerial, DEFAULT_MAPPING_OPTIONS.convertSerial),
      placeholderStyle: text(o.placeholderStyle) || DEFAULT_MAPPING_OPTIONS.placeholderStyle,
      rowLimit: Number.isFinite(rowLimit) && rowLimit >= 0 ? Math.floor(rowLimit) : DEFAULT_MAPPING_OPTIONS.rowLimit,
      startRow: Number.isFinite(startRow) && startRow >= 1 ? Math.floor(startRow) : DEFAULT_MAPPING_OPTIONS.startRow,
    },
    createdAt: text(src.createdAt),
    updatedAt: text(src.updatedAt),
  };
}

/** 列名比較用の正規化（区切り記号・大文字小文字・camelCase を吸収、日本語は保持） */
export function normalizeColumnKey(name) {
  return text(name)
    .normalize('NFKC')
    .toLowerCase()
    .replace(/[^0-9a-z\u3040-\u30ff\u3400-\u9fff]+/g, '');
}

/**
 * Excel の列名 / 日本語見出しと DB 列名を自動で対応付ける。
 * 完全一致 → 部分一致 → 列コメント一致 の順、同じ DB 列は重複して割り当てない。
 */
export function autoMapPairs(sourceColumns, dbColumns) {
  const used = new Set();
  const targets = (dbColumns || []).map((c) => ({
    name: text(c?.name), type: text(c?.type), comment: text(c?.comment),
    key: normalizeColumnKey(c?.name), commentKey: normalizeColumnKey(c?.comment),
  }));
  return (sourceColumns || []).map((s, i) => {
    const index = Number.isInteger(s?.index) ? s.index : i;
    const label = text(s?.label ?? s?.name ?? s);
    const key = normalizeColumnKey(label);
    let best = '';
    let score = 0;
    for (const c of targets) {
      if (!c.name || used.has(c.name)) continue;
      let cur = 0;
      if (key && key === c.key) cur = 100;
      else if (key && key.length >= 2 && c.key.includes(key)) cur = 60;
      else if (key && c.key.length >= 2 && key.includes(c.key)) cur = 50;
      else if (key && key.length >= 2 && c.commentKey.includes(key)) cur = 30;
      if (cur > score) { score = cur; best = c.name; }
    }
    if (score >= 30 && best) used.add(best);
    return { index, source: label, target: score >= 30 ? best : '' };
  });
}

function isBooleanType(type) {
  const head = text(type).trim().toLowerCase().match(/^([a-z_]+)/)?.[1] || '';
  return head === 'boolean' || head === 'bool';
}

/** PostgreSQL の文字列リテラル。シングルクォートを二重化し、NUL を除去 */
export function quoteSqlText(value) {
  return `'${text(value).replace(/\u0000/g, '').replace(/'/g, "''")}'`;
}

/** 2026/12/1 → 2026-12-01、2026.12.01 → 2026-12-01 */
export function normalizeDateText(value) {
  const s = text(value).trim();
  const m = s.match(/^(\d{4})[/\-.](\d{1,2})[/\-.](\d{1,2})(.*)$/);
  if (!m) return s;
  const base = `${m[1]}-${m[2].padStart(2, '0')}-${m[3].padStart(2, '0')}`;
  return base + m[4];
}

/** Excel のシリアル日付（1900 年方式）を ISO 文字列へ。時刻小数部も保持 */
export function excelSerialToIso(serial) {
  const n = Number(serial);
  if (!Number.isFinite(n) || n < 1 || n > 100000) return '';
  // serial 60 は Excel の架空の 1900-02-29。61 以降は 1 日補正する
  const days = n >= 61 ? Math.floor(n) - 1 : Math.floor(n);
  const ms = Date.UTC(1899, 11, 31) + days * 86400000;
  const iso = new Date(ms).toISOString().slice(0, 10);
  const frac = n - Math.floor(n);
  if (frac <= 0) return iso;
  const total = Math.round(frac * 86400);
  const hh = String(Math.floor(total / 3600) % 24).padStart(2, '0');
  const mm = String(Math.floor(total / 60) % 60).padStart(2, '0');
  const ss = String(total % 60).padStart(2, '0');
  return `${iso} ${hh}:${mm}:${ss}`;
}

/** 1 セルを列型に応じた SQL リテラルへ変換する */
function cellLiteral(raw, col, options, warn) {
  const value = text(raw).trim();
  const type = col?.type || '';
  if (value === '' || /^null$/i.test(value)) {
    if (options.emptyAsNull || /^null$/i.test(value)) return 'NULL';
    return isNumericType(type) || isDateType(type) || isBooleanType(type) ? 'NULL' : "''";
  }
  if (isNumericType(type)) {
    const cleaned = value.replace(/,/g, '');
    if (/^[-+]?\d+(\.\d+)?([eE][-+]?\d+)?$/.test(cleaned)) return cleaned;
    warn(`数値型 ${col.name} に数値以外の値「${clip(value)}」があるため NULL にしました`);
    return 'NULL';
  }
  if (isBooleanType(type)) {
    const v = value.toLowerCase();
    if (['1', 'true', 't', 'yes', 'y', '○', '有効'].includes(v)) return 'TRUE';
    if (['0', 'false', 'f', 'no', 'n', '×', '無効'].includes(v)) return 'FALSE';
    warn(`真偽型 ${col.name} に解釈できない値「${clip(value)}」があるため NULL にしました`);
    return 'NULL';
  }
  if (isDateType(type)) {
    let v = value;
    if (options.convertSerial !== false && /^\d+(\.\d+)?$/.test(value)) {
      const iso = excelSerialToIso(value);
      if (iso) v = iso;
    } else {
      v = normalizeDateText(value);
    }
    return quoteSqlText(v);
  }
  return quoteSqlText(value);
}

/** SQL の関数・キーワードをそのまま出力できる既定値かどうか */
const CONSTANT_VALUE_RE = /^(current_timestamp|current_date|localtimestamp|now\(\)|default)$/i;

/** 既定値を SQL へ。空欄は null（列から外す）／SQL キーワード・引用済みリテラルは生値／其余は列型で判定 */
function defaultLiteral(value, col, options, warn) {
  const v = text(value).trim();
  if (!v) return null;
  if (CONSTANT_VALUE_RE.test(v)) return v.toUpperCase();
  if (/^'.*'$/.test(v)) return v; // 既にクォート済みの SQL リテラルはそのまま
  return cellLiteral(v, col, options, warn);
}

/** 1 テーブル分の INSERT 文ブロック列を生成する */
function buildTableInsert({ table, tableId, pairs = [], defaults = [], rows, kind, opts, quote, includeSchema, warn }) {
  const label = table?.name ? qualifiedTable(table, 'postgres', { includeSchema, quote }) : (tableId || '（不明なテーブル）');
  const fail = (msg) => {
    warn(msg);
    return { blocks: [], rows: 0, columns: 0, defaultCount: 0 };
  };
  if (!table || !text(table.name)) return fail(`テーブル ${label} のメタデータが見つかりません（マッピングから外すか、メタデータを読み直してください）`);
  const columns = Array.isArray(table.columns) ? table.columns : [];
  const byName = new Map(columns.map((c) => [text(c?.name), c]));

  // マッピング列（同一 Excel 列を複数 DB 列へ割り当て可。DB 列の重複は先頭のみ）
  const usedTargets = new Set();
  const active = [];
  for (const p of pairs) {
    const target = text(p?.target);
    if (!target) continue;
    if (!byName.has(target)) {
      warn(`テーブル ${label} の列 ${target} はメタデータに存在しないため除外しました`);
      continue;
    }
    if (usedTargets.has(target)) {
      warn(`テーブル ${label} の列 ${target} が重複して割り当てられています（先頭の 1 件のみ使用）`);
      continue;
    }
    if (!Number.isInteger(p?.index) || p.index < 0) continue;
    usedTargets.add(target);
    active.push({ index: p.index, target });
  }

  // 既定値（DB 列→値）。Excel 列が無い列は定数、Excel 列がある列は空欄時の代替値として使う
  const defaultByColumn = new Map();
  const defaultItems = [];
  for (const d of defaults) {
    const col = byName.get(d.column);
    if (!col || !text(d.value).trim()) continue;
    defaultByColumn.set(d.column, d.value);
    if (usedTargets.has(d.column)) continue; // マッピング済み列は空欄時の代替値として扱う
    usedTargets.add(d.column);
    defaultItems.push({ target: d.column, value: d.value });
  }
  const appliedDefaults = new Set(defaultItems.map((d) => d.target));

  if (!active.length && !defaultItems.length) {
    return fail(`テーブル ${label} にマッピングされた列・既定値がありません`);
  }

  // 列順はマッピング設定順ではなく、テーブル（メタデータ）の列定義順に従う。
  const columnOrder = new Map(columns.map((c, i) => [text(c?.name), i]));
  const ordered = [
    ...active,
    ...defaultItems.map((d) => ({ index: -1, target: d.target, isDefault: true, raw: d.value })),
  ].sort((a, b) => (columnOrder.get(a.target) ?? Number.MAX_SAFE_INTEGER)
    - (columnOrder.get(b.target) ?? Number.MAX_SAFE_INTEGER));
  const tableName = qualifiedTable(table, 'postgres', { includeSchema, quote });
  // フィールド名は 1 行にまとめて出力する（1 列 1 行にはしない）。
  const columnList = ordered.map((p) => ident(p.target, 'postgres', quote)).join(', ');
  const columnBlock = [`INSERT INTO ${tableName} (${columnList}) VALUES`];

  let tuples = [];
  if (kind === 'template') {
    const values = ordered.map((p) => (p.isDefault
      ? defaultLiteral(p.raw, byName.get(p.target), opts, warn) || 'NULL'
      : placeholder(p.target, opts.placeholderStyle, literalFlags(byName.get(p.target)?.type))));
    tuples.push(`(${values.join(', ')})`);
  } else {
    const sourceRows = Array.isArray(rows) ? rows : [];
    if (!sourceRows.length) warn(`テーブル ${label}: 取り込んだデータ行がありません`);
    tuples = sourceRows.map((row) => {
      const cells = Array.isArray(row) ? row : [];
      const values = ordered.map((p) => {
        if (p.isDefault) return defaultLiteral(p.raw, byName.get(p.target), opts, warn) || 'NULL';
        const raw = cells[p.index];
        const fallback = defaultByColumn.get(p.target);
        if (fallback && (text(raw).trim() === '' || /^null$/i.test(text(raw).trim()))) {
          appliedDefaults.add(p.target);
          return defaultLiteral(fallback, byName.get(p.target), opts, warn) || 'NULL';
        }
        return cellLiteral(raw, byName.get(p.target), opts, warn);
      });
      return `(${values.join(', ')})`;
    });
  }

  let blocks = [];
  if (tuples.length) {
    if (opts.batch === 'multi' || tuples.length === 1) {
      const lines = [...columnBlock];
      tuples.forEach((t, i) => lines.push(`    ${t}${i < tuples.length - 1 ? ',' : ';'}`));
      blocks = [lines.join('\n')];
    } else {
      blocks = tuples.map((t) => [...columnBlock, `    ${t};`].join('\n'));
    }
  }
  return {
    blocks,
    rows: kind === 'template' ? 0 : tuples.length,
    columns: ordered.length,
    defaultCount: appliedDefaults.size,
  };
}

const sourceRowsLike = (rows) => (Array.isArray(rows) ? rows.length : 0);

/**
 * INSERT SQL を生成する（複数テーブル対応）。
 * @param {object} p
 * @param {Array}  p.tables  [{ table, tableId, pairs, defaults }] — 順に出力
 * @param {object} p.table / p.pairs — 旧単一テーブル形式（tables 未指定時のみ）
 * @param {Array}  p.rows  Excel のデータ行（文字列の配列）
 * @param {string} p.mode  'values' | 'template'
 * @param {object} p.options
 */
export function buildInsertSql({ table, tables, pairs = [], rows = [], mode, options = {} } = {}) {
  const opts = { ...DEFAULT_MAPPING_OPTIONS, ...(options || {}) };
  const kind = MODES.includes(mode) ? mode : opts.mode;
  const quote = Boolean(opts.quoteIdentifiers);
  const includeSchema = opts.includeSchema !== false;
  const warnings = [];
  const seenWarnings = new Set();
  const warn = (msg) => {
    if (seenWarnings.has(msg) || warnings.length >= MAX_WARNINGS) return;
    seenWarnings.add(msg);
    warnings.push(msg);
  };

  const list = Array.isArray(tables) && tables.length
    ? tables
    : (table ? [{ table, tableId: makeTableId(table?.schema, table?.name), pairs, defaults: [] }] : []);
  if (!list.length) {
    warn('対象テーブルが選択されていません');
    return { sql: '', warnings, stats: { lines: 0, chars: 0, statements: 0, rows: 0, columns: 0, tables: [] } };
  }

  const blocks = [];
  const tableStats = [];
  let totalStatements = 0;
  let totalRows = 0;
  let totalColumns = 0;
  for (const entry of list) {
    const r = buildTableInsert({
      table: entry.table,
      tableId: entry.tableId,
      pairs: entry.pairs || [],
      defaults: entry.defaults || [],
      rows,
      kind,
      opts,
      quote,
      includeSchema,
      warn,
    });
    if (!r.blocks.length) continue;
    blocks.push(...r.blocks);
    totalStatements += r.blocks.length;
    totalRows += r.rows;
    totalColumns += r.columns;
    tableStats.push({
      tableId: entry.tableId || makeTableId(entry.table?.schema, entry.table?.name),
      statements: r.blocks.length,
      rows: r.rows,
      columns: r.columns,
      defaultColumns: r.defaultCount,
    });
  }

  if (!blocks.length) {
    return { sql: '', warnings, stats: { lines: 0, chars: 0, statements: 0, rows: 0, columns: 0, tables: tableStats } };
  }
  const sql = blocks.join('\n\n');
  return {
    sql,
    warnings,
    stats: {
      lines: sql.split('\n').length,
      chars: sql.length,
      statements: totalStatements,
      rows: totalRows,
      columns: totalColumns,
      tables: tableStats,
    },
  };
}

/**
 * 取込結果（rows / header）から「列名一覧」と「データ行」を導出する。
 * Web 画面と Windows コマンドラインで同じ判定を使うための共有関数。
 */
export function guessHeaderRow(row) {
  const cells = (Array.isArray(row) ? row : []).map((c) => text(c).trim()).filter(Boolean);
  if (!cells.length) return false;
  const numeric = cells.filter((c) => /^[-+]?[\d,]+(\.\d+)?$/.test(c)).length;
  return numeric < cells.length;
}

export function deriveSheet(file, { headerMode = 'auto', skipRows = 0 } = {}) {
  const rows = Array.isArray(file?.rows) ? file.rows : [];
  const visible = rows.slice(Math.max(0, Number(skipRows) || 0));
  const autoHeader = guessHeaderRow(visible[0]);
  const useHeader = headerMode === 'on' ? true : headerMode === 'off' ? false : autoHeader;
  const hasHeader = Array.isArray(file?.header) && file.header.length > 0;
  const headerRow = hasHeader ? file.header : (useHeader ? (visible[0] || []) : []);
  const dataRows = hasHeader ? visible : (useHeader ? visible.slice(1) : visible);
  let width = headerRow.length;
  for (const r of dataRows.slice(0, 100)) width = Math.max(width, Array.isArray(r) ? r.length : 0);
  const counts = new Map();
  const sourceColumns = Array.from({ length: width }, (_, i) => {
    const raw = text(headerRow[i]).trim() || `列${i + 1}`;
    const n = (counts.get(raw) || 0) + 1;
    counts.set(raw, n);
    return { index: i, label: n > 1 ? `${raw} (${n})` : raw };
  });
  return { visible, autoHeader, useHeader, headerRow, dataRows, width, sourceColumns };
}

/** 開始行・出力行数を適用して INSERT に渡す行を返す（mode=template は空配列） */
export function rowsForInsert(dataRows, options = {}, overrides = {}) {
  const o = { ...DEFAULT_MAPPING_OPTIONS, ...(options || {}) };
  const mode = overrides.mode || o.mode;
  if (mode === 'template') return [];
  const startRow = Math.max(1, Number(overrides.startRow ?? o.startRow) || 1);
  const from = startRow > 1 ? dataRows.slice(startRow - 1) : dataRows;
  const limit = Number(overrides.rowLimit ?? o.rowLimit);
  return Number.isFinite(limit) && limit > 0 ? from.slice(0, limit) : from;
}

/** UI 表示用：テーブル id を schema.name 形式で作る */
export function tableLabel(schema, name) {
  return makeTableId(schema, name);
}

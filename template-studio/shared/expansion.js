/**
 * 「1 つの変数 → N インスタンス」の生成定義。
 * サーバー（複数コード生成）とブラウザ（N 個の入力欄生成）で同じ定義を共有する。
 *
 *   single : 従来どおり 1 値 → 1 件
 *   range  : 数字範囲 1〜10 など → 連番値 1 つにつき 1 件
 *   values : リスト形式で入力した複数値 → 値 1 つにつき 1 件
 *
 * 同一変数は必ず 1 つのモードだけを持つ。複数の変数を同時に展開しない
 * （組み合わせ爆発を避け、生成結果を「すべて表示」できる件数に保つため）。
 */
import { padNum } from './transforms.js';

export const MAX_ITEMS = 100;

export const GENERATOR_MODES = [
  { id: 'single', label: '単一値', hint: '1 つの値から 1 件生成' },
  { id: 'range', label: '数字範囲', hint: '1〜10 のような連番を N 件生成' },
  { id: 'values', label: '複数値リスト', hint: '入力した値 1 つにつき 1 件生成' },
];

export const VALUE_SEPARATORS = [
  { id: 'newline', label: '改行', value: '\n' },
  { id: 'comma', label: 'カンマ ,', value: ',' },
  { id: 'semicolon', label: 'セミコロン ;', value: ';' },
  { id: 'pipe', label: '縦線 |', value: '|' },
  { id: 'tab', label: 'タブ', value: '\t' },
];

const num = (v, fallback) => {
  if (v == null || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
};

/** '\\n' / '\\t' のような字面表記も実際の区切り文字に直す */
export function separatorChar(value) {
  const s = String(value ?? '\n');
  if (s === '\\n' || s === 'newline') return '\n';
  if (s === '\\t' || s === 'tab') return '\t';
  if (s === 'comma') return ',';
  if (s === 'semicolon') return ';';
  if (s === 'pipe') return '|';
  return s;
}

/** front-matter の expand / multi 設定から既定値を作る */
function variableDefaults(variable) {
  const expand = variable?.expand && typeof variable.expand === 'object'
    ? variable.expand
    : (variable?.expand === true ? { enabled: true } : {});
  const multi = variable?.multi;
  let multiText = '';
  let multiSep = '\n';
  if (multi && typeof multi === 'object' && !Array.isArray(multi)) {
    const raw = multi.values ?? multi.text ?? multi.default;
    multiText = Array.isArray(raw) ? raw.join('\n') : (raw == null ? '' : String(raw));
    if (multi.separator != null) multiSep = separatorChar(multi.separator);
  } else if (Array.isArray(multi)) {
    multiText = multi.map(String).join('\n');
  } else if (typeof multi === 'string') {
    multiText = multi;
  }
  if (!multiText && multi && variable?.default != null) {
    multiText = Array.isArray(variable.default)
      ? variable.default.join('\n')
      : String(variable.default);
  }
  const hasMulti = Boolean(multi);
  const hasExpand = Boolean(variable?.expand);
  return { expand, multiText, multiSep, hasMulti, hasExpand };
}

/**
 * front-matter / UI 設定 / 変数定義を合成して 1 つの生成設定に正規化する。
 * mode が明示されていない場合は旧データや front-matter から推定する（後方互換）。
 */
export function normalizeGenerator(cfg, variable) {
  const d = variableDefaults(variable);
  const own = cfg && typeof cfg === 'object' && !Array.isArray(cfg) ? cfg : {};
  const c = {
    mode: undefined,
    enabled: false,
    // range
    from: 1,
    to: 10,
    step: 1,
    pad: 0,
    prefix: '',
    suffix: '',
    values: {},
    // values(list)
    text: d.multiText,
    separator: d.multiSep,
    trim: true,
    skipEmpty: true,
    ...d.expand,
    ...own,
  };

  // モード決定：UI 明示 > front-matter multi > 旧 expand/enabled
  let mode = c.mode;
  if (!GENERATOR_MODES.some((m) => m.id === mode)) {
    if (mode == null || mode === '') {
      if (d.hasMulti && !own.text && own.mode === undefined) mode = 'values';
      else if (c.enabled === true || d.hasExpand) mode = 'range';
      else mode = 'single';
    } else {
      mode = 'range';
    }
  }
  c.mode = mode;
  c.enabled = mode !== 'single';

  c.from = Math.trunc(num(c.from, 1));
  c.to = Math.trunc(num(c.to, 10));
  c.step = Math.trunc(num(c.step, 1)) || 1;
  c.pad = Math.min(20, Math.max(0, Math.trunc(num(c.pad, 0))));
  c.labelPad = Math.min(20, Math.max(0, Math.trunc(num(c.labelPad, 0))));
  c.prefix = String(c.prefix ?? '');
  c.suffix = String(c.suffix ?? '');
  c.labelPrefix = String(c.labelPrefix ?? '');
  if (!c.values || typeof c.values !== 'object' || Array.isArray(c.values)) c.values = {};
  c.text = String(c.text ?? '');
  c.separator = separatorChar(c.separator);
  c.trim = c.trim !== false;
  c.skipEmpty = c.skipEmpty !== false;
  return c;
}

/** 区切り文字で複数値を取り出す。空行は既定で除外し、最大 100 件に制限する。 */
export function parseValues(text, separator = '\n', opts = {}) {
  const sep = separatorChar(separator);
  const src = String(text ?? '');
  const raw = sep ? src.split(sep) : [src];
  const out = [];
  for (let s of raw) {
    if (opts.trim !== false) s = s.trim();
    else if (sep === '\n') s = s.replace(/\r$/, '');
    if (opts.skipEmpty !== false && !s) continue;
    out.push(s);
    if (out.length >= MAX_ITEMS) break;
  }
  return out;
}

/** 複数値を 1 つのテキストに戻す（逐行編集 → text の同期用） */
export function joinValues(items, separator = '\n') {
  return items.map(String).join(separatorChar(separator));
}

/** 数字範囲からインスタンスを作る。値は UI で上書き可能。 */
export function expansionItems(cfg) {
  const c = normalizeGenerator({ ...cfg, mode: 'range' });
  const out = [];
  if (c.step > 0) {
    for (let i = c.from; i <= c.to && out.length < MAX_ITEMS; i += c.step) out.push(i);
  } else {
    for (let i = c.from; i >= c.to && out.length < MAX_ITEMS; i += c.step) out.push(i);
  }
  if (!out.length) out.push(c.from);
  return out.map((i) => {
    const auto = `${c.prefix}${padNum(i, c.pad)}${c.suffix}`;
    const own = c.values[String(i)];
    return {
      index: i,
      label: auto,
      auto,
      value: own == null || own === '' ? auto : String(own),
    };
  });
}

/** リスト形式の複数値からインスタンスを作る。 */
export function valueItems(cfg) {
  const c = normalizeGenerator({ ...cfg, mode: 'values' });
  const list = parseValues(c.text, c.separator, { trim: c.trim, skipEmpty: c.skipEmpty });
  if (!list.length) list.push('');
  return list.slice(0, MAX_ITEMS).map((v, i) => {
    const label = v.length > 48 ? `${v.slice(0, 47)}…` : v;
    return {
      index: i + 1,
      label: c.labelPrefix
        ? `${c.labelPrefix}${padNum(i + 1, c.labelPad)} · ${label}`
        : label,
      auto: v,
      value: v,
    };
  });
}

/** モードに応じたインスタンス配列。single は空配列。 */
export function generatorItems(cfg) {
  const c = normalizeGenerator(cfg);
  if (c.mode === 'range') return expansionItems(c);
  if (c.mode === 'values') return valueItems(c);
  return [];
}

/**
 * 有効な生成変数を 1 つ選ぶ（同時に複数の展開は行わない）。
 * ① UI から明示されたモードを最優先。② 無ければ front-matter の multi / expand を既定として使う。
 * UI で明示的に single が指定された変数は、front-matter の既定より優先して無効化する。
 */
export function pickGenerator(generators, variables) {
  const own = generators && typeof generators === 'object' && !Array.isArray(generators)
    ? generators
    : {};
  const byName = new Map(variables.map((v) => [v.name, v]));
  const build = (name, cfg, variable) => {
    const normalized = normalizeGenerator(cfg, variable);
    return { name, mode: normalized.mode, cfg: normalized, items: generatorItems(normalized) };
  };

  for (const [name, cfg] of Object.entries(own)) {
    if (!byName.has(name)) continue;
    const normalized = normalizeGenerator(cfg, byName.get(name));
    if (normalized.mode === 'single') continue;
    return build(name, cfg, byName.get(name));
  }

  for (const v of variables) {
    if (!v.multi && !v.expand) continue;
    if (Object.prototype.hasOwnProperty.call(own, v.name)) continue;
    const normalized = normalizeGenerator({}, v);
    if (normalized.mode === 'single') continue;
    return build(v.name, {}, v);
  }
  return null;
}

/** 旧 API 互換ラッパー */
export function normalizeExpansion(cfg, variable) {
  return normalizeGenerator({ ...(cfg && typeof cfg === 'object' ? cfg : {}), mode: cfg?.mode || 'range' }, variable);
}

/** 旧 API 互換ラッパー */
export function pickExpansion(expansions, variables) {
  return pickGenerator(expansions, variables);
}

/* ============================================================
 * TABLE 入力モード
 *   1 変数 = 1 列 / 1 行 = 1 件のコード
 *   空欄セルは各変数の「単一値（既定値）」で補完する
 *   自動生成変数（Upper / Lower）は列にせず、行の値から都度算出する
 * ============================================================ */

export const TABLE_MAX_ROWS = MAX_ITEMS;

/** 表の列になる変数。front-matter の宣言順を優先し、未宣言は本文の出現順。 */
export function tableColumns(variables) {
  return (variables || [])
    .map((v, i) => ({ v, i }))
    .filter(({ v }) => !v.autoCounter && v.type !== 'sequence')
    .sort((a, b) => {
      const ao = Number.isFinite(a.v.declaredOrder) ? a.v.declaredOrder : Number.MAX_SAFE_INTEGER;
      const bo = Number.isFinite(b.v.declaredOrder) ? b.v.declaredOrder : Number.MAX_SAFE_INTEGER;
      return ao - bo || a.i - b.i;
    })
    .map(({ v }) => v.name);
}

const cellText = (value) => (value == null ? '' : String(value));

export function blankTableRow(columns) {
  const row = {};
  for (const c of columns) row[c] = '';
  return row;
}

/** UI / API の table 状態を正規化する。全空の行は 1 行だけ残す。 */
export function normalizeTable(table, variables) {
  const columns = tableColumns(variables);
  const raw = table && typeof table === 'object' && !Array.isArray(table) ? table : {};
  const enabled = raw.enabled === true;
  const source = Array.isArray(raw.rows) ? raw.rows : [];
  const rows = source.slice(0, TABLE_MAX_ROWS).map((r) => {
    const out = blankTableRow(columns);
    if (Array.isArray(r)) {
      columns.forEach((c, i) => { out[c] = cellText(r[i]); });
    } else if (r && typeof r === 'object') {
      for (const c of columns) {
        if (Object.prototype.hasOwnProperty.call(r, c)) out[c] = cellText(r[c]);
      }
    }
    return out;
  });
  const meaningful = rows.filter((r) => columns.some((c) => r[c].trim() !== ''));
  const finalRows = meaningful.length ? meaningful : [rows[0] || blankTableRow(columns)];
  return { enabled, columns, rows: finalRows, count: finalRows.length };
}

/** 空欄を除外した上書き値。空欄は呼び出し側の values（既定値）をそのまま使う。 */
export function tableOverrides(row, columns) {
  const out = {};
  for (const c of columns) {
    const v = row[c];
    if (v != null && String(v).trim() !== '') out[c] = String(v);
  }
  return out;
}

/** 出力見出し用の短いラベル（行の先頭 2 つの非空値） */
export function tableLabel(row, columns) {
  const parts = [];
  for (const c of columns) {
    const v = String(row[c] ?? '').trim();
    if (!v) continue;
    parts.push(v.length > 24 ? `${v.slice(0, 23)}…` : v);
    if (parts.length >= 2) break;
  }
  return parts.join(' / ');
}

/** ヘッダー名を変数名に正規化する（{{item}} / 前後空白 / 全角括弧も吸収） */
export function normalizeHeader(value) {
  return cellText(value)
    .trim()
    .replace(/^[｛{]{2}/, '')
    .replace(/[｝}]{2}$/, '')
    .trim();
}

/** 1 行目が変数名のヘッダーかどうかを判定する */
export function looksLikeHeader(headerRow, columns) {
  const hits = (headerRow || []).filter((h) => columns.includes(normalizeHeader(h))).length;
  return hits > 0 && hits >= Math.min(2, columns.length);
}

/**
 * ファイル / 貼り付けから得た「ヘッダー行 + データ行」を変数列の行オブジェクトへ変換する。
 * ヘッダーがあれば名前で対付けし、無ければ列順（variables の宣言順）で割り当てる。
 */
export function mapTableRows(headerRow, dataRows, columns) {
  const mapping = (headerRow || []).map(normalizeHeader);
  return (dataRows || []).slice(0, TABLE_MAX_ROWS).map((cells) => {
    const row = blankTableRow(columns);
    const list = Array.isArray(cells) ? cells : [cells];
    if (mapping.length) {
      mapping.forEach((name, i) => {
        if (columns.includes(name)) row[name] = cellText(list[i]);
      });
    } else {
      columns.forEach((c, i) => { row[c] = cellText(list[i]); });
    }
    return row;
  });
}

/** Excel / スプレッドシートからの貼り付け（タブ区切り優先、CSV も可） */
export function parsePastedTable(text, columns) {
  const lines = String(text ?? '').split(/\r?\n/).filter((l) => l.trim() !== '');
  const parse = (line) => (line.includes('\t') ? line.split('\t') : line.split(','))
    .map((s) => s.trim());
  const first = parse(lines[0] || '');
  const hasHeader = looksLikeHeader(first, columns);
  return mapTableRows(
    hasHeader ? first : [],
    (hasHeader ? lines.slice(1) : lines).map(parse),
    columns,
  );
}

/** 1 列を数字範囲で埋めた行を作る（他列は base の値を複製） */
export function buildRangeRows({
  column, from, to, step = 1, pad = 0, prefix = '', suffix = '', base = {}, columns,
}) {
  const cols = columns || [column];
  const start = Math.trunc(Number(from));
  const end = Math.trunc(Number(to));
  const inc = Math.trunc(Number(step)) || 1;
  if (!Number.isFinite(start) || !Number.isFinite(end) || !column) return [];
  const digits = Math.max(0, Math.min(20, Math.trunc(Number(pad) || 0)));
  const out = [];
  const push = (i) => {
    if (out.length >= TABLE_MAX_ROWS) return;
    const row = blankTableRow(cols);
    for (const c of cols) row[c] = cellText(base?.[c] ?? '');
    row[column] = `${prefix}${padNum(i, digits)}${suffix}`;
    out.push(row);
  };
  if (inc > 0) { for (let i = start; i <= end; i += inc) push(i); }
  else { for (let i = start; i >= end; i += inc) push(i); }
  return out;
}


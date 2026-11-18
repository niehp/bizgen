/**
 * 固定値プリセット（列 = 固定値）の共通モデル。
 * サーバー / ブラウザー / CLI で同じ正規化・マージ規則を使う。
 *
 * preset = {
 *   id, name,
 *   entries: [{ column, value }],   // column = DB 列名, value = SQL リテラル or キーワード
 *   createdAt, updatedAt
 * }
 *
 * 例:
 *   company_cd = 10
 *   create_date = CURRENT_TIMESTAMP
 * 値は shared/mapping.js の defaultLiteral() で解釈され、
 * CURRENT_TIMESTAMP / NOW() / DEFAULT はそのまま SQL に出力される。
 */

const text = (v) => (v === undefined || v === null ? '' : String(v));

/** 外部 JSON / UI 入力を正規形へ。列名の重複は後勝ち、空の値は除外しない（UI で編集途中を保持） */
export function normalizePreset(raw = {}) {
  const src = raw && typeof raw === 'object' ? raw : {};
  const seen = new Set();
  const entries = (Array.isArray(src.entries) ? src.entries : [])
    .map((e) => ({
      column: text(e?.column ?? e?.name).trim(),
      value: text(e?.value),
    }))
    .filter((e) => {
      if (!e.column || seen.has(e.column)) return false;
      seen.add(e.column);
      return true;
    });
  return {
    id: text(src.id),
    name: text(src.name).trim(),
    entries,
    createdAt: text(src.createdAt),
    updatedAt: text(src.updatedAt),
  };
}

/**
 * 既存の既定値（defaults: [{column, value}]）へプリセットの列=値をマージする。
 * 同じ列はプリセット側で上書き（固定値を確実に反映）、新しい列は追加。
 */
export function mergePresetIntoDefaults(defaults, entries) {
  const byColumn = new Map();
  for (const d of Array.isArray(defaults) ? defaults : []) {
    const col = text(d?.column).trim();
    if (!col) continue;
    byColumn.set(col, { column: col, value: text(d?.value) });
  }
  for (const e of Array.isArray(entries) ? entries : []) {
    const col = text(e?.column).trim();
    if (!col) continue;
    byColumn.set(col, { column: col, value: text(e?.value) });
  }
  return [...byColumn.values()];
}

/** ファイル名用の安全なStem（日本語は保持、Windows 禁則は置換） */
export function presetFileStem(value, fallback = 'preset', maxLength = 64) {
  let s = text(value).normalize('NFC')
    .replace(/[\\/:*?"<>|\u0000-\u001f\u007f]/g, '_')
    .replace(/\s+/g, ' ')
    .trim();
  s = s.slice(0, maxLength).trim();
  return s || fallback;
}

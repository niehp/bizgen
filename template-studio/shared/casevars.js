/**
 * 「変数を 1 つ定義すると、その先頭大文字版 / 先頭小文字版が自動的に新しい変数になる」機能。
 * サーバー（extract / render）とブラウザ（入力欄のプレビュー）で同じ定義を共有する。
 *
 *   基礎変数 item  →  {{itemUpper}} / {{itemLower}}
 *
 * ・明示的に front-matter の vars に書かれた名前は、派生として扱わない（実変数が優先）。
 * ・接尾辞は front-matter の caseVars: { upper: ..., lower: ... } で変更できる。
 * ・autoCaseVars: false で丸ごと無効化できる。
 */
import { TRANSFORM_MAP } from './transforms.js';

export const DEFAULT_CASE_SUFFIXES = { upper: 'Upper', lower: 'Lower' };

export const CASE_VARIANTS = [
  { key: 'upper', label: '先頭大文字', filter: 'upperFirst' },
  { key: 'lower', label: '先頭小文字', filter: 'lowerFirst' },
];

const safeSuffix = (value, fallback) => {
  const s = String(value ?? '').trim().replace(/[^A-Za-z0-9_]/g, '');
  return s || fallback;
};

/** meta 形式でも、extract が返す { enabled, upper, lower } 形式でも受け取れる */
function normalize(cfg) {
  if (cfg && typeof cfg === 'object' && ('enabled' in cfg || 'upper' in cfg || 'lower' in cfg)) {
    return normalizeCaseVars({
      autoCaseVars: cfg.enabled !== false,
      caseVars: { upper: cfg.upper, lower: cfg.lower },
    });
  }
  return normalizeCaseVars(cfg || {});
}

/** front-matter の設定を正規化する */
export function normalizeCaseVars(meta = {}) {
  const enabled = meta.autoCaseVars !== false && meta.caseVars !== false;
  const raw = meta.caseVars && typeof meta.caseVars === 'object' && !Array.isArray(meta.caseVars)
    ? meta.caseVars
    : {};
  let upper = safeSuffix(raw.upper ?? raw.upperSuffix, DEFAULT_CASE_SUFFIXES.upper);
  let lower = safeSuffix(raw.lower ?? raw.lowerSuffix, DEFAULT_CASE_SUFFIXES.lower);
  if (upper === lower) {
    upper = DEFAULT_CASE_SUFFIXES.upper;
    lower = DEFAULT_CASE_SUFFIXES.lower;
  }
  return { enabled, upper, lower };
}

/** 基礎変数 1 個から自動生成される変数の定義 */
export function caseVarDefinitions(base, cfg) {
  const c = normalize(cfg);
  if (!c.enabled) return [];
  return CASE_VARIANTS.map((v) => {
    const name = `${base}${c[v.key]}`;
    return {
      name,
      base,
      variant: v.key,
      label: v.label,
      filter: v.filter,
      placeholder: `{{${name}}}`,
    };
  });
}

/**
 * 名前が「基礎変数 + 接尾辞」なら派生変数として解決する。
 * 明示宣言済みの名前は実変数なので解決しない。
 */
export function resolveCaseVar(name, cfg, declaredNames = new Set()) {
  const c = normalize(cfg);
  if (!c.enabled || declaredNames.has(name)) return null;
  for (const v of CASE_VARIANTS) {
    const suffix = c[v.key];
    if (!name.endsWith(suffix) || name.length <= suffix.length) continue;
    const base = name.slice(0, -suffix.length);
    if (!/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(base)) continue;
    return {
      name,
      base,
      variant: v.key,
      label: v.label,
      filter: v.filter,
      placeholder: `{{${name}}}`,
    };
  }
  return null;
}

/** 派生値そのものを計算する（UI プレビューと render で同じ結果になる） */
export function applyCaseVariant(text, filter) {
  const def = TRANSFORM_MAP[filter];
  const s = String(text ?? '');
  return def ? String(def.apply(s, {}, {})) : s;
}

/**
 * 「変数を 1 つ定義すると、その命名規則違いが自動的に新しい変数になる」機能。
 * サーバー（extract / render）とブラウザ（入力欄のプレビュー）で同じ定義を共有する。
 *
 *   基礎変数 org_tree から 6 種類を自動生成（入力不要）:
 *     {{org_treeUpper}}      先頭大文字        Org_tree
 *     {{org_treeLower}}      先頭小文字        org_tree
 *     {{org_treePascal}}     大驼峰 PascalCase OrgTree
 *     {{org_treeCamel}}      小驼峰 camelCase  orgTree
 *     {{org_treeUpperSnake}} 大蛇形 UPPER_SNAKE ORG_TREE
 *     {{org_treeSnake}}      小蛇形 snake_case org_tree
 *
 * ・明示的に front-matter の vars に書かれた名前は、派生として扱わない（実変数が優先）。
 * ・接尾辞は front-matter の caseVars: { upper: ..., camel: ... } で変更できる。
 * ・autoCaseVars: false で丸ごと無効化できる。
 */
import { TRANSFORM_MAP } from './transforms.js';

export const DEFAULT_CASE_SUFFIXES = {
  upper: 'Upper',
  lower: 'Lower',
  pascal: 'Pascal',
  camel: 'Camel',
  upperSnake: 'UpperSnake',
  snake: 'Snake',
};

/** key = 設定名 / label = 表示名 / filter = transforms.js の変換名 */
export const CASE_VARIANTS = [
  { key: 'upper', label: '首字母大写', filter: 'upperFirst' },
  { key: 'lower', label: '首字母小写', filter: 'lowerFirst' },
  { key: 'pascal', label: '大驼峰 PascalCase', filter: 'pascal' },
  { key: 'camel', label: '小驼峰 camelCase', filter: 'camel' },
  { key: 'upperSnake', label: '大蛇形 UPPER_SNAKE', filter: 'constant' },
  { key: 'snake', label: '小蛇形 snake_case', filter: 'snake' },
];

const VARIANT_BY_KEY = Object.fromEntries(CASE_VARIANTS.map((v) => [v.key, v]));

const safeSuffix = (value, fallback) => {
  const s = String(value ?? '').trim().replace(/[^A-Za-z0-9_]/g, '');
  return s || fallback;
};

/**
 * meta 形式でも、extract が返す { enabled, ...各接尾辞 } 形式でも受け取れる。
 * 6 種すべての接尾辞を保持し、以前の upper/lower だけのデータも補完する。
 */
function normalize(cfg) {
  if (cfg && typeof cfg === 'object'
    && ('enabled' in cfg || 'upper' in cfg || 'lower' in cfg || 'camel' in cfg)) {
    const raw = {};
    for (const v of CASE_VARIANTS) if (cfg[v.key] != null) raw[v.key] = cfg[v.key];
    return normalizeCaseVars({
      autoCaseVars: cfg.enabled !== false,
      caseVars: raw,
    });
  }
  return normalizeCaseVars(cfg || {});
}

/** front-matter の設定を正規化する（6 種すべての接尾辞を返す） */
export function normalizeCaseVars(meta = {}) {
  const enabled = meta.autoCaseVars !== false && meta.caseVars !== false;
  const raw = meta.caseVars && typeof meta.caseVars === 'object' && !Array.isArray(meta.caseVars)
    ? meta.caseVars
    : {};
  const out = { enabled };
  for (const v of CASE_VARIANTS) {
    out[v.key] = safeSuffix(
      raw[v.key] ?? raw[`${v.key}Suffix`],
      DEFAULT_CASE_SUFFIXES[v.key],
    );
  }
  // 接尾辞が衝突すると解決が曖昧になるため、衝突分は既定値に戻す。
  const used = new Set();
  for (const v of CASE_VARIANTS) {
    if (used.has(out[v.key])) out[v.key] = DEFAULT_CASE_SUFFIXES[v.key];
    used.add(out[v.key]);
  }
  return out;
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
 * 名前が「基礎変数 + いずれかの接尾辞」なら派生変数として解決する。
 * 明示宣言済みの名前は実変数なので解決しない。
 * 長い接尾辞を優先する（UpperSnake を Upper より先に判定）。
 */
export function resolveCaseVar(name, cfg, declaredNames = new Set()) {
  const c = normalize(cfg);
  if (!c.enabled || declaredNames.has(name)) return null;
  const ordered = [...CASE_VARIANTS].sort((a, b) => c[b.key].length - c[a.key].length);
  for (const v of ordered) {
    const suffix = c[v.key];
    if (!name.endsWith(suffix) || name.length <= suffix.length) continue;
    const base = name.slice(0, -suffix.length);
    if (!/^[A-Za-z_][A-Za-z0-9_.-]*$/.test(base)) continue;
    const def = VARIANT_BY_KEY[v.key];
    return {
      name,
      base,
      variant: v.key,
      label: def.label,
      filter: def.filter,
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

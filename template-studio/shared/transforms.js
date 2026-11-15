/**
 * テンプレート変数の式・変換（フィルタ）定義。
 * サーバーの render とブラウザのプレビューで同じ実装を共有する（単一真実源）。
 * Node.js / ブラウザ双方で動くよう、Node 固有 API は使用しない。
 */

export const TYPES = [
  'text', 'textarea', 'code', 'number', 'boolean', 'select', 'date', 'list', 'sequence',
];

/** {{ ... }} を捕捉する。format='{n}' のような内の波括弧を許すため、最短一致で閉じる。 */
export const VAR_RE = /\{\{([^\n]*?)\}\}/g;
export const newVarPattern = () => new RegExp(VAR_RE.source, 'g');

/* ------------------------------------------------------------------ */
/* 共通ヘルパ                                                          */
/* ------------------------------------------------------------------ */

/** 引用符・括弧の外側だけで分割する（filter 引数内の , や | を守る） */
function splitTopLevel(text, sep) {
  const out = [];
  let buf = '';
  let quote = null;
  let depth = 0;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (quote) {
      buf += ch;
      if (ch === quote && text[i - 1] !== '\\') quote = null;
      continue;
    }
    if (ch === '"' || ch === "'") { quote = ch; buf += ch; continue; }
    if (ch === '(' || ch === '[' || ch === '{') { depth += 1; buf += ch; continue; }
    if (ch === ')' || ch === ']' || ch === '}') { depth -= 1; buf += ch; continue; }
    if (ch === sep && depth === 0) { out.push(buf); buf = ''; continue; }
    buf += ch;
  }
  out.push(buf);
  return out;
}

function unquote(s) {
  const t = String(s).trim();
  if (t.length >= 2 && ((t[0] === '"' && t.at(-1) === '"') || (t[0] === "'" && t.at(-1) === "'"))) {
    return t.slice(1, -1).replace(/\\(['"\\])/g, '$1');
  }
  return t;
}

function nval(v, fallback) {
  if (v == null || v === '') return fallback;
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
}

/** 負数にも対応したゼロ埋め。文字列にも使える。 */
export function padNum(value, width, char = '0') {
  const s = String(value);
  const w = Math.max(0, Math.trunc(nval(width, 0)));
  if (!w || s.length >= w) return s;
  const fill = String(char || '0') || '0';
  if (s.startsWith('-')) return '-' + fill.repeat(w - s.length) + s.slice(1);
  return s.padStart(w, fill);
}

/** camelCase / snake_case / 区切り文字を単語に分解する */
function words(s) {
  return String(s)
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean);
}

const cap = (w) => w.charAt(0).toUpperCase() + w.slice(1);
const lower = (w) => w.toLowerCase();

/** 先頭 1 文字が英大文字 / 英小文字 / それ以外かを判定する */
export function firstCharCase(value) {
  const ch = String(value ?? '').trimStart().charAt(0);
  if (!ch) return 'none';
  if (/[A-Z]/.test(ch)) return 'upper';
  if (/[a-z]/.test(ch)) return 'lower';
  return 'other';
}

export const FIRST_CASE_LABEL = {
  upper: '大文字', lower: '小文字', other: '英字以外', none: '（空）',
};

/**
 * 変換結果の先頭英文字を、元の値の先頭文字の大小に戻す。
 * 接頭辞やインデントに非英字が付いていても、最初の英文字を探して適用する。
 * 例: 元値 OrgTree → snake 結果 org_tree → Org_tree
 */
export function restoreFirstCase(text, original) {
  const s = String(text ?? '');
  const kind = firstCharCase(original);
  if (kind !== 'upper' && kind !== 'lower') return s;
  const m = /[A-Za-z]/.exec(s);
  if (!m) return s;
  const i = m.index;
  const ch = kind === 'upper' ? s[i].toUpperCase() : s[i].toLowerCase();
  return s.slice(0, i) + ch + s.slice(i + 1);
}

/** 明示的に大小文字を指定する変換。keepFirstCase よりこちらを優先する。 */
export const CASE_OVERRIDE_FILTERS = new Set([
  'upperFirst', 'lowerFirst', 'upper', 'lower',
  'isUpperFirst', 'isLowerFirst', 'ifUpperFirst', 'ifLowerFirst',
  'keepFirstCase',
]);

const upperFirst = (s) => s.charAt(0).toUpperCase() + s.slice(1);
const lowerFirst = (s) => s.charAt(0).toLowerCase() + s.slice(1);

/** 判定結果に応じて {v} 原値 / {U} 先頭大文字 / {L} 先頭小文字 を展開する */
function caseBranch(s, a, want, defYes, defNo) {
  const hit = firstCharCase(s) === want;
  const tpl = String(hit ? (a.yes ?? defYes) : (a.no ?? defNo));
  return tpl
    .replaceAll('{v}', s)
    .replaceAll('{U}', upperFirst(s))
    .replaceAll('{L}', lowerFirst(s));
}

/* ------------------------------------------------------------------ */
/* 連番（sequence 型）                                                  */
/* ------------------------------------------------------------------ */

export function normalizeSequence(raw, variable) {
  const cfg = {
    start: 1, end: 5, step: 1, pad: 0, char: '0', joiner: '\n',
    ...(variable?.sequence || {}),
  };
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) Object.assign(cfg, raw);
  cfg.start = nval(cfg.start, 1);
  cfg.end = nval(cfg.end, 5);
  cfg.step = nval(cfg.step, 1) || 1;
  cfg.pad = Math.min(20, Math.max(0, Math.trunc(nval(cfg.pad, 0))));
  cfg.char = String(cfg.char || '0');
  cfg.joiner = String(cfg.joiner ?? '\n').replace(/\\n/g, '\n').replace(/\\t/g, '\t');
  return cfg;
}

export function makeSequence(cfg) {
  const out = [];
  const MAX = 500;
  if (cfg.step > 0) {
    for (let i = cfg.start; i <= cfg.end && out.length < MAX; i += cfg.step) out.push(i);
  } else {
    for (let i = cfg.start; i >= cfg.end && out.length < MAX; i += cfg.step) out.push(i);
  }
  if (!out.length) out.push(cfg.start);
  return out.map((n) => padNum(n, cfg.pad, cfg.char));
}

/* ------------------------------------------------------------------ */
/* 変換（フィルタ）カタログ                                             */
/* ------------------------------------------------------------------ */

const A = (name, label, def, hint) => ({ name, label, default: String(def ?? ''), ...(hint ? { hint } : {}) });

export const TRANSFORMS = [
  {
    name: 'upperFirst', label: '先頭を大文字', group: 'case',
    description: '先頭の 1 文字だけ大文字化する（2 文字目以降は変更しない）',
    example: 'orgTree → OrgTree', args: [],
    apply: (s) => s.charAt(0).toUpperCase() + s.slice(1),
  },
  {
    name: 'lowerFirst', label: '先頭を小文字', group: 'case',
    description: '先頭の 1 文字だけ小文字化する（2 文字目以降は変更しない）',
    example: 'OrgTree → orgTree', args: [],
    apply: (s) => s.charAt(0).toLowerCase() + s.slice(1),
  },
  {
    name: 'upper', label: 'すべて大文字', group: 'case',
    description: '文字列全体を大文字化する', example: 'orgTree → ORGTREE', args: [],
    apply: (s) => s.toUpperCase(),
  },
  {
    name: 'lower', label: 'すべて小文字', group: 'case',
    description: '文字列全体を小文字化する', example: 'OrgTree → orgtree', args: [],
    apply: (s) => s.toLowerCase(),
  },
  {
    name: 'camel', label: 'camelCase', group: 'case',
    description: '単語境界を詰めて先頭小文字のキャメルケースにする（変数名・フィールド名向け）',
    example: 'org_tree_node → orgTreeNode', args: [],
    apply: (s) => words(s).map((w, i) => (i === 0 ? lower(w) : cap(lower(w)))).join(''),
  },
  {
    name: 'pascal', label: 'PascalCase', group: 'case',
    description: '単語境界を詰めて先頭大文字にする（クラス名・型名向け）',
    example: 'org_tree_node → OrgTreeNode', args: [],
    apply: (s) => words(s).map((w) => cap(lower(w))).join(''),
  },
  {
    name: 'snake', label: 'snake_case', group: 'case',
    description: '単語をアンダースコアで連結する（DB カラム名向け）',
    example: 'OrgTreeNode → org_tree_node', args: [],
    apply: (s) => words(s).map(lower).join('_'),
  },
  {
    name: 'kebab', label: 'kebab-case', group: 'case',
    description: '単語をハイフンで連結する（CSS クラス名・URL 向け）',
    example: 'OrgTreeNode → org-tree-node', args: [],
    apply: (s) => words(s).map(lower).join('-'),
  },
  {
    name: 'constant', label: 'CONSTANT_CASE', group: 'case',
    description: '大文字のアンダースコア連結（定数名向け）',
    example: 'orgTreeNode → ORG_TREE_NODE', args: [],
    apply: (s) => words(s).map((w) => lower(w).toUpperCase()).join('_'),
  },
  {
    name: 'trim', label: '前後空白除去', group: 'text',
    description: '先頭・末尾の空白を除去する', example: '  abc  → abc', args: [],
    apply: (s) => s.trim(),
  },
  {
    name: 'pad', label: 'ゼロ埋め', group: 'number',
    description: '指定桁数まで文字で埋める（伝票番号・コードの採番）',
    example: '7 → 007',
    args: [A('width', '桁数', 3), A('char', '埋める文字', '0')],
    apply: (s, a) => padNum(s, a.width ?? 3, a.char || '0'),
  },
  {
    name: 'seq', label: '連番付与（リスト）', group: 'number',
    description: 'list / sequence の各行に連番を付ける。行ごとに {n}=番号 / {v}=値 で書式指定',
    example: 'a,b → 01. a / 02. b',
    args: [
      A('start', '開始番号', 1),
      A('pad', '桁数', 0),
      A('format', '書式', '{n}. {v}'),
      A('joiner', '区切り', '\\n', '改行は \\n、カンマは , と入力'),
    ],
    apply: (s, a, ctx) => {
      const list = (ctx.items && ctx.items.length ? ctx.items : String(s).split(/\r?\n/))
        .map((v) => String(v).trim()).filter(Boolean);
      const start = nval(a.start, 1);
      const pad = nval(a.pad, 0);
      const fmt = String(a.format ?? '{n}. {v}');
      const joiner = String(a.joiner ?? '\n').replace(/\\n/g, '\n').replace(/\\t/g, '\t');
      return list.map((v, i) => fmt
        .replaceAll('{n}', padNum(start + i, pad))
        .replaceAll('{v}', v))
        .join(joiner);
    },
  },
  {
    name: 'counter', label: '連番カウンタ', group: 'number',
    description: 'テンプレート内に出現するたびに 1 ずつ増える番号を出力する（入力値は不要）',
    example: '3 箇所に付けると 001 / 002 / 003',
    args: [
      A('id', 'カウンタ ID', 'default', '別系統の連番を作るときに変更'),
      A('start', '開始番号', 1),
      A('step', '増分', 1),
      A('pad', '桁数', 0),
    ],
    apply: (_s, a, ctx) => {
      const counters = ctx.counters || (ctx.counters = {});
      const id = String(a.id || 'default');
      const start = nval(a.start, 1);
      const step = nval(a.step, 1) || 1;
      if (!(id in counters)) counters[id] = start;
      const n = counters[id];
      counters[id] += step;
      return padNum(n, a.pad ?? 0);
    },
  },
  {
    name: 'isUpperFirst', label: '先頭が大文字か判定', group: 'condition',
    description: '先頭文字が英大文字なら true、それ以外は false を出力する',
    example: 'OrgTree → true / orgTree → false', args: [],
    apply: (s) => (firstCharCase(s) === 'upper' ? 'true' : 'false'),
  },
  {
    name: 'isLowerFirst', label: '先頭が小文字か判定', group: 'condition',
    description: '先頭文字が英小文字なら true、それ以外は false を出力する',
    example: 'orgTree → true / OrgTree → false', args: [],
    apply: (s) => (firstCharCase(s) === 'lower' ? 'true' : 'false'),
  },
  {
    name: 'ifUpperFirst', label: '大文字なら / それ以外', group: 'condition',
    description: '先頭が大文字なら yes、それ以外なら no を出力。{v} 原値 / {U} 先頭大文字 / {L} 先頭小文字が使える',
    example: '既定では 大文字→{U} / それ以外→{L}（同じ変数のまま分岐）',
    args: [A('yes', '大文字なら', '{U}'), A('no', 'それ以外', '{L}')],
    apply: (s, a) => caseBranch(s, a, 'upper', '{U}', '{L}'),
  },
  {
    name: 'ifLowerFirst', label: '小文字なら / それ以外', group: 'condition',
    description: '先頭が小文字なら yes、それ以外なら no を出力。{v} / {U} / {L} が使える',
    example: '既定では 小文字→{L} / それ以外→{U}',
    args: [A('yes', '小文字なら', '{L}'), A('no', 'それ以外', '{U}')],
    apply: (s, a) => caseBranch(s, a, 'lower', '{L}', '{U}'),
  },
  {
    name: 'keepFirstCase', label: '先頭文字を保持', group: 'condition',
    description: '元の値の先頭が大文字なら変換後も大文字、小文字なら小文字に戻す（同じ変数の大小関係を維持）',
    example: 'OrgTree → snake → Org_tree / orgTree → snake → org_tree',
    args: [],
    apply: (s, _a, ctx) => restoreFirstCase(s, ctx.base?.text ?? s),
  },
];

export const TRANSFORM_MAP = Object.fromEntries(TRANSFORMS.map((t) => [t.name, t]));
export const TRANSFORM_CATALOG = TRANSFORMS.map(({ apply, ...rest }) => rest);
export const TRANSFORM_GROUPS = [
  { id: 'case', label: '大文字・小文字 / 命名規則' },
  { id: 'condition', label: '先頭文字の判定・分岐' },
  { id: 'number', label: '連番・数字' },
  { id: 'text', label: '文字列' },
];

/* ------------------------------------------------------------------ */
/* 式の解析                                                            */
/* ------------------------------------------------------------------ */

function parseArgs(raw, def = []) {
  const args = {};
  if (!raw || !raw.trim()) return args;
  const parts = splitTopLevel(raw, ',');
  parts.forEach((part, i) => {
    const p = part.trim();
    if (!p) return;
    const eq = p.indexOf('=');
    const key = eq >= 0 ? p.slice(0, eq).trim() : (def[i]?.name || String(i));
    let val = unquote(eq >= 0 ? p.slice(eq + 1) : p);
    val = val.replace(/\\n/g, '\n').replace(/\\t/g, '\t');
    args[key] = val;
  });
  return args;
}

/**
 * "{{ name:type | filter(args) | filter }}" の中身を解析する。
 * 不正な式でも変数そのものは失わないよう、警告は warnings に積む。
 */
export function parseExpression(inner) {
  const raw = String(inner ?? '').trim();
  const warnings = [];
  const segments = splitTopLevel(raw, '|');
  const head = (segments.shift() || '').trim();
  const hm = head.match(/^([A-Za-z_][A-Za-z0-9_.-]*)\s*(?::\s*([A-Za-z]+))?$/);
  if (!hm) {
    return { valid: false, name: '', inlineType: null, filters: [], raw, warnings: [`変数式を解釈できません: {{${raw}}}`] };
  }

  const filters = [];
  for (const seg of segments) {
    const s = seg.trim();
    const fm = s.match(/^([A-Za-z][A-Za-z0-9_]*)\s*(?:\(([^()]*)\))?$/);
    if (!fm) {
      warnings.push(`変換式を解釈できません: "${s}"（無視しました）`);
      continue;
    }
    const def = TRANSFORM_MAP[fm[1]];
    if (!def) {
      warnings.push(`未対応の変換 "${fm[1]}" を {{${raw}}} で指定しています（無視しました）。`);
      filters.push({ name: fm[1], args: {}, unknown: true });
      continue;
    }
    filters.push({ name: fm[1], args: parseArgs(fm[2], def.args), unknown: false });
  }

  return {
    valid: true,
    name: hm[1],
    inlineType: hm[2] ? hm[2].toLowerCase() : null,
    filters,
    raw,
    warnings,
  };
}

/** プレースホルダ文字列を組み立てる（コピー用） */
export function buildPlaceholder(name, filterName, args = {}) {
  const def = TRANSFORM_MAP[filterName];
  if (!def) return `{{${name}}}`;
  const parts = Object.entries(args)
    .filter(([, v]) => v !== '' && v != null)
    .map(([k, v]) => {
      const s = String(v).replace(/\n/g, '\\n');
      const needQuote = /[\s,()'"]/.test(s);
      return `${k}=${needQuote ? `'${s.replace(/'/g, "\\'")}'` : s}`;
    });
  return `{{${name} | ${filterName}${def && parts.length ? `(${parts.join(', ')})` : ''}}}`;
}

/* ------------------------------------------------------------------ */
/* 値 → 基礎値 / 変換適用                                               */
/* ------------------------------------------------------------------ */

export function toBaseValue(variable, raw) {
  const type = variable?.type || 'text';
  if (type === 'boolean') {
    const text = raw === true || raw === 'true' || raw === 1 || raw === '1' ? 'true' : 'false';
    return { text, items: null };
  }
  if (type === 'sequence') {
    const cfg = normalizeSequence(raw, variable);
    const items = makeSequence(cfg);
    return { text: items.join(cfg.joiner), items };
  }
  if (type === 'list') {
    const items = String(raw ?? '').split(/\r?\n/).map((s) => s.trim()).filter(Boolean);
    return { text: items.join(variable?.joiner || ', '), items };
  }
  const text = raw == null ? '' : (typeof raw === 'object' ? '' : String(raw));
  const items = ['code', 'textarea'].includes(type) && text.includes('\n')
    ? text.split(/\r?\n/)
    : null;
  return { text, items };
}

export function applyFilters(base, filters = [], ctx = {}) {
  const counters = ctx.counters || {};
  const items = base?.items ?? null;
  const explicitCase = filters.some((f) => CASE_OVERRIDE_FILTERS.has(f.name));
  let text = base?.text ?? '';
  for (const f of filters) {
    if (f.unknown) continue;
    const def = TRANSFORM_MAP[f.name];
    if (!def) continue;
    text = String(def.apply(text, f.args || {}, { ...ctx, counters, items, base }));
  }
  // 変数規則 / 全体スイッチが有効なら、変換後も元の先頭文字の大小を維持する。
  // ただし upperFirst 等を明示した箇所は、その指定を尊重して上書きしない。
  if (ctx.keepFirstCase && !explicitCase) text = restoreFirstCase(text, base?.text ?? '');
  return text;
}

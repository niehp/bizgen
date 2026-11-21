import yaml from 'js-yaml';
import {
  TYPES,
  TRANSFORM_MAP,
  newVarPattern,
  parseExpression,
  toBaseValue,
  applyFilters,
} from '../shared/transforms.js';
import {
  normalizeCaseVars,
  resolveCaseVar,
  caseVarDefinitions,
  applyCaseVariant,
} from '../shared/casevars.js';
import {
  pickGenerator,
  normalizeTable,
  tableOverrides,
  tableLabel,
} from '../shared/expansion.js';

export { TYPES };

const FRONT_MATTER_RE = /^\uFEFF?---\s*\r?\n([\s\S]*?)\r?\n---\s*(?:\r?\n|$)/;

function splitTemplate(template) {
  const src = String(template ?? '').replace(/^\uFEFF/, '');
  const m = src.match(FRONT_MATTER_RE);
  if (!m) return { meta: {}, body: src, hasFrontMatter: false, metaError: null };

  let meta = {};
  let metaError = null;
  try {
    const loaded = yaml.load(m[1]);
    meta = loaded && typeof loaded === 'object' ? loaded : {};
  } catch (err) {
    metaError = err.message;
  }
  return { meta, body: src.slice(m[0].length), hasFrontMatter: true, metaError };
}

function lineIndex(body) {
  const starts = [0];
  for (let i = 0; i < body.length; i += 1) {
    if (body[i] === '\n') starts.push(i + 1);
  }
  return (idx) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= idx) lo = mid;
      else hi = mid - 1;
    }
    return lo + 1;
  };
}

function normalizeDefault(value) {
  if (value == null) return '';
  if (typeof value === 'boolean') return value ? 'true' : 'false';
  if (value && typeof value === 'object' && !Array.isArray(value)) return value;
  if (Array.isArray(value)) return value.join('\n');
  return String(value);
}

/** front-matter / UI から渡された既定変換を正規化する */
function normalizeTransform(t) {
  if (!t) return null;
  const spec = typeof t === 'string' ? { filter: t } : t;
  const filter = String(spec.filter || spec.name || '').trim();
  if (!filter || !TRANSFORM_MAP[filter]) return null;
  const args = spec.args && typeof spec.args === 'object' ? spec.args : {};
  return { filter, args };
}

function blankVariable(name) {
  return {
    name,
    type: 'text',
    label: name,
    default: '',
    required: false,
    options: [],
    placeholder: '',
    description: '',
    joiner: ', ',
    sequence: {},
    expand: null,
    multi: null,
    keepFirstCase: false,
    defaultTransform: null,
    occurrences: 0,
    lines: [],
    usages: [],
    transforms: [],
    derived: [],
    derivedUsed: [],
    autoCounter: false,
    source: 'inline',
  };
}

/**
 * テンプレートから変数を自動抽出する。
 * 本文の {{name}} / {{name:type}} / {{name | filter(args)}} を走査し、
 * YAML front-matter の vars で属性を上書きする。
 *
 * {{nameUpper}} / {{nameCamel}} / {{nameUpperSnake}} 等は「name から自動生成された変数」として扱い、
 * 独立した入力欄を作らず name に紐付ける（全 6 種類: 先頭大文字/小文字・大驼峰・小驼峰・大蛇形・小蛇形）。
 * front-matter に明示宣言された名前は実変数優先。
 */
export function extract(template) {
  const { meta, body, hasFrontMatter, metaError } = splitTemplate(template);
  const declared =
    meta.vars && typeof meta.vars === 'object' && !Array.isArray(meta.vars)
      ? meta.vars
      : {};
  const declaredNames = new Set(Object.keys(declared));
  const declaredOrder = new Map(Object.keys(declared).map((k, i) => [k, i]));
  const caseVars = normalizeCaseVars(meta);

  const lineOf = lineIndex(body);
  const map = new Map();
  const order = [];
  const warnings = new Set();
  const derivedIndex = new Map();

  // ① 全プレースホルダを収集し、自動生成変数（6 種類の命名規則）を解決する
  const matches = [];
  const re = newVarPattern();
  let m;
  while ((m = re.exec(body)) !== null) {
    const expr = parseExpression(m[1]);
    for (const w of expr.warnings) warnings.add(w);
    if (!expr.valid) continue;
    matches.push({ expr, index: m.index });
    const info = resolveCaseVar(expr.name, caseVars, declaredNames);
    if (info && !derivedIndex.has(info.name)) derivedIndex.set(info.name, info);
  }

  const ensure = (name) => {
    if (!map.has(name)) {
      map.set(name, blankVariable(name));
      order.push(name);
    }
    return map.get(name);
  };

  // ② 変数ごとに集約。自動生成変数の出現は基礎変数に紐付ける
  for (const { expr, index } of matches) {
    const info = derivedIndex.get(expr.name) || null;
    const v = ensure(info ? info.base : expr.name);
    const line = lineOf(index);
    v.occurrences += 1;
    v.lines.push(line);

    if (info) {
      if (!v.derivedUsed.includes(info.name)) v.derivedUsed.push(info.name);
      continue;
    }

    const { inlineType, filters } = expr;
    v.usages.push({
      raw: expr.raw,
      line,
      filters: filters.map((f) => ({ name: f.name, args: f.args, unknown: f.unknown })),
    });
    for (const f of filters) {
      if (!f.unknown && !v.transforms.includes(f.name)) v.transforms.push(f.name);
    }

    if (inlineType) {
      if (!TYPES.includes(inlineType)) {
        warnings.add(`未対応の型 "${inlineType}" が {{${expr.name}}} に指定されています。text として扱います。`);
      } else if (v.type !== 'text' && v.type !== inlineType) {
        warnings.add(`変数 "${expr.name}" に異なる型が指定されています（${v.type} / ${inlineType}）。後勝ちで ${inlineType} を採用しました。`);
        v.type = inlineType;
      } else {
        v.type = inlineType;
      }
    }
  }

  for (const v of map.values()) {
    v.declaredOrder = declaredOrder.has(v.name)
      ? declaredOrder.get(v.name)
      : Number.MAX_SAFE_INTEGER;
    const d = declared[v.name];
    if (d && typeof d === 'object') {
      v.source = 'front-matter';
      if (d.type && TYPES.includes(String(d.type).toLowerCase())) v.type = String(d.type).toLowerCase();
      if (d.label != null) v.label = String(d.label);
      if (d.default != null) v.default = normalizeDefault(d.default);
      if (d.required != null) v.required = Boolean(d.required);
      if (d.placeholder != null) v.placeholder = String(d.placeholder);
      if (d.description != null) v.description = String(d.description);
      if (d.joiner != null) v.joiner = String(d.joiner);
      if (d.sequence && typeof d.sequence === 'object') v.sequence = d.sequence;
      if (d.expand != null) v.expand = d.expand === true ? { enabled: true } : d.expand;
      if (d.multi != null) v.multi = d.multi === true ? { enabled: true } : d.multi;
      if (d.keepFirstCase != null) v.keepFirstCase = Boolean(d.keepFirstCase);
      if (d.transform != null) v.defaultTransform = normalizeTransform(d.transform);
      if (Array.isArray(d.options)) v.options = d.options.map(String);
      else if (typeof d.options === 'string') v.options = d.options.split('|').map((s) => s.trim());
    }
    if (v.type === 'select' && v.options.length === 0) {
      warnings.add(`変数 "${v.name}" は select 型ですが options が未定義です。`);
    }
    // counter のみで使われる変数は入力不要（出現順に自動採番）
    v.autoCounter = v.source === 'inline'
      && v.usages.length > 0
      && v.usages.every((u) => u.filters.some((f) => f.name === 'counter'))
      && v.transforms.length === 1
      && v.transforms[0] === 'counter';
    // すべての基礎変数に 6 種類の自動生成変数を付与する。
    // ただし同名の実変数が明示されている場合はそちらを優先し、派生を出さない。
    v.derived = caseVarDefinitions(v.name, caseVars)
      .filter((d) => !declaredNames.has(d.name) && !map.has(d.name));
  }

  if (metaError) warnings.add(`front-matter の YAML 解析に失敗しました: ${metaError}`);
  for (const key of Object.keys(declared)) {
    if (!map.has(key)) warnings.add(`front-matter の変数 "${key}" は本文で使われていません。`);
  }

  const variables = order.map((n) => map.get(n));
  const derivedVariables = variables.flatMap((v) => v.derived.map((d) => ({
    ...d,
    used: v.derivedUsed.includes(d.name),
  })));
  const types = {};
  for (const v of variables) types[v.type] = (types[v.type] || 0) + 1;

  return {
    variables,
    caseVars: { ...caseVars },
    derivedVariables,
    meta: {
      title: meta.title ? String(meta.title) : '',
      description: meta.description ? String(meta.description) : '',
      outputExt: meta.outputExt ? String(meta.outputExt).replace(/^\./, '') : 'txt',
      keepFirstCase: Boolean(meta.keepFirstCase),
    },
    stats: {
      unique: variables.length,
      occurrences: variables.reduce((s, v) => s + v.occurrences, 0),
      transforms: variables.reduce((s, v) => s + v.transforms.length, 0),
      derived: derivedVariables.length,
      derivedUsed: derivedVariables.filter((d) => d.used).length,
      multi: variables.filter((v) => v.multi != null).length,
      types,
      hasFrontMatter,
    },
    warnings: [...warnings],
  };
}

/** UI / front-matter の既定変換をフィルタ配列に変換する */
function overrideFilters(transforms, variable, name) {
  const hasOwn = transforms && Object.prototype.hasOwnProperty.call(transforms, name);
  const spec = hasOwn
    ? normalizeTransform(transforms[name])
    : (variable?.defaultTransform || null);
  if (!spec) return [];
  return [{ name: spec.filter, args: spec.args || {}, unknown: false }];
}

/**
 * 1 インスタンス分のコードを生成する。
 * replace のコールバック形式を使うため、値に $& や $1 が含まれても誤展開されない。
 * 式中に | filter があればそれを優先し、無ければ UI / front-matter の既定変換を適用する。
 * 自動生成変数は基礎値に該当する命名規則変換を適用したものを「元の値」として扱うため、
 * keepFirstCase が有効でも {{nameUpper}} は常に大文字開始、{{nameLower}} は常に小文字開始になる。
 */
function renderOnce(body, variables, byName, values, transforms, globalKeep = false, derivedIndex = new Map()) {
  const bases = {};
  const missing = [];

  for (const v of variables) {
    const raw = Object.prototype.hasOwnProperty.call(values, v.name)
      ? values[v.name]
      : v.default;
    const base = toBaseValue(v, raw);
    bases[v.name] = base;
    if (v.required && !base.text.trim()) missing.push(v.name);
  }

  // counter は成果物 1 件ごとに 1 から振り直す
  const counters = {};
  const used = new Set();
  let replaced = 0;
  const code = body.replace(newVarPattern(), (full, inner) => {
    const expr = parseExpression(inner);
    if (!expr.valid) return full;

    let name = expr.name;
    let caseInfo = null;
    if (!Object.prototype.hasOwnProperty.call(bases, name)) {
      caseInfo = derivedIndex.get(name) || null;
      if (!caseInfo) return full;
      name = caseInfo.base;
    }
    if (!Object.prototype.hasOwnProperty.call(bases, name)) return full;

    used.add(name);
    replaced += 1;
    let base = bases[name];
    if (caseInfo) {
      base = { ...base, text: applyCaseVariant(base.text, caseInfo.filter) };
    }
    const variable = byName.get(name);
    const filters = expr.filters.length
      ? expr.filters
      : (caseInfo ? [] : overrideFilters(transforms, variable, name));
    return applyFilters(base, filters, {
      counters,
      name,
      variable,
      values,
      keepFirstCase: globalKeep || Boolean(variable?.keepFirstCase),
    });
  });

  return {
    code,
    missing,
    unused: Object.keys(values).filter((k) => !used.has(k)),
    replaced,
    stats: {
      lines: code ? code.split(/\r?\n/).length : 0,
      chars: code.length,
    },
  };
}

/**
 * 変数値を埋め込んでコードを生成する。
 *
 * TABLE モード（options.table.enabled）が有効なときは「1 行 = 1 件」。
 *   ・1 変数 = 1 列。空欄セルは各変数の単一値（既定値）で補う。
 *   ・{{nameUpper}} / {{nameLower}} は行ごとの値から算出するため、
 *     大文字で始まる行は大文字のまま、小文字で始まる行は小文字のままになる。
 * TABLE が無効なら従来の生成モード（range: 数字範囲 / values: 複数値リスト）。
 *
 * code は常に先頭 1 件（後方互換）、outputs に全件を入れる。
 */
export function render(template, values = {}, transforms = {}, expansions = {}, options = {}) {
  const { meta, body } = splitTemplate(template);
  const info = extract(template);
  const variables = info.variables;
  const byName = new Map(variables.map((v) => [v.name, v]));
  const derivedIndex = new Map(info.derivedVariables.map((d) => [d.name, d]));
  // front-matter の keepFirstCase: true は常に有効。UI スイッチでも追加で有効にできる。
  const globalKeep = Boolean(meta.keepFirstCase || options.keepFirstCase);

  const tbl = normalizeTable(options.table, variables);
  const gen = tbl.enabled ? null : pickGenerator(expansions, variables);

  let instances;
  if (tbl.enabled) {
    instances = tbl.rows.map((row, i) => ({
      index: i + 1,
      label: tableLabel(row, tbl.columns),
      value: '',
      overrides: tableOverrides(row, tbl.columns),
    }));
  } else if (gen) {
    instances = gen.items.map((it) => ({
      index: it.index,
      label: it.label,
      value: it.value,
      overrides: { [gen.name]: it.value },
    }));
  } else {
    instances = [null];
  }

  const generation = tbl.enabled
    ? { name: 'TABLE', mode: 'table', count: instances.length, columns: tbl.columns }
    : (gen ? { name: gen.name, mode: gen.mode, count: instances.length } : null);

  const outputs = [];
  const missingSet = new Set();
  let replaced = 0;
  let firstUnused = [];

  instances.forEach((inst, idx) => {
    const instValues = inst ? { ...values, ...inst.overrides } : values;
    const once = renderOnce(body, variables, byName, instValues, transforms, globalKeep, derivedIndex);
    for (const m of once.missing) missingSet.add(m);
    if (idx === 0) firstUnused = once.unused;
    replaced += once.replaced;
    outputs.push({
      code: once.code,
      index: inst ? inst.index : 1,
      label: inst ? inst.label : '',
      value: inst ? inst.value : '',
      mode: generation ? generation.mode : 'single',
      replaced: once.replaced,
      stats: once.stats,
    });
  });

  const first = outputs[0] || { code: '', stats: { lines: 0, chars: 0 } };
  const outputExt = meta.outputExt ? String(meta.outputExt).replace(/^\./, '') : 'txt';

  return {
    code: first.code,
    outputs: generation ? outputs : [],
    generation,
    expansion: generation,
    caseVars: info.caseVars,
    derivedVariables: info.derivedVariables,
    missing: [...missingSet],
    unused: firstUnused,
    replaced,
    outputExt,
    stats: first.stats,
  };
}

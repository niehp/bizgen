/**
 * SQL 生成エンジン（サーバー / ブラウザー共用）
 * ・複数テーブル結合 SELECT
 * ・更新列 / 条件列を選ぶ UPDATE
 * ・条件列を選ぶ DELETE
 * 条件値 / SET 値の既定プレースホルダは「camelCase 名の埋め込みコメント + 列型別リテラル」。
 * 文字列型 '' / 数値型 99 / 日付型 '2026-12-01'。:column 形式は既定では使わない。
 * 説明用の列コメントは注入しない（addComments: true を明示した場合のみ付与）。
 */
import {
  dialectInfo, findTable, autoJoinPlan, makeTableId,
  DEFAULT_PLACEHOLDER_STYLE, isNumericType, isDateType,
} from './dbmeta.js';

/** 日付 / 日時型の条件値に置くサンプルリテラル */
export const DATE_PLACEHOLDER_LITERAL = "'2026-12-01'";

export function ident(name, dialect, quote) {
  const s = String(name ?? '');
  if (!quote) return s;
  const [open, close] = dialectInfo(dialect).quote;
  return open + s.split(close).join(close + close) + close;
}

export function columnRef(path, dialect, quote) {
  return String(path ?? '')
    .split('.')
    .filter(Boolean)
    .map((p) => ident(p, dialect, quote))
    .join('.');
}

export function qualifiedTable(table, dialect, opts = {}) {
  const { includeSchema = true, quote = false } = opts;
  const parts = [];
  if (includeSchema && dialect !== 'sqlite' && table.schema && table.schema !== 'main') {
    parts.push(table.schema);
  }
  parts.push(table.name);
  return parts.map((p) => ident(p, dialect, quote)).join('.');
}

/**
 * 物理列名を camelCase のバインド名に変換する。
 * USER_ID / user_id / user-id / userId / t1.USER_ID → userId
 * 英数字を含まない列名（日本語の物理名など）はそのまま返す。
 */
export function camelCase(name) {
  const raw = String(name ?? 'param').split('.').pop() || 'param';
  // 日本語など非 ASCII の物理名は単語分割で欠落するため、そのままバインド名に使う
  if (/[^\x00-\x7F]/.test(raw)) return raw.replace(/\s+/g, '');
  const words = raw
    .replace(/([a-z0-9])([A-Z])/g, '$1 $2')
    .replace(/([A-Z]+)([A-Z][a-z])/g, '$1 $2')
    .split(/[^A-Za-z0-9]+/)
    .filter(Boolean);
  if (!words.length) return raw;
  return words
    .map((w, i) => (i === 0
      ? w.toLowerCase()
      : w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()))
    .join('');
}

/** 列型から埋め込みコメント形式のリテラル種別を決める */
export function literalFlags(type) {
  return { numeric: isNumericType(type), date: isDateType(type) };
}

/**
 * 条件値 / SET 値のプレースホルダ。
 * 既定は日系 SI の SQL テンプレート形式（camelCase 名のブロックコメント + リテラル）。
 * 文字列型は ''、数値型は 99、日付 / 日時型は '2026-12-01' を置く。:USER_ID は使わない。
 * 明示的に別スタイルを選んだ場合のみ :name / #{name} / @name / ? を出力する。
 */
export function placeholder(name, style, options = {}) {
  const col = String(name ?? 'param').split('.').pop() || 'param';
  switch (style) {
    case 'named': return `:${col}`;
    case 'mybatis': return `#{${col}}`;
    case 'at': return `@${col}`;
    case 'question': return '?';
    case 'comment':
    default: {
      const literal = options.date ? DATE_PLACEHOLDER_LITERAL
        : options.numeric ? '99' : "''";
      return `/*${camelCase(col)}*/${literal}`;
    }
  }
}

function valueOf(raw, name, style, flags = {}) {
  const s = String(raw ?? '').trim();
  return s || placeholder(name, style, flags);
}

/**
 * WHERE 条件の列参照（t1.col / col）からメタデータ上の型名を引く。
 * 別名付きは plan.aliases で表を逆引きし、無ければ対象表 → 同名列の順に探す。
 */
export function columnTypeOf(metadata, plan, cfg, ref) {
  const path = String(ref ?? '');
  const dot = path.lastIndexOf('.');
  const alias = dot >= 0 ? path.slice(0, dot) : '';
  const colName = dot >= 0 ? path.slice(dot + 1) : path;
  let table = null;
  if (alias && plan?.aliases) {
    const tableId = Object.keys(plan.aliases).find((id) => plan.aliases[id] === alias);
    if (tableId) table = findTable(metadata, tableId);
  }
  if (!table && cfg?.tableId) table = findTable(metadata, cfg.tableId);
  if (!table && plan?.tables?.length) {
    table = plan.tables.find((t) => t.columns.some((c) => c.name === colName));
  }
  return table?.columns.find((c) => c.name === colName)?.type || '';
}

function renderCondition(cond, ctx) {
  const { dialect, quote, style } = ctx;
  const left = columnRef(cond.column, dialect, quote);
  const op = cond.op || '=';
  const flags = literalFlags(ctx.typeOf?.(cond.column));
  const v = valueOf(cond.value, cond.column, style, flags);
  let expr;
  switch (op) {
    case 'IS NULL': expr = `${left} IS NULL`; break;
    case 'IS NOT NULL': expr = `${left} IS NOT NULL`; break;
    case 'IN': expr = `${left} IN (${v})`; break;
    case 'BETWEEN': {
      const v2 = valueOf(cond.value2, `${cond.column}_to`, style, flags);
      expr = `${left} BETWEEN ${v} AND ${v2}`;
      break;
    }
    default: expr = `${left} ${op} ${v}`;
  }
  return expr;
}

export function buildWhere(conditions, ctx) {
  const active = (conditions || []).filter((c) => c && c.column);
  if (!active.length) return [];
  const lines = ['WHERE'];
  active.forEach((c, i) => {
    const prefix = i === 0 ? '' : (c.conjunction === 'OR' ? 'OR ' : 'AND ');
    let text = `    ${prefix}${renderCondition(c, ctx)}`;
    if (ctx.addComments && c.comment) text += ` -- ${c.comment}`;
    lines.push(text);
  });
  return lines;
}

const stats = (sql, extra = {}) => ({
  lines: sql ? sql.split('\n').length : 0,
  chars: sql.length,
  ...extra,
});

/* ---------------- 複数テーブル結合 SELECT ---------------- */
export function buildSelectJoin(metadata, cfg = {}) {
  const dialect = cfg.dialect || metadata?.dialect || 'postgres';
  const info = dialectInfo(dialect);
  const plan = autoJoinPlan(metadata, cfg.tableIds || []);
  const ctx = {
    dialect,
    quote: Boolean(cfg.quoteIdentifiers),
    includeSchema: cfg.includeSchema !== false,
    addComments: Boolean(cfg.addComments),
    style: cfg.placeholderStyle || DEFAULT_PLACEHOLDER_STYLE,
    typeOf: (ref) => columnTypeOf(metadata, plan, cfg, ref),
  };
  const warnings = [];
  if (!plan.tables.length) {
    warnings.push('テーブルが 1 つ以上選択されていません');
    return { sql: '', warnings, stats: stats(''), plan };
  }

  const items = [];
  for (const t of plan.tables) {
    const alias = plan.aliases[t.id];
    // 列数無制限でも線形時間で済むよう列名を索引化する
    const byName = new Map(t.columns.map((c) => [c.name, c]));
    const picked = cfg.select?.[t.id] ?? t.columns.map((c) => c.name);
    for (const name of picked) {
      const col = byName.get(name);
      if (!col) continue;
      let expr = `    ${columnRef(`${alias}.${name}`, dialect, ctx.quote)}`;
      if (cfg.columnAlias && plan.tables.length > 1) {
        expr += ` AS ${ident(`${alias}_${name}`, dialect, ctx.quote)}`;
      }
      items.push({ expr, comment: ctx.addComments ? col.comment : '' });
    }
  }
  if (!items.length) items.push({ expr: '    *', comment: '' });

  const lines = [cfg.distinct ? 'SELECT DISTINCT' : 'SELECT'];
  items.forEach((it, i) => {
    lines.push(it.expr + (i < items.length - 1 ? ',' : '') + (it.comment ? ` -- ${it.comment}` : ''));
  });
  lines.push('FROM');

  plan.joins.forEach((j, idx) => {
    const t = findTable(metadata, j.tableId);
    if (!t) return;
    const ref = qualifiedTable(t, dialect, ctx);
    const as = info.tableAliasAs ? 'AS ' : '';
    if (idx === 0) {
      let line = `    ${ref} ${as}${j.alias}`;
      if (ctx.addComments && t.comment) line += ` -- ${t.comment}`;
      lines.push(line);
      return;
    }
    const joinType = cfg.joinTypes?.[idx] || cfg.joinTypes?.[j.tableId]
      || cfg.joinTypes?.[j.alias] || j.joinType || 'INNER';
    let line = `${joinType} JOIN ${ref} ${as}${j.alias}`;
    if (ctx.addComments && t.comment) line += ` -- ${t.comment}`;
    lines.push(line);
    const override = cfg.onOverrides?.[idx] || cfg.onOverrides?.[j.tableId] || cfg.onOverrides?.[j.alias];
    const ons = (Array.isArray(override) ? override : j.on || []).filter((o) => o.left && o.right);
    if (ons.length) {
      const expr = ons
        .map((o) => `${columnRef(o.left, dialect, ctx.quote)} = ${columnRef(o.right, dialect, ctx.quote)}`)
        .join(' AND ');
      lines.push(`    ON ${expr}`);
    } else {
      lines.push('    ON 1 = 1');
      warnings.push(`${t.id} の結合条件を自動推定できませんでした（ON 1 = 1）。結合条件を手動で指定してください。`);
    }
  });

  lines.push(...buildWhere(cfg.where, ctx));
  if (cfg.orderBy && String(cfg.orderBy).trim()) {
    lines.push(`ORDER BY ${String(cfg.orderBy).trim()}`);
  }
  lines.push(';');
  const sql = lines.join('\n');
  return {
    sql,
    warnings,
    plan,
    stats: stats(sql, { tables: plan.tables.length, joins: Math.max(0, plan.joins.length - 1), columns: items.length }),
  };
}

/* ---------------- UPDATE / DELETE 共通 ---------------- */
function conditionWarnings(table, where, kind, warnings) {
  const cols = (where || []).filter((c) => c?.column).map((c) => String(c.column).split('.').pop());
  if (!cols.length) {
    warnings.push(kind === 'update'
      ? 'WHERE 条件がありません。全件更新になります。'
      : 'WHERE 条件がありません。全件削除になります。');
  } else if (table.primaryKey?.length && !table.primaryKey.some((pk) => cols.includes(pk))) {
    warnings.push(`WHERE に主キー（${table.primaryKey.join(', ')}）が含まれていません。`);
  }
}

function ctxOf(metadata, cfg) {
  return {
    dialect: cfg.dialect || metadata?.dialect || 'postgres',
    quote: Boolean(cfg.quoteIdentifiers),
    includeSchema: cfg.includeSchema !== false,
    addComments: Boolean(cfg.addComments),
    style: cfg.placeholderStyle || DEFAULT_PLACEHOLDER_STYLE,
    typeOf: (ref) => columnTypeOf(metadata, null, cfg, ref),
  };
}

/* ---------------- UPDATE ---------------- */
export function buildUpdate(metadata, cfg = {}) {
  const ctx = ctxOf(metadata, cfg);
  const table = findTable(metadata, cfg.tableId);
  const warnings = [];
  if (!table) {
    warnings.push('対象テーブルが選択されていません');
    return { sql: '', warnings, stats: stats('') };
  }
  const byName = new Map(table.columns.map((c) => [c.name, c]));
  // 指定がない場合は「表の全列」を既定とする。空配列は全解除の明示指定として尊重する。
  const requested = cfg.setColumns == null ? table.columns.map((c) => c.name) : cfg.setColumns;
  const names = Array.isArray(requested) ? requested : [];
  // 列数無制限でも O(n^2) にならないよう、重複排除と存在確認は Set / Map で行う
  const setNames = [...new Set(names)].filter((n) => byName.has(n));
  if (!setNames.length) warnings.push('更新する列が選択されていません。');
  const isFullSet = setNames.length === byName.size;
  if (!isFullSet && table.primaryKey?.some((pk) => setNames.includes(pk))) {
    warnings.push(`主キー（${table.primaryKey.join(', ')}）を更新対象に含んでいます。`);
  }
  const setItems = setNames.map((name) => {
    const col = byName.get(name);
    const value = valueOf(cfg.setValues?.[name], name, ctx.style, literalFlags(col?.type));
    return {
      expr: `    ${ident(name, ctx.dialect, ctx.quote)} = ${value}`,
      comment: ctx.addComments ? col?.comment || '' : '',
    };
  });
  conditionWarnings(table, cfg.where, 'update', warnings);

  const lines = [`UPDATE ${qualifiedTable(table, ctx.dialect, ctx)}`, 'SET'];
  setItems.forEach((it, i) => {
    lines.push(it.expr + (i < setItems.length - 1 ? ',' : '') + (it.comment ? ` -- ${it.comment}` : ''));
  });
  lines.push(...buildWhere(cfg.where, ctx));
  lines.push(';');
  const sql = lines.join('\n');
  return { sql, warnings, stats: stats(sql, { tables: 1, sets: setItems.length, conditions: (cfg.where || []).filter((c) => c?.column).length }) };
}

/* ---------------- DELETE ---------------- */
export function buildDelete(metadata, cfg = {}) {
  const ctx = ctxOf(metadata, cfg);
  const table = findTable(metadata, cfg.tableId);
  const warnings = [];
  if (!table) {
    warnings.push('対象テーブルが選択されていません');
    return { sql: '', warnings, stats: stats('') };
  }
  conditionWarnings(table, cfg.where, 'delete', warnings);
  const lines = [`DELETE FROM ${qualifiedTable(table, ctx.dialect, ctx)}`];
  lines.push(...buildWhere(cfg.where, ctx));
  lines.push(';');
  const sql = lines.join('\n');
  return { sql, warnings, stats: stats(sql, { tables: 1, conditions: (cfg.where || []).filter((c) => c?.column).length }) };
}

export function buildSql(metadata, kind, cfg) {
  if (kind === 'update') return buildUpdate(metadata, cfg);
  if (kind === 'delete') return buildDelete(metadata, cfg);
  return buildSelectJoin(metadata, cfg);
}

/** WHERE 条件の新規 1 行 */
export function newCondition(column = '', op = '=') {
  return { column, op, value: '', value2: '', conjunction: 'AND', comment: '' };
}

/** テーブル一覧を id で参照しやすい形にする（UI 用） */
export function tableOptions(metadata) {
  return (metadata?.tables || []).map((t) => ({
    id: t.id,
    label: t.id,
    comment: t.comment,
    columnCount: t.columns.length,
    key: makeTableId(t.schema, t.name),
  }));
}

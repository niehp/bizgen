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
  DEFAULT_PLACEHOLDER_STYLE, isNumericType, isDateType, isCharacterType,
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
/**
 * 列参照（alias.col / col）からメタデータ上の「列コメント」を引く。
 * columnTypeOf と同じ解決順で、UPDATE / DELETE の SQL に DB 列コメントを表示するために使う。
 */
function resolveColumn(metadata, plan, cfg, ref) {
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
  return { table, column: table?.columns.find((c) => c.name === colName) || null };
}

export function columnCommentOf(metadata, plan, cfg, ref) {
  return resolveColumn(metadata, plan, cfg, ref).column?.comment || '';
}

export function columnTypeOf(metadata, plan, cfg, ref) {
  return resolveColumn(metadata, plan, cfg, ref).column?.type || '';
}

/**
 * 列の並び順を決めるキー。表の順番 × 列の ordinal で返す。
 * 「字段的顺序按照表的顺序」を満たすため、SELECT / WHERE / SET すべてで使う。
 */
export function makeOrdinalOf(metadata, plan, cfg) {
  return (ref) => {
    const { table, column } = resolveColumn(metadata, plan, cfg, ref);
    const tableIndex = table && plan?.tables?.length
      ? plan.tables.findIndex((t) => t.id === table.id)
      : 0;
    return (tableIndex < 0 ? 0 : tableIndex) * 100000 + (column?.ordinal || 0);
  };
}

function renderConditionExpr(cond, ctx) {
  const { dialect, quote, style } = ctx;
  const left = columnRef(cond.column, dialect, quote);
  const op = cond.op || '=';
  const flags = literalFlags(ctx.typeOf?.(cond.column));
  const v = valueOf(cond.value, cond.column, style, flags);
  switch (op) {
    case 'IS NULL': return `${left} IS NULL`;
    case 'IS NOT NULL': return `${left} IS NOT NULL`;
    case 'IN': return `${left} IN (${v})`;
    case 'BETWEEN': {
      const v2 = valueOf(cond.value2, `${cond.column}_to`, style, flags);
      return `${left} BETWEEN ${v} AND ${v2}`;
    }
    default: return `${left} ${op} ${v}`;
  }
}

/** IF 文の変数名。個別指定が無ければ列名の camelCase を使う */
export function conditionVariable(cond) {
  const raw = String(cond?.variable ?? '').trim();
  return camelCase(raw || cond?.column || 'param');
}

// 条件 1 行（IF 付きは 3 行）を出力する。
// IF の書式: スラッシュ2つ + アスタリスク + IF ... + アスタリスク + スラッシュ2つ で囲む。
// AND / OR は IF ブロックの中に置き、IF が偽のとき条件自体が消えるようにする。
function renderConditionLines(cond, ctx, isFirst) {
  const lines = [];
  const wrap = Boolean(cond.if);
  if (wrap) {
    const variable = conditionVariable(cond);
    // 文字型は空文字も弾く。数値 / 日付 / boolean などは null チェックだけ。
    const check = isCharacterType(ctx.typeOf?.(cond.column))
      ? `${variable} != "" && ${variable} != null`
      : `${variable} != null`;
    lines.push(`/*IF ${check}*/`);
  }
  const prefix = isFirst ? '' : (cond.conjunction === 'OR' ? 'OR ' : 'AND ');
  let text = `    ${prefix}${renderConditionExpr(cond, ctx)}`;
  // UPDATE / DELETE では DB の列コメントを既定で表示する。
  // 個別条件のコメント（c.comment）があればそれを優先し、無ければメタデータの列コメントを使う。
  const note = c_note(cond, ctx);
  if (note) text += ` -- ${note}`;
  lines.push(text);
  if (wrap) lines.push('/*END*/');
  return lines;
}

function c_note(cond, ctx) {
  return cond.comment || (ctx.showColumnComments ? ctx.commentOf?.(cond.column) : '') || '';
}

export function buildWhere(conditions, ctx) {
  const active = (conditions || []).filter((c) => c && c.column);
  if (!active.length) return [];
  // フィールド順はテーブル定義順に揃える（ユーザーが行を入れ替えても出力順は一定）
  if (ctx.ordinalOf) {
    active.sort((a, b) => ctx.ordinalOf(a.column) - ctx.ordinalOf(b.column));
  }
  // IF が複数ある場合、先頭の IF が偽でも残りの条件が正しく効くよう WHERE 1 = 1 を基準にする。
  const useAnchor = active.length > 1 && active.some((c) => c.if);
  const lines = [useAnchor ? 'WHERE 1 = 1' : 'WHERE'];
  // 直前が IF ブロックだけなら、その条件が消える可能性があるため AND / OR を付けない。
  let hasCertainCondition = false;
  active.forEach((c) => {
    const first = useAnchor ? false : !hasCertainCondition;
    lines.push(...renderConditionLines(c, ctx, first));
    if (!c.if) hasCertainCondition = true;
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
    ordinalOf: makeOrdinalOf(metadata, plan, cfg),
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
    // 出力列はテーブル定義の列順に揃える（選択した順序には依存しない）
    const ordered = [...picked].sort((a, b) => (byName.get(a)?.ordinal || 0) - (byName.get(b)?.ordinal || 0));
    for (const name of ordered) {
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
    const ons = (Array.isArray(override) ? override : j.on || []).filter(
      (o) => o.left && (o.rightMode === 'value' || o.right),
    );
    if (ons.length) {
      // ON / AND はそれぞれ 1 行ずつ出力する。右辺は列参照のほか入力値も選べる。
      ons.forEach((o, oi) => {
        const left = columnRef(o.left, dialect, ctx.quote);
        const right = o.rightMode === 'value' || !o.right
          ? valueOf(o.rightValue ?? o.value ?? '', o.left, ctx.style, literalFlags(ctx.typeOf?.(o.left)))
          : columnRef(o.right, dialect, ctx.quote);
        lines.push(`    ${oi === 0 ? 'ON ' : 'AND '}${left} = ${right}`);
      });
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
  // UPDATE / DELETE は WHERE / SET に DB 列コメントを表示するのが既定。
  // 「列コメントを付与」を明示的に OFF（false）にした場合のみ抑制する。
  const showColumnComments = cfg.showColumnComments !== false;
  return {
    dialect: cfg.dialect || metadata?.dialect || 'postgres',
    quote: Boolean(cfg.quoteIdentifiers),
    includeSchema: cfg.includeSchema !== false,
    addComments: Boolean(cfg.addComments),
    showColumnComments,
    style: cfg.placeholderStyle || DEFAULT_PLACEHOLDER_STYLE,
    typeOf: (ref) => columnTypeOf(metadata, null, cfg, ref),
    commentOf: (ref) => columnCommentOf(metadata, null, cfg, ref),
    ordinalOf: makeOrdinalOf(metadata, null, cfg),
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
  // 列数無制限でも O(n^2) にならないよう、重複排除と存在確認は Set / Map で行う。
  // SET 行は表の列順（ordinal）で出力する。
  const setNames = [...new Set(names)]
    .filter((n) => byName.has(n))
    .sort((a, b) => (byName.get(a).ordinal || 0) - (byName.get(b).ordinal || 0));
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
      // UPDATE の SET 行にも DB 列コメントを既定で表示する
      comment: ctx.showColumnComments ? col?.comment || '' : '',
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
  return { column, op, value: '', value2: '', conjunction: 'AND', comment: '', if: false, variable: '' };
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

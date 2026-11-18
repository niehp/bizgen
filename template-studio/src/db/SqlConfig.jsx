import React, { useEffect, useMemo, useState } from 'react';
import {
  PLACEHOLDER_STYLES, CONDITION_OPS, JOIN_TYPES, APP_DB_DIALECT, isNumericType, isDateType,
} from '../../shared/dbmeta.js';
import { placeholder, newCondition, camelCase, literalFlags } from '../../shared/sqlbuilder.js';

// 列数は無制限。ドロップダウンは検索で全列にアクセスできるよう、描画だけ間引く。
const OPTION_LIMIT = 200;
// SET 値の入力欄も列数無制限のため、先頭から段階的に描画する（全件を一度に DOM に出さない）
const SET_PAGE_SIZE = 100;

function optionSubset(options, query, selected) {
  const q = String(query || '').trim().toLowerCase();
  const matched = q
    ? options.filter((o) => `${o.label} ${o.comment || ''}`.toLowerCase().includes(q))
    : options;
  const keep = new Set((selected || []).filter(Boolean));
  const pinned = options.filter((o) => keep.has(o.value));
  const pinnedSet = new Set(pinned.map((o) => o.value));
  const rest = matched.filter((o) => !pinnedSet.has(o.value));
  return {
    list: [...pinned, ...rest].slice(0, OPTION_LIMIT),
    matched: matched.length,
    total: options.length,
  };
}

function ConditionEditor({ conditions, onChange, columnOptions, style, typeOf }) {
  const patch = (i, key, value) => onChange(conditions.map((c, n) => (n === i ? { ...c, [key]: value } : c)));
  const needsValue = (op) => (CONDITION_OPS.find((o) => o.id === op) || {}).needsValue !== false;
  const flagsOf = (ref) => literalFlags(typeOf(ref));

  return (
    <div className="db-cond-box">
      <div className="db-section-head"><span>WHERE 条件</span><span className="db-section-sub">{"空欄＝列型別 /*camelCase*/'' / 99 / '2026-12-01'"}</span></div>
      <div className="db-filter-row">
        <span className="db-section-sub">条件列の一括設定</span>
        <button
          type="button" className="link-btn"
          disabled={!columnOptions.length}
          onClick={() => onChange(columnOptions.map((o) => newCondition(o.value, '=')))}
        >条件を全選択</button>
        <button
          type="button" className="link-btn"
          disabled={!conditions.length}
          onClick={() => onChange([])}
        >条件を全解除</button>
        <span className="db-filter-count">{conditions.length} / {columnOptions.length} 条件</span>
      </div>
      {conditions.length === 0 && <div className="db-empty">条件列を選ぶか「条件を追加」してください</div>}
      {conditions.map((c, i) => (
        <div className="cond-row" key={`${c.column}-${i}`}>
          {i > 0 ? (
            <select
              className="control cond-conj"
              value={c.conjunction || 'AND'}
              onChange={(e) => patch(i, 'conjunction', e.target.value)}
            >
              <option value="AND">AND</option>
              <option value="OR">OR</option>
            </select>
          ) : <span className="cond-conj cond-conj-blank" />}
          <select
            className="control cond-col"
            value={c.column}
            onChange={(e) => patch(i, 'column', e.target.value)}
          >
            <option value="">（列を選択）</option>
            {columnOptions.map((o) => (
              <option key={o.value} value={o.value}>{o.label}{o.comment ? ` — ${o.comment}` : ''}</option>
            ))}
          </select>
          <select
            className="control cond-op"
            value={c.op}
            onChange={(e) => patch(i, 'op', e.target.value)}
          >
            {CONDITION_OPS.map((o) => <option key={o.id} value={o.id}>{o.label}</option>)}
          </select>
          {needsValue(c.op) && (
            <input
              className="control cond-val"
              value={c.value ?? ''}
              placeholder={placeholder(c.column, style, flagsOf(c.column))}
              onChange={(e) => patch(i, 'value', e.target.value)}
            />
          )}
          {c.op === 'BETWEEN' && (
            <input
              className="control cond-val"
              value={c.value2 ?? ''}
              placeholder={placeholder(`${c.column}_to`, style, flagsOf(c.column))}
              onChange={(e) => patch(i, 'value2', e.target.value)}
            />
          )}
          <button
            type="button" className="tbl-del" title="条件を削除"
            onClick={() => onChange(conditions.filter((_, n) => n !== i))}
          >×</button>
        </div>
      ))}
      <button
        type="button" className="link-btn"
        onClick={() => onChange([...conditions, newCondition(columnOptions[0]?.value || '', '=')])}
      >＋ 条件を追加</button>
    </div>
  );
}

function JoinEditor({ plan, joinCfg, onJoinType, onOnPairs, onResetOn, onRemoveJoinTable, allColumnOptions }) {
  if (!plan || plan.joins.length < 2) {
    return <div className="db-empty">結合するテーブルを 2 つ以上選択してください</div>;
  }
  return (
    <div className="db-join-box">
      <div className="db-section-head"><span>結合条件（自動推定・編集可）</span><span className="db-section-sub">FK → 同名キー → 同名列 の順で推定</span></div>
      {plan.joins.slice(1).map((j, k) => {
        const idx = k + 1;
        const override = joinCfg.onOverrides[idx];
        const pairs = Array.isArray(override) ? override : j.on;
        const joinType = joinCfg.joinTypes[idx] || j.joinType || 'INNER';
        const rightOptions = allColumnOptions.filter((o) => o.value.startsWith(`${j.alias}.`));
        const patchPair = (n, key, value) => onOnPairs(idx, pairs.map((p, m) => (m === n ? { ...p, [key]: value } : p)));
        return (
          <div className="join-block" key={j.alias}>
            <div className="join-head">
              <span className="tag">{j.alias} · {j.tableId}</span>
              <select
                className="control cond-op"
                value={joinType}
                onChange={(e) => onJoinType(idx, e.target.value)}
              >
                {JOIN_TYPES.map((t) => <option key={t} value={t}>{t} JOIN</option>)}
              </select>
              <span className="db-section-sub">親 {j.parentAlias}</span>
              {override && <button type="button" className="link-btn" onClick={() => onResetOn(idx)}>自動推定に戻す</button>}
              {onRemoveJoinTable && (
                <button
                  type="button" className="link-btn link-btn-danger"
                  title="このテーブルを結合から外す"
                  onClick={() => onRemoveJoinTable(j.tableId)}
                >この表を削除</button>
              )}
            </div>
            {pairs.length === 0 && <div className="db-empty db-warn-text">結合条件が未設定です（ON 1 = 1）</div>}
            {pairs.map((p, n) => (
              <div className="cond-row" key={n}>
                <span className="cond-conj cond-on">ON</span>
                <select className="control cond-col" value={p.left} onChange={(e) => patchPair(n, 'left', e.target.value)}>
                  <option value="">（左列）</option>
                  {allColumnOptions.map((o) => <option key={o.value} value={o.value}>{o.value}</option>)}
                </select>
                <span className="cond-eq">=</span>
                <select className="control cond-col" value={p.right} onChange={(e) => patchPair(n, 'right', e.target.value)}>
                  <option value="">（右列）</option>
                  {rightOptions.map((o) => <option key={o.value} value={o.value}>{o.value}</option>)}
                </select>
                <button
                  type="button" className="tbl-del" title="条件を削除"
                  onClick={() => onOnPairs(idx, pairs.filter((_, m) => m !== n))}
                >×</button>
              </div>
            ))}
            <button
              type="button" className="link-btn"
              onClick={() => onOnPairs(idx, [...pairs, {
                left: allColumnOptions[0]?.value || '',
                right: rightOptions[0]?.value || '',
              }])}
            >＋ ON 条件を追加</button>
          </div>
        );
      })}
    </div>
  );
}

function SetValueEditor({ updateCfg, onChange, style, typeOf }) {
  const [query, setQuery] = useState('');
  const [limit, setLimit] = useState(SET_PAGE_SIZE);
  const columns = updateCfg.setColumns || [];
  const q = String(query).trim().toLowerCase();
  const matched = q ? columns.filter((c) => c.toLowerCase().includes(q)) : columns;
  const shown = matched.slice(0, limit);

  // 対象テーブル / 検索語が変わったら先頭から描画し直す。入力済みの値は保持する。
  useEffect(() => { setLimit(SET_PAGE_SIZE); }, [updateCfg.tableId, query]);

  if (!columns.length) {
    return (
      <div className="db-cond-box">
        <div className="db-section-head"><span>SET の値 / 式</span></div>
        <div className="db-empty">SET 列は全て解除されています。列一覧でチェックを入れるか「全列を選択」を使ってください</div>
      </div>
    );
  }
  return (
    <div className="db-cond-box">
      <div className="db-section-head"><span>SET の値 / 式</span><span className="db-section-sub">{"空欄＝列型別 /*camelCase*/'' / 99 / '2026-12-01'"}</span></div>
      <div className="db-filter-row">
        <input
          className="control db-filter-input"
          value={query}
          placeholder="SET 列名で絞り込み（表示のみ・値は全列に保持）"
          aria-label="SET 列の絞り込み"
          onChange={(e) => setQuery(e.target.value)}
        />
        {query && <button type="button" className="link-btn" onClick={() => setQuery('')}>クリア</button>}
        <span className="db-filter-count">{matched.length} / {columns.length} 列</span>
      </div>
      {shown.map((col) => (
        <label className="set-row" key={col}>
          <code>{col}</code>
          <input
            className="control"
            value={updateCfg.setValues[col] ?? ''}
            placeholder={placeholder(col, style, literalFlags(typeOf(col)))}
            onChange={(e) => onChange({ ...updateCfg.setValues, [col]: e.target.value })}
          />
        </label>
      ))}
      {shown.length < matched.length && (
        <div className="db-list-more">
          <span>{shown.length} / {matched.length} 件を表示（SET は全 {columns.length} 列が生成対象・上限なし）</span>
          <button type="button" className="link-btn" onClick={() => setLimit((n) => n + SET_PAGE_SIZE)}>
            さらに {SET_PAGE_SIZE} 件を表示
          </button>
        </div>
      )}
      <div className="db-tip">例: <code>CURRENT_TIMESTAMP</code> / <code>{'#{updatedAt}'}</code> / <code>'0'</code>（空欄は列型別の <code>{'/*columnName*/\'\''}</code> / <code>99</code> / <code>'2026-12-01'</code>）</div>
    </div>
  );
}

export default function SqlConfig({
  metadata, sqlKind, options, onOptions,
  plan, joinCfg, onJoinType, onOnPairs, onResetOn, onJoinPatch, onSetOrderBy,
  onRemoveJoinTable,
  updateCfg, onSetValues,
  conditions, onConditions, columnOptions, allColumnOptions,
}) {
  const [columnQuery, setColumnQuery] = useState('');
  const sampleColumn = columnOptions[0]?.value || 'customer_id';
  const sampleNumericColumn = columnOptions.find((o) => isNumericType(o.type))?.value || 'amount';
  const sampleDateColumn = columnOptions.find((o) => isDateType(o.type))?.value || 'order_date';
  // 2 万列超でも O(1) で型を引けるよう索引化する
  const typeByRef = useMemo(
    () => new Map(columnOptions.map((o) => [o.value, o.type])),
    [columnOptions],
  );
  const typeOf = (ref) => typeByRef.get(ref) || '';
  const selectedRefs = useMemo(() => {
    const refs = (conditions || []).map((c) => c.column);
    (plan?.joins || []).forEach((j, idx) => {
      const pairs = joinCfg.onOverrides?.[idx] || j.on || [];
      for (const p of pairs) refs.push(p.left, p.right);
    });
    return refs;
  }, [conditions, plan, joinCfg.onOverrides]);
  const scoped = useMemo(
    () => optionSubset(columnOptions, columnQuery, selectedRefs),
    [columnOptions, columnQuery, selectedRefs],
  );
  const allScoped = useMemo(
    () => optionSubset(allColumnOptions, columnQuery, selectedRefs),
    [allColumnOptions, columnQuery, selectedRefs],
  );
  const manyColumns = Math.max(scoped.total, allScoped.total) > OPTION_LIMIT;
  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>③ SQL 生成設定</h2>
          <p className="panel-sub">列の選択は左のパネル、演算子・値・結合条件はここで指定します</p>
        </div>
        <span className="badge badge-blue">{sqlKind === 'join' ? 'SELECT 結合' : sqlKind === 'update' ? 'UPDATE' : 'DELETE'}</span>
      </div>

      <div className="db-config-scroll">
        <div className="db-options">
          <div className="db-field">
            <span>出力方言</span>
            <select className="control" value={APP_DB_DIALECT} disabled title="PostgreSQL 固定">
              <option value={APP_DB_DIALECT}>PostgreSQL（固定）</option>
            </select>
          </div>
          <label className="db-field">
            <span>プレースホルダ</span>
            <select
              className="control"
              value={options.placeholderStyle}
              onChange={(e) => onOptions({ placeholderStyle: e.target.value })}
            >
              {PLACEHOLDER_STYLES.map((s) => <option key={s.id} value={s.id}>{s.label}</option>)}
            </select>
          </label>
          <div className="db-switches">
            <label className="switch"><input type="checkbox" checked={options.quoteIdentifiers} onChange={(e) => onOptions({ quoteIdentifiers: e.target.checked })} /><span>識別子を引用</span></label>
            <label className="switch"><input type="checkbox" checked={options.includeSchema} onChange={(e) => onOptions({ includeSchema: e.target.checked })} /><span>スキーマ名を付与</span></label>
            <label className="switch"><input type="checkbox" checked={options.addComments} onChange={(e) => onOptions({ addComments: e.target.checked })} /><span>列コメントを付与</span></label>
            {sqlKind === 'join' && (
              <>
                <label className="switch"><input type="checkbox" checked={options.columnAlias} onChange={(e) => onOptions({ columnAlias: e.target.checked })} /><span>列に別名を付与</span></label>
                <label className="switch"><input type="checkbox" checked={Boolean(joinCfg.distinct)} onChange={(e) => onJoinPatch({ distinct: e.target.checked })} /><span>DISTINCT</span></label>
              </>
            )}
          </div>
          {sqlKind === 'join' && (
            <label className="db-field">
              <span>ORDER BY（任意）</span>
              <input
                className="control"
                value={joinCfg.orderBy || ''}
                placeholder="例: t1.org_id ASC"
                onChange={(e) => onSetOrderBy(e.target.value)}
              />
            </label>
          )}
          {manyColumns && (
            <label className="db-field db-column-search">
              <span>列ドロップダウンの絞り込み（{Math.max(scoped.total, allScoped.total)} 列・上限なし）</span>
              <input
                className="control"
                value={columnQuery}
                placeholder="列名 / 列コメントで検索"
                onChange={(e) => setColumnQuery(e.target.value)}
              />
              <span className="db-section-sub">該当 {scoped.matched} 件 / 先頭 {scoped.list.length} 件を表示</span>
            </label>
          )}
        </div>

        <div className="db-tip db-ph-hint">
          条件値 / SET 値は旧形式の名前付きパラメータではなく、列型別の埋め込みコメントを出力します。
          <code>{placeholder(sampleColumn, options.placeholderStyle)}</code>（文字列型）／
          <code>{placeholder(sampleNumericColumn, options.placeholderStyle, { numeric: true })}</code>（数値型）／
          <code>{placeholder(sampleDateColumn, options.placeholderStyle, { date: true })}</code>（日付・日時型）。
          バインド名 <code>{camelCase(sampleColumn)}</code> は列名の camelCase です。
          リテラルはメタデータの列型で自動判定し、空欄はこの形式、値や式を入力した場合はそのまま出力します。
        </div>

        {sqlKind === 'join' && (
          <JoinEditor
            plan={plan}
            joinCfg={joinCfg}
            onJoinType={onJoinType}
            onOnPairs={onOnPairs}
            onResetOn={onResetOn}
            onRemoveJoinTable={onRemoveJoinTable}
            allColumnOptions={allScoped.list}
          />
        )}

        {sqlKind === 'update' && (
          <SetValueEditor updateCfg={updateCfg} onChange={onSetValues} style={options.placeholderStyle} typeOf={typeOf} />
        )}

        <ConditionEditor
          conditions={conditions}
          onChange={onConditions}
          columnOptions={scoped.list}
          style={options.placeholderStyle}
          typeOf={typeOf}
        />

        {!metadata && <div className="db-empty">メタデータを読み込むと設定できます</div>}
        <div className="db-tip">
          UPDATE / DELETE の SET・WHERE 行には DB の列コメント（-- ...）を既定で表示します。
          条件値の埋め込みコメントは指定書式のバインド名です。
          UPDATE の SET は表の全列が既定で、不要な列は左の列一覧で解除できます。
          条件列・SET 列とも「全選択 / 全解除」で一括設定できます。
          結合条件パネルの「この表を削除」で結合からテーブルを外せます。
          SQL は右側に選択中の 1 種類だけを全件表示し、ダウンロード（.sql 自動付与）に対応します。
        </div>
      </div>
    </section>
  );
}

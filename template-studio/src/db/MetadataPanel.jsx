import React, { useEffect, useState } from 'react';
import { findTable, dialectInfo, filterTables, filterColumns } from '../../shared/dbmeta.js';

// 列数・テーブル数は無制限。DOM だけを分割して描画し、スクロールで全件に到達できるようにする。
const PAGE_SIZE = 300;

const MODE_HINT = {
  join: '複数テーブルにチェック → 結合 SELECT。列の SELECT チェックで出力列を選びます。',
  update: '対象テーブルを 1 つ選択 → SET は全列が既定で選択済み。不要な列だけ解除し、条件列にチェックします。',
  delete: '対象テーブルを 1 つ選択 → 条件列にチェックします。',
};

export default function MetadataPanel({
  metadata, activeTableId, onActiveTable, sqlKind,
  joinTableIds, onToggleJoinTable, targetTableId, onSelectTarget,
  isSelectChecked, onToggleSelectColumn,
  isSetChecked, onToggleSetColumn, onSetAllColumns,
  isWhereChecked, onToggleWhereColumn,
  plan,
}) {
  const [tableFilter, setTableFilter] = useState('');
  const [columnFilter, setColumnFilter] = useState('');
  const [tableLimit, setTableLimit] = useState(PAGE_SIZE);
  const [columnLimit, setColumnLimit] = useState(PAGE_SIZE);
  const tables = metadata?.tables || [];
  const active = findTable(metadata, activeTableId) || tables[0] || null;
  const visibleTables = filterTables(tables, tableFilter);
  const visibleColumns = filterColumns(active?.columns || [], columnFilter);
  const shownTables = visibleTables.slice(0, tableLimit);
  const shownColumns = visibleColumns.slice(0, columnLimit);

  // メタデータ / 絞り込み / 選択テーブルが変わったら先頭から表示し直す（選択状態は保持）
  useEffect(() => { setTableLimit(PAGE_SIZE); }, [metadata, tableFilter]);
  useEffect(() => { setColumnLimit(PAGE_SIZE); }, [active?.id, columnFilter]);

  const onListScroll = (setter, total) => (e) => {
    const el = e.currentTarget;
    if (el.scrollTop + el.clientHeight >= el.scrollHeight - 120) {
      setter((n) => Math.min(n + PAGE_SIZE, total));
    }
  };
  const moreHint = (shown, total, setter) => shown < total && (
    <div className="db-list-more">
      <span>{shown} / {total} 件を表示（スクロールで続きを読み込み · 上限なし）</span>
      <button type="button" className="link-btn" onClick={() => setter(total)}>全件を表示</button>
    </div>
  );

  const alias = active && plan?.aliases ? plan.aliases[active.id] : '';
  const isTarget = Boolean(active && active.id === targetTableId);
  const canWhere = sqlKind === 'join' ? Boolean(alias) : isTarget;
  const info = dialectInfo(metadata?.dialect);

  const whereRef = (col) => (sqlKind === 'join' && alias ? `${alias}.${col}` : col);

  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>② メタデータ（テーブル / 列）</h2>
          <p className="panel-sub">テーブルを選ぶと列とコメントを一覧表示します</p>
        </div>
        <span className="badge badge-neutral">{info.label}</span>
      </div>

      {!metadata ? (
        <div className="empty">
          <p>メタデータが読み込まれていません</p>
          <p className="empty-sub">「サンプルを読み込む」か DB に接続して抽出してください</p>
        </div>
      ) : (
        <>
          <div className="db-meta-summary">
            <span className="tag">{tables.length} テーブル</span>
            <span className="tag tag-soft">
              {tables.reduce((n, t) => n + t.columns.length, 0)} 列
            </span>
            <span className="tag tag-teal">テーブル数・列数 上限なし</span>
            <span className="db-meta-name">{metadata.name}</span>
          </div>
          <div className="db-mode-hint">{MODE_HINT[sqlKind]}</div>

          <div className="db-section">
            <div className="db-section-head">
              <span>テーブル一覧</span>
              <span className="db-section-sub">
                {sqlKind === 'join' ? `${joinTableIds.length} 件を結合` : '1 件を選択'}
              </span>
            </div>
            <div className="db-filter-row">
              <input
                className="control db-filter-input"
                value={tableFilter}
                placeholder="テーブル名 / スキーマ / 表コメントで絞り込み"
                aria-label="テーブル名の絞り込み"
                onChange={(e) => setTableFilter(e.target.value)}
              />
              {tableFilter && (
                <button type="button" className="link-btn" onClick={() => setTableFilter('')}>クリア</button>
              )}
              <span className="db-filter-count">{visibleTables.length} / {tables.length}</span>
            </div>
            <div className="db-list" onScroll={onListScroll(setTableLimit, visibleTables.length)}>
              {shownTables.map((t) => {
                const checked = sqlKind === 'join'
                  ? joinTableIds.includes(t.id)
                  : t.id === targetTableId;
                return (
                  <div
                    key={t.id}
                    className={`db-table-row ${active?.id === t.id ? 'is-active' : ''} ${checked ? 'is-checked' : ''}`}
                  >
                    <input
                      type={sqlKind === 'join' ? 'checkbox' : 'radio'}
                      name="db-target-table"
                      checked={checked}
                      onChange={() => (sqlKind === 'join' ? onToggleJoinTable(t.id) : onSelectTarget(t.id))}
                      aria-label={t.id}
                    />
                    <button type="button" className="db-table-main" onClick={() => onActiveTable(t.id)}>
                      <span className="db-table-name">{t.id}</span>
                      {t.comment && <span className="db-comment">{t.comment}</span>}
                    </button>
                    <div className="db-table-meta">
                      {t.primaryKey.length > 0 && <span className="chip chip-pk">PK {t.primaryKey.join(', ')}</span>}
                      <span className="chip chip-soft">{t.columns.length} 列</span>
                    </div>
                  </div>
                );
              })}
              {visibleTables.length === 0 && (
                <div className="db-empty">条件に一致するテーブルがありません</div>
              )}
            </div>
            {moreHint(shownTables.length, visibleTables.length, setTableLimit)}
          </div>

          <div className="db-section db-section-cols">
            <div className="db-section-head">
              <span>列一覧 {active ? `— ${active.id}` : ''}</span>
              {alias && <span className="db-section-sub">別名 {alias}</span>}
            </div>
            {active?.comment && <div className="db-table-comment">表コメント: {active.comment}</div>}
            <div className="db-filter-row">
              <input
                className="control db-filter-input"
                value={columnFilter}
                placeholder="列名 / 型 / 列コメントで絞り込み"
                aria-label="列名の絞り込み"
                onChange={(e) => setColumnFilter(e.target.value)}
              />
              {columnFilter && (
                <button type="button" className="link-btn" onClick={() => setColumnFilter('')}>クリア</button>
              )}
              <span className="db-filter-count">{visibleColumns.length} / {active?.columns.length || 0}</span>
              <span className="db-filter-note">表示のみ（列数無制限・選択状態は保持）</span>
            </div>
            {sqlKind === 'update' && (
              <div className="db-filter-row">
                <span className="db-section-sub">SET は表の全列（主キー・監査列を含む）が既定</span>
                <button
                  type="button" className="link-btn"
                  disabled={!isTarget}
                  onClick={() => onSetAllColumns(true)}
                >全列を選択</button>
                <button
                  type="button" className="link-btn"
                  disabled={!isTarget}
                  onClick={() => onSetAllColumns(false)}
                >全列を解除</button>
                <span className="db-filter-note">絞り込みは表示のみ・一括操作は表の全列に適用</span>
              </div>
            )}
            <div className="db-col-head">
              <span className="db-col-checks">
                {sqlKind === 'join' && <span>SELECT</span>}
                {sqlKind === 'update' && <span>SET</span>}
                <span>条件</span>
              </span>
              <span>列名 / 型 / コメント</span>
            </div>
            <div className="db-list db-col-list" onScroll={onListScroll(setColumnLimit, visibleColumns.length)}>
              {shownColumns.map((c) => {
                const wr = whereRef(c.name);
                return (
                  <div key={c.name} className="db-col-row">
                    <span className="db-col-checks">
                      {sqlKind === 'join' && (
                        <input
                          type="checkbox"
                          checked={isSelectChecked(active.id, c.name)}
                          onChange={() => onToggleSelectColumn(active.id, c.name)}
                          aria-label={`SELECT ${c.name}`}
                          disabled={!joinTableIds.includes(active.id)}
                        />
                      )}
                      {sqlKind === 'update' && (
                        <input
                          type="checkbox"
                          checked={isSetChecked(c.name)}
                          onChange={() => onToggleSetColumn(c.name)}
                          aria-label={`SET ${c.name}`}
                          disabled={!isTarget}
                        />
                      )}
                      <input
                        type="checkbox"
                        checked={isWhereChecked(wr)}
                        onChange={() => onToggleWhereColumn(sqlKind, wr)}
                        aria-label={`WHERE ${c.name}`}
                        disabled={!canWhere}
                      />
                    </span>
                    <span className="db-col-main">
                      <span className="db-col-line">
                        <code>{c.name}</code>
                        <span className="db-col-type">{c.type || '—'}</span>
                        {c.primaryKey && <span className="chip chip-pk">PK</span>}
                        {!c.nullable && <span className="chip chip-soft">NOT NULL</span>}
                      </span>
                      {c.comment && <span className="db-comment">{c.comment}</span>}
                    </span>
                  </div>
                );
              })}
              {!active && <div className="db-empty">テーブルを選択してください</div>}
              {active && visibleColumns.length === 0 && (
                <div className="db-empty">条件に一致する列がありません</div>
              )}
            </div>
            {moreHint(shownColumns.length, visibleColumns.length, setColumnLimit)}
          </div>
        </>
      )}
    </section>
  );
}

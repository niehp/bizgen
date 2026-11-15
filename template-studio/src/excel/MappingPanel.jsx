import React, { useMemo, useState } from 'react';
import { filterTables } from '../../shared/dbmeta.js';
import { mappingFileId } from '../../shared/mapping.js';

// 列数は無制限。DOM に出す選択肢・行だけを間引き、検索で全件に到達できるようにする。
const OPTION_LIMIT = 200;
const COLUMN_PAGE_SIZE = 100;
const TABLE_PAGE_SIZE = 50;

function optionSubset(options, query, selected) {
  const q = String(query || '').trim().toLowerCase();
  const matched = q
    ? options.filter((o) => `${o.label} ${o.comment || ''}`.toLowerCase().includes(q))
    : options;
  const keep = new Set((selected || []).filter((v) => v !== undefined && v !== null).map(String));
  const pinned = options.filter((o) => keep.has(String(o.value)));
  const pinnedSet = new Set(pinned.map((o) => o.value));
  const rest = matched.filter((o) => !pinnedSet.has(o.value));
  return {
    list: [...pinned, ...rest].slice(0, OPTION_LIMIT),
    matched: matched.length,
    total: options.length,
  };
}

const text = (v) => (v === undefined || v === null ? '' : String(v));

/**
 * 「表 → Excel」方向のマッピング編集。
 * 行は DB テーブルの列。各 DB 列に Excel 列を 1 つ選ぶ（同じ Excel 列を複数 DB 列へ選べる）。
 * Excel 列が無い DB 列には既定値を設定する。Excel 列がある列の既定値は空欄時の代替値。
 */
function TableMappingEditor({ tm, table, sourceColumns, onPatch, onAutoMap }) {
  const [columnQuery, setColumnQuery] = useState('');
  const [sourceQuery, setSourceQuery] = useState('');
  const [pageLimit, setPageLimit] = useState(COLUMN_PAGE_SIZE);

  const columns = table?.columns || [];
  const pairByTarget = useMemo(() => {
    const m = new Map();
    for (const p of tm.pairs) if (p.target && !m.has(p.target)) m.set(p.target, p.index);
    return m;
  }, [tm.pairs]);
  const defaultsByColumn = useMemo(
    () => new Map(tm.defaults.map((d) => [d.column, d.value])),
    [tm.defaults],
  );
  const usedIndices = useMemo(
    () => [...pairByTarget.values()].filter((i) => Number.isInteger(i) && i >= 0).map(String),
    [pairByTarget],
  );
  const sourceOptions = useMemo(() => sourceColumns.map((c) => ({
    value: String(c.index), label: c.label, comment: `列 ${c.index + 1}`,
  })), [sourceColumns]);
  const scopedSources = useMemo(
    () => optionSubset(sourceOptions, sourceQuery, usedIndices),
    [sourceOptions, sourceQuery, usedIndices],
  );

  const mappedCount = columns.filter((c) => pairByTarget.has(c.name)).length;
  const defaultCount = columns.filter((c) => text(defaultsByColumn.get(c.name)).trim()).length;
  const unmappedCount = Math.max(0, columns.length - mappedCount);

  const q = String(columnQuery).trim().toLowerCase();
  const matchedColumns = q
    ? columns.filter((c) => `${c.name} ${c.type || ''} ${c.comment || ''}`.toLowerCase().includes(q))
    : columns;
  const shownColumns = matchedColumns.slice(0, pageLimit);

  const setExcelColumn = (column, raw) => {
    const index = raw === '' ? -1 : Number(raw);
    const next = tm.pairs.filter((p) => p.target !== column);
    if (index >= 0) {
      const src = sourceColumns.find((c) => c.index === index);
      next.push({ index, source: src?.label || '', target: column });
    }
    onPatch({ pairs: next });
  };

  const setDefault = (column, value) => {
    const exists = tm.defaults.some((d) => d.column === column);
    const next = exists
      ? tm.defaults.map((d) => (d.column === column ? { ...d, value } : d))
      : [...tm.defaults, { column, value }];
    onPatch({ defaults: next });
  };

  return (
    <div className="tm-block">
      <div className="tm-head">
        <span className="tm-name"><code>{tm.tableId}</code>{table ? `（${columns.length} 列）` : '（メタデータに存在しないテーブル）'}</span>
        <span className="db-filter-count">Excel 割当 {mappedCount} · 未割当 {unmappedCount} · 既定値 {defaultCount}</span>
        <button type="button" className="btn btn-ghost btn-sm" onClick={() => onAutoMap(tm.tableId)} disabled={!sourceColumns.length || !columns.length}>
          自動マッピング
        </button>
      </div>

      <div className="db-filter-row">
        <input
          className="control db-filter-input"
          value={columnQuery}
          placeholder="DB 列名 / 列コメント / 型で絞り込み"
          aria-label={`${tm.tableId} の DB 列絞り込み`}
          onChange={(e) => { setColumnQuery(e.target.value); setPageLimit(COLUMN_PAGE_SIZE); }}
        />
        <span className="db-filter-count">{matchedColumns.length} / {columns.length} 列</span>
      </div>
      <div className="db-filter-row">
        <input
          className="control db-filter-input"
          value={sourceQuery}
          placeholder="Excel 列名で絞り込み（選択肢用・割当済み列は常に表示）"
          aria-label="Excel 列の選択肢絞り込み"
          onChange={(e) => setSourceQuery(e.target.value)}
        />
        <span className="db-filter-count">該当 {scopedSources.matched} / 全 {scopedSources.total} 列</span>
      </div>

      <div className="db-list map-list">
        <div className="map-row map-row-head map-db-head">
          <span>DB 列（型 / コメント）</span><span>←</span><span>Excel 列</span><span>既定値</span>
        </div>
        {shownColumns.map((c) => {
          const current = pairByTarget.has(c.name) ? String(pairByTarget.get(c.name)) : '';
          const hasExcel = current !== '';
          return (
            <div className={`map-row map-db-row${hasExcel ? '' : ' map-db-unmapped'}`} key={c.name}>
              <code title={`${c.name} ${c.type || ''} ${c.comment || ''}`}>
                {c.name}{c.type ? <span className="map-db-type"> [{c.type}]</span> : null}
                {c.comment ? <span className="map-db-comment"> — {c.comment}</span> : null}
              </code>
              <span className="map-arrow">←</span>
              <select
                className="control"
                value={current}
                aria-label={`${c.name} に割り当てる Excel 列`}
                onChange={(e) => setExcelColumn(c.name, e.target.value)}
              >
                <option value="">（Excel 列なし → 既定値）</option>
                {scopedSources.list.map((o) => (
                  <option key={o.value} value={o.value}>{o.label}</option>
                ))}
              </select>
              <input
                className="control"
                value={defaultsByColumn.get(c.name) ?? ''}
                placeholder={hasExcel ? '空欄時の既定値' : '既定値（定数）'}
                aria-label={`${c.name} の既定値`}
                onChange={(e) => setDefault(c.name, e.target.value)}
              />
            </div>
          );
        })}
        {!shownColumns.length && <div className="db-empty">該当する DB 列がありません</div>}
        {shownColumns.length < matchedColumns.length && (
          <div className="db-list-more">
            <span>{shownColumns.length} / {matchedColumns.length} 件を表示（上限なし）</span>
            <button type="button" className="link-btn" onClick={() => setPageLimit((n) => n + COLUMN_PAGE_SIZE)}>
              さらに {COLUMN_PAGE_SIZE} 件を表示
            </button>
          </div>
        )}
        <div className="db-tip">
          表 → Excel の方向で割り当てます。同じ Excel 列を複数の DB 列（別テーブルも可）へ選べます。
          Excel 列なしの DB 列は「既定値」が定数になり、Excel 列ありの列は空欄時だけ既定値を使います。
          <code>CURRENT_TIMESTAMP</code> / <code>NOW()</code> / <code>DEFAULT</code> はそのまま出力。
        </div>
      </div>
    </div>
  );
}

export default function MappingPanel({
  metadataItems, metadataId, onMetadata, metadataName,
  tables, tableMappings, onToggleTable, onPatchTable, onAutoMapTable,
  sourceColumns, mappingItems, mappingGroups, mappingName, onMappingName,
  currentMappingRef, currentGroupName, onSave, onLoad, onDelete, busy,
}) {
  const [tableFilter, setTableFilter] = useState('');
  const [tableLimit, setTableLimit] = useState(TABLE_PAGE_SIZE);

  const visibleTables = useMemo(() => filterTables(tables, tableFilter), [tables, tableFilter]);
  const checkedIds = useMemo(() => new Set(tableMappings.map((t) => t.tableId)), [tableMappings]);
  const totalPairs = tableMappings.reduce((n, t) => n + t.pairs.filter((p) => p.target).length, 0);
  const shownTables = visibleTables.slice(0, tableLimit);
  const tableById = useMemo(() => new Map(tables.map((t) => [t.id, t])), [tables]);
  const groups = mappingGroups || [];
  const files = mappingItems || [];
  // 保存名 → 実際に生成される JSON ファイル名のプレビュー
  const previewName = String(mappingName || '').trim() || '（マッピング名）';
  const previewFiles = useMemo(
    () => tableMappings.map((tm) => `${mappingFileId(previewName, tm.tableId || 'table')}.json`),
    [previewName, tableMappings],
  );

  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>② 列マッピング（表 → Excel）</h2>
          <p className="panel-sub">DB テーブルの列ごとに Excel 列、または既定値を割り当てます</p>
        </div>
        <span className="badge badge-neutral">{tableMappings.length} 表 / {totalPairs} 列</span>
      </div>

      <div className="db-config-scroll">
        <div className="map-source-box">
          <label className="db-field">
            <span>DB メタデータ</span>
            <select className="control" value={metadataId} onChange={(e) => onMetadata(e.target.value)}>
              <option value="sample">サンプルスキーマ（FMS / CRM）</option>
              {metadataItems.map((m) => (
                <option key={m.id} value={m.id}>{m.name || m.id}（{m.tableCount} テーブル）</option>
              ))}
            </select>
          </label>
          <div className="db-filter-row">
            <input
              className="control db-filter-input"
              value={tableFilter}
              placeholder="テーブル名 / スキーマ / 表コメントで絞り込み"
              aria-label="テーブル名の絞り込み"
              onChange={(e) => { setTableFilter(e.target.value); setTableLimit(TABLE_PAGE_SIZE); }}
            />
            {tableFilter && <button type="button" className="link-btn" onClick={() => setTableFilter('')}>クリア</button>}
            <span className="db-filter-count">{visibleTables.length} / {tables.length}</span>
          </div>
          <div className="db-list map-table-list">
            {shownTables.map((t) => (
              <label className="map-table-item" key={t.id}>
                <input
                  type="checkbox"
                  checked={checkedIds.has(t.id)}
                  onChange={(e) => onToggleTable(t.id, e.target.checked)}
                />
                <code>{t.id}</code>
                <span className="db-filter-count">{t.columns.length} 列</span>
              </label>
            ))}
            {!shownTables.length && <div className="db-empty">該当するテーブルがありません</div>}
            {shownTables.length < visibleTables.length && (
              <div className="db-list-more">
                <span>{shownTables.length} / {visibleTables.length} 件を表示（上限なし）</span>
                <button type="button" className="link-btn" onClick={() => setTableLimit((n) => n + TABLE_PAGE_SIZE)}>
                  さらに {TABLE_PAGE_SIZE} 件を表示
                </button>
              </div>
            )}
          </div>
          {metadataName && <div className="db-tip">メタデータ: {metadataName} · テーブル数上限なし。チェックした表の順に INSERT を生成します</div>}
        </div>

        {tableMappings.map((tm) => (
          <TableMappingEditor
            key={tm.tableId}
            tm={tm}
            table={tableById.get(tm.tableId)}
            sourceColumns={sourceColumns}
            onPatch={(patch) => onPatchTable(tm.tableId, patch)}
            onAutoMap={onAutoMapTable}
          />
        ))}
        {!tableMappings.length && (
          <div className="db-empty">対象テーブルをチェックしてください（複数選択可）</div>
        )}

        <div className="map-save-box">
          <div className="db-section-head">
            <span>マッピングを JSON に保存（1 テーブル = 1 ファイル）</span>
            <span className="db-section-sub">data/mappings/&lt;保存名&gt;__&lt;表名&gt;.json</span>
          </div>
          <div className="map-save-row">
            <input
              className="control"
              value={mappingName}
              placeholder="マッピング名（例: 受注取込 → t_order ほか）"
              aria-label="マッピング名"
              onChange={(e) => onMappingName(e.target.value)}
            />
            <button
              type="button" className="btn btn-primary btn-sm"
              onClick={onSave} disabled={busy === 'save' || !tableMappings.length}
            >{busy === 'save' ? '保存中…' : '保存'}</button>
          </div>
          <div className="map-file-preview">
            <span className="db-section-sub">
              保存名がファイル名になります（{previewFiles.length} ファイル{currentGroupName ? ` · 読込中: ${currentGroupName}` : ''}）
            </span>
            {previewFiles.slice(0, 6).map((f) => <code key={f}>{f}</code>)}
            {previewFiles.length > 6 && <span className="db-filter-count">…ほか {previewFiles.length - 6} ファイル</span>}
          </div>
          <div className="map-save-row">
            <select className="control" value={currentMappingRef} onChange={(e) => onLoad(e.target.value)}>
              <option value="">（保存済みマッピングを選択）</option>
              <optgroup label="保存名ごと（全テーブルをまとめて読込）">
                {groups.map((g) => (
                  <option key={`g:${g.name}`} value={`group:${g.name}`}>
                    {g.name} · {g.tableIds[0]}{g.tableCount > 1 ? ` ほか ${g.tableCount} 表` : ''} · {g.pairCount} 列（{g.fileCount} JSON）
                  </option>
                ))}
              </optgroup>
              <optgroup label="個別テーブル JSON（CLI の --mapping に指定）">
                {files.map((m) => (
                  <option key={`f:${m.id}`} value={`file:${m.id}`}>
                    {m.file} · {m.tableId} · {m.pairCount} 列
                  </option>
                ))}
              </optgroup>
            </select>
            <button
              type="button" className="btn btn-danger-ghost btn-sm"
              onClick={onDelete} disabled={!currentMappingRef}
            >削除</button>
          </div>
          <div className="db-tip">
            1 つのテーブルにつき 1 つの JSON を書き出します。ファイル名は「保存名__スキーマ.テーブル名.json」なので、
            保存時の名前とファイルの対応がそのまま分かります。同じ保存名の JSON はまとめて読込・削除でき、
            コマンドラインでは <code>--mapping "保存名"</code> で全表、<code>--mapping "ファイルID"</code> で 1 表だけ生成できます。
            保存するのは列名・対応関係・既定値だけです（Excel のデータ本体は保存しません）。
          </div>
        </div>
      </div>
    </section>
  );
}

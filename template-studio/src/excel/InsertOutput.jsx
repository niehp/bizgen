import React, { useState } from 'react';
import {
  defaultSqlFileName, normalizeSqlFileName, sqlFileContents,
} from '../../shared/sqlfile.js';

const ROW_LIMITS = [
  { value: 10, label: '先頭 10 行' },
  { value: 50, label: '先頭 50 行' },
  { value: 200, label: '先頭 200 行' },
  { value: 0, label: '全件' },
];

export default function InsertOutput({ result, options, onOptions, fileBaseName, dataRowCount, onStatus }) {
  const [fileNameInput, setFileNameInput] = useState('');
  const sql = result?.sql || '';
  const warnings = result?.warnings || [];
  const stats = result?.stats || { lines: 0, chars: 0, statements: 0, rows: 0, columns: 0, tables: [] };
  const fallbackName = defaultSqlFileName('insert', fileBaseName || 'multi');
  const defaultName = normalizeSqlFileName('', fallbackName);
  const downloadName = normalizeSqlFileName(fileNameInput, fallbackName);
  const startRow = Math.max(1, Number(options.startRow) || 1);

  const patch = (key, value) => onOptions({ [key]: value });

  const copySql = async () => {
    if (!sql) return;
    try {
      await navigator.clipboard.writeText(sql);
      onStatus({ type: 'ok', text: 'INSERT SQL をコピーしました' });
    } catch {
      onStatus({ type: 'error', text: 'クリップボードにアクセスできません' });
    }
  };

  const download = () => {
    if (!sql) return;
    try {
      const blob = new Blob([sqlFileContents(sql)], { type: 'application/sql;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = downloadName;
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      onStatus({ type: 'ok', text: `${downloadName} をダウンロードしました（${stats.statements} 文）` });
    } catch (e) {
      onStatus({ type: 'error', text: `ダウンロードに失敗しました: ${e.message || e}` });
    }
  };

  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>③ INSERT SQL</h2>
          <p className="panel-sub">マッピングに従って PostgreSQL 向け INSERT を生成します</p>
        </div>
        <span className="badge badge-neutral">
          {stats.statements} 文 / {stats.columns} 列{stats.tables?.length > 1 ? ` / ${stats.tables.length} 表` : ''}
        </span>
      </div>

      <div className="db-options map-out-options">
        <label className="db-field"><span>生成モード</span>
          <select className="control" value={options.mode} onChange={(e) => patch('mode', e.target.value)}>
            <option value="values">Excel の実データを埋め込む</option>
            <option value="template">埋め込みコメント雛形（/*camelCase*/）</option>
          </select>
        </label>
        <label className="db-field"><span>開始行（データ行）</span>
          <input
            className="control" type="number" min="1"
            value={startRow}
            placeholder="1"
            aria-label="出力を開始するデータ行"
            onChange={(e) => patch('startRow', Math.max(1, Number(e.target.value) || 1))}
            disabled={options.mode === 'template'}
          />
        </label>
        <label className="db-field"><span>出力行数</span>
          <select
            className="control" value={options.rowLimit}
            onChange={(e) => patch('rowLimit', Number(e.target.value))}
            disabled={options.mode === 'template'}
          >
            {ROW_LIMITS.map((r) => <option key={r.value} value={r.value}>{r.label}</option>)}
          </select>
        </label>
        <label className="db-field"><span>INSERT の形式</span>
          <select
            className="control" value={options.batch}
            onChange={(e) => patch('batch', e.target.value)}
            disabled={options.mode === 'template'}
          >
            <option value="each">1 行につき 1 INSERT</option>
            <option value="multi">複数 VALUES を 1 INSERT に</option>
          </select>
        </label>
        <div className="db-switches">
          <label className="switch"><input type="checkbox" checked={options.emptyAsNull} onChange={(e) => patch('emptyAsNull', e.target.checked)} /><span>空欄は NULL</span></label>
          <label className="switch"><input type="checkbox" checked={options.includeSchema} onChange={(e) => patch('includeSchema', e.target.checked)} /><span>スキーマ名を付与</span></label>
          <label className="switch"><input type="checkbox" checked={options.quoteIdentifiers} onChange={(e) => patch('quoteIdentifiers', e.target.checked)} /><span>識別子を引用</span></label>
          <label className="switch"><input type="checkbox" checked={options.convertSerial} onChange={(e) => patch('convertSerial', e.target.checked)} /><span>Excel シリアル日付を変換</span></label>
        </div>
      </div>

      <div className="db-tip db-ph-hint">
        値は DB の列型で判定します（文字列 <code>{'/*camelCase*/\'\''}</code> 相当のクォート、
        数値は無引用、日付は <code>'2026-12-01'</code>、シングルクォートは二重化）。
        雛形モードは <code>{'/*camelCase*/\'\''}</code> / <code>99</code> / <code>'2026-12-01'</code> を出力します。
        データ {dataRowCount} 行のうち {startRow} 行目から出力し、全 {stats.rows} 行（{stats.tables?.length || 0} 表）。
      </div>

      {stats.tables?.length > 0 && (
        <div className="tm-stats">
          {stats.tables.map((t) => (
            <span className="tag tag-soft" key={t.tableId}>
              {t.tableId}: {t.statements} 文 · {t.columns} 列{t.defaultColumns ? ` · 既定値 ${t.defaultColumns}` : ''}
            </span>
          ))}
        </div>
      )}

      <div className="toolbar">
        <button type="button" className="btn btn-primary btn-sm" onClick={copySql} disabled={!sql}>SQL をコピー</button>
        <span className="spacer" />
        <span className="mini-stat">{stats.lines} 行 / {sql.length} 文字</span>
      </div>

      <div className="db-file-bar">
        <label className="db-file-field">
          <span>保存ファイル名</span>
          <input
            className="control db-file-input"
            value={fileNameInput}
            placeholder={defaultName}
            aria-label="保存ファイル名"
            onChange={(e) => setFileNameInput(e.target.value)}
          />
        </label>
        <button type="button" className="btn btn-sm" onClick={download} disabled={!sql}>.sql をダウンロード</button>
        <span className="db-file-hint">出力: <code>{downloadName}</code> · 拡張子は自動付与</span>
      </div>

      {warnings.length > 0 && (
        <div className="db-warnings">
          {warnings.map((w, i) => <div className="alert alert-warn" key={i}>{w}</div>)}
        </div>
      )}

      <div className="db-output-scroll">
        <div className="db-sql-block">
          <div className="db-sql-title">INSERT <span>{stats.statements} 文</span></div>
          <pre className="output db-sql">{sql || '（SQL がありません。ファイルと対象テーブルを選び、列を割り当ててください）'}</pre>
        </div>
      </div>
    </section>
  );
}

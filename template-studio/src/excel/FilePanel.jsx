import React from 'react';

const ACCEPT = '.xlsx,.xlsm,.csv,.tsv,.txt,.json,.md,.markdown';
const PREVIEW_ROWS = 20;

const cell = (v) => (v === undefined || v === null ? '' : String(v));

export default function FilePanel({
  file, busy, skipRows, headerMode, useHeader, autoHeader,
  sourceColumns, dataRows, onPick, onSheet, onClear, onSkipRows, onHeaderMode,
}) {
  const pick = (e) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (f) onPick(f);
  };
  const preview = (dataRows || []).slice(0, PREVIEW_ROWS);
  const warnings = file?.warnings || [];

  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>① Excel / ファイル取込</h2>
          <p className="panel-sub">ファイルを読み込み、列とデータを確認します</p>
        </div>
        <span className="badge badge-blue">{file ? `${sourceColumns.length} 列` : '未読込'}</span>
      </div>

      <div className="import-file-row">
        <label className="btn btn-sm import-file-btn">
          {busy === 'file' ? '読み込み中…' : (file ? '別のファイルを選択' : 'ファイルを選択')}
          <input type="file" accept={ACCEPT} onChange={pick} disabled={busy === 'file'} />
        </label>
        <span className="import-file-name">
          {file ? file.filename : 'xlsx / xlsm / csv / tsv / txt / json / md に対応'}
        </span>
        {file && <button type="button" className="link-btn" onClick={onClear}>クリア</button>}
      </div>

      {!file ? (
        <div className="empty">
          <p>ファイルが読み込まれていません</p>
          <p className="empty-sub">1 行目をヘッダーとして使うかは読み込み後に選べます</p>
        </div>
      ) : (
        <>
          <div className="excel-info">
            <span className="tag">{file.kind}</span>
            <span className="tag tag-soft">{file.rowCount} 行</span>
            <span className="tag tag-soft">{sourceColumns.length} 列</span>
            <span className="tag tag-teal">データ {dataRows.length} 行</span>
            {file.truncated && <span className="tag tag-soft">先頭 1000 行まで</span>}
          </div>

          <div className="excel-options">
            {file.sheetNames?.length > 1 && (
              <label className="seq-item"><span>シート</span>
                <select className="control" value={file.sheet} onChange={(e) => onSheet(e.target.value)} disabled={busy === 'file'}>
                  {file.sheetNames.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </label>
            )}
            <label className="seq-item"><span>先頭から除外する行数</span>
              <input
                className="control" type="number" min="0" max="50"
                value={skipRows}
                onChange={(e) => onSkipRows(e.target.value === '' ? 0 : Number(e.target.value))}
              />
            </label>
            {!file.header?.length && (
              <label className="seq-item"><span>1 行目の扱い</span>
                <select className="control" value={headerMode} onChange={(e) => onHeaderMode(e.target.value)}>
                  <option value="auto">自動判定（{autoHeader ? 'ヘッダー' : 'データ'}）</option>
                  <option value="on">列名ヘッダー</option>
                  <option value="off">データ行</option>
                </select>
              </label>
            )}
            <span className="db-filter-note">
              {useHeader ? '1 行目を列名として使用' : '1 行目からデータとして使用（列名は 列1, 列2 …）'}
            </span>
          </div>

          {warnings.length > 0 && (
            <div className="db-warnings">
              {warnings.map((w, i) => <div className="alert alert-warn" key={i}>{w}</div>)}
            </div>
          )}

          <div className="db-section-head">
            <span>読み取ったデータ（先頭 {preview.length} 行 / 全 {dataRows.length} 行）</span>
            <span className="db-section-sub">横スクロールで全列を確認</span>
          </div>
          <div className="excel-preview-wrap">
            <table className="tbl excel-preview">
              <thead>
                <tr>
                  <th className="tbl-idx">#</th>
                  {sourceColumns.map((c) => (
                    <th key={c.index}>
                      <div className="tbl-th-name">{c.label}</div>
                      <div className="tbl-th-label">列 {c.index + 1}</div>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {preview.map((r, ri) => (
                  <tr key={ri}>
                    <td className="tbl-idx">{ri + 1}</td>
                    {sourceColumns.map((c) => <td key={c.index}>{cell(r?.[c.index])}</td>)}
                  </tr>
                ))}
                {!preview.length && (
                  <tr><td colSpan={sourceColumns.length + 1} className="db-empty">データ行がありません</td></tr>
                )}
              </tbody>
            </table>
          </div>
          <div className="db-tip">
            取り込みはサーバー側で解析し、ファイル本体は保存しません。列数は無制限、行数は先頭 1000 行までです。
          </div>
        </>
      )}
    </section>
  );
}

import React, { useState } from 'react';
import {
  defaultSqlFileName, normalizeSqlFileName, sqlFileContents,
} from '../../shared/sqlfile.js';

const KIND_LABEL = { join: '結合 SELECT', update: 'UPDATE', delete: 'DELETE' };

/** 選択中の SQL 種類だけを 1 件表示する（他種別の SQL は一切出力しない） */
export default function SqlOutput({ result, sqlKind, onStatus, defaultFileName }) {
  const sql = result?.sql || '';
  const warnings = result?.warnings || [];
  const lines = result?.stats?.lines || 0;
  const [fileNameInput, setFileNameInput] = useState('');

  const fallbackName = defaultFileName || defaultSqlFileName(sqlKind);
  const defaultName = normalizeSqlFileName('', fallbackName);
  const downloadName = normalizeSqlFileName(fileNameInput, fallbackName);

  const copySql = async () => {
    if (!sql) return;
    try {
      await navigator.clipboard.writeText(sql);
      onStatus({ type: 'ok', text: `${KIND_LABEL[sqlKind]} をコピーしました` });
    } catch {
      onStatus({ type: 'error', text: 'クリップボードにアクセスできません' });
    }
  };

  const downloadSql = () => {
    if (!sql) return;
    try {
      // ファイルは表示中の 1 件だけ。末尾に改行を付けて POSIX 準拠のテキストにする。
      const body = sqlFileContents(sql);
      const blob = new Blob([body], { type: 'application/sql;charset=utf-8' });
      const url = URL.createObjectURL(blob);
      const a = document.createElement('a');
      a.href = url;
      a.download = downloadName;
      a.style.display = 'none';
      document.body.appendChild(a);
      a.click();
      a.remove();
      setTimeout(() => URL.revokeObjectURL(url), 1000);
      onStatus({ type: 'ok', text: `${downloadName} をダウンロードしました（${sql.length} 文字）` });
    } catch (e) {
      onStatus({ type: 'error', text: `ダウンロードに失敗しました: ${e.message || e}` });
    }
  };

  return (
    <section className="panel db-panel">
      <div className="panel-head">
        <div>
          <h2>④ 生成 SQL</h2>
          <p className="panel-sub">選択中の {KIND_LABEL[sqlKind]} のみを表示します</p>
        </div>
        <span className="badge badge-neutral">{lines} 行 / {sql.length} 文字</span>
      </div>

      <div className="toolbar">
        <button type="button" className="btn btn-primary btn-sm" onClick={copySql} disabled={!sql}>
          SQL をコピー
        </button>
        <span className="spacer" />
        <span className="mini-stat">1 件の SQL（{KIND_LABEL[sqlKind]}）· 列コメントなし（{`/*camelCase*/'' / 99 / '2026-12-01'`}）</span>
      </div>

      <div className="db-file-bar">
        <label className="db-file-field">
          <span>保存ファイル名</span>
          <input
            className="control db-file-input"
            value={fileNameInput}
            placeholder={defaultName}
            aria-label="保存ファイル名"
            title="拡張子 .sql は自動で付与されます"
            onChange={(e) => setFileNameInput(e.target.value)}
          />
        </label>
        <button type="button" className="btn btn-sm" onClick={downloadSql} disabled={!sql}>
          .sql をダウンロード
        </button>
        <span className="db-file-hint">
          出力: <code>{downloadName}</code> · 拡張子は自動付与 · 保存先はブラウザ設定に従う
        </span>
      </div>

      {warnings.length > 0 && (
        <div className="db-warnings">
          {warnings.map((w, i) => (
            <div className="alert alert-warn" key={i}>[{KIND_LABEL[sqlKind]}] {w}</div>
          ))}
        </div>
      )}

      <div className="db-output-scroll">
        <div className="db-sql-block">
          <div className="db-sql-title">{KIND_LABEL[sqlKind]} <span>{lines} 行</span></div>
          <pre className="output db-sql">{sql || '（出力する SQL がありません。テーブルと列を選択してください）'}</pre>
        </div>
      </div>
    </section>
  );
}

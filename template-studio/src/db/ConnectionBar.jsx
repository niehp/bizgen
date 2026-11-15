import React, { useRef, useState } from 'react';
import { APP_DB_DIALECT, dialectInfo } from '../../shared/dbmeta.js';

const LABELS = {
  host: 'ホスト', port: 'ポート', user: 'ユーザー', password: 'パスワード',
  database: 'DB 名', schema: 'スキーマ', serviceName: 'サービス名',
  filePath: 'SQLite ファイル', ssl: 'SSL', encrypt: '暗号化',
};

const NUMERIC = new Set(['port']);

export default function ConnectionBar({
  connection, onConnectionChange, drivers, items, sourceId, metadata, busy,
  onTest, onExtract, onLoadSample, onLoadItem, onDeleteItem, onUpload,
  onSaveSnapshot, onStatus,
}) {
  const [showJson, setShowJson] = useState(false);
  const [jsonName, setJsonName] = useState('');
  const [selectedItem, setSelectedItem] = useState('');
  const fileRef = useRef(null);

  const info = dialectInfo(APP_DB_DIALECT);
  const driver = drivers.find((d) => d.id === info.id);
  const driverBad = driver && !driver.installed;

  const set = (key, value) => onConnectionChange({ [key]: value });

  const copyJson = async () => {
    if (!metadata) return;
    try {
      await navigator.clipboard.writeText(JSON.stringify(metadata, null, 2));
      onStatus({ type: 'ok', text: 'メタデータ JSON をクリップボードにコピーしました' });
    } catch {
      onStatus({ type: 'error', text: 'クリップボードにアクセスできません。JSON 表示から手動でコピーしてください' });
    }
  };

  const handleFile = async (e) => {
    const file = e.target.files?.[0];
    if (!file) return;
    try {
      const text = await file.text();
      const parsed = JSON.parse(text);
      await onUpload(parsed, file.name);
    } catch (err) {
      onStatus({ type: 'error', text: `JSON 読込失敗: ${err.message}` });
    } finally {
      e.target.value = '';
    }
  };

  return (
    <section className="db-bar">
      <div className="db-bar-head">
        <div className="db-bar-title">
          <span className="db-step">1</span>
          <div>
            <h2>DB 接続情報 → メタデータ抽出</h2>
            <p>テーブル / 列のコメント・主キー・外部キーを取得し JSON に保存します（パスワードは保存しません）</p>
          </div>
        </div>
        <div className="db-bar-actions">
          <button type="button" className="btn btn-ghost" onClick={onLoadSample}>サンプルを読み込む</button>
          <button type="button" className="btn btn-ghost" onClick={onTest} disabled={Boolean(busy)}>
            {busy === 'test' ? '接続中…' : '接続テスト'}
          </button>
          <button type="button" className="btn btn-primary" onClick={onExtract} disabled={Boolean(busy)}>
            {busy === 'extract' ? '抽出中…' : 'メタデータ抽出 → JSON 保存'}
          </button>
        </div>
      </div>

      <div className="db-conn-grid">
        <div className="db-field">
          <span>DB 種別</span>
          <select className="control" value={APP_DB_DIALECT} disabled title="接続先は PostgreSQL 固定です">
            <option value={APP_DB_DIALECT}>PostgreSQL（固定）</option>
          </select>
        </div>
        {info.fields.map((key) => (
          <div className="db-field" key={key}>
            <span>{LABELS[key] || key}</span>
            {key === 'ssl' || key === 'encrypt' ? (
              <label className="db-check">
                <input
                  type="checkbox"
                  checked={Boolean(connection[key])}
                  onChange={(e) => set(key, e.target.checked)}
                />
                有効
              </label>
            ) : (
              <input
                className="control"
                type={key === 'password' ? 'password' : NUMERIC.has(key) ? 'number' : 'text'}
                value={connection[key] ?? ''}
                placeholder={key === 'schema' ? info.schemaHint : ''}
                onChange={(e) => set(key, NUMERIC.has(key) ? Number(e.target.value) : e.target.value)}
              />
            )}
          </div>
        ))}
        <div className="db-field">
          <span>テーブル名フィルタ</span>
          <input
            className="control"
            value={connection.tablePattern ?? ''}
            placeholder="例: m_* / t_order*"
            onChange={(e) => set('tablePattern', e.target.value)}
          />
        </div>
        <div className="db-field db-field-short">
          <span>ビュー</span>
          <label className="db-check">
            <input
              type="checkbox"
              checked={Boolean(connection.includeViews)}
              onChange={(e) => set('includeViews', e.target.checked)}
            />
            含める
          </label>
        </div>
      </div>

      <div className="db-conn-hint">
        接続先は PostgreSQL 固定です。テーブル / 列コメントは
        <code>obj_description()</code> / <code>col_description()</code>（pg_catalog.pg_description）から、
        主キー・外部キー・型・NULL 許容は information_schema / pg_catalog から取得します。
        テーブル数・列数に上限はなく、抽出した全件をそのまま JSON に保存します。
      </div>

      {driverBad && (
        <div className="db-driver-warn">
          {info.label} 用ドライバが未インストールです。プロジェクト直下で <code>{driver.install}</code> を実行してください。
        </div>
      )}

      <div className="db-json-row">
        <span className="db-row-label">保存済み JSON</span>
        <select
          className="control db-item-select"
          value={selectedItem}
          onChange={(e) => setSelectedItem(e.target.value)}
        >
          <option value="">（選択）</option>
          {items.map((it) => (
            <option key={it.id} value={it.id}>
              {it.name} — {it.tableCount} 表 / {it.columnCount} 列
            </option>
          ))}
        </select>
        <button
          type="button" className="btn btn-ghost btn-sm" disabled={!selectedItem}
          onClick={() => onLoadItem(selectedItem)}
        >読み込む</button>
        <button
          type="button" className="btn btn-danger-ghost btn-sm" disabled={!selectedItem}
          onClick={() => onDeleteItem(selectedItem)}
        >削除</button>
        <span className="db-sep" />
        <label className="import-file-btn">
          <span className="btn btn-ghost btn-sm">JSON ファイルを読み込む</span>
          <input ref={fileRef} type="file" accept=".json,application/json" onChange={handleFile} />
        </label>
        <input
          className="control db-name-input"
          value={jsonName}
          placeholder={metadata?.name || 'メタデータ名'}
          onChange={(e) => setJsonName(e.target.value)}
        />
        <button
          type="button" className="btn btn-ghost btn-sm" disabled={!metadata}
          onClick={() => onSaveSnapshot(jsonName.trim() || metadata?.name || 'DB メタデータ')}
        >現在の内容を JSON 保存</button>
        <span className="spacer" />
        {metadata && (
          <>
            <button type="button" className="link-btn" onClick={() => setShowJson((v) => !v)}>
              {showJson ? 'JSON を閉じる' : 'JSON を表示'}
            </button>
            <button type="button" className="link-btn" onClick={copyJson}>JSON をコピー</button>
          </>
        )}
        {sourceId && <span className="tag tag-soft">{sourceId}</span>}
      </div>

      {showJson && metadata && (
        <pre className="output db-json">{JSON.stringify(metadata, null, 2)}</pre>
      )}
    </section>
  );
}

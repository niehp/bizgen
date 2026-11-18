import React, { useMemo, useState } from 'react';
import { presetFileStem } from '../../shared/preset.js';

const text = (v) => (v === undefined || v === null ? '' : String(v));

/**
 * 固定値プリセット編集パネル。
 * 「列名 = 固定値」の組を名前付きで保存 / 編集 / 削除し、選択中のテーブルへ適用する。
 * 値は CURRENT_TIMESTAMP / NOW() / DEFAULT / 数値 / クォート済みリテラルをそのまま書ける。
 */
export default function PresetPanel({
  presets, busy, onSave, onDelete, onApply, hasTable,
}) {
  const [selectedId, setSelectedId] = useState('');
  const [draft, setDraft] = useState(null);

  const selected = useMemo(
    () => presets.find((p) => p.id === selectedId) || null,
    [presets, selectedId],
  );

  const startNew = () => {
    setSelectedId('');
    setDraft({ id: '', name: '', entries: [{ column: '', value: '' }] });
  };

  const startEdit = () => {
    if (!selected) return;
    setDraft({
      id: selected.id,
      name: selected.name,
      entries: selected.entries.length
        ? selected.entries.map((e) => ({ ...e }))
        : [{ column: '', value: '' }],
    });
  };

  const patchDraft = (patch) => setDraft((prev) => ({ ...prev, ...patch }));

  const patchEntry = (i, key, value) => setDraft((prev) => ({
    ...prev,
    entries: prev.entries.map((e, idx) => (idx === i ? { ...e, [key]: value } : e)),
  }));

  const addEntry = () => setDraft((prev) => ({
    ...prev,
    entries: [...prev.entries, { column: '', value: '' }],
  }));

  const removeEntry = (i) => setDraft((prev) => ({
    ...prev,
    entries: prev.entries.filter((_, idx) => idx !== i),
  }));

  const save = async () => {
    if (!draft) return;
    if (!draft.name.trim()) { window.alert('プリセット名を入力してください'); return; }
    if (!draft.entries.some((e) => e.column.trim())) {
      window.alert('固定値を 1 列以上入力してください'); return;
    }
    try {
      const saved = await onSave(draft);
      setSelectedId(saved.id);
      setDraft(null);
    } catch { /* 上位で通知済み */ }
  };

  const remove = () => {
    if (!selected) return;
    if (!window.confirm(`固定値プリセット「${selected.name}」を削除しますか？`)) return;
    onDelete(selected.id);
    setSelectedId('');
    if (draft?.id === selected.id) setDraft(null);
  };

  const previewFile = draft?.name?.trim()
    ? `${presetFileStem(draft.name)}.json`
    : '（プリセット名）.json';

  return (
    <div className="map-preset-box">
      <div className="db-section-head">
        <span>固定値プリセット（列 = 固定値）</span>
        <span className="db-section-sub">data/presets/&lt;名前&gt;.json</span>
      </div>

      <div className="map-save-row">
        <select
          className="control"
          value={selectedId}
          aria-label="固定値プリセットを選択"
          onChange={(e) => { setSelectedId(e.target.value); setDraft(null); }}
        >
          <option value="">（プリセットを選択）</option>
          {presets.map((p) => (
            <option key={p.id} value={p.id}>{p.name} · {p.entryCount} 列</option>
          ))}
        </select>
        <button type="button" className="btn btn-ghost btn-sm" onClick={startNew}>新規</button>
        <button type="button" className="btn btn-ghost btn-sm" onClick={startEdit} disabled={!selected}>編集</button>
        <button
          type="button" className="btn btn-primary btn-sm"
          onClick={() => onApply(selectedId)} disabled={!selected || !hasTable}
          title={hasTable ? '選択中のテーブルの既定値へマージします' : '先にテーブルを選択してください'}
        >適用</button>
        <button type="button" className="btn btn-danger-ghost btn-sm" onClick={remove} disabled={!selected}>削除</button>
      </div>

      {selected && !draft && (
        <div className="preset-preview">
          {selected.entries.map((e) => (
            <code key={e.column}>{e.column} = {text(e.value) || '（空）'}</code>
          ))}
        </div>
      )}

      {draft && (
        <div className="preset-editor">
          <div className="map-save-row">
            <input
              className="control"
              value={draft.name}
              placeholder="プリセット名（例: 共通監査列）"
              aria-label="プリセット名"
              onChange={(e) => patchDraft({ name: e.target.value })}
            />
            <span className="db-section-sub">{previewFile}</span>
          </div>
          <div className="preset-entry-head">
            <span>DB 列名</span><span>固定値</span><span />
          </div>
          <div className="preset-entry-list">
            {draft.entries.map((e, i) => (
              <div className="preset-entry-row" key={i}>
                <input
                  className="control"
                  value={e.column}
                  placeholder="company_cd"
                  aria-label={`固定値 ${i + 1} の列名`}
                  onChange={(ev) => patchEntry(i, 'column', ev.target.value)}
                />
                <input
                  className="control"
                  value={e.value}
                  placeholder="10 / CURRENT_TIMESTAMP / NOW() / DEFAULT"
                  aria-label={`${e.column || `固定値 ${i + 1}`} の値`}
                  onChange={(ev) => patchEntry(i, 'value', ev.target.value)}
                />
                <button type="button" className="link-btn" onClick={() => removeEntry(i)} title="この行を削除">×</button>
              </div>
            ))}
          </div>
          <div className="map-save-row">
            <button type="button" className="link-btn" onClick={addEntry}>＋ 行を追加</button>
            <span className="spacer" />
            <button type="button" className="btn btn-ghost btn-sm" onClick={() => setDraft(null)}>キャンセル</button>
            <button type="button" className="btn btn-primary btn-sm" onClick={save} disabled={busy === 'preset'}>
              {busy === 'preset' ? '保存中…' : '保存'}
            </button>
          </div>
        </div>
      )}

      <div className="db-tip">
        Excel 列が無い DB 列へ入れる固定値をまとめて登録できます（例: <code>company_cd=10</code>、<code>create_date=CURRENT_TIMESTAMP</code>）。
        <code>CURRENT_TIMESTAMP</code> / <code>NOW()</code> / <code>DEFAULT</code> / <code>'固定文字列'</code> はそのまま SQL に出力されます。
        「適用」で選択中のテーブルの既定値へマージされます（同じ列はプリセット側で上書き）。
      </div>
    </div>
  );
}

import React, { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import {
  importTableFile, listDbMetadata, listDbTables,
  listMappings, getMapping, getMappingGroup,
  saveMapping, deleteMapping, deleteMappingGroup,
  listPresets, savePreset, deletePreset,
} from '../api.js';
import {
  DEFAULT_MAPPING_OPTIONS, normalizeMapping, autoMapPairs, buildInsertSql,
  deriveSheet, rowsForInsert,
} from '../../shared/mapping.js';
import { normalizePreset, mergePresetIntoDefaults } from '../../shared/preset.js';
import FilePanel from './FilePanel.jsx';
import MappingPanel from './MappingPanel.jsx';
import PresetPanel from './PresetPanel.jsx';
import InsertOutput from './InsertOutput.jsx';

function readAsDataUrl(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('ファイルを読み込めませんでした'));
    reader.readAsDataURL(file);
  });
}

/** 表→Excel 方向: 自動対応のうち実際に DB 列が決まったものだけを pairs に残す */
const autoPairs = (sourceColumns, dbColumns) => autoMapPairs(sourceColumns, dbColumns)
  .filter((p) => p.target);

export default function ExcelStudio({ onStatus }) {
  const [metadataItems, setMetadataItems] = useState([]);
  const [metadataId, setMetadataId] = useState('');
  const [metadataName, setMetadataName] = useState('');
  const [tables, setTables] = useState([]);

  const [file, setFile] = useState(null);
  const [busy, setBusy] = useState('');
  const [skipRows, setSkipRows] = useState(0);
  const [headerMode, setHeaderMode] = useState('auto');
  const [tableMappings, setTableMappings] = useState([]);

  // 固定値プリセット（列 = 固定値の再利用可能なセット）
  const [presets, setPresets] = useState([]);

  const [mappingItems, setMappingItems] = useState([]);
  const [mappingGroups, setMappingGroups] = useState([]);
  // group:<保存名> / file:<JSON ID> / ''（未読込）
  const [currentMappingRef, setCurrentMappingRef] = useState('');
  const [currentGroupName, setCurrentGroupName] = useState('');
  const [mappingName, setMappingName] = useState('');
  const [options, setOptions] = useState(DEFAULT_MAPPING_OPTIONS);

  // 保存済みマッピングを読み込んだ後は、ファイルを選び直しても自動マッピングで上書きしない
  const pinnedRef = useRef(false);
  const autoKeyRef = useRef('');

  const applyMetadata = useCallback(async (id) => {
    if (!id) { setTables([]); setMetadataName(''); return; }
    const r = await listDbTables(id);
    setTables(r.tables || []);
    setMetadataName(r.name || id);
  }, []);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      // 各 API は独立して失敗しうる（例: 旧サーバーには /api/presets が無い）。
      // 1 つが失敗しても DB メタデータ一覧が空にならないよう、個別に結果を扱う。
      const [metaRes, mapsRes, presetRes] = await Promise.allSettled([
        listDbMetadata(), listMappings(), listPresets(),
      ]);
      if (cancelled) return;

      const items = metaRes.status === 'fulfilled' ? (metaRes.value.items || []) : [];
      setMetadataItems(items);
      if (metaRes.status === 'rejected') {
        onStatus({ type: 'error', text: `DB メタデータ一覧を取得できません: ${metaRes.reason?.message || metaRes.reason}` });
      }
      if (mapsRes.status === 'fulfilled') {
        setMappingItems(mapsRes.value.items || []);
        setMappingGroups(mapsRes.value.groups || []);
      }
      if (presetRes.status === 'fulfilled') {
        setPresets(presetRes.value.items || []);
      }

      // 内蔵サンプルは廃止。保存済みメタデータがあれば先頭を自動選択する。
      if (items.length) {
        const first = items[0];
        setMetadataId(first.id);
        try {
          await applyMetadata(first.id);
        } catch (e) {
          if (!cancelled) onStatus({ type: 'error', text: `メタデータ読込失敗: ${e.message}` });
          return;
        }
        if (!cancelled) {
          onStatus({ type: 'ok', text: `メタデータ「${first.name || first.id}」を読み込みました` });
        }
      } else if (metaRes.status === 'fulfilled') {
        onStatus({ type: 'info', text: 'DB メタデータがありません。DB ページで抽出 / JSON 読込してください' });
      }
    })();
    return () => { cancelled = true; };
  }, [onStatus, applyMetadata]);

  const handleMetadata = useCallback(async (id) => {
    // DB メタデータを切り替えたら、古いテーブル列に紐づくマッピングは無効なので全て破棄する
    pinnedRef.current = false;
    autoKeyRef.current = '';
    setMetadataId(id);
    setTableMappings([]);
    setCurrentMappingRef('');
    setCurrentGroupName('');
    try { await applyMetadata(id); }
    catch (e) { onStatus({ type: 'error', text: `メタデータ読込失敗: ${e.message}` }); }
  }, [applyMetadata, onStatus]);

  /* ---------------- ファイル取込 ---------------- */
  const handlePick = useCallback(async (picked) => {
    setBusy('file');
    try {
      const data = await readAsDataUrl(picked);
      const r = await importTableFile(picked.name, data);
      setFile({ ...r, data });
      setSkipRows(0);
      setHeaderMode('auto');
      // 新しい Excel を取込んだら、旧ファイルの列に紐づくマッピング状態は破棄する。
      // ただし保存済みマッピングを明示的に読込中（pinned）の場合は、その Excel データとして
      // 適用する意図なので保持する（読込後にファイルを選ぶWorkflowを壊さない）。
      if (!pinnedRef.current) {
        setTableMappings([]);
        setCurrentMappingRef('');
        setCurrentGroupName('');
      }
      autoKeyRef.current = '';
      setMappingName((prev) => prev || `${picked.name.replace(/\.[^.]+$/, '')} → 取込`);
      onStatus({ type: 'ok', text: `${picked.name} を読み込みました（${r.rowCount} 行）` });
    } catch (e) {
      onStatus({ type: 'error', text: `取込失敗: ${e.message}` });
    } finally { setBusy(''); }
  }, [onStatus]);

  const handleSheet = useCallback(async (sheet) => {
    if (!file) return;
    setBusy('file');
    try {
      const r = await importTableFile(file.filename, file.data, sheet);
      setFile({ ...r, data: file.data });
      onStatus({ type: 'ok', text: `シート「${sheet}」を読み込みました` });
    } catch (e) {
      onStatus({ type: 'error', text: `シート読込失敗: ${e.message}` });
    } finally { setBusy(''); }
  }, [file, onStatus]);

  const handleClearFile = useCallback(() => {
    setFile(null);
    setSkipRows(0);
    setHeaderMode('auto');
    autoKeyRef.current = '';
    onStatus({ type: 'info', text: 'ファイルをクリアしました' });
  }, [onStatus]);

  /* ---------------- 読み取った列 / 行（CLI と共通の判定） ---------------- */
  const sheet = useMemo(
    () => deriveSheet(file, { headerMode, skipRows }),
    [file, headerMode, skipRows],
  );
  const {
    autoHeader, useHeader, dataRows, width, sourceColumns,
  } = sheet;

  const tableById = useMemo(() => new Map(tables.map((t) => [t.id, t])), [tables]);

  /* ---------------- テーブル選択 / マッピング編集（単一テーブル選択） ---------------- */
  const toggleTable = useCallback((id) => {
    pinnedRef.current = false;
    setTableMappings((prev) => {
      // 同じテーブルが再度選ばれた場合は解除してマッピングもクリアする
      if (prev.length === 1 && prev[0].tableId === id) return [];
      const cols = tableById.get(id)?.columns || [];
      return [{
        tableId: id,
        pairs: file && sourceColumns.length ? autoPairs(sourceColumns, cols) : [],
        defaults: [],
      }];
    });
  }, [tableById, file, sourceColumns]);

  const patchTable = useCallback((id, patch) => {
    setTableMappings((prev) => prev.map((t) => (t.tableId === id ? { ...t, ...patch } : t)));
  }, []);

  const autoMapTable = useCallback((id) => {
    const cols = tableById.get(id)?.columns || [];
    if (!sourceColumns.length || !cols.length) {
      onStatus({ type: 'error', text: '自動マッピングには Excel ファイルとテーブルの列が必要です' });
      return;
    }
    pinnedRef.current = false;
    setTableMappings((prev) => prev.map((t) => (t.tableId === id
      ? { ...t, pairs: autoPairs(sourceColumns, cols) }
      : t)));
    onStatus({ type: 'ok', text: `${id}: 列名・列コメントから自動マッピングしました` });
  }, [tableById, sourceColumns, onStatus]);

  // ファイル / 対象表が変わったときだけ自動マッピング（手動編集は上書きしない）
  useEffect(() => {
    if (!file || !width || !tableMappings.length) {
      autoKeyRef.current = '';
      return;
    }
    const key = [file.filename, file.sheet, skipRows, headerMode, useHeader, width,
      tableMappings.map((t) => t.tableId).join('+')].join('|');
    if (pinnedRef.current || autoKeyRef.current === key) {
      autoKeyRef.current = key;
      return;
    }
    autoKeyRef.current = key;
    setTableMappings((prev) => prev.map((tm) => {
      const cols = tableById.get(tm.tableId)?.columns || [];
      return { ...tm, pairs: cols.length ? autoPairs(sourceColumns, cols) : tm.pairs };
    }));
  }, [file, width, skipRows, headerMode, useHeader, tableMappings, sourceColumns, tableById]);

  const handleOptions = useCallback((patch) => setOptions((prev) => ({ ...prev, ...patch })), []);

  /* ---------------- マッピング JSON（1 テーブル = 1 ファイル） ---------------- */
  const refreshMappings = useCallback(async () => {
    const r = await listMappings();
    setMappingItems(r.items || []);
    setMappingGroups(r.groups || []);
  }, []);

  const handleSaveMapping = useCallback(async () => {
    if (!tableMappings.length) { onStatus({ type: 'error', text: '対象テーブルを 1 つ選択してください' }); return; }
    if (!tableMappings.some((t) => t.pairs.some((p) => p.target)
      || t.defaults.some((d) => String(d.value ?? '').trim()))) {
      onStatus({ type: 'error', text: '割り当てた Excel 列も既定値もありません' }); return;
    }
    setBusy('save');
    try {
      const payload = normalizeMapping({
        name: mappingName.trim() || `${file?.filename || 'Excel'} → ${tableMappings[0].tableId}`,
        metadataId,
        sourceFile: file?.filename || '', sheet: file?.sheet || '',
        headerMode, skipRows,
        tables: tableMappings,
        options,
      });
      const r = await saveMapping(payload, undefined, currentGroupName || undefined);
      pinnedRef.current = true;
      setCurrentGroupName(r.groupName);
      setCurrentMappingRef(`group:${r.groupName}`);
      setMappingName(r.groupName);
      await refreshMappings();
      onStatus({
        type: 'ok',
        text: `「${r.groupName}」を ${r.files.length} 個の JSON に保存しました（1 テーブル = 1 ファイル）`,
      });
    } catch (e) {
      onStatus({ type: 'error', text: `保存失敗: ${e.message}` });
    } finally { setBusy(''); }
  }, [tableMappings, mappingName, file, metadataId, headerMode, skipRows, options,
    currentGroupName, refreshMappings, onStatus]);

  const handleLoadMapping = useCallback(async (ref) => {
    setCurrentMappingRef(ref);
    if (!ref) { setCurrentGroupName(''); return; }
    const kind = ref.startsWith('group:') ? 'group' : 'file';
    const key = ref.slice(ref.indexOf(':') + 1);
    try {
      const r = kind === 'group' ? await getMappingGroup(key) : await getMapping(key);
      const m = r.mapping;
      pinnedRef.current = true;
      autoKeyRef.current = '';
      setCurrentGroupName(m.groupName || m.name);
      setMappingName(m.name);
      setHeaderMode(m.headerMode);
      setSkipRows(m.skipRows);
      setOptions({ ...DEFAULT_MAPPING_OPTIONS, ...m.options });
      setMetadataId(m.metadataId || '');
      await applyMetadata(m.metadataId || '');
      setTableMappings(m.tables.length
        ? m.tables
        : [{ tableId: m.tableId, pairs: m.pairs, defaults: [] }]);
      onStatus({
        type: 'ok',
        text: `${m.name}（${m.tables.length} 表 / ${r.ids?.length || 1} JSON）を読み込みました${file ? '' : '。Excel ファイルも選択してください'}`,
      });
    } catch (e) {
      onStatus({ type: 'error', text: `マッピング読込失敗: ${e.message}` });
    }
  }, [applyMetadata, file, onStatus]);

  const handleDeleteMapping = useCallback(async () => {
    if (!currentMappingRef) return;
    const isGroup = currentMappingRef.startsWith('group:');
    const key = currentMappingRef.slice(currentMappingRef.indexOf(':') + 1);
    const label = isGroup ? `保存名「${key}」の全 JSON` : `${key}.json`;
    if (!window.confirm(`${label} を削除しますか？`)) return;
    try {
      const r = isGroup ? await deleteMappingGroup(key) : await deleteMapping(key);
      setCurrentMappingRef('');
      setCurrentGroupName('');
      await refreshMappings();
      onStatus({ type: 'ok', text: `${label} を削除しました（${r.deleted === true ? 1 : r.deleted} ファイル）` });
    } catch (e) { onStatus({ type: 'error', text: `削除失敗: ${e.message}` }); }
  }, [currentMappingRef, refreshMappings, onStatus]);

  /* ---------------- 固定値プリセット ---------------- */
  const refreshPresets = useCallback(async () => {
    const r = await listPresets();
    setPresets(r.items || []);
  }, []);

  const handleSavePreset = useCallback(async (raw) => {
    setBusy('preset');
    try {
      const payload = normalizePreset(raw);
      const r = await savePreset(payload);
      pinnedRef.current = true;
      await refreshPresets();
      onStatus({ type: 'ok', text: `固定値プリセット「${r.preset.name}」を保存しました（${r.preset.entries.length} 列）` });
      return r.preset;
    } catch (e) {
      onStatus({ type: 'error', text: `プリセット保存失敗: ${e.message}` });
      throw e;
    } finally { setBusy(''); }
  }, [refreshPresets, onStatus]);

  const handleDeletePreset = useCallback(async (id) => {
    if (!id) return;
    try {
      await deletePreset(id);
      await refreshPresets();
      onStatus({ type: 'ok', text: '固定値プリセットを削除しました' });
    } catch (e) { onStatus({ type: 'error', text: `プリセット削除失敗: ${e.message}` }); }
  }, [refreshPresets, onStatus]);

  // 選択中のテーブル（単一）の既定値へ、プリセットの列=値をマージする
  const handleApplyPreset = useCallback((presetId) => {
    const preset = presets.find((p) => p.id === presetId);
    if (!preset) return;
    if (!tableMappings.length) {
      onStatus({ type: 'error', text: '先に対象テーブルを選択してください' });
      return;
    }
    const entries = preset.entries.filter((e) => String(e.column || '').trim());
    if (!entries.length) {
      onStatus({ type: 'error', text: 'このプリセットには固定値がありません' });
      return;
    }
    setTableMappings((prev) => prev.map((tm) => ({ ...tm, defaults: mergePresetIntoDefaults(tm.defaults, entries) })));
    pinnedRef.current = true;
    onStatus({ type: 'ok', text: `プリセット「${preset.name}」の固定値 ${entries.length} 列を適用しました` });
  }, [presets, tableMappings, onStatus]);

  /* ---------------- INSERT SQL ---------------- */
  const rowsFromStart = useMemo(
    () => rowsForInsert(dataRows, options, { rowLimit: 0, mode: 'values' }),
    [dataRows, options],
  );
  const rowsForSql = useMemo(
    () => rowsForInsert(dataRows, options),
    [dataRows, options],
  );

  const buildTables = useMemo(() => tableMappings.map((tm) => ({
    tableId: tm.tableId,
    table: tableById.get(tm.tableId) || null,
    pairs: tm.pairs,
    defaults: tm.defaults,
  })), [tableMappings, tableById]);

  const result = useMemo(() => buildInsertSql({
    tables: buildTables, rows: rowsForSql, mode: options.mode, options,
  }), [buildTables, rowsForSql, options.mode, options]);
  // ファイル未読込の初期画面では「列未割当」の警告を出さない
  const shownResult = file ? result : { ...result, warnings: [] };
  const fileBaseName = tableMappings[0]?.tableId
    || (tableMappings.length > 1 ? 'multi' : '');

  return (
    <div className="db-page">
      <div className="db-kind-bar">
        <span className="db-row-label">Excel 取込 → 列マッピング → INSERT SQL（1 テーブル単位で保存）</span>
        <span className="db-kind-hint">メタデータは DB ページで保存した JSON を再利用できます</span>
      </div>
      <main className="workspace workspace-db workspace-excel">
        <FilePanel
          file={file}
          busy={busy}
          skipRows={skipRows}
          headerMode={headerMode}
          useHeader={useHeader}
          autoHeader={autoHeader}
          sourceColumns={sourceColumns}
          dataRows={dataRows}
          onPick={handlePick}
          onSheet={handleSheet}
          onClear={handleClearFile}
          onSkipRows={setSkipRows}
          onHeaderMode={setHeaderMode}
        />
        <MappingPanel
          metadataItems={metadataItems}
          metadataId={metadataId}
          onMetadata={handleMetadata}
          metadataName={metadataName}
          tables={tables}
          tableMappings={tableMappings}
          onToggleTable={toggleTable}
          onPatchTable={patchTable}
          onAutoMapTable={autoMapTable}
          sourceColumns={sourceColumns}
          mappingItems={mappingItems}
          mappingGroups={mappingGroups}
          mappingName={mappingName}
          onMappingName={setMappingName}
          currentMappingRef={currentMappingRef}
          currentGroupName={currentGroupName}
          onSave={handleSaveMapping}
          onLoad={handleLoadMapping}
          onDelete={handleDeleteMapping}
          busy={busy}
          presets={presets}
          onSavePreset={handleSavePreset}
          onDeletePreset={handleDeletePreset}
          onApplyPreset={handleApplyPreset}
        />
        <InsertOutput
          result={shownResult}
          options={options}
          onOptions={handleOptions}
          fileBaseName={fileBaseName}
          dataRowCount={rowsFromStart.length}
          onStatus={onStatus}
        />
      </main>
    </div>
  );
}

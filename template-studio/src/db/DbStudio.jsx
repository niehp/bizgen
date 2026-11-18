import React, { useCallback, useEffect, useMemo, useState } from 'react';
import {
  listDbDrivers, testDbConnection, extractDbMetadata,
  listDbMetadata, getDbMetadata, saveDbMetadata, deleteDbMetadata,
} from '../api.js';
import {
  defaultConnection, normalizeMetadata, autoJoinPlan, findTable, dialectInfo,
  DEFAULT_PLACEHOLDER_STYLE, APP_DB_DIALECT,
} from '../../shared/dbmeta.js';
import {
  buildSelectJoin, buildUpdate, buildDelete, newCondition,
} from '../../shared/sqlbuilder.js';
import { defaultSqlFileName } from '../../shared/sqlfile.js';
import ConnectionBar from './ConnectionBar.jsx';
import MetadataPanel from './MetadataPanel.jsx';
import SqlConfig from './SqlConfig.jsx';
import SqlOutput from './SqlOutput.jsx';

const EMPTY_RESULT = { sql: '', warnings: [], stats: { lines: 0, chars: 0 }, plan: null };

const KINDS = [
  { id: 'join', label: '複数テーブル結合 SELECT' },
  { id: 'update', label: 'UPDATE' },
  { id: 'delete', label: 'DELETE' },
];
const KIND_HINT = {
  join: '複数テーブルにチェック → 結合キーを自動推定 → 出力列を選択',
  update: '対象テーブルを 1 つ → SET は全列が既定（不要な列だけ解除）→ 条件列（WHERE）を選択',
  delete: '対象テーブルを 1 つ → 条件列（WHERE）を選択',
};

const DEFAULT_OPTIONS = {
  placeholderStyle: DEFAULT_PLACEHOLDER_STYLE,
  quoteIdentifiers: false,
  includeSchema: true,
  addComments: false,
  columnAlias: true,
};

const firstKey = (table) => table?.primaryKey?.[0] || table?.columns?.[0]?.name || '';

function seedConfigs(metadata) {
  const tables = metadata.tables || [];
  const first = tables[0];
  // UPDATE の SET は指定書式どおり「表の全列」を既定で設定する（列数上限なし）
  const allColumns = (first?.columns || []).map((c) => c.name);
  return {
    joinCfg: {
      tableIds: tables.slice(0, 2).map((t) => t.id),
      select: {},
      joinTypes: {},
      onOverrides: {},
      where: [],
      orderBy: '',
      distinct: false,
    },
    updateCfg: {
      tableId: first?.id || '',
      setColumns: allColumns,
      setValues: {},
      where: first ? [newCondition(firstKey(first), '=')] : [],
    },
    deleteCfg: {
      tableId: first?.id || '',
      where: first ? [newCondition(firstKey(first), '=')] : [],
    },
  };
}

export default function DbStudio({ onStatus }) {
  const [drivers, setDrivers] = useState([]);
  const [items, setItems] = useState([]);
  const [connection, setConnection] = useState(() => defaultConnection(APP_DB_DIALECT));
  const [metadata, setMetadata] = useState(null);
  const [sourceId, setSourceId] = useState('');
  const [activeTableId, setActiveTableId] = useState('');
  const [sqlKind, setSqlKind] = useState('join');
  const [busy, setBusy] = useState('');
  const [options, setOptions] = useState(DEFAULT_OPTIONS);

  const [joinCfg, setJoinCfg] = useState({ tableIds: [], select: {}, joinTypes: {}, onOverrides: {}, where: [], orderBy: '', distinct: false });
  const [updateCfg, setUpdateCfg] = useState({ tableId: '', setColumns: [], setValues: {}, where: [] });
  const [deleteCfg, setDeleteCfg] = useState({ tableId: '', where: [] });

  const refreshItems = useCallback(async () => {
    try {
      const r = await listDbMetadata();
      setItems(r.items || []);
    } catch (e) {
      onStatus({ type: 'error', text: e.message });
    }
  }, [onStatus]);

  const applyMetadata = useCallback((raw, srcId, message) => {
    const meta = normalizeMetadata(raw);
    if (!meta.tables.length) throw new Error('テーブルが 1 件もないメタデータです');
    setMetadata(meta);
    const seed = seedConfigs(meta);
    setJoinCfg(seed.joinCfg);
    setUpdateCfg(seed.updateCfg);
    setDeleteCfg(seed.deleteCfg);
    setActiveTableId(meta.tables[0].id);
    setSourceId(srcId || meta.id || '');
    onStatus({ type: 'ok', text: message });
  }, [onStatus]);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const [d, list] = await Promise.all([listDbDrivers(), listDbMetadata()]);
        if (cancelled) return;
        setDrivers(d.drivers || []);
        const items = list.items || [];
        setItems(items);
        // 内蔵サンプルは廃止。保存済みメタデータがあれば先頭を自動読込。
        if (items.length) {
          try {
            const r = await getDbMetadata(items[0].id);
            if (!cancelled) applyMetadata(r.metadata, items[0].id, `メタデータ「${r.metadata.name || items[0].id}」を読み込みました`);
          } catch (e) {
            if (!cancelled) onStatus({ type: 'info', text: '保存済みメタデータの読込に失敗しました。一覧から選択してください' });
          }
        } else if (!cancelled) {
          onStatus({ type: 'info', text: 'DB に接続してメタデータを抽出するか、JSON をアップロードしてください' });
        }
      } catch (e) {
        if (!cancelled) onStatus({ type: 'error', text: `DB API に接続できません: ${e.message}` });
      }
    })();
    return () => { cancelled = true; };
  }, [applyMetadata, onStatus]);

  /* ---------------- 接続 / JSON ---------------- */
  const onConnectionChange = useCallback((patch) => {
    setConnection((prev) => ({ ...prev, ...patch }));
  }, []);

  const handleTest = async () => {
    setBusy('test');
    try {
      const r = await testDbConnection(connection);
      onStatus({ type: 'ok', text: `接続成功（${dialectInfo(r.dialect).label}）: ${String(r.version).slice(0, 90)}` });
    } catch (e) {
      onStatus({ type: 'error', text: `接続失敗: ${e.message}` });
    } finally { setBusy(''); }
  };

  const handleExtract = async () => {
    setBusy('extract');
    try {
      const name = `${dialectInfo(APP_DB_DIALECT).label} メタデータ`;
      const r = await extractDbMetadata(connection, name, true);
      applyMetadata(r.metadata, r.saved?.id || '', `抽出完了: ${r.metadata.tables.length} テーブル / JSON 保存 ${r.saved?.id || ''}`);
      await refreshItems();
    } catch (e) {
      onStatus({ type: 'error', text: `抽出失敗: ${e.message}` });
    } finally { setBusy(''); }
  };

  const handleLoadItem = async (id) => {
    if (!id) return;
    try {
      const r = await getDbMetadata(id);
      applyMetadata(r.metadata, id, `${r.metadata.name} を JSON から読み込みました`);
    } catch (e) { onStatus({ type: 'error', text: e.message }); }
  };

  const handleDeleteItem = async (id) => {
    if (!id || !window.confirm(`${id} を削除しますか？`)) return;
    try {
      await deleteDbMetadata(id);
      if (sourceId === id) setSourceId('');
      await refreshItems();
      onStatus({ type: 'ok', text: `${id} を削除しました` });
    } catch (e) { onStatus({ type: 'error', text: e.message }); }
  };

  const handleUpload = async (parsed, filename) => {
    applyMetadata(parsed, '', `${filename} を読み込みました`);
  };

  const handleSaveSnapshot = async (name) => {
    if (!metadata) return;
    try {
      const r = await saveDbMetadata({ ...metadata, name }, undefined);
      setSourceId(r.id);
      await refreshItems();
      onStatus({ type: 'ok', text: `メタデータを ${r.id}.json に保存しました` });
    } catch (e) { onStatus({ type: 'error', text: e.message }); }
  };

  /* ---------------- 生成 ---------------- */
  const effectiveDialect = APP_DB_DIALECT;
  const baseCfg = { ...options, dialect: effectiveDialect };

  const plan = useMemo(
    () => (metadata ? autoJoinPlan(metadata, joinCfg.tableIds) : null),
    [metadata, joinCfg.tableIds],
  );

  const results = useMemo(() => {
    if (!metadata) return { join: EMPTY_RESULT, update: EMPTY_RESULT, delete: EMPTY_RESULT };
    return {
      join: buildSelectJoin(metadata, { ...baseCfg, ...joinCfg }),
      update: buildUpdate(metadata, { ...baseCfg, ...updateCfg }),
      delete: buildDelete(metadata, { ...baseCfg, ...deleteCfg }),
    };
    // baseCfg は options / dialect から毎回作られる
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [metadata, options, effectiveDialect, joinCfg, updateCfg, deleteCfg]);

  const result = results[sqlKind] || EMPTY_RESULT;
  const where = sqlKind === 'join' ? joinCfg.where : sqlKind === 'update' ? updateCfg.where : deleteCfg.where;
  const setWhere = useCallback((list) => {
    if (sqlKind === 'join') setJoinCfg((prev) => ({ ...prev, where: list }));
    else if (sqlKind === 'update') setUpdateCfg((prev) => ({ ...prev, where: list }));
    else setDeleteCfg((prev) => ({ ...prev, where: list }));
  }, [sqlKind]);

  const columnOptions = useMemo(() => {
    if (sqlKind === 'join') {
      return (plan?.tables || []).flatMap((t) => {
        const a = plan.aliases[t.id];
        return t.columns.map((c) => ({
          value: `${a}.${c.name}`, label: `${a}.${c.name}`, comment: c.comment, type: c.type,
        }));
      });
    }
    const t = findTable(metadata, sqlKind === 'update' ? updateCfg.tableId : deleteCfg.tableId);
    return (t?.columns || []).map((c) => ({
      value: c.name, label: c.name, comment: c.comment, type: c.type,
    }));
  }, [sqlKind, plan, metadata, updateCfg.tableId, deleteCfg.tableId]);

  const allColumnOptions = useMemo(() => (plan?.tables || []).flatMap((t) => {
    const a = plan.aliases[t.id];
    return t.columns.map((c) => ({
      value: `${a}.${c.name}`, label: `${a}.${c.name}`, comment: c.comment, type: c.type,
    }));
  }), [plan]);

  /* ---------------- 選択操作 ---------------- */
  const toggleJoinTable = useCallback((id) => {
    setJoinCfg((prev) => {
      const removing = prev.tableIds.includes(id);
      const tableIds = removing ? prev.tableIds.filter((x) => x !== id) : [...prev.tableIds, id];
      let whereList = prev.where;
      const oldAlias = plan?.aliases?.[id];
      if (removing && oldAlias) {
        const prefix = `${oldAlias}.`;
        whereList = whereList.filter((c) => !String(c.column).startsWith(prefix));
      }
      // インデックス依存の手動設定は選択変更時に自動推定へ戻す
      return { ...prev, tableIds, where: whereList, onOverrides: {}, joinTypes: {} };
    });
  }, [plan]);

  const selectTarget = useCallback((id) => {
    const t = findTable(metadata, id);
    const key = firstKey(t);
    if (sqlKind === 'update') {
      setUpdateCfg((prev) => ({
        ...prev,
        tableId: id,
        setColumns: (t?.columns || []).map((c) => c.name),
        setValues: {},
        where: key ? [newCondition(key, '=')] : [],
      }));
    } else {
      setDeleteCfg((prev) => ({ ...prev, tableId: id, where: key ? [newCondition(key, '=')] : [] }));
    }
    setActiveTableId(id);
  }, [metadata, sqlKind]);

  const toggleSelectColumn = useCallback((tableId, name) => {
    setJoinCfg((prev) => {
      const t = findTable(metadata, tableId);
      const cur = prev.select[tableId] ?? (t?.columns || []).map((c) => c.name);
      const next = cur.includes(name) ? cur.filter((x) => x !== name) : [...cur, name];
      return { ...prev, select: { ...prev.select, [tableId]: next } };
    });
  }, [metadata]);

  const toggleSetColumn = useCallback((name) => {
    setUpdateCfg((prev) => ({
      ...prev,
      setColumns: prev.setColumns.includes(name)
        ? prev.setColumns.filter((x) => x !== name)
        : [...prev.setColumns, name],
    }));
  }, []);

  /** SET 列の一括選択 / 一括解除（表示件数に関係なく表の全列に適用） */
  const setAllSetColumns = useCallback((enabled) => {
    const t = findTable(metadata, updateCfg.tableId);
    setUpdateCfg((prev) => ({
      ...prev,
      setColumns: enabled ? (t?.columns || []).map((c) => c.name) : [],
    }));
  }, [metadata, updateCfg.tableId]);

  /** 列一覧の SELECT 全選択 / 全解除（選択中テーブルの全列に適用・列数無制限） */
  const setAllSelectColumns = useCallback((enabled) => {
    const t = findTable(metadata, activeTableId);
    if (!t) return;
    setJoinCfg((prev) => ({
      ...prev,
      select: { ...prev.select, [t.id]: enabled ? t.columns.map((c) => c.name) : [] },
    }));
  }, [metadata, activeTableId]);

  /** 列一覧の 条件 全選択 / 全解除（選択中テーブルの全列に適用・列数無制限） */
  const setAllWhereColumns = useCallback((enabled) => {
    const t = findTable(metadata, activeTableId);
    if (!t) return;
    const a = sqlKind === 'join' ? plan?.aliases?.[activeTableId] : '';
    const refs = t.columns.map((c) => (a ? `${a}.${c.name}` : c.name));
    const setter = sqlKind === 'join' ? setJoinCfg : sqlKind === 'update' ? setUpdateCfg : setDeleteCfg;
    setter((prev) => {
      const cur = prev.where || [];
      if (!enabled) {
        const remove = new Set(refs);
        return { ...prev, where: cur.filter((c) => !remove.has(c.column)) };
      }
      const have = new Set(cur.map((c) => c.column));
      const add = refs.filter((r) => !have.has(r)).map((r) => newCondition(r, '='));
      return { ...prev, where: [...cur, ...add] };
    });
  }, [metadata, activeTableId, sqlKind, plan]);

  /** 表一覧の「選択中テーブルをクリア」: 選択とそれに紐づく設定をまとめて解除する */
  const clearSelectedTables = useCallback(() => {
    if (sqlKind === 'join') {
      setJoinCfg((prev) => ({
        ...prev,
        tableIds: [], select: {}, where: [], joinTypes: {}, onOverrides: {}, orderBy: '',
      }));
    } else if (sqlKind === 'update') {
      setUpdateCfg((prev) => ({ ...prev, tableId: '', setColumns: [], setValues: {}, where: [] }));
    } else {
      setDeleteCfg((prev) => ({ ...prev, tableId: '', where: [] }));
    }
  }, [sqlKind]);

  const toggleWhereColumn = useCallback((kind, ref) => {
    const setter = kind === 'join' ? setJoinCfg : kind === 'update' ? setUpdateCfg : setDeleteCfg;
    setter((prev) => {
      const cur = prev.where || [];
      return {
        ...prev,
        where: cur.some((c) => c.column === ref)
          ? cur.filter((c) => c.column !== ref)
          : [...cur, newCondition(ref, '=')],
      };
    });
  }, []);

  // 2 万列超でも描画が O(n^2) にならないよう、チェック判定は Set に索引化する
  const selectSets = useMemo(() => Object.fromEntries(
    Object.entries(joinCfg.select).map(([id, arr]) => [id, new Set(arr)]),
  ), [joinCfg.select]);
  const isSelectChecked = useCallback((tableId, name) => {
    const set = selectSets[tableId];
    return set ? set.has(name) : true;
  }, [selectSets]);

  const setSet = useMemo(() => new Set(updateCfg.setColumns), [updateCfg.setColumns]);
  const isSetChecked = useCallback((name) => setSet.has(name), [setSet]);
  const isWhereChecked = useCallback((ref) => where.some((c) => c.column === ref), [where]);

  const onJoinType = useCallback((idx, type) => {
    setJoinCfg((prev) => ({ ...prev, joinTypes: { ...prev.joinTypes, [idx]: type } }));
  }, []);
  const onOnPairs = useCallback((idx, pairs) => {
    setJoinCfg((prev) => ({ ...prev, onOverrides: { ...prev.onOverrides, [idx]: pairs } }));
  }, []);
  const onResetOn = useCallback((idx) => {
    setJoinCfg((prev) => {
      const next = { ...prev.onOverrides };
      delete next[idx];
      return { ...prev, onOverrides: next };
    });
  }, []);
  const onJoinPatch = useCallback((patch) => setJoinCfg((prev) => ({ ...prev, ...patch })), []);
  const onSetOrderBy = useCallback((v) => setJoinCfg((prev) => ({ ...prev, orderBy: v })), []);
  const onSetValues = useCallback((map) => setUpdateCfg((prev) => ({ ...prev, setValues: map })), []);
  const onOptions = useCallback((patch) => setOptions((prev) => ({ ...prev, ...patch })), []);

  const targetTableId = sqlKind === 'update' ? updateCfg.tableId : deleteCfg.tableId;
  const defaultFileName = useMemo(
    () => defaultSqlFileName(sqlKind, sqlKind === 'join' ? joinCfg.tableIds[0] : targetTableId),
    [sqlKind, joinCfg.tableIds, targetTableId],
  );

  return (
    <div className="db-page">
      <ConnectionBar
        connection={connection}
        onConnectionChange={onConnectionChange}
        drivers={drivers}
        items={items}
        sourceId={sourceId}
        metadata={metadata}
        busy={busy}
        onTest={handleTest}
        onExtract={handleExtract}
        onLoadItem={handleLoadItem}
        onDeleteItem={handleDeleteItem}
        onUpload={handleUpload}
        onSaveSnapshot={handleSaveSnapshot}
        onStatus={onStatus}
      />

      <div className="db-kind-bar">
        <span className="db-row-label">生成する SQL</span>
        {KINDS.map((k) => (
          <button
            key={k.id}
            type="button"
            className={`nav-pill ${sqlKind === k.id ? 'active' : ''}`}
            onClick={() => setSqlKind(k.id)}
          >{k.label}</button>
        ))}
        <span className="db-kind-hint">{KIND_HINT[sqlKind]}</span>
      </div>

      <main className="workspace workspace-db">
        <MetadataPanel
          metadata={metadata}
          activeTableId={activeTableId}
          onActiveTable={setActiveTableId}
          sqlKind={sqlKind}
          joinTableIds={joinCfg.tableIds}
          onToggleJoinTable={toggleJoinTable}
          onClearSelectedTables={clearSelectedTables}
          targetTableId={targetTableId}
          onSelectTarget={selectTarget}
          isSelectChecked={isSelectChecked}
          onToggleSelectColumn={toggleSelectColumn}
          isSetChecked={isSetChecked}
          onToggleSetColumn={toggleSetColumn}
          onSetAllColumns={setAllSetColumns}
          onSetAllSelectColumns={setAllSelectColumns}
          onSetAllWhereColumns={setAllWhereColumns}
          isWhereChecked={isWhereChecked}
          onToggleWhereColumn={toggleWhereColumn}
          plan={plan}
        />
        <SqlConfig
          metadata={metadata}
          sqlKind={sqlKind}
          options={options}
          onOptions={onOptions}
          plan={plan}
          joinCfg={joinCfg}
          onJoinType={onJoinType}
          onOnPairs={onOnPairs}
          onResetOn={onResetOn}
          onRemoveJoinTable={toggleJoinTable}
          onJoinPatch={onJoinPatch}
          onSetOrderBy={onSetOrderBy}
          updateCfg={updateCfg}
          onSetValues={onSetValues}
          conditions={where}
          onConditions={setWhere}
          columnOptions={columnOptions}
          allColumnOptions={allColumnOptions}
        />
        <SqlOutput
          result={result}
          sqlKind={sqlKind}
          onStatus={onStatus}
          defaultFileName={defaultFileName}
        />
      </main>
    </div>
  );
}

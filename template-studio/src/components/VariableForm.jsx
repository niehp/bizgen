import React, { useState } from 'react';
import {
  TRANSFORM_CATALOG,
  TRANSFORM_GROUPS,
  TRANSFORM_MAP,
  toBaseValue,
  applyFilters,
  normalizeSequence,
  buildPlaceholder,
  firstCharCase,
  FIRST_CASE_LABEL,
} from '../../shared/transforms.js';
import { applyCaseVariant } from '../../shared/casevars.js';
import { importTableFile } from '../api.js';
import {
  GENERATOR_MODES,
  VALUE_SEPARATORS,
  MAX_ITEMS,
  normalizeGenerator,
  generatorItems,
  expansionItems,
  parseValues,
  joinValues,
  separatorChar,
  TABLE_MAX_ROWS,
  tableColumns,
  blankTableRow,
  normalizeTable,
  parsePastedTable,
  mapTableRows,
  looksLikeHeader,
  normalizeHeader,
  buildRangeRows,
} from '../../shared/expansion.js';

const JOINERS = [
  { label: '改行', value: '\n' },
  { label: 'カンマ + 空白', value: ', ' },
  { label: 'カンマ', value: ',' },
  { label: '区切りなし', value: '' },
];

async function copyText(text) {
  try {
    await navigator.clipboard.writeText(text);
    return true;
  } catch {
    return false;
  }
}

/* ---------------- 連番（sequence 型） ---------------- */

function SequenceControl({ variable, value, onChange }) {
  const cfg = normalizeSequence(value, variable);
  const raw = value && typeof value === 'object' && !Array.isArray(value) ? value : {};
  const set = (key, val) => onChange({ ...raw, [key]: val });
  const numField = (key, label, extra = {}) => (
    <label className="seq-item" key={key}>
      <span>{label}</span>
      <input
        className="control"
        type="number"
        value={cfg[key]}
        onChange={(e) => set(key, e.target.value === '' ? '' : Number(e.target.value))}
        {...extra}
      />
    </label>
  );

  return (
    <div className="seq-grid">
      {numField('start', '開始')}
      {numField('end', '終了')}
      {numField('step', '増分', { min: 1 })}
      {numField('pad', '桁数', { min: 0, max: 20 })}
      <label className="seq-item">
        <span>区切り</span>
        <select className="control" value={cfg.joiner} onChange={(e) => set('joiner', e.target.value)}>
          {JOINERS.map((j) => <option key={j.label} value={j.value}>{j.label}</option>)}
          {!JOINERS.some((j) => j.value === cfg.joiner) && (
            <option value={cfg.joiner}>（カスタム）</option>
          )}
        </select>
      </label>
      <div className="seq-preview">
        <span>連番プレビュー</span>
        <code>{toBaseValue(variable, cfg).text.split('\n').slice(0, 8).join(' / ')}</code>
      </div>
    </div>
  );
}

/* ---------------- 先頭文字（判定 + 自動生成変数） ---------------- */

function FirstCaseBadge({ value }) {
  const kind = firstCharCase(value);
  return <span className={`case-badge case-${kind}`} title="先頭文字の判定">{FIRST_CASE_LABEL[kind]}</span>;
}

function FirstCaseBar({ name, value, expanded, derived }) {
  const [copied, setCopied] = useState('');
  const kind = expanded ? 'other' : firstCharCase(value);
  const badge = expanded ? '各件で判定' : FIRST_CASE_LABEL[kind];
  const buttons = [
    ...(derived || []).map((d) => ({
      id: d.variant,
      label: d.label,
      expr: d.placeholder,
    })),
    { id: 'C', label: '判定で分岐', expr: `{{${name} | ifUpperFirst}}` },
  ];
  const run = async (b) => {
    if (await copyText(b.expr)) {
      setCopied(b.id);
      window.setTimeout(() => setCopied(''), 1500);
    }
  };

  return (
    <div className="case-bar">
      <span className="case-label">先頭文字</span>
      <span className={`case-badge case-${kind}`}>{badge}</span>
      <span className="case-actions">
        {buttons.map((b) => (
          <button
            type="button"
            className="link-btn"
            key={b.id}
            title={b.expr}
            onClick={() => run(b)}
          >
            {copied === b.id ? '✓ コピー' : b.label}
          </button>
        ))}
      </span>
    </div>
  );
}

/** 1 つの変数から自動生成された {{nameUpper}} / {{nameLower}} を一覧表示する */
function CaseVarsBox({ variable, value, expanded }) {
  const [copied, setCopied] = useState('');
  const defs = variable.derived || [];
  if (!defs.length) return null;
  const base = toBaseValue(variable, value);
  const run = async (d) => {
    if (await copyText(d.placeholder)) {
      setCopied(d.name);
      window.setTimeout(() => setCopied(''), 1500);
    }
  };

  return (
    <div className="casevars-box">
      <div className="casevars-head">
        <span className="casevars-title">自動生成変数</span>
        <span className="casevars-hint">
          {expanded ? '各インスタンスの値から自動計算' : 'この入力値から自動計算・追加入力は不要'}
        </span>
      </div>
      {defs.map((d) => (
        <div className="casevar-row" key={d.name}>
          <code className="casevar-name">{d.placeholder}</code>
          <span className={`case-badge case-${['upper', 'pascal', 'upperSnake'].includes(d.variant) ? 'upper' : 'lower'}`}>{d.label}</span>
          <code className="casevar-value">
            {expanded ? '（各件で算出）' : (applyCaseVariant(base.text, d.filter) || '（空）')}
          </code>
          <button type="button" className="link-btn" onClick={() => run(d)} title={d.placeholder}>
            {copied === d.name ? '✓ コピー' : '{{ }}'}
          </button>
        </div>
      ))}
    </div>
  );
}

/* ---------------- 1 変数 → N インスタンス（単一値 / 数字範囲 / 複数値リスト） ---------------- */

function RangePanel({ c, items, derived, patch }) {
  const setText = (key) => (e) => patch({ [key]: e.target.value });
  const setNum = (key) => (e) => patch({ [key]: e.target.value === '' ? '' : Number(e.target.value) });
  const setItemValue = (i, v) => patch({ values: { ...c.values, [String(i)]: v } });
  const fillAuto = () => {
    const values = {};
    for (const it of items) values[String(it.index)] = it.auto;
    patch({ values });
  };

  return (
    <>
      <div className="exp-grid">
        <label className="seq-item"><span>開始</span><input className="control" type="number" value={c.from} onChange={setNum('from')} /></label>
        <label className="seq-item"><span>終了</span><input className="control" type="number" value={c.to} onChange={setNum('to')} /></label>
        <label className="seq-item"><span>増分</span><input className="control" type="number" min="1" value={c.step} onChange={setNum('step')} /></label>
        <label className="seq-item"><span>桁数</span><input className="control" type="number" min="0" max="20" value={c.pad} onChange={setNum('pad')} /></label>
        <label className="seq-item"><span>接頭辞</span><input className="control" type="text" value={c.prefix} onChange={setText('prefix')} placeholder="field" /></label>
        <label className="seq-item"><span>接尾辞</span><input className="control" type="text" value={c.suffix} onChange={setText('suffix')} placeholder="Dto" /></label>
      </div>

      <div className="exp-list-head">
        <span>各インスタンスの値（未入力なら連番を自動採番）</span>
        <button type="button" className="link-btn" onClick={fillAuto}>連番で自動入力</button>
      </div>
      <div className="exp-list">
        {items.map((it) => (
          <div className="exp-item" key={it.index}>
            <span className="exp-idx">{it.label}</span>
            <input
              className="control"
              type="text"
              value={it.value}
              placeholder={it.auto}
              onChange={(e) => setItemValue(it.index, e.target.value)}
            />
            <FirstCaseBadge value={it.value} />
            {derived.map((d) => (
              <code className="exp-derived" key={d.name} title={d.placeholder}>
                {applyCaseVariant(it.value, d.filter)}
              </code>
            ))}
          </div>
        ))}
      </div>
    </>
  );
}

function ValuesPanel({ c, items, derived, patch }) {
  const [bulk, setBulk] = useState(false);
  // 編集用は空行も残す（新しい行に入力できるようにする）。生成用は generatorItems 側で空行を除外する。
  const rows = parseValues(c.text, c.separator, { trim: false, skipEmpty: false });
  const sepId = c.separatorId
    || VALUE_SEPARATORS.find((s) => s.value === c.separator)?.id
    || 'newline';

  const setRow = (i, v) => {
    const next = [...rows];
    next[i] = v;
    patch({ text: joinValues(next, c.separator) });
  };
  const addRow = () => patch({ text: joinValues([...rows, ''], c.separator) });
  const delRow = (i) => patch({ text: joinValues(rows.filter((_, j) => j !== i), c.separator) });
  const setSep = (id) => {
    const char = separatorChar(id);
    patch({ separator: char, separatorId: id, text: joinValues(rows, char) });
  };

  return (
    <>
      <div className="gen-options">
        <label className="seq-item">
          <span>区切り</span>
          <select className="control" value={sepId} onChange={(e) => setSep(e.target.value)}>
            {VALUE_SEPARATORS.map((s) => (
              <option key={s.id} value={s.id}>{s.label}</option>
            ))}
          </select>
        </label>
        <label className="gen-check">
          <input type="checkbox" checked={c.skipEmpty} onChange={(e) => patch({ skipEmpty: e.target.checked })} />
          <span>空行を除外</span>
        </label>
        <label className="gen-check">
          <input type="checkbox" checked={c.trim} onChange={(e) => patch({ trim: e.target.checked })} />
          <span>前後の空白を除去</span>
        </label>
        <button type="button" className="link-btn" onClick={() => setBulk((v) => !v)}>
          {bulk ? '一括入力を閉じる' : 'テキストで一括入力'}
        </button>
      </div>

      {bulk && (
        <textarea
          className="control control-area mono gen-bulk"
          rows={5}
          spellCheck={false}
          value={c.text}
          placeholder="1 行に 1 つの値。まとめて貼り付けできます"
          onChange={(e) => patch({ text: e.target.value })}
        />
      )}

      <div className="exp-list-head">
        <span>値の一覧（1 行 = 1 件のコード）</span>
        <span className="gen-count">{items.length} 件生成 {items.length >= MAX_ITEMS ? `（上限 ${MAX_ITEMS} 件）` : ''}</span>
      </div>
      <div className="exp-list gen-list">
        {rows.map((v, i) => (
          <div className="exp-item" key={i}>
            <span className="exp-idx">{i + 1}</span>
            <input
              className="control"
              type="text"
              value={v}
              placeholder="値を入力"
              onChange={(e) => setRow(i, e.target.value)}
            />
            <FirstCaseBadge value={v} />
            {derived.map((d) => (
              <code className="exp-derived" key={d.name} title={d.placeholder}>
                {applyCaseVariant(v, d.filter)}
              </code>
            ))}
            <button
              type="button"
              className="link-btn gen-del"
              title="この行を削除"
              onClick={() => delRow(i)}
            >
              ×
            </button>
          </div>
        ))}
      </div>
      <div className="gen-actions">
        <button type="button" className="link-btn" onClick={addRow} disabled={rows.length >= MAX_ITEMS}>
          ＋ 行を追加
        </button>
      </div>
    </>
  );
}

function GeneratorBox({ variable, cfg, onChange }) {
  const c = normalizeGenerator(cfg, variable);
  const items = generatorItems(c);
  const derived = variable.derived || [];
  const patch = (p) => onChange({ ...c, ...p });
  const setMode = (mode) => patch({ mode, enabled: mode !== 'single' });
  const modeDef = GENERATOR_MODES.find((m) => m.id === c.mode) || GENERATOR_MODES[0];

  return (
    <div className="exp-box">
      <div className="gen-head">
        <span className="gen-title">値の入力モード</span>
        <select
          className="control gen-mode"
          value={c.mode}
          onChange={(e) => setMode(e.target.value)}
        >
          {GENERATOR_MODES.map((m) => (
            <option key={m.id} value={m.id}>{m.label}</option>
          ))}
        </select>
        {c.mode !== 'single' && <span className="exp-count">{items.length} 件</span>}
      </div>
      <p className="gen-hint">{modeDef.hint}</p>

      {c.mode === 'range' && (
        <RangePanel c={c} items={expansionItems(c)} derived={derived} patch={patch} />
      )}
      {c.mode === 'values' && (
        <ValuesPanel c={c} items={items} derived={derived} patch={patch} />
      )}
      {c.mode !== 'single' && (
        <p className="exp-note">
          右パネルに {items.length} 件のコードをすべて表示します。生成モードを持てる変数は同時に 1 つです。
          各行の右側は自動生成変数の値です。
        </p>
      )}
    </div>
  );
}

/* ---------------- TABLE 入力（1 変数 = 1 列 / 1 行 = 1 件） ---------------- */

const RANGE_DEFAULT = { column: '', from: 1, to: 10, step: 1, pad: 0, prefix: '', suffix: '' };

const IMPORT_ACCEPT = '.xlsx,.xlsm,.csv,.tsv,.txt,.json,.md,.markdown';

/* ファイル → 行データ → 変数列への割り当て */
function FileImportPanel({ columns, rows, setRows, notify, onImportValues }) {
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [skipRows, setSkipRows] = useState(0);
  const [headerMode, setHeaderMode] = useState('auto');

  const readFile = (fileObj) => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('ファイルを読み込めませんでした'));
    reader.readAsDataURL(fileObj);
  });

  const onPick = async (e) => {
    const picked = e.target.files?.[0];
    e.target.value = '';
    if (!picked) return;
    setLoading(true);
    setError('');
    try {
      const data = await readFile(picked);
      const r = await importTableFile(picked.name, data);
      setFile({ ...r, data });
      setSkipRows(0);
      setHeaderMode('auto');
    } catch (err) {
      setError(err.message || String(err));
      setFile(null);
    } finally {
      setLoading(false);
    }
  };

  const changeSheet = async (sheet) => {
    if (!file) return;
    setLoading(true);
    setError('');
    try {
      const r = await importTableFile(file.filename, file.data, sheet);
      setFile({ ...r, data: file.data });
    } catch (err) {
      setError(err.message || String(err));
    } finally {
      setLoading(false);
    }
  };

  const visible = (file?.rows || []).slice(Math.max(0, Number(skipRows) || 0));
  const autoHeader = file?.header?.length
    ? false
    : looksLikeHeader(visible[0] || [], columns);
  const useHeader = headerMode === 'on' ? true : headerMode === 'off' ? false : autoHeader;
  const header = file?.header?.length ? file.header : (useHeader ? (visible[0] || []) : []);
  const dataRows = file?.header?.length ? visible : (useHeader ? visible.slice(1) : visible);
  const mapped = file ? mapTableRows(header, dataRows, columns) : [];
  const headerNames = header.map(normalizeHeader);

  const apply = (append) => {
    if (!mapped.length) return;
    setRows(append ? [...rows, ...mapped] : mapped);
    notify(`${mapped.length} 行を${append ? '追加' : '取り込み'}ました`);
  };

  return (
    <div className="table-tool-panel import-panel">
      <div className="import-file-row">
        <label className="btn btn-sm import-file-btn">
          {loading ? '読み込み中…' : (file ? '別のファイルを選択' : 'ファイルを選択')}
          <input type="file" accept={IMPORT_ACCEPT} onChange={onPick} disabled={loading} />
        </label>
        <span className="import-file-name">
          {file ? `${file.filename}（${file.rowCount} 行 / ${file.kind}）` : 'xlsx / csv / tsv / txt / json / md に対応'}
        </span>
        {file && (
          <button type="button" className="link-btn" onClick={() => { setFile(null); setError(''); }}>
            クリア
          </button>
        )}
      </div>

      {error && <div className="alert alert-danger import-error">{error}</div>}

      {file && (
        <>
          <div className="import-options">
            {file.sheetNames.length > 1 && (
              <label className="seq-item"><span>シート</span>
                <select className="control" value={file.sheet} onChange={(e) => changeSheet(e.target.value)} disabled={loading}>
                  {file.sheetNames.map((s) => <option key={s} value={s}>{s}</option>)}
                </select>
              </label>
            )}
            <label className="seq-item"><span>先頭から除外</span>
              <input className="control" type="number" min="0" max="50" value={skipRows}
                onChange={(e) => setSkipRows(e.target.value === '' ? 0 : Number(e.target.value))} />
            </label>
            {!file.header.length && (
              <label className="seq-item"><span>1 行目</span>
                <select className="control" value={headerMode} onChange={(e) => setHeaderMode(e.target.value)}>
                  <option value="auto">自動判定（{autoHeader ? 'ヘッダー' : 'データ'}）</option>
                  <option value="on">変数名のヘッダー</option>
                  <option value="off">データ行</option>
                </select>
              </label>
            )}
            <span className="import-map-count">{mapped.length} 行 → 変数へ割り当て</span>
          </div>

          <div className="import-preview-wrap">
            <table className="tbl import-preview">
              <thead>
                <tr>
                  <th className="tbl-idx">#</th>
                  {columns.map((c) => <th key={c}>{c}{headerNames.includes(c) ? ' ✓' : ''}</th>)}
                </tr>
              </thead>
              <tbody>
                {mapped.slice(0, 4).map((row, i) => (
                  <tr key={i}>
                    <td className="tbl-idx">{i + 1}</td>
                    {columns.map((c) => <td key={c}>{row[c] || <span className="import-empty">（空）</span>}</td>)}
                  </tr>
                ))}
                {!mapped.length && (
                  <tr><td colSpan={columns.length + 1} className="import-empty">割り当てられる行がありません</td></tr>
                )}
              </tbody>
            </table>
          </div>

          {file.warnings.length > 0 && (
            <div className="import-warnings">{file.warnings.join(' / ')}</div>
          )}

          <div className="table-tool-actions">
            <button type="button" className="btn btn-sm" onClick={() => apply(false)} disabled={!mapped.length}>
              TABLE に反映（置換）
            </button>
            <button type="button" className="btn btn-sm" onClick={() => apply(true)} disabled={!mapped.length}>
              末尾に追加
            </button>
            <span className="table-tool-hint">列順: {columns.join(' | ')}。ヘッダー名が変数名と一致すれば列順は任意です。</span>
          </div>
        </>
      )}
    </div>
  );
}

function TableInput({ variables, values, table, onTableChange, onImportValues }) {
  const columns = tableColumns(variables);
  const byName = new Map(variables.map((v) => [v.name, v]));
  const displayColumns = columns.flatMap((name) => {
    const v = byName.get(name);
    return [
      { kind: 'input', key: name, name, label: v?.label || name },
      ...(v?.derived || []).map((d) => ({
        kind: 'derived', key: d.name, name: d.name,
        base: name, filter: d.filter, label: d.label, placeholder: d.placeholder,
      })),
    ];
  });
  const rows = table.rows.length ? table.rows : [blankTableRow(columns)];
  const generatedCount = normalizeTable({ enabled: true, rows }, variables).count;

  const [showPaste, setShowPaste] = useState(false);
  const [pasteText, setPasteText] = useState('');
  const [showImport, setShowImport] = useState(false);
  const [showRange, setShowRange] = useState(false);
  const [range, setRange] = useState(RANGE_DEFAULT);
  const [flash, setFlash] = useState('');
  const rangeColumn = columns.includes(range.column) ? range.column : (columns[0] || '');

  const setRows = (next) => onTableChange({ ...table, rows: next.slice(0, TABLE_MAX_ROWS) });
  const setCell = (rowIndex, name, value) =>
    setRows(rows.map((r, i) => (i === rowIndex ? { ...r, [name]: value } : r)));
  const addRow = () => setRows([...rows, blankTableRow(columns)]);
  const dupRow = (i) => setRows([...rows.slice(0, i + 1), { ...rows[i] }, ...rows.slice(i + 1)]);
  const delRow = (i) => setRows(rows.filter((_, j) => j !== i));
  const clearRows = () => setRows([blankTableRow(columns)]);
  const effective = (row, name) => {
    const own = String(row[name] ?? '').trim();
    return own || String(values[name] ?? '');
  };
  const notify = (text) => {
    setFlash(text);
    window.setTimeout(() => setFlash(''), 1600);
  };
  const applyPaste = (append) => {
    const parsed = parsePastedTable(pasteText, columns);
    if (!parsed.length) return;
    setRows(append ? [...rows, ...parsed] : parsed);
    notify(`${parsed.length} 行を${append ? '追加' : '反映'}しました`);
    setPasteText('');
  };
  const applyRange = (append) => {
    const built = buildRangeRows({ ...range, column: rangeColumn, base: rows[0] || {}, columns });
    if (!built.length) return;
    setRows(append ? [...rows, ...built] : built);
    notify(`${built.length} 行を生成しました`);
  };
  const setRangeField = (key, value) => setRange((r) => ({ ...r, [key]: value }));
  const numField = (key, label, extra = {}) => (
    <label className="seq-item" key={key}>
      <span>{label}</span>
      <input
        className="control" type="number"
        value={range[key]}
        onChange={(e) => setRangeField(key, e.target.value === '' ? '' : Number(e.target.value))}
        {...extra}
      />
    </label>
  );

  if (!columns.length) return null;

  return (
    <div className="table-box">
      <div className="table-head">
        <span className="table-title">TABLE 入力</span>
        <span className="table-sub">1 変数 = 1 列 / 1 行 = 1 件のコード</span>
        <span className="table-count">{rows.length} 行 → {generatedCount} 件</span>
      </div>

      <div className="table-tools">
        <button type="button" className="btn btn-sm" onClick={addRow} disabled={rows.length >= TABLE_MAX_ROWS}>
          ＋ 行を追加
        </button>
        <button type="button" className="link-btn" onClick={() => setShowRange((v) => !v)}>
          {showRange ? '範囲入力を閉じる' : '数字範囲で行を生成'}
        </button>
        <button type="button" className="link-btn" onClick={() => setShowImport((v) => !v)}>
          {showImport ? 'ファイル取込を閉じる' : 'ファイルからインポート'}
        </button>
        <button type="button" className="link-btn" onClick={() => setShowPaste((v) => !v)}>
          {showPaste ? '貼り付けを閉じる' : 'Excel から貼り付け'}
        </button>
        <button type="button" className="link-btn" onClick={clearRows}>表をクリア</button>
        {flash && <span className="table-flash">{flash}</span>}
      </div>

      {showImport && (
        <FileImportPanel
          columns={columns}
          rows={rows}
          setRows={setRows}
          notify={notify}
          onImportValues={onImportValues}
        />
      )}

      {showRange && (
        <div className="table-tool-panel">
          <div className="exp-grid table-range-grid">
            <label className="seq-item"><span>対象列</span>
              <select className="control" value={rangeColumn} onChange={(e) => setRangeField('column', e.target.value)}>
                {columns.map((c) => <option key={c} value={c}>{c}</option>)}
              </select>
            </label>
            {numField('from', '開始')}
            {numField('to', '終了')}
            {numField('step', '増分', { min: 1 })}
            {numField('pad', '桁数', { min: 0, max: 20 })}
            <label className="seq-item"><span>接頭辞</span>
              <input className="control" type="text" value={range.prefix} placeholder="field"
                onChange={(e) => setRangeField('prefix', e.target.value)} />
            </label>
            <label className="seq-item"><span>接尾辞</span>
              <input className="control" type="text" value={range.suffix} placeholder="Dto"
                onChange={(e) => setRangeField('suffix', e.target.value)} />
            </label>
          </div>
          <div className="table-tool-actions">
            <button type="button" className="btn btn-sm" onClick={() => applyRange(false)}>この範囲で表を置換</button>
            <button type="button" className="btn btn-sm" onClick={() => applyRange(true)}>末尾に追加</button>
            <span className="table-tool-hint">例: 1 〜 10 → 10 行 → 10 件のコード</span>
          </div>
        </div>
      )}

      {showPaste && (
        <div className="table-tool-panel">
          <textarea
            className="control control-area mono"
            rows={4}
            spellCheck={false}
            value={pasteText}
            placeholder={'Excel / スプレッドシートからコピーした表をそのまま貼り付け（タブ区切り）。\n列順: ' + columns.join(' | ')}
            onChange={(e) => setPasteText(e.target.value)}
          />
          <div className="table-tool-actions">
            <button type="button" className="btn btn-sm" onClick={() => applyPaste(false)}>表に反映（置換）</button>
            <button type="button" className="btn btn-sm" onClick={() => applyPaste(true)}>末尾に追加</button>
            <span className="table-tool-hint">列順: {columns.join(' | ')}（1 行目が変数名なら自動で除外）</span>
          </div>
        </div>
      )}

      <div className="table-scroll">
        <table className="tbl">
          <thead>
            <tr>
              <th className="tbl-idx">#</th>
              {displayColumns.map((c) => (
                <th key={c.key} className={c.kind === 'derived' ? 'tbl-derived-head' : ''} title={c.placeholder || ''}>
                  <div className="tbl-th-name">{`{{${c.name}}}`}</div>
                  <div className="tbl-th-label">
                    {c.kind === 'input' ? c.label : `${c.label}（自動生成）`}
                  </div>
                </th>
              ))}
              <th className="tbl-act">操作</th>
            </tr>
          </thead>
          <tbody>
            {rows.map((row, i) => (
              <tr key={i}>
                <td className="tbl-idx">{i + 1}</td>
                {displayColumns.map((c) => {
                  if (c.kind === 'input') {
                    const kind = firstCharCase(effective(row, c.name));
                    return (
                      <td key={c.key}>
                        <input
                          className="control tbl-cell" type="text" spellCheck={false}
                          value={row[c.name] ?? ''}
                          placeholder={String(values[c.name] ?? '') || '空欄は既定値'}
                          onChange={(e) => setCell(i, c.name, e.target.value)}
                        />
                        <span className={`case-badge case-${kind}`}>{FIRST_CASE_LABEL[kind]}</span>
                      </td>
                    );
                  }
                  return (
                    <td key={c.key} className="tbl-derived-cell">
                      <code className="tbl-derived">
                        {applyCaseVariant(effective(row, c.base), c.filter) || '（空）'}
                      </code>
                    </td>
                  );
                })}
                <td className="tbl-act">
                  <button type="button" className="link-btn" title="この行を複製" onClick={() => dupRow(i)}>複製</button>
                  <button type="button" className="link-btn tbl-del" title="この行を削除" onClick={() => delRow(i)}>×</button>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <p className="table-hint">
        同じ変数に複数の値を入力できます。1 行が 1 件のコードになります。空欄はテンプレートの既定値を使用します。
        右側の薄い列は自動生成変数（首字母大/小文字・大驼峰・小驼峰・大蛇形・小蛇形）で、行ごとの値から算出されます。
        数字範囲での行生成・Excel / CSV / JSON などのファイルインポートに対応します（上限 {TABLE_MAX_ROWS} 行）。
      </p>
    </div>
  );
}

/* ---------------- 変換プレビュー ---------------- */

function TransformBox({ variable, value, spec, onChange, keepFirstCase }) {
  const [copied, setCopied] = useState(false);
  const filterName = spec?.filter || '';
  const args = spec?.args || {};
  const def = TRANSFORM_MAP[filterName];
  const base = toBaseValue(variable, value);
  const preview = applyFilters(
    base,
    def ? [{ name: filterName, args }] : [],
    { counters: {}, keepFirstCase },
  );

  const selectFilter = (name) => {
    if (!name) return onChange(null);
    onChange({ filter: name, args: {} });
  };

  const setArg = (key, val, fallback) => {
    const next = { ...args };
    if (val === '' || val === fallback) delete next[key];
    else next[key] = val;
    onChange({ filter: filterName, args: next });
  };

  const copy = async () => {
    const text = buildPlaceholder(variable.name, filterName, args);
    if (await copyText(text)) {
      setCopied(true);
      window.setTimeout(() => setCopied(false), 1500);
    }
  };

  return (
    <div className="transform-box">
      <div className="transform-row">
        <span className="transform-label">変換</span>
        <select className="control transform-select" value={filterName} onChange={(e) => selectFilter(e.target.value)}>
          <option value="">（なし）</option>
          {TRANSFORM_GROUPS.map((g) => (
            <optgroup label={g.label} key={g.id}>
              {TRANSFORM_CATALOG.filter((t) => t.group === g.id).map((t) => (
                <option value={t.name} key={t.name}>{t.label} · {t.name}</option>
              ))}
            </optgroup>
          ))}
        </select>
        <button type="button" className="link-btn" onClick={copy} title="この変数のプレースホルダをコピー">
          {copied ? 'コピーしました' : '{{ }} をコピー'}
        </button>
      </div>

      {def?.description && <p className="transform-desc">{def.description}<span> 例: {def.example}</span></p>}

      {def?.args?.length > 0 && (
        <div className="transform-args">
          {def.args.map((a) => (
            <label className="arg-item" key={a.name} title={a.hint || a.label}>
              <span>{a.label}</span>
              <input
                className="control"
                type="text"
                value={args[a.name] ?? a.default}
                onChange={(e) => setArg(a.name, e.target.value, a.default)}
                placeholder={a.default}
              />
            </label>
          ))}
        </div>
      )}

      <div className="transform-preview">
        <span>変換後</span>
        <code>{preview || '（空）'}</code>
      </div>
      <div className="case-result">
        <span>先頭文字</span>
        <FirstCaseBadge value={value} />
        <span>→</span>
        <FirstCaseBadge value={preview} />
        {keepFirstCase && <span className="case-keep">保持ルール適用中</span>}
      </div>
      {variable.transforms?.length > 0 && (
        <p className="transform-note">
          テンプレート内の <code>| {variable.transforms.join(' / ')}</code> 指定は、この設定より優先されます。
        </p>
      )}
    </div>
  );
}

/* ---------------- 1 変数の入力欄 ---------------- */

function Field({
  variable, value, transformMap, expansionCfg, keepFirstCase, tableMode = false,
  onChange, onTransformChange, onExpansionChange, onReset,
}) {
  const {
    name, type, label, required, options, placeholder, description,
    occurrences, lines, joiner, autoCounter, derived = [],
  } = variable;

  const genCfg = normalizeGenerator(expansionCfg, variable);
  const expanded = !tableMode && genCfg.mode !== 'single'
    && !autoCounter && type !== 'sequence' && type !== 'boolean';
  const common = {
    id: `f-${name}`,
    value: typeof value === 'object' ? '' : (value ?? ''),
    onChange: (e) => onChange(name, type === 'boolean' ? e.target.checked : e.target.value),
  };

  // TABLE のみ入力方式とする。個別の単一値入力は廃止し、既定値（空欄セル用）を表示するだけにする。
  let control;
  if (tableMode) {
    const def = value == null || value === '' ? '（なし）' : String(value);
    control = (
      <div className="auto-note table-mode-note">
        既定値 <code>{def}</code> — TABLE の空欄セルで使用します。値は上の TABLE で入力してください。
      </div>
    );
  } else if (expanded) {
    control = <div className="auto-note">複数インスタンス生成中 — 下の一覧に値を 1 件ずつ指定してください</div>;
  } else if (autoCounter) {
    control = <div className="auto-note">出現順に 1, 2, 3… を自動採番します（入力不要）</div>;
  } else if (type === 'boolean') {
    control = (
      <label className="switch">
        <input
          id={`f-${name}`}
          type="checkbox"
          checked={value === true || value === 'true'}
          onChange={(e) => onChange(name, e.target.checked)}
        />
        <span>{value === true || value === 'true' ? 'true' : 'false'}</span>
      </label>
    );
  } else if (type === 'sequence') {
    control = <SequenceControl variable={variable} value={value} onChange={(v) => onChange(name, v)} />;
  } else if (type === 'select') {
    control = (
      <select {...common} className="control">
        {(options.length ? options : ['']).map((opt) => (
          <option key={opt} value={opt}>{opt || '（未選択）'}</option>
        ))}
      </select>
    );
  } else if (type === 'date') {
    control = <input {...common} type="date" className="control" />;
  } else if (type === 'number') {
    control = <input {...common} type="number" className="control" placeholder={placeholder} />;
  } else if (type === 'textarea' || type === 'code' || type === 'list') {
    control = (
      <textarea
        {...common}
        className={`control control-area ${type === 'code' || type === 'list' ? 'mono' : ''}`}
        rows={4}
        placeholder={placeholder || (type === 'list' ? '1 行に 1 項目' : '')}
        spellCheck={false}
      />
    );
  } else {
    control = <input {...common} type="text" className="control" placeholder={placeholder} />;
  }

  const hasOwn = Object.prototype.hasOwnProperty.call(transformMap, name);
  const spec = hasOwn ? transformMap[name] : (variable.defaultTransform || null);

  return (
    <div className="field">
      <div className="field-head">
        <label htmlFor={`f-${name}`}>
          {label}
          {required && <span className="required">必須</span>}
        </label>
        <div className="field-tags">
          {expanded && <span className="tag tag-teal">{genCfg.mode === 'values' ? '複数値' : '範囲'}</span>}
          {autoCounter && <span className="tag tag-teal">counter</span>}
          {derived.length > 0 && <span className="tag tag-teal">+{derived.length} 自動</span>}
          <span className="tag">{type}</span>
          <span className="tag tag-soft">{occurrences} 箇所 · L{lines[0]}</span>
          <button type="button" className="link-btn" onClick={() => onReset(name)}>既定値</button>
        </div>
      </div>
      {control}
      {tableMode && !autoCounter && type !== 'sequence' && (
        <div className="auto-note table-mode-note">
          TABLE 入力中 — この値は表の空欄時に使う既定値です。先頭文字の判定と自動生成変数は上の TABLE に行ごとに表示されます。
        </div>
      )}
      <div className="field-foot">
        <code>{`{{${name}}}`}</code>
        {description && <span>{description}</span>}
        {type === 'list' && <span>改行 → 「{joiner || ', '}」で結合</span>}
        {variable.transforms?.length > 0 && variable.transforms.map((t) => (
          <span className="tag tag-soft" key={t}>| {t}</span>
        ))}
      </div>

      {!tableMode && (
        <>
          {!autoCounter && type !== 'sequence' && (
            <FirstCaseBar name={name} value={value} expanded={expanded} derived={derived} />
          )}
          {!autoCounter && type !== 'sequence' && (
            <CaseVarsBox variable={variable} value={value} expanded={expanded} />
          )}
          {!autoCounter && type !== 'sequence' && (
            <GeneratorBox
              variable={variable}
              cfg={expansionCfg}
              onChange={(next) => onExpansionChange(name, next)}
            />
          )}
        </>
      )}
      {!autoCounter && (
        <TransformBox
          variable={variable}
          value={value}
          spec={spec}
          keepFirstCase={keepFirstCase}
          onChange={(next) => onTransformChange(name, next)}
        />
      )}
    </div>
  );
}

export default function VariableForm({
  variables, values, transforms, expansions, caseVars,
  keepFirstCase, metaKeep, onKeepFirstCaseChange,
  table, onTableChange, onImportValues,
  onChange, onTransformChange, onExpansionChange, onReset, onResetAll,
}) {
  const effectiveKeep = Boolean(keepFirstCase || metaKeep);
  const autoCount = variables.reduce((sum, v) => sum + (v.derived?.length || 0), 0);
  const suffix = caseVars?.enabled
    ? Object.entries(caseVars)
      .filter(([k, v]) => k !== 'enabled' && v)
      .map(([, v]) => v)
      .join(' / ')
    : 'オフ';
  // 値の入力は TABLE のみ。単一値 / 数字範囲 / 複数値リスト の個別入力モードは提供しない。
  const tableState = { ...(table && typeof table === 'object' ? table : {}), enabled: true };
  const tableMode = true;

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>2. 変数を入力</h2>
          <p className="panel-sub">検出された変数から入力欄を自動生成しました</p>
        </div>
        <button type="button" className="btn btn-ghost" onClick={onResetAll} disabled={!variables.length}>
          すべて既定値
        </button>
      </div>

      <div className="panel-options">
        <span className="tag tag-teal">TABLE 入力（1 変数 = 1 列・複数値は行で入力）</span>
        <label className="exp-toggle">
          <input
            type="checkbox"
            checked={effectiveKeep}
            disabled={Boolean(metaKeep)}
            onChange={(e) => onKeepFirstCaseChange(e.target.checked)}
          />
          <span>先頭文字を保持する</span>
        </label>
        {metaKeep && <span className="tag tag-teal">テンプレートで有効</span>}
        <span className="option-hint">
          1 行 = 1 件のコードを生成します。同じ変数に複数の値は TABLE の行で入力し、
          Excel / CSV などのファイル取込・数字範囲での行生成にも対応します。
          6 種類の自動生成変数（{suffix}）は入力不要で、{autoCount} 個を各行から自動計算します。
        </span>
      </div>

      {tableMode && variables.length > 0 && (
        <TableInput
          variables={variables}
          values={values}
          table={tableState}
          onTableChange={onTableChange}
          onImportValues={onImportValues}
        />
      )}

      {variables.length === 0 ? (
        <div className="empty">
          <p>変数が見つかりません。</p>
          <p className="empty-sub">テンプレート本文に <code>{'{{className}}'}</code> のように書いてください。</p>
        </div>
      ) : (
        <div className="field-list">
          {variables.map((v) => (
            <Field
              key={v.name}
              variable={v}
              value={values[v.name]}
              transformMap={transforms}
              expansionCfg={expansions[v.name]}
              keepFirstCase={effectiveKeep}
              tableMode={tableMode}
              onChange={onChange}
              onTransformChange={onTransformChange}
              onExpansionChange={onExpansionChange}
              onReset={onReset}
            />
          ))}
        </div>
      )}
    </section>
  );
}

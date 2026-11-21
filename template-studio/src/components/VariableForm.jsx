import React, { useState } from 'react';
// firstCharCase 只用于 TABLE 单元格上的首字母标记，其余 transform 工具已随 field-list 一起移除
import { firstCharCase, FIRST_CASE_LABEL } from '../../shared/transforms.js';
import { applyCaseVariant } from '../../shared/casevars.js';
import { importTableFile } from '../api.js';
import {
  TABLE_MAX_ROWS,   // 表行数上限：避免一次生成上万份代码把浏览器卡死
  tableColumns,     // 从变量定义中取出「真正需要用户填」的基础变量名
  blankTableRow,
  normalizeTable,   // 把二维表展开成渲染所需的多行数据
  parsePastedTable,
  looksLikeHeader,
  normalizeHeader,
  mapTableRows,
  buildRangeRows,
} from '../../shared/expansion.js';

// 默认展示 10 行空表：让用户打开就能直接填写，不必反复点「追加行」。
// 为什么安全：normalizeTable 会过滤全空行，空行不会被渲染成多余代码，
// 只有真正填了值的行才计入生成数量。
const DEFAULT_TABLE_ROWS = 10;
const emptyRows = (columns) =>
  Array.from({ length: DEFAULT_TABLE_ROWS }, () => blankTableRow(columns));

/* ---------------- TABLE 入力（1 変数 = 1 列 / 1 行 = 1 件） ---------------- */

const RANGE_DEFAULT = { column: '', from: 1, to: 10, step: 1, pad: 0, prefix: '', suffix: '' };

const IMPORT_ACCEPT = '.xlsx,.xlsm,.csv,.tsv,.txt,.json,.md,.markdown';

/**
 * 文件 → 行数据 → 变量列的映射面板。
 * 为什么保留独立的导入面板：文件可能带表头/多 sheet，需要用户确认列如何对应变量；
 * 直接塞进 TABLE 会让「导入结果」和「手工输入」混在一起无法回滚。
 */
function FileImportPanel({ columns, rows, setRows, notify, onImportValues }) {
  const [file, setFile] = useState(null);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [skipRows, setSkipRows] = useState(0);
  const [headerMode, setHeaderMode] = useState('auto');

  // 用 data URL 读取，是因为后端 importTableFile 统一按 base64 解析各类格式，前端无需关心具体格式
  const readFile = (fileObj) => new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onload = () => resolve(String(reader.result));
    reader.onerror = () => reject(new Error('ファイルを読み込めませんでした'));
    reader.readAsDataURL(fileObj);
  });

  const onPick = async (e) => {
    const picked = e.target.files?.[0];
    e.target.value = ''; // 允许重复选择同一个文件时仍能触发 change
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

/**
 * 唯一的变量输入区：一行 = 一份代码，一列 = 一个变量。
 * 为什么只保留 TABLE：多值变量用「行」表达比「逐个输入框」更紧凑，且天然支持
 * 文件导入 / 剪贴板 / 数字范围三种批量填法，不需要为每种类型维护一套输入控件。
 */
function TableInput({ variables, values, table, onTableChange, onImportValues }) {
  const columns = tableColumns(variables);
  const byName = new Map(variables.map((v) => [v.name, v]));

  // 列顺序刻意分成两段：所有人填的基础变量在前，自动派生变量在后。
  // 为什么：用户只需要在左侧连续填写，右侧只读列一眼就能看出是「算出来的」，避免交错列误填。
  const inputColumns = columns.map((name) => {
    const v = byName.get(name);
    return { kind: 'input', key: name, name, label: v?.label || name };
  });
  const derivedColumns = columns.flatMap((name) => {
    const v = byName.get(name);
    return (v?.derived || []).map((d) => ({
      kind: 'derived', key: d.name, name: d.name,
      base: name, filter: d.filter, label: d.label, placeholder: d.placeholder,
    }));
  });
  const displayColumns = [...inputColumns, ...derivedColumns];

  // 切模板瞬间 rows 可能尚未初始化：给 10 行空表，既有默认填写高度，也避免 undefined.length 崩溃
  const rows = table.rows?.length ? table.rows : emptyRows(columns);
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
  // 清空后仍保留 10 行的默认高度，避免表格塌陷成一行
  const clearRows = () => setRows(emptyRows(columns));
  // 空单元格回退到 front-matter 的默认值，让「只填变化的值」成为可能
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
        <span className="table-count" title="空行不生成代码，只有填了值的行才计入">入力 {rows.length} 行 → 生成 {generatedCount} 件</span>
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
        初期状態は {DEFAULT_TABLE_ROWS} 行分の入力枠を用意しています。同じ変数に複数の値を入力でき、1 行が 1 件のコードになります。
        空欄の行はコードを生成しません（入力した行だけが対象）。空欄セルはテンプレートの既定値を使用します。
        左側が入力変数、右側の薄い列が自動生成変数（首字母大/小文字・大驼峰・小驼峰・大蛇形・小蛇形）で、行ごとの値から算出されます。
        数字範囲での行生成・Excel / CSV / JSON などのファイルインポート・クリップボード貼り付けに対応します（上限 {TABLE_MAX_ROWS} 行）。
      </p>
    </div>
  );
}

/**
 * 变量入力区：只渲染 TABLE 一个输入入口。
 * 为什么不再渲染 field-list：逐个变量的输入框与 TABLE 表达同一份数据，
 * 两套入口会互相覆盖；保留单一的 TABLE 能让「一行 = 一份代码」的心智模型保持一致。
 */
export default function VariableForm({
  variables, values, caseVars,
  keepFirstCase, metaKeep, onKeepFirstCaseChange,
  table, onTableChange, onImportValues, onResetAll,
}) {
  const effectiveKeep = Boolean(keepFirstCase || metaKeep);
  const autoCount = variables.reduce((sum, v) => sum + (v.derived?.length || 0), 0);
  const suffix = caseVars?.enabled
    ? Object.entries(caseVars)
      .filter(([k, v]) => k !== 'enabled' && v)
      .map(([, v]) => v)
      .join(' / ')
    : 'オフ';
  // rows 缺省为 [] 是防御性的：切模板瞬间 table 可能尚未同步，避免下游读到 undefined
  const tableState = { rows: [], ...(table && typeof table === 'object' ? table : {}), enabled: true };

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>2. 変数を入力</h2>
          <p className="panel-sub">検出された変数から TABLE を自動生成しました</p>
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
          Excel / CSV などのファイル取込・クリップボード貼り付け・数字範囲での行生成にも対応します。
          6 種類の自動生成変数（{suffix}）は入力不要で、{autoCount} 個を各行から自動計算します。
        </span>
      </div>

      {variables.length > 0 ? (
        <TableInput
          variables={variables}
          values={values}
          table={tableState}
          onTableChange={onTableChange}
          onImportValues={onImportValues}
        />
      ) : (
        <div className="empty">
          <p>変数が見つかりません。</p>
          <p className="empty-sub">テンプレート本文に <code>{'{{className}}'}</code> のように書いてください。</p>
        </div>
      )}
    </section>
  );
}

import { useEffect, useRef, useState } from 'react';
import { getExcelSample, parseExcelWidgets } from '../api.js';
import { resolveWidget, widgetsToCsv } from '../../../shared/excel-widgets.js';
import { DEFAULT_WIDGET_RULES, WIDGET_SCHEMA } from '../../../shared/maskat.js';
import useAutoSave, { downloadState } from '../useAutoSave.js';
import WidgetList from './WidgetList.jsx';
import MaskatPanel from './MaskatPanel.jsx';

const CONTROL_OF_TAG = Object.fromEntries(DEFAULT_WIDGET_RULES.map((r) => [r.tag, r.control]));

export default function ExcelStudio({ config, onStatus }) {
  const fileRef = useRef(null);
  const [dragging, setDragging] = useState(false);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  // 自动保存：Excel 解析结果（含可编辑的 widget 列表）刷新后仍可恢复。
  const auto = useAutoSave('excel:v1');
  const [result, setResult] = useState(() => auto.restore());

  // result 变化即写入 localStorage；widget 编辑即时生效，无需手动保存。
  useEffect(() => {
    if (result) auto.save(result);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [result]);

  const tell = (text, type = 'info') => {
    setNotice(text);
    onStatus?.({ type, text });
  };

  const load = async (file, filename) => {
    setBusy(true);
    setError('');
    setNotice('');
    try {
      const r = await parseExcelWidgets({ file, filename });
      setResult(r);
      tell(`已读取「${r.sheets.itemSheet}」+「${r.sheets.layoutSheet}」：${r.stats.widgets} 个 widget（输入框 ${r.stats.input} / 选择项 ${r.stats.choice} / 其它 ${r.stats.other}）`);
    } catch (e) {
      setError(e.message || 'Excel 读取失败');
    } finally {
      setBusy(false);
    }
  };

  const pick = (file) => {
    if (!file) return;
    if (file.size > 12 * 1024 * 1024) { setError('Excel 超过 12 MB'); return; }
    const reader = new FileReader();
    reader.onerror = () => setError('文件读取失败');
    reader.onload = () => load(String(reader.result || ''), file.name);
    reader.readAsDataURL(file);
  };

  const loadSample = async () => {
    setBusy(true);
    setError('');
    try {
      const s = await getExcelSample();
      await load(s.file, s.filename);
    } catch (e) {
      setError(e.message || '示例加载失败');
      setBusy(false);
    }
  };

  const edit = (id, field, value) => {
    setResult((prev) => {
      if (!prev) return prev;
      return {
        ...prev,
        items: prev.items.map((it) => {
          if (it.id !== id) return it;
          if (field === 'tag') {
            const tag = value;
            const guessed = resolveWidget(tag);
            return {
              ...it,
              tag,
              widget: guessed.widget,
              control: CONTROL_OF_TAG[tag] || it.control,
              eventAttributes: WIDGET_SCHEMA[tag]?.eventAttributes || [],
            };
          }
          return { ...it, [field]: value };
        }),
      };
    });
  };

  const remove = (id) => setResult((prev) => (
    prev ? { ...prev, items: prev.items.filter((it) => it.id !== id) } : prev
  ));

  const items = result?.items || [];
  const image = {
    name: result?.filename || '',
    width: Math.max(800, ...items.map((i) => (Number(i.left) || 0) + (Number(i.width) || 0) + 40)),
    height: Math.max(600, ...items.map((i) => (Number(i.top) || 0) + (Number(i.height) || 0) + 40)),
  };
  const spec = config?.maskat?.specMeta;
  const tags = config?.maskat?.availableTags || [];

  return (
    <main className="content excel-content">
      <section className="panel excel-upload">
        <div className="panel-head">
          <div>
            <h2>Excel 设计书 → Widget</h2>
            <p>读取「画面項目」和「画面レイアウト」两张 sheet，关联生成 widget 列表</p>
          </div>
          <span className="badge badge-tech">xlsx / xlsm</span>
        </div>

        <div
          className={`excel-drop ${dragging ? 'dragging' : ''}`}
          onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
          onDragLeave={() => setDragging(false)}
          onDrop={(e) => { e.preventDefault(); setDragging(false); pick(e.dataTransfer.files?.[0]); }}
          onClick={() => fileRef.current?.click()}
          role="button"
          tabIndex={0}
          onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileRef.current?.click(); }}
        >
          <input
            ref={fileRef}
            type="file"
            accept=".xlsx,.xlsm,.csv,.tsv,application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
            hidden
            onChange={(e) => { pick(e.target.files?.[0]); e.target.value = ''; }}
          />
          <strong>{busy ? '读取中…' : '点击选择或拖入 Excel 设计书'}</strong>
          <span>需包含「画面項目」（项目名 / widget 类型 / event 属性）与「画面レイアウト」（TOP / LEFT / WIDTH）两表</span>
        </div>

        <div className="excel-actions">
          <button type="button" className="btn btn-primary" onClick={loadSample} disabled={busy}>加载示例设计书</button>
          {result && (
            <button
              type="button"
              className="btn btn-ghost"
              onClick={() => {
                const blob = new Blob([widgetsToCsv(items)], { type: 'text/csv;charset=utf-8' });
                const url = URL.createObjectURL(blob);
                const a = document.createElement('a');
                a.href = url; a.download = 'widgets.csv';
                document.body.appendChild(a); a.click(); a.remove();
                setTimeout(() => URL.revokeObjectURL(url), 1500);
              }}
            >导出 widget 列表 CSV</button>
          )}
          {result && <button type="button" className="btn btn-ghost" onClick={() => downloadState(result, 'pageitem-excel-state.json')}>导出状态 JSON</button>}
          {result && <span className="file-tag">{result.filename}</span>}
        </div>

        {error && <div className="alert alert-error">{error}</div>}
        {notice && <div className="alert alert-ok">{notice}</div>}
        {result?.warnings?.length > 0 && (
          <div className="alert alert-warn">
            {result.warnings.map((w, i) => <div key={i}>{w}</div>)}
          </div>
        )}

        {result && (
          <div className="excel-summary">
            <div className="summary-item"><span>Sheet</span><strong>{result.sheets.names.join(' / ')}</strong></div>
            <div className="summary-item"><span>识别表头</span><strong>项目表 第 {result.headers.item.row} 行 · 布局表 第 {result.headers.layout.row} 行</strong></div>
            <div className="summary-item"><span>行数</span><strong>{result.stats.itemRows} 项目 / {result.stats.layoutRows} 布局（匹配 {result.stats.matched}）</strong></div>
            <div className="summary-item"><span>分类</span><strong>输入框 {result.stats.input} · 选择项 {result.stats.choice} · 其它 {result.stats.other}</strong></div>
          </div>
        )}
      </section>

      <section className="panel spec-panel">
        <div className="panel-head">
          <div>
            <h2>Maskat widget 预设 · /maskat-spec</h2>
            <p>{config?.maskat?.note || '标签名与属性来自 bindingConfiguration'}</p>
          </div>
          <span className="badge badge-ok">spec 驱动</span>
        </div>
        <div className="spec-grid">
          <div><span>定义文件</span><strong>{spec?.sourceFiles?.length ?? '-'} 份 bindingConfiguration</strong></div>
          <div><span>widget 定义</span><strong>{spec?.tagCount ?? '-'} 个（可定位 {spec?.positionableCount ?? '-'}）</strong></div>
          <div><span>layout 结构</span><strong>&lt;layoutDef&gt; → &lt;layout name&gt; → widget</strong></div>
          <div><span>event 结构</span><strong>&lt;eventDef&gt; → &lt;component&gt; → &lt;event&gt;</strong></div>
        </div>
        <p className="spec-note">
          修改 <code>maskat-spec/bindingConfiguration_*.txt</code> 后执行 <code>npm run spec</code> 重建
          <code> widget-schema.json / widget-rules.json</code>，页面与生成产物随即按新规则变化。
        </p>
      </section>

      <WidgetList items={items} onEdit={edit} onDelete={remove} tags={tags} onNotice={setNotice} />

      <MaskatPanel image={image} items={items} config={config} />
    </main>
  );
}

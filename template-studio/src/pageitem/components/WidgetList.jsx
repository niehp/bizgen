import { Fragment, useMemo, useState } from 'react';
import { widgetsToCsv } from '../../../shared/excel-widgets.js';
import { WIDGET_SCHEMA } from '../../../shared/maskat.js';
import { CONTROL_CLASSES } from '../../../shared/layout.js';

const CLASS_LABEL = Object.fromEntries(CONTROL_CLASSES.map((c) => [c.id, c.label]));
const FILTERS = [
  { id: 'all', label: '全部' },
  { id: 'input', label: '输入框' },
  { id: 'choice', label: '选择项' },
  { id: 'other', label: '其它' },
];

const download = (content, filename, type) => {
  const blob = new Blob([content], { type });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
};

export default function WidgetList({ items, onEdit, onDelete, onAdd, tags = [], onNotice }) {
  const [filter, setFilter] = useState('all');
  const [copied, setCopied] = useState('');
  const [openAttrs, setOpenAttrs] = useState(null);
  const [attrQuery, setAttrQuery] = useState('');

  const shown = useMemo(() => {
    const list = filter === 'all' ? items : items.filter((i) => i.control === filter);
    return list.map((it, idx) => ({ it, idx: items.indexOf(it) + 1 }));
  }, [items, filter]);

  const json = useMemo(() => `${JSON.stringify(items.map((it) => ({
    name: it.name,
    widget: it.widget,
    tag: it.tag,
    control: it.control,
    top: it.top, left: it.left, width: it.width, height: it.height,
    events: it.events || [],
    eventAttributes: it.eventAttributes || [],
    attrs: it.attrs || {},
    required: Boolean(it.required),
  })), null, 2)}\n`, [items]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(json);
      setCopied('已复制');
      setTimeout(() => setCopied(''), 1800);
    } catch { setCopied('复制失败'); setTimeout(() => setCopied(''), 1800); }
  };

  const addEvent = (it, name) => {
    const cur = it.events || [];
    if (cur.includes(name)) return;
    onEdit(it.id, 'events', [...cur, name]);
    onNotice?.(`${it.name} 已追加 event 属性「${name}」（取自 /maskat-spec）`);
  };

  // 坐标 / 名字已在表格里单独编辑，属性面板只展示其余 spec 属性
  const ATTR_SKIP = new Set(['top', 'left', 'width', 'height', 'name']);
  const specAttrs = (tag) => {
    const spec = WIDGET_SCHEMA[tag] || {};
    return (spec.attributes || []).filter((a) => !ATTR_SKIP.has(a));
  };
  const setAttr = (it, key, value) => onEdit(it.id, 'attrs', { ...(it.attrs || {}), [key]: value });

  return (
    <section className="panel widget-panel">
      <div className="panel-head">
        <div>
          <h2>Widget 列表</h2>
          <p>画面項目 × 画面レイアウト → widget 名字 / 类型 / TOP / LEFT / WIDTH / event 属性</p>
        </div>
        <span className="badge badge-tech">{items.length} widgets</span>
      </div>

      <div className="widget-toolbar">
        <div className="filter-row">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              className={`chip-btn ${filter === f.id ? 'active' : ''}`}
              onClick={() => setFilter(f.id)}
            >
              {f.label}
              <span className="chip-count">
                {f.id === 'all' ? items.length : items.filter((i) => i.control === f.id).length}
              </span>
            </button>
          ))}
        </div>
        <div className="toolbar-actions">
          <button type="button" className="btn btn-primary" onClick={onAdd} disabled={!onAdd}>＋ 添加 widget</button>
          <button type="button" className="btn btn-ghost" onClick={copy}>{copied || '复制 JSON'}</button>
          <button type="button" className="btn btn-ghost" onClick={() => download(json, 'widgets.json', 'application/json;charset=utf-8')}>下载 JSON</button>
          <button type="button" className="btn btn-ghost" onClick={() => download(widgetsToCsv(items), 'widgets.csv', 'text/csv;charset=utf-8')}>下载 CSV</button>
        </div>
      </div>

      <div className="widget-table-wrap">
        <table className="widget-table">
          <thead>
            <tr>
              <th className="col-no">No</th>
              <th>widget 名字</th>
              <th>widget 类型（Maskat 标签）</th>
              <th>分类</th>
              <th className="col-num">TOP</th>
              <th className="col-num">LEFT</th>
              <th className="col-num">WIDTH</th>
              <th className="col-num">HEIGHT</th>
              <th>event 属性 / 可用属性（spec）</th>
              <th className="col-attrs">spec 属性</th>
              <th className="col-act" />
            </tr>
          </thead>
          <tbody>
            {shown.length === 0 && (
              <tr><td colSpan={11} className="table-empty">还没有 widget：请上传 Excel 设计书、点「加载示例设计书」或点「＋ 添加 widget」</td></tr>
            )}
            {shown.map(({ it, idx }) => {
              const specEvents = WIDGET_SCHEMA[it.tag]?.eventAttributes || it.eventAttributes || [];
              const attrsOpen = openAttrs === it.id;
              const q = attrQuery.trim().toLowerCase();
              const attrList = specAttrs(it.tag).filter((a) => !q || a.toLowerCase().includes(q));
              const required = WIDGET_SCHEMA[it.tag]?.required || [];
              return (
                <Fragment key={it.id}>
                <tr className={it.control === 'input' ? 'row-input' : it.control === 'choice' ? 'row-choice' : ''}>
                  <td className="col-no">{idx}</td>
                  <td>
                    <input className="cell-text" value={it.name} onChange={(e) => onEdit(it.id, 'name', e.target.value)} aria-label="widget 名字" />
                  </td>
                  <td>
                    <select
                      className="cell-select"
                      value={it.tag}
                      onChange={(e) => onEdit(it.id, 'tag', e.target.value)}
                      aria-label="widget 类型"
                    >
                      {!tags.some((t) => t.tag === it.tag) && <option value={it.tag}>{it.tag}</option>}
                      {tags.map((t) => <option key={t.tag} value={t.tag}>{t.tag}</option>)}
                    </select>
                    <span className="cell-sub">{it.widget}</span>
                  </td>
                  <td>
                    <select
                      className="cell-select cell-select-sm"
                      value={it.control}
                      onChange={(e) => onEdit(it.id, 'control', e.target.value)}
                      aria-label="控件分类"
                    >
                      {CONTROL_CLASSES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
                    </select>
                  </td>
                  {['top', 'left', 'width', 'height'].map((field) => (
                    <td key={field} className="col-num">
                      <input
                        className="cell-num"
                        type="number"
                        value={it[field]}
                        onChange={(e) => onEdit(it.id, field, Number(e.target.value) || 0)}
                        aria-label={field}
                      />
                    </td>
                  ))}
                  <td className="col-events">
                    <input
                      className="cell-text"
                      value={(it.events || []).join(', ')}
                      onChange={(e) => onEdit(it.id, 'events', e.target.value.split(/[,;、\s]+/).filter(Boolean))}
                      placeholder="onEdit, funcOnLeave"
                      aria-label="event 属性"
                    />
                    <div className="event-chips">
                      {specEvents.slice(0, 8).map((ev) => (
                        <button key={ev} type="button" className={`event-chip ${(it.events || []).includes(ev) ? 'on' : ''}`} onClick={() => addEvent(it, ev)} title={`点击${(it.events || []).includes(ev) ? '（已选）' : '添加'} ${ev}`}>
                          {ev}
                        </button>
                      ))}
                      {specEvents.length === 0 && <span className="cell-sub">该 widget 无 event 属性</span>}
                    </div>
                  </td>
                  <td className="col-attrs">
                    <button type="button" className="btn btn-mini" onClick={() => setOpenAttrs(attrsOpen ? null : it.id)}>
                      {attrsOpen ? '收起' : '属性'}
                    </button>
                  </td>
                  <td className="col-act">
                    <button type="button" className="btn-icon" onClick={() => onDelete(it.id)} title="删除" aria-label="删除">×</button>
                  </td>
                </tr>
                {attrsOpen && (
                  <tr className="attrs-row">
                    <td colSpan={11}>
                      <div className="attrs-head">
                        <strong>{it.tag}</strong> 按 /maskat-spec 编辑属性（带 * 为必填）
                        <input
                          className="cell-input attrs-search"
                          value={attrQuery}
                          placeholder="筛选属性名"
                          aria-label="筛选 spec 属性"
                          onChange={(e) => setAttrQuery(e.target.value)}
                        />
                      </div>
                      <div className="attrs-grid">
                        {attrList.map((a) => (
                          <label key={a} className="attrs-field">
                            <span>{a}{required.includes(a) ? ' *' : ''}</span>
                            <input
                              className="cell-input"
                              value={(it.attrs || {})[a] ?? ''}
                              placeholder={WIDGET_SCHEMA[it.tag]?.defaults?.[a] ?? ''}
                              onChange={(e) => setAttr(it, a, e.target.value)}
                            />
                          </label>
                        ))}
                        {!attrList.length && <span className="cell-sub">该 widget 没有匹配的属性</span>}
                      </div>
                    </td>
                  </tr>
                )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>
      <p className="widget-hint">
        TOP / LEFT / WIDTH 单位为原图像素；event 属性列可直接输入，也可点击下方 spec 提供的合法属性。
        点每行「属性」可按 /maskat-spec 编辑该 widget 的其它属性；「＋ 添加 widget」可任意新增。
        灰色分类 <strong>{CLASS_LABEL.input}</strong> / <strong>{CLASS_LABEL.choice}</strong> 可随时调整。
      </p>
    </section>
  );
}

import { useState } from 'react';
import { itemRows, ITEM_TYPES, CONTROL_CLASSES, WIDGET_TYPES } from '../../../shared/layout.js';
import { attrsToText, parseAttrs, DEFAULT_WIDGET_RULES, WIDGET_SCHEMA } from '../../../shared/maskat.js';

const NUM_FIELDS = ['top', 'left', 'width', 'height'];
const HEAD = { top: 'TOP', left: 'LEFT', width: 'WIDTH', height: 'HEIGHT' };
const TYPE_OPTIONS = {
  label: '标签', input: '输入框', select: '下拉框', radio: '单选组', checkbox: '复选框',
  button: '按钮', header: '列头/标题', title: '标题', text: '文本', other: '其他',
};
const FILTERS = [{ id: 'all', label: '全部' }, ...CONTROL_CLASSES.map((c) => ({ id: c.id, label: c.label }))];

const TAG_BY_WIDGET = Object.fromEntries(DEFAULT_WIDGET_RULES.map((r) => [r.widget, r.tag]));

export default function ItemTable({
  items, unit, setUnit, image, selectedId,
  onSelect, onEdit, onDelete, onClear, onAdd, dropped,
}) {
  const [drafts, setDrafts] = useState({});
  const [filter, setFilter] = useState('all');
  const rows = itemRows(items, unit, image);
  const suffix = unit === 'px' ? 'px' : unit === '%' ? '%' : '';
  const step = unit === 'px' ? 1 : unit === '%' ? 0.1 : 1;

  const keyOf = (id, f) => `${id}:${f}`;
  const shown = (it, f, value) => drafts[keyOf(it.id, f)] ?? value;

  const changeNum = (it, f, value) => {
    const k = keyOf(it.id, f);
    setDrafts((prev) => ({ ...prev, [k]: value }));
    if (value !== '' && Number.isFinite(Number(value))) onEdit(it.id, f, value);
  };
  const blurNum = (it, f) => {
    const k = keyOf(it.id, f);
    setDrafts((prev) => {
      const next = { ...prev };
      delete next[k];
      return next;
    });
  };

  const visible = rows
    .map((row, i) => ({ row, it: items[i] }))
    .filter(({ it }) => filter === 'all' || (it.control || 'other') === filter);

  const counts = {
    input: items.filter((i) => i.control === 'input').length,
    choice: items.filter((i) => i.control === 'choice').length,
    other: items.filter((i) => (i.control || 'other') === 'other').length,
  };

  // 每个 widget 可按 /maskat-spec 编辑属性；坐标/名字已有独立列，这里只提示其余属性
  const ATTR_SKIP = new Set(['top', 'left', 'width', 'height', 'name']);
  const specAttrHint = (it) => {
    const tag = it.tag || TAG_BY_WIDGET[it.widget];
    return (WIDGET_SCHEMA[tag]?.attributes || []).filter((a) => !ATTR_SKIP.has(a)).slice(0, 12).join(', ');
  };

  return (
    <section className="card table-card">
      <div className="card-head card-head-row">
        <h2><span className="step">4</span> 项目名 / TOP · LEFT · WIDTH / 控件分类</h2>
        <div className="table-tools">
          <label className="unit-switch">
            <span>单位</span>
            <select className="control control-sm" value={unit} onChange={(e) => setUnit(e.target.value)}>
              <option value="px">px（原图像素）</option>
              <option value="%">%（相对画布）</option>
              <option value="norm1000">0–1000（归一化）</option>
            </select>
          </label>
          <span className="count-chip">{items.length} 个项目</span>
          <button type="button" className="btn btn-mini btn-primary" onClick={onAdd} disabled={!onAdd}>＋ 添加项目</button>
          <button type="button" className="btn btn-mini btn-danger-ghost" onClick={onClear} disabled={!items.length}>清空</button>
        </div>
      </div>

      <div className="filter-bar">
        <div className="seg">
          {FILTERS.map((f) => (
            <button
              key={f.id}
              type="button"
              className={filter === f.id ? 'active' : ''}
              onClick={() => setFilter(f.id)}
            >
              {f.label}{f.id !== 'all' ? ` ${counts[f.id] || 0}` : ` ${items.length}`}
            </button>
          ))}
        </div>
        <span className="filter-hint">
          输入框 <strong className="cls-input">{counts.input}</strong> ·
          选择项 <strong className="cls-choice">{counts.choice}</strong> ·
          其它 <strong>{counts.other}</strong> — 分类可直接修改，决定生成哪种 Maskat widget
        </span>
      </div>

      {!items.length ? (
        <div className="table-empty">
          暂无项目。请上传图片后点「解析图片」，或加载内置示例页面 / 在图片上框选标注。
        </div>
      ) : (
        <div className="table-scroll">
          <table className="item-table">
            <thead>
              <tr>
                <th className="col-no">No</th>
                <th className="col-name">项目名</th>
                {NUM_FIELDS.map((f) => (
                  <th key={f} className={f === 'width' ? 'col-num col-emphasis' : 'col-num'}>
                    {HEAD[f]}{suffix ? <span className="unit-suffix">({suffix})</span> : null}
                  </th>
                ))}
                <th className="col-control">控件分类</th>
                <th className="col-widget">Widget</th>
                <th className="col-attrs">spec 属性</th>
                <th className="col-label">关联标签 / 候选项</th>
                <th className="col-type">外观类型</th>
                <th className="col-op" />
              </tr>
            </thead>
            <tbody>
              {visible.map(({ row, it }) => (
                <tr
                  key={it.id}
                  className={selectedId === it.id ? 'row-selected' : ''}
                  onClick={() => onSelect(it.id)}
                >
                  <td className="col-no">{row.no}</td>
                  <td className="col-name">
                    <input
                      className="cell-input"
                      value={it.name}
                      title={it.text ? `画面文本：${it.text}` : it.name}
                      onFocus={() => onSelect(it.id)}
                      onChange={(e) => onEdit(it.id, 'name', e.target.value)}
                    />
                  </td>
                  {NUM_FIELDS.map((f) => (
                    <td key={f} className={f === 'width' ? 'col-num col-emphasis' : 'col-num'}>
                      <input
                        className="cell-input cell-num"
                        type="number"
                        step={step}
                        value={shown(it, f, row[f])}
                        onChange={(e) => changeNum(it, f, e.target.value)}
                        onBlur={() => blurNum(it, f)}
                        onFocus={() => onSelect(it.id)}
                      />
                    </td>
                  ))}
                  <td className="col-control">
                    <select
                      className={`cell-select cls-${it.control || 'other'}`}
                      value={it.control || 'other'}
                      onChange={(e) => onEdit(it.id, 'control', e.target.value)}
                    >
                      {CONTROL_CLASSES.map((c) => <option key={c.id} value={c.id}>{c.label}</option>)}
                    </select>
                  </td>
                  <td className="col-widget">
                    <select
                      className="cell-select"
                      value={it.widget || 'static'}
                      onChange={(e) => onEdit(it.id, 'widget', e.target.value)}
                    >
                      {WIDGET_TYPES.map((w) => (
                        <option key={w.id} value={w.id}>{w.label}</option>
                      ))}
                    </select>
                  </td>
                  <td className="col-attrs">
                    <input
                      className="cell-input"
                      value={attrsToText(it.attrs || {})}
                      placeholder="field=xxx, maxlength=100"
                      title={specAttrHint(it) || '该 widget 的 spec 属性'}
                      onChange={(e) => onEdit(it.id, 'attrs', parseAttrs(e.target.value))}
                      onFocus={() => onSelect(it.id)}
                    />
                  </td>
                  <td className="col-label">
                    <input
                      className="cell-input"
                      value={it.label || ''}
                      placeholder="字段标签"
                      title={(it.options || []).length ? `候选项：${it.options.join(' / ')}` : '该控件对应的字段标签文字'}
                      onChange={(e) => onEdit(it.id, 'label', e.target.value)}
                      onFocus={() => onSelect(it.id)}
                    />
                    {(it.options || []).length > 0 && (
                      <span className="opt-chip" title={it.options.join(' / ')}>{it.options.length} 项</span>
                    )}
                    {it.required && <span className="req-chip" title="必填项目">*</span>}
                  </td>
                  <td className="col-type">
                    <select
                      className="cell-select"
                      value={it.type || 'other'}
                      onChange={(e) => onEdit(it.id, 'type', e.target.value)}
                    >
                      {ITEM_TYPES.map((t) => <option key={t} value={t}>{TYPE_OPTIONS[t]}</option>)}
                    </select>
                  </td>
                  <td className="col-op">
                    <button
                      type="button"
                      className="row-del"
                      title="删除该项目"
                      onClick={(e) => { e.stopPropagation(); onDelete(it.id); }}
                    >×</button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="table-foot">
        <span>坐标原点为图片左上角，TOP / LEFT 为项目框左上角，WIDTH 为框宽度。表格内容可直接编辑，图片上的框会同步更新；分类列决定 Maskat widget 的标签类型。</span>
        {dropped?.length > 0 && (
          <details className="dropped">
            <summary>{dropped.length} 个项目被过滤</summary>
            <ul>
              {dropped.slice(0, 20).map((d, i) => <li key={i}>{d.name}：{d.reason}</li>)}
            </ul>
          </details>
        )}
      </div>
    </section>
  );
}

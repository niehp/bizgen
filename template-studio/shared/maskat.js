/**
 * Maskat widget 生成器（spec 驱动）。
 *
 * 真值来源：/maskat-spec/bindingConfiguration_*.txt
 *   → npm run spec 生成 widget-schema.json / widget-rules.json / widget-rules.js
 *   → 本模块只消费生成物，不再硬编码标签名。
 *
 * 已确认的真实结构（来自 bindingConfiguration_maskat.txt / _event.txt）：
 *   <layoutDef>                       无属性
 *     <layout name="...">            仅 name(required) / refParentHTML / fragment，children="*"
 *       <textBox name="..." top="162px" left="212px" ... />   ← widget 直接作为 layout 子元素
 *     </layout>
 *   </layoutDef>
 *   <eventDef> → <component id="..."> → <event id="..." type="local|remote" />
 *
 * 本模块是纯函数，前后端共用，不引入任何依赖。
 */
import { CONTROL_CLASSES, WIDGET_TYPES, sortItems } from './layout.js';
import {
  DEFAULT_WIDGET_RULES, WIDGET_SCHEMA, SPEC_META,
} from '../maskat-spec/widget-rules.js';

export { DEFAULT_WIDGET_RULES, WIDGET_SCHEMA, SPEC_META };

export const MASKAT_NOTE = `依据 /maskat-spec 下 ${SPEC_META.sourceFiles.length} 份 bindingConfiguration 生成（${SPEC_META.tagCount} 个 widget 定义 / ${SPEC_META.positionableCount} 个可定位）。标签名、必填属性、事件属性均取自该 spec；修改 txt 后运行 npm run spec 重建规则。`;

const text = (v, fallback = '') => String(v ?? fallback).trim();
const CONTROL_BY_ID = Object.fromEntries(CONTROL_CLASSES.map((c) => [c.id, c]));
const RULE_BY_WIDGET = Object.fromEntries(DEFAULT_WIDGET_RULES.map((r) => [r.widget, r]));

/** 取某个真实标签的 spec 片段 */
export function specForTag(tag) {
  const s = WIDGET_SCHEMA[tag];
  if (!s) return { attributes: [], required: [], eventAttributes: [], defaults: {}, children: [] };
  return {
    attributes: Array.isArray(s.attributes) ? s.attributes : [],
    required: Array.isArray(s.required) ? s.required : [],
    eventAttributes: Array.isArray(s.eventAttributes) ? s.eventAttributes : [],
    defaults: s.defaults || {},
    children: Array.isArray(s.children) ? s.children : [],
    className: s.className || '',
  };
}

/** 页面里用 "type=text, maxlength=20" 这种紧凑写法编辑额外属性 */
export function parseAttrs(input) {
  const out = {};
  for (const part of text(input).split(/[,;\n]+/)) {
    const m = /^\s*([A-Za-z_][A-Za-z0-9_:.-]*)\s*[=:]\s*(.*?)\s*$/.exec(part);
    if (!m) continue;
    const key = m[1];
    const value = m[2].replace(/^["']|["']$/g, '');
    if (key && value && /^[A-Za-z_][A-Za-z0-9_:.-]*$/.test(key)) out[key] = value.slice(0, 120);
  }
  return out;
}

export function attrsToText(attrs = {}) {
  return Object.entries(attrs || {}).map(([k, v]) => `${k}=${v}`).join(', ');
}

/**
 * 归一化用户编辑过的规则：以 spec 派生的 DEFAULT_WIDGET_RULES 为基底，
 * tag 改动时自动切换到新标签的 spec（必填/事件属性随之改变）。
 */
export function normalizeRules(raw) {
  const src = Array.isArray(raw)
    ? Object.fromEntries(raw.filter((r) => r && typeof r === 'object').map((r) => [r.widget, r]))
    : (raw && typeof raw === 'object' ? raw : {});
  return DEFAULT_WIDGET_RULES.map((base) => {
    const o = src[base.widget];
    if (!o || typeof o !== 'object') return { ...base, attrs: { ...base.attrs }, spec: { ...base.spec } };
    const tag = text(o.tag, base.tag);
    const validTag = /^[A-Za-z_][A-Za-z0-9_.:-]*$/.test(tag) && WIDGET_SCHEMA[tag] ? tag : base.tag;
    const attrs = typeof o.attrs === 'string'
      ? parseAttrs(o.attrs)
      : (o.attrs && typeof o.attrs === 'object' ? { ...o.attrs } : { ...base.attrs });
    return {
      ...base,
      tag: validTag,
      className: WIDGET_SCHEMA[validTag]?.className || base.className,
      attrs,
      spec: validTag === base.tag ? { ...base.spec } : specForTag(validTag),
      enabled: o.enabled !== false,
      focusable: o.focusable === undefined ? base.focusable : Boolean(o.focusable),
      note: text(o.note),
    };
  });
}

export function rulesForUi(rules) {
  return normalizeRules(rules).map((r) => ({
    widget: r.widget,
    label: r.label,
    control: r.control,
    tag: r.tag,
    className: r.className,
    requiredAttributes: r.spec.required,
    eventAttributes: r.spec.eventAttributes,
    attrs: attrsToText(r.attrs),
    enabled: r.enabled,
    focusable: r.focusable,
  }));
}

/** 所有可选的真实 Maskat 标签（页面上改映射时用） */
export function availableTags() {
  return Object.values(WIDGET_SCHEMA)
    .filter((w) => w.positionable)
    .map((w) => ({ tag: w.tag, className: w.className, source: w.source }))
    .sort((a, b) => a.tag.localeCompare(b.tag));
}

const ID_PREFIX = {
  text: 'txt', number: 'num', date: 'dat', textarea: 'txa', password: 'pwd',
  select: 'sel', radio: 'rad', checkbox: 'chk', list: 'lst',
  label: 'lbl', button: 'btn', header: 'hdr', title: 'ttl', grid: 'grd', static: 'stc',
};

export function widgetId(widget, seq, prefix = '') {
  const p = text(prefix).replace(/[^A-Za-z0-9_]/g, '').slice(0, 12);
  const code = ID_PREFIX[widget] || 'wdg';
  return `${p}${code}${String(seq).padStart(3, '0')}`;
}

/** 把项目名 / 标签类项目关联到它右侧的输入框或选择项 */
export function attachLabels(widgets) {
  const labels = widgets.filter((w) => w.control === 'other' && (w.widget === 'label' || w.type === 'label'));
  for (const w of widgets) {
    if (w.label) continue;
    if (w.control !== 'input' && w.control !== 'choice') continue;
    const cy = w.top + w.height / 2;
    let best = null;
    for (const l of labels) {
      const right = l.left + l.width;
      const gap = w.left - right;
      if (gap < -10 || gap > 240) continue;
      const lcy = l.top + l.height / 2;
      if (Math.abs(lcy - cy) > Math.max(26, Math.min(w.height, l.height))) continue;
      if (!best || right > best.left + best.width) best = l;
    }
    if (best) {
      w.label = best.name;
      w.labelRef = best.id;
    }
  }
  return widgets;
}

/**
 * 事件属性名按 spec 校验：Excel / 页面里写的事件若该 widget 不支持，
 * 映射到同类合法事件（不丢事件，也不生成非法 XML）。
 */
function resolveEvent(name, allowed, notes, widgetName) {
  if (!allowed?.length) {
    notes.push(`${widgetName}: <${name}> 所属 widget 没有 event 属性，已忽略 ${name}`);
    return null;
  }
  if (allowed.includes(name)) return name;
  const lower = name.toLowerCase();
  const exact = allowed.find((a) => a.toLowerCase() === lower);
  if (exact) return exact;
  const hints = [
    [/leave|previous|blur/i, ['funcOnLeave', 'validating_leave', 'funcOnPrevious', 'validating_previous']],
    [/edit|change|input/i, ['onEdit', 'styleFormatter', 'funcOnLeave']],
    [/format|style/i, ['styleFormatter']],
    [/dialog/i, ['dialogSelected', 'dialogClosed', 'dialogCreateParameter']],
    [/click|cell|row/i, ['styleFormatter', 'onCellContextMenuKeyCode', 'funcOnLeave']],
  ];
  for (const [re, candidates] of hints) {
    if (!re.test(lower)) continue;
    const hit = candidates.find((c) => allowed.includes(c));
    if (hit) { notes.push(`${widgetName}: event「${name}」不是该 widget 的合法属性，已映射为「${hit}」`); return hit; }
  }
  const fallback = allowed[0];
  notes.push(`${widgetName}: event「${name}」不是该 widget 的合法属性，已映射为「${fallback}」`);
  return fallback;
}

/** spec 必填属性的合理默认值（数据绑定名等无法从图片推断） */
function requiredValues(w, spec) {
  const out = {};
  for (const a of spec.required) {
    if (['name', 'top', 'left', 'width', 'height'].includes(a)) continue;
    if (a === 'text') out[a] = w.label || w.name;
    else if (a === 'field') out[a] = w.id;
    else if (a === 'group') out[a] = w.label ? `${w.label}Group` : 'defaultGroup';
    else if (a === 'maxlength') out[a] = spec.defaults.maxlength ?? '100';
    else out[a] = spec.defaults[a] ?? '';
  }
  return out;
}

/**
 * 项目列表 → widget 定义列表。
 * items 可以来自图片解析，也可以来自 Excel「画面項目 / 画面レイアウト」。
 */
export function buildWidgets(items = [], rawRules = [], options = {}) {
  const rules = normalizeRules(rawRules);
  const byWidget = Object.fromEntries(rules.map((r) => [r.widget, r]));
  const list = sortItems([...items]);
  const widgets = [];
  const warnings = [];
  const stats = { total: list.length, input: 0, choice: 0, other: 0, emitted: 0, skipped: 0, byWidget: {} };
  const counters = {};
  let tab = 0;

  for (const it of list) {
    const control = ['input', 'choice', 'other'].includes(it.control) ? it.control : 'other';
    const widget = RULE_BY_WIDGET[it.widget]
      ? it.widget
      : (CONTROL_BY_ID[control]?.defaultWidget || 'label');
    stats[control] += 1;
    stats.byWidget[widget] = (stats.byWidget[widget] || 0) + 1;

    const rule = byWidget[widget] || RULE_BY_WIDGET[widget];
    counters[widget] = (counters[widget] || 0) + 1;
    const id = text(it.id) || widgetId(widget, counters[widget], options.idPrefix);
    const focusable = rule?.focusable ?? (control !== 'other' || widget === 'button');
    // Excel 可直接写真实 Maskat 标签；图片解析的 item 没有 tag 字段，走映射规则
    const tag = (it.tag && WIDGET_SCHEMA[it.tag]) ? it.tag : (rule?.tag || widget);
    const spec = specForTag(tag);

    // 事件属性：Excel / 页面指定优先，否则用 spec 里该 widget 的第一个事件属性
    const declared = Array.isArray(it.events) ? it.events.filter(Boolean) : [];
    const events = {};
    for (const e of declared) {
      const name = text(e).split('=')[0];
      if (!name) continue;
      const value = text(e).includes('=') ? text(e).slice(text(e).indexOf('=') + 1) : '';
      const resolved = resolveEvent(name, spec.eventAttributes, warnings, it.name || id);
      if (!resolved) continue;
      events[resolved] = value || `on_${id}_${resolved}`;
    }

    const w = {
      id,
      name: it.name,
      label: text(it.label),
      control,
      widget,
      tag,
      className: WIDGET_SCHEMA[tag]?.className || rule?.className || '',
      attrs: { ...(rule?.attrs || {}) },
      eventAttributes: spec.eventAttributes,
      events,
      specRequired: spec.required,
      left: Number(it.left) || 0, top: Number(it.top) || 0,
      width: Number(it.width) || 0, height: Number(it.height) || 0,
      tabIndex: (rule?.enabled !== false && focusable) ? ++tab : 0,
      required: Boolean(it.required),
      options: Array.isArray(it.options) ? it.options.slice(0, 30) : [],
      text: text(it.text),
      confidence: Number(it.confidence) || 0,
      type: it.type || 'other',
      source: it.source || 'manual',
      enabled: rule?.enabled !== false,
      focusable,
    };
    w.requiredValues = requiredValues(w, spec);
    if (!w.enabled) {
      stats.skipped += 1;
      warnings.push(`${it.name}：widget「${widget}」的映射规则设为不输出，已跳过`);
    } else {
      stats.emitted += 1;
    }
    widgets.push(w);
  }

  attachLabels(widgets);
  return { widgets, stats, warnings };
}

/** XML 属性值转义 */
export function xmlEscape(v) {
  return String(v ?? '')
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&apos;');
}

const safeName = (s, fallback = 'pageLayout') => {
  const v = text(s)
    .replace(/[^A-Za-z0-9_\-.]/g, '_')
    .replace(/^[^A-Za-z]+/i, '');
  return (v || fallback).slice(0, 80);
};
const jsIdentifier = (s) => safeName(s, 'pageLayout').replace(/[^A-Za-z0-9_]/g, '_');
const px = (v) => `${Math.round(Number(v) || 0)}px`;

/** 生成严格符合 spec 的 layout.xml */
export function buildLayoutXml(widgets = [], image = {}, options = {}) {
  const layoutName = safeName(options.layoutName, 'pageLayout');
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!-- Maskat layout.xml - generated from /maskat-spec bindingConfiguration.',
    `     ${text(MASKAT_NOTE)}`,
    '     Structure: layoutDef > layout(name) > widget(...). -->',
    '<layoutDef>',
    `  <layout name="${xmlEscape(layoutName)}">`,
  ];
  const warnings = [];

  for (const w of widgets) {
    if (!w.enabled) continue;
    const spec = specForTag(w.tag);
    const allowed = new Set(spec.attributes);
    const pairs = [];
    const push = (k, v, warn = true) => {
      if (v === undefined || v === null || v === '') return;
      if (!allowed.has(k)) {
        if (warn) warnings.push(`${w.name}: 属性 ${k} 不属于 <${w.tag}>，已忽略`);
        return;
      }
      pairs.push([k, v]);
    };
    push('name', w.id);
    push('top', px(w.top));
    push('left', px(w.left));
    // 部分 widget（如 radioButton）spec 里没有宽高，静默跳过即可
    push('width', px(w.width), false);
    push('height', px(w.height), false);
    // spec 必填属性（field / maxlength / text / group ...）
    Object.entries(w.requiredValues || {}).forEach(([k, v]) => push(k, v));
    // 规则里用户自定义的额外属性
    Object.entries(w.attrs || {}).forEach(([k, v]) => { if (!pairs.some(([p]) => p === k)) push(k, v); });
    if (w.tabIndex && allowed.has('tabIndex')) pairs.push(['tabIndex', w.tabIndex]);
    if (w.required && allowed.has('required')) pairs.push(['required', 'true']);
    // 事件属性（layout 里直接绑定函数）
    Object.entries(w.events || {}).forEach(([k, v]) => push(k, v));

    const attrText = pairs.map(([k, v]) => `${k}="${xmlEscape(v)}"`).join(' ');
    // 候选项：只有 spec 允许 itemFileWriteStore 子元素的 widget 才输出
    const canStore = spec.children.includes('itemFileWriteStore');
    const children = (w.options || []).slice(0, 30);
    if (children.length && canStore) {
      lines.push(`    <${w.tag} ${attrText}>`);
      lines.push(`      <itemFileWriteStore name="${xmlEscape(w.id)}_store">`);
      for (const opt of children) {
        lines.push(`        <item name="${xmlEscape(opt)}" value="${xmlEscape(opt)}" />`);
      }
      lines.push('      </itemFileWriteStore>');
      lines.push(`    </${w.tag}>`);
    } else {
      lines.push(`    <${w.tag} ${attrText} />`);
    }
  }

  lines.push('  </layout>', '</layoutDef>');
  return { xml: `${lines.join('\n')}\n`, warnings };
}

/** 结构化 widget 定义（含 spec 元信息，便于程序消费 / 列表展示） */
export function buildWidgetsJson(widgets = [], image = {}, options = {}, stats = {}, warnings = []) {
  return `${JSON.stringify({
    schemaVersion: 'maskat-widgets.v2',
    specSource: '/maskat-spec/bindingConfiguration_*.txt',
    specMeta: { tagCount: SPEC_META.tagCount, positionableCount: SPEC_META.positionableCount },
    layoutName: safeName(options.layoutName, 'pageLayout'),
    image: {
      name: text(image.name),
      width: Number(image.width) || 0,
      height: Number(image.height) || 0,
    },
    unit: 'px',
    note: MASKAT_NOTE,
    stats,
    warnings,
    widgets: widgets.filter((w) => w.enabled).map((w) => ({
      id: w.id, name: w.name, label: w.label, control: w.control, widget: w.widget,
      tag: w.tag, className: w.className, attrs: { ...w.attrs, ...w.requiredValues },
      eventAttributes: w.eventAttributes, events: w.events,
      top: w.top, left: w.left, width: w.width, height: w.height,
      tabIndex: w.tabIndex, required: w.required, options: w.options,
      text: w.text, confidence: w.confidence, type: w.type, source: w.source,
      ...(w.labelRef ? { labelRef: w.labelRef } : {}),
    })),
  }, null, 2)}\n`;
}

/** event.xml：严格按 eventDef > component(id) > event(id) 结构 */
export function buildEventXml(widgets = [], options = {}) {
  const withEvents = widgets.filter((w) => w.enabled && Object.keys(w.events || {}).length);
  const lines = [
    '<?xml version="1.0" encoding="UTF-8"?>',
    '<!-- Maskat event.xml - structure from bindingConfiguration_event.txt.',
    '     eventDef > component(id) > event(id, type=local|remote). -->',
    '<eventDef>',
  ];
  if (!withEvents.length) {
    lines.push('  <!-- 当前没有绑定事件属性的 widget；在列表的 event 属性列中勾选后生成 -->');
  }
  for (const w of withEvents) {
    lines.push(`  <component id="${xmlEscape(w.id)}">`);
    for (const [evt, handler] of Object.entries(w.events)) {
      lines.push(`    <event id="${xmlEscape(evt)}" type="local" ref="${xmlEscape(handler)}" />`);
    }
    lines.push('  </component>');
  }
  lines.push('</eventDef>');
  return `${lines.join('\n')}\n`;
}

/** 页面 JavaScript 骨架 */
export function buildPageJs(widgets = [], options = {}) {
  const list = widgets.filter((w) => w.enabled);
  const lines = [
    '/**',
    ' * Maskat 页面 JavaScript 骨架（自动生成）。',
    ' * widget 标签与属性以 /maskat-spec 为准；事件函数名与 layout.xml / event.xml 对应。',
    ' */',
    `var ${jsIdentifier(options.layoutName)}_widgets = ${JSON.stringify(
      list.map((w) => ({
        id: w.id, name: w.name, tag: w.tag, control: w.control,
        top: w.top, left: w.left, width: w.width, tabIndex: w.tabIndex,
        events: Object.keys(w.events || {}),
      })),
      null, 2,
    )};`,
    '',
    'function initPage() {',
    '  // TODO: 按项目 Maskat 版本取得 widget 实例并绑定初始值',
    '}',
    '',
    'function validatePage() {',
    '  // TODO: 必填 / 数值 / 日期校验，required 字段见 widgets.json',
    '  return true;',
    '}',
    '',
  ];
  return `${lines.join('\n')}\n`;
}

/** 一次性生成全部产物（前端与 /api/maskat/build 共用） */
export function buildMaskatBundle({ items = [], image = {}, rules = [], options = {} } = {}) {
  const { widgets, stats, warnings } = buildWidgets(items, rules, options);
  const layout = buildLayoutXml(widgets, image, options);
  return {
    widgets,
    stats,
    warnings: [...warnings, ...layout.warnings],
    files: {
      'layout.xml': layout.xml,
      'widgets.json': buildWidgetsJson(widgets, image, options, stats, warnings),
      'event.xml': buildEventXml(widgets, options),
      'page.js': buildPageJs(widgets, options),
    },
  };
}

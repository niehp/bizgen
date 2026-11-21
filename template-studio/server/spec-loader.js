/**
 * Maskat widget 体系加载器。
 *
 * maskat-spec/bindingConfiguration_*.txt 是 Maskat 官方的 bindingConfiguration 定义
 * （JS 对象字面量：标签名 → { type, extend, attributes, children }）。
 * 本模块把它们解析成规范化的 schema，再由 schema 派生「控件分类 → widget 标签/属性」映射规则。
 *
 * 纯 Node.js，无第三方依赖；前后端共用（前端通过 /api/config 获取结果）。
 */
import fs from 'node:fs';
import path from 'node:path';

export const SPEC_FILES = [
  'bindingConfiguration_maskat.txt',
  'bindingConfiguration_event.txt',
  'bindingConfiguration_intramart.txt',
  'bindingConfiguration_intramartx.txt',
];

const text = (v, fallback = '') => String(v ?? fallback).trim();

/** 类引用 / plugin 调用的惰性代理：读到哪里就把路径拼成字符串 */
function refProxy(base) {
  const fn = function () {};
  return new Proxy(fn, {
    get(_t, key) {
      const k = String(key);
      if (k === Symbol.toPrimitive.toString() || k === 'toString' || k === 'valueOf') {
        return () => base;
      }
      if (k === 'getProperty') return () => undefined; // 让 `plugin.getProperty(x) || "默认"` 取默认值
      if (k === Symbol.iterator.toString()) return undefined;
      return refProxy(base ? `${base}.${k}` : k);
    },
    apply() { return base; },
  });
}

/**
 * 把 JS 对象字面量求值成普通对象。
 * 配置里混有未加引号的类引用（maskat.xxx.Wrapper）与 plugin.getProperty(...) 调用，
 * 用 with + Proxy 把这些未知标识符解析成路径字符串（仅处理本地可信文件）。
 */
export function evaluateBindingSource(src) {
  let s = String(src || '');
  s = s.replace(/\/\*[\s\S]*?\*\//g, '');
  s = s.replace(/(^|[^:])\/\/[^\n]*/g, '$1'); // 不误伤 http:// 这类字符串
  const t = s.trim();
  if (!t.startsWith('{')) s = `{\n${s}\n}`; // intramartx / maskat 是裸片段
  const scope = new Proxy({}, {
    has: () => true,
    get: (_t, key) => (key === Symbol.unscopables ? undefined : refProxy(String(key))),
  });
  // eslint-disable-next-line no-new-func
  const evaluate = new Function('scope', `with (scope) { return (${s}); }`);
  return evaluate(scope);
}

/** 读取并合并 4 份 bindingConfiguration */
export function loadBindingConfig(specDir) {
  const merged = {};
  const fileStats = [];
  for (const file of SPEC_FILES) {
    const p = path.join(specDir, file);
    if (!fs.existsSync(p)) continue;
    const raw = fs.readFileSync(p, 'utf8');
    let obj;
    try {
      obj = evaluateBindingSource(raw);
    } catch (e) {
      throw new Error(`${file} 解析失败: ${e.message}`);
    }
    let n = 0;
    for (const [tag, def] of Object.entries(obj || {})) {
      if (!def || typeof def !== 'object') continue;
      // 同名标签以后加载的定义为准，但记录来源
      merged[tag] = { ...(merged[tag] || {}), ...def, _source: file };
      n += 1;
    }
    fileStats.push({ file, bytes: Buffer.byteLength(raw), definitions: n });
  }
  if (!fileStats.length) throw new Error(`maskat-spec 中未找到 bindingConfiguration_*.txt（${specDir}）`);
  return { merged, fileStats };
}

const ATTR_TYPE_MAP = {
  string: 'string', length: 'length', number: 'number', boolean: 'boolean',
  enum: 'enum', function: 'function', object: 'object', array: 'array', any: 'any',
};

/** 事件属性：type=function，或属性名以 on 开头 */
export function isEventAttribute(name, def = {}) {
  return def?.type === 'function' || /^on[A-Z_]/.test(name) || /^(?:click|change|blur|focus|keydown|keyup)$/i.test(name);
}

/**
 * 规范化成 widget schema：
 * { tag, className, extend, attributes: {name:{type,required,defaultValue,values,property,event}},
 *   eventAttributes: [...], children: [...], positionable, source }
 */
export function buildWidgetSchema(merged) {
  const widgets = {};
  for (const [tag, def] of Object.entries(merged)) {
    if (tag === '#document' || tag.startsWith('#')) continue;
    const attrs = {};
    const eventAttributes = [];
    for (const [name, a] of Object.entries(def.attributes || {})) {
      if (!a || typeof a !== 'object') continue;
      const type = ATTR_TYPE_MAP[text(a.type, 'string').toLowerCase()] || 'string';
      const rec = {
        name,
        type,
        required: Boolean(a.required),
        property: text(a.property),
        values: Array.isArray(a.values) ? a.values.map(String) : undefined,
        defaultValue: a.defaultValue === undefined || typeof a.defaultValue === 'object'
          ? undefined : a.defaultValue,
      };
      if (type === 'function' && rec.defaultValue !== undefined) rec.defaultValue = String(rec.defaultValue);
      attrs[name] = rec;
      if (isEventAttribute(name, a)) eventAttributes.push(name);
    }
    // descriptor 型的 function 定义（如 onChange: { type: "function", property: "..." }）已包含在上面
    const attrNames = Object.keys(attrs);
    widgets[tag] = {
      tag,
      className: text(def.type),
      extend: text(def.extend),
      attributes: attrs,
      attributeNames: attrNames,
      eventAttributes,
      children: Object.keys(def.children || {}),
      repeatChildren: Object.entries(def.children || {})
        .filter(([, c]) => c && c.repeat).map(([k]) => k),
      // 可定位 widget：拥有 top / left（Maskat 用绝对定位）
      positionable: Boolean(attrs.top && attrs.left),
      hasSize: Boolean(attrs.width || attrs.height),
      requiredAttributes: attrNames.filter((n) => attrs[n].required),
      source: text(def._source),
    };
  }
  return widgets;
}

/** 顶层容器 / 事件定义（layoutDef / layout / eventDef / event 等；spec 中不存在 frame） */
export function buildContainerSchema(widgets) {
  const pick = (tag) => widgets[tag] || null;
  return {
    document: pick('#document'),
    layoutDef: pick('layoutDef'),
    layout: pick('layout'),
    eventDef: pick('eventDef'),
    event: pick('event'),
  };
}

export function loadSpec(specDir) {
  const { merged, fileStats } = loadBindingConfig(specDir);
  const widgets = buildWidgetSchema(merged);
  const containers = buildContainerSchema(widgets);
  return {
    specDir,
    fileStats,
    widgets,
    containers,
    tagCount: Object.keys(widgets).length,
    positionableTags: Object.values(widgets).filter((w) => w.positionable).map((w) => w.tag),
  };
}

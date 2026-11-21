/**
 * 画面项目（项目名 + TOP / LEFT / WIDTH）的统一数据模型。
 * 纯函数，前后端共用；内部一律以「原图像素 px」保存，展示/导出时再换算单位。
 *
 * item = {
 *   id, name, top, left, width, height,   // px，基于原图左上角
 *   type, text, confidence, source,
 *   control, widget, label, options, required  // 控件分类（输入框 / 选择项 / 其它）
 * }
 */

export const UNITS = [
  { id: 'px', label: 'px（原图像素）' },
  { id: '%', label: '%（相对画布）' },
  { id: 'norm1000', label: '0–1000（归一化）' },
];

/** 视觉类型（与语义分类 control 分开：类型描述长什么样，分类描述能不能输入/选择） */
export const ITEM_TYPES = [
  'label', 'input', 'select', 'radio', 'checkbox',
  'button', 'header', 'title', 'text', 'other',
];

/** 控件大类：输入框 / 选择项 / 其它（用于 Maskat widget 分类） */
export const CONTROL_CLASSES = [
  { id: 'input', label: '输入框', defaultWidget: 'text' },
  { id: 'choice', label: '选择项', defaultWidget: 'select' },
  { id: 'other', label: '其它', defaultWidget: 'label' },
];

/** widget 细分类型（id 同时是默认 Maskat 标签名，可在映射规则中改写） */
export const WIDGET_TYPES = [
  { id: 'text', label: '单行文本', control: 'input' },
  { id: 'number', label: '数值输入', control: 'input' },
  { id: 'date', label: '日期输入', control: 'input' },
  { id: 'textarea', label: '多行文本', control: 'input' },
  { id: 'password', label: '密码输入', control: 'input' },
  { id: 'select', label: '下拉选择', control: 'choice' },
  { id: 'radio', label: '单选按钮', control: 'choice' },
  { id: 'checkbox', label: '复选框', control: 'choice' },
  { id: 'list', label: '列表选择', control: 'choice' },
  { id: 'label', label: '项目名/标签', control: 'other' },
  { id: 'button', label: '按钮', control: 'other' },
  { id: 'header', label: '列头/标题', control: 'other' },
  { id: 'title', label: '页面标题', control: 'other' },
  { id: 'grid', label: '表格', control: 'other' },
  { id: 'static', label: '静态文本', control: 'other' },
];

export const WIDGET_BY_ID = Object.fromEntries(WIDGET_TYPES.map((w) => [w.id, w]));
export const CONTROL_BY_ID = Object.fromEntries(CONTROL_CLASSES.map((c) => [c.id, c]));

export const clamp = (v, lo, hi) => Math.min(hi, Math.max(lo, v));

export const toInt = (v, fallback = 0) => {
  const n = Number(v);
  return Number.isFinite(n) ? Math.round(n) : fallback;
};

const text = (v, fallback = '') => String(v ?? fallback).replace(/\s+/g, ' ').trim();

const round = (v, digits = 0) => {
  const p = 10 ** digits;
  return Math.round(Number(v) * p) / p;
};

/** 规则兜底：模型没给分类、或用户手工标注时，按项目名/文本/类型推断 widget */
function inferWidget(item) {
  const s = `${text(item.name)} ${text(item.text)} ${text(item.widgetHint)}`.toLowerCase();
  const t = text(item.type, 'other').toLowerCase();
  // 类型已明确是纯展示/按钮时直接返回，避免标签名里的「金額」「取引日」被误判成输入控件
  if (t === 'label' || t === 'text') return 'label';
  if (t === 'header') return 'header';
  if (t === 'title') return 'title';
  if (t === 'button') return 'button';
  if (t === 'select') return 'select';
  if (t === 'radio') return 'radio';
  if (t === 'checkbox') return 'checkbox';
  if (t === 'grid') return 'grid';
  // 以下是可输入 / 可选择控件，按名称与画面文本推断细分类型
  if (/checkbox|check|复选|多选|チェック|☑|□/.test(s)) return 'checkbox';
  if (/radio|单选|ラジオ|◎|◉/.test(s)) return 'radio';
  if (/select|combo|下拉|プルダウン|コンボ|選択|▼|▽/.test(s)) return 'select';
  if (/listbox|列表选择|一覧選択/.test(s)) return 'list';
  if (/date|日期|年月日|カレンダ|calendar|取引日/.test(s)) return 'date';
  if (/password|密码|パスワード/.test(s)) return 'password';
  if (/amount|金额|金額|数量|number|数値/.test(s)) return 'number';
  if (/textarea|摘要|備考|备注|多行|複数行/.test(s)) return 'textarea';
  if (t === 'input') return 'text';
  return 'static';
}

/** 大模型可能返回中文分类名 / 别名，这里统一收敛到内部 id */
const CONTROL_ALIAS = {
  '输入框': 'input', '收入框': 'input', '输入': 'input', '入力欄': 'input', '入力项目': 'input',
  '文本输入': 'input', input: 'input', field: 'input',
  '选择项': 'choice', '選択項目': 'choice', '選択': 'choice', '下拉': 'choice', '选择': 'choice',
  choice: 'choice', selectable: 'choice',
  '其它': 'other', '其他': 'other', other: 'other', '其它项目': 'other',
};
const WIDGET_ALIAS = {
  textbox: 'text', textfield: 'text', text_field: 'text', input: 'text', '单行文本': 'text',
  combobox: 'select', dropdown: 'select', pulldown: 'select', combo: 'select', '下拉框': 'select',
  radiobutton: 'radio', radiogroup: 'radio', '单选框': 'radio',
  check: 'checkbox', checkbutton: 'checkbox', '复选框': 'checkbox',
  listbox: 'list', '列表框': 'list',
  calendar: 'date', datepicker: 'date', '日期框': 'date',
  memo: 'textarea', multiline: 'textarea', '文本域': 'textarea',
  numeric: 'number', '数值框': 'number',
  caption: 'label', statictext: 'static', '静态文本': 'static',
};

/** 保证 control 与 widget 一致（解析兜底用） */
export function classifyItem(item = {}) {
  const rawControl = text(item.control).toLowerCase();
  let control = CONTROL_ALIAS[rawControl] || rawControl;
  const rawWidget = text(item.widget).toLowerCase();
  let widget = WIDGET_ALIAS[rawWidget] || rawWidget;
  if (!CONTROL_BY_ID[control]) {
    if (WIDGET_BY_ID[widget]) control = WIDGET_BY_ID[widget].control;
    else control = 'other';
  }
  if (!WIDGET_BY_ID[widget]) widget = inferWidget(item);
  const w = WIDGET_BY_ID[widget];
  if (w.control !== control) {
    // 明确的 control 优先：把冲突的 widget 换成该类的默认类型
    widget = CONTROL_BY_ID[control].defaultWidget;
    if (control === 'input' && /date|日期|年月日/.test(`${text(item.name)} ${text(item.text)}`)) widget = 'date';
    if (control === 'input' && /amount|金额|金額|数量/.test(`${text(item.name)} ${text(item.text)}`)) widget = 'number';
  }
  return { control, widget };
}

/** 表格里改 control / widget 时同步另一个字段 */
export function syncControlWidget(item, field, value) {
  if (field === 'widget') {
    const w = WIDGET_BY_ID[value];
    return w ? { widget: value, control: w.control } : {};
  }
  if (field === 'control') {
    const c = CONTROL_BY_ID[value] ? value : 'other';
    const cur = WIDGET_BY_ID[item.widget];
    return {
      control: c,
      widget: cur && cur.control === c ? item.widget : CONTROL_BY_ID[c].defaultWidget,
    };
  }
  return {};
}

let seq = 0;
export const uid = () => `it_${Date.now().toString(36)}_${(seq++).toString(36)}_${Math.random().toString(36).slice(2, 6)}`;

/** 从模型/页面给出的各种形状里取出 {name, top, left, width, height, coordSpace, ...} */
function readRaw(raw) {
  if (!raw || typeof raw !== 'object') return null;
  const name = text(raw.name ?? raw.itemName ?? raw.item_name ?? raw.label ?? raw.project ?? raw['项目名']);
  if (!name) return null;

  let top;
  let left;
  let width;
  let height;
  let coordSpace = text(raw.coordSpace ?? raw.coordinateSpace ?? raw.unit).toLowerCase();

  const bbox = raw.bbox ?? raw.box ?? raw.boundingBox ?? raw.rect ?? raw.bounds;
  if (Array.isArray(bbox) && bbox.length >= 4 && bbox.every((v) => Number.isFinite(Number(v)))) {
    const [x1, y1, x2, y2] = bbox.map(Number);
    const fmt = text(raw.boxFormat ?? raw.bboxFormat).toLowerCase();
    if (fmt.includes('xywh') || fmt.includes('ltwh')) {
      left = x1; top = y1; width = x2; height = y2;
    } else {
      left = Math.min(x1, x2); top = Math.min(y1, y2);
      width = Math.abs(x2 - x1); height = Math.abs(y2 - y1);
      if (raw.boxFormat === undefined && x2 <= x1 && y2 <= y1) {
        // 明显不是右下角坐标时按 x,y,w,h 兜底
        left = x1; top = y1; width = x2; height = y2;
      }
    }
  } else {
    top = Number(raw.top ?? raw.y ?? raw.y1);
    left = Number(raw.left ?? raw.x ?? raw.x1);
    width = Number(raw.width ?? raw.w ?? (Number.isFinite(Number(raw.right)) ? Number(raw.right) - left : NaN));
    height = Number(raw.height ?? raw.h ?? (Number.isFinite(Number(raw.bottom)) ? Number(raw.bottom) - top : NaN));
  }

  if (![top, left, width, height].every(Number.isFinite)) return null;

  if (!coordSpace || !['px', 'norm1000', '1000', 'percent', '%'].includes(coordSpace)) {
    const max = Math.max(...[top, left, width, height].map((n) => Math.abs(n)));
    coordSpace = max > 1000 ? 'px' : 'norm1000';
  }
  if (coordSpace === '1000') coordSpace = 'norm1000';
  if (coordSpace === '%') coordSpace = 'percent';

  return {
    name,
    top, left, width, height,
    coordSpace,
    type: text(raw.type ?? raw.kind, 'other').toLowerCase(),
    textValue: text(raw.text ?? raw.value ?? raw.content),
    confidence: Number(raw.confidence),
    source: text(raw.source, 'vision'),
    control: text(raw.control ?? raw.controlClass ?? raw.category ?? raw.categoryName),
    widget: text(raw.widget ?? raw.widgetType ?? raw.maskatWidget ?? raw.component),
    label: text(raw.label ?? raw.itemLabel ?? raw.fieldLabel ?? raw.caption),
    options: (Array.isArray(raw.options) ? raw.options : Array.isArray(raw.choices) ? raw.choices : [])
      .map((o) => (o && typeof o === 'object' ? text(o.label ?? o.value ?? o.text ?? o.name) : text(o)))
      .filter(Boolean).slice(0, 30),
    required: raw.required === true || raw.required === 1 || raw.required === 'true' || raw.required === '是',
    widgetHint: text(raw.widgetHint ?? raw.hint),
  };
}

/** 把任意坐标空间换算为原图像素 */
function toPixels(r, image) {
  const W = Math.max(1, Number(image?.width) || 1);
  const H = Math.max(1, Number(image?.height) || 1);
  let { top, left, width, height } = r;
  if (r.coordSpace === 'norm1000') {
    left = (left / 1000) * W;
    width = (width / 1000) * W;
    top = (top / 1000) * H;
    height = (height / 1000) * H;
  } else if (r.coordSpace === 'percent') {
    left = (left / 100) * W;
    width = (width / 100) * W;
    top = (top / 100) * H;
    height = (height / 100) * H;
  }
  return { top, left, width, height };
}

/**
 * 归一化：坐标换算 → 裁剪到画布 → 丢弃无效 → 去重 → 按阅读顺序排序。
 * 返回 { items, dropped: [{name, reason}] }
 */
export function normalizeItems(rawItems = [], image = {}, options = {}) {
  const W = Math.max(1, toInt(image.width, 1));
  const H = Math.max(1, toInt(image.height, 1));
  const limit = Math.max(1, Math.min(1000, toInt(options.maxItems, 300)));
  const items = [];
  const dropped = [];

  for (const raw of (Array.isArray(rawItems) ? rawItems : [])) {
    const r = readRaw(raw);
    if (!r) { dropped.push({ name: text(raw?.name, '(无名)'), reason: '缺少项目名或坐标' }); continue; }
    const p = toPixels(r, { width: W, height: H });
    const left = clamp(toInt(p.left), 0, W - 1);
    const top = clamp(toInt(p.top), 0, H - 1);
    const width = clamp(toInt(p.width), 0, W - left);
    const height = clamp(toInt(p.height), 0, H - top);
    if (width < 2 || height < 2) {
      dropped.push({ name: r.name, reason: '框尺寸过小或超出图片范围' });
      continue;
    }
    const key = `${r.name}|${Math.round(left / 12)}|${Math.round(top / 12)}`;
    if (items.some((it) => it._key === key)) {
      dropped.push({ name: r.name, reason: '与已有项目重复' });
      continue;
    }
    const cls = classifyItem({ ...r, name: r.name, type: r.type, text: r.textValue });
    const item = {
      id: text(raw.id) || uid(),
      name: r.name.slice(0, 200),
      top, left, width, height,
      type: ITEM_TYPES.includes(r.type) ? r.type : 'other',
      text: r.textValue.slice(0, 300),
      confidence: Number.isFinite(r.confidence) ? clamp(round(r.confidence, 2), 0, 1) : 0,
      source: r.source,
      ...cls,
      label: r.label.slice(0, 200),
      options: r.options,
      required: r.required,
      _key: key,
    };
    items.push(item);
    if (items.length >= limit) break;
  }

  sortItems(items);
  return { items: items.map(({ _key, ...it }) => it), dropped };
}

/** 阅读顺序：同一行（±10px）按 LEFT，否则按 TOP */
export function sortItems(items = []) {
  items.sort((a, b) => {
    if (Math.abs(a.top - b.top) > 10) return a.top - b.top;
    if (a.left !== b.left) return a.left - b.left;
    return a.name.localeCompare(b.name, 'zh-CN');
  });
  return items;
}

/** px → 展示单位 */
export function convertBox(item, unit, image) {
  const W = Math.max(1, toInt(image?.width, 1));
  const H = Math.max(1, toInt(image?.height, 1));
  const box = {
    top: toInt(item.top), left: toInt(item.left),
    width: toInt(item.width), height: toInt(item.height),
  };
  if (unit === '%') {
    return {
      top: round((box.top / H) * 100, 2), left: round((box.left / W) * 100, 2),
      width: round((box.width / W) * 100, 2), height: round((box.height / H) * 100, 2),
    };
  }
  if (unit === 'norm1000') {
    return {
      top: round((box.top / H) * 1000), left: round((box.left / W) * 1000),
      width: round((box.width / W) * 1000), height: round((box.height / H) * 1000),
    };
  }
  return box;
}

/** 展示/导出用的项目行（带编号） */
export function itemRows(items, unit, image) {
  return items.map((it, i) => ({
    no: i + 1,
    name: it.name,
    ...convertBox(it, unit, image),
    type: it.type || 'other',
    text: it.text || '',
    confidence: it.confidence ?? 0,
    control: it.control || 'other',
    widget: it.widget || 'static',
    label: it.label || '',
    options: Array.isArray(it.options) ? it.options : [],
    required: Boolean(it.required),
  }));
}

export function buildOutput({ image = {}, items = [], unit = 'px', meta = {} }) {
  return {
    schemaVersion: 'page-item-layout.v1',
    unit,
    image: {
      name: text(image.name),
      width: toInt(image.width),
      height: toInt(image.height),
      mime: text(image.mime),
    },
    generatedAt: new Date().toISOString(),
    source: meta.source || 'manual',
    itemCount: items.length,
    items: itemRows(items, unit, image),
  };
}

const csvCell = (v) => {
  const s = String(v ?? '');
  return /[",\r\n]/.test(s) ? `"${s.replace(/"/g, '""')}"` : s;
};

/** Excel 直接打开不乱码：加 UTF-8 BOM */
export function toCsv(items, unit, image) {
  const rows = itemRows(items, unit, image);
  const head = ['序号', '项目名', 'TOP', 'LEFT', 'WIDTH', 'HEIGHT', '控件分类', 'Widget', '关联标签', '候选项', '必填', '类型', '文本'];
  const lines = [head.join(',')];
  const classLabel = { input: '输入框', choice: '选择项', other: '其它' };
  for (const r of rows) {
    lines.push([
      r.no, r.name, r.top, r.left, r.width, r.height,
      classLabel[r.control] || '其它', r.widget, r.label,
      (r.options || []).join(' / '), r.required ? '是' : '', r.type, r.text,
    ].map(csvCell).join(','));
  }
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

/** 表格里手工改坐标后，把数值按当前单位换算回 px */
export function fromDisplay(field, value, unit, image) {
  const n = Number(value);
  if (!Number.isFinite(n)) return null;
  const W = Math.max(1, toInt(image?.width, 1));
  const H = Math.max(1, toInt(image?.height, 1));
  const vertical = field === 'top' || field === 'height';
  const full = vertical ? H : W;
  if (unit === '%') return (n / 100) * full;
  if (unit === 'norm1000') return (n / 1000) * full;
  return n;
}

/** 手工标注：以两个屏幕点创建合法框 */
export function boxFromPoints(a, b, image) {
  const W = Math.max(1, toInt(image?.width, 1));
  const H = Math.max(1, toInt(image?.height, 1));
  const left = clamp(Math.round(Math.min(a.x, b.x)), 0, W - 1);
  const top = clamp(Math.round(Math.min(a.y, b.y)), 0, H - 1);
  const width = clamp(Math.round(Math.abs(b.x - a.x)), 0, W - left);
  const height = clamp(Math.round(Math.abs(b.y - a.y)), 0, H - top);
  return { left, top, width, height };
}

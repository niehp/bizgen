/**
 * 「画面項目」+「画面レイアウト」两表 → widget 项目列表。
 *
 * 输出 items 与图片解析结果同构，可直接喂给 shared/maskat.js 生成 layout.xml；
 * 列表列所需字段（widget 名字 / 类型 / TOP / LEFT / WIDTH / event 属性）都在 item 上。
 * 纯函数，前后端共用。
 */
import { WIDGET_TYPES, CONTROL_CLASSES } from './layout.js';
import { DEFAULT_WIDGET_RULES, WIDGET_SCHEMA } from '../maskat-spec/widget-rules.js';

const text = (v) => String(v ?? '').trim();
const norm = (v) => text(v).replace(/[\s\u3000（）()【】\[\]：:／\/・_－-]/g, '').toLowerCase();

/** 列名别名（中日英） */
const FIELD_ALIASES = {
  id: ['項目ID', '項目番号', '項目CD', '項目コード', '項番', '項目No', 'No', 'ID', 'ItemId', '项目ID', '项目编号', 'PJT項目ID'],
  name: ['項目名', '項目名称', '名称', 'Widget名', 'widget名', 'ウィジェット名', 'ラベル名', '项目名', 'widgetName', 'Name', 'Label', 'PJT項目名'],
  widget: ['Widget種別', 'widget種別', 'WidgetType', 'widget', 'Widget', '部品種別', 'コントロール種別', '控件类型', '項目種別', '種別', 'タイプ', '型', 'Type', '控件', 'ウィジェット種別'],
  control: ['入力区分', 'コントロール分類', '控件分类', '区分', '分類', 'Control', '分类', '入力種別'],
  event: ['イベント属性', 'イベント', 'event属性', 'Event', 'event', 'events', '事件属性', '事件', 'EventAttributes'],
  required: ['必須区分', '必須', 'required', '必填', 'Required'],
  options: ['候補値', '選択肢', '候補', 'options', 'Options', '列表值', '下拉选项'],
  label: ['関連ラベル', 'ラベル', 'Label', 'label', '关联标签'],
  top: ['TOP', 'top', 'Top', '上位置', 'Y座標', 'Y', '上', '上辺', 'top位置', 'Y坐标'],
  left: ['LEFT', 'left', 'Left', '左位置', 'X座標', 'X', '左', '左辺', 'left位置', 'X坐标'],
  width: ['WIDTH', 'width', 'Width', '幅', '横幅', '宽度', 'W', '宽'],
  height: ['HEIGHT', 'height', 'Height', '高さ', '高度', 'H', '高'],
  tab: ['TAB', 'tabIndex', 'TabIndex', 'タブオーダー', 'タブ順', 'TAB順'],
  text: ['表示テキスト', '初期値', 'デフォルト値', '既定値', 'Text', 'text', 'Value', '値'],
};

const ALIAS_MAP = new Map();
for (const [field, list] of Object.entries(FIELD_ALIASES)) {
  for (const a of list) {
    const k = norm(a);
    if (!ALIAS_MAP.has(k)) ALIAS_MAP.set(k, field);
  }
}

/** 在前 25 行里找出最像表头的一行 */
export function detectHeader(rows = []) {
  let best = { index: -1, map: {}, score: 0 };
  for (let i = 0; i < Math.min(rows.length, 25); i += 1) {
    const row = rows[i] || [];
    const map = {};
    let score = 0;
    row.forEach((cell, col) => {
      const field = ALIAS_MAP.get(norm(cell));
      if (field && !(field in map)) { map[field] = col; score += 1; }
    });
    if (score > best.score) best = { index: i, map, score };
  }
  return best;
}

export function readSheetTable(sheet) {
  const rows = Array.isArray(sheet?.rows) ? sheet.rows : [];
  const header = detectHeader(rows);
  if (header.index < 0 || header.score < 2) {
    return { rows: [], header, warnings: [`工作表「${sheet?.name || ''}」未识别出表头行`] };
  }
  const map = header.map;
  const out = [];
  for (let i = header.index + 1; i < rows.length; i += 1) {
    const r = rows[i] || [];
    const get = (f) => (map[f] === undefined ? '' : text(r[map[f]]));
    const rec = {};
    for (const f of Object.keys(FIELD_ALIASES)) rec[f] = get(f);
    if (!Object.values(rec).some(Boolean)) continue;
    // 跳过说明行 / 合计行
    if (/^(合計|計|備考|注|※|注意)/.test(rec.name || rec.id || '')) continue;
    rec._row = i + 1;
    out.push(rec);
  }
  return { rows: out, header, warnings: [] };
}

/** 真实 Maskat 标签 → 抽象 widget 类型（标签重复时保留第一条，如 textBox 归 text 而非 password） */
const TAG_TO_WIDGET = {};
for (const r of DEFAULT_WIDGET_RULES) {
  const k = norm(r.tag);
  if (!(k in TAG_TO_WIDGET)) TAG_TO_WIDGET[k] = r.widget;
}
const WIDGET_ALIASES = {
  text: ['textbox', 'text', 'テキスト', '文本框', '入力欄', '输入框', '收入框', '单行文本', '文字'],
  number: ['numbertextbox', 'number', '数値', '数值', '金额', '金額', '数量'],
  date: ['datetextbox', 'date', '日付', '日期', '年月日', 'calendar'],
  password: ['password', '密码', 'パスワード'],
  textarea: ['textarea', 'simpletextarea', 'テキストエリア', '文本域', '多行', '摘要', '備考'],
  select: ['filteringselect', 'select', 'combobox', 'コンボ', '下拉', 'プルダウン', '选择', '選択', 'combobox'],
  radio: ['radiobutton', 'radio', 'ラジオ', '单选', '单选框'],
  checkbox: ['checkbox', 'check', 'チェック', '复选', '复选框'],
  list: ['selectbox', 'listbox', 'list', 'リスト', '列表'],
  label: ['label', 'ラベル', '标签', '項目名', '项目名'],
  button: ['button', 'ボタン', '按钮'],
  header: ['headerlabel', 'header', '見出し', '列头', '表头'],
  title: ['title', 'タイトル', '标题'],
  grid: ['grid', 'グリッド', '表格', '表'],
  static: ['readtextbox', 'readonly', '固定', '只读', '静的'],
};
const WIDGET_LOOKUP = new Map();
for (const [w, list] of Object.entries(WIDGET_ALIASES)) {
  for (const a of list) WIDGET_LOOKUP.set(norm(a), w);
}
for (const [tag, w] of Object.entries(TAG_TO_WIDGET)) WIDGET_LOOKUP.set(tag, w);

export function resolveWidget(raw) {
  const k = norm(raw);
  if (!k) return { widget: 'label', tag: 'label' };
  const w = WIDGET_LOOKUP.get(k) || (TAG_TO_WIDGET[k] ? TAG_TO_WIDGET[k] : null);
  const widget = w || (WIDGET_SCHEMA[k] ? 'text' : 'label');
  const rule = DEFAULT_WIDGET_RULES.find((r) => r.widget === widget);
  return { widget, tag: w ? (rule?.tag || widget) : (WIDGET_SCHEMA[k] ? k : 'label') };
}

export function resolveControl(raw, widget) {
  const k = norm(raw);
  if (/入力|input|テキスト|文本|收入框/.test(k)) return 'input';
  if (/選択|选择|choice|下拉|プルダウン|リスト|单选|复选/.test(k)) return 'choice';
  if (/その他|其他|other|ラベル|标签|ボタン|按钮/.test(k)) return 'other';
  const def = WIDGET_TYPES.find((w) => w.id === widget);
  return def?.control || 'other';
}

function splitList(raw) {
  return text(raw).split(/[,;、\n|]+/).map((s) => text(s)).filter(Boolean);
}

const num = (v, fallback = 0) => {
  const n = Number(text(v).replace(/[^\d.\-]/g, ''));
  return Number.isFinite(n) ? n : fallback;
};

/** 找目标 sheet（按名称，找不到则按顺序回退） */
function pickSheet(sheets, pattern, fallbackIndex) {
  const idx = sheets.findIndex((s) => pattern.test(text(s.name)));
  return idx >= 0 ? { sheet: sheets[idx], matched: true } : { sheet: sheets[fallbackIndex] || null, matched: false };
}

/**
 * 主入口：Excel 全部 sheet → widget 项目列表。
 * @returns {{ items, sheets, stats, warnings, headers }}
 */
export function widgetsFromExcel(sheets = []) {
  const warnings = [];
  if (!sheets.length) throw new Error('Excel 中没有可读取的工作表');

  const itemPick = pickSheet(sheets, /画面項目|画面项目|項目定義|項目一覧|項目定義書|items?/i, 0);
  const layoutPick = pickSheet(sheets, /画面レイアウト|画面レイアウト|レイアウト|layout|配置/i, 1);
  if (!itemPick.matched) warnings.push(`未找到「画面項目」工作表，改用「${itemPick.sheet?.name || ''}」`);
  if (!layoutPick.matched) warnings.push(`未找到「画面レイアウト」工作表，改用「${layoutPick.sheet?.name || ''}」`);
  if (itemPick.sheet === layoutPick.sheet && sheets.length > 1) {
    warnings.push('两张工作表被识别为同一张，请检查 sheet 名称');
  }

  const a = readSheetTable(itemPick.sheet);
  const b = readSheetTable(layoutPick.sheet);
  warnings.push(...a.warnings, ...b.warnings);

  const layoutById = new Map();
  const layoutByName = new Map();
  for (const r of b.rows) {
    if (r.id) layoutById.set(norm(r.id), r);
    if (r.name) layoutByName.set(norm(r.name), r);
  }
  const layoutRows = b.rows;

  const items = [];
  let matched = 0;
  const usedLayout = new Set();
  a.rows.forEach((r, i) => {
    let l = (r.id && layoutById.get(norm(r.id)))
      || (r.name && layoutByName.get(norm(r.name)))
      || null;
    if (!l && layoutRows.length === a.rows.length) { l = layoutRows[i]; }
    if (l) { matched += 1; usedLayout.add(l); }

    // 项目表优先（名字/类型），布局表补坐标；空值不覆盖另一张表的有效值
    const src = {};
    for (const row of [l, r]) {
      for (const [k, v] of Object.entries(row || {})) {
        if (v !== '' && v !== undefined && v !== null) src[k] = v;
      }
    }
    const { widget, tag } = resolveWidget(src.widget || src.text);
    const control = resolveControl(src.control, widget);
    const rule = DEFAULT_WIDGET_RULES.find((x) => x.widget === widget);
    const specEvents = WIDGET_SCHEMA[tag]?.eventAttributes || rule?.spec?.eventAttributes || [];
    const events = splitList(src.event);
    const id = text(src.id) || `excel_${String(i + 1).padStart(3, '0')}`;

    items.push({
      id,
      name: text(src.name) || text(src.label) || id,
      control,
      widget,
      tag,
      label: text(src.label),
      top: num(src.top),
      left: num(src.left),
      width: num(src.width, 160),
      height: num(src.height, 28),
      tabIndex: num(src.tab),
      required: /必須|required|true|1|○|有/i.test(text(src.required)),
      options: splitList(src.options),
      events,
      eventAttributes: specEvents,
      text: text(src.text),
      type: widget === 'label' ? 'label' : widget,
      confidence: 1,
      source: 'excel',
      excelRow: r._row,
    });
  });

  const unmatchedLayout = layoutRows.filter((r) => !usedLayout.has(r));
  if (unmatchedLayout.length) {
    warnings.push(`画面レイアウト 中有 ${unmatchedLayout.length} 行未匹配到画面項目（可能只是区块/坐标辅助行）`);
  }
  if (!items.length) warnings.push('画面項目 表中没有可生成的 widget 行');

  return {
    items,
    sheets: {
      itemSheet: itemPick.sheet?.name || '',
      layoutSheet: layoutPick.sheet?.name || '',
      names: sheets.map((s) => s.name),
    },
    rawSheets: sheets.map((s) => ({ name: s.name, rowCount: (s.rows || []).length })),
    headers: {
      item: { row: a.header.index + 1, columns: a.header.map },
      layout: { row: b.header.index + 1, columns: b.header.map },
    },
    stats: {
      sheetCount: sheets.length,
      itemRows: a.rows.length,
      layoutRows: b.rows.length,
      widgets: items.length,
      matched,
      input: items.filter((x) => x.control === 'input').length,
      choice: items.filter((x) => x.control === 'choice').length,
      other: items.filter((x) => x.control === 'other').length,
    },
    warnings,
  };
}

/** 列表导出 CSV：widget名字 / widget类型 / TOP / LEFT / WIDTH / event属性 */
export function widgetsToCsv(items = []) {
  const esc = (v) => `"${String(v ?? '').replace(/"/g, '""')}"`;
  const head = ['No', 'widget名字', 'widget类型', 'Maskat标签', '控件分类', 'TOP', 'LEFT', 'WIDTH', 'HEIGHT', 'event属性', '可用event属性', '必須'];
  const lines = [head.join(',')];
  items.forEach((it, i) => {
    lines.push([
      i + 1, it.name, it.widget, it.tag, it.control,
      it.top, it.left, it.width, it.height,
      (it.events || []).join(' | '), (it.eventAttributes || []).join(' | '),
      it.required ? 'Y' : '',
    ].map(esc).join(','));
  });
  return `\uFEFF${lines.join('\r\n')}\r\n`;
}

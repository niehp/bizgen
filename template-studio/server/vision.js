/**
 * 视觉大模型解析：页面截图 → 项目名 + bbox + 控件分类 → 归一化坐标 → 原图像素 TOP/LEFT/WIDTH。
 * 只调用 OpenAI 兼容的 /chat/completions 多模态接口，不依赖任何 SDK。
 * 密钥来自服务端 .env。请求体里的 llm 字段仅兼容旧客户端，页面已移除大模型设置。
 */
import { envValue } from './env.js';
import { normalizeItems } from '../shared/layout.js';

const DEFAULT_BASE = 'https://api.openai.com/v1';
const DEFAULT_MODEL = 'gpt-4o';

const clip = (s, n = 400) => {
  const t = String(s ?? '').replace(/\s+/g, ' ').trim();
  return t.length > n ? `${t.slice(0, n)}…` : t;
};

export function llmStatus() {
  const key = envValue('LLM_API_KEY') || envValue('OPENAI_API_KEY');
  const baseUrl = envValue('LLM_BASE_URL') || envValue('OPENAI_BASE_URL') || DEFAULT_BASE;
  const model = envValue('LLM_MODEL') || envValue('OPENAI_MODEL') || DEFAULT_MODEL;
  return {
    configured: Boolean(key),
    baseUrl,
    model,
    provider: 'OpenAI 兼容 /chat/completions（多模态视觉）',
    hint: key
      ? '服务端 .env 已配置密钥，可直接点击「解析图片」'
      : '服务端未配置密钥：请在 .env 中设置 LLM_API_KEY，或使用离线示例 / 手动标注',
  };
}

function resolveConfig(input = {}) {
  const l = input && typeof input === 'object' ? input : {};
  const useEnv = l.useEnv !== false;
  const envKey = envValue('LLM_API_KEY') || envValue('OPENAI_API_KEY');
  const envBase = envValue('LLM_BASE_URL') || envValue('OPENAI_BASE_URL');
  const envModel = envValue('LLM_MODEL') || envValue('OPENAI_MODEL');
  const envTimeout = envValue('LLM_TIMEOUT_MS');
  const apiKey = String(l.apiKey || (useEnv ? envKey : '') || '').trim();
  return {
    apiKey,
    baseUrl: String(l.baseUrl || (useEnv ? envBase : '') || DEFAULT_BASE).trim().replace(/\/+$/, ''),
    model: String(l.model || (useEnv ? envModel : '') || DEFAULT_MODEL).trim(),
    timeoutMs: Math.max(10000, Number(envTimeout || 90000) || 90000),
    keySource: l.apiKey ? '页面本次请求' : (envKey ? '服务端 .env' : ''),
  };
}

/** 严格的输出契约：0–1000 归一化 bbox，服务端换算成原图像素 */
export function buildPrompt({ image, options = {} }) {
  const focus = String(options.focus || '').trim();
  const maxItems = Math.max(1, Math.min(300, Number(options.maxItems) || 200));
  const extra = focus ? `\n# 本次额外要求（优先遵守）\n${clip(focus, 600)}\n` : '';
  return `# 角色
你是一个页面布局解析引擎。用户会给你一张页面截图，你需要识别截图中的每一个「项目」，并给出它在图片中的位置。

# 输入图片
原始尺寸：${image.width} × ${image.height} 像素${image.name ? `，文件名：${clip(image.name, 80)}` : ''}。

# 需要识别的「项目」
页面上所有可见的 UI 项目，包括但不限于：
- 字段标签 / 项目名（例如「会社コード」「取引日」「金额」等表单左侧的文字）
- 输入框、下拉框、日期框、文本域等控件的可见矩形区域
- 按钮、链接、复选框 / 单选框及其文字
- 表格的列头单元格、区块小标题、页面大标题、说明文字、状态标签
要求：标签文字与它对应的控件算作 2 个独立项目；不要把整页或整个表单当成 1 个项目；同一项目不要重复列出；最多 ${maxItems} 个。
${extra}
# 控件分类（本次的重点，必须逐项判断）
每个有边框、可输入或从候选项中选定的控件，必须判定它属于：
- input（输入框）：可直接键入文字 / 数值 / 日期的文本框、文本域、密码框、日期框、金额框
- choice（选择项）：必须从候选项中挑选的下拉框、单选按钮组、复选框、列表框
- other（其它）：字段标签、按钮、列头、标题、静态文本、表格
判别依据：有下拉箭头 / ▼ / 一组圆形单选 / 方形复选 / 候选项列表 → choice；有可输入光标或空白可填写矩形 → input；纯文字、按钮 → other。
只有 input 与 choice 会被转换成 Maskat widget 的输入类部件，请务必区分准确，不要把标签文字框当成输入框。

# 每个项目的字段
- name：该项目在画面上显示的原文文字，保持原语言，不要翻译、不要改写、不要补充推测；确无文字的控件用简短用途描述（如「検索入力欄」）
- bbox：[x1, y1, x2, y2]，紧贴该项目可见区域的最小矩形
- type：label | input | select | radio | checkbox | button | header | title | text | other
- text：控件内的值或占位文字，标签类填空字符串 ""
- control：input | choice | other（严格按上面的判别标准）
- widget：text | number | date | textarea | password | select | radio | checkbox | list | label | button | header | title | grid | static
          输入框中金额/数量用 number，日期用 date，多行用 textarea；选择项中下拉用 select，圆形单选组用 radio，方形勾选用 checkbox
- label：该控件左侧对应的字段标签文字（项目名）；标签/按钮/标题类填 ""；找不到对应标签时填 ""
- options：选择项可见的候选项文字数组（如 ["JPY","CNY","USD"] 或 ["入金","出金"]）；非选择项填 []
- required：画面上有「*」「必須」等必填标记时为 true，否则 false
- confidence：0 ~ 1 的置信度

# 坐标硬约束（必须遵守）
- bbox 使用 0 ~ 1000 的归一化整数坐标：左上角为 (0,0)，右下角为 (1000,1000)，与图片实际像素尺寸无关
- x1 < x2 且 y1 < y2，4 个值都必须是整数，且都在 0 ~ 1000 之间
- 标签的框只包住文字本身，输入框/选择项的框包住整个控件边框（含下拉箭头），不要框旁边的标签文字
- control 与 widget 必须一致：control=input 时 widget 只能是 text/number/date/textarea/password；control=choice 时只能是 select/radio/checkbox/list；control=other 时只能是 label/button/header/title/grid/static

# 输出格式
只输出一个 JSON 对象，不要 Markdown 代码栅栏，不要任何解释文字：
{"items":[{"name":"会社コード","bbox":[80,150,190,172],"type":"label","text":"","control":"other","widget":"label","label":"","options":[],"required":false,"confidence":0.95},{"name":"会社コード入力欄","bbox":[210,145,420,180],"type":"input","text":"1001","control":"input","widget":"text","label":"会社コード","options":[],"required":true,"confidence":0.94},{"name":"通貨選択","bbox":[790,145,1090,180],"type":"select","text":"JPY","control":"choice","widget":"select","label":"通貨","options":["JPY","CNY","USD"],"required":true,"confidence":0.9}]}

再次确认：只输出 JSON，坐标为 0~1000 的整数，items 必须覆盖图中所有可见项目，且每项都必须带 control / widget / label / options / required 五个分类字段。`;
}

/** 从模型的自由文本里稳健地抽出 JSON 对象 */
export function parseJsonLoose(raw) {
  const s = String(raw ?? '').trim();
  if (!s) throw new Error('大模型返回内容为空');
  const attempts = [s];
  const fenced = /```(?:json)?\s*([\s\S]*?)```/i.exec(s);
  if (fenced) attempts.unshift(fenced[1].trim());

  for (const body of attempts) {
    const start = body.indexOf('{');
    if (start < 0) continue;
    let depth = 0;
    let inString = false;
    let escaped = false;
    let end = -1;
    for (let i = start; i < body.length; i += 1) {
      const c = body[i];
      if (inString) {
        if (escaped) escaped = false;
        else if (c === '\\') escaped = true;
        else if (c === '"') inString = false;
        continue;
      }
      if (c === '"') inString = true;
      else if (c === '{') depth += 1;
      else if (c === '}') {
        depth -= 1;
        if (depth === 0) { end = i + 1; break; }
      }
    }
    const candidate = end > start ? body.slice(start, end) : body.slice(start);
    try { return JSON.parse(candidate); } catch { /* 尝试下一个候选 */ }
    try { return JSON.parse(body); } catch { /* continue */ }
  }
  throw new Error(`无法从大模型返回中解析 JSON：${clip(s, 260)}`);
}

function extractContent(json) {
  const c = json?.choices?.[0]?.message?.content;
  if (typeof c === 'string') return c;
  if (Array.isArray(c)) return c.map((p) => (typeof p === 'string' ? p : (p?.text ?? ''))).join('\n');
  if (c && typeof c === 'object') return String(c.text ?? c.content ?? '');
  if (typeof json?.output_text === 'string') return json.output_text;
  throw new Error(`大模型响应缺少 choices[0].message.content：${clip(JSON.stringify(json), 260)}`);
}

async function chatOnce(cfg, dataUrl, prompt, withResponseFormat, maxTokens = 8192) {
  const controller = new AbortController();
  const timer = setTimeout(() => controller.abort(), cfg.timeoutMs);
  const body = {
    model: cfg.model,
    temperature: 0,
    max_tokens: maxTokens,
    messages: [
      { role: 'system', content: '你是精确的页面布局标注器。只输出符合契约的 JSON，不输出任何额外文字。' },
      {
        role: 'user',
        content: [
          { type: 'text', text: prompt },
          { type: 'image_url', image_url: { url: dataUrl } },
        ],
      },
    ],
  };
  if (withResponseFormat) body.response_format = { type: 'json_object' };

  try {
    const res = await fetch(`${cfg.baseUrl}/chat/completions`, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        Authorization: `Bearer ${cfg.apiKey}`,
      },
      body: JSON.stringify(body),
      signal: controller.signal,
    });
    const raw = await res.text();
    if (!res.ok) {
      const err = new Error(`大模型接口返回 HTTP ${res.status}：${clip(raw, 400)}`);
      err.status = res.status;
      err.body = clip(raw, 600);
      throw err;
    }
    let json;
    try { json = JSON.parse(raw); } catch { throw new Error(`大模型响应不是 JSON：${clip(raw, 260)}`); }
    return { text: extractContent(json), usage: json.usage ?? null, response: json };
  } catch (e) {
    if (e.name === 'AbortError') throw new Error(`大模型调用超时（${Math.round(cfg.timeoutMs / 1000)} 秒）`);
    if (e.cause?.code) e.message = `${e.message}（网络：${e.cause.code}）`;
    throw e;
  } finally {
    clearTimeout(timer);
  }
}

async function callVision(cfg, dataUrl, prompt, maxTokens) {
  try {
    const r = await chatOnce(cfg, dataUrl, prompt, true, maxTokens);
    return { ...r, responseFormat: true };
  } catch (e) {
    // 部分兼容接口不支持 response_format，自动去掉重试一次
    const unsupported = e.status === 400
      && /response_format|json_object|json_schema|not support|unsupported/i.test(`${e.message} ${e.body || ''}`);
    if (!unsupported) throw e;
    const r = await chatOnce(cfg, dataUrl, prompt, false, maxTokens);
    return { ...r, responseFormat: false };
  }
}

function err(message, code) {
  const e = new Error(message);
  e.code = code;
  return e;
}

/** 主入口：返回可直接给前端的 items（px）与调试元信息 */
export async function parseImage({ image, llm = {}, options = {} }) {
  const cfg = resolveConfig(llm);
  if (!cfg.apiKey) {
    throw err('未配置大模型 API Key：请在页面「大模型设置」填写，或把 .env.example 复制为 .env 填 LLM_API_KEY。无需密钥时可用「加载示例页面」或手动框选标注。', 'NO_API_KEY');
  }

  const prompt = buildPrompt({ image, options });
  const maxItems = Math.max(1, Math.min(300, Number(options.maxItems) || 200));
  // 每项含 5 个分类字段，约需 55~70 token；按项目数动态给足输出空间，避免 JSON 被截断
  const maxTokens = Math.min(16384, Math.max(4096, maxItems * 64 + 1536));
  const started = Date.now();
  const result = await callVision(cfg, image.dataUrl, prompt, maxTokens);

  let json;
  try {
    json = parseJsonLoose(result.text);
  } catch (e) {
    throw err(`${e.message}。可点击「原始响应」查看模型实际输出，或换用支持 JSON 输出的视觉模型。`, 'LLM_PARSE');
  }

  const rawItems = json?.items ?? json?.projects ?? json?.elements ?? json?.detections ?? json?.['项目'];
  if (!Array.isArray(rawItems)) {
    throw err(`大模型返回的 JSON 缺少 items 数组：${clip(JSON.stringify(json), 260)}`, 'LLM_PARSE');
  }

  const { items, dropped } = normalizeItems(rawItems, image, { maxItems });
  if (!items.length) {
    throw err(`大模型返回了 ${rawItems.length} 个项目，但坐标全部无效（超出图片范围或尺寸过小）。请查看「原始响应」，或换用支持坐标输出的视觉模型。`, 'EMPTY_RESULT');
  }

  return {
    items,
    dropped,
    meta: {
      source: 'vision',
      model: cfg.model,
      baseUrl: cfg.baseUrl,
      keySource: cfg.keySource,
      responseFormat: result.responseFormat,
      usage: result.usage,
      elapsedMs: Date.now() - started,
      prompt,
      rawText: result.text,
      returned: rawItems.length,
    },
  };
}

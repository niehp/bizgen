/**
 * 无依赖读取 Excel 的全部工作表（.xlsx / .xlsm = ZIP + SpreadsheetML）。
 * 与图片解析模块独立，仅用于「画面項目 / 画面レイアウト」两表。
 */
import zlib from 'node:zlib';

export const EXCEL_LIMITS = { maxBytes: 12 * 1024 * 1024 };
const str = (v) => String(v ?? '');

function decodeXml(s) {
  return str(s)
    .replace(/&lt;/g, '<')
    .replace(/&gt;/g, '>')
    .replace(/&quot;/g, '"')
    .replace(/&apos;/g, "'")
    .replace(/&#x([0-9a-fA-F]+);/g, (_, h) => String.fromCodePoint(parseInt(h, 16)))
    .replace(/&#(\d+);/g, (_, d) => String.fromCodePoint(Number(d)))
    .replace(/&amp;/g, '&');
}

function xmlAttrs(fragment) {
  const out = {};
  for (const m of fragment.matchAll(/([:\w-]+)\s*=\s*"([^"]*)"/g)) out[m[1]] = decodeXml(m[2]);
  return out;
}

/** 最小 ZIP 读取器（central directory，store / deflate） */
function makeZipReader(buf) {
  if (buf.length < 22) throw new Error('不是有效的 xlsx 文件（ZIP 结构缺失）');
  let eocd = -1;
  const from = Math.max(0, buf.length - 65558);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('找不到 ZIP 结构，请另存为 .xlsx');
  const count = buf.readUInt16LE(eocd + 10);
  let ptr = buf.readUInt32LE(eocd + 16);
  const entries = new Map();
  for (let i = 0; i < count && ptr + 46 <= buf.length; i += 1) {
    if (buf.readUInt32LE(ptr) !== 0x02014b50) break;
    const method = buf.readUInt16LE(ptr + 10);
    const compSize = buf.readUInt32LE(ptr + 20);
    const nameLen = buf.readUInt16LE(ptr + 28);
    const extraLen = buf.readUInt16LE(ptr + 30);
    const commentLen = buf.readUInt16LE(ptr + 32);
    const localOff = buf.readUInt32LE(ptr + 42);
    const name = buf.subarray(ptr + 46, ptr + 46 + nameLen).toString('utf8');
    entries.set(name, { method, compSize, localOff });
    ptr += 46 + nameLen + extraLen + commentLen;
  }
  return (name) => {
    const e = entries.get(name);
    if (!e || e.localOff + 30 > buf.length) return null;
    if (buf.readUInt32LE(e.localOff) !== 0x04034b50) return null;
    const nameLen = buf.readUInt16LE(e.localOff + 26);
    const extraLen = buf.readUInt16LE(e.localOff + 28);
    const start = e.localOff + 30 + nameLen + extraLen;
    const raw = buf.subarray(start, start + e.compSize);
    if (e.method === 0) return raw.toString('utf8');
    if (e.method === 8) {
      try { return zlib.inflateRawSync(raw).toString('utf8'); }
      catch { throw new Error('Excel 工作簿解压失败'); }
    }
    return null;
  };
}

function columnIndex(letters) {
  let n = 0;
  for (const ch of letters) {
    if (!/[A-Z]/.test(ch)) break;
    n = n * 26 + (ch.charCodeAt(0) - 64);
  }
  return Math.max(0, n - 1);
}

function parseSheetXml(sheetXml, shared) {
  const rows = [];
  const cellRe = /<c\b[^>]*?\/>|<c\b[^>]*?>[\s\S]*?<\/c>/g;
  for (const rowMatch of sheetXml.matchAll(/<row\b[^>]*?\/>|<row\b[^>]*?>([\s\S]*?)<\/row>/g)) {
    const inner = rowMatch[1] || '';
    const cells = [];
    for (const cm of inner.matchAll(cellRe)) {
      const tag = cm[0];
      const head = tag.match(/^<c\b([^>]*?)\/?>/);
      if (!head) continue;
      const a = xmlAttrs(head[1]);
      const ref = str(a.r).replace(/\d+/g, '').toUpperCase();
      const col = columnIndex(ref);
      const body = tag.includes('</c>') ? tag.slice(tag.indexOf('>') + 1, tag.lastIndexOf('</c>')) : '';
      let value = '';
      if (a.t === 'inlineStr') {
        value = [...body.matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)].map((t) => decodeXml(t[1])).join('');
      } else {
        const vm = body.match(/<v\b[^>]*>([\s\S]*?)<\/v>/);
        if (vm) {
          const raw = decodeXml(vm[1]);
          value = a.t === 's' ? (shared[Number(raw)] ?? '') : raw;
        }
      }
      while (cells.length <= col) cells.push('');
      cells[col] = value.trim();
    }
    rows.push(cells);
  }
  while (rows.length && rows[rows.length - 1].every((c) => c === '')) rows.pop();
  return rows;
}

/** CSV（UTF-8 / BOM）→ 单表 */
function parseCsv(text) {
  const rows = [];
  let row = [];
  let cell = '';
  let quoted = false;
  const s = text.replace(/^\uFEFF/, '');
  for (let i = 0; i < s.length; i += 1) {
    const ch = s[i];
    if (quoted) {
      if (ch === '"' && s[i + 1] === '"') { cell += '"'; i += 1; }
      else if (ch === '"') quoted = false;
      else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === ',') { row.push(cell.trim()); cell = ''; }
    else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && s[i + 1] === '\n') i += 1;
      row.push(cell.trim()); cell = '';
      rows.push(row); row = [];
    } else cell += ch;
  }
  if (cell || row.length) { row.push(cell.trim()); rows.push(row); }
  return rows.filter((r) => r.some((c) => c !== ''));
}

/**
 * @param {string} data base64 或 dataURL
 * @returns {{ sheets: Array<{name, rows}>, warnings: string[] }}
 */
export function parseExcelSheets(data, filename = '') {
  if (typeof data !== 'string' || !data) throw new Error('Excel 文件数据为空');
  let base64 = data;
  if (base64.startsWith('data:')) {
    const comma = base64.indexOf(',');
    base64 = comma >= 0 ? base64.slice(comma + 1) : '';
  }
  const buf = Buffer.from(base64, 'base64');
  if (!buf.length) throw new Error('Excel 文件内容为空');
  if (buf.length > EXCEL_LIMITS.maxBytes) {
    throw new Error(`Excel 文件过大（上限 ${Math.round(EXCEL_LIMITS.maxBytes / 1024 / 1024)} MB）`);
  }
  const ext = (String(filename).split('.').pop() || '').toLowerCase();
  const isZip = buf[0] === 0x50 && buf[1] === 0x4b;
  const warnings = [];

  if (ext === 'xls' && !isZip) {
    throw new Error('不支持 .xls (BIFF)，请另存为 .xlsx');
  }
  if (!isZip) {
    const rows = parseCsv(buf.toString('utf8'));
    return { sheets: [{ name: ext === 'tsv' ? 'Sheet1' : String(filename || 'CSV'), rows }], warnings: ['CSV 只有一个表，无法同时提供「画面項目」和「画面レイアウト」'] };
  }

  const read = makeZipReader(buf);
  const wbXml = read('xl/workbook.xml');
  if (!wbXml) throw new Error('缺少 xl/workbook.xml，不是有效的 xlsx');
  const rels = read('xl/_rels/workbook.xml.rels') || '';
  const targets = new Map();
  for (const m of rels.matchAll(/<Relationship\b([^>]*)\/?>/g)) {
    const a = xmlAttrs(m[1]);
    if (a.Id) targets.set(a.Id, a.Target || '');
  }
  const sheets = [];
  for (const m of wbXml.matchAll(/<sheet\b([^>]*)\/?>/g)) {
    const a = xmlAttrs(m[1]);
    let target = targets.get(a['r:id']) || `worksheets/sheet${sheets.length + 1}.xml`;
    target = String(target).replace(/^\//, '').replace(/^\.\//, '');
    sheets.push({
      name: a.name || `Sheet${sheets.length + 1}`,
      path: target.startsWith('xl/') ? target : `xl/${target}`,
    });
  }
  if (!sheets.length) throw new Error('工作簿中没有工作表');

  const sstXml = read('xl/sharedStrings.xml');
  const shared = [];
  if (sstXml) {
    for (const m of sstXml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) {
      shared.push([...m[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)]
        .map((t) => decodeXml(t[1])).join(''));
    }
  }

  const out = [];
  for (const s of sheets) {
    const xml = read(s.path);
    if (!xml) { warnings.push(`工作表「${s.name}」读取失败，已跳过`); continue; }
    out.push({ name: s.name, rows: parseSheetXml(xml, shared) });
  }
  if (!out.length) throw new Error('所有工作表都读取失败');
  return { sheets: out, warnings };
}

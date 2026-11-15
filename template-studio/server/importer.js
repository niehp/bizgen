/**
 * 変数値ファイルの取り込み（サーバー側・外部依存なし）
 *
 *   .xlsx / .xlsm : ZIP + SpreadsheetML を自前で展開（共有文字列・複数シート対応）
 *   .csv / .tsv / .txt : 区切り文字を自動判定（引用符・改行入りセル・BOM・Shift_JIS 対応）
 *   .json : オブジェクト配列 / 配列配列 / { rows: [...] }
 *   .md / .markdown : パイプ区切りの表
 *
 * 戻り値は「行の配列」だけに正規化し、変数名への対付けはブラウザ側
 * （shared/expansion.js の mapTableRows）が行う。ファイルは一切ディスクに保存しない。
 */
import zlib from 'node:zlib';

const MAX_BYTES = 8 * 1024 * 1024;
const MAX_ROWS = 1000;

const str = (v) => (v == null ? '' : String(v));

/* ---------------- テキスト ---------------- */

function decodeText(buf) {
  if (buf[0] === 0xef && buf[1] === 0xbb && buf[2] === 0xbf) {
    return buf.subarray(3).toString('utf8');
  }
  try {
    return new TextDecoder('utf-8', { fatal: true }).decode(buf);
  } catch {
    try {
      return new TextDecoder('shift_jis').decode(buf);
    } catch {
      return buf.toString('utf8');
    }
  }
}

/** 引用符 / フィールド内改行に対応した区切り文字パーサ */
function splitDelimited(text, delim) {
  const rows = [];
  let row = [];
  let field = '';
  let quoted = false;
  for (let i = 0; i < text.length; i += 1) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"') {
        if (text[i + 1] === '"') { field += '"'; i += 1; }
        else quoted = false;
      } else {
        field += ch;
      }
    } else if (ch === '"' && field === '') {
      quoted = true;
    } else if (ch === delim) {
      row.push(field); field = '';
    } else if (ch === '\n' || ch === '\r') {
      if (ch === '\r' && text[i + 1] === '\n') i += 1;
      row.push(field); field = '';
      rows.push(row); row = [];
    } else {
      field += ch;
    }
  }
  if (field !== '' || row.length) { row.push(field); rows.push(row); }
  return rows;
}

function detectDelimiter(text) {
  const sample = text.split(/\r?\n/).filter((l) => l.trim() !== '').slice(0, 8);
  let best = ',';
  let bestScore = -1;
  for (const d of ['\t', ',', ';', '|']) {
    const counts = sample.map((l) => (splitDelimited(l, d)[0] || []).length);
    const max = Math.max(0, ...counts);
    const consistent = counts.filter((c) => c === max).length;
    const score = max * 10 + consistent;
    if (score > bestScore) { bestScore = score; best = d; }
  }
  return best;
}

function parseDelimited(text) {
  const delim = detectDelimiter(text);
  const rows = splitDelimited(text, delim)
    .map((r) => r.map((c) => c.trim()))
    .filter((r) => r.some((c) => c !== ''));
  return { header: [], rows, kind: 'delimited', delimiter: delim === '\t' ? 'TSV' : `区切り "${delim}"` };
}

function parseJsonTable(text) {
  let obj;
  try { obj = JSON.parse(text); }
  catch (e) { throw new Error(`JSON の解析に失敗しました: ${e.message}`); }
  let records = Array.isArray(obj)
    ? obj
    : (obj && Array.isArray(obj.rows) ? obj.rows : (obj && typeof obj === 'object' ? [obj] : []));
  if (!records.length) return { header: [], rows: [], kind: 'json' };
  if (Array.isArray(records[0])) {
    return { header: [], rows: records.map((r) => r.map(str)), kind: 'json' };
  }
  const keys = [];
  for (const r of records) {
    if (r && typeof r === 'object') {
      for (const k of Object.keys(r)) if (!keys.includes(k)) keys.push(k);
    }
  }
  const rows = records.map((r) => keys.map((k) => {
    const v = r?.[k];
    if (v == null) return '';
    return typeof v === 'object' ? JSON.stringify(v) : str(v);
  }));
  return { header: keys, rows, kind: 'json' };
}

function parseMarkdownTable(text) {
  const rows = [];
  for (const line of text.split(/\r?\n/)) {
    const t = line.trim();
    if (!t.startsWith('|')) continue;
    const cells = t.replace(/^\|/, '').replace(/\|$/, '').split('|').map((s) => s.trim());
    if (cells.every((c) => /^:?-{2,}:?$/.test(c))) continue;
    rows.push(cells);
  }
  return { header: [], rows, kind: 'markdown' };
}

/* ---------------- xlsx（ZIP + SpreadsheetML） ---------------- */

function xmlAttrs(fragment) {
  const out = {};
  for (const m of fragment.matchAll(/([:\w-]+)\s*=\s*"([^"]*)"/g)) out[m[1]] = decodeXml(m[2]);
  return out;
}

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

/** 最小限の ZIP リーダ（store / deflate、central directory 方式） */
function makeZipReader(buf) {
  if (buf.length < 22) throw new Error('ZIP ファイルとして読み込めません');
  let eocd = -1;
  const from = Math.max(0, buf.length - 65558);
  for (let i = buf.length - 22; i >= from; i -= 1) {
    if (buf.readUInt32LE(i) === 0x06054b50) { eocd = i; break; }
  }
  if (eocd < 0) throw new Error('ZIP 構造が見つかりません（.xlsx として読み込めません）');
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
      catch { throw new Error('Excel ブックの展開に失敗しました'); }
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

function readXlsx(buf, requestedSheet) {
  const read = makeZipReader(buf);
  const wbXml = read('xl/workbook.xml');
  if (!wbXml) throw new Error('xl/workbook.xml がありません。Excel ブックとして読み込めません');

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
    const path = target.startsWith('xl/') ? target : `xl/${target}`;
    sheets.push({ name: a.name || `Sheet${sheets.length + 1}`, path });
  }
  if (!sheets.length) throw new Error('ワークシートが見つかりません');

  const byIndex = /^\d+$/.test(String(requestedSheet ?? '')) ? Number(requestedSheet) : -1;
  const chosen = sheets.find((s) => s.name === requestedSheet)
    || (byIndex >= 0 ? sheets[byIndex] : null)
    || sheets[0];

  const sstXml = read('xl/sharedStrings.xml');
  const shared = [];
  if (sstXml) {
    for (const m of sstXml.matchAll(/<si\b[^>]*>([\s\S]*?)<\/si>/g)) {
      shared.push([...m[1].matchAll(/<t\b[^>]*>([\s\S]*?)<\/t>/g)]
        .map((t) => decodeXml(t[1])).join(''));
    }
  }

  const sheetXml = read(chosen.path);
  if (!sheetXml) throw new Error(`ワークシート "${chosen.name}" を読み込めません`);

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

  return {
    kind: 'xlsx',
    sheetNames: sheets.map((s) => s.name),
    sheet: chosen.name,
    header: [],
    rows,
    warnings: sheets.length > 1 ? [`${sheets.length} 枚のシートがあります。取り込むシートを選択してください。`] : [],
  };
}

/* ---------------- 公開 API ---------------- */

export function importTable({ filename = '', data = '', sheet } = {}) {
  if (typeof data !== 'string' || !data) throw new Error('ファイルデータが空です');
  let base64 = data;
  if (base64.startsWith('data:')) {
    const comma = base64.indexOf(',');
    base64 = comma >= 0 ? base64.slice(comma + 1) : '';
  }
  const buf = Buffer.from(base64, 'base64');
  if (!buf.length) throw new Error('ファイルが空です');
  if (buf.length > MAX_BYTES) {
    throw new Error(`ファイルが大きすぎます（上限 ${Math.round(MAX_BYTES / 1024 / 1024)} MB）`);
  }

  const name = str(filename);
  const ext = (name.split('.').pop() || '').toLowerCase();
  const isZip = buf[0] === 0x50 && buf[1] === 0x4b;
  let result;

  if (ext === 'xlsx' || ext === 'xlsm' || isZip) {
    result = readXlsx(buf, sheet);
    if (ext === 'xls') {
      result.warnings.push('拡張子は .xls ですが中身は .xlsx です。拡張子の変更をお勧めします。');
    }
  } else if (ext === 'xls') {
    throw new Error('.xls (BIFF) には対応していません。.xlsx または CSV で保存し直してください。');
  } else {
    const text = decodeText(buf);
    if (ext === 'json') result = parseJsonTable(text);
    else if (ext === 'md' || ext === 'markdown') result = parseMarkdownTable(text);
    else result = parseDelimited(text);
  }

  const rawRows = Array.isArray(result.rows) ? result.rows : [];
  const truncated = rawRows.length > MAX_ROWS;
  // 列数は切り詰めない（1 変数 = 1 列の TABLE で何列でも扱える）
  const rows = rawRows.slice(0, MAX_ROWS).map((r) => (Array.isArray(r) ? r : [r])
    .map((c) => str(c).trim()));
  const notes = [...(result.warnings || [])];
  if (truncated) notes.push(`${rawRows.length} 行中 ${MAX_ROWS} 行まで読み込みました。`);
  if (!rows.length) notes.push('取り込める行が見つかりませんでした。');
  if (result.delimiter) notes.push(`区切り: ${result.delimiter}`);
  if (result.kind === 'xlsx' && !result.sheetNames.length) notes.push('シート名を取得できませんでした。');

  return {
    filename: name,
    kind: result.kind,
    sheetNames: result.sheetNames || [],
    sheet: result.sheet || '',
    header: result.header || [],
    rows,
    rowCount: rows.length,
    truncated,
    warnings: notes,
  };
}

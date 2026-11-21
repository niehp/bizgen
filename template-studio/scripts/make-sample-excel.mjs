/** 生成含「画面項目」「画面レイアウト」两表的最小 xlsx 示例（无第三方依赖）。 */
import fs from 'node:fs';
import path from 'node:path';
import zlib from 'node:zlib';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const OUT = path.join(__dirname, '..', 'samples', '画面設計書サンプル.xlsx');

const esc = (s) => String(s).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
const col = (n) => {
  let s = '';
  let x = n + 1;
  while (x > 0) { const r = (x - 1) % 26; s = String.fromCharCode(65 + r) + s; x = Math.floor((x - 1) / 26); }
  return s;
};
const sheetXml = (rows) => {
  const body = rows.map((row, ri) => {
    const cells = row.map((v, ci) => {
      const ref = `${col(ci)}${ri + 1}`;
      if (typeof v === 'number') return `<c r="${ref}"><v>${v}</v></c>`;
      if (v === '' || v === undefined || v === null) return '';
      return `<c r="${ref}" t="inlineStr"><is><t>${esc(v)}</t></is></c>`;
    }).join('');
    return `<row r="${ri + 1}">${cells}</row>`;
  }).join('');
  return `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<worksheet xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main"><sheetData>${body}</sheetData></worksheet>`;
};

const itemRows = [
  ['取引明細 入力画面 設計書（サンプル）'],
  ['作成: 2026-11-16 / この2行はヘッダ自動検出のテスト用'],
  [],
  ['項目ID', '項目名', 'Widget種別', '入力区分', '必須', 'イベント属性', '選択肢', '関連ラベル'],
  ['P001', '会社コード', 'textBox', '入力', '必須', 'onEdit,funcOnLeave', '', '会社コード'],
  ['P002', '取引日', 'dateTextBox', '入力', '必須', 'onEdit', '', '取引日'],
  ['P003', '金額', 'numberTextBox', '入力', '必須', 'onEdit', '', '金額'],
  ['P004', '通貨', 'filteringSelect', '選択', '必須', 'onEdit', 'JPY,CNY,USD', '通貨'],
  ['P005', 'ステータス', 'radioButton', '選択', '', 'funcOnLeave', '正常,保留,完了', 'ステータス'],
  ['P006', '承認済', 'checkBox', '選択', '', 'styleFormatter', '', '承認'],
  ['P007', '摘要', 'textarea', '入力', '', 'onEdit', '', '摘要'],
  ['P008', '検索', 'button', 'その他', '', 'styleFormatter', '', ''],
  ['P009', '基本情報', 'headerLabel', 'その他', '', '', '', ''],
  ['P010', '明細一覧', 'grid', 'その他', '', 'onCellContextMenuKeyCode', '', ''],
];

const layoutRows = [
  ['画面レイアウト（座標は px）'],
  ['TOP / LEFT / WIDTH / HEIGHT は原画像基準'],
  [],
  ['項目ID', '項目名', 'TOP', 'LEFT', 'WIDTH', 'HEIGHT', 'TAB'],
  ['P001', '会社コード', 162, 212, 300, 36, 1],
  ['P002', '取引日', 162, 790, 300, 36, 2],
  ['P003', '金額', 274, 212, 300, 36, 3],
  ['P004', '通貨', 274, 790, 300, 36, 4],
  ['P005', 'ステータス', 330, 660, 200, 36, 5],
  ['P006', '承認済', 330, 880, 210, 36, 6],
  ['P007', '摘要', 330, 212, 300, 48, 7],
  ['P008', '検索', 580, 900, 110, 36, 8],
  ['P009', '基本情報', 116, 64, 120, 26, 0],
  ['P010', '明細一覧', 450, 64, 1060, 200, 0],
];

const files = {
  '[Content_Types].xml': `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">`
    + `<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>`
    + `<Default Extension="xml" ContentType="application/xml"/>`
    + `<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>`
    + `<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`
    + `<Override PartName="/xl/worksheets/sheet2.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>`
    + `</Types>`,
  '_rels/.rels': `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
    + `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/officeDocument" Target="xl/workbook.xml"/>`
    + `</Relationships>`,
  'xl/workbook.xml': `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<workbook xmlns="http://schemas.openxmlformats.org/spreadsheetml/2006/main" `
    + `xmlns:r="http://schemas.openxmlformats.org/officeDocument/2006/relationships"><sheets>`
    + `<sheet name="画面項目" sheetId="1" r:id="rId1"/>`
    + `<sheet name="画面レイアウト" sheetId="2" r:id="rId2"/>`
    + `</sheets></workbook>`,
  'xl/_rels/workbook.xml.rels': `<?xml version="1.0" encoding="UTF-8" standalone="yes"?>`
    + `<Relationships xmlns="http://schemas.openxmlformats.org/package/2006/relationships">`
    + `<Relationship Id="rId1" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet1.xml"/>`
    + `<Relationship Id="rId2" Type="http://schemas.openxmlformats.org/officeDocument/2006/relationships/worksheet" Target="worksheets/sheet2.xml"/>`
    + `</Relationships>`,
  'xl/worksheets/sheet1.xml': sheetXml(itemRows),
  'xl/worksheets/sheet2.xml': sheetXml(layoutRows),
};

/** store 模式 ZIP */
function makeZip(entries) {
  const locals = [];
  const centrals = [];
  let offset = 0;
  for (const [name, content] of Object.entries(entries)) {
    const nameBuf = Buffer.from(name, 'utf8');
    const data = Buffer.from(content, 'utf8');
    const crc = zlib.crc32 ? zlib.crc32(data) : crc32(data);
    const local = Buffer.alloc(30);
    local.writeUInt32LE(0x04034b50, 0);
    local.writeUInt16LE(20, 4);
    local.writeUInt16LE(0, 6);
    local.writeUInt16LE(0, 8);
    local.writeUInt32LE(crc, 14);
    local.writeUInt32LE(data.length, 18);
    local.writeUInt32LE(data.length, 22);
    local.writeUInt16LE(nameBuf.length, 26);
    locals.push(local, nameBuf, data);

    const central = Buffer.alloc(46);
    central.writeUInt32LE(0x02014b50, 0);
    central.writeUInt16LE(20, 4);
    central.writeUInt16LE(20, 6);
    central.writeUInt32LE(crc, 16);
    central.writeUInt32LE(data.length, 20);
    central.writeUInt32LE(data.length, 24);
    central.writeUInt16LE(nameBuf.length, 28);
    central.writeUInt32LE(offset, 42);
    centrals.push(central, nameBuf);
    offset += local.length + nameBuf.length + data.length;
  }
  const centralBuf = Buffer.concat(centrals);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(Object.keys(entries).length, 8);
  eocd.writeUInt16LE(Object.keys(entries).length, 10);
  eocd.writeUInt32LE(centralBuf.length, 12);
  eocd.writeUInt32LE(offset, 16);
  return Buffer.concat([...locals, centralBuf, eocd]);
}

function crc32(buf) {
  let c = 0xFFFFFFFF;
  for (let i = 0; i < buf.length; i += 1) {
    c ^= buf[i];
    for (let k = 0; k < 8; k += 1) c = (c >>> 1) ^ (0xEDB88320 & -(c & 1));
  }
  return (c ^ 0xFFFFFFFF) >>> 0;
}

fs.mkdirSync(path.dirname(OUT), { recursive: true });
fs.writeFileSync(OUT, makeZip(files));
console.log('[sample] 已生成', OUT, fs.statSync(OUT).size, 'bytes');

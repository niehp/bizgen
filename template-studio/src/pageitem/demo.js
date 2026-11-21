/**
 * 内置示例页面：用 Canvas 离线生成一张「取引明細 入力画面」截图，
 * 并给出与绘制坐标完全一致的项目标注（含输入框 / 选择项分类）。
 * 无需任何密钥即可验证完整流程：截图 → 项目名 TOP/LEFT/WIDTH → Maskat widget。
 */
import { normalizeItems } from '../../shared/layout.js';

const W = 1200;
const H = 780;
const FONT = '"Microsoft YaHei", "Yu Gothic", "PingFang SC", system-ui, sans-serif';

/**
 * type = 视觉外观；control/widget = 语义分类（输入框 / 选择项 / 其它）
 * 坐标一律是原图像素，与 drawItem 绘制位置完全一致。
 */
const D = [
  { name: '取引明細 入力画面', left: 32, top: 16, width: 280, height: 32, type: 'title' },
  { name: 'ホーム > 取引管理 > 明細入力', left: 40, top: 76, width: 300, height: 20, type: 'text' },
  { name: '基本情報', left: 64, top: 116, width: 120, height: 26, type: 'header' },

  { name: '会社コード', left: 72, top: 170, width: 120, height: 22, type: 'label' },
  { name: '会社コード入力欄', left: 212, top: 162, width: 300, height: 36, type: 'input', text: '1001', control: 'input', widget: 'text', label: '会社コード', required: true },
  { name: '取引日', left: 650, top: 170, width: 120, height: 22, type: 'label' },
  { name: '取引日入力欄', left: 790, top: 162, width: 300, height: 36, type: 'input', text: '2026/03/01', control: 'input', widget: 'date', label: '取引日', required: true },

  { name: '社内口座コード', left: 72, top: 226, width: 120, height: 22, type: 'label' },
  { name: '社内口座コード入力欄', left: 212, top: 218, width: 300, height: 36, type: 'input', text: '0001-001', control: 'input', widget: 'text', label: '社内口座コード' },
  { name: '勘定科目', left: 650, top: 226, width: 120, height: 22, type: 'label' },
  { name: '勘定科目入力欄', left: 790, top: 218, width: 300, height: 36, type: 'input', text: '売上高', control: 'input', widget: 'text', label: '勘定科目' },

  { name: '金額', left: 72, top: 282, width: 120, height: 22, type: 'label' },
  { name: '金額入力欄', left: 212, top: 274, width: 300, height: 36, type: 'input', text: '12,500', control: 'input', widget: 'number', label: '金額', required: true },
  { name: '通貨', left: 650, top: 282, width: 120, height: 22, type: 'label' },
  { name: '通貨選択', left: 790, top: 274, width: 300, height: 36, type: 'select', text: 'JPY', control: 'choice', widget: 'select', label: '通貨', options: ['JPY', 'CNY', 'USD'], required: true },

  { name: '摘要', left: 72, top: 338, width: 120, height: 22, type: 'label' },
  { name: '摘要入力欄', left: 212, top: 330, width: 300, height: 48, type: 'input', text: '3月分売上', control: 'input', widget: 'textarea', label: '摘要' },
  { name: 'ステータス', left: 560, top: 338, width: 104, height: 22, type: 'label' },
  { name: 'ステータス選択', left: 660, top: 330, width: 200, height: 36, type: 'select', text: '正常', control: 'choice', widget: 'select', label: 'ステータス', options: ['正常', '保留', '完了'] },
  { name: '承認済チェック', left: 880, top: 330, width: 210, height: 36, type: 'checkbox', text: '承認済', control: 'choice', widget: 'checkbox', label: '承認', options: ['承認済'] },

  { name: '収支区分', left: 72, top: 394, width: 120, height: 22, type: 'label' },
  { name: '収支区分選択', left: 212, top: 386, width: 300, height: 32, type: 'radio', text: '入金', control: 'choice', widget: 'radio', label: '収支区分', options: ['入金', '出金'] },
  { name: '取引種別', left: 560, top: 394, width: 104, height: 22, type: 'label' },
  { name: '取引種別チェック', left: 660, top: 386, width: 430, height: 32, type: 'checkbox', text: '通常,緊急', control: 'choice', widget: 'checkbox', label: '取引種別', options: ['通常', '緊急'] },

  { name: '検索', left: 760, top: 452, width: 110, height: 38, type: 'button' },
  { name: '登録', left: 890, top: 452, width: 110, height: 38, type: 'button' },
  { name: 'クリア', left: 1020, top: 452, width: 110, height: 38, type: 'button' },

  { name: '明細一覧', left: 64, top: 530, width: 120, height: 26, type: 'header' },
  { name: 'No', left: 40, top: 572, width: 70, height: 36, type: 'header' },
  { name: '伝票No', left: 110, top: 572, width: 190, height: 36, type: 'header' },
  { name: '取引日', left: 300, top: 572, width: 190, height: 36, type: 'header' },
  { name: '金額', left: 490, top: 572, width: 190, height: 36, type: 'header' },
  { name: 'ステータス', left: 680, top: 572, width: 190, height: 36, type: 'header' },
  { name: '摘要', left: 870, top: 572, width: 290, height: 36, type: 'header' },
];

const TABLE_X = [40, 110, 300, 490, 680, 870, 1160];
const ROWS = [
  ['1', 'A-1001', '2026/03/01', '12,500', '正常', '3月分売上'],
  ['2', 'A-1002', '2026/03/02', '(3,200)', '保留', '材料仕入'],
  ['3', 'A-1003', '2026/03/03', '1,800', '正常', '出張交通費'],
];

function roundRect(ctx, x, y, w, h, r = 4) {
  const rr = Math.min(r, w / 2, h / 2);
  ctx.beginPath();
  ctx.moveTo(x + rr, y);
  ctx.lineTo(x + w - rr, y);
  ctx.quadraticCurveTo(x + w, y, x + w, y + rr);
  ctx.lineTo(x + w, y + h - rr);
  ctx.quadraticCurveTo(x + w, y + h, x + w - rr, y + h);
  ctx.lineTo(x + rr, y + h);
  ctx.quadraticCurveTo(x, y + h, x, y + h - rr);
  ctx.lineTo(x, y + rr);
  ctx.quadraticCurveTo(x, y, x + rr, y);
  ctx.closePath();
}

function drawBase(ctx) {
  ctx.fillStyle = '#f4f6fa';
  ctx.fillRect(0, 0, W, H);

  ctx.fillStyle = '#1f3a5f';
  ctx.fillRect(0, 0, W, 64);
  ctx.fillStyle = '#3b82f6';
  ctx.fillRect(0, 62, W, 2);

  ctx.fillStyle = '#ffffff';
  ctx.strokeStyle = '#dde4ee';
  ctx.lineWidth = 1;
  roundRect(ctx, 40, 104, 1120, 320, 8); ctx.fill(); ctx.stroke();
  roundRect(ctx, 40, 520, 1120, 226, 8); ctx.fill(); ctx.stroke();

  ctx.strokeStyle = '#dde4ee';
  ctx.fillStyle = '#f8fafc';
  ctx.fillRect(40, 572, 1120, 36);
  for (const x of TABLE_X) {
    ctx.beginPath(); ctx.moveTo(x + 0.5, 572); ctx.lineTo(x + 0.5, 746); ctx.stroke();
  }
  for (let y = 572; y <= 746; y += 36) {
    ctx.beginPath(); ctx.moveTo(40, y + 0.5); ctx.lineTo(1160, y + 0.5); ctx.stroke();
  }
  ctx.font = `13px ${FONT}`;
  ctx.fillStyle = '#64748b';
  ctx.textBaseline = 'middle';
  ROWS.forEach((row, ri) => {
    row.forEach((cell, ci) => {
      const x = TABLE_X[ci] + 12;
      const y = 608 + ri * 36 + 18;
      ctx.textAlign = ci === 3 ? 'right' : 'left';
      ctx.fillText(cell, ci === 3 ? TABLE_X[ci + 1] - 14 : x, y, TABLE_X[ci + 1] - TABLE_X[ci] - 24);
    });
  });
  ctx.textAlign = 'left';
  ctx.font = `12px ${FONT}`;
  ctx.fillStyle = '#94a3b8';
  ctx.fillText('© FMS 取引管理システム  ·  サンプル画面（本ツールが自動生成）', 40, 762);
}

function drawTextBox(ctx, d) {
  const { left: x, top: y, width: w, height: h } = d;
  ctx.fillStyle = '#ffffff';
  ctx.strokeStyle = '#b9c6d8';
  ctx.lineWidth = 1.4;
  roundRect(ctx, x, y, w, h, 4); ctx.fill(); ctx.stroke();
  ctx.font = `14px ${FONT}`;
  ctx.fillStyle = d.text ? '#1e293b' : '#aab6c6';
  ctx.textAlign = 'left';
  ctx.fillText(d.text || '入力してください', x + 11, y + (h > 42 ? 18 : h / 2), w - 22);
}

function drawSelect(ctx, d) {
  const { left: x, top: y, width: w, height: h } = d;
  ctx.fillStyle = '#ffffff';
  ctx.strokeStyle = '#7c3aed';
  ctx.lineWidth = 1.4;
  roundRect(ctx, x, y, w, h, 4); ctx.fill(); ctx.stroke();
  ctx.font = `14px ${FONT}`;
  ctx.fillStyle = '#1e293b';
  ctx.textAlign = 'left';
  ctx.fillText(d.text || '選択してください', x + 11, y + h / 2, w - 40);
  ctx.fillStyle = '#f1e9ff';
  ctx.fillRect(x + w - 30, y + 1, 28, h - 2);
  ctx.fillStyle = '#7c3aed';
  ctx.font = `bold 12px ${FONT}`;
  ctx.textAlign = 'center';
  ctx.fillText('▼', x + w - 16, y + h / 2);
}

function drawChoiceGroup(ctx, d) {
  const { left: x, top: y, height: h } = d;
  const options = d.options || [];
  ctx.font = `13px ${FONT}`;
  ctx.fillStyle = '#334155';
  ctx.textAlign = 'left';
  let cx = x + 4;
  for (const opt of options) {
    if (d.type === 'radio') {
      ctx.beginPath(); ctx.arc(cx + 8, y + h / 2, 7, 0, Math.PI * 2);
      ctx.strokeStyle = '#7c3aed'; ctx.lineWidth = 1.5; ctx.stroke();
      if (opt === d.text) { ctx.beginPath(); ctx.arc(cx + 8, y + h / 2, 3.5, 0, Math.PI * 2); ctx.fillStyle = '#7c3aed'; ctx.fill(); }
    } else {
      ctx.strokeStyle = '#7c3aed'; ctx.lineWidth = 1.5;
      ctx.strokeRect(cx, y + h / 2 - 7, 14, 14);
      if (String(d.text || '').includes(opt)) {
        ctx.fillStyle = '#7c3aed'; ctx.fillRect(cx + 3, y + h / 2 - 4, 8, 8);
      }
    }
    ctx.fillStyle = '#334155';
    ctx.fillText(opt, cx + 22, y + h / 2, 110);
    cx += 130;
  }
}

function drawItem(ctx, d) {
  const { left: x, top: y, width: w, height: h } = d;
  ctx.textBaseline = 'middle';

  if (d.type === 'title') {
    ctx.font = `bold 22px ${FONT}`;
    ctx.fillStyle = '#ffffff';
    ctx.textAlign = 'left';
    ctx.fillText(d.name, x, y + h / 2);
    return;
  }
  if (d.type === 'text') {
    ctx.font = `13px ${FONT}`;
    ctx.fillStyle = '#64748b';
    ctx.textAlign = 'left';
    ctx.fillText(d.name, x, y + h / 2);
    return;
  }
  if (d.type === 'label') {
    ctx.font = `14px ${FONT}`;
    ctx.fillStyle = '#334155';
    ctx.textAlign = 'left';
    ctx.fillText(d.name, x, y + h / 2, w);
    return;
  }
  if (d.type === 'input') { drawTextBox(ctx, d); return; }
  if (d.type === 'select') { drawSelect(ctx, d); return; }
  if (d.type === 'radio' || d.type === 'checkbox') { drawChoiceGroup(ctx, d); return; }
  if (d.type === 'button') {
    const primary = d.name === '検索';
    ctx.fillStyle = primary ? '#2563eb' : '#ffffff';
    ctx.strokeStyle = '#2563eb';
    ctx.lineWidth = 1.4;
    roundRect(ctx, x, y, w, h, 5); ctx.fill(); ctx.stroke();
    ctx.font = `bold 14px ${FONT}`;
    ctx.fillStyle = primary ? '#ffffff' : '#2563eb';
    ctx.textAlign = 'center';
    ctx.fillText(d.name, x + w / 2, y + h / 2);
    return;
  }
  if (d.type === 'header') {
    const isTableHead = y >= 560;
    if (isTableHead) {
      ctx.font = `bold 13px ${FONT}`;
      ctx.fillStyle = '#334155';
      ctx.textAlign = 'center';
      ctx.fillText(d.name, x + w / 2, y + h / 2, w - 10);
    } else {
      ctx.fillStyle = '#2563eb';
      ctx.fillRect(x, y + 3, 4, h - 6);
      ctx.font = `bold 16px ${FONT}`;
      ctx.fillStyle = '#1e293b';
      ctx.textAlign = 'left';
      ctx.fillText(d.name, x + 14, y + h / 2);
    }
  }
}

export function buildDemo() {
  const canvas = document.createElement('canvas');
  canvas.width = W;
  canvas.height = H;
  const ctx = canvas.getContext('2d');
  drawBase(ctx);
  for (const d of D) drawItem(ctx, d);

  const dataUrl = canvas.toDataURL('image/png');
  const image = {
    name: '示例页面_取引明細入力.png',
    width: W,
    height: H,
    mime: 'image/png',
    dataUrl,
    bytes: Math.round((dataUrl.length - 'data:image/png;base64,'.length) * 0.75),
  };
  const { items } = normalizeItems(
    D.map((d) => ({ ...d, coordSpace: 'px', confidence: 0.99, source: 'demo' })),
    image,
  );
  return { image, items };
}

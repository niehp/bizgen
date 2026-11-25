/**
 * SVG 上传支持：浏览器里把 SVG 栅格化为 PNG dataURL。
 *
 * 为什么不在服务端直接收 SVG：多模态解析接口通常只接受位图（PNG/JPG/WEBP），
 * 直接在前端转换可以统一后续的尺寸计算、框选标注与 JSON 快照格式。
 *
 * 注意：SVG 用 <img> 加载时浏览器不会执行脚本，安全性由浏览器保证；
 * 若 SVG 引用了跨域外部资源导致 canvas 被污染，toDataURL 会抛错，这里给出明确提示。
 */

const MAX_SIDE = 6000; // canvas 常见上限 16384；留出余量避免超大 SVG 拖垮页面

export function isSvgFile(file) {
  return Boolean(file) && (file.type === 'image/svg+xml' || /\.svg$/i.test(file.name || ''));
}

function readAsText(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader();
    reader.onerror = () => reject(new Error('SVG 文件读取失败'));
    reader.onload = () => resolve(String(reader.result || ''));
    reader.readAsText(file, 'utf-8');
  });
}

function loadImage(src) {
  return new Promise((resolve, reject) => {
    const img = new Image();
    img.onload = () => resolve(img);
    img.onerror = () => reject(new Error('SVG 解析失败：文件可能损坏或包含不支持的内容'));
    img.src = src;
  });
}

/** 从 SVG 头部推断像素尺寸：width/height → viewBox → 默认 1200×800 */
export function svgSize(svgText) {
  const head = String(svgText || '').slice(0, 8192);
  const svgTag = /<svg\b[^>]*>/i.exec(head)?.[0] || '';
  const attr = (name) => {
    const re = new RegExp(`\\b${name}\\s*=\\s*["']([^"']+)["']`, 'i');
    return re.exec(svgTag)?.[1] || '';
  };
  const px = (value) => {
    // 只认纯数字 / px；mm、cm、%、em 等单位依赖渲染环境，交给 viewBox 兜底
    const m = /^\s*([0-9]*\.?[0-9]+)\s*(?:px)?\s*$/i.exec(value || '');
    return m ? Math.round(parseFloat(m[1])) : 0;
  };
  let width = px(attr('width'));
  let height = px(attr('height'));
  const vb = attr('viewBox').trim().split(/[\s,]+/).map(Number);
  if (vb.length === 4 && vb.every(Number.isFinite)) {
    const [, , vw, vh] = vb;
    if (!width && height && vw && vh) width = Math.round((height * vw) / vh);
    if (!height && width && vw && vh) height = Math.round((width * vh) / vw);
    if (!width && !height) { width = Math.round(vw); height = Math.round(vh); }
  }
  if (!width || !height || width < 1 || height < 1) {
    // 缺尺寸时给一个屏幕比例默认值，canvas 会按 viewBox 自动缩放绘制
    width = 1200; height = 800;
  }
  return { width, height };
}

/** 把 SVG File 转成可直接走原有上传流程的 PNG 元数据对象 */
export async function svgToPng(file, { maxSide = MAX_SIDE } = {}) {
  const text = await readAsText(file);
  const size = svgSize(text);
  const scale = Math.min(1, maxSide / Math.max(size.width, size.height));
  const outW = Math.max(1, Math.round(size.width * scale));
  const outH = Math.max(1, Math.round(size.height * scale));

  const blob = new Blob([text], { type: 'image/svg+xml;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  try {
    const img = await loadImage(url);
    const canvas = document.createElement('canvas');
    canvas.width = outW;
    canvas.height = outH;
    const ctx = canvas.getContext('2d');
    if (!ctx) throw new Error('当前浏览器不支持 Canvas，无法转换 SVG');
    // 白底：多数页面截图本身是不透明的，SVG 透明区域转 PNG 后也不会变黑
    ctx.fillStyle = '#ffffff';
    ctx.fillRect(0, 0, outW, outH);
    ctx.drawImage(img, 0, 0, outW, outH);
    let dataUrl;
    try {
      dataUrl = canvas.toDataURL('image/png');
    } catch {
      throw new Error('SVG 引用了跨域外部资源，无法转换为 PNG；请先在浏览器里另存为 PNG 后上传');
    }
    const comma = dataUrl.indexOf(',');
    const bytes = comma >= 0 ? Math.floor(((dataUrl.length - comma - 1) * 3) / 4) : 0;
    const baseName = (file.name || 'picture.svg').replace(/\.svg$/i, '');
    return {
      name: `${baseName}.png`,
      width: outW,
      height: outH,
      mime: 'image/png',
      bytes,
      dataUrl,
      sourceSvg: true,
    };
  } finally {
    URL.revokeObjectURL(url);
  }
}

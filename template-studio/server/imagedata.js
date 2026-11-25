/**
 * 无第三方依赖的图片 DataURL 校验与原始尺寸解析。
 * 坐标一律基于「原图像素」，因此必须先知道图片真实宽高，
 * 不能依赖浏览器里被 CSS 缩放后的显示尺寸。
 */

const ALLOWED = ['image/png', 'image/jpeg', 'image/webp', 'image/gif', 'image/bmp'];
const MAX_BYTES = 12 * 1024 * 1024;

const jpegSize = (b) => {
  let i = 2;
  while (i + 9 < b.length) {
    if (b[i] !== 0xff) { i += 1; continue; }
    const marker = b[i + 1];
    if (marker === 0xd8 || marker === 0xd9 || marker === 0x01 || (marker >= 0xd0 && marker <= 0xd7)) {
      i += 2; continue;
    }
    if (i + 4 > b.length) break;
    const len = b.readUInt16BE(i + 2);
    const isSof = [0xc0, 0xc1, 0xc2, 0xc3, 0xc5, 0xc6, 0xc7, 0xc9, 0xca, 0xcb, 0xcd, 0xce, 0xcf].includes(marker);
    if (isSof && len >= 7 && i + 9 <= b.length) {
      return { width: b.readUInt16BE(i + 7), height: b.readUInt16BE(i + 5) };
    }
    if (len < 2) break;
    i += 2 + len;
  }
  return null;
};

const webpSize = (b) => {
  if (b.toString('ascii', 0, 4) !== 'RIFF' || b.toString('ascii', 8, 12) !== 'WEBP') return null;
  const chunk = b.toString('ascii', 12, 16);
  if (chunk === 'VP8X' && b.length >= 30) {
    return {
      width: 1 + b.readUIntLE(24, 3),
      height: 1 + b.readUIntLE(27, 3),
    };
  }
  if (chunk === 'VP8 ' && b.length >= 30) {
    return {
      width: b.readUInt16LE(26) & 0x3fff,
      height: b.readUInt16LE(28) & 0x3fff,
    };
  }
  if (chunk === 'VP8L' && b.length >= 25) {
    const bits = b.readUInt32LE(21);
    return { width: 1 + (bits & 0x3fff), height: 1 + ((bits >> 14) & 0x3fff) };
  }
  return null;
};

export function readImageSize(buffer) {
  const b = buffer;
  if (b.length >= 24 && b.toString('hex', 0, 8) === '89504e470d0a1a0a') {
    return { width: b.readUInt32BE(16), height: b.readUInt32BE(20) };
  }
  if (b.length >= 10 && ['GIF87a', 'GIF89a'].includes(b.toString('ascii', 0, 6))) {
    return { width: b.readUInt16LE(6), height: b.readUInt16LE(8) };
  }
  if (b.length >= 26 && b.toString('ascii', 0, 2) === 'BM') {
    return { width: Math.abs(b.readInt32LE(18)), height: Math.abs(b.readInt32LE(22)) };
  }
  if (b.length >= 4 && b[0] === 0xff && b[1] === 0xd8) return jpegSize(b);
  if (b.length >= 30) return webpSize(b);
  return null;
}

/** 返回可直接交给多模态接口的规范化 dataURL，以及真实像素尺寸 */
export function parseImageDataUrl(input, { maxBytes = MAX_BYTES } = {}) {
  const raw = String(input || '');
  const m = /^data:(image\/[a-z0-9.+-]+);base64,([a-z0-9+/=\s]+)$/i.exec(raw);
  if (!m) throw new Error('图片格式不正确：请上传 PNG / JPG / WEBP / GIF / BMP，或直接使用页面截图粘贴');

  let mime = m[1].toLowerCase();
  if (mime === 'image/jpg') mime = 'image/jpeg';
  if (mime === 'image/svg+xml') {
    throw new Error('SVG 请在页面上传：页面会自动转换为 PNG 后再解析；直接调用接口时请传 PNG / JPG / WEBP');
  }
  if (!ALLOWED.includes(mime)) {
    throw new Error(`暂不支持 ${mime}：请上传 PNG / JPG / WEBP / GIF / BMP`);
  }

  const buffer = Buffer.from(m[2].replace(/\s+/g, ''), 'base64');
  if (!buffer.length) throw new Error('图片内容为空');
  if (buffer.length > maxBytes) {
    throw new Error(`图片过大（${(buffer.length / 1024 / 1024).toFixed(1)} MB），上限 ${(maxBytes / 1024 / 1024).toFixed(0)} MB`);
  }

  const size = readImageSize(buffer);
  if (!size || !size.width || !size.height || !Number.isFinite(size.width) || !Number.isFinite(size.height)) {
    throw new Error('无法读取图片尺寸：文件可能已损坏，请另存为 PNG / JPG 后重试');
  }
  if (size.width > 20000 || size.height > 20000) throw new Error('图片尺寸超出支持范围（20000px）');

  return {
    buffer,
    mime,
    width: size.width,
    height: size.height,
    bytes: buffer.length,
    dataUrl: `data:${mime};base64,${buffer.toString('base64')}`,
  };
}

export const IMAGE_LIMITS = {
  maxBytes: MAX_BYTES,
  accepted: ALLOWED,
  maxDimension: 20000,
};

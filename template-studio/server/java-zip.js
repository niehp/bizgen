/**
 * 轻量 ZIP/JAR 读取器（纯 Node.js，无第三方依赖）。
 * 扫描归档中的所有条目：Java 源码交给解析器结构化解析，
 * 其余可读文本（xml/properties/md/sql/json/yml 等）按行分块建立全文索引。
 * 支持 stored(0) 与 deflate(8)。
 */
import zlib from 'node:zlib';
import { looksLikeText } from './java-parser.js';

const U16 = (b, o) => b.readUInt16LE(o);
const U32 = (b, o) => b.readUInt32LE(o);
const SIG_EOCD = 0x06054b50;
const SIG_CENTRAL = 0x02014b50;
const SIG_LOCAL = 0x04034b50;

function findEocd(buf) {
  const min = Math.max(0, buf.length - 22 - 65535);
  for (let i = buf.length - 22; i >= min; i--) {
    if (U32(buf, i) === SIG_EOCD) return i;
  }
  throw new Error('不是有效的 ZIP/JAR 文件（未找到结束目录）');
}

/**
 * @returns {{files:Array<{path:string,content:string,bytes:number,isJava:boolean}>,
 *            javaCount:number, textCount:number, skipped:number, totalEntries:number}}
 */
export function readArchiveEntries(buffer, options = {}) {
  const { maxFiles = 20000, maxFileBytes = 8 * 1024 * 1024 } = options;
  const buf = Buffer.isBuffer(buffer) ? buffer : Buffer.from(buffer);
  const eocd = findEocd(buf);
  const total = U16(buf, eocd + 10);
  const cdSize = U32(buf, eocd + 12);
  const cdOffset = U32(buf, eocd + 16);
  if (cdOffset + cdSize > buf.length) throw new Error('ZIP 中央目录损坏');

  const files = [];
  let skipped = 0;
  let javaCount = 0;
  let textCount = 0;
  let offset = cdOffset;
  for (let n = 0; n < total; n++) {
    if (offset + 46 > buf.length || U32(buf, offset) !== SIG_CENTRAL) break;
    const method = U16(buf, offset + 10);
    const compSize = U32(buf, offset + 20);
    const uncompSize = U32(buf, offset + 24);
    const nameLen = U16(buf, offset + 28);
    const extraLen = U16(buf, offset + 30);
    const commentLen = U16(buf, offset + 32);
    const localOffset = U32(buf, offset + 42);
    const name = buf.toString('utf8', offset + 46, offset + 46 + nameLen).replace(/\\/g, '/');
    offset += 46 + nameLen + extraLen + commentLen;

    if (name.endsWith('/')) continue;
    if (uncompSize > maxFileBytes || files.length >= maxFiles) { skipped++; continue; }
    if (localOffset + 30 > buf.length || U32(buf, localOffset) !== SIG_LOCAL) { skipped++; continue; }
    const localNameLen = U16(buf, localOffset + 26);
    const localExtraLen = U16(buf, localOffset + 28);
    const dataStart = localOffset + 30 + localNameLen + localExtraLen;
    const compressed = buf.subarray(dataStart, dataStart + compSize);
    let raw;
    try {
      if (method === 0) raw = compressed;
      else if (method === 8) raw = zlib.inflateRawSync(compressed);
      else { skipped++; continue; }
    } catch { skipped++; continue; }
    if (raw.includes(0)) { skipped++; continue; }
    const content = raw.toString('utf8');
    const isJava = /\.java$/i.test(name);
    if (!isJava && !looksLikeText(name, content)) { skipped++; continue; }
    files.push({ path: name, content, bytes: uncompSize, isJava });
    if (isJava) javaCount++; else textCount++;
  }
  return { files, javaCount, textCount, skipped, totalEntries: total };
}

/** 兼容旧调用名 */
export const readJavaEntriesFromZip = readArchiveEntries;

/** 极简 .env 加载器（无第三方依赖）。不覆盖已有的进程环境变量。 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const here = path.dirname(fileURLToPath(import.meta.url));
const envPath = path.join(here, '..', '.env');

let cache = null;
let cachedMtime = -1;

export function dotenv() {
  let mtime = -1;
  try { mtime = fs.statSync(envPath).mtimeMs; } catch { cache = {}; cachedMtime = -1; return cache; }
  if (cache && mtime === cachedMtime) return cache;

  const out = {};
  try {
    for (const line of fs.readFileSync(envPath, 'utf8').split(/\r?\n/)) {
      const s = line.trim();
      if (!s || s.startsWith('#')) continue;
      const i = s.indexOf('=');
      if (i < 1) continue;
      const key = s.slice(0, i).trim();
      let value = s.slice(i + 1).trim();
      if ((value.startsWith('"') && value.endsWith('"')) || (value.startsWith("'") && value.endsWith("'"))) {
        value = value.slice(1, -1);
      }
      out[key] = value;
    }
  } catch { /* 文件不可读时按无配置处理 */ }
  cache = out;
  cachedMtime = mtime;
  return cache;
}

/** 进程环境变量优先，其次 .env */
export function envValue(key) {
  const fromProcess = String(process.env[key] ?? '').trim();
  return fromProcess || String(dotenv()[key] ?? '').trim();
}

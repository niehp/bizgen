/**
 * 固定値プリセットを JSON ファイルとして保存する。
 *   data/presets/<名前>.json
 * ファイル名は保存名に紐づく（日本語可）。1 プリセット = 1 ファイル。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizePreset, presetFileStem } from '../shared/preset.js';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIR = path.join(ROOT, 'data', 'presets');

const text = (v) => (v === undefined || v === null ? '' : String(v));

function ensureDir() {
  fs.mkdirSync(DIR, { recursive: true });
}

function safeId(id) {
  const s = String(id ?? '');
  if (!s || s.length > 220 || s === '.' || s === '..'
    || /[\\/]/.test(s) || /^\./.test(s) || /[. ]$/.test(s)) return null;
  return /^[\p{L}\p{N} ._()\-]+$/u.test(s) ? s : null;
}

function fileOf(id) {
  const safe = safeId(id);
  if (!safe) throw new Error('不正なプリセット ID です');
  return path.join(DIR, `${safe}.json`);
}

function info(id, preset, size) {
  return {
    id,
    file: `${id}.json`,
    name: preset.name || id,
    entryCount: preset.entries.length,
    entries: preset.entries,
    size,
    updatedAt: preset.updatedAt,
    createdAt: preset.createdAt,
  };
}

function listEntries() {
  ensureDir();
  return fs.readdirSync(DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => {
      try {
        const p = path.join(DIR, f);
        const raw = JSON.parse(fs.readFileSync(p, 'utf8'));
        return { id: f.slice(0, -5), path: p, preset: normalizePreset(raw), size: fs.statSync(p).size };
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

const byNewest = (a, b) => text(b.preset.updatedAt).localeCompare(text(a.preset.updatedAt)) || a.id.localeCompare(b.id);

export function listPresets() {
  return listEntries().sort(byNewest).map((e) => info(e.id, e.preset, e.size));
}

export function getPreset(id) {
  const p = fileOf(id);
  if (!fs.existsSync(p)) return null;
  return normalizePreset(JSON.parse(fs.readFileSync(p, 'utf8')));
}

export function savePreset(raw, id) {
  const preset = normalizePreset(raw);
  if (!preset.name) throw new Error('プリセット名は必須です');
  ensureDir();
  const wanted = text(id || preset.id || presetFileStem(preset.name));
  const safe = safeId(wanted);
  if (!safe) throw new Error('プリセット名に使用できない文字が含まれています');
  const p = path.join(DIR, `${safe}.json`);
  const now = new Date().toISOString();
  const existing = fs.existsSync(p) ? normalizePreset(JSON.parse(fs.readFileSync(p, 'utf8'))) : null;
  const stored = {
    ...preset,
    id: safe,
    createdAt: existing?.createdAt || preset.createdAt || now,
    updatedAt: now,
  };
  fs.writeFileSync(p, JSON.stringify(stored, null, 2), 'utf8');
  return { preset: stored, info: info(safe, stored, fs.statSync(p).size) };
}

export function deletePreset(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return false;
  fs.unlinkSync(p);
  return true;
}

/** CLI 用: 名前 / ID / JSON パスから固定値エントリを解決する */
export function resolvePresetEntries(ref) {
  const key = text(ref).trim();
  if (!key) return { entries: [], source: '' };
  // 直接 JSON パス
  if (/\.json$/i.test(key) && fs.existsSync(key)) {
    const p = normalizePreset(JSON.parse(fs.readFileSync(key, 'utf8')));
    return { entries: p.entries, source: key };
  }
  const direct = getPreset(key);
  if (direct) return { entries: direct.entries, source: `${key}.json` };
  const byName = listEntries().find((e) => e.preset.name === key || e.id === presetFileStem(key));
  if (byName) return { entries: byName.preset.entries, source: `${byName.id}.json` };
  throw new Error(`固定値プリセットが見つかりません: ${key}\n  --list-presets で一覧を確認できます`);
}

export function listPresetNames() {
  return listPresets().map((p) => p.name);
}

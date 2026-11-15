/**
 * Excel 列 ↔ DB 列のマッピング定義を JSON ファイルとして保存する。
 *
 * 【1 テーブル = 1 JSON ファイル】
 *   data/mappings/<保存名>__<schema.table>.json
 * 保存時の名前がファイル名の先頭に入るため、名前とファイルの対応がすぐ分かる。
 * 同じ保存名で保存された複数ファイルは「1 つのマッピンググループ」として
 * まとめて読み込める（Web / CLI どちらも同じルール）。
 * Excel の実データ本体は保存しない（列名と対応関係・既定値だけ）。
 *
 * 旧形式（1 ファイルに tables[] をまとめたもの／tableId+pairs の単一表形式）も
 * 読み込み・一覧表示・削除はそのまま可能。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { normalizeMapping, mappingFileId } from '../shared/mapping.js';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const DIR = path.join(ROOT, 'data', 'mappings');

const text = (v) => (v === undefined || v === null ? '' : String(v));
const WINDOWS_RESERVED = /^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])$/i;

function ensureDir() {
  fs.mkdirSync(DIR, { recursive: true });
}

/** 生成されたファイル名（Unicode 可）だけを ID として許可し、パス遷移を拒否する */
function safeId(id) {
  const s = String(id ?? '');
  if (!s || s.length > 220 || s === '.' || s === '..'
    || /[\\/]/.test(s) || /^\./.test(s) || /[. ]$/.test(s) || WINDOWS_RESERVED.test(s)) return null;
  return /^[\p{L}\p{N} ._()\-]+$/u.test(s) ? s : null;
}

function fileOf(id) {
  const safe = safeId(id);
  if (!safe) throw new Error('不正なマッピング ID です');
  return path.join(DIR, `${safe}.json`);
}

function info(id, mapping, size) {
  const tables = Array.isArray(mapping.tables) ? mapping.tables : [];
  return {
    id,
    file: `${id}.json`,
    name: mapping.name || id,
    groupName: mapping.groupName || mapping.name || id,
    groupFileCount: mapping.groupFileCount || tables.length || 1,
    tableIndex: mapping.tableIndex || 0,
    metadataId: mapping.metadataId,
    tableId: tables[0]?.tableId || mapping.tableId || '',
    tableCount: tables.length,
    sourceFile: mapping.sourceFile,
    sheet: mapping.sheet,
    pairCount: tables.reduce((n, t) => n + (t.pairs || []).filter((p) => p.target).length, 0),
    defaultCount: tables.reduce((n, t) => n + (t.defaults || []).filter((d) => String(d.value ?? '').trim()).length, 0),
    updatedAt: mapping.updatedAt,
    createdAt: mapping.createdAt,
    size,
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
        return { id: f.slice(0, -5), path: p, mapping: normalizeMapping(raw), size: fs.statSync(p).size };
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

const byNewest = (a, b) => text(b.updatedAt).localeCompare(text(a.updatedAt)) || a.id.localeCompare(b.id);

/** 個別 JSON ファイルの一覧（1 ファイル = 1 エントリ） */
export function listMappings() {
  return listEntries()
    .sort(byNewest)
    .map((e) => info(e.id, e.mapping, e.size));
}

/** 保存名ごとのグループ一覧（Web の読込選択肢・CLI の --list 用） */
export function listMappingGroups() {
  const byName = new Map();
  for (const e of listEntries()) {
    const key = e.mapping.groupName || e.mapping.name || e.id;
    if (!byName.has(key)) byName.set(key, []);
    byName.get(key).push(e);
  }
  return [...byName.entries()].map(([name, entries]) => {
    entries.sort((a, b) => (a.mapping.tableIndex || 999) - (b.mapping.tableIndex || 999)
      || a.id.localeCompare(b.id));
    const items = entries.map((e) => info(e.id, e.mapping, e.size));
    const updated = items.map((i) => text(i.updatedAt)).sort().reverse()[0] || '';
    const created = items.map((i) => text(i.createdAt)).filter(Boolean).sort()[0] || '';
    return {
      name,
      ids: items.map((i) => i.id),
      files: items.map((i) => i.file),
      tableIds: items.map((i) => i.tableId),
      tableCount: items.reduce((n, i) => n + (i.tableCount || 1), 0),
      pairCount: items.reduce((n, i) => n + i.pairCount, 0),
      defaultCount: items.reduce((n, i) => n + i.defaultCount, 0),
      fileCount: items.length,
      updatedAt: updated,
      createdAt: created,
    };
  }).sort((a, b) => text(b.updatedAt).localeCompare(text(a.updatedAt)) || a.name.localeCompare(b.name));
}

export function getMapping(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return null;
  return normalizeMapping(JSON.parse(fs.readFileSync(p, 'utf8')));
}

/** 保存名に属する全ファイルを 1 つのマッピング（tables[]）に復元する */
export function getGroupMapping(name) {
  const key = text(name);
  const entries = listEntries()
    .filter((e) => (e.mapping.groupName || e.mapping.name || e.id) === key)
    .sort((a, b) => (a.mapping.tableIndex || 999) - (b.mapping.tableIndex || 999)
      || a.id.localeCompare(b.id));
  if (!entries.length) return null;
  const tables = [];
  const seen = new Set();
  for (const e of entries) {
    for (const t of e.mapping.tables) {
      const k = t.tableId || `table_${tables.length + 1}`;
      if (seen.has(k)) continue;
      seen.add(k);
      tables.push(t);
    }
  }
  const first = entries[0].mapping;
  return {
    mapping: normalizeMapping({
      ...first,
      id: key,
      name: first.name || key,
      groupName: key,
      groupFileCount: entries.length,
      tableIndex: 0,
      tables,
    }),
    ids: entries.map((e) => e.id),
  };
}

/**
 * 保存：テーブル 1 つにつき 1 ファイル書き出す。
 * 同名グループの古いファイル（表を外した／名前を変えた場合）は自動で削除する。
 * requestedId は旧 API 互換用（明示指定時は先頭テーブルにその ID を使う）。
 */
export function saveMapping(raw, requestedId, replaceGroup) {
  ensureDir();
  const mapping = normalizeMapping(raw);
  const groupName = text(mapping.name).trim();
  if (!groupName) throw new Error('マッピング名を入力してください');
  const tables = mapping.tables;
  if (!tables.length) throw new Error('対象テーブルがありません');
  const total = tables.reduce((n, t) => n + t.pairs.length + t.defaults.length, 0);
  if (!total) throw new Error('マッピングする列がありません');
  if (requestedId && !safeId(requestedId)) throw new Error('不正なマッピング ID です');

  const now = new Date().toISOString();
  const groupKeys = new Set([groupName]);
  if (text(replaceGroup).trim()) groupKeys.add(text(replaceGroup).trim());
  const related = listEntries().filter((e) => groupKeys.has(e.mapping.groupName || e.mapping.name || e.id)
    || (requestedId && e.id === requestedId));
  const createdAt = related.map((e) => text(e.mapping.createdAt)).filter(Boolean).sort()[0] || now;

  const ids = [];
  const seen = new Set();
  tables.forEach((t, i) => {
    let id = requestedId && i === 0
      ? String(requestedId)
      : mappingFileId(groupName, t.tableId || `table${i + 1}`);
    if (seen.has(id)) {
      let n = 2;
      while (seen.has(`${id}_${n}`)) n += 1;
      id = `${id}_${n}`;
    }
    if (!safeId(id)) throw new Error(`ファイル名に使えない文字が含まれています: ${id}`);
    seen.add(id);
    ids.push(id);
  });

  // 同じ保存名で以前保存したが今回対象外になったファイルを掃除
  const deleted = [];
  for (const e of related) {
    if (seen.has(e.id)) continue;
    try { fs.unlinkSync(e.path); deleted.push(e.id); } catch { /* noop */ }
  }

  const written = [];
  const files = [];
  const items = [];
  ids.forEach((id, i) => {
    const single = normalizeMapping({
      ...mapping,
      id,
      name: groupName,
      groupName,
      groupFileCount: tables.length,
      tableIndex: i + 1,
      tables: [tables[i]],
      createdAt,
      updatedAt: now,
    });
    const p = fileOf(id);
    fs.writeFileSync(p, JSON.stringify(single, null, 2), 'utf8');
    written.push(single);
    files.push(`${id}.json`);
    items.push(info(id, single, fs.statSync(p).size));
  });

  const merged = normalizeMapping({
    ...mapping,
    id: ids[0],
    name: groupName,
    groupName,
    groupFileCount: tables.length,
    tableIndex: 0,
    tables,
    createdAt,
    updatedAt: now,
  });
  return {
    id: ids[0], ids, groupName, files, items, deleted,
    mapping: merged,
    info: items[0],
  };
}

export function deleteMapping(id) {
  ensureDir();
  const p = fileOf(id);
  if (!fs.existsSync(p)) return false;
  fs.unlinkSync(p);
  return true;
}

/** 保存名に属する JSON ファイルをまとめて削除する */
export function deleteMappingGroup(name) {
  const key = text(name);
  const targets = listEntries().filter((e) => (e.mapping.groupName || e.mapping.name || e.id) === key);
  let n = 0;
  for (const e of targets) {
    try { fs.unlinkSync(e.path); n += 1; } catch { /* noop */ }
  }
  return n;
}

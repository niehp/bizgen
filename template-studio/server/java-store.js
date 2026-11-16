/**
 * Java 代码索引库（Node 内置 SQLite + FTS5）。
 * 数据表：java_sources（扫描批次）/ java_files（源码全文）/ java_entries（方法级条目）/ java_fts（全文索引）。
 * 所有检索字段都能独立查询，命中后返回行级上下文与相邻方法。
 */
import { DatabaseSync } from 'node:sqlite';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import { fileURLToPath } from 'node:url';
import { parseJavaSources, looksLikeText, isBinaryFileName } from './java-parser.js';
import { readArchiveEntries } from './java-zip.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(__dirname, '..', 'data');
fs.mkdirSync(DATA_DIR, { recursive: true });
const db = new DatabaseSync(path.join(DATA_DIR, 'code-index.sqlite'));
db.exec('PRAGMA journal_mode = DELETE; PRAGMA synchronous = NORMAL;');

db.exec(`
CREATE TABLE IF NOT EXISTS java_sources (
  id TEXT PRIMARY KEY,
  name TEXT NOT NULL,
  kind TEXT NOT NULL,
  created_at TEXT NOT NULL,
  updated_at TEXT NOT NULL,
  file_count INTEGER NOT NULL DEFAULT 0,
  entry_count INTEGER NOT NULL DEFAULT 0,
  note TEXT NOT NULL DEFAULT '',
  group_name TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS java_scan_roots (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  path TEXT NOT NULL UNIQUE,
  group_name TEXT NOT NULL DEFAULT '',
  source_id TEXT NOT NULL DEFAULT '',
  file_sig TEXT NOT NULL DEFAULT '{}',
  last_scan TEXT NOT NULL DEFAULT ''
);
CREATE TABLE IF NOT EXISTS java_root_archives (
  root_id INTEGER NOT NULL,
  path TEXT NOT NULL,
  source_id TEXT NOT NULL,
  sig TEXT NOT NULL DEFAULT '',
  PRIMARY KEY (root_id, path)
);
CREATE TABLE IF NOT EXISTS java_files (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id TEXT NOT NULL,
  path TEXT NOT NULL,
  file_name TEXT NOT NULL,
  package_name TEXT NOT NULL,
  content TEXT NOT NULL,
  UNIQUE(source_id, path)
);
CREATE TABLE IF NOT EXISTS java_entries (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  source_id TEXT NOT NULL,
  file_id INTEGER NOT NULL,
  kind TEXT NOT NULL,
  path TEXT NOT NULL,
  package_name TEXT NOT NULL,
  class_name TEXT NOT NULL,
  field_name TEXT NOT NULL DEFAULT '',
  method_name TEXT NOT NULL,
  signature TEXT NOT NULL,
  method_content TEXT NOT NULL,
  package_comment TEXT NOT NULL,
  class_comment TEXT NOT NULL,
  method_comment TEXT NOT NULL,
  field_comment TEXT NOT NULL DEFAULT '',
  start_line INTEGER NOT NULL,
  end_line INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_entries_source ON java_entries(source_id);
CREATE INDEX IF NOT EXISTS idx_entries_file ON java_entries(file_id);
CREATE INDEX IF NOT EXISTS idx_entries_pkg ON java_entries(package_name);
CREATE INDEX IF NOT EXISTS idx_entries_class ON java_entries(class_name);
CREATE INDEX IF NOT EXISTS idx_entries_method ON java_entries(method_name);
`);

const FTS_COLUMNS = [
  'package_name', 'class_name', 'field_name', 'method_name', 'method_content',
  'package_comment', 'class_comment', 'method_comment', 'field_comment',
];
const FTS_DDL = `
CREATE VIRTUAL TABLE IF NOT EXISTS java_fts USING fts5(
  ${FTS_COLUMNS.join(', ')},
  content='java_entries', content_rowid='id', tokenize='unicode61'
);
CREATE TRIGGER IF NOT EXISTS java_fts_ai AFTER INSERT ON java_entries BEGIN
  INSERT INTO java_fts(rowid, ${FTS_COLUMNS.join(', ')})
  VALUES (new.id, ${FTS_COLUMNS.map((c) => `new.${c}`).join(', ')});
END;
CREATE TRIGGER IF NOT EXISTS java_fts_ad AFTER DELETE ON java_entries BEGIN
  INSERT INTO java_fts(java_fts, rowid, ${FTS_COLUMNS.join(', ')})
  VALUES ('delete', old.id, ${FTS_COLUMNS.map((c) => `old.${c}`).join(', ')});
END;
CREATE TRIGGER IF NOT EXISTS java_fts_au AFTER UPDATE ON java_entries BEGIN
  INSERT INTO java_fts(java_fts, rowid, ${FTS_COLUMNS.join(', ')})
  VALUES ('delete', old.id, ${FTS_COLUMNS.map((c) => `old.${c}`).join(', ')});
  INSERT INTO java_fts(rowid, ${FTS_COLUMNS.join(', ')})
  VALUES (new.id, ${FTS_COLUMNS.map((c) => `new.${c}`).join(', ')});
END;
`;
db.exec(FTS_DDL);

// 兼容已有索引库：补齐字段列，必要时重建 FTS5（旧版索引没有 field_name / field_comment）
const sourceCols = db.prepare('PRAGMA table_info(java_sources)').all().map((c) => c.name);
if (!sourceCols.includes('group_name')) db.exec("ALTER TABLE java_sources ADD COLUMN group_name TEXT NOT NULL DEFAULT ''");
const entryCols = db.prepare('PRAGMA table_info(java_entries)').all().map((c) => c.name);
if (!entryCols.includes('field_name')) db.exec("ALTER TABLE java_entries ADD COLUMN field_name TEXT NOT NULL DEFAULT ''");
if (!entryCols.includes('field_comment')) db.exec("ALTER TABLE java_entries ADD COLUMN field_comment TEXT NOT NULL DEFAULT ''");
db.exec('CREATE INDEX IF NOT EXISTS idx_entries_field ON java_entries(field_name)');
const ftsCols = db.prepare('PRAGMA table_info(java_fts)').all().map((c) => c.name);
if (!FTS_COLUMNS.every((c) => ftsCols.includes(c))) {
  db.exec(`DROP TRIGGER IF EXISTS java_fts_ai; DROP TRIGGER IF EXISTS java_fts_ad;
           DROP TRIGGER IF EXISTS java_fts_au; DROP TABLE IF EXISTS java_fts;`);
  db.exec(FTS_DDL);
  db.exec(`INSERT INTO java_fts(rowid, ${FTS_COLUMNS.join(', ')})
           SELECT id, ${FTS_COLUMNS.join(', ')} FROM java_entries`);
}

// 不保留任何内置示例数据：清理旧版本可能写入的 demo 批次
for (const row of db.prepare("SELECT id FROM java_sources WHERE kind = 'demo' OR name LIKE '__demo__%'").all()) {
  deleteSource(row.id);
}

export const FIELDS = [
  { key: 'packageName', col: 'package_name', label: '包名' },
  { key: 'className', col: 'class_name', label: '类名' },
  { key: 'fieldName', col: 'field_name', label: '字段名' },
  { key: 'methodName', col: 'method_name', label: '方法名' },
  { key: 'methodContent', col: 'method_content', label: '方法内容' },
  { key: 'packageComment', col: 'package_comment', label: '包注释' },
  { key: 'classComment', col: 'class_comment', label: '类注释' },
  { key: 'methodComment', col: 'method_comment', label: '方法注释' },
  { key: 'fieldComment', col: 'field_comment', label: '字段注释' },
];
const FIELD_BY_KEY = Object.fromEntries(FIELDS.map((f) => [f.key, f]));
const ALL_COLUMNS = FIELDS.map((f) => `e.${f.col}`).join(', ') + ', e.signature';
const MAX_SCAN = 60000;

const now = () => new Date().toISOString();
const asRows = (stmt, ...args) => stmt.all(...args).map((r) => ({ ...r }));

/* ---------------- 分组 / 过滤条件工具 ---------------- */

/** 多级分组以 / 分隔（同时兼容反斜杠输入），规范化后形如 平台/FC模块/二方库 */
function normalizeGroup(value) {
  return String(value ?? '')
    .split(/[/\\]/)
    .map((s) => s.trim())
    .filter(Boolean)
    .slice(0, 8)
    .join('/')
    .slice(0, 200);
}
function groupParent(path) {
  const i = path.lastIndexOf('/');
  return i < 0 ? '' : path.slice(0, i);
}
/** 选中父分组时同时包含其所有后代 */
function groupFilterClause(group, alias = 's') {
  const g = normalizeGroup(group);
  if (!g) return { sql: '', params: [] };
  const esc = escapeLike(g);
  return {
    sql: ` AND (${alias}.group_name = ? OR ${alias}.group_name LIKE ? ESCAPE '\\')`,
    params: [g, `${esc}/%`],
  };
}
/** 包名过滤：多个用逗号/分号分隔，* 表示任意字符（如 com.example.*） */
function parsePackageFilters(input) {
  if (input == null) return [];
  const parts = Array.isArray(input) ? input : String(input).split(/[,;\n]+/);
  return parts.map((p) => String(p).trim()).filter(Boolean).slice(0, 20);
}
function packageFilterClause(filters, caseSensitive, alias = 'e') {
  if (!filters.length) return { sql: '', params: [] };
  const col = caseSensitive ? `${alias}.package_name` : `LOWER(${alias}.package_name)`;
  // GLOB 大小写敏感且原生支持 * 通配符；LIKE 默认对 ASCII 不区分大小写，故仅用于忽略大小写场景
  const op = caseSensitive ? 'GLOB' : 'LIKE';
  const pattern = (f) => {
    const value = caseSensitive ? String(f) : String(f).toLowerCase();
    if (caseSensitive) {
      return value.replace(/[[\]?]/g, (ch) => ({ '[': '[[]', ']': '[]]', '?': '[?]' })[ch]);
    }
    return value.replace(/[\\%_]/g, '\\$&').replace(/\*/g, '%');
  };
  const cond = `${col} ${op} ?${caseSensitive ? '' : " ESCAPE '\\'"}`;
  return {
    sql: ` AND (${filters.map(() => cond).join(' OR ')})`,
    params: filters.map(pattern),
  };
}

/* ---------------- 数据源管理 ---------------- */

export function listSources() {
  const rows = asRows(db.prepare('SELECT * FROM java_sources ORDER BY updated_at DESC'));
  const counts = asRows(db.prepare('SELECT source_id, COUNT(*) n FROM java_files GROUP BY source_id'));
  const map = Object.fromEntries(counts.map((c) => [c.source_id, c.n]));
  return rows.map((r) => ({ ...r, actualFiles: map[r.id] || 0 }));
}

export function stats() {
  const one = (sql) => ({ ...db.prepare(sql).get() });
  return {
    sources: one('SELECT COUNT(*) n FROM java_sources').n,
    files: one('SELECT COUNT(*) n FROM java_files').n,
    entries: one('SELECT COUNT(*) n FROM java_entries').n,
    methods: one("SELECT COUNT(*) n FROM java_entries WHERE kind IN ('method','constructor')").n,
    fields: one("SELECT COUNT(*) n FROM java_entries WHERE kind = 'field'").n,
    texts: one("SELECT COUNT(*) n FROM java_entries WHERE kind = 'text'").n,
    byKind: Object.fromEntries(asRows(db.prepare('SELECT kind, COUNT(*) n FROM java_sources GROUP BY kind'))
      .map((r) => [r.kind, r.n])),
  };
}

export function deleteSource(id) {
  // 删除文件夹根批次时，同时移除其下自动扫描的 JAR/ZIP 子批次与扫描根记录
  const root = db.prepare('SELECT id FROM java_scan_roots WHERE source_id = ?').get(id);
  if (root) {
    const archives = asRows(db.prepare('SELECT source_id FROM java_root_archives WHERE root_id = ?'), root.id);
    for (const a of archives) if (a.source_id !== id) deleteSource(a.source_id);
  }
  db.exec('BEGIN');
  try {
    if (root) {
      db.prepare('DELETE FROM java_root_archives WHERE root_id = ?').run(root.id);
      db.prepare('DELETE FROM java_scan_roots WHERE id = ?').run(root.id);
    }
    db.prepare('DELETE FROM java_root_archives WHERE source_id = ?').run(id);
    db.prepare('DELETE FROM java_scan_roots WHERE source_id = ?').run(id);
    db.prepare('DELETE FROM java_entries WHERE source_id = ?').run(id);
    db.prepare('DELETE FROM java_files WHERE source_id = ?').run(id);
    const r = db.prepare('DELETE FROM java_sources WHERE id = ?').run(id);
    db.exec('COMMIT');
    return r.changes > 0;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
}

/** 修改数据源所属分组（同时同步其扫描根） */
export function setSourceGroup(id, group) {
  const value = normalizeGroup(group);
  const r = db.prepare('UPDATE java_sources SET group_name = ?, updated_at = ? WHERE id = ?')
    .run(value, now(), id);
  db.prepare('UPDATE java_scan_roots SET group_name = ? WHERE source_id = ?').run(value, id);
  // 手动扫描的归档子批次若属于某个扫描根，改组时整组统一，避免下次增量扫描被覆盖
  const ref = db.prepare('SELECT root_id FROM java_root_archives WHERE source_id = ?').get(id);
  if (ref) {
    const root = db.prepare('SELECT id, source_id FROM java_scan_roots WHERE id = ?').get(ref.root_id);
    if (root) {
      db.prepare('UPDATE java_scan_roots SET group_name = ? WHERE id = ?').run(value, root.id);
      db.prepare('UPDATE java_sources SET group_name = ? WHERE id = ?').run(value, root.source_id);
      const siblings = asRows(db.prepare('SELECT source_id FROM java_root_archives WHERE root_id = ?'), root.id);
      for (const sib of siblings) {
        db.prepare('UPDATE java_sources SET group_name = ? WHERE id = ?').run(value, sib.source_id);
      }
    }
  }
  return r.changes > 0;
}

/** 修改数据源名称（仅改展示名，不影响索引内容） */
export function setSourceName(id, name) {
  const value = String(name ?? '').trim().slice(0, 120);
  if (!value) throw new Error('数据源名称不能为空');
  const r = db.prepare('UPDATE java_sources SET name = ?, updated_at = ? WHERE id = ?')
    .run(value, now(), id);
  return r.changes > 0;
}

/**
 * 修改分组树中某个节点的名称：该节点及其所有后代路径同步替换前缀。
 * from 为完整分组路径（如 平台/FC模块），to 为新节点名（可含 / 形成新层级）。
 */
export function renameGroup(from, to) {
  const oldPath = normalizeGroup(from);
  const newLeaf = normalizeGroup(to);
  if (!oldPath) throw new Error('请选择要改名的分组');
  if (!newLeaf) throw new Error('分组名称不能为空');
  if (oldPath === newLeaf) return false;
  const parent = groupParent(oldPath);
  const newPrefix = parent ? `${parent}/${newLeaf}` : newLeaf;
  const esc = escapeLike(oldPath);
  const where = `group_name = ? OR group_name LIKE ? ESCAPE '\\'`;
  const r = db.prepare(`UPDATE java_sources
    SET group_name = ? || substr(group_name, ?), updated_at = ? WHERE ${where}`)
    .run(newPrefix, oldPath.length + 1, now(), oldPath, `${esc}/%`);
  db.prepare(`UPDATE java_scan_roots
    SET group_name = ? || substr(group_name, ?) WHERE ${where}`)
    .run(newPrefix, oldPath.length + 1, oldPath, `${esc}/%`);
  return r.changes > 0;
}

/** 返回分组路径清单（含中间层级），供命令行与界面下拉使用 */
export function listGroups() {
  const paths = new Set();
  for (const row of db.prepare('SELECT DISTINCT group_name FROM java_sources').all()) {
    const parts = normalizeGroup(row.group_name).split('/').filter(Boolean);
    let p = '';
    for (const part of parts) {
      p = p ? `${p}/${part}` : part;
      paths.add(p);
    }
  }
  return [...paths].sort();
}

export function listRoots() {
  return asRows(db.prepare(`SELECT r.id, r.path, r.group_name, r.last_scan, r.source_id, s.name source_name
    FROM java_scan_roots r LEFT JOIN java_sources s ON s.id = r.source_id
    ORDER BY r.last_scan DESC`));
}

/* ---------------- 扫描 / 入库 ---------------- */

function ensureSource(name, kind, note, reuse = false, groupName = '') {
  const group = normalizeGroup(groupName);
  if (reuse) {
    const found = db.prepare('SELECT * FROM java_sources WHERE name = ? AND kind = ?').get(name, kind);
    if (found) {
      db.prepare('UPDATE java_sources SET updated_at = ?, note = ?, group_name = ? WHERE id = ?')
        .run(now(), note, group, found.id);
      return { id: found.id, reused: true };
    }
  }
  const id = crypto.randomUUID();
  db.prepare(`INSERT INTO java_sources (id, name, kind, created_at, updated_at, note, group_name)
              VALUES (?, ?, ?, ?, ?, ?, ?)`).run(id, name, kind, now(), now(), note, group);
  return { id, reused: false };
}

function ingestFiles(sourceId, files) {
  const { results, errors } = parseJavaSources(files);
  const upsertFile = db.prepare(`INSERT INTO java_files (source_id, path, file_name, package_name, content)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(source_id, path) DO UPDATE SET file_name=excluded.file_name,
      package_name=excluded.package_name, content=excluded.content`);
  const delEntries = db.prepare('DELETE FROM java_entries WHERE file_id = ?');
  const getId = db.prepare('SELECT id FROM java_files WHERE source_id = ? AND path = ?');
  const insEntry = db.prepare(`INSERT INTO java_entries
    (source_id, file_id, kind, path, package_name, class_name, field_name, method_name, signature,
     method_content, package_comment, class_comment, method_comment, field_comment, start_line, end_line)
    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`);

  let entryCount = 0;
  let javaFiles = 0;
  let textFiles = 0;
  for (const f of results) {
    if (f.isJava) javaFiles++; else textFiles++;
    upsertFile.run(sourceId, f.path, f.fileName, f.packageName, f.content);
    const fileId = getId.get(sourceId, f.path).id;
    delEntries.run(fileId);
    for (const e of f.entries) {
      insEntry.run(sourceId, fileId, e.kind, f.path, e.packageName || '', e.className || '',
        e.fieldName || '', e.methodName || '', e.signature || '', e.methodContent || '',
        e.packageComment || '', e.classComment || '', e.methodComment || '', e.fieldComment || '',
        e.startLine || 1, e.endLine || 1);
      entryCount++;
    }
  }
  const fileCount = db.prepare('SELECT COUNT(*) n FROM java_files WHERE source_id = ?').get(sourceId).n;
  const totalEntries = db.prepare('SELECT COUNT(*) n FROM java_entries WHERE source_id = ?').get(sourceId).n;
  db.prepare('UPDATE java_sources SET file_count = ?, entry_count = ?, updated_at = ? WHERE id = ?')
    .run(fileCount, totalEntries, now(), sourceId);
  return { fileCount, entryCount, totalEntries, javaFiles, textFiles, errors };
}

const withTx = (fn) => {
  db.exec('BEGIN');
  try {
    const out = fn();
    db.exec('COMMIT');
    return out;
  } catch (e) {
    db.exec('ROLLBACK');
    throw e;
  }
};

function validateFiles(files) {
  if (!Array.isArray(files) || !files.length) throw new Error('没有可扫描的文本文件');
  if (files.length > 5000) throw new Error('单次最多扫描 5000 个文件，请分批上传');
  return files.map((f) => {
    if (!f || typeof f.path !== 'string' || typeof f.content !== 'string') {
      throw new Error('文件格式错误：需要 { path, content }');
    }
    if (f.content.length > 8 * 1024 * 1024) throw new Error(`文件过大：${f.path}`);
    return { path: f.path, content: f.content };
  });
}

export function scanFolder(name, files, note = '', groupName = '') {
  const valid = validateFiles(files);
  const { id, reused } = ensureSource(
    String(name || '文件夹扫描').slice(0, 120), 'folder', note, true, groupName,
  );
  const out = withTx(() => ingestFiles(id, valid));
  return { sourceId: id, reused, ...out };
}

/** 把一个归档（JAR/ZIP）作为独立数据源入库 */
function ingestArchive(buffer, name, groupName = '', note = '') {
  const parsed = readArchiveEntries(buffer);
  if (!parsed.files.length) {
    return {
      empty: true, sourceId: '', fileCount: 0, entryCount: 0, javaFiles: 0, textFiles: 0,
      ...parsed,
    };
  }
  const { id } = ensureSource(
    String(name || 'jar 扫描').slice(0, 120), 'jar', note, false, groupName,
  );
  const out = withTx(() => ingestFiles(id, parsed.files));
  return {
    sourceId: id, reused: false, ...out,
    skipped: parsed.skipped, jarEntries: parsed.totalEntries,
    archiveJavaFiles: parsed.javaCount, archiveTextFiles: parsed.textCount,
  };
}

export function scanJar(name, base64, note = '', groupName = '') {
  if (typeof base64 !== 'string' || !base64) throw new Error('请提供 JAR 文件的 base64 内容');
  const buffer = Buffer.from(base64, 'base64');
  if (!buffer.length) throw new Error('JAR 文件内容为空');
  const out = ingestArchive(buffer, name || 'jar 扫描', groupName, note || 'JAR / ZIP 归档');
  if (out.empty) throw new Error('该归档中没有可索引的文本文件（Java 源码、配置、文档等）。');
  return out;
}

const IGNORE_DIR = /(^|[\\/])(node_modules|target|build|dist|\.git|\.idea|\.workbuddy)([\\/]|$)/i;

/** 只收集路径与修改签名，不读文件内容；内容仅在签名变化时读取 */
function collectTree(root) {
  const found = [];
  const skipped = [];
  const walk = (dir, depth) => {
    if (found.length >= 5000 || depth > 40) return;
    for (const ent of fs.readdirSync(dir, { withFileTypes: true })) {
      if (found.length >= 5000) break;
      if (IGNORE_DIR.test(path.join(dir, ent.name))) continue;
      const full = path.join(dir, ent.name);
      try {
        if (ent.isDirectory()) {
          walk(full, depth + 1);
        } else if (ent.isSymbolicLink()) {
          continue;
        } else {
          const relPath = path.relative(root, full).replace(/\\/g, '/');
          const st = fs.statSync(full);
          if (st.size > 8 * 1024 * 1024) { skipped.push({ path: relPath, error: '文件超过 8MB' }); continue; }
          const archive = /\.(jar|zip)$/i.test(ent.name);
          if (!archive && isBinaryFileName(ent.name)) {
            skipped.push({ path: relPath, error: '二进制文件' });
            continue;
          }
          found.push({ relPath, full, archive, sig: `${Math.round(st.mtimeMs)}:${st.size}` });
        }
      } catch (e) {
        skipped.push({ path: relPath(full, root), error: e.message || String(e) });
      }
    }
  };
  walk(root, 0);
  return { found, skipped };
}
const relPath = (full, root) => path.relative(root, full).replace(/\\/g, '/');

/**
 * 递归扫描服务器本地文件夹（Web 端填路径即可）。
 * 首次全量，之后只重新读取「新增 / 修改 / 删除」的文件；目录下的 JAR/ZIP 同时作为独立数据源入库。
 */
export function scanPath(dirPath, name = '', note = '', groupName = '') {
  const root = path.resolve(String(dirPath || '').trim());
  if (!root || root === path.parse(root).root) throw new Error('请指定具体的源码文件夹路径');
  if (!fs.existsSync(root)) throw new Error('路径不存在：' + root);
  if (!fs.statSync(root).isDirectory()) throw new Error('指定路径不是文件夹：' + root);
  const groupValue = normalizeGroup(groupName);

  const existing = db.prepare('SELECT * FROM java_scan_roots WHERE path = ?').get(root);
  const { found, skipped } = collectTree(root);
  const foundPaths = new Set(found.map((i) => i.relPath));
  const sigMap = existing ? JSON.parse(existing.file_sig || '{}') : {};
  const archPrev = existing
    ? asRows(db.prepare('SELECT path, sig, source_id FROM java_root_archives WHERE root_id = ?'), existing.id)
    : [];
  const archMap = new Map(archPrev.map((r) => [r.path, { sig: r.sig, source_id: r.source_id }]));

  // 1) 文本 / 源码文件：只处理签名变化的
  const changedText = [];
  for (const item of found) {
    if (item.archive || sigMap[item.relPath] === item.sig) continue;
    const buf = fs.readFileSync(item.full);
    if (buf.includes(0)) { skipped.push({ path: item.relPath, error: '二进制文件' }); continue; }
    const text = buf.toString('utf8');
    if (!looksLikeText(item.relPath, text)) {
      skipped.push({ path: item.relPath, error: '二进制文件' });
      continue;
    }
    changedText.push({ path: item.relPath, content: text });
    sigMap[item.relPath] = item.sig;
  }
  const removed = Object.keys(sigMap).filter((p) => !foundPaths.has(p));
  for (const p of removed) delete sigMap[p];

  // 2) 目录下的 JAR / ZIP：变化时重建对应数据源
  let archivesAdded = 0;
  let archivesUpdated = 0;
  let archivesRemoved = 0;
  let archivesFailed = 0;
  const currentArchives = found.filter((i) => i.archive);
  const archivePaths = new Set(currentArchives.map((i) => i.relPath));
  for (const a of currentArchives) {
    const prev = archMap.get(a.relPath);
    if (prev && prev.sig === a.sig) continue;
    let out;
    try {
      out = ingestArchive(
        fs.readFileSync(a.full),
        `${path.basename(root)}/${a.relPath}`,
        groupName || existing?.group_name || '',
        `${root} :: ${a.relPath}`,
      );
    } catch (e) {
      skipped.push({ path: a.relPath, error: e.message || String(e) });
      archivesFailed++;
      continue;
    }
    if (out.empty) { archivesFailed++; continue; }
    if (prev) { deleteSource(prev.source_id); archivesUpdated++; } else archivesAdded++;
    archMap.set(a.relPath, { sig: a.sig, source_id: out.sourceId });
  }
  for (const [p, row] of [...archMap]) {
    if (archivePaths.has(p)) continue;
    deleteSource(row.source_id);
    archMap.delete(p);
    archivesRemoved++;
  }

  // 3) 文件夹主数据源
  let sourceId = existing?.source_id || '';
  let created = false;
  if (!sourceId || !db.prepare('SELECT 1 FROM java_sources WHERE id = ?').get(sourceId)) {
    sourceId = ensureSource(
      String(name || path.basename(root)).slice(0, 120), 'folder', note || root, true, groupName,
    ).id;
    created = true;
  } else {
    db.prepare('UPDATE java_sources SET group_name = ?, updated_at = ? WHERE id = ?')
      .run(groupValue, now(), sourceId);
  }

  let ingestSummary = { javaFiles: 0, textFiles: 0, entryCount: 0 };
  if (created || changedText.length || removed.length) {
    withTx(() => {
      if (changedText.length) ingestSummary = ingestFiles(sourceId, changedText);
      for (const p of removed) {
        const f = db.prepare('SELECT id FROM java_files WHERE source_id = ? AND path = ?').get(sourceId, p);
        if (f) {
          db.prepare('DELETE FROM java_entries WHERE file_id = ?').run(f.id);
          db.prepare('DELETE FROM java_files WHERE id = ?').run(f.id);
        }
      }
      const fileCount = db.prepare('SELECT COUNT(*) n FROM java_files WHERE source_id = ?').get(sourceId).n;
      const totalEntries = db.prepare('SELECT COUNT(*) n FROM java_entries WHERE source_id = ?').get(sourceId).n;
      db.prepare('UPDATE java_sources SET file_count = ?, entry_count = ?, updated_at = ? WHERE id = ?')
        .run(fileCount, totalEntries, now(), sourceId);
      ingestSummary.entryCount = totalEntries;
      ingestSummary.fileCount = fileCount;
    });
  }

  db.prepare(`INSERT INTO java_scan_roots (path, group_name, source_id, file_sig, last_scan)
    VALUES (?, ?, ?, ?, ?)
    ON CONFLICT(path) DO UPDATE SET group_name=excluded.group_name, source_id=excluded.source_id,
      file_sig=excluded.file_sig, last_scan=excluded.last_scan`)
    .run(root, groupValue, sourceId, JSON.stringify(sigMap), now());
  const rootId = existing?.id || db.prepare('SELECT id FROM java_scan_roots WHERE path = ?').get(root).id;
  for (const [p, row] of archMap) {
    db.prepare(`INSERT INTO java_root_archives (root_id, path, source_id, sig) VALUES (?, ?, ?, ?)
      ON CONFLICT(root_id, path) DO UPDATE SET source_id=excluded.source_id, sig=excluded.sig`)
      .run(rootId, p, row.source_id, row.sig);
  }

  return {
    sourceId, root, reused: !!existing, incremental: !!existing,
    fileCount: ingestSummary.fileCount ?? db.prepare('SELECT COUNT(*) n FROM java_files WHERE source_id = ?').get(sourceId).n,
    entryCount: ingestSummary.entryCount ?? db.prepare('SELECT COUNT(*) n FROM java_entries WHERE source_id = ?').get(sourceId).n,
    javaFiles: ingestSummary.javaFiles, textFiles: ingestSummary.textFiles,
    added: changedText.length, removed: removed.length,
    archivesAdded, archivesUpdated, archivesRemoved, archivesFailed, archives: archMap.size,
    skipped: skipped.length, skipDetails: skipped.slice(0, 20),
  };
}

/** 每次检索前调用：对所有登记过的文件夹执行增量扫描 */
export function refreshScanRoots() {
  const roots = listRoots();
  const report = {
    roots: roots.length, added: 0, removed: 0,
    archivesAdded: 0, archivesUpdated: 0, archivesRemoved: 0, errors: [],
  };
  for (const r of roots) {
    try {
      const out = scanPath(r.path, '', r.path, r.group_name);
      report.added += out.added;
      report.removed += out.removed;
      report.archivesAdded += out.archivesAdded;
      report.archivesUpdated += out.archivesUpdated;
      report.archivesRemoved += out.archivesRemoved;
    } catch (e) {
      report.errors.push({ path: r.path, error: e.message || String(e) });
    }
  }
  return report;
}

/** 命中详情里的「查看完整文件」按需读取 */
export function getFileContent(fileId) {
  const file = db.prepare('SELECT * FROM java_files WHERE id = ?').get(fileId);
  if (!file) throw new Error('未找到该文件');
  const content = String(file.content ?? '');
  return {
    id: file.id, path: file.path, fileName: file.file_name,
    content, lines: content.split('\n').length, bytes: Buffer.byteLength(content),
  };
}

/* ---------------- 检索辅助 ---------------- */

const escapeHtml = (s) => String(s ?? '')
  .replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;').replace(/'/g, '&#39;');
const escapeLike = (s) => String(s).replace(/[\\%_]/g, '\\$&');
const collapseWs = (s) => String(s ?? '').replace(/\s+/g, ' ').trim();

function findNeedle(haystack, needles, sensitive) {
  const target = sensitive ? String(haystack ?? '') : String(haystack ?? '').toLowerCase();
  for (const raw of needles) {
    if (!raw) continue;
    const needle = sensitive ? raw : raw.toLowerCase();
    const at = target.indexOf(needle);
    if (at >= 0) return { index: at, length: needle.length };
  }
  return null;
}

/** 在规范化空白后的文本中定位，返回原始文本中的近似区间 */
function findCollapsed(haystack, needles, sensitive) {
  const original = String(haystack ?? '');
  const norm = collapseWs(original);
  const targetNorm = sensitive ? norm : norm.toLowerCase();
  const map = [];
  let wasSpace = false;
  for (let i = 0; i < original.length; i++) {
    const isSpace = /\s/.test(original[i]);
    if (!isSpace || !wasSpace) map.push(i);
    wasSpace = isSpace;
  }
  for (const raw of needles) {
    const needle = collapseWs(raw);
    if (!needle) continue;
    const at = targetNorm.indexOf(sensitive ? needle : needle.toLowerCase());
    if (at >= 0) {
      const start = map[at] ?? 0;
      const end = map[Math.min(at + needle.length - 1, map.length - 1)] ?? start;
      return { index: start, length: end - start + 1 };
    }
  }
  return null;
}

function mergeRanges(ranges) {
  const sorted = ranges.filter((r) => r && r.length > 0).sort((a, b) => a.index - b.index);
  const out = [];
  for (const r of sorted) {
    const last = out[out.length - 1];
    if (last && r.index <= last.index + last.length) {
      last.length = Math.max(last.length, r.index + r.length - last.index);
    } else out.push({ ...r });
  }
  return out;
}

/** 生成带 <mark> 的 HTML（先转义，再插入标记） */
function highlight(text, ranges) {
  const src = String(text ?? '');
  const merged = mergeRanges(ranges).filter((r) => r.index < src.length);
  if (!merged.length) return escapeHtml(src);
  let html = '';
  let pos = 0;
  for (const r of merged) {
    if (r.index < pos) continue;
    html += escapeHtml(src.slice(pos, r.index));
    html += '<mark class="java-mark">' + escapeHtml(src.slice(r.index, r.index + r.length)) + '</mark>';
    pos = r.index + r.length;
  }
  html += escapeHtml(src.slice(pos));
  return html;
}

function numberedLines(text, startLine, ranges = [], offset = 0) {
  const lines = String(text ?? '').split('\n');
  return lines.map((line, i) => {
    const lineStart = lines.slice(0, i).reduce((n, l) => n + l.length + 1, 0);
    const local = ranges
      .filter((r) => r.index + r.length > lineStart && r.index < lineStart + line.length)
      .map((r) => ({
        index: Math.max(0, r.index - lineStart),
        length: Math.min(r.index + r.length, lineStart + line.length) - Math.max(r.index, lineStart),
      }));
    return `<div class="java-code-line"><span class="java-ln">${startLine + i}</span><span class="java-code">${highlight(line, local) || '&nbsp;'}</span></div>`;
  }).join('');
}

function splitTerms(query) {
  const matches = String(query).match(/"[^"]+"|\S+/g) || [];
  return matches.map((t) => t.replace(/^"|"$/g, '')).filter((t) => t.length >= 2);
}

function anchorOf(query) {
  const parts = String(query).match(/[\p{L}\p{N}_$]{3,}/gu) || [];
  parts.sort((a, b) => b.length - a.length);
  return parts[0] || '';
}

/** 解析文件名后缀过滤：支持 "*.java, *.xml" / ".java;xml" / ["java","xml"] */
function parseExtensions(input) {
  if (input == null) return [];
  const parts = Array.isArray(input)
    ? input
    : String(input).split(/[,;\s]+/);
  const out = [];
  for (const raw of parts) {
    let ext = String(raw ?? '').trim().toLowerCase();
    if (!ext) continue;
    if (ext.startsWith('*.')) ext = ext.slice(2);
    else if (ext.startsWith('.')) ext = ext.slice(1);
    // 仅允许常见后缀字符，避免 LIKE 通配符注入
    if (/^[a-z0-9_+~-]{1,16}$/.test(ext) && !out.includes(ext)) out.push(ext);
  }
  return out;
}

/** 生成后缀过滤 SQL 片段（按条目 path 的文件名后缀匹配） */
function extensionClause(exts, alias = 'e') {
  if (!exts.length) return { sql: '', params: [] };
  return {
    sql: ` AND (${exts.map((ext) => `LOWER(${alias}.path) LIKE LOWER(?)`).join(' OR ')})`,
    params: exts.map((ext) => `%.${ext}`),
  };
}

function buildSnippet(entry, fieldKey, needle, needles, contextLines, rawQuery, caseSensitive = false) {
  const text = String(entry[fieldKey] ?? '');
  const ranges = [];
  let at = findNeedle(text, needles, caseSensitive);
  if (at) ranges.push(at);
  else if (/\s/.test(rawQuery) && fieldKey === 'methodContent') {
    at = findCollapsed(text, needles, caseSensitive);
    if (at) ranges.push(at);
  }
  const hitLine = at ? text.slice(0, at.index).split('\n').length - 1 : 0;
  const lines = text.split('\n');
  if (fieldKey === 'methodContent') {
    const from = Math.max(0, hitLine - contextLines);
    const to = Math.min(lines.length - 1, hitLine + contextLines);
    const segment = lines.slice(from, to + 1).join('\n');
    const segmentStart = lines.slice(0, from).reduce((n, l) => n + l.length + 1, 0);
    const local = ranges.map((r) => ({ index: r.index - segmentStart, length: r.length }));
    return numberedLines(segment, entry.start_line + from, local);
  }
  const shown = text.length > 900 ? text.slice(0, 900) + '\n…（省略）' : text;
  const codeHead = entry.methodContent ? entry.methodContent.split('\n').slice(0, Math.max(3, contextLines)).join('\n') : '';
  return `<div class="java-comment-block">${highlight(shown, ranges)}</div>`
    + (codeHead ? `<div class="java-code-label">对应代码</div>` + numberedLines(codeHead, entry.start_line, []) : '');
}

function fetchCandidates({ sourceId, sourceIds, sourceKinds, sourceGroup, extensions, packageFilters, needle, fields, mode, caseSensitive }) {
  const params = [];
  let sql = `SELECT ${ALL_COLUMNS}, e.id, e.source_id, e.file_id, e.kind, e.path, e.start_line, e.end_line, s.name source_name, s.kind source_kind, s.group_name source_group_name
             FROM java_entries e JOIN java_sources s ON s.id = e.source_id WHERE 1=1`;
  if (sourceId) { sql += ' AND e.source_id = ?'; params.push(sourceId); }
  if (sourceIds?.length) {
    sql += ` AND e.source_id IN (${sourceIds.map(() => '?').join(',')})`;
    params.push(...sourceIds);
  }
  if (sourceKinds?.length) {
    sql += ` AND s.kind IN (${sourceKinds.map(() => '?').join(',')})`;
    params.push(...sourceKinds);
  }
  const grp = groupFilterClause(sourceGroup);
  if (grp.sql) { sql += grp.sql; params.push(...grp.params); }
  const ext = extensionClause(extensions);
  if (ext.sql) { sql += ext.sql; params.push(...ext.params); }
  const pkg = packageFilterClause(packageFilters, caseSensitive);
  if (pkg.sql) { sql += pkg.sql; params.push(...pkg.params); }
  if (mode !== 'regex' && needle && fields.length) {
    if (caseSensitive) {
      // instr 为二进制比较，不受 LIKE 大小写规则影响
      sql += ` AND (${fields.map((f) => `instr(e.${f.col}, ?) > 0`).join(' OR ')})`;
      fields.forEach(() => params.push(needle));
    } else {
      const esc = escapeLike(needle);
      sql += ` AND (${fields.map((f) => `LOWER(e.${f.col}) LIKE LOWER(?) ESCAPE '\\'`).join(' OR ')})`;
      fields.forEach(() => params.push(`%${esc}%`));
    }
  }
  sql += ` ORDER BY e.id DESC LIMIT ${MAX_SCAN}`;
  return asRows(db.prepare(sql), ...params);
}

/** 主检索入口：每次检索前对所有登记的文件夹执行增量扫描 */
export function search(params = {}) {
  const refresh = refreshScanRoots();
  const rawQuery = String(params.query ?? '');
  if (!rawQuery.trim()) return { hits: [], total: 0, scanned: 0, terms: [], refresh };
  const mode = ['fuzzy', 'regex', 'fulltext'].includes(params.mode) ? params.mode : 'fuzzy';
  const logic = params.logic === 'and' ? 'and' : 'or';
  const contextLines = Math.min(12, Math.max(0, Number(params.contextLines ?? 3) || 0));
  const limit = Math.min(200, Math.max(1, Number(params.limit ?? 50) || 50));
  const sourceId = params.sourceId ? String(params.sourceId) : '';
  const sourceGroup = params.sourceGroup ? String(params.sourceGroup) : '';
  const caseSensitive = params.caseSensitive === true || params.caseSensitive === 'true';
  const extensions = parseExtensions(params.fileExtensions ?? params.extensions);
  const packageFilters = parsePackageFilters(params.packageFilter ?? params.packages);
  // 按数据源过滤：显式传入 sourceIds 时只检索这些批次；空数组表示未选任何数据源
  const hasSourceFilter = Array.isArray(params.sourceIds);
  const sourceIds = hasSourceFilter
    ? params.sourceIds.filter(Boolean).map(String)
    : (sourceId ? [sourceId] : []);
  // 默认同时检索 JAR 索引与文件夹全文索引；前端可勾选来源类型
  const sourceKinds = Array.isArray(params.sourceKinds)
    ? params.sourceKinds.filter((k) => ['jar', 'folder'].includes(k))
    : ['jar', 'folder'];
  // 显式指定了来源但全部无效（例如全部取消勾选）时不应退化为“检索全部”
  if ((Array.isArray(params.sourceKinds) && !sourceKinds.length) || (hasSourceFilter && !sourceIds.length)) {
    return { hits: [], total: 0, scanned: 0, terms: [], mode, refresh };
  }
  let fields = Array.isArray(params.fields) && params.fields.length
    ? params.fields.map((k) => FIELD_BY_KEY[k]).filter(Boolean)
    : FIELDS;
  if (!fields.length) fields = FIELDS;

  // 代码段（多行、含括号/泛型/分号，或较长）按整段匹配：原样 → 压缩空白，不拆成单词
  const blockQuery = mode === 'fuzzy' && (
    /[\r\n]/.test(rawQuery) || /[(){}<>;=]/.test(rawQuery) || rawQuery.trim().length > 30
  );
  const terms = blockQuery ? [rawQuery.trim()] : splitTerms(rawQuery);
  if (!terms.length) return { hits: [], total: 0, scanned: 0, terms: [], refresh };

  let regex = null;
  if (mode === 'regex') {
    if (rawQuery.length > 2000) throw new Error('正则表达式过长（上限 2000 字符）');
    try { regex = new RegExp(rawQuery, caseSensitive ? 'g' : 'gi'); }
    catch (e) { throw new Error('正则表达式无效：' + e.message); }
  }

  let candidates;
  if (mode === 'fulltext') {
    const colNames = fields.map((f) => f.col);
    const matchExpr = terms.map((t) => colNames.map((c) => `${c} : "${t.replace(/"/g, '""')}"*`).join(' OR ')).join(' OR ');
    let sql = `SELECT ${ALL_COLUMNS}, e.id, e.source_id, e.file_id, e.kind, e.path, e.start_line, e.end_line, s.name source_name, s.kind source_kind, s.group_name source_group_name
               FROM java_entries e JOIN java_sources s ON s.id = e.source_id
               WHERE e.id IN (SELECT rowid FROM java_fts WHERE java_fts MATCH ?)`;
    const args = [matchExpr];
    if (sourceId) { sql += ' AND e.source_id = ?'; args.push(sourceId); }
    if (sourceIds.length) {
      sql += ` AND e.source_id IN (${sourceIds.map(() => '?').join(',')})`;
      args.push(...sourceIds);
    }
    if (sourceKinds.length) {
      sql += ` AND s.kind IN (${sourceKinds.map(() => '?').join(',')})`;
      args.push(...sourceKinds);
    }
    const grp = groupFilterClause(sourceGroup);
    if (grp.sql) { sql += grp.sql; args.push(...grp.params); }
    const ext = extensionClause(extensions);
    if (ext.sql) { sql += ext.sql; args.push(...ext.params); }
    const pkg = packageFilterClause(packageFilters, caseSensitive);
    if (pkg.sql) { sql += pkg.sql; args.push(...pkg.params); }
    sql += ` ORDER BY e.id DESC LIMIT ${MAX_SCAN}`;
    try { candidates = asRows(db.prepare(sql), ...args); }
    catch (e) { throw new Error('全文检索语法错误：' + e.message); }
    // unicode61 对中文分词不友好：FTS 无结果时回退到 LIKE 模糊匹配
    if (!candidates.length) {
      candidates = fetchCandidates({
        sourceId, sourceIds, sourceKinds, sourceGroup, extensions, packageFilters,
        needle: anchorOf(terms.join(' ')), fields, mode: 'fuzzy', caseSensitive,
      });
    }
  } else {
    candidates = fetchCandidates({
      sourceId, sourceIds, sourceKinds, sourceGroup, extensions, packageFilters,
      needle: anchorOf(blockQuery ? terms[0] : terms.join(' ')), fields, mode, caseSensitive,
    });
  }

  // FTS / LIKE 返回的是 snake_case 行，统一补一份驼峰字段供高亮与摘要使用
  candidates = candidates.map((row) => {
    const out = { ...row };
    for (const f of FIELDS) out[f.key] = row[f.col];
    return out;
  });

  const hits = [];
  for (const entry of candidates) {
    const matched = [];
    let primaryKey = '';
    let primaryAt = null;
    for (const f of fields) {
      const value = String(entry[f.col] ?? '');
      let ok = false;
      let location = null;
      if (mode === 'regex') {
        regex.lastIndex = 0;
        const m = regex.exec(value);
        ok = Boolean(m);
        if (ok) location = { index: m.index, length: m[0].length };
      } else if (mode === 'fulltext') {
        location = findNeedle(value, terms, caseSensitive);
        ok = Boolean(location);
      } else if (blockQuery) {
        location = findNeedle(value, terms, caseSensitive) || findCollapsed(value, terms, caseSensitive);
        ok = Boolean(location);
      } else {
        location = findNeedle(value, terms, caseSensitive);
        ok = Boolean(location);
      }
      if (ok) {
        matched.push({
          key: f.key,
          label: f.key === 'methodContent' && entry.kind === 'text' ? '文件内容' : f.label,
        });
        if (!primaryKey || f.key === 'methodContent' || f.key === 'methodComment') {
          if (!primaryKey || f.key === 'methodContent') { primaryKey = f.key; primaryAt = location; }
        }
      }
    }
    // AND：每个关键词至少要命中一个字段
    if (logic === 'and' && mode !== 'regex' && !blockQuery) {
      const allText = caseSensitive
        ? fields.map((f) => String(entry[f.col] ?? '')).join('\n')
        : fields.map((f) => String(entry[f.col] ?? '')).join('\n').toLowerCase();
      if (!terms.every((t) => allText.includes(caseSensitive ? t : t.toLowerCase()))) continue;
    }
    if (mode === 'regex') {
      const allText = fields.map((f) => String(entry[f.col] ?? '')).join('\n');
      regex.lastIndex = 0;
      if (!regex.test(allText)) continue;
    }
    if (!matched.length) continue;
    const needleText = primaryAt ? String(entry[primaryKey] ?? '').slice(primaryAt.index, primaryAt.index + primaryAt.length) : terms[0];
    hits.push({
      id: entry.id,
      sourceId: entry.source_id,
      sourceName: entry.source_name,
      sourceKind: entry.source_kind,
      sourceGroup: entry.source_group_name || '',
      fileId: entry.file_id,
      kind: entry.kind,
      path: entry.path,
      packageName: entry.package_name,
      className: entry.class_name,
      fieldName: entry.field_name,
      methodName: entry.method_name,
      signature: entry.signature,
      startLine: entry.start_line,
      endLine: entry.end_line,
      matchedFields: matched,
      snippetHtml: buildSnippet(entry, primaryKey, needleText, primaryAt ? [needleText] : terms, contextLines, rawQuery, caseSensitive),
      methodPreview: entry.method_content.length > 1600 ? entry.method_content.slice(0, 1600) + '\n…（省略）' : entry.method_content,
    });
    if (hits.length >= limit) break;
  }
  return {
    hits, total: hits.length, scanned: candidates.length, terms, mode, blockQuery, refresh,
    filters: { caseSensitive, extensions, packageFilters, sourceIds, sourceKinds, sourceGroup },
  };
}

/** 命中详情：前 / 中 / 后代码 + 同文件相邻方法 */
export function getEntry(id, contextLines = 6, query = '', caseSensitive = false) {
  const entry = db.prepare(`SELECT e.*, s.name source_name FROM java_entries e
    JOIN java_sources s ON s.id = e.source_id WHERE e.id = ?`).get(id);
  if (!entry) throw new Error('未找到该代码条目');
  const file = db.prepare('SELECT * FROM java_files WHERE id = ?').get(entry.file_id);
  const lines = String(file?.content ?? '').split('\n');
  const ctx = Math.min(20, Math.max(0, Number(contextLines) || 6));
  const beforeFrom = Math.max(0, entry.start_line - 1 - ctx);
  const beforeText = lines.slice(beforeFrom, entry.start_line - 1).join('\n');
  const hitText = lines.slice(entry.start_line - 1, Math.min(lines.length, entry.end_line)).join('\n');
  const afterText = lines.slice(entry.end_line, Math.min(lines.length, entry.end_line + ctx)).join('\n');

  // 命中段内高亮：与检索框输入保持一致（代码段按整段匹配）
  const hitRanges = [];
  const rawQuery = String(query ?? '').trim();
  if (rawQuery) {
    const parts = /[\r\n]|[(){}<>;=]/.test(rawQuery) ? [rawQuery] : splitTerms(rawQuery);
    for (const part of parts) {
      const at = findNeedle(hitText, [part], caseSensitive) || findCollapsed(hitText, [part], caseSensitive);
      if (at) hitRanges.push(at);
    }
  }

  const neighbours = asRows(db.prepare(`SELECT id, method_name, field_name, class_name, signature, start_line, end_line
    FROM java_entries WHERE file_id = ? AND kind IN ('method','constructor','field','text') AND id <> ?
      AND (start_line < ? OR start_line > ?)
    ORDER BY start_line`), entry.file_id, id, entry.start_line, entry.end_line);
  const prev = neighbours.filter((n) => n.start_line < entry.start_line).pop() || null;
  const next = neighbours.find((n) => n.start_line > entry.end_line) || null;
  const mini = (n) => n ? {
    id: n.id,
    methodName: n.method_name || n.field_name || n.signature || `L${n.start_line}`,
    className: n.class_name,
    signature: n.signature,
    startLine: n.start_line,
    endLine: n.end_line,
    preview: lines.slice(n.start_line - 1, Math.min(lines.length, n.start_line + 6)).join('\n'),
  } : null;

  return {
    entry: {
      id: entry.id,
      sourceId: entry.source_id,
      sourceName: entry.source_name,
      fileId: entry.file_id,
      kind: entry.kind,
      path: entry.path,
      packageName: entry.package_name,
      className: entry.class_name,
      fieldName: entry.field_name,
      methodName: entry.method_name,
      signature: entry.signature,
      methodContent: entry.method_content,
      packageComment: entry.package_comment,
      classComment: entry.class_comment,
      methodComment: entry.method_comment,
      fieldComment: entry.field_comment,
      startLine: entry.start_line,
      endLine: entry.end_line,
    },
    fileName: file?.file_name || '',
    path: file?.path || '',
    before: { text: beforeText, html: numberedLines(beforeText, beforeFrom + 1) },
    hit: { text: hitText, html: numberedLines(hitText, entry.start_line, hitRanges) },
    after: { text: afterText, html: numberedLines(afterText, entry.end_line + 1) },
    prevMethod: mini(prev),
    nextMethod: mini(next),
  };
}

export function sourceFiles(sourceId) {
  return asRows(db.prepare(`SELECT id, path, file_name, package_name, LENGTH(content) bytes
    FROM java_files WHERE source_id = ? ORDER BY path`), sourceId);
}

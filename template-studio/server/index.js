import express from 'express';
import cors from 'cors';
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { extract, render, TYPES } from './engine.js';
import { importTable } from './importer.js';
import { TRANSFORM_CATALOG, TRANSFORM_GROUPS } from '../shared/transforms.js';
import { buildSql } from '../shared/sqlbuilder.js';
import { APP_DB_DIALECT } from '../shared/dbmeta.js';
import { buildInsertSql } from '../shared/mapping.js';
import * as dbmeta from './dbmeta.js';
import * as mappings from './mapping.js';
import * as presets from './preset.js';
import * as javaIndex from './java-store.js';
import {
  listTemplates,
  getTemplate,
  saveTemplate,
  deleteTemplate,
} from './store.js';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const PORT = Number(process.env.PORT || 3001);
const app = express();

app.use(cors());
// 変数値ファイル（base64）と列数無制限の DB メタデータ JSON を受け取るため余裕を持たせる。
// 20000 列超のメタデータを保存 / SQL 生成に渡しても 413 にならないよう上限を大きく取る。
app.use(express.json({ limit: '256mb' }));

const wrap = (fn) => (req, res) => {
  try {
    const data = fn(req);
    res.json({ ok: true, ...data });
  } catch (err) {
    res.status(400).json({ ok: false, error: err.message || String(err) });
  }
};

const wrapAsync = (fn) => (req, res) => {
  Promise.resolve()
    .then(() => fn(req))
    .then((data) => res.json({ ok: true, ...data }))
    .catch((err) => res.status(400).json({ ok: false, error: err.message || String(err) }));
};

app.get('/api/health', (_req, res) => {
  res.json({ ok: true, service: 'template-studio', types: TYPES, transforms: TRANSFORM_CATALOG.length });
});

app.get('/api/types', (_req, res) => res.json({ ok: true, types: TYPES }));

app.get('/api/transforms', (_req, res) => res.json({
  ok: true,
  transforms: TRANSFORM_CATALOG,
  groups: TRANSFORM_GROUPS,
}));

app.post('/api/extract', wrap((req) => {
  const template = req.body?.template;
  if (typeof template !== 'string') throw new Error('template は文字列で指定してください');
  return extract(template);
}));

app.post('/api/render', wrap((req) => {
  const template = req.body?.template;
  const values = req.body?.values ?? {};
  const transforms = req.body?.transforms ?? {};
  const expansions = req.body?.expansions ?? {};
  const options = req.body?.options ?? {};
  if (typeof template !== 'string') throw new Error('template は文字列で指定してください');
  if (!values || typeof values !== 'object' || Array.isArray(values)) {
    throw new Error('values はオブジェクトで指定してください');
  }
  if (!transforms || typeof transforms !== 'object' || Array.isArray(transforms)) {
    throw new Error('transforms はオブジェクトで指定してください');
  }
  if (!expansions || typeof expansions !== 'object' || Array.isArray(expansions)) {
    throw new Error('expansions はオブジェクトで指定してください');
  }
  if (!options || typeof options !== 'object' || Array.isArray(options)) {
    throw new Error('options はオブジェクトで指定してください');
  }
  return render(template, values, transforms, expansions, options);
}));

/** 変数値ファイル → 行データ（.xlsx / .csv / .tsv / .txt / .json / .md） */
app.post('/api/import', wrap((req) => importTable({
  filename: req.body?.filename,
  data: req.body?.data,
  sheet: req.body?.sheet,
})));

app.get('/api/templates', wrap(() => ({ templates: listTemplates() })));

app.get('/api/templates/:id', wrap((req) => {
  const tpl = getTemplate(req.params.id);
  if (!tpl) {
    return { found: false, template: null };
  }
  return { found: true, template: tpl };
}));

app.post('/api/templates', wrap((req) => {
  const name = String(req.body?.name || '').trim();
  const content = req.body?.content;
  if (!name) throw new Error('テンプレート名は必須です');
  if (typeof content !== 'string') throw new Error('content は文字列で指定してください');
  return { template: saveTemplate(name, content) };
}));

app.delete('/api/templates/:id', wrap((req) => {
  const deleted = deleteTemplate(req.params.id);
  if (!deleted) throw new Error('指定されたテンプレートが見つかりません');
  return { deleted: true };
}));

/* ---------------- DB メタデータ / SQL 生成（PostgreSQL 固定） ---------------- */
const onlyPostgres = (conn) => {
  const requested = conn?.dialect;
  if (requested && requested !== APP_DB_DIALECT) {
    throw new Error(`このアプリは PostgreSQL 専用です（指定された DB 種別: ${requested}）`);
  }
  return { ...(conn || {}), dialect: APP_DB_DIALECT };
};

app.get('/api/db/drivers', wrap(() => ({
  drivers: dbmeta.driverStatus().filter((d) => d.id === APP_DB_DIALECT),
  dialect: APP_DB_DIALECT,
})));

app.post('/api/db/test', wrapAsync((req) =>
  dbmeta.testConnection(onlyPostgres(req.body?.connection))));

app.post('/api/db/extract', wrapAsync(async (req) => {
  const conn = onlyPostgres(req.body?.connection);
  const { metadata, version } = await dbmeta.extractMetadata(conn, { name: req.body?.name });
  let saved = null;
  if (req.body?.save !== false) {
    saved = dbmeta.saveMetadata(metadata, req.body?.id);
    metadata.id = saved.id;
  }
  return { metadata, version, saved: saved?.info || null };
}));

app.post('/api/db/sql', wrap((req) => {
  const metadata = req.body?.metadata;
  const kind = String(req.body?.kind || 'join');
  const config = req.body?.config || {};
  if (!metadata || typeof metadata !== 'object') throw new Error('metadata はオブジェクトで指定してください');
  return { result: buildSql(metadata, kind, config) };
}));

app.get('/api/db/metadata', wrap(() => ({ items: dbmeta.listMetadata() })));

app.get('/api/db/metadata/:id', wrap((req) => {
  const metadata = dbmeta.getMetadata(req.params.id);
  if (!metadata) throw new Error('指定されたメタデータが見つかりません');
  return { metadata };
}));

app.post('/api/db/metadata', wrap((req) => {
  const raw = req.body?.metadata ?? req.body;
  const r = dbmeta.saveMetadata(raw, req.body?.id);
  return { id: r.id, metadata: r.metadata, info: r.info };
}));

app.delete('/api/db/metadata/:id', wrap((req) => {
  if (!dbmeta.deleteMetadata(req.params.id)) throw new Error('指定されたメタデータが見つかりません');
  return { deleted: true };
}));

/** テーブル選択用の軽量一覧（列名 / 型 / コメントのみ） */
app.get('/api/db/metadata/:id/tables', wrap((req) => {
  const r = dbmeta.tableSummaries(req.params.id);
  if (!r) throw new Error('指定されたメタデータが見つかりません');
  return { tables: r.tables, name: r.name, dialect: r.dialect };
}));

/* ---------------- Excel → 列マッピング → INSERT SQL ---------------- */
// 1 テーブル = 1 JSON。items = 個別ファイル、groups = 保存名ごとのまとまり。
app.get('/api/mappings', wrap(() => ({
  items: mappings.listMappings(),
  groups: mappings.listMappingGroups(),
})));

// 保存名を指定して、その保存で作られた全テーブルの JSON を 1 つのマッピングに復元
app.get('/api/mappings/group/:name', wrap((req) => {
  const r = mappings.getGroupMapping(req.params.name);
  if (!r) throw new Error('指定されたマッピングが見つかりません');
  return { mapping: r.mapping, ids: r.ids };
}));

app.delete('/api/mappings/group/:name', wrap((req) => {
  const n = mappings.deleteMappingGroup(req.params.name);
  if (!n) throw new Error('指定されたマッピングが見つかりません');
  return { deleted: n };
}));

app.get('/api/mappings/:id', wrap((req) => {
  const mapping = mappings.getMapping(req.params.id);
  if (!mapping) throw new Error('指定されたマッピングが見つかりません');
  return { mapping };
}));

app.post('/api/mappings', wrap((req) => {
  const r = mappings.saveMapping(req.body?.mapping ?? req.body, req.body?.id, req.body?.replaceGroup);
  return {
    id: r.id, ids: r.ids, groupName: r.groupName, files: r.files,
    items: r.items, deleted: r.deleted, mapping: r.mapping, info: r.info,
  };
}));

app.delete('/api/mappings/:id', wrap((req) => {
  if (!mappings.deleteMapping(req.params.id)) throw new Error('指定されたマッピングが見つかりません');
  return { deleted: true };
}));

app.post('/api/mapping/insert', wrap((req) => ({
  result: buildInsertSql({
    tables: req.body?.tables,
    table: req.body?.table,
    pairs: req.body?.pairs,
    rows: req.body?.rows,
    mode: req.body?.mode,
    options: req.body?.options,
  }),
})));

/* ---------------- 固定値プリセット（列 = 固定値） ---------------- */
app.get('/api/presets', wrap(() => ({ items: presets.listPresets() })));

app.get('/api/presets/:id', wrap((req) => {
  const preset = presets.getPreset(req.params.id);
  if (!preset) throw new Error('固定値プリセットが見つかりません');
  return { preset };
}));

app.post('/api/presets', wrap((req) => {
  const r = presets.savePreset(req.body?.preset ?? req.body, req.body?.id);
  return { id: r.info.id, preset: r.preset, info: r.info };
}));

app.delete('/api/presets/:id', wrap((req) => {
  if (!presets.deletePreset(req.params.id)) throw new Error('固定値プリセットが見つかりません');
  return { deleted: true };
}));

/* ---------------- Java 代码索引 / 全文检索 ---------------- */
app.get('/api/java/sources', wrap(() => ({
  sources: javaIndex.listSources(),
  stats: javaIndex.stats(),
  roots: javaIndex.listRoots(),
  groups: javaIndex.listGroups(),
})));

app.get('/api/java/files', wrap((req) => ({
  files: javaIndex.sourceFiles(String(req.query.sourceId || '')),
})));

app.get('/api/java/files/:fileId/content', wrap((req) => javaIndex.getFileContent(req.params.fileId)));

app.put('/api/java/sources/:id/group', wrap((req) => ({
  updated: javaIndex.setSourceGroup(req.params.id, req.body?.group),
})));

app.put('/api/java/sources/:id/name', wrap((req) => ({
  updated: javaIndex.setSourceName(req.params.id, req.body?.name),
})));

app.put('/api/java/groups/rename', wrap((req) => ({
  updated: javaIndex.renameGroup(req.body?.from, req.body?.to),
})));

app.delete('/api/java/sources/:id', wrap((req) => ({
  deleted: javaIndex.deleteSource(req.params.id),
})));

// webkitdirectory 读取的源码文本：{ name, files:[{path, content}] }
app.post('/api/java/scan/folder', wrap((req) => javaIndex.scanFolder(
  req.body?.name, req.body?.files, req.body?.note, req.body?.group,
)));

// JAR / ZIP / sources.jar：浏览器读取为 base64 后提交
app.post('/api/java/scan/jar', wrap((req) => javaIndex.scanJar(
  req.body?.name, req.body?.base64, req.body?.note, req.body?.group,
)));

// 服务器本地目录：Web 端填写绝对路径；首次全量，之后由每次检索触发增量扫描
app.post('/api/java/scan/path', wrap((req) => javaIndex.scanPath(
  req.body?.path, req.body?.name, req.body?.note, req.body?.group,
)));

app.post('/api/java/search', wrap((req) => javaIndex.search(req.body || {})));

app.get('/api/java/entries/:id', wrap((req) => javaIndex.getEntry(
  req.params.id, Number(req.query.context || 6), String(req.query.q || ''),
  req.query.cs === '1' || req.query.cs === 'true',
)));

// 未定義の API は SPA の index.html を返さず、JSON 404 を返す。
// こうすると旧クライアント/旧サーバーが混在しても res.json() が失敗せず、
// フロント側で個別にエラー扱いできる（メタデータ一覧が空になる事故を防ぐ）。
app.use('/api', (_req, res) => res.status(404).json({ ok: false, error: 'API endpoint not found' }));

const dist = path.join(__dirname, '..', 'dist');
if (fs.existsSync(dist)) {
  app.use(express.static(dist));
  app.get('*', (_req, res) => res.sendFile(path.join(dist, 'index.html')));
}

app.use((err, _req, res, _next) => {
  res.status(500).json({ ok: false, error: err.message || 'internal error' });
});

app.listen(PORT, () => {
  console.log(`[template-studio] API  http://localhost:${PORT}/api/health`);
  if (fs.existsSync(dist)) {
    console.log(`[template-studio] WEB http://localhost:${PORT}/  (built dist)`);
  }
});

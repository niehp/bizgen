#!/usr/bin/env node
/**
 * 保存済みマッピング JSON + Excel ファイル → PostgreSQL INSERT SQL
 *
 * Windows コマンドラインからは ExcelToSql.bat 経由で実行する:
 *   ExcelToSql.bat --mapping <保存名|ファイルID|JSONパス> --excel <Excelパス> [--out <ファイル名>]
 *
 * マッピングは「1 テーブル = 1 JSON」（data/mappings/<保存名>__<表名>.json）。
 * --mapping に保存名を渡すとその保存の全テーブルを、ファイル ID を渡すと 1 テーブルだけを生成する。
 * ブラウザ画面と同じ shared/mapping.js / shared/dbmeta.js / server/importer.js を
 * そのまま使うため、表ヘッダー判定・列型変換・既定値・開始行の挙動は完全一致する。
 * SQL 本文は標準出力（--out 指定時はファイル）、進捗と警告は標準エラーへ出力する。
 */
import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import {
  normalizeMapping, deriveSheet, rowsForInsert, buildInsertSql,
} from '../shared/mapping.js';
import { normalizeMetadata } from '../shared/dbmeta.js';
import { mergePresetIntoDefaults } from '../shared/preset.js';
import { resolvePresetEntries, listPresetNames } from '../server/preset.js';
import { importTable } from '../server/importer.js';
import {
  sqlFileContents, defaultSqlFileName, normalizeSqlFileName,
} from '../shared/sqlfile.js';

const ROOT = path.join(path.dirname(fileURLToPath(import.meta.url)), '..');
const MAP_DIR = path.join(ROOT, 'data', 'mappings');
const META_DIR = path.join(ROOT, 'data', 'db-metadata');

const text = (v) => (v === undefined || v === null ? '' : String(v));
const num = (v, fallback) => {
  const n = Number(v);
  return Number.isFinite(n) ? n : fallback;
};

const USAGE = `
使い方:
  ExcelToSql.bat --mapping <保存名|ファイルID|JSONパス> --excel <Excelパス> [オプション]
  node cli/ExcelToSql.mjs --mapping <保存名|ファイルID|JSONパス> --excel <Excelパス> [オプション]

必須:
  --mapping <name|ID|path>
                        保存名（= その保存の全テーブル JSON をまとめて使用）、
                        ファイル ID（data/mappings/<ID>.json = 1 テーブルだけ）、
                        または任意のマッピング JSON ファイルパス
  --excel   <path>      xlsx / xlsm / csv / tsv / txt / json / md ファイル
                        （位置引数でも可: --mapping ID --excel file.csv）

任意:
  --group <name>        保存名を明示指定（--mapping より優先。全テーブルを生成）
  --out <name>          結果を .sql ファイルへ保存（拡張子 .sql は自動付与）。未指定は標準出力
  --metadata <ID|path>  メタデータ JSON を明示指定（マッピングの metadataId を上書き）
  --preset <名前|ID|path> 固定値プリセットを適用（列=固定値を既定値へマージ。複数回指定可）
  --sheet <name>        Excel のシート名（マッピングの sheet を上書き）
  --start-row <n>       INSERT に使うデータ行の開始行（1 起点、既定はマッピングの設定）
  --limit <n>           出力行数（0 = 全件、既定はマッピングの設定）
  --skip-rows <n>       先頭から除外する行数（既定はマッピングの設定）
  --header auto|on|off  1 行目の扱い（既定はマッピングの設定）
  --batch each|multi    1 行 1 INSERT / 複数 VALUES を 1 INSERT（既定はマッピングの設定）
  --template            実データの代わりに /*camelCase*/ 雛形を出力
  --no-schema           スキーマ名を付けない
  --quote               識別子を "..." で引用
  --list                保存済みマッピング一覧を表示
  --list-presets        保存済み固定値プリセット一覧を表示
  --help                このヘルプを表示

例:
  ExcelToSql.bat --list
  ExcelToSql.bat --list-presets
  ExcelToSql.bat --mapping "受注取込" --excel C:\\data\\order.xlsx --out order_insert
  ExcelToSql.bat --mapping "受注取込__public.t_order" --excel C:\\data\\order.xlsx --start-row 3 > order_only.sql
  ExcelToSql.bat --mapping "受注取込" --excel C:\\data\\order.xlsx --preset "共通監査列" > all.sql
`;

function parseArgs(argv) {
  const out = { _: [] };
  const flags = new Set(['help', 'list', 'list-presets', 'template', 'no-schema', 'quote']);
  for (let i = 0; i < argv.length; i += 1) {
    const arg = argv[i];
    if (arg.startsWith('--')) {
      const eq = arg.indexOf('=');
      const key = eq >= 0 ? arg.slice(2, eq) : arg.slice(2);
      if (!key) continue;
      if (flags.has(key)) { out[key] = true; continue; }
      const val = eq >= 0 ? arg.slice(eq + 1) : argv[++i];
      if (val === undefined) throw new Error(`--${key} の値がありません`);
      // --preset は複数回指定可（後のものほど優先）
      if (key === 'preset') {
        if (!Array.isArray(out.preset)) out.preset = [];
        out.preset.push(val);
      } else {
        out[key] = val;
      }
    } else {
      out._.push(arg);
    }
  }
  return out;
}

/** 相対パス / ID / ストア内 ID を解決する */
function resolveFile(arg, dir, label) {
  const direct = path.resolve(process.cwd(), arg);
  if (fs.existsSync(direct) && fs.statSync(direct).isFile()) return direct;
  const withExt = /\.json$/i.test(arg) ? arg : `${arg}.json`;
  const inStore = path.join(dir, withExt);
  if (fs.existsSync(inStore) && fs.statSync(inStore).isFile()) return inStore;
  throw new Error(`${label} が見つかりません: ${arg}\n  確認先: ${direct} / ${inStore}`);
}

function readJson(p, label) {
  try { return JSON.parse(fs.readFileSync(p, 'utf8')); }
  catch (e) { throw new Error(`${label} の JSON を読み込めません: ${e.message}`); }
}

/** data/mappings 内の全 JSON（1 ファイル = 1 テーブル）を読む */
function storeEntries() {
  if (!fs.existsSync(MAP_DIR)) return [];
  return fs.readdirSync(MAP_DIR)
    .filter((f) => f.endsWith('.json'))
    .map((f) => {
      try {
        const p = path.join(MAP_DIR, f);
        const m = normalizeMapping(JSON.parse(fs.readFileSync(p, 'utf8')));
        return { id: f.slice(0, -5), file: f, path: p, mapping: m, group: m.groupName || m.name || f.slice(0, -5) };
      } catch {
        return null;
      }
    })
    .filter(Boolean);
}

function listMappings() {
  const entries = storeEntries();
  if (!entries.length) { console.log('（保存済みマッピングはありません）'); return; }
  const groups = new Map();
  for (const e of entries) {
    if (!groups.has(e.group)) groups.set(e.group, []);
    groups.get(e.group).push(e);
  }
  console.log(`保存済みマッピング（1 テーブル = 1 JSON）: ${MAP_DIR}`);
  for (const [name, list] of groups) {
    list.sort((a, b) => (a.mapping.tableIndex || 999) - (b.mapping.tableIndex || 999)
      || a.id.localeCompare(b.id));
    console.log(`  [保存名] ${name}  (${list.length} ファイル)`);
    for (const e of list) {
      const t = e.mapping.tables[0] || {};
      const pairs = (t.pairs || []).filter((p) => p.target).length;
      const defaults = (t.defaults || []).filter((d) => text(d.value).trim()).length;
      console.log(`      ${e.id}.json  →  ${t.tableId || e.mapping.tableId}  (${pairs} 列 / 既定値 ${defaults})`);
    }
  }
  console.log('  使い方: --mapping "保存名" で全テーブル / --mapping "ファイルID" で 1 テーブル / --group "保存名"');
}

/** 保存名に属する複数 JSON を 1 つのマッピング（tables[]）へ復元 */
function mergeEntries(entries, label) {
  const list = [...entries].sort((a, b) => (a.mapping.tableIndex || 999) - (b.mapping.tableIndex || 999)
    || a.id.localeCompare(b.id));
  const tables = [];
  const seen = new Set();
  for (const e of list) {
    for (const t of e.mapping.tables) {
      const key = t.tableId || `table_${tables.length + 1}`;
      if (seen.has(key)) continue;
      seen.add(key);
      tables.push(t);
    }
  }
  const first = list[0].mapping;
  return {
    mapping: normalizeMapping({ ...first, id: label, name: first.name || label, groupName: label, tables }),
    paths: list.map((e) => e.path),
    ids: list.map((e) => e.id),
    multiple: list.length > 1,
  };
}

/**
 * --mapping の解決順:
 *   1. 明示した JSON ファイルパス（旧形式の複数表 JSON もそのまま可）
 *   2. data/mappings/<ID>.json（1 テーブルだけ）
 *   3. 保存名（同じ保存名の全 JSON をまとめて使用）
 * --group 指定時は常に保存名として解決する。
 */
function loadMapping(ref, groupRef) {
  const entries = storeEntries();
  if (groupRef) {
    const hit = entries.filter((e) => e.group === groupRef);
    if (!hit.length) throw new Error(`保存名「${groupRef}」のマッピング JSON が見つかりません（--list で確認できます）`);
    return mergeEntries(hit, groupRef);
  }
  const direct = path.resolve(process.cwd(), ref);
  if (fs.existsSync(direct) && fs.statSync(direct).isFile()) {
    return {
      mapping: normalizeMapping(readJson(direct, 'マッピング')),
      paths: [direct], ids: [path.basename(direct, '.json')], multiple: false,
    };
  }
  const wanted = ref.replace(/\.json$/i, '');
  const byId = entries.find((e) => e.id === wanted);
  if (byId) {
    return { mapping: byId.mapping, paths: [byId.path], ids: [byId.id], multiple: false };
  }
  const byName = entries.filter((e) => e.group === ref);
  if (byName.length) return mergeEntries(byName, ref);
  throw new Error(`マッピングが見つかりません: ${ref}\n  確認先: ${direct} / ${path.join(MAP_DIR, `${wanted}.json`)}\n  --list で保存名とファイル ID を確認できます`);
}

function loadMetadata(mapping, override) {
  if (override) {
    const p = resolveFile(override, META_DIR, 'メタデータ');
    return { metadata: normalizeMetadata(readJson(p, 'メタデータ')), source: p };
  }
  const id = text(mapping.metadataId);
  if (!id) {
    throw new Error('マッピングに metadataId がありません。--metadata <ID|JSONパス> で保存済みメタデータを指定してください（内蔵サンプルは廃止されました）');
  }
  const p = resolveFile(id, META_DIR, 'メタデータ');
  return { metadata: normalizeMetadata(readJson(p, 'メタデータ')), source: p };
}

function main(argv) {
  const args = parseArgs(argv);
  if (args.help) { console.log(USAGE.trim()); return 0; }
  if (args.list) { listMappings(); return 0; }
  if (args['list-presets']) {
    const names = listPresetNames();
    console.log(names.length ? names.join('\n') : '（保存済みの固定値プリセットはありません）');
    return 0;
  }

  const groupRef = text(args.group);
  const mappingRef = args.mapping || args._[0] || groupRef;
  const excelRef = args.excel || args._[1];
  if (!mappingRef || !excelRef) {
    console.error('[ERROR] --mapping（または --group）と --excel を指定してください（--help で使い方を表示）');
    return 1;
  }

  const loaded = loadMapping(mappingRef, groupRef);
  const mapping = loaded.mapping;
  if (!mapping.tables.length) throw new Error('マッピングに対象テーブルがありません');

  const { metadata, source: metaSource } = loadMetadata(mapping, args.metadata);
  const byId = new Map(metadata.tables.map((t) => [t.id, t]));

  // 固定値プリセットを全テーブルの既定値へマージ（--preset は複数回指定可、後勝ち）
  let presetEntries = [];
  const presetRefs = Array.isArray(args.preset) ? args.preset : (args.preset ? [args.preset] : []);
  for (const ref of presetRefs) {
    const r = resolvePresetEntries(ref);
    presetEntries = mergePresetIntoDefaults(presetEntries, r.entries);
    console.error(`[PRESET] ${r.source}: ${r.entries.length} 列を適用`);
  }

  const buildTables = mapping.tables.map((t) => ({
    tableId: t.tableId,
    table: byId.get(t.tableId) || null,
    pairs: t.pairs,
    defaults: presetEntries.length
      ? mergePresetIntoDefaults(t.defaults, presetEntries)
      : t.defaults,
  }));
  const missing = buildTables.filter((t) => !t.table).map((t) => t.tableId);
  if (missing.length) {
    console.error(`[WARN] メタデータに存在しないテーブル: ${missing.join(', ')}（--metadata で別の JSON を指定できます）`);
  }

  const excelPath = path.resolve(process.cwd(), excelRef);
  if (!fs.existsSync(excelPath) || !fs.statSync(excelPath).isFile()) {
    throw new Error(`Excel ファイルが見つかりません: ${excelPath}`);
  }
  const data = fs.readFileSync(excelPath).toString('base64');
  const sheet = text(args.sheet ?? mapping.sheet);
  const file = importTable({ filename: path.basename(excelPath), data, sheet: sheet || undefined });

  const options = { ...mapping.options };
  if (args['start-row'] !== undefined) options.startRow = Math.max(1, Math.floor(num(args['start-row'], 1)));
  if (args.limit !== undefined) options.rowLimit = Math.max(0, Math.floor(num(args.limit, options.rowLimit)));
  if (args.batch) options.batch = args.batch === 'multi' ? 'multi' : 'each';
  if (args.template) options.mode = 'template';
  if (args['no-schema']) options.includeSchema = false;
  if (args.quote) options.quoteIdentifiers = true;

  const headerMode = ['auto', 'on', 'off'].includes(args.header) ? args.header : mapping.headerMode;
  const skipRows = args['skip-rows'] !== undefined
    ? Math.max(0, Math.floor(num(args['skip-rows'], mapping.skipRows)))
    : mapping.skipRows;
  const parsed = deriveSheet(file, { headerMode, skipRows });
  const rows = rowsForInsert(parsed.dataRows, options);
  const result = buildInsertSql({
    tables: buildTables, rows, mode: options.mode, options,
  });

  let outPath = '';
  if (args.out) {
    const outDir = path.dirname(path.resolve(process.cwd(), args.out));
    fs.mkdirSync(outDir, { recursive: true });
    const fallback = defaultSqlFileName('insert', buildTables[0]?.tableId || 'multi');
    const outBase = normalizeSqlFileName(path.basename(args.out), fallback);
    outPath = path.join(outDir, outBase);
    fs.writeFileSync(outPath, sqlFileContents(result.sql), 'utf8');
  } else {
    process.stdout.write(sqlFileContents(result.sql));
  }

  const log = (s) => process.stderr.write(`${s}\n`);
  log(`[OK] マッピング : ${mapping.name || mappingRef}（${loaded.multiple ? `保存名 / ${loaded.ids.length} JSON` : '1 JSON'}）`);
  for (const p of loaded.paths) log(`     JSON       : ${p}`);
  log(`     メタデータ : ${metaSource}（${metadata.tables.length} テーブル）`);
  log(`     Excel      : ${excelPath}${sheet ? ` [シート: ${file.sheet || sheet}]` : ''}（${parsed.dataRows.length} データ行 / ${parsed.width} 列）`);
  log(`     出力       : ${options.mode === 'template' ? '雛形' : `開始 ${text(options.startRow)} 行目 / 全表合計 ${result.stats.rows} 行`} / ${result.stats.statements} 文 / ${result.stats.tables.length} 表`);
  if (outPath) log(`     保存先     : ${outPath}`);
  for (const w of result.warnings) log(`[WARN] ${w}`);
  for (const w of file.warnings || []) log(`[WARN] 取込: ${w}`);
  if (!result.sql) log('[WARN] SQL が空です（マッピングと対象テーブルを確認してください）');
  return result.sql ? 0 : 2;
}

try {
  process.exitCode = main(process.argv.slice(2));
} catch (e) {
  console.error(`[ERROR] ${e.message}`);
  process.exitCode = 1;
}

#!/usr/bin/env node
/**
 * Java 代码索引命令行工具
 * 与 Web 端共用 server/java-store.js 中的 SQLite 索引库（data/code-index.sqlite），
 * 可在不启动 Web 服务的情况下对文件夹 / JAR 建立（增量）全文索引并检索。
 *
 * 用法见 help：node cli/java-index.mjs help
 */
import fs from 'node:fs';
import path from 'node:path';
import * as store from '../server/java-store.js';

const HELP = `Java 代码索引 / 全文检索 CLI（与 Web 端共用 SQLite 索引库）

命令：
  scan <目录|jar文件> [选项]   扫描目录（增量）或 JAR/ZIP，建立索引
  refresh                      对所有已登记目录执行增量扫描
  sources                      列出数据源与统计
  groups                       列出多级分组路径
  search <关键词> [选项]        检索代码 / 注释
  delete <sourceId>            删除一个数据源批次
  file <fileId>                按需输出完整文件内容
  help                         显示本帮助

通用选项：
  --name <名称>        数据源名称（scan）
  --group <路径>       多级分组，如 "平台/FC模块/二方库"（scan）
  --note <说明>        备注（scan）
  --json               输出完整 JSON

search 选项：
  --mode <fuzzy|regex|fulltext>   检索模式（默认 fuzzy）
  --fields <字段,字段>            包名,类名,字段名,方法名,方法内容,包注释,类注释,方法注释,字段注释
  --ext <后缀>                    文件名后缀过滤，如 "*.java,*.xml"
  --package <包名>                包名过滤，支持 * 通配符，如 "com.example.*"
  --case-sensitive                区分大小写（三种模式均生效）
  --source <sourceId>             只检索某个数据源
  --group-filter <路径>           只检索某分组（含子分组）
  --kinds <jar,folder>            来源类型（默认全部）
  --limit <数量>                  返回上限（默认 50）
  --context <行数>                命中上下文行数（默认 3）

示例：
  npm run java-index -- scan D:/work/fc-src --group "平台/FC模块" --name fc-src
  npm run java-index -- refresh
  npm run java-index -- search "组织树" --package "com.example.*" --ext "*.java" --context 3
  npm run java-index -- search "findBy(Parent|Code)" --mode regex --case-sensitive
  npm run java-index -- groups
`;

function parseArgs(args) {
  const opt = { _: [] };
  const bools = new Set(['--case-sensitive', '--json', '--help']);
  for (let i = 0; i < args.length; i++) {
    const a = args[i];
    if (bools.has(a)) { opt[a.slice(2)] = true; continue; }
    if (a.startsWith('--')) {
      const key = a.slice(2);
      if (i + 1 >= args.length) throw new Error(`${a} 缺少取值`);
      opt[key] = args[++i];
    } else {
      opt._.push(a);
    }
  }
  return opt;
}

const out = (value, opt) => {
  if (opt.json) { console.log(JSON.stringify(value, null, 2)); return; }
  if (typeof value === 'string') { console.log(value); return; }
  console.log(JSON.stringify(value, null, 2));
};

const stripHtml = (html) => String(html || '')
  .replace(/<[^>]+>/g, '')
  .replace(/&lt;/g, '<').replace(/&gt;/g, '>').replace(/&amp;/g, '&')
  .replace(/&#39;/g, "'").replace(/&quot;/g, '"');

function cmdScan(opt) {
  const target = opt._[0];
  if (!target) throw new Error('请指定要扫描的目录或 JAR/ZIP 文件');
  const resolved = path.resolve(target);
  if (!fs.existsSync(resolved)) throw new Error('路径不存在：' + resolved);
  const st = fs.statSync(resolved);
  let result;
  if (st.isDirectory()) {
    result = store.scanPath(resolved, opt.name || '', opt.note || '', opt.group || '');
  } else if (/\.(jar|zip)$/i.test(resolved)) {
    const base64 = fs.readFileSync(resolved).toString('base64');
    result = store.scanJar(opt.name || path.basename(resolved), base64, opt.note || '', opt.group || '');
  } else {
    throw new Error('仅支持目录或 .jar / .zip 文件：' + resolved);
  }
  out({
    sourceId: result.sourceId,
    root: result.root,
    files: result.fileCount,
    entries: result.entryCount,
    javaFiles: result.javaFiles,
    textFiles: result.textFiles,
    added: result.added,
    removed: result.removed,
    archives: result.archives,
    archivesAdded: result.archivesAdded,
    archivesUpdated: result.archivesUpdated,
    archivesRemoved: result.archivesRemoved,
    skipped: result.skipped,
  }, opt);
}

function cmdSearch(opt) {
  const query = opt._.slice(0).join(' ');
  if (!query.trim()) throw new Error('请输入检索关键词');
  const fields = opt.fields
    ? opt.fields.split(/[,，]/).map((s) => s.trim()).filter(Boolean)
    : undefined;
  const result = store.search({
    query,
    mode: opt.mode || 'fuzzy',
    fields,
    caseSensitive: !!opt['case-sensitive'],
    fileExtensions: opt.ext || '',
    packageFilter: opt.package || '',
    sourceId: opt.source || '',
    sourceGroup: opt['group-filter'] || '',
    sourceKinds: opt.kinds ? opt.kinds.split(/[,，]/).map((s) => s.trim()).filter(Boolean) : undefined,
    limit: Number(opt.limit || 50),
    contextLines: Number(opt.context || 3),
  });
  if (opt.json) { out(result, opt); return; }
  console.log(`命中 ${result.total} 条（扫描 ${result.scanned} 条索引）  模式=${result.mode}`);
  if (result.refresh && (result.refresh.added || result.refresh.removed || result.refresh.archivesAdded)) {
    console.log(`增量扫描：+${result.refresh.added} / -${result.refresh.removed} 文件，归档 +${result.refresh.archivesAdded} / ~${result.refresh.archivesUpdated} / -${result.refresh.archivesRemoved}`);
  }
  result.hits.forEach((h, i) => {
    const symbol = [h.packageName, h.className].filter(Boolean).join('.')
      + (h.methodName ? `#${h.methodName}` : h.fieldName ? `#${h.fieldName}` : '');
    console.log(`\n[${i + 1}] ${symbol || h.path}  (${h.path}:${h.startLine}-${h.endLine})`);
    console.log(`    来源: ${h.sourceName}${h.sourceGroup ? ` [${h.sourceGroup}]` : ''}  命中: ${h.matchedFields.map((f) => f.label).join('/')}`);
    const snippet = stripHtml(h.snippetHtml).split('\n').filter((s) => s.trim()).slice(0, 12).join('\n');
    console.log(snippet.split('\n').map((line) => '    ' + line).join('\n'));
  });
}

function main() {
  const [cmd, ...rest] = process.argv.slice(2);
  const opt = parseArgs(rest);
  switch (cmd) {
    case 'help':
    case '-h':
    case '--help':
    case undefined:
      console.log(HELP);
      break;
    case 'scan':
      cmdScan(opt);
      break;
    case 'refresh':
      out(store.refreshScanRoots(), opt);
      break;
    case 'sources':
      out({ sources: store.listSources(), roots: store.listRoots(), stats: store.stats() }, opt);
      break;
    case 'groups':
      out(store.listGroups(), opt);
      break;
    case 'search':
      cmdSearch(opt);
      break;
    case 'delete': {
      const id = opt._[0];
      if (!id) throw new Error('请指定数据源 ID');
      out({ deleted: store.deleteSource(id) }, opt);
      break;
    }
    case 'file': {
      const id = opt._[0];
      if (!id) throw new Error('请指定文件 ID');
      out(store.getFileContent(id), opt);
      break;
    }
    default:
      throw new Error(`未知命令：${cmd}（输入 help 查看用法）`);
  }
}

try {
  main();
} catch (e) {
  console.error('错误：' + (e.message || String(e)));
  process.exitCode = 1;
}

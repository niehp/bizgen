/**
 * 纯 JS Java 源码解析器。
 * 目标不是完整编译级 AST，而是稳定抽取下列检索所需信息：
 *   packageName / className / methodName / methodContent
 *   packageComment / classComment / methodComment
 * 关键技巧：先构造与原文等长的 masked 文本，把注释和字符串字面量替换成空格（保留换行），
 * 这样正则匹配不会命中注释里的 "class Foo {"，且行号与原文严格一致。
 */

const KEYWORDS = new Set([
  'if', 'else', 'for', 'while', 'do', 'switch', 'case', 'default', 'try', 'catch', 'finally',
  'return', 'throw', 'new', 'synchronized', 'instanceof', 'assert', 'class', 'interface',
  'enum', 'record', 'extends', 'implements', 'super', 'this', 'package', 'import', 'void',
  'int', 'long', 'boolean', 'byte', 'char', 'short', 'float', 'double', 'var',
]);
const MODIFIERS = new Set([
  'public', 'protected', 'private', 'static', 'final', 'abstract', 'native', 'synchronized',
  'default', 'strictfp', 'transient', 'volatile', 'sealed', 'non-sealed',
]);
const STATEMENT_STARTS = new Set([
  'return', 'throw', 'new', 'if', 'else', 'for', 'while', 'switch', 'case', 'do',
  'try', 'catch', 'finally', 'assert', 'yield', 'super', 'this', 'import', 'package',
  'continue', 'break',
]);

function normalize(source) {
  return String(source || '').replace(/\r\n?/g, '\n');
}

/** 扫描注释/字符串，返回 masked 文本和注释列表 */
function maskSource(src) {
  const chars = src.split('');
  const comments = [];
  let i = 0;
  let state = 'code';
  let start = 0;

  const blank = (from, to, keepNewline = true) => {
    for (let k = from; k < to; k++) {
      if (keepNewline && chars[k] === '\n') continue;
      chars[k] = ' ';
    }
  };

  while (i < src.length) {
    const c = src[i];
    const n = src[i + 1];
    if (state === 'code') {
      if (c === '/' && n === '/') { state = 'line'; start = i; i += 2; continue; }
      if (c === '/' && n === '*') { state = 'block'; start = i; i += 2; continue; }
      if (c === '"') {
        if (src.slice(i, i + 3) === '"""') { state = 'text'; start = i; i += 3; } else { state = 'string'; start = i; i++; }
        continue;
      }
      if (c === "'") { state = 'char'; start = i; i++; continue; }
      i++;
      continue;
    }
    if (state === 'line') {
      if (c === '\n') {
        const raw = src.slice(start, i);
        comments.push({ start, end: i, raw, kind: 'line', text: cleanComment(raw, 'line') });
        state = 'code';
      }
      i++;
      continue;
    }
    if (state === 'block') {
      if (c === '*' && n === '/') {
        const end = i + 2;
        const raw = src.slice(start, end);
        const kind = raw.startsWith('/**') && !raw.startsWith('/***') ? 'javadoc' : 'block';
        comments.push({ start, end, raw, kind, text: cleanComment(raw, kind) });
        blank(start, end);
        state = 'code';
        i = end;
        continue;
      }
      i++;
      continue;
    }
    if (state === 'string' || state === 'char') {
      if (c === '\\') { blank(i, Math.min(i + 2, src.length)); i += 2; continue; }
      if ((state === 'string' && c === '"') || (state === 'char' && c === "'")) {
        blank(start, i + 1);
        state = 'code';
      }
      i++;
      continue;
    }
    if (state === 'text') {
      if (src.slice(i, i + 3) === '"""') {
        blank(start, i + 3);
        state = 'code';
        i += 3;
      } else i++;
      continue;
    }
    i++;
  }
  if (state === 'line') {
    const raw = src.slice(start);
    comments.push({ start, end: src.length, raw, kind: 'line', text: cleanComment(raw, 'line') });
  }
  if (state === 'block') {
    const raw = src.slice(start);
    comments.push({ start, end: src.length, raw, kind: 'block', text: cleanComment(raw, 'block') });
    blank(start, src.length);
  }
  return { masked: chars.join(''), comments };
}

function cleanComment(raw, kind) {
  if (kind === 'line') {
    return raw.replace(/^\s*\/\/+/, '').replace(/\s+$/, '');
  }
  let body = raw.replace(/^\/\*+/, '').replace(/\*+\/$/, '');
  body = body.split('\n').map((line) => line.replace(/^\s*\*\s?/, '').replace(/\s+$/, '')).join('\n');
  return body.trim();
}

function makeLineLocator(src) {
  const starts = [0];
  for (let i = 0; i < src.length; i++) if (src[i] === '\n') starts.push(i + 1);
  return (pos) => {
    let lo = 0;
    let hi = starts.length - 1;
    while (lo < hi) {
      const mid = (lo + hi + 1) >> 1;
      if (starts[mid] <= pos) lo = mid; else hi = mid - 1;
    }
    return lo + 1;
  };
}

function matchBrace(masked, openIdx) {
  let depth = 0;
  for (let i = openIdx; i < masked.length; i++) {
    const c = masked[i];
    if (c === '{') depth++;
    else if (c === '}') {
      depth--;
      if (depth === 0) return i;
    }
  }
  return masked.length - 1;
}

function prevBoundary(masked, idx) {
  for (let i = idx - 1; i >= 0; i--) {
    if (masked[i] === ';' || masked[i] === '{' || masked[i] === '}') return i;
  }
  return -1;
}

/** 查找声明前紧邻的 javadoc（允许中间只有空白与注解） */
function findLeadingJavadoc(comments, masked, declStart, maxDistance = 2500) {
  for (let i = comments.length - 1; i >= 0; i--) {
    const c = comments[i];
    if (c.kind !== 'javadoc' || c.end > declStart) continue;
    if (declStart - c.end > maxDistance) break;
    const between = masked.slice(c.end, declStart)
      .replace(/@[\w.]+(?:\s*\([^()]*\))?/g, '')
      .trim();
    if (between === '') return c.text;
    return '';
  }
  return '';
}

function annotationFreeHead(head) {
  // 去掉行首注解（含带参数的注解），保留其余修饰符与返回类型
  return head
    .replace(/(?:^|\n)\s*@[\w.]+(?:\s*\([^()]*\))?/g, '')
    .replace(/^\s*@[\w.]+(?:\s*\([^()]*\))?\s*/g, '')
    .replace(/\s+/g, ' ')
    .trim();
}

function braceDepthBefore(masked, idx) {
  let depth = 0;
  for (let i = 0; i < idx; i++) {
    if (masked[i] === '{') depth++;
    else if (masked[i] === '}') depth--;
  }
  return depth;
}

/**
 * 解析单个 .java 文件。
 * @returns {{packageName:string, packageComment:string, classes:Array, methods:Array, entries:Array, content:string}}
 */
export function parseJavaFile(relPath, rawSource) {
  const src = normalize(rawSource);
  const { masked, comments } = maskSource(src);
  const lineOf = makeLineLocator(src);

  // ---- package ----
  const pkgMatch = /\bpackage\s+([\w.]+)\s*;/.exec(masked);
  const packageName = pkgMatch ? pkgMatch[1] : '';
  let packageComment = '';
  if (pkgMatch) {
    const before = masked.slice(0, pkgMatch.index).trim();
    packageComment = before === '' || /^\/\/[^\n]*$/.test(before)
      ? findLeadingJavadoc(comments, masked, pkgMatch.index, 4000)
      : findLeadingJavadoc(comments, masked, pkgMatch.index, 4000);
  }
  if (!packageComment && /(^|\/)package-info\.java$/i.test(relPath)) {
    const first = comments.find((c) => c.kind === 'javadoc');
    if (first) packageComment = first.text;
  }

  // ---- 类 / 接口 / 枚举 / record ----
  const classRe = /(?:^|[\s;{}])((?:(?:public|protected|private|abstract|final|static|strictfp|sealed|non-sealed)\s+)*)(@interface|class|interface|enum|record)\s+([A-Za-z_$][\w$]*)/g;
  const classes = [];
  let cm;
  while ((cm = classRe.exec(masked)) !== null) {
    const nameStart = cm.index + cm[0].lastIndexOf(cm[3]);
    const open = masked.indexOf('{', nameStart + cm[3].length);
    if (open < 0) continue;
    const close = matchBrace(masked, open);
    // 修饰符起点（而非 class 关键字位置），这样注解与修饰符不会挡住紧邻的 javadoc
    const declStart = cm.index + (cm[0].match(/^\s/) ? 1 : 0);
    classes.push({
      name: cm[3],
      kind: cm[2],
      declStart,
      nameStart,
      open,
      close,
      depth: braceDepthBefore(masked, declStart),
      startLine: lineOf(declStart),
      endLine: lineOf(close),
      comment: findLeadingJavadoc(comments, masked, declStart),
      header: src.slice(declStart, Math.min(open + 1, declStart + 600)).trim(),
    });
  }

  const ownerOf = (pos) => {
    let best = null;
    for (const c of classes) {
      if (c.open < pos && pos <= c.close && (!best || c.open > best.open)) best = c;
    }
    return best;
  };

  // ---- 方法：以 { 为锚点提取有方法体的方法；以 ; 为锚点提取接口 / abstract 声明 ----
  const methods = [];
  for (let i = 0; i < masked.length; i++) {
    const isBlock = masked[i] === '{';
    const isDecl = masked[i] === ';';
    if (!isBlock && !isDecl) continue;
    const boundary = prevBoundary(masked, i);
    const head = annotationFreeHead(masked.slice(boundary + 1, i));
    if (!head || head.length > 800) continue;
    if (head.includes('=') || head.includes('->') || /\bnew\b/.test(head)) continue;
    const firstWord = (head.match(/^[A-Za-z_$][\w$]*/) || [''])[0];
    if (STATEMENT_STARTS.has(firstWord)) continue;

    const m = /^([\w$<>\[\],.?\s]*?)\s+([A-Za-z_$][\w$]*)\s*\(([\s\S]*)\)(?:\s+throws\s+[\w\s,.<>$?]+)?$/.exec(head);
    if (!m) continue;
    const methodName = m[2];
    if (KEYWORDS.has(methodName) || STATEMENT_STARTS.has(methodName)) continue;
    const ownerHere = ownerOf(i);
    if (isDecl && (!ownerHere || (ownerHere.kind !== 'interface' && !/\babstract\b/.test(head)))) continue;
    const prefix = m[1].trim();
    const modifierWords = prefix.split(/\s+/).filter(Boolean);
    const lastToken = modifierWords[modifierWords.length - 1] || '';
    const rest = modifierWords.filter((w) => !MODIFIERS.has(w) && w !== '@');
    const isConstructor = rest.length === 0 && lastToken && MODIFIERS.has(lastToken);
    if (!isConstructor && !lastToken) continue;

    // 签名在原文中的起点（跳过注解后）
    const rawHead = masked.slice(boundary + 1, i);
    const sigOffset = rawHead.search(/\S/);
    const sigStart = boundary + 1 + Math.max(0, sigOffset);
    const close = isBlock ? matchBrace(masked, i) : i;
    const owner = ownerHere;
    const methodContent = src.slice(sigStart, close + 1).trim();
    const comment = findLeadingJavadoc(comments, masked, sigStart);

    methods.push({
      name: methodName,
      isConstructor,
      returnType: isConstructor ? '' : rest[rest.length - 1] || '',
      params: m[3].replace(/\s+/g, ' ').trim(),
      owner: owner ? owner.name : '',
      sigStart,
      open: i,
      close,
      startLine: lineOf(sigStart),
      endLine: lineOf(close),
      comment,
      content: methodContent,
    });
  }

  // ---- 字段：类体内、方法体外的 ; 结尾声明（含 javadoc） ----
  const fields = [];
  for (let i = 0; i < masked.length; i++) {
    if (masked[i] !== ';') continue;
    const owner = ownerOf(i);
    if (!owner) continue;
    if (methods.some((m) => m.open < i && i < m.close)) continue;
    const boundary = prevBoundary(masked, i);
    const head = annotationFreeHead(masked.slice(boundary + 1, i));
    if (!head || head.length > 600) continue;
    const firstWord = (head.match(/^[A-Za-z_$][\w$]*/) || [''])[0];
    if (STATEMENT_STARTS.has(firstWord)) continue;
    if (head.includes('(') || head.includes('{') || head.includes('->') || /\bnew\b/.test(head)) continue;

    // 去掉初始化表达式（字符串字面量已被 masked，等号不会来自字符串内容）
    const eq = head.indexOf('=');
    const decl = (eq >= 0 ? head.slice(0, eq) : head).trim();
    const fm = /^(?:(?:public|protected|private|static|final|transient|volatile|strictfp|synchronized)\s+)*([\w$<>\[\],.?\s]+?)\s+([A-Za-z_$][\w$]*)((?:\s*,\s*[A-Za-z_$][\w$]*)*)$/.exec(decl);
    if (!fm) continue;
    const names = [fm[2], ...(fm[3].match(/[A-Za-z_$][\w$]*/g) || [])];
    const rawHead = masked.slice(boundary + 1, i);
    const sigStart = boundary + 1 + Math.max(0, rawHead.search(/\S/));
    const comment = findLeadingJavadoc(comments, masked, sigStart);
    const content = src.slice(sigStart, i + 1).trim();
    for (const name of names) {
      fields.push({
        name,
        type: fm[1].trim(),
        owner: owner.name,
        comment,
        content,
        sigStart,
        startLine: lineOf(sigStart),
        endLine: lineOf(i),
      });
    }
  }

  // ---- 汇总为检索条目：package / class / field / method 四个层级 ----
  const entries = [];
  if (packageComment) {
    entries.push({
      kind: 'package', path: relPath, packageName, className: '', methodName: '',
      signature: packageName ? `package ${packageName};` : '',
      methodContent: packageName ? `package ${packageName};` : '',
      packageComment, classComment: '', methodComment: '',
      startLine: pkgMatch ? lineOf(pkgMatch.index) : 1,
      endLine: pkgMatch ? lineOf(pkgMatch.index) : 1,
    });
  }
  for (const c of classes) {
    entries.push({
      kind: 'class', path: relPath, packageName, className: c.name, methodName: '',
      signature: c.header,
      methodContent: c.header,
      packageComment, classComment: c.comment, methodComment: '',
      startLine: c.startLine, endLine: c.startLine,
    });
  }
  for (const f of fields) {
    const owner = classes.find((c) => c.name === f.owner) || null;
    entries.push({
      kind: 'field', path: relPath, packageName,
      className: f.owner, methodName: '',
      fieldName: f.name,
      signature: `${f.type} ${f.name}`,
      methodContent: f.content,
      packageComment, classComment: owner ? owner.comment : '',
      methodComment: '', fieldComment: f.comment,
      startLine: f.startLine, endLine: f.endLine,
    });
  }
  for (const m of methods) {
    const owner = classes.find((c) => c.name === m.owner) || null;
    entries.push({
      kind: m.isConstructor ? 'constructor' : 'method',
      path: relPath, packageName,
      className: m.owner,
      methodName: m.name,
      signature: m.content.split('\n')[0],
      methodContent: m.content,
      packageComment,
      classComment: owner ? owner.comment : '',
      methodComment: m.comment,
      startLine: m.startLine,
      endLine: m.endLine,
    });
  }

  return { packageName, packageComment, classes, fields, methods, entries, content: src };
}

/** 明显的二进制扩展名：不做全文索引 */
const BINARY_EXT = /\.(?:class|jar|war|ear|zip|tar|gz|bz2|xz|7z|rar|png|jpe?g|gif|bmp|ico|webp|svgz|pdf|docx?|xlsx?|pptx?|odt|ods|exe|dll|so|dylib|bin|dat|db|sqlite|sqlite3|ttf|otf|eot|woff2?|mp3|mp4|m4a|avi|mov|wav|flac|ogg|o|obj|pyc|pyo|lock)$/i;

/** 仅按文件名判断是否为明显二进制（用于扫描前预过滤） */
export function isBinaryFileName(name) {
  return BINARY_EXT.test(String(name || ''));
}

/** 判断文本内容是否可作为全文索引对象 */
export function looksLikeText(relPath, content) {
  if (BINARY_EXT.test(String(relPath || ''))) return false;
  const head = String(content ?? '').slice(0, 8192);
  if (head.includes('\0')) return false;
  // UTF-8 解码失败会产生替换字符；少量出现时允许（例如注释中的特殊符号）
  return (head.match(/\uFFFD/g) || []).length <= 3;
}

/**
 * 非 Java 文本文件：按行分块建立全文条目。
 * 每块保留精确起止行号，命中后可直接显示该段代码 / 配置的前后文。
 */
export function makeTextEntries(relPath, rawSource, chunkLines = 40) {
  const src = normalize(rawSource);
  if (!looksLikeText(relPath, src)) return [];
  const lines = src.split('\n');
  const size = Math.max(1, Math.min(200, Number(chunkLines) || 40));
  const entries = [];
  for (let i = 0; i < lines.length; i += size) {
    const part = lines.slice(i, i + size);
    const content = part.join('\n');
    if (!content.trim()) continue;
    entries.push({
      kind: 'text',
      path: relPath,
      packageName: '',
      className: '',
      fieldName: '',
      methodName: '',
      signature: `${String(relPath).split(/[\\/]/).pop()} : L${i + 1}`,
      methodContent: content,
      packageComment: '',
      classComment: '',
      methodComment: '',
      fieldComment: '',
      startLine: i + 1,
      endLine: i + part.length,
    });
  }
  return entries;
}

/** 批量解析：Java 文件结构化解析，其余文本文件按行分块建立全文索引 */
export function parseJavaSources(files) {
  const results = [];
  const errors = [];
  for (const f of files) {
    if (!f || typeof f.content !== 'string') continue;
    const relPath = String(f.path || '').replace(/\\/g, '/');
    const isJava = /\.java$/i.test(relPath);
    if (!isJava && !looksLikeText(relPath, f.content)) {
      errors.push({ path: f.path, error: '二进制文件已跳过' });
      continue;
    }
    try {
      const parsed = isJava
        ? parseJavaFile(relPath, f.content)
        : {
          packageName: '',
          content: normalize(f.content),
          entries: makeTextEntries(relPath, f.content),
        };
      results.push({
        path: relPath,
        fileName: relPath.split('/').pop(),
        packageName: parsed.packageName || '',
        content: parsed.content,
        entries: parsed.entries,
        isJava,
      });
    } catch (err) {
      errors.push({ path: f.path, error: err.message || String(err) });
    }
  }
  return { results, errors };
}

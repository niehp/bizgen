import React, { useEffect, useMemo, useRef, useState } from 'react';
import {
  listJavaSources, listJavaFiles, deleteJavaSource,
  scanJavaFolder, scanJavaJar, scanJavaPath,
  searchJava, getJavaEntry, setJavaSourceGroup, setJavaSourceName, renameJavaGroup,
  getJavaFileContent, getJavaFacets,
} from '../api.js';

const SOURCE_KINDS = [
  { id: 'jar', label: 'JAR 索引（SQLite）' },
  { id: 'folder', label: '文件夹（全文索引）' },
];
const MODES = [
  { id: 'fuzzy', label: '模糊检索' },
  { id: 'regex', label: '正则检索' },
  { id: 'fulltext', label: '全文索引' },
];

const kindLabel = (kind) => ({
  method: '方法', constructor: '构造函数', class: '类', field: '字段', package: '包', text: '文本',
}[kind] || kind);

const readBase64 = (file) => new Promise((resolve, reject) => {
  const reader = new FileReader();
  reader.onload = () => resolve(String(reader.result).split(',')[1] || '');
  reader.onerror = () => reject(new Error('读取文件失败: ' + file.name));
  reader.readAsDataURL(file);
});

export default function JavaStudio({ onStatus }) {
  const [sources, setSources] = useState([]);
  const [stats, setStats] = useState({ sources: 0, files: 0, entries: 0, methods: 0, fields: 0, texts: 0, byKind: {} });
  const [selectedSource, setSelectedSource] = useState('');
  const [sourceKinds, setSourceKinds] = useState(SOURCE_KINDS.map((k) => k.id));
  const [sourceGroup, setSourceGroup] = useState('');
  const [scanGroup, setScanGroup] = useState('');
  const [pathInput, setPathInput] = useState('');
  const [roots, setRoots] = useState([]);
  const [files, setFiles] = useState([]);
  const [busy, setBusy] = useState('');
  const [query, setQuery] = useState('');
  const [mode, setMode] = useState('fuzzy');
  const [logic, setLogic] = useState('or');
  const [caseSensitive, setCaseSensitive] = useState(false);
  const [contextLines, setContextLines] = useState(3);
  const [limit, setLimit] = useState(50);
  const [results, setResults] = useState(null);
  const [searching, setSearching] = useState(false);
  const [detail, setDetail] = useState(null);
  const [detailLoading, setDetailLoading] = useState(false);
  const [fullFile, setFullFile] = useState(null);
  const [fullFileLoading, setFullFileLoading] = useState(false);
  const [editingGroup, setEditingGroup] = useState('');
  const [groupDraft, setGroupDraft] = useState('');
  const [editingName, setEditingName] = useState('');
  const [nameDraft, setNameDraft] = useState('');
  const [editingGroupName, setEditingGroupName] = useState(null);
  const [groupNameDraft, setGroupNameDraft] = useState('');
  const [sourceIds, setSourceIds] = useState([]);
  const [facets, setFacets] = useState({ extensions: [], packages: [], groups: [] });
  const [hasSearched, setHasSearched] = useState(false);
  const lastAutoKey = useRef('');
  const searchSeq = useRef(0);
  const [fileNameFilter, setFileNameFilter] = useState('');
  const [packageFilter, setPackageFilter] = useState('');
  // 二级检索：在一级结果内再过滤，不重新扫描索引
  const [narrowQuery, setNarrowQuery] = useState('');
  const [narrowMode, setNarrowMode] = useState('fuzzy');
  const [narrowCaseSensitive, setNarrowCaseSensitive] = useState(false);
  const [collapsedGroups, setCollapsedGroups] = useState([]);
  const jarInput = useRef(null);
  const folderInput = useRef(null);

  const tell = (type, text) => onStatus?.({ type, text });

  const refreshSources = async () => {
    const r = await listJavaSources();
    setSources(r.sources || []);
    setStats(r.stats || stats);
    setRoots(r.roots || []);
    return r.sources || [];
  };

  // 数据源过滤默认全选；新增/删除后保留仍有效的选择
  useEffect(() => {
    setSourceIds((prev) => {
      if (!sources.length) return [];
      const valid = prev.filter((id) => sources.some((s) => s.id === id));
      return valid.length ? valid : sources.map((s) => s.id);
    });
  }, [sources]);

  useEffect(() => {
    refreshSources().catch((e) => tell('error', '索引库连接失败: ' + e.message));
    // 支持 ?q=关键词 深链：打开页面即自动检索，便于分享检索结果
    const q = new URLSearchParams(window.location.search).get('q');
    if (q) {
      setQuery(q);
      // 稍后再检索，避免状态栏被 App 启动时的模板加载提示覆盖
      setTimeout(() => runSearch({ query: q, sourceId: '' }), 400);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  useEffect(() => {
    if (!selectedSource) { setFiles([]); return; }
    listJavaFiles(selectedSource)
      .then((r) => setFiles(r.files || []))
      .catch((e) => tell('error', e.message));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [selectedSource]);

  // 检索条件指纹：任一输入变化都会触发联动重查（检索过一次后生效）
  const autoDeps = JSON.stringify({
    query, mode, logic, caseSensitive, fileNameFilter, packageFilter,
    contextLines, limit, selectedSource, sourceIds, sourceKinds, sourceGroup,
  });

  const runSearch = async (override = {}) => {
    // 索引库尚未加载时不传 sourceIds（后台按全部处理）；加载后按勾选过滤
    const effectiveSourceIds = override.sourceIds ?? (sources.length ? sourceIds : undefined);
    const payload = {
      query: override.query ?? query,
      mode: override.mode ?? mode,
      logic: override.logic ?? logic,
      caseSensitive: override.caseSensitive ?? caseSensitive,
      fileNameFilter: override.fileNameFilter ?? fileNameFilter,
      packageFilter: override.packageFilter ?? packageFilter,
      contextLines: override.contextLines ?? contextLines,
      limit: override.limit ?? limit,
      sourceId: override.sourceId ?? selectedSource,
      sourceIds: effectiveSourceIds,
      sourceKinds: override.sourceKinds ?? sourceKinds,
      sourceGroup: override.sourceGroup ?? sourceGroup,
    };
    if (!payload.query.trim()) { tell('error', '请输入要检索的代码或注释'); return; }
    setHasSearched(true);
    lastAutoKey.current = autoDeps;
    const seq = ++searchSeq.current;
    setSearching(true);
    setDetail(null);
    try {
      const r = await searchJava(payload);
      if (seq !== searchSeq.current) return;
      setResults(r);
      const inc = r.refresh;
      const incText = inc && (inc.added || inc.removed || inc.archivesAdded || inc.archivesUpdated || inc.archivesRemoved)
        ? ` · 增量扫描 +${inc.added} / -${inc.removed} 文件，归档 +${inc.archivesAdded} / ~${inc.archivesUpdated} / -${inc.archivesRemoved}`
        : '';
      tell(r.total ? 'ok' : 'info', `命中 ${r.total} 条（扫描 ${r.scanned} 条索引记录）${incText}`);
    } catch (e) {
      if (seq === searchSeq.current) tell('error', e.message);
    } finally {
      if (seq === searchSeq.current) setSearching(false);
    }
  };

  // 检索过一次后，任一条件变化自动重新检索（400ms 防抖，避免逐字触发）
  useEffect(() => {
    if (!hasSearched || !query.trim()) return undefined;
    if (lastAutoKey.current === autoDeps) return undefined;
    const timer = window.setTimeout(() => runSearch(), 400);
    return () => window.clearTimeout(timer);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [autoDeps, hasSearched]);

  // 检索来源（JAR / 文件夹）变化 → 联动数据源勾选、选中数据源与分组过滤
  useEffect(() => {
    const allowed = sources.filter((s) => sourceKinds.includes(s.kind)).map((s) => s.id);
    setSourceIds((prev) => {
      const next = prev.filter((id) => allowed.includes(id));
      return next.length === prev.length ? prev : next;
    });
    if (selectedSource && !allowed.includes(selectedSource)) setSelectedSource('');
    if (sourceGroup) {
      const valid = sources.some((s) => allowed.includes(s.id)
        && (s.group_name === sourceGroup || String(s.group_name || '').startsWith(sourceGroup + '/')));
      if (!valid) setSourceGroup('');
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [sourceKinds, sources]);

  // 来源 / 数据源 / 分组变化 → 联动后缀、包名、分组候选项
  useEffect(() => {
    let cancelled = false;
    getJavaFacets({ sourceKinds, sourceIds, sourceId: selectedSource, sourceGroup })
      .then((r) => { if (!cancelled) setFacets(r); })
      .catch(() => {});
    return () => { cancelled = true; };
  }, [
    sourceKinds.join(','), sourceIds.join(','), selectedSource, sourceGroup, sources.length,
    // eslint-disable-next-line react-hooks/exhaustive-deps
  ]);

  // 二级检索：仅在一级结果集内再过滤，不重新扫描索引库
  const visibleHits = useMemo(() => {
    const all = results?.hits || [];
    const q = narrowQuery.trim();
    if (!q) return all;
    let re = null;
    if (narrowMode === 'regex') {
      try { re = new RegExp(q, narrowCaseSensitive ? '' : 'i'); }
      catch { return []; }
    }
    const tokens = narrowCaseSensitive ? q.split(/\s+/).filter(Boolean) : q.toLowerCase().split(/\s+/).filter(Boolean);
    const textOf = (h) => [
      h.packageName, h.className, h.fieldName, h.methodName, h.signature,
      h.methodPreview, h.packageComment, h.classComment, h.methodComment, h.fieldComment,
      h.fileName, h.path, h.sourceName,
    ].filter(Boolean).join('\n');
    if (re) return all.filter((h) => { re.lastIndex = 0; return re.test(textOf(h)); });
    // 带空格：先按连续短语（压缩空白差异）匹配；无命中再回退多词 AND
    const collapse = (s) => String(s ?? '').replace(/\s+/g, ' ');
    const phrase = narrowCaseSensitive ? collapse(q) : collapse(q).toLowerCase();
    const phraseHits = all.filter((h) => {
      const text = collapse(textOf(h));
      return (narrowCaseSensitive ? text : text.toLowerCase()).includes(phrase);
    });
    if (phraseHits.length) return phraseHits;
    return all.filter((h) => {
      const hay = narrowCaseSensitive ? textOf(h) : textOf(h).toLowerCase();
      return tokens.every((t) => hay.includes(t));
    });
  }, [results, narrowQuery, narrowMode, narrowCaseSensitive]);

  const openDetail = async (id) => {
    setDetailLoading(true);
    setFullFile(null);
    try {
      const r = await getJavaEntry(id, Math.max(6, Number(contextLines) + 3), query, caseSensitive);
      setDetail(r);
      document.querySelector('.java-detail-scroll')?.scrollTo({ top: 0 });
    } catch (e) {
      tell('error', e.message);
    } finally {
      setDetailLoading(false);
    }
  };

  const openFullFile = async () => {
    if (!detail) return;
    setFullFileLoading(true);
    try {
      const r = await getJavaFileContent(detail.entry.fileId);
      setFullFile(r);
    } catch (e) {
      tell('error', e.message);
    } finally {
      setFullFileLoading(false);
    }
  };

  const handleJar = async (event) => {
    const file = event.target.files?.[0];
    event.target.value = '';
    if (!file) return;
    setBusy(`正在读取 ${file.name} …`);
    try {
      const base64 = await readBase64(file);
      setBusy(`正在解析 ${file.name} 中的 Java 源码 …`);
      const r = await scanJavaJar(file.name, base64, scanGroup);
      await refreshSources();
      setSelectedSource(r.sourceId);
      setBusy('');
      tell('ok', `JAR 扫描完成：${r.archiveJavaFiles || 0} 个 Java + ${r.archiveTextFiles || 0} 个文本文件，${r.entryCount} 条索引${r.skipped ? `，跳过 ${r.skipped} 个条目` : ''}`);
    } catch (e) {
      setBusy('');
      tell('error', e.message);
    }
  };

  const handleFolder = async (event) => {
    const list = Array.from(event.target.files || []);
    event.target.value = '';
    if (!list.length) return;
    const BINARY = /\.(?:class|war|ear|tar|gz|bz2|xz|7z|rar|png|jpe?g|gif|bmp|ico|webp|pdf|docx?|xlsx?|pptx?|exe|dll|so|dylib|bin|dat|db|sqlite3?|ttf|otf|eot|woff2?|mp3|mp4|avi|mov|wav|o|obj|pyc)$/i;
    const archives = list.filter((f) => /\.(jar|zip)$/i.test(f.name) && f.size <= 64 * 1024 * 1024);
    const candidates = list.filter((f) => !archives.includes(f) && f.size <= 8 * 1024 * 1024 && !BINARY.test(f.name));
    if (!candidates.length && !archives.length) { tell('error', '所选文件夹中没有可索引的文本文件或 JAR/ZIP'); return; }
    if (candidates.length + archives.length > 5000) { tell('error', '单次最多 5000 个文件，请分批选择子目录'); return; }
    const folderName = ((candidates[0] || archives[0]).webkitRelativePath || (candidates[0] || archives[0]).name).split('/')[0];
    let firstId = '';
    let indexed = 0;
    let archiveCount = 0;
    try {
      for (let i = 0; i < candidates.length; i += 300) {
        const batch = candidates.slice(i, i + 300);
        setBusy(`正在读取文件 ${Math.min(i + 300, candidates.length)} / ${candidates.length} …`);
        const payload = [];
        for (const f of batch) {
          const text = await f.text();
          if (text.includes('\0')) continue;
          payload.push({ path: f.webkitRelativePath || f.name, content: text });
        }
        if (!payload.length) continue;
        const r = await scanJavaFolder(folderName, payload, scanGroup);
        if (!firstId) firstId = r.sourceId;
        indexed += (r.javaFiles || 0) + (r.textFiles || 0);
      }
      for (const f of archives) {
        setBusy(`正在解析归档 ${f.name} …`);
        const base64 = await readBase64(f);
        const r = await scanJavaJar(f.webkitRelativePath || f.name, base64, scanGroup);
        if (!firstId) firstId = r.sourceId;
        archiveCount++;
        indexed += r.javaFiles + r.textFiles;
      }
      await refreshSources();
      if (firstId) setSelectedSource(firstId);
      setBusy('');
      tell('ok', `文件夹扫描完成：${indexed} 条索引${archiveCount ? `（含 ${archiveCount} 个 JAR/ZIP）` : ''}`);
    } catch (e) {
      setBusy('');
      tell('error', e.message);
    }
  };

  const handlePath = async () => {
    const dirPath = pathInput.trim();
    if (!dirPath) { tell('error', '请输入服务器上的源码文件夹绝对路径'); return; }
    setBusy(`正在扫描 ${dirPath} …`);
    try {
      const r = await scanJavaPath(dirPath, '', scanGroup);
      await refreshSources();
      setSelectedSource(r.sourceId);
      setBusy('');
      tell(r.skipped ? 'info' : 'ok',
        `路径扫描完成：${r.fileCount} 个文件 / ${r.entryCount} 条索引 · 本次新增 ${r.added} / 移除 ${r.removed}，JAR/ZIP ${r.archives} 个${r.skipped ? `，跳过 ${r.skipped} 个文件` : ''}`);
    } catch (e) {
      setBusy('');
      tell('error', e.message);
    }
  };

  const handleDelete = async (source) => {
    if (!window.confirm(`删除索引批次「${source.name}」？该批次的源码与索引会被移除。`)) return;
    try {
      await deleteJavaSource(source.id);
      if (selectedSource === source.id) setSelectedSource('');
      await refreshSources();
      setResults(null);
      setDetail(null);
      tell('ok', '已删除索引批次');
    } catch (e) {
      tell('error', e.message);
    }
  };

  const saveGroup = async (source) => {
    const value = groupDraft.trim();
    setEditingGroup('');
    if (value === (source.group_name || '')) return;
    try {
      await setJavaSourceGroup(source.id, value);
      await refreshSources();
      tell('ok', `数据源已归入「${value || '未分组'}」`);
    } catch (e) {
      tell('error', e.message);
    }
  };

  const saveName = async (source) => {
    const value = nameDraft.trim();
    setEditingName('');
    if (!value || value === source.name) return;
    try {
      await setJavaSourceName(source.id, value);
      await refreshSources();
      tell('ok', `数据源已改名为「${value}」`);
    } catch (e) {
      tell('error', e.message);
    }
  };

  const saveGroupName = async (from, displayName) => {
    const value = groupNameDraft.trim();
    setEditingGroupName(null);
    if (!value || value === displayName) return;
    try {
      await renameJavaGroup(from, value);
      await refreshSources();
      // 分组过滤下拉同步到新路径（含后代）
      if (from && sourceGroup) {
        if (sourceGroup === from) {
          setSourceGroup(value);
        } else if (sourceGroup.startsWith(from + '/')) {
          const parent = from.includes('/') ? from.slice(0, from.lastIndexOf('/')) : '';
          const newPrefix = parent ? `${parent}/${value}` : value;
          setSourceGroup(newPrefix + sourceGroup.slice(from.length));
        }
      }
      tell('ok', `分组「${displayName}」已改名为「${value}」`);
    } catch (e) {
      tell('error', e.message);
    }
  };

  const toggleSourceId = (id) => setSourceIds((prev) => (
    prev.includes(id) ? prev.filter((x) => x !== id) : [...prev, id]
  ));

  const toggleSourceKind = (kind) => setSourceKinds((prev) => (
    prev.includes(kind) ? prev.filter((x) => x !== kind) : [...prev, kind]
  ));

  const setKindSelection = (kind, selected) => {
    const ids = sources.filter((s) => s.kind === kind).map((s) => s.id);
    setSourceIds((prev) => (selected
      ? [...new Set([...prev, ...ids])]
      : prev.filter((id) => !ids.includes(id))));
  };

  // 多级分组：group_name 为 "父/子/孙" 路径，构建为可折叠的树
  const buildGroupTree = (list) => {
    const make = (name, p, renameFrom) => ({
      name, path: p, renameFrom, items: [], children: new Map(), sources: 0, entries: 0,
    });
    const root = make('', '', '');
    for (const s of list) {
      const parts = String(s.group_name || '').split('/').map((x) => x.trim()).filter(Boolean);
      const segments = parts.length ? parts : ['未分组'];
      let node = root;
      let p = '';
      for (const segment of segments) {
        p = p ? `${p}/${segment}` : segment;
        if (!node.children.has(segment)) node.children.set(segment, make(segment, p, parts.length ? p : ''));
        node = node.children.get(segment);
      }
      node.items.push(s);
    }
    const rollup = (node) => {
      let count = node.items.length;
      let entries = node.items.reduce((n, s) => n + (s.entry_count || 0), 0);
      for (const child of node.children.values()) {
        const c = rollup(child);
        count += c.sources;
        entries += c.entries;
      }
      node.sources = count;
      node.entries = entries;
      return node;
    };
    rollup(root);
    return root;
  };
  const groupTree = buildGroupTree(sources);
  // 检索来源按 JAR / 文件夹分成两棵树，各自保留多级分类分组
  const treesByKind = {
    jar: buildGroupTree(sources.filter((s) => s.kind === 'jar')),
    folder: buildGroupTree(sources.filter((s) => s.kind === 'folder')),
  };
  const groupOptions = (() => {
    const paths = [];
    const walk = (node) => {
      for (const child of node.children.values()) {
        if (child.path) paths.push(child.path);
        walk(child);
      }
    };
    walk(groupTree);
    return [...new Set([...paths, sourceGroup].filter(Boolean))];
  })();
  const renderSource = (s) => (
    <div key={s.id} className={`java-source-item ${selectedSource === s.id ? 'active' : ''}`}>
      <div className="java-source-row">
        <input
          type="checkbox"
          className="java-source-check"
          checked={sourceIds.includes(s.id)}
          onChange={() => toggleSourceId(s.id)}
          title="勾选后参与检索"
        />
        {editingName === s.id ? (
          <input
            className="control java-name-edit"
            autoFocus
            value={nameDraft}
            onChange={(e) => setNameDraft(e.target.value)}
            onBlur={() => saveName(s)}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
            placeholder="数据源名称"
            spellCheck={false}
          />
        ) : (
          <button type="button" className="java-source-main" onClick={() => setSelectedSource(s.id)}>
            <span className="java-source-name" title={s.name}>{s.name}</span>
            <span className="java-source-kind">{s.kind}</span>
            <span className="java-source-meta">{s.file_count} 文件 / {s.entry_count} 条</span>
          </button>
        )}
        <button
          type="button"
          className="java-source-icon"
          title="修改数据源名称"
          onClick={() => { setEditingName(s.id); setNameDraft(s.name); }}
        >✎</button>
        <button type="button" className="java-source-del" onClick={() => handleDelete(s)} title="删除">×</button>
      </div>
      <div className="java-source-row java-source-subrow">
        {editingGroup === s.id ? (
          <input
            className="control java-group-edit"
            autoFocus
            value={groupDraft}
            onChange={(e) => setGroupDraft(e.target.value)}
            onBlur={() => saveGroup(s)}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
            placeholder="Group"
          />
        ) : (
          <button
            type="button"
            className="java-source-group"
            title="点击修改该数据源所属分组"
            onClick={() => { setEditingGroup(s.id); setGroupDraft(s.group_name || ''); }}
          >{s.group_name || '未分组'}</button>
        )}
      </div>
    </div>
  );

  const renderGroup = (node, depth = 0, kindPrefix = '') => {
    const key = `${kindPrefix}:${node.path || '__ungrouped__'}`;
    const collapsed = collapsedGroups.includes(key);
    return (
      <div key={key} className="java-group-block" style={{ marginLeft: depth * 10 }}>
        <div className="java-group-head-row">
          <button
            type="button"
            className="java-group-head"
            onClick={() => setCollapsedGroups((prev) => (
              collapsed ? prev.filter((x) => x !== key) : [...prev, key]
            ))}
          >
            <span>{collapsed ? '▸' : '▾'} {node.name}</span>
            <span className="java-group-count">{node.sources} 批 / {node.entries} 条</span>
          </button>
          <button
            type="button"
            className="java-source-icon"
            title="修改该分组名称（下级分组同步更新）"
            onClick={() => { setEditingGroupName(node.renameFrom); setGroupNameDraft(node.name); }}
          >✎</button>
        </div>
        {editingGroupName === node.renameFrom && (
          <input
            className="control java-group-name-edit"
            autoFocus
            value={groupNameDraft}
            onChange={(e) => setGroupNameDraft(e.target.value)}
            onBlur={() => saveGroupName(node.renameFrom, node.name)}
            onKeyDown={(e) => { if (e.key === 'Enter') e.currentTarget.blur(); }}
            placeholder="新分组名"
            spellCheck={false}
          />
        )}
        {!collapsed && (
          <div className="java-group-body">
            {node.items.map(renderSource)}
            {[...node.children.values()].map((child) => renderGroup(child, depth + 1, kindPrefix))}
          </div>
        )}
      </div>
    );
  };

  return (
    <div className="java-page">
      <div className="java-stat-strip">
        <span className="java-stat"><b>{stats.sources}</b> 个索引批次</span>
        <span className="java-stat"><b>{stats.files}</b> 已索引文件</span>
        <span className="java-stat"><b>{stats.methods}</b> 个方法</span>
        <span className="java-stat"><b>{stats.fields}</b> 个字段</span>
        <span className="java-stat"><b>{stats.texts}</b> 文本段</span>
        <span className="java-stat"><b>{stats.entries}</b> 条检索记录</span>
        <span className="java-stat-note">
          JAR {stats.byKind.jar || 0} 批 / 文件夹 {stats.byKind.folder || 0} 批 · 包名 / 类名 / 字段名 / 方法名 / 方法内容 / 包注释 / 类注释 / 方法注释 均可检索
        </span>
      </div>

      <main className="workspace workspace-java">
        {/* 左：数据源 */}
        <section className="panel java-source-panel">
          <div className="panel-head">
            <div>
              <h2>数据源</h2>
              <p className="panel-sub">JAR 归档 / 任意文本文件目录 / 服务器路径</p>
            </div>
          </div>

          <label className="java-group-field">
            <span>数据源分组（Group，支持多级）</span>
            <input
              className="control java-group-input"
              value={scanGroup}
              onChange={(e) => setScanGroup(e.target.value)}
              placeholder="例如：平台/FC模块/二方库"
              spellCheck={false}
            />
          </label>

          <div className="java-upload-row">
            <button type="button" className="btn btn-primary" onClick={() => jarInput.current?.click()} disabled={!!busy}>
              扫描 JAR
            </button>
            <button type="button" className="btn" onClick={() => folderInput.current?.click()} disabled={!!busy}>
              扫描文件夹
            </button>
          </div>
          <input ref={jarInput} type="file" accept=".jar,.zip" hidden onChange={handleJar} />
          <input ref={folderInput} type="file" webkitdirectory="" directory="" multiple hidden onChange={handleFolder} />
          <div className="java-path-row">
            <input
              className="control java-path-input"
              value={pathInput}
              onChange={(e) => setPathInput(e.target.value)}
              onKeyDown={(e) => { if (e.key === 'Enter') handlePath(); }}
              placeholder="服务器源码目录绝对路径"
              spellCheck={false}
            />
            <button type="button" className="btn" onClick={handlePath} disabled={!!busy}>扫描路径</button>
          </div>
          {busy && <div className="java-busy">{busy}</div>}
          <p className="java-hint">JAR 会扫描全部条目：.java 结构化解析，其他文本文件建立全文索引；扫描文件夹 / 路径覆盖所有文本文件与其中 JAR/ZIP，二进制自动跳过。路径扫描会自动登记，每次检索自动增量更新。</p>

          <div className="java-section-title">
            检索来源 · 按 JAR / 文件夹分组
            <span className="badge badge-neutral">{sourceIds.length}/{sources.length} 参与检索</span>
          </div>
          <div className="java-source-kinds">
            {SOURCE_KINDS.map((k) => {
              const enabled = sourceKinds.includes(k.id);
              const kindSources = sources.filter((s) => s.kind === k.id);
              const picked = kindSources.filter((s) => sourceIds.includes(s.id)).length;
              return (
                <div key={k.id} className={`java-kind-group ${enabled ? '' : 'java-kind-off'}`}>
                  <div className="java-kind-group-head">
                    <label className="java-check java-kind-check">
                      <input type="checkbox" checked={enabled} onChange={() => toggleSourceKind(k.id)} />
                      {k.label}
                      <span className="java-kind-count">{picked}/{kindSources.length}</span>
                    </label>
                    <button type="button" className="link-btn" disabled={!enabled || !kindSources.length}
                      onClick={() => setKindSelection(k.id, true)}>全选</button>
                    <button type="button" className="link-btn" disabled={!enabled || !kindSources.length}
                      onClick={() => setKindSelection(k.id, false)}>清空</button>
                  </div>
                  {enabled && (
                    <div className="java-kind-group-body">
                      {[...treesByKind[k.id].children.values()].map((node) => renderGroup(node, 0, k.id))}
                      {!kindSources.length && <div className="java-empty-mini">暂无该类型数据源</div>}
                    </div>
                  )}
                </div>
              );
            })}
          </div>

          <div className="java-section-title">按分组过滤（含子分组）</div>
          <select className="control java-group-filter" value={sourceGroup} onChange={(e) => setSourceGroup(e.target.value)}>
            <option value="">全部分组</option>
            {[...new Set([...groupOptions, ...(facets.groups || [])])].map((g) => (
              <option key={g} value={g}>{g}</option>
            ))}
          </select>

          <button
            type="button"
            className={`java-source-item java-all-sources ${selectedSource === '' ? 'active' : ''}`}
            onClick={() => setSelectedSource('')}
          >
            <span className="java-source-name">全部数据源（不限定单一批次）</span>
            <span className="java-source-meta">{stats.entries} 条</span>
          </button>

          {!!roots.length && (
            <div className="java-roots-note">自动增量扫描路径：{roots.length} 个（每次检索自动更新）</div>
          )}

          {selectedSource && (
            <>
              <div className="java-section-title">文件列表 <span className="badge badge-neutral">{files.length}</span></div>
              <div className="java-file-list">
                {files.map((f) => (
                  <button
                    key={f.id}
                    type="button"
                    className="java-file-item"
                    title={f.path}
                    onClick={() => runSearch({ query: f.file_name.replace(/\.java$/i, ''), sourceId: selectedSource })}
                  >
                    {f.file_name}
                    <span>{f.package_name}</span>
                  </button>
                ))}
              </div>
            </>
          )}
        </section>

        {/* 中：检索条件 + 结果 */}
        <section className="panel java-search-panel">
          <div className="panel-head">
            <div>
              <h2>代码检索</h2>
              <p className="panel-sub">输入代码段或注释，定位对应实现及其前后代码 · 检索一次后条件变化自动重查</p>
            </div>
            <button type="button" className="btn btn-primary" onClick={() => runSearch()} disabled={searching}>
              {searching ? '检索中…' : '检索'}
            </button>
          </div>

          <textarea
            className="editor java-query"
            value={query}
            onChange={(e) => setQuery(e.target.value)}
            onKeyDown={(e) => {
              if ((e.ctrlKey || e.metaKey) && e.key === 'Enter') { e.preventDefault(); runSearch(); }
            }}
            placeholder={'输入代码 / 注释 / 关键词（Java、配置、文档均可），例如：\ngetOrgTree\n@param\nserver.port'}
            spellCheck={false}
          />

          <div className="java-mode-row">
            {MODES.map((m) => (
              <button
                key={m.id}
                type="button"
                className={`nav-pill ${mode === m.id ? 'active' : ''}`}
                onClick={() => setMode(m.id)}
              >{m.label}</button>
            ))}
            <span className="java-mode-hint">
              {mode === 'fuzzy'
                ? '代码段自动整段匹配（忽略空白差异）；多关键词按 OR / AND 组合'
                : mode === 'regex'
                  ? '输入 JavaScript 正则，例如 findBy(Parent|Code)'
                  : '基于 FTS5 索引；中文会自动回退到模糊检索'}
            </span>
          </div>

          <div className="java-options-row">
            <label className="java-check">多词逻辑
              <select className="control" value={logic} onChange={(e) => setLogic(e.target.value)}>
                <option value="or">OR（任一）</option>
                <option value="and">AND（全部）</option>
              </select>
            </label>
            <label className="java-check">
              <input type="checkbox" checked={caseSensitive} onChange={(e) => setCaseSensitive(e.target.checked)} />
              区分大小写
            </label>
            <label className="java-check">文件名过滤
              <input
                className="control java-file-input"
                list="java-file-candidates"
                value={fileNameFilter}
                onChange={(e) => setFileNameFilter(e.target.value)}
                placeholder="*Service*.java"
                spellCheck={false}
              />
            </label>
            <label className="java-check">包名过滤
              <input
                className="control java-pkg-input"
                list="java-pkg-candidates"
                value={packageFilter}
                onChange={(e) => setPackageFilter(e.target.value)}
                placeholder="com.example.*"
                spellCheck={false}
              />
            </label>
            <label className="java-check">上下文
              <input
                className="control java-num" type="number" min="0" max="12"
                value={contextLines} onChange={(e) => setContextLines(Number(e.target.value))}
              /> 行
            </label>
            <label className="java-check">上限
              <select className="control" value={limit} onChange={(e) => setLimit(Number(e.target.value))}>
                <option value={20}>20</option><option value={50}>50</option>
                <option value={100}>100</option><option value={200}>200</option>
              </select>
            </label>
          </div>
          <datalist id="java-file-candidates">
            {(facets.fileNames || []).map((x) => <option key={x} value={x} />)}
          </datalist>
          <datalist id="java-pkg-candidates">
            {(facets.packages || []).map((x) => <option key={x} value={x} />)}
          </datalist>
          <div className="java-ext-hint">
            检索覆盖全部字段（包名 / 类名 / 字段名 / 方法名 / 方法内容 / 各类注释）。<b>关键字支持带空格</b>：词间空格按连续短语匹配（忽略空白差异），无结果时自动回退多词匹配；词首 / 词尾空格会自动忽略。文件名过滤按文件名匹配，支持 <code>*</code> 通配符（如 <code>*Service*.java</code>）；包名过滤支持 <code>*</code> 通配符（如 <code>com.example.*</code>）；多个值用英文逗号分隔，留空表示不过滤。区分大小写对模糊 / 正则 / 全文三种模式均生效。</div>

          <div className="java-narrow">
            <div className="java-narrow-head">
              <span className="java-narrow-title">二级检索 · 在一级结果内再检索</span>
              {results && narrowQuery.trim() && (
                <span className="badge badge-blue">{visibleHits.length} / {results.hits.length} 条</span>
              )}
            </div>
            <div className="java-narrow-row">
              <input
                className="control java-narrow-input"
                value={narrowQuery}
                onChange={(e) => setNarrowQuery(e.target.value)}
                placeholder="在一级结果中继续过滤（代码 / 注释 / 文件名 / 类名 …）"
                spellCheck={false}
              />
              <div className="java-narrow-modes">
                {[{ id: 'fuzzy', label: '模糊' }, { id: 'regex', label: '正则' }].map((m) => (
                  <button
                    key={m.id}
                    type="button"
                    className={`nav-pill ${narrowMode === m.id ? 'active' : ''}`}
                    onClick={() => setNarrowMode(m.id)}
                  >{m.label}</button>
                ))}
                <label className="java-check java-narrow-cs">
                  <input type="checkbox" checked={narrowCaseSensitive} onChange={(e) => setNarrowCaseSensitive(e.target.checked)} />
                  区分大小写
                </label>
                {!!narrowQuery && <button type="button" className="link-btn" onClick={() => setNarrowQuery('')}>清除</button>}
              </div>
            </div>
            {results && narrowQuery.trim() && !visibleHits.length && (
              <div className="java-narrow-hint">二级检索无命中：一级的 {results.hits.length} 条结果里没有符合的内容。可放宽二级条件或清除后重新检索。</div>
            )}
          </div>

          <div className="java-results-head">
            <span>检索结果{narrowQuery.trim() ? '（二级筛选后）' : ''}</span>
            {results && <span className="badge badge-blue">{results.total} 条命中 / 扫描 {results.scanned} 条</span>}
          </div>
          <div className="java-result-list">
            {!results && <div className="java-empty">先扫描 JAR 或文件夹建立索引，然后输入代码 / 注释 / 关键词检索。</div>}
            {results && !results.hits.length && <div className="java-empty">没有命中。试试更短的关键词，或切换检索模式 / 勾选更多字段。</div>}
            {visibleHits.map((hit) => (
              <div
                key={hit.id}
                role="button"
                tabIndex={0}
                className={`java-result-card ${detail?.entry?.id === hit.id ? 'active' : ''}`}
                onClick={() => openDetail(hit.id)}
                onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') openDetail(hit.id); }}
              >
                <div className="java-result-top">
                  <span className="badge badge-neutral">{kindLabel(hit.kind)}</span>
                  <span className="java-source-origin">{hit.sourceKind === 'jar' ? 'JAR·DB' : '文件夹·FTS'}</span>
                  <code className="java-result-symbol">
                    {[hit.packageName, hit.className].filter(Boolean).join('.')}
                    {hit.methodName ? `#${hit.methodName}` : hit.fieldName ? `#${hit.fieldName}` : ''}
                  </code>
                  <span className="java-result-line">{hit.startLine}{hit.endLine !== hit.startLine ? `–${hit.endLine}` : ''}</span>
                </div>
                <div className="java-result-tags">
                  {hit.matchedFields.map((f) => <span key={f.key} className="java-tag">{f.label}</span>)}
                </div>
                <div className="java-snippet" dangerouslySetInnerHTML={{ __html: hit.snippetHtml }} />
                <div className="java-result-path">{hit.sourceGroup ? `[${hit.sourceGroup}] ` : ''}{hit.sourceName} · {hit.path}</div>
              </div>
            ))}
          </div>
        </section>

        {/* 右：上下文与相邻方法 */}
        <section className="panel java-detail-panel">
          <div className="panel-head">
            <div>
              <h2>代码上下文</h2>
              <p className="panel-sub">命中代码的前后代码与相邻方法</p>
            </div>
            {detail && <span className="badge badge-blue">{detail.entry.methodName || detail.entry.fieldName || detail.entry.className || detail.entry.packageName || detail.entry.signature}</span>}
          </div>

          <div className="java-detail-scroll">
            {detailLoading && <div className="java-empty">加载中…</div>}
            {!detailLoading && !detail && (
              <div className="java-empty java-empty-detail">
                <p>点击左侧检索结果，查看：</p>
                <ul>
                  <li>命中位置前后各 N 行代码（带行号）</li>
                  <li>所属包 / 类 / 字段 / 方法注释</li>
                  <li>同一文件中的上一段 / 下一段代码（方法或字段）</li>
                  <li>点击「查看完整文件」按需显示整份文件内容</li>
                </ul>
              </div>
            )}
            {!detailLoading && detail && (
              <>
                <div className="java-detail-meta">
                  <code>{detail.path}</code>
                  <span>{detail.entry.kind} · L{detail.entry.startLine}–{detail.entry.endLine}</span>
                  <button type="button" className="btn btn-ghost java-fullfile-btn" onClick={openFullFile} disabled={fullFileLoading}>
                    {fullFileLoading ? '读取中…' : '查看完整文件'}
                  </button>
                </div>

                {(detail.entry.packageComment || detail.entry.classComment || detail.entry.methodComment || detail.entry.fieldComment) && (
                  <div className="java-comments">
                    {detail.entry.packageComment && <div className="java-comment-item"><b>包注释</b><pre>{detail.entry.packageComment}</pre></div>}
                    {detail.entry.classComment && <div className="java-comment-item"><b>类注释</b><pre>{detail.entry.classComment}</pre></div>}
                    {detail.entry.fieldComment && <div className="java-comment-item"><b>字段注释</b><pre>{detail.entry.fieldComment}</pre></div>}
                    {detail.entry.methodComment && <div className="java-comment-item"><b>方法注释</b><pre>{detail.entry.methodComment}</pre></div>}
                  </div>
                )}

                <div className="java-code-label">前文（上下文）</div>
                <div className="java-code-block" dangerouslySetInnerHTML={{ __html: detail.before.html }} />

                <div className="java-code-label java-hit-label">命中代码</div>
                <div className="java-code-block java-hit-block" dangerouslySetInnerHTML={{ __html: detail.hit.html }} />

                <div className="java-code-label">后文（上下文）</div>
                <div className="java-code-block" dangerouslySetInnerHTML={{ __html: detail.after.html }} />

                <div className="java-section-title">相邻代码（方法 / 字段）</div>
                <div className="java-neighbour-grid">
                  {['prevMethod', 'nextMethod'].map((key) => {
                    const n = detail[key];
                    return (
                      <div key={key} className="java-neighbour">
                        <div className="java-neighbour-title">{key === 'prevMethod' ? '← 上一段代码' : '下一段代码 →'}</div>
                        {n ? (
                          <button type="button" className="java-neighbour-btn" onClick={() => openDetail(n.id)}>
                            <code>{n.className ? `${n.className}#` : ''}{n.methodName}</code>
                            <pre>{n.preview}</pre>
                          </button>
                        ) : <div className="java-empty-mini">无</div>}
                      </div>
                    );
                  })}
                </div>
              </>
            )}
          </div>
        </section>
      </main>

      {fullFile && (
        <div className="java-fullfile-overlay" role="presentation" onClick={() => setFullFile(null)}>
          <div className="java-fullfile-modal" role="dialog" aria-modal="true" onClick={(e) => e.stopPropagation()}>
            <div className="java-fullfile-head">
              <div>
                <b>{fullFile.fileName}</b>
                <span>{fullFile.path} · {fullFile.lines} 行 · {fullFile.bytes} 字节</span>
              </div>
              <button type="button" className="btn btn-ghost" onClick={() => setFullFile(null)}>关闭</button>
            </div>
            <div className="java-fullfile-body">
              <pre className="java-fullfile-gutter">{Array.from({ length: Math.min(fullFile.lines, 20000) }, (_, i) => i + 1).join('\n')}</pre>
              <pre className="java-fullfile-text">{fullFile.content.split('\n').slice(0, 20000).join('\n')}{fullFile.lines > 20000 ? '\n…（仅显示前 20000 行）' : ''}</pre>
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

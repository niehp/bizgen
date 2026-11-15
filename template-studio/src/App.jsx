import React, { useCallback, useEffect, useState } from 'react';
import {
  extractVariables, renderTemplate, listTemplates, getTemplate,
  saveTemplate, deleteTemplate,
} from './api.js';
import TemplateEditor from './components/TemplateEditor.jsx';
import VariableForm from './components/VariableForm.jsx';
import OutputPanel from './components/OutputPanel.jsx';
import DbStudio from './db/DbStudio.jsx';
import ExcelStudio from './excel/ExcelStudio.jsx';

const viewFromHash = () => {
  const hash = window.location.hash.replace(/^#\/?/, '');
  if (hash === 'db') return 'db';
  if (hash === 'excel' || hash === 'mapping') return 'excel';
  return 'template';
};

const EMPTY_EXTRACTION = {
  variables: [],
  meta: { title: '', description: '', outputExt: 'txt', keepFirstCase: false },
  caseVars: { enabled: true, upper: 'Upper', lower: 'Lower' },
  derivedVariables: [],
  stats: { unique: 0, occurrences: 0, types: {}, derived: 0, derivedUsed: 0, multi: 0, hasFrontMatter: false },
  warnings: [],
};

const EMPTY_RESULT = {
  code: '', missing: [], unused: [], replaced: 0, outputExt: 'txt',
  stats: { lines: 0, chars: 0 },
};

const STARTER = `---
title: 新しいテンプレート
outputExt: txt
vars:
  target:
    label: 対象名
    default: sample
---
Hello {{target}}!
`;

export default function App() {
  const [templates, setTemplates] = useState([]);
  const [currentId, setCurrentId] = useState('');
  const [name, setName] = useState('');
  const [template, setTemplate] = useState('');
  const [extraction, setExtraction] = useState(EMPTY_EXTRACTION);
  const [values, setValues] = useState({});
  const [transforms, setTransforms] = useState({});
  const [expansions, setExpansions] = useState({});
  const [table, setTable] = useState(null);
  const [keepFirstCase, setKeepFirstCase] = useState(false);
  const [result, setResult] = useState(EMPTY_RESULT);
  const [status, setStatus] = useState({ type: 'info', text: '準備中…' });
  const [saving, setSaving] = useState(false);
  const [view, setView] = useState(viewFromHash);

  useEffect(() => {
    const onHash = () => setView(viewFromHash());
    window.addEventListener('hashchange', onHash);
    return () => window.removeEventListener('hashchange', onHash);
  }, []);

  const go = useCallback((next) => {
    window.location.hash = next === 'db' ? '#db' : next === 'excel' ? '#excel' : '#template';
    setView(next);
  }, []);

  const refresh = useCallback(async () => {
    const r = await listTemplates();
    setTemplates(r.templates || []);
    return r.templates || [];
  }, []);

  const loadTemplate = useCallback(async (id) => {
    const r = await getTemplate(id);
    if (!r.found || !r.template) throw new Error('テンプレートが見つかりません');
    setCurrentId(id);
    setName(r.template.name);
    setTemplate(r.template.content);
    setValues({});
    setTransforms({});
    setExpansions({});
    // null は「テンプレートから TABLE の初期行を読み込む」合図
    setTable(null);
  }, []);

  useEffect(() => {
    (async () => {
      try {
        const list = await refresh();
        const requested = new URLSearchParams(window.location.search).get('tpl');
        const chosen = (requested && list.find((t) => t.id === requested)) || list[0];
        if (chosen) {
          await loadTemplate(chosen.id);
          setStatus({ type: 'ok', text: `「${chosen.name}」を読み込みました` });
        } else {
          setTemplate(STARTER);
          setName('無題テンプレート');
          setStatus({ type: 'info', text: 'テンプレートを新規作成しました' });
        }
      } catch (e) {
        setStatus({ type: 'error', text: 'API に接続できません: ' + e.message });
      }
    })();
  }, [refresh, loadTemplate]);

  // テンプレート変更 → 変数を自動抽出し、既入力値を保持したまま既定値を補う
  useEffect(() => {
    if (!template.trim()) {
      setExtraction(EMPTY_EXTRACTION);
      setResult(EMPTY_RESULT);
      return;
    }
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const r = await extractVariables(template);
        if (cancelled) return;
        setExtraction(r);
        setValues((prev) => {
          const next = {};
          for (const v of r.variables) {
            next[v.name] = Object.prototype.hasOwnProperty.call(prev, v.name)
              ? prev[v.name]
              : (v.type === 'boolean' ? v.default === 'true' : v.default);
          }
          return next;
        });
        setTransforms((prev) => {
          const next = {};
          for (const v of r.variables) {
            if (Object.prototype.hasOwnProperty.call(prev, v.name)) next[v.name] = prev[v.name];
          }
          return next;
        });
        setExpansions((prev) => {
          const next = {};
          for (const v of r.variables) {
            if (Object.prototype.hasOwnProperty.call(prev, v.name)) next[v.name] = prev[v.name];
            else if (v.multi) next[v.name] = { mode: 'values', enabled: true };
            else if (v.expand) next[v.name] = { mode: 'range', enabled: true };
          }
          return next;
        });
        // TABLE はテンプレート切替時のみシードし、編集中の行は保持する
        setTable((prev) => prev == null
          ? { enabled: r.table?.enabled !== false, rows: r.table?.rows || [] }
          : prev);
      } catch (e) {
        if (!cancelled) setStatus({ type: 'error', text: e.message });
      }
    }, 300);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [template]);

  // 変数値の変更 → リアルタイムにコード生成
  useEffect(() => {
    if (!template.trim()) return;
    let cancelled = false;
    const timer = setTimeout(async () => {
      try {
        const r = await renderTemplate(template, values, transforms, expansions, {
          keepFirstCase,
          table,
        });
        if (!cancelled) setResult(r);
      } catch (e) {
        if (!cancelled) setStatus({ type: 'error', text: e.message });
      }
    }, 180);
    return () => { cancelled = true; clearTimeout(timer); };
  }, [template, values, transforms, expansions, keepFirstCase, table]);

  const handleValue = useCallback((n, v) => {
    setValues((prev) => ({ ...prev, [n]: v }));
  }, []);

  const handleTransform = useCallback((n, spec) => {
    setTransforms((prev) => ({ ...prev, [n]: spec }));
  }, []);

  const handleExpansion = useCallback((n, cfg) => {
    setExpansions((prev) => ({ ...prev, [n]: cfg }));
  }, []);

  const handleTableChange = useCallback((next) => {
    setTable(next);
  }, []);

  // ファイル取込で「1 行目を単一値に反映」を選んだとき、各変数の既定値を上書きする
  const handleImportValues = useCallback((patch) => {
    setValues((prev) => ({ ...prev, ...patch }));
  }, []);

  const toDefault = (v) => (v.type === 'boolean' ? v.default === 'true' : v.default);

  const handleReset = useCallback((n) => {
    const v = extraction.variables.find((x) => x.name === n);
    if (v) setValues((prev) => ({ ...prev, [n]: toDefault(v) }));
  }, [extraction.variables]);

  const handleResetAll = useCallback(() => {
    const next = {};
    for (const v of extraction.variables) next[v.name] = toDefault(v);
    setValues(next);
  }, [extraction.variables]);

  const handleSave = async () => {
    if (!name.trim()) {
      setStatus({ type: 'error', text: 'テンプレート名を入力してください' });
      return;
    }
    setSaving(true);
    try {
      const r = await saveTemplate(name.trim(), template);
      await refresh();
      setCurrentId(r.template.id);
      setStatus({
        type: 'ok',
        text: r.template.created ? `「${name}」を作成しました` : `「${name}」を更新しました`,
      });
    } catch (e) {
      setStatus({ type: 'error', text: e.message });
    } finally {
      setSaving(false);
    }
  };

  const handleNew = () => {
    setCurrentId('');
    setName('無題テンプレート');
    setTemplate(STARTER);
    setValues({});
    setTable(null);
    setStatus({ type: 'info', text: '新規テンプレート。編集後に保存してください' });
  };

  const handleDelete = async () => {
    if (!currentId) return;
    if (!window.confirm(`「${name}」を削除しますか？`)) return;
    try {
      await deleteTemplate(currentId);
      const list = await refresh();
      if (list.length) await loadTemplate(list[0].id);
      else { handleNew(); }
      setStatus({ type: 'ok', text: '削除しました' });
    } catch (e) {
      setStatus({ type: 'error', text: e.message });
    }
  };

  return (
    <div className="app">
      <header className="topbar">
        <div className="brand">
          <span className="brand-mark" />
          <div>
            <h1>Template Studio</h1>
            <p>テンプレート変数 → 動的フォーム → コード生成</p>
          </div>
        </div>

        <nav className="main-nav" aria-label="機能切替">
          <button
            type="button"
            className={`nav-item ${view === 'template' ? 'active' : ''}`}
            onClick={() => go('template')}
          >テンプレート生成</button>
          <button
            type="button"
            className={`nav-item ${view === 'db' ? 'active' : ''}`}
            onClick={() => go('db')}
          >DB メタデータ / SQL 生成</button>
          <button
            type="button"
            className={`nav-item ${view === 'excel' ? 'active' : ''}`}
            onClick={() => go('excel')}
          >Excel 取込 / INSERT 生成</button>
        </nav>

        {view === 'template' ? <div className="topbar-actions">
          <select
            className="control template-select"
            value={currentId}
            onChange={(e) => {
              const id = e.target.value;
              if (!id) return handleNew();
              loadTemplate(id).catch((err) => setStatus({ type: 'error', text: err.message }));
            }}
          >
            <option value="">（新規作成）</option>
            {templates.map((t) => (
              <option key={t.id} value={t.id}>{t.name}</option>
            ))}
          </select>
          <input
            className="control name-input"
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="テンプレート名"
            aria-label="テンプレート名"
          />
          <button type="button" className="btn btn-ghost" onClick={handleNew}>新規</button>
          <button type="button" className="btn btn-primary" onClick={handleSave} disabled={saving}>
            {saving ? '保存中…' : '保存'}
          </button>
          <button type="button" className="btn btn-danger-ghost" onClick={handleDelete} disabled={!currentId}>削除</button>
        </div> : <div className="topbar-actions">
          <span className="nav-context">
            {view === 'db'
              ? 'DB 接続 → メタデータ JSON → 結合 / UPDATE / DELETE SQL'
              : 'Excel 取込 → 列マッピング JSON → INSERT SQL（1 テーブル = 1 JSON）'}
          </span>
        </div>}
      </header>

      {view === 'db' ? <DbStudio onStatus={setStatus} />
        : view === 'excel' ? <ExcelStudio onStatus={setStatus} />
          : <main className="workspace">
        <TemplateEditor template={template} onChange={setTemplate} extraction={extraction} />
        <VariableForm
          variables={extraction.variables}
          values={values}
          transforms={transforms}
          expansions={expansions}
          caseVars={extraction.caseVars}
          keepFirstCase={keepFirstCase}
          metaKeep={Boolean(extraction.meta.keepFirstCase)}
          onKeepFirstCaseChange={setKeepFirstCase}
          table={table}
          onTableChange={handleTableChange}
          onImportValues={handleImportValues}
          onChange={handleValue}
          onTransformChange={handleTransform}
          onExpansionChange={handleExpansion}
          onReset={handleReset}
          onResetAll={handleResetAll}
        />
        <OutputPanel result={result} />
      </main>}

      <footer className={`statusbar status-${status.type}`}>
        <span className="status-dot" />
        <span>{status.text}</span>
        <span className="spacer" />
        <span className="status-tech">React · Node.js / Express</span>
      </footer>
    </div>
  );
}

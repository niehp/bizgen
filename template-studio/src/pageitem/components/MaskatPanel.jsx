import { useEffect, useMemo, useRef, useState } from 'react';
import {
  DEFAULT_WIDGET_RULES, MASKAT_NOTE, buildMaskatBundle, rulesForUi,
} from '../../../shared/maskat.js';
import { WIDGET_TYPES, CONTROL_CLASSES } from '../../../shared/layout.js';

const FILES = [
  { id: 'layout.xml', label: 'layout.xml', hint: 'layoutDef > layout(name) > widget；标签与属性按 /maskat-spec 校验，非法属性自动忽略' },
  { id: 'widgets.json', label: 'widgets.json', hint: '结构化 widget 定义：控件分类、真实标签、spec 元信息、event 属性与坐标' },
  { id: 'event.xml', label: 'event.xml', hint: 'eventDef > component(id) > event(id, type=local|remote)，对应列表中的 event 属性' },
  { id: 'page.js', label: 'page.js', hint: '页面 JavaScript 骨架（控件清单 + 空的初始化 / 校验函数）' },
];

const CLASS_LABEL = Object.fromEntries(CONTROL_CLASSES.map((c) => [c.id, c.label]));
const WIDGET_LABEL = Object.fromEntries(WIDGET_TYPES.map((w) => [w.id, w.label]));

export default function MaskatPanel({ image, items, config }) {
  const [rules, setRules] = useState(() => rulesForUi(DEFAULT_WIDGET_RULES));
  const [opts, setOpts] = useState({ layoutName: 'pageLayout', idPrefix: '' });
  const [file, setFile] = useState('layout.xml');
  const [showRules, setShowRules] = useState(false);
  const [copied, setCopied] = useState('');
  const [notice, setNotice] = useState('');
  const fileRef = useRef(null);
  const touched = useRef(false);

  // 服务端若下发了项目自己的默认规则，且用户尚未手改，则采用服务端规则
  useEffect(() => {
    if (touched.current) return;
    const fromServer = config?.maskat?.defaultRules;
    if (Array.isArray(fromServer) && fromServer.length) setRules(rulesForUi(fromServer));
  }, [config]);

  const bundle = useMemo(
    () => buildMaskatBundle({ items, image, rules, options: opts }),
    [items, image, rules, opts],
  );

  const activeFile = FILES.find((f) => f.id === file) || FILES[0];
  const text = bundle.files[file] || '';

  const setOpt = (k, v) => setOpts((prev) => ({ ...prev, [k]: v }));

  const editRule = (widget, field, value) => {
    touched.current = true;
    setRules((prev) => prev.map((r) => (r.widget === widget ? { ...r, [field]: value } : r)));
  };

  const downloadBlob = (content, filename, type) => {
    const blob = new Blob([content], { type });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  };

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setCopied('已复制');
      setTimeout(() => setCopied(''), 1800);
    } catch {
      setCopied('复制失败，请手动选择文本');
      setTimeout(() => setCopied(''), 2600);
    }
  };

  const exportRules = () => {
    downloadBlob(`${JSON.stringify(rules, null, 2)}\n`, 'maskat-widget-rules.json', 'application/json;charset=utf-8');
    setNotice('映射规则已导出为 JSON');
  };

  const importRules = (e) => {
    const f = e.target.files?.[0];
    e.target.value = '';
    if (!f) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const parsed = JSON.parse(String(reader.result || ''));
        const arr = Array.isArray(parsed) ? parsed : parsed.rules;
        if (!Array.isArray(arr)) throw new Error('JSON 顶层应为数组或含 rules 数组的对象');
        const next = rulesForUi(arr);
        if (!next.length) throw new Error('规则为空');
        touched.current = true;
        setRules(next);
        setNotice(`已导入 ${next.length} 条映射规则（未知字段自动补齐为默认值）`);
      } catch (err) {
        setNotice(`导入失败：${err.message}`);
      }
    };
    reader.onerror = () => setNotice('规则文件读取失败');
    reader.readAsText(f);
  };

  const downloadAll = () => {
    downloadBlob(
      `${JSON.stringify({
        schemaVersion: 'maskat-bundle.v1',
        layoutName: opts.layoutName,
        specSource: '/maskat-spec',
        stats: bundle.stats,
        warnings: bundle.warnings,
        files: bundle.files,
      }, null, 2)}\n`,
      `${opts.layoutName || 'pageLayout'}_maskat_bundle.json`,
      'application/json;charset=utf-8',
    );
  };

  const stats = bundle.stats;

  return (
    <section className="card maskat-card">
      <div className="card-head card-head-row">
        <h2><span className="step">6</span> 控件分类 → Maskat widget 生成 <span className="badge badge-ok">/maskat-spec 驱动</span></h2>
        <div className="output-tools">
          <button type="button" className="btn btn-mini" onClick={() => setShowRules((v) => !v)}>
            {showRules ? '收起映射规则' : '编辑映射规则'}
          </button>
          <button type="button" className="btn btn-mini" onClick={copy} disabled={!items.length}>复制</button>
          <button type="button" className="btn btn-mini btn-primary" onClick={() => downloadBlob(text, activeFile.id, 'text/plain;charset=utf-8')} disabled={!items.length}>
            下载 {activeFile.id}
          </button>
          <button type="button" className="btn btn-mini" onClick={downloadAll} disabled={!items.length}>下载全部产物 (.json)</button>
          {copied && <span className="copy-tip">{copied}</span>}
        </div>
      </div>

      <div className="maskat-body">
        <div className="maskat-stats">
          <div className="stat-card"><span>项目总数</span><strong>{stats.total}</strong></div>
          <div className="stat-card stat-input"><span>输入框 input</span><strong>{stats.input}</strong></div>
          <div className="stat-card stat-choice"><span>选择项 choice</span><strong>{stats.choice}</strong></div>
          <div className="stat-card"><span>其它 other</span><strong>{stats.other}</strong></div>
          <div className="stat-card stat-emit"><span>生成 widget</span><strong>{stats.emitted}</strong></div>
        </div>

        <div className="maskat-opts">
          <label className="field field-inline">
            <span>layout 名</span>
            <input className="control control-sm" value={opts.layoutName} onChange={(e) => setOpt('layoutName', e.target.value)} />
          </label>
          <label className="field field-inline">
            <span>部件 ID 前缀</span>
            <input className="control control-sm" value={opts.idPrefix} placeholder="可空" onChange={(e) => setOpt('idPrefix', e.target.value)} />
          </label>
        </div>

        {showRules && (
          <div className="rules-box">
            <div className="rules-head">
              <span>「Widget 类型 → Maskat XML 标签」映射规则来自 /maskat-spec；改成不存在的标签会自动回退到 spec 默认值</span>
              <div>
                <button type="button" className="btn btn-mini" onClick={exportRules}>导出规则</button>
                <button type="button" className="btn btn-mini" onClick={() => fileRef.current?.click()}>导入规则</button>
                <button type="button" className="btn btn-mini btn-danger-ghost" onClick={() => { touched.current = true; setRules(rulesForUi(DEFAULT_WIDGET_RULES)); setNotice('已恢复默认映射规则'); }}>恢复默认</button>
                <input ref={fileRef} type="file" accept=".json,application/json" hidden onChange={importRules} />
              </div>
            </div>
            <div className="rules-scroll">
              <table className="rules-table">
                <thead>
                  <tr>
                    <th>控件类型</th><th>分类</th><th>Maskat 标签</th><th>额外属性</th><th>必填属性 (spec)</th><th>event 属性 (spec)</th><th>输出</th>
                  </tr>
                </thead>
                <tbody>
                  {rules.map((r) => (
                    <tr key={r.widget}>
                      <td>{WIDGET_LABEL[r.widget] || r.widget}</td>
                      <td><span className={`cls-chip cls-${r.control}`}>{CLASS_LABEL[r.control]}</span></td>
                      <td>
                        <input
                          className="cell-input"
                          value={r.tag}
                          onChange={(e) => editRule(r.widget, 'tag', e.target.value)}
                          aria-label={`${r.widget} 标签名`}
                        />
                      </td>
                      <td>
                        <input
                          className="cell-input"
                          value={r.attrs}
                          placeholder="type=text, maxlength=20"
                          onChange={(e) => editRule(r.widget, 'attrs', e.target.value)}
                          aria-label={`${r.widget} 额外属性`}
                        />
                      </td>
                      <td>
                        <span className="rule-spec" title={(r.requiredAttributes || []).join(', ')}>
                          {(r.requiredAttributes || []).join(', ') || '—'}
                        </span>
                      </td>
                      <td>
                        <span className="rule-spec" title={(r.eventAttributes || []).join(', ')}>
                          {(r.eventAttributes || []).slice(0, 3).join(', ') || '—'}
                          {(r.eventAttributes || []).length > 3 ? ' …' : ''}
                        </span>
                      </td>
                      <td>
                        <input
                          type="checkbox"
                          checked={r.enabled}
                          onChange={(e) => editRule(r.widget, 'enabled', e.target.checked)}
                          aria-label={`${r.widget} 是否输出`}
                        />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <p className="rules-note">{MASKAT_NOTE}</p>
          </div>
        )}

        {notice && <div className="alert alert-clip">{notice}</div>}

        <div className="file-tabs seg">
          {FILES.map((f) => (
            <button key={f.id} type="button" className={file === f.id ? 'active' : ''} onClick={() => setFile(f.id)}>
              {f.label}
            </button>
          ))}
        </div>
        <p className="file-hint">{activeFile.hint}</p>
        <pre className="code-box maskat-code">{items.length ? text : '（暂无项目：请先上传图片并解析，或加载内置示例页面）'}</pre>

        {bundle.warnings.length > 0 && (
          <details className="dropped maskat-warn">
            <summary>{bundle.warnings.length} 条生成提示</summary>
            <ul>{bundle.warnings.slice(0, 20).map((w, i) => <li key={i}>{w}</li>)}</ul>
          </details>
        )}
      </div>

      <div className="output-foot">
        layout.xml 严格按 /maskat-spec 的真实结构 layoutDef → layout(name) → widget 生成，坐标来自 TOP / LEFT / WIDTH / HEIGHT（原图像素）。
        标签名、必填属性、event 属性均来自 widget-schema.json，非法属性会被忽略并列出提示；修改 maskat-spec/*.txt 后运行 npm run spec 即可重建规则。
      </div>
    </section>
  );
}

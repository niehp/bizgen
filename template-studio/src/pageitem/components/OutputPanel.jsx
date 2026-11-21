import { useMemo, useState } from 'react';
import { buildOutput, toCsv } from '../../../shared/layout.js';

const baseName = (name) => String(name || 'page').replace(/\.[a-z0-9]+$/i, '').replace(/[\\/:*?"<>|]/g, '_') || 'page';

export default function OutputPanel({ image, items, unit, meta, source }) {
  const [tab, setTab] = useState('json');
  const [copied, setCopied] = useState('');

  const jsonText = useMemo(
    () => JSON.stringify(buildOutput({ image, items, unit, meta: { source } }), null, 2),
    [image, items, unit, meta, source],
  );
  const csvText = useMemo(() => toCsv(items, unit, image), [items, unit, image]);
  const text = tab === 'json' ? jsonText : tab === 'csv' ? csvText : '';

  const download = () => {
    if (tab === 'debug') return;
    const isCsv = tab === 'csv';
    const blob = new Blob([isCsv ? csvText : jsonText], {
      type: isCsv ? 'text/csv;charset=utf-8' : 'application/json;charset=utf-8',
    });
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = `${baseName(image?.name)}_items.${isCsv ? 'csv' : 'json'}`;
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1500);
  };

  const copy = async () => {
    if (tab === 'debug') return;
    try {
      await navigator.clipboard.writeText(text);
      setCopied('已复制');
      setTimeout(() => setCopied(''), 1800);
    } catch {
      setCopied('复制失败，请手动选择文本');
      setTimeout(() => setCopied(''), 2600);
    }
  };

  return (
    <section className="card output-card">
      <div className="card-head card-head-row">
        <h2><span className="step">5</span> 输出 / 导出</h2>
        <div className="output-tools">
          <div className="seg">
            <button type="button" className={tab === 'json' ? 'active' : ''} onClick={() => setTab('json')}>JSON</button>
            <button type="button" className={tab === 'csv' ? 'active' : ''} onClick={() => setTab('csv')}>CSV</button>
            <button type="button" className={tab === 'debug' ? 'active' : ''} onClick={() => setTab('debug')}>Prompt / 原始响应</button>
          </div>
          <button type="button" className="btn btn-mini" onClick={copy} disabled={!items.length || tab === 'debug'}>复制</button>
          <button type="button" className="btn btn-mini btn-primary" onClick={download} disabled={!items.length || tab === 'debug'}>
            下载 {tab === 'csv' ? '.csv' : '.json'}
          </button>
          {copied && <span className="copy-tip">{copied}</span>}
        </div>
      </div>

      {tab === 'debug' ? (
        <div className="debug-body">
          {!meta ? (
            <div className="table-empty">
              当前为内置示例 / 手动标注，未调用大模型，因此没有 Prompt 与原始响应。
              配置密钥后点「解析图片」即可在此查看发给模型的完整提示词和模型原始返回。
            </div>
          ) : (
            <>
              <div className="meta-grid">
                <div><span>来源</span><strong>视觉大模型</strong></div>
                <div><span>模型</span><strong>{meta.model}</strong></div>
                <div><span>耗时</span><strong>{(meta.elapsedMs / 1000).toFixed(1)} s</strong></div>
                <div><span>密钥来源</span><strong>{meta.keySource || '—'}</strong></div>
                <div><span>返回项目</span><strong>{meta.returned}</strong></div>
                <div><span>Token</span><strong>{meta.usage?.total_tokens ?? '—'}</strong></div>
              </div>
              <h3 className="debug-title">发给大模型的 Prompt</h3>
              <pre className="code-box">{meta.prompt}</pre>
              <h3 className="debug-title">大模型原始响应</h3>
              <pre className="code-box">{meta.rawText || '(空)'}</pre>
            </>
          )}
        </div>
      ) : (
        <pre className="code-box output-code">{text || '（暂无数据）'}</pre>
      )}

      <div className="output-foot">
        {tab === 'json' && 'JSON 含图片尺寸、单位与 items 数组；每项有 name / top / left / width / height 以及 control / widget / label / options / required。'}
        {tab === 'csv' && 'CSV 带 UTF-8 BOM，可直接用 Excel 打开；列：序号、项目名、TOP、LEFT、WIDTH、HEIGHT、控件分类、Widget、关联标签、候选项、必填、类型、文本。'}
        {tab === 'debug' && '调试信息仅在当前浏览器会话内展示，不会上传到任何第三方。'}
      </div>
    </section>
  );
}

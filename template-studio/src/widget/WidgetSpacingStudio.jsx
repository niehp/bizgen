/**
 * XML Widget 间距计算（独立项目 widget-spacing 融合进 template-studio 的导航视图）。
 *
 * 职责：纯前端计算。粘贴每行一个 <widget left="..." width="..." />，
 * 以第一行的 left 为起点，按「前一个 left + 前一个 width + 间隔」重算后续 left，
 * 同时输出改写后的文本和逐行明细，供人工确认后复制回 Maskat 定义文件。
 *
 * 样式全部限定在 .ws-root 下，沿用 template-studio 的设计变量，避免污染其他导航页。
 */
import { useCallback, useEffect, useMemo, useRef, useState } from 'react';
import { computeLayout } from './layout.js';
import './widget-spacing.css';

const SAMPLE = `<widget left="16" width="120" title="A" />
<widget left="999" width="96" title="B" />
<widget left="999" width="64" title="C" />`;

export default function WidgetSpacingStudio({ onStatus }) {
  const [input, setInput] = useState(SAMPLE);
  const [gap, setGap] = useState('12');
  const [copied, setCopied] = useState(false);
  const copyTimer = useRef(null);

  useEffect(() => () => window.clearTimeout(copyTimer.current), []);

  const result = useMemo(
    () => computeLayout(input, gap === '' ? 0 : Number(gap)),
    [input, gap],
  );

  const handleCopy = useCallback(async () => {
    try {
      await navigator.clipboard.writeText(result.text);
      setCopied(true);
      onStatus?.({ type: 'ok', text: `已复制 ${result.widgets.length} 个 widget 的调整结果` });
      window.clearTimeout(copyTimer.current);
      copyTimer.current = window.setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
      onStatus?.({ type: 'error', text: '复制失败：当前浏览器不允许访问剪贴板' });
    }
  }, [result, onStatus]);

  const handleClear = useCallback(() => {
    setInput('');
    setCopied(false);
  }, []);

  const totalWidth =
    result.widgets.length > 0
      ? result.widgets[result.widgets.length - 1].newLeft +
        result.widgets[result.widgets.length - 1].width -
        result.widgets[0].newLeft
      : 0;

  return (
    <div className="ws-root">
      <header className="ws-hero">
        <div>
          <h1>XML Widget 间距计算</h1>
          <p>从上往下，以第一个 XML widget 的 left 为起点，自动重算后续 widget 的 left。</p>
        </div>
        <div className="ws-formula">后 left = 前 left + 前 width + 间隔</div>
      </header>

      <section className="ws-card ws-toolbar">
        <label className="ws-field">
          <span>间隔</span>
          <input
            type="number"
            value={gap}
            step="1"
            onChange={(event) => setGap(event.target.value)}
          />
        </label>
        <button type="button" className="ws-btn-primary" onClick={handleCopy}>
          {copied ? '已复制' : '复制结果'}
        </button>
        <button type="button" onClick={handleClear}>清空</button>
        <span className="ws-summary">
          识别 <b>{result.widgets.length}</b> 个 widget
          {result.widgets.length > 0 && <> · 总宽度 <b>{totalWidth}</b></>}
        </span>
      </section>

      <div className="ws-columns">
        <section className="ws-card ws-editor">
          <div className="ws-card-head">
            <h2>输入</h2>
            <span>每行一个 XML widget，需包含 left 与 width 属性</span>
          </div>
          <textarea
            spellCheck={false}
            value={input}
            placeholder={'<widget left="16" width="120" />\n<widget left="999" width="96" />'}
            onChange={(event) => setInput(event.target.value)}
          />
        </section>

        <section className="ws-card ws-editor">
          <div className="ws-card-head">
            <h2>结果</h2>
            <span>随输入自动更新</span>
          </div>
          <textarea spellCheck={false} readOnly value={result.text} />
        </section>
      </div>

      <section className="ws-card ws-table-card">
        <div className="ws-card-head">
          <h2>计算明细</h2>
          <span>第一行的 left 保持不变</span>
        </div>
        <div className="ws-table-wrap">
          <table>
            <thead>
              <tr>
                <th>#</th>
                <th>行号</th>
                <th>原 left</th>
                <th>width</th>
                <th>新 left</th>
                <th>调整</th>
              </tr>
            </thead>
            <tbody>
              {result.widgets.length === 0 && (
                <tr>
                  <td colSpan={6} className="ws-empty">
                    未识别到 widget，请粘贴 XML 文本（每行一个，包含 left 与 width 属性）。
                  </td>
                </tr>
              )}
              {result.widgets.map((widget, index) => (
                <tr key={`${widget.line}-${index}`}>
                  <td>{index + 1}</td>
                  <td>{widget.line}</td>
                  <td>{widget.originalLeft}</td>
                  <td>{widget.width}</td>
                  <td className="ws-new-left">{widget.newLeft}</td>
                  <td className={widget.delta === 0 ? 'ws-delta ws-zero' : 'ws-delta'}>
                    {widget.delta > 0 ? `+${widget.delta}` : widget.delta}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  );
}

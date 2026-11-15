import React, { useState } from 'react';

/**
 * 生成コードは「すべて」平铺して表示する。
 * 区切りは HTML の見出しだけであり、成果物のコードにはコメントを一切挿入しない。
 * コピーも純粋なコードのみ（見出し・注釈を含めない）。ダウンロード機能は設けない。
 */
export default function OutputPanel({ result }) {
  const [copied, setCopied] = useState(false);
  const {
    code = '', missing = [], unused = [], replaced = 0,
    outputExt, outputs = [], expansion,
  } = result;

  const multi = outputs.length > 0;
  const ext = outputExt || 'txt';
  const generation = result.generation || expansion || {};
  const mode = generation.mode || 'range';
  const modeLabel = mode === 'table'
    ? `TABLE 入力 ${generation.columns?.length || 0} 列 × ${outputs.length} 行`
    : mode === 'values'
      ? `${generation.name || '変数'} の複数値リスト`
      : `${generation.name || '変数'} の数字範囲`;
  const blocks = multi
    ? outputs.map((o, i) => ({
      key: `${i}-${o.index}`,
      label: `${i + 1} / ${outputs.length}${o.label ? ` · ${o.label}` : ''}`,
      code: o.code,
    }))
    : [{ key: 'single', label: '', code }];

  // コピー・行数計算はコード本体のみ。区切りコメントは混ぜない。
  const plainText = blocks.map((b) => b.code).join('\n');
  const isEmpty = blocks.every((b) => !b.code);
  const totalLines = plainText ? plainText.split('\n').length : 0;

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(plainText);
      setCopied(true);
      setTimeout(() => setCopied(false), 1500);
    } catch {
      setCopied(false);
    }
  };

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>3. 生成コード</h2>
          <p className="panel-sub">
            {multi
              ? `${modeLabel} ${outputs.length} 件をすべて表示しています（注釈の自動挿入なし）`
              : '入力に応じてリアルタイムに更新されます（注釈の自動挿入なし）'}
          </p>
        </div>
        <div className="output-badges">
          {multi && <span className="badge badge-blue">全 {outputs.length} 件</span>}
          <span className="badge badge-neutral">.{ext}</span>
        </div>
      </div>

      {missing.length > 0 && (
        <div className="alert alert-danger">
          必須変数が未入力です: {missing.map((m) => <code key={m}>{`{{${m}}}`}</code>)}
        </div>
      )}
      {unused.length > 0 && (
        <div className="alert alert-warn">
          テンプレートで使われていない値: {unused.join(', ')}
        </div>
      )}

      <div className="toolbar">
        <button type="button" className="btn" onClick={copy} disabled={isEmpty}>
          {copied ? 'コピーしました' : (multi ? '全件をコピー' : 'コピー')}
        </button>
        <span className="spacer" />
        <span className="mini-stat">
          {multi ? `${outputs.length} 件 · ` : ''}{replaced} 置換 · {totalLines} 行 · {plainText.length} 文字
        </span>
      </div>

      <div className="output-stack">
        {isEmpty ? (
          <div className="output-empty">テンプレートを入力すると、ここに生成結果が表示されます</div>
        ) : blocks.map((b) => (
          <div className="output-block" key={b.key}>
            {multi && (
              <div className="output-block-head">
                <span>{b.label}</span>
                <span>{b.code ? b.code.split('\n').length : 0} 行</span>
              </div>
            )}
            <pre className="output"><code>{b.code}</code></pre>
          </div>
        ))}
      </div>
    </section>
  );
}

import React from 'react';

export default function TemplateEditor({ template, onChange, extraction }) {
  const { stats, warnings, meta } = extraction;
  const typeSummary = Object.entries(stats.types || {})
    .map(([k, n]) => `${k} ${n}`)
    .join(' / ');

  return (
    <section className="panel">
      <div className="panel-head">
        <div>
          <h2>1. テンプレート入力</h2>
          <p className="panel-sub">本文に <code>{'{{変数名}}'}</code> を書くと自動検出されます</p>
        </div>
        <span className="badge badge-neutral">{template.length} 文字</span>
      </div>

      {meta.title && (
        <div className="meta-strip">
          <strong>{meta.title}</strong>
          {meta.description && <span> — {meta.description}</span>}
          <span className="badge badge-blue">.{meta.outputExt}</span>
        </div>
      )}

      <textarea
        className="editor"
        spellCheck={false}
        value={template}
        onChange={(e) => onChange(e.target.value)}
        placeholder={'---\ntitle: サンプル\noutputExt: java\nvars:\n  className:\n    label: クラス名\n    default: DemoService\n---\npublic class {{className}} {\n}\n'}
      />

      <div className="syntax-hint">
        <div><code>{'{{name}}'}</code> そのまま出力</div>
        <div><code>{'{{nameUpper}}'}</code> / <code>{'{{nameLower}}'}</code> / <code>{'{{namePascal}}'}</code> / <code>{'{{nameCamel}}'}</code></div>
        <div><code>{'{{nameUpperSnake}}'}</code> / <code>{'{{nameSnake}}'}</code> 6 種類の命名規則変数（入力不要）</div>
        <div><code>{'{{name | upperFirst}}'}</code> 変換（大小文字・連番）</div>
        <div><code>{'{{name | isUpperFirst}}'}</code> 先頭文字の判定・分岐</div>
        <div><code>{'{{name:type}}'}</code> 型指定（list / sequence ほか）</div>
        <div><code>--- vars: ---</code> label / default / transform / expand（数字範囲）</div>
        <div><code>{'multi: { values: [...] }'}</code> 同じ変数に複数の値 → 値ごとに 1 件生成</div>
        <div><code>{'table: { rows: [...] }'}</code> 1 変数 = 1 列 / 1 行 = 1 件の TABLE 入力</div>
        <div><code>ファイルからインポート</code> xlsx / csv / tsv / json / md の変数表を取り込み</div>
      </div>

      <div className="stat-row">
        <span className="stat"><b>{stats.unique || 0}</b> 変数</span>
        <span className="stat"><b>{stats.occurrences || 0}</b> 箇所</span>
        <span className="stat"><b>{stats.transforms || 0}</b> 変換</span>
        <span className="stat"><b>{stats.derived || 0}</b> 自動変数</span>
        <span className="stat"><b>{stats.multi || 0}</b> 複数値</span>
        <span className="stat stat-grow">{typeSummary || '型なし'}</span>
      </div>

      {warnings.length > 0 && (
        <ul className="warning-list">
          {warnings.map((w, i) => <li key={i}>{w}</li>)}
        </ul>
      )}
    </section>
  );
}

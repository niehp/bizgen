import { useRef, useState } from 'react';

export default function UploadPanel({
  config, onUpload, onDemo, onParse,
  loading, hasImage, image, error, notice,
}) {
  const fileRef = useRef(null);
  const [dragging, setDragging] = useState(false);
  const [clipMsg, setClipMsg] = useState('');

  const pickFiles = (files) => {
    // SVG 与普通位图统一交给 onUpload：SVG 会在浏览器里栅格化为 PNG 后再解析
    const file = Array.from(files || []).find((f) => f.type.startsWith('image/') || /\.svg$/i.test(f.name || ''));
    if (file) onUpload(file);
  };

  /** 主动读取剪贴板（Clipboard API）；权限被拒时提示退回 Ctrl + V */
  const readClipboard = async () => {
    setClipMsg('');
    try {
      if (!navigator.clipboard?.read) {
        setClipMsg('当前浏览器不支持主动读取剪贴板，请直接按 Ctrl + V 粘贴截图。');
        return;
      }
      const clipItems = await navigator.clipboard.read();
      for (const clipItem of clipItems) {
        const type = (clipItem.types || []).find((t) => t.startsWith('image/'));
        if (!type) continue;
        const blob = await clipItem.getType(type);
        const ext = type === 'image/svg+xml' ? 'svg'
          : type === 'image/jpeg' ? 'jpg' : type === 'image/webp' ? 'webp' : 'png';
        const file = new File([blob], `clipboard-${Date.now()}.${ext}`, { type });
        onUpload(file);
        setClipMsg('已从剪贴板读取图片，正在载入…');
        return;
      }
      setClipMsg('剪贴板里没有图片：请先复制截图，或直接按 Ctrl + V。');
    } catch (e) {
      setClipMsg(`读取剪贴板失败（${e?.message || '浏览器拒绝访问'}）。请直接按 Ctrl + V 粘贴截图。`);
    }
  };

  return (
    <section className="card side-card">
      <div className="card-head">
        <h2><span className="step">1</span> 上传页面图片</h2>
      </div>

      <div
        className={`dropzone ${dragging ? 'dragging' : ''}`}
        onDragOver={(e) => { e.preventDefault(); setDragging(true); }}
        onDragLeave={(e) => { e.preventDefault(); setDragging(false); }}
        onDrop={(e) => { e.preventDefault(); setDragging(false); pickFiles(e.dataTransfer.files); }}
        onClick={() => fileRef.current?.click()}
        role="button"
        tabIndex={0}
        onKeyDown={(e) => { if (e.key === 'Enter' || e.key === ' ') fileRef.current?.click(); }}
      >
        <input
          ref={fileRef}
          type="file"
          accept="image/png,image/jpeg,image/webp,image/gif,image/bmp,image/svg+xml,.svg"
          hidden
          onChange={(e) => { pickFiles(e.target.files); e.target.value = ''; }}
        />
        <div className="dz-icon" aria-hidden="true">
          <svg width="30" height="30" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
            <rect x="3" y="4" width="18" height="16" rx="2" />
            <circle cx="8.5" cy="9.5" r="1.5" />
            <path d="M21 15l-5-5L5 20" />
          </svg>
        </div>
        <strong>点击选择 / 拖放页面截图</strong>
        <span className="dz-sub">支持文件 / 拖放 / Ctrl + V / 主动读取剪贴板</span>
        <span className="dz-sub">PNG / JPG / WEBP / GIF / BMP / SVG · ≤ 12 MB</span>
        <span className="dz-sub">SVG 会自动转换为 PNG 后再解析</span>
      </div>

      <div className="clip-row">
        <button type="button" className="btn btn-mini btn-block" onClick={readClipboard} disabled={loading}>
          从剪贴板读取图片
        </button>
      </div>
      {clipMsg && <div className="alert alert-clip">{clipMsg}</div>}

      {image && (
        <div className="file-chip" title={image.name}>
          <span className="file-dot" />
          <span className="file-name">{image.name}</span>
          <span className="file-meta">{image.width} × {image.height}</span>
        </div>
      )}

      <div className="btn-row">
        <button type="button" className="btn btn-primary btn-block" onClick={onParse} disabled={!hasImage || loading}>
          {loading ? '解析中…' : '解析图片 → 列出项目'}
        </button>
        <button type="button" className="btn btn-ghost btn-block" onClick={onDemo} disabled={loading}>
          加载内置示例页面（离线）
        </button>
      </div>

      {error && <div className="alert alert-error"><strong>解析失败</strong><span>{error}</span></div>}
      {notice && <div className="alert alert-ok">{notice}</div>}

      <p className="settings-note">大模型设置已移除：解析所用接口与密钥统一由服务端 .env 配置，页面不再填写或保存。</p>

      <details className="settings">
        <summary><span>使用说明</span></summary>
        <div className="settings-body help">
          <ol>
            <li>上传页面截图：选文件、拖放、Ctrl + V 粘贴，或点「从剪贴板读取图片」。</li>
            <li>点「解析图片」，视觉大模型找出项目名、边框，并判定<strong>输入框 / 选择项 / 其它</strong>。</li>
            <li>服务端把 0–1000 归一化坐标换算成<strong>原图像素</strong>，列出 TOP / LEFT / WIDTH。</li>
            <li>表格里可改名、微调坐标、修正控件分类与 Widget；也可在图片上拖拽框新增项目。</li>
            <li>「Maskat widget 生成」按可编辑映射规则产出 layout.xml / widgets.json / event.xml / page.js。</li>
            <li>支持切换单位（px / % / 0–1000），导出 JSON 或 CSV。</li>
          </ol>
          <p>服务端未配置解析接口时：点「加载内置示例页面」可离线查看完整效果，或用「框选标注」模式手工圈项目。</p>
        </div>
      </details>
    </section>
  );
}

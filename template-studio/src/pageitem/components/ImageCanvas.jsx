import { useEffect, useRef, useState } from 'react';
import { boxFromPoints, clamp } from '../../../shared/layout.js';

const TYPE_LABEL = {
  label: '标签', input: '输入框', select: '下拉框', radio: '单选组', checkbox: '复选框',
  button: '按钮', header: '列头/标题', title: '标题', text: '文本', other: '其他',
};
const CLASS_LABEL = { input: '输入框', choice: '选择项', other: '其它' };

export default function ImageCanvas({
  image, items, selectedId, onSelect, onAddBox,
  zoom, setZoom, mode, setMode, unit,
}) {
  const stageRef = useRef(null);
  const scrollRef = useRef(null);
  const [drag, setDrag] = useState(null);
  const [cursor, setCursor] = useState(null);

  const toPoint = (e) => {
    const r = stageRef.current.getBoundingClientRect();
    return {
      x: clamp((e.clientX - r.left) / zoom, 0, image.width),
      y: clamp((e.clientY - r.top) / zoom, 0, image.height),
    };
  };

  const fitWidth = () => {
    const avail = (scrollRef.current?.clientWidth || 900) - 32;
    setZoom(clamp(Math.round((avail / image.width) * 100) / 100, 0.1, 2));
  };

  // 每次换图自动适应预览区宽度（内部坐标仍按原图像素计算，不受缩放影响）
  useEffect(() => {
    if (!image) return undefined;
    const id = requestAnimationFrame(() => {
      const avail = (scrollRef.current?.clientWidth || 900) - 32;
      setZoom(clamp(Math.round((avail / image.width) * 100) / 100, 0.1, 2));
    });
    return () => cancelAnimationFrame(id);
  }, [image?.dataUrl, image?.width]);

  const onPointerDown = (e) => {
    if (!image) return;
    if (mode !== 'draw') { onSelect(null); return; }
    if (typeof e.currentTarget.setPointerCapture === 'function') {
      try { e.currentTarget.setPointerCapture(e.pointerId); } catch { /* 部分环境不支持 */ }
    }
    const p = toPoint(e);
    setDrag({ start: p, cur: p });
  };

  const onPointerMove = (e) => {
    if (!image) return;
    const p = toPoint(e);
    setCursor(p);
    if (drag) setDrag((prev) => (prev ? { ...prev, cur: p } : prev));
  };

  const onPointerUp = (e) => {
    if (!drag) return;
    const p = toPoint(e);
    const box = boxFromPoints(drag.start, p, image);
    if (box.width >= 4 && box.height >= 4) onAddBox(box);
    setDrag(null);
    try { e.currentTarget.releasePointerCapture(e.pointerId); } catch { /* ignore */ }
  };

  const draftBox = drag ? boxFromPoints(drag.start, drag.cur, image) : null;

  return (
    <section className="card canvas-card">
      <div className="card-head card-head-row">
        <h2><span className="step">3</span> 图片预览 / 框选</h2>
        <div className="canvas-tools">
          <div className="seg">
            <button type="button" className={mode === 'select' ? 'active' : ''} onClick={() => setMode('select')}>选择</button>
            <button type="button" className={mode === 'draw' ? 'active' : ''} onClick={() => setMode('draw')}>框选标注</button>
          </div>
          <button type="button" className="btn btn-mini" onClick={() => setZoom((z) => clamp(Math.round((z - 0.1) * 100) / 100, 0.1, 2))}>−</button>
          <input
            className="zoom-range"
            type="range" min="0.1" max="2" step="0.05"
            value={zoom}
            onChange={(e) => setZoom(Number(e.target.value))}
            aria-label="缩放"
          />
          <button type="button" className="btn btn-mini" onClick={() => setZoom((z) => clamp(Math.round((z + 0.1) * 100) / 100, 0.1, 2))}>＋</button>
          <span className="zoom-label">{Math.round(zoom * 100)}%</span>
          <button type="button" className="btn btn-mini" onClick={fitWidth} disabled={!image}>适应宽度</button>
        </div>
      </div>

      <div className="canvas-scroll" ref={scrollRef}>
        {!image ? (
          <div className="canvas-empty">
            <div className="empty-art">🖼</div>
            <strong>尚未上传图片</strong>
            <span>上传页面截图，或点左侧「加载内置示例页面」立即查看效果</span>
          </div>
        ) : (
          <div
            className={`stage ${mode === 'draw' ? 'stage-draw' : ''}`}
            ref={stageRef}
            style={{ width: image.width * zoom, height: image.height * zoom }}
            onPointerDown={onPointerDown}
            onPointerMove={onPointerMove}
            onPointerUp={onPointerUp}
            onPointerLeave={() => setCursor(null)}
          >
            <img
              className="stage-img"
              src={image.dataUrl}
              alt={image.name || '上传的页面截图'}
              style={{ width: image.width * zoom, height: image.height * zoom }}
              draggable={false}
            />
            <div className="overlay">
              {items.map((it, i) => (
                <div
                  key={it.id}
                  className={`bbox bbox-${it.type || 'other'} ${selectedId === it.id ? 'selected' : ''}`}
                  style={{
                    left: it.left * zoom, top: it.top * zoom,
                    width: Math.max(2, it.width * zoom), height: Math.max(2, it.height * zoom),
                    pointerEvents: mode === 'draw' ? 'none' : 'auto',
                  }}
                  onPointerDown={(e) => {
                    if (mode === 'draw') return;
                    e.stopPropagation();
                    onSelect(it.id);
                  }}
                  title={`${it.name} · ${CLASS_LABEL[it.control] || '其它'} / ${TYPE_LABEL[it.type] || '其他'} · TOP ${it.top} LEFT ${it.left} WIDTH ${it.width}`}
                >
                  <span className="bbox-no">{i + 1}</span>
                </div>
              ))}
            </div>
            {draftBox && (
              <div
                className="bbox-draft"
                style={{
                  left: draftBox.left * zoom, top: draftBox.top * zoom,
                  width: draftBox.width * zoom, height: draftBox.height * zoom,
                }}
              />
            )}
          </div>
        )}
      </div>

      <div className="canvas-foot">
        <span>
          {image
            ? `原图 ${image.width} × ${image.height} px · 坐标单位 ${unit === 'px' ? 'px（原图像素）' : unit === '%' ? '%（相对画布）' : '0–1000（归一化）'}`
            : '坐标一律基于原图像素，不受预览缩放影响'}
        </span>
        <span className="cursor-pos">
          {cursor && image ? `x ${Math.round(cursor.x)} · y ${Math.round(cursor.y)}` : ''}
        </span>
        {mode === 'draw' && <span className="draw-tip">在图片上按住拖拽画框，松开后自动加入项目表</span>}
        {items.length > 0 && <span className="legend"><i className="lg-label" />标签 <i className="lg-input" />输入框 <i className="lg-choice" />选择项 <i className="lg-button" />按钮 <i className="lg-header" />列头</span>}
      </div>
    </section>
  );
}

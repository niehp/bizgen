/**
 * 页面项目 → Maskat widget 工作台（整合进 template-studio 的一个导航视图）。
 *
 * 两个子视图：
 *  - image：上传/粘贴截图 → 视觉模型解析出项目名 + TOP/LEFT/WIDTH → 输入框/选择项分类 → Maskat widget
 *  - excel：读取 Excel「画面項目」「画面レイアウト」两表 → widget 列表（名字/类型/坐标/event 属性）
 *
 * 为什么做成单组件持有全部状态：图片视图与 Excel 视图共享 spec 配置，且都要“自动保存当前状态”，
 * 状态集中在上层便于统一序列化到 localStorage，也便于子视图切换时不丢失各自工作进度。
 */
import { useCallback, useEffect, useRef, useState } from 'react';
import { getConfig, parseImage } from './api.js';
import { buildDemo } from './demo.js';
import { boxFromPoints, clamp, fromDisplay, sortItems, syncControlWidget, uid } from '../../shared/layout.js';
import UploadPanel from './components/UploadPanel.jsx';
import ImageCanvas from './components/ImageCanvas.jsx';
import ItemTable from './components/ItemTable.jsx';
import OutputPanel from './components/OutputPanel.jsx';
import MaskatPanel from './components/MaskatPanel.jsx';
import WidgetExcelStudio from './components/ExcelStudio.jsx';
import useAutoSave, { downloadState } from './useAutoSave.js';
import './pageitem.css'; // 作用域样式：全部选择器限定在 .pi-root 下，不污染主界面

const DEFAULT_LLM = { baseUrl: '', apiKey: '', model: '', focus: '', maxItems: 200, useEnv: true };
const STORAGE_IMAGE = 'image:v1';

export default function PageItemStudio({ onStatus }) {
  // 支持 #pageitem-excel 深链：主导航用 #pageitem，子视图再细分。
  const [view, setView] = useState(() =>
    window.location.hash.replace(/^#\/?/, '') === 'pageitem-excel' ? 'excel' : 'image');
  const [config, setConfig] = useState(null);
  const [image, setImage] = useState(null);
  const [items, setItems] = useState([]);
  const [selectedId, setSelectedId] = useState(null);
  const [unit, setUnit] = useState('px');
  const [zoom, setZoom] = useState(1);
  const [mode, setMode] = useState('select');
  const [llm, setLlm] = useState(DEFAULT_LLM);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [notice, setNotice] = useState('');
  const [meta, setMeta] = useState(null);
  const [dropped, setDropped] = useState([]);
  const [source, setSource] = useState('');
  const [resetKey, setResetKey] = useState(0);

  const imageAuto = useAutoSave(STORAGE_IMAGE);

  const tell = useCallback((text, type = 'info') => {
    setNotice(text);
    onStatus?.({ type, text });
  }, [onStatus]);

  // spec 配置只拉取一次：标签名、必填属性、event 属性都来自 /maskat-spec。
  useEffect(() => {
    getConfig()
      .then(setConfig)
      .catch(() => setConfig({ llm: { configured: false, hint: '后端未连接：请确认 Node.js 服务已启动' } }));
  }, []);

  // 首次挂载恢复上次自动保存的图片工作状态。
  useEffect(() => {
    const saved = imageAuto.restore();
    if (!saved) return;
    if (saved.image) setImage(saved.image);
    if (Array.isArray(saved.items)) setItems(saved.items);
    if (saved.llm) setLlm((p) => ({ ...p, ...saved.llm }));
    if (saved.unit) setUnit(saved.unit);
    if (saved.source) setSource(saved.source);
    setResetKey((k) => k + 1);
    setNotice('已恢复上次自动保存的工作状态');
    // 只在挂载时恢复一次
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // 图片视图状态变化 → 自动保存到 localStorage（同步写入，防抖交给依赖变化频率）。
  useEffect(() => {
    if (!image && !items.length) return;
    imageAuto.save({ image, items, llm, unit, source });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [image, items, llm, unit, source]);

  const onUpload = useCallback((file) => {
    if (!file || !file.type.startsWith('image/')) { setError('请选择图片文件'); return; }
    if (file.size > 12 * 1024 * 1024) { setError('图片超过 12 MB，请压缩后重试'); return; }
    const reader = new FileReader();
    reader.onerror = () => setError('图片读取失败');
    reader.onload = () => {
      const dataUrl = String(reader.result || '');
      const probe = new Image();
      probe.onerror = () => setError('无法解析该图片，请另存为 PNG / JPG 后重试');
      probe.onload = () => {
        setImage({
          name: file.name || 'pasted-image.png', width: probe.naturalWidth, height: probe.naturalHeight,
          mime: file.type, bytes: file.size, dataUrl,
        });
        setItems([]); setSelectedId(null); setMeta(null); setDropped([]); setSource(''); setError('');
        tell(`已载入 ${file.name || '剪贴板图片'}（${probe.naturalWidth} × ${probe.naturalHeight} px），点「解析图片」开始识别`);
        setResetKey((k) => k + 1);
      };
      probe.src = dataUrl;
    };
    reader.readAsDataURL(file);
  }, [tell]);

  // Ctrl + V 直接粘贴截图（输入焦点不在文本框时才拦截，避免影响正常输入）。
  useEffect(() => {
    const onPaste = (e) => {
      if (view !== 'image') return;
      const tag = document.activeElement?.tagName;
      if (tag === 'INPUT' || tag === 'TEXTAREA' || document.activeElement?.isContentEditable) return;
      const item = Array.from(e.clipboardData?.items || []).find((it) => it.type.startsWith('image/'));
      const file = item?.getAsFile();
      if (!file) return;
      e.preventDefault();
      onUpload(file);
    };
    window.addEventListener('paste', onPaste);
    return () => window.removeEventListener('paste', onPaste);
  }, [onUpload, view]);

  const go = useCallback((next) => {
    window.location.hash = next === 'excel' ? '#pageitem-excel' : '#pageitem';
    setView(next);
  }, []);

  const onDemo = useCallback(() => {
    const demo = buildDemo();
    setImage(demo.image); setItems(demo.items); setSelectedId(null);
    setUnit('px'); setMode('select'); setMeta(null); setDropped([]);
    setSource('demo'); setError('');
    tell(`已加载内置示例页面：${demo.items.length} 个项目（离线演示数据，未调用大模型）`);
    setResetKey((k) => k + 1);
  }, [tell]);

  const onParse = useCallback(async () => {
    if (!image) return;
    setLoading(true); setError(''); setNotice('');
    try {
      const result = await parseImage({
        image: image.dataUrl, filename: image.name,
        llm: { baseUrl: llm.baseUrl.trim(), apiKey: llm.apiKey, model: llm.model.trim(), useEnv: llm.useEnv },
        options: { focus: llm.focus.trim(), maxItems: Number(llm.maxItems) || 200 },
      });
      setImage((prev) => (prev ? { ...prev, width: result.image.width, height: result.image.height, mime: result.image.mime, bytes: result.image.bytes } : prev));
      setItems(result.items); setDropped(result.dropped || []); setMeta(result.meta);
      setSource('vision'); setSelectedId(null);
      const nInput = result.items.filter((i) => i.control === 'input').length;
      const nChoice = result.items.filter((i) => i.control === 'choice').length;
      tell(`识别完成：${result.items.length} 个项目（输入框 ${nInput} / 选择项 ${nChoice}），坐标已换算为原图像素`);
      setResetKey((k) => k + 1);
    } catch (e) {
      setError(e.message || '解析失败'); // 保留已有标注，不因失败清空
    } finally {
      setLoading(false);
    }
  }, [image, llm, tell]);

  const onEdit = useCallback((id, field, value) => {
    if (!image) return;
    setItems((prev) => prev.map((it) => {
      if (it.id !== id) return it;
      if (field === 'control' || field === 'widget') return { ...it, ...syncControlWidget(it, field, value) };
      if (['name', 'type', 'label', 'options', 'required'].includes(field)) return { ...it, [field]: value };
      const n = fromDisplay(field, value, unit, image);
      if (n === null) return it;
      const W = image.width, H = image.height;
      let px = Math.round(n);
      if (field === 'left') px = clamp(px, 0, Math.max(0, W - it.width));
      else if (field === 'top') px = clamp(px, 0, Math.max(0, H - it.height));
      else if (field === 'width') px = clamp(px, 1, W - it.left);
      else if (field === 'height') px = clamp(px, 1, H - it.top);
      else return it;
      return { ...it, [field]: px };
    }));
  }, [image, unit]);

  const onDelete = useCallback((id) => {
    setItems((prev) => prev.filter((it) => it.id !== id));
    setSelectedId((prev) => (prev === id ? null : prev));
  }, []);

  const onAddBox = useCallback((box) => {
    if (!image) return;
    const valid = boxFromPoints({ x: box.left, y: box.top }, { x: box.left + box.width, y: box.top + box.height }, image);
    if (valid.width < 4 || valid.height < 4) return;
    const id = uid();
    const next = {
      id, name: `项目 ${items.length + 1}`, ...valid, type: 'other', text: '', confidence: 0,
      source: 'manual', control: 'other', widget: 'static', label: '', options: [], required: false,
    };
    setItems((prev) => sortItems([...prev, next]));
    setSelectedId(id);
    setSource((prev) => (prev === 'demo' || prev === 'vision' ? 'mixed' : prev || 'manual'));
    tell(`已新增框选项目：TOP ${valid.top} / LEFT ${valid.left} / WIDTH ${valid.width}`);
  }, [image, items.length, tell]);

  const onClear = useCallback(() => {
    setItems([]); setSelectedId(null); setDropped([]);
    tell('项目列表已清空，图片仍保留，可重新解析或框选');
  }, [tell]);

  // 自动保存工具栏：导出 / 导入。state 序列化后即是“当前状态的 JSON 快照”。
  const fileRef = useRef(null);
  const exportState = () => downloadState({ image, items, llm, unit, source, view: 'image' }, 'pageitem-state.json');
  const importState = (file) => {
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => {
      try {
        const data = JSON.parse(String(reader.result || ''));
        if (data.image) setImage(data.image);
        if (Array.isArray(data.items)) setItems(data.items);
        if (data.llm) setLlm((p) => ({ ...p, ...data.llm }));
        if (data.unit) setUnit(data.unit);
        if (data.source) setSource(data.source);
        setResetKey((k) => k + 1);
        tell('已导入状态 JSON');
      } catch { setError('状态 JSON 解析失败'); }
    };
    reader.readAsText(file);
  };

  return (
    <div className="pi-root">
      <div className="view-bar">
        <button type="button" className={`view-tab ${view === 'image' ? 'active' : ''}`} onClick={() => go('image')}>
          图片解析 → Maskat
        </button>
        <button type="button" className={`view-tab ${view === 'excel' ? 'active' : ''}`} onClick={() => go('excel')}>
          Excel 设计书 → Widget 列表
        </button>
        <span className="view-context">
          {view === 'excel'
            ? '画面項目 + 画面レイアウト → widget 名字 / 类型 / TOP LEFT WIDTH / event 属性 → /maskat-spec'
            : '文件 / 剪贴板 → 视觉解析 → 输入框 / 选择项分类 → 坐标 → Maskat widget'}
        </span>
        <span className="pi-autosave">
          <span className="badge badge-ok">自动保存</span>
          <button type="button" className="btn btn-mini" onClick={exportState}>导出状态 JSON</button>
          <button type="button" className="btn btn-mini" onClick={() => fileRef.current?.click()}>导入</button>
          <input ref={fileRef} type="file" accept="application/json,.json" hidden
            onChange={(e) => { importState(e.target.files?.[0]); e.target.value = ''; }} />
        </span>
      </div>

      {error && <div className="alert alert-error pi-global-error">{error}</div>}
      {notice && <div className="alert alert-ok pi-global-notice">{notice}</div>}

      {view === 'excel'
        ? <div className="layout layout-excel"><WidgetExcelStudio config={config} onStatus={onStatus} /></div>
        : (
          <div className="layout">
            <aside className="side">
              <UploadPanel config={config} llm={llm} setLlm={setLlm} onUpload={onUpload}
                onDemo={onDemo} onParse={onParse} loading={loading} hasImage={Boolean(image)}
                image={image} error={error} notice={notice} />
            </aside>
            <main className="content">
              <ImageCanvas image={image} items={items} selectedId={selectedId}
                onSelect={setSelectedId} onAddBox={onAddBox} zoom={zoom} setZoom={setZoom}
                mode={mode} setMode={setMode} unit={unit} />
              <ItemTable key={resetKey} items={items} unit={unit} setUnit={setUnit}
                image={image || { width: 1, height: 1 }} selectedId={selectedId}
                onSelect={setSelectedId} onEdit={onEdit} onDelete={onDelete} onClear={onClear}
                dropped={dropped} />
              <OutputPanel image={image || {}} items={items} unit={unit} meta={meta} source={source} />
              <MaskatPanel image={image || { width: 1, height: 1 }} items={items} config={config} />
            </main>
          </div>
        )}

      <footer className="app-footer">
        项目坐标以原图像素为基准 · Maskat 标签与事件属性来自 /maskat-spec · 工作状态自动保存到本地（可导出 JSON）
      </footer>
    </div>
  );
}

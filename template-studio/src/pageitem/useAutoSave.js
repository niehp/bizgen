/**
 * 状态自动保存 hook：把“当前工作状态”以 JSON 写入 localStorage。
 *
 * 为什么用 localStorage 而不是每次落盘到服务端：
 * 1) 状态里可能含图片 dataURL（数 MB），写服务端要额外接口与存储，且刷新频繁；
 * 2) localStorage 同步、零依赖，刷新/重开浏览器即可恢复，满足“自动保存当前状态”；
 * 3) 仍提供“导出 JSON / 导入 JSON”按钮，便于跨设备迁移或纳入版本管理。
 *
 * 用法：const { save, restore, clear } = useAutoSave('pageitem:image:v1');
 */
import { useCallback } from 'react';

const PREFIX = 'pageitem:autosave:';

export default function useAutoSave(key) {
  const storageKey = `${PREFIX}${key}`;

  const save = useCallback((state) => {
    try {
      window.localStorage.setItem(storageKey, JSON.stringify({ version: 1, savedAt: Date.now(), state }));
      return true;
    } catch {
      // 超额（图片过大）或隐私模式下写入失败时静默降级，不阻断编辑。
      return false;
    }
  }, [storageKey]);

  const restore = useCallback(() => {
    try {
      const raw = window.localStorage.getItem(storageKey);
      if (!raw) return null;
      const parsed = JSON.parse(raw);
      return parsed?.state ?? null;
    } catch {
      return null;
    }
  }, [storageKey]);

  const clear = useCallback(() => {
    try { window.localStorage.removeItem(storageKey); } catch { /* 忽略 */ }
  }, [storageKey]);

  return { save, restore, clear, storageKey };
}

/** 导出状态为可下载的 JSON 文件；filename 由调用方给出。 */
export function downloadState(state, filename) {
  const blob = new Blob([`${JSON.stringify(state, null, 2)}\n`], { type: 'application/json;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = filename;
  document.body.appendChild(a);
  a.click();
  a.remove();
  setTimeout(() => URL.revokeObjectURL(url), 1500);
}

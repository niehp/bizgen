const request = async (path, options = {}) => {
  const res = await fetch(`/api${path}`, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  let json;
  try { json = await res.json(); } catch {
    throw new Error(`服务端返回异常（HTTP ${res.status}）`);
  }
  if (!res.ok || json.ok === false) {
    const e = new Error(json.error || `请求失败（HTTP ${res.status}）`);
    e.code = json.code;
    throw e;
  }
  return json;
};

export const getConfig = () => request('/config');

export const parseImage = (payload) => request('/parse', {
  method: 'POST',
  body: JSON.stringify(payload),
});

/** 程序化生成 Maskat 产物（页面默认在浏览器本地用同一套纯函数实时生成） */
export const buildMaskat = (payload) => request('/maskat/build', {
  method: 'POST',
  body: JSON.stringify(payload),
});

/** Excel 设计书：读取「画面項目」「画面レイアウト」→ widget 列表 */
export const parseExcelWidgets = (payload) => request('/excel/widgets', {
  method: 'POST',
  body: JSON.stringify(payload),
});

/** 内置示例设计书（零文件时演示用） */
export const getExcelSample = () => request('/excel/sample');

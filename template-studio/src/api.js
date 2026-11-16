const BASE = '/api';

async function request(path, options) {
  const res = await fetch(BASE + path, {
    headers: { 'Content-Type': 'application/json' },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({}));
    throw new Error(body.error || `HTTP ${res.status}`);
  }
  return res.json();
}

export const extractVariables = (template) =>
  request('/extract', { method: 'POST', body: JSON.stringify({ template }) });

export const renderTemplate = (template, values, transforms = {}, expansions = {}, options = {}) =>
  request('/render', {
    method: 'POST',
    body: JSON.stringify({ template, values, transforms, expansions, options }),
  });

export const importTableFile = (filename, data, sheet) =>
  request('/import', {
    method: 'POST',
    body: JSON.stringify({ filename, data, sheet }),
  });

export const listTemplates = () => request('/templates');

export const getTemplate = (id) => request(`/templates/${encodeURIComponent(id)}`);

export const saveTemplate = (name, content) =>
  request('/templates', { method: 'POST', body: JSON.stringify({ name, content }) });

export const deleteTemplate = (id) =>
  request(`/templates/${encodeURIComponent(id)}`, { method: 'DELETE' });

/* ---------------- DB メタデータ / SQL 生成 ---------------- */
export const listDbDrivers = () => request('/db/drivers');

export const getSampleMetadata = () => request('/db/sample');

export const testDbConnection = (connection) =>
  request('/db/test', { method: 'POST', body: JSON.stringify({ connection }) });

export const extractDbMetadata = (connection, name, save = true) =>
  request('/db/extract', {
    method: 'POST',
    body: JSON.stringify({ connection, name, save }),
  });

export const listDbMetadata = () => request('/db/metadata');

export const getDbMetadata = (id) => request(`/db/metadata/${encodeURIComponent(id)}`);

export const saveDbMetadata = (metadata, id) =>
  request('/db/metadata', { method: 'POST', body: JSON.stringify({ metadata, id }) });

export const deleteDbMetadata = (id) =>
  request(`/db/metadata/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const buildDbSql = (metadata, kind, config) =>
  request('/db/sql', {
    method: 'POST',
    body: JSON.stringify({ metadata, kind, config }),
  });

export const listDbTables = (id) =>
  request(`/db/metadata/${encodeURIComponent(id)}/tables`);

/* ---------------- Excel 列マッピング / INSERT 生成 ---------------- */
// 1 テーブル = 1 JSON。items は個別ファイル、groups は保存名ごとのまとまり。
export const listMappings = () => request('/mappings');

export const getMapping = (id) => request(`/mappings/${encodeURIComponent(id)}`);

export const getMappingGroup = (name) =>
  request(`/mappings/group/${encodeURIComponent(name)}`);

export const deleteMappingGroup = (name) =>
  request(`/mappings/group/${encodeURIComponent(name)}`, { method: 'DELETE' });

// replaceGroup: 読み込んだ保存名。名前変更や表の増減時に古い JSON を掃除する。
export const saveMapping = (mapping, id, replaceGroup) =>
  request('/mappings', {
    method: 'POST',
    body: JSON.stringify({ mapping, id, replaceGroup }),
  });

export const deleteMapping = (id) =>
  request(`/mappings/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const buildInsertSqlApi = (payload) =>
  request('/mapping/insert', { method: 'POST', body: JSON.stringify(payload) });

/* ---------------- Java 代码索引 / 全文检索 ---------------- */
export const listJavaSources = () => request('/java/sources');

export const listJavaFiles = (sourceId) =>
  request(`/java/files?sourceId=${encodeURIComponent(sourceId)}`);

export const deleteJavaSource = (id) =>
  request(`/java/sources/${encodeURIComponent(id)}`, { method: 'DELETE' });

export const scanJavaFolder = (name, files, group = '') =>
  request('/java/scan/folder', {
    method: 'POST',
    body: JSON.stringify({ name, files, group }),
  });

export const scanJavaJar = (name, base64, group = '') =>
  request('/java/scan/jar', {
    method: 'POST',
    body: JSON.stringify({ name, base64, group }),
  });

export const scanJavaPath = (dirPath, name = '', group = '') =>
  request('/java/scan/path', {
    method: 'POST',
    body: JSON.stringify({ path: dirPath, name, group }),
  });

export const setJavaSourceGroup = (id, group) =>
  request(`/java/sources/${encodeURIComponent(id)}/group`, {
    method: 'PUT',
    body: JSON.stringify({ group }),
  });

export const setJavaSourceName = (id, name) =>
  request(`/java/sources/${encodeURIComponent(id)}/name`, {
    method: 'PUT',
    body: JSON.stringify({ name }),
  });

export const renameJavaGroup = (from, to) =>
  request('/java/groups/rename', {
    method: 'PUT',
    body: JSON.stringify({ from, to }),
  });

export const getJavaFileContent = (fileId) =>
  request(`/java/files/${encodeURIComponent(fileId)}/content`);

export const searchJava = (payload) =>
  request('/java/search', { method: 'POST', body: JSON.stringify(payload) });

export const getJavaEntry = (id, context = 6, query = '', caseSensitive = false) =>
  request(`/java/entries/${encodeURIComponent(id)}?context=${context}&q=${encodeURIComponent(query)}&cs=${caseSensitive ? '1' : '0'}`);

import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(__dirname, '..', 'data');
const FILE = path.join(DATA_DIR, 'templates.json');

// 組み込みテンプレート（seed）は廃止。templates.json はユーザー作成分のみを保持する。
function ensure() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  if (!fs.existsSync(FILE)) fs.writeFileSync(FILE, '[]', 'utf8');
}

function read() {
  ensure();
  try {
    const data = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    return Array.isArray(data) ? data : [];
  } catch {
    return [];
  }
}

function write(items) {
  ensure();
  fs.writeFileSync(FILE, JSON.stringify(items, null, 2), 'utf8');
}

export function listTemplates() {
  return read().map(({ id, name, updatedAt }) => ({ id, name, updatedAt }));
}

export function getTemplate(id) {
  return read().find((t) => t.id === id) || null;
}

export function saveTemplate(name, content) {
  const items = read();
  const now = new Date().toISOString();
  const existing = items.find((t) => t.name === name);
  if (existing) {
    existing.content = content;
    existing.updatedAt = now;
    write(items);
    return { id: existing.id, name: existing.name, updatedAt: now, created: false };
  }
  const item = {
    id: 'tpl-' + Date.now().toString(36) + '-' + Math.random().toString(36).slice(2, 7),
    name,
    content,
    updatedAt: now,
  };
  items.unshift(item);
  write(items);
  return { id: item.id, name: item.name, updatedAt: now, created: true };
}

export function deleteTemplate(id) {
  const items = read();
  const next = items.filter((t) => t.id !== id);
  if (next.length === items.length) return false;
  write(next);
  return true;
}

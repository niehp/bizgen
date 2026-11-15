/**
 * 生成 SQL のダウンロードファイル名（ブラウザー / サーバー共用の純関数）
 *
 * ・ユーザーが入力した名前を優先し、空欄なら既定名を使う
 * ・拡張子 .sql を自動付与（既に付いている場合は二重に付けない）
 * ・Windows / macOS で使えない文字・末尾のドット/空白・制御文字を除去
 */

const FALLBACK_NAME = 'query';
const MAX_STEM_LENGTH = 180;

/** 選択中の SQL 種別と対象表から既定のファイル名（拡張子なし）を作る */
export function defaultSqlFileName(kind, tableRef = '') {
  const label = kind === 'update' ? 'update'
    : kind === 'delete' ? 'delete'
      : kind === 'insert' ? 'insert' : 'select_join';
  const last = String(tableRef || '').trim().split('.').pop() || '';
  const table = last.replace(/[^A-Za-z0-9_-]/g, '_').replace(/^_+|_+$/g, '') || FALLBACK_NAME;
  return `${table}_${label}`;
}

/**
 * 入力値 → 保存可能な .sql ファイル名。
 * "t_order" → "t_order.sql" / "t_order.SQL" → "t_order.sql" / "a/b:c" → "a_b_c.sql"
 * ".sql" 以外の拡張子（.txt など）が付いていても .sql を付与する。
 */
export function normalizeSqlFileName(input, fallback = FALLBACK_NAME) {
  const base = String(input ?? '').trim() || String(fallback ?? '').trim() || FALLBACK_NAME;
  const withoutExt = /\.sql$/i.test(base) ? base.slice(0, -4) : base;
  const stem = withoutExt
    .replace(/[\u0000-\u001f\u007f]/g, '_')
    .replace(/[\\/:*?"<>|]/g, '_')
    .replace(/\s+/g, ' ')
    .trim()
    .replace(/[. ]+$/g, '')
    .slice(0, MAX_STEM_LENGTH)
    .trim()
    .replace(/[. ]+$/g, '') || FALLBACK_NAME;
  return `${stem}.sql`;
}

/** 保存する SQL 本文。空でなければ末尾に改行を 1 つ付ける（表示内容は変えない） */
export function sqlFileContents(sql) {
  const text = String(sql ?? '');
  if (!text) return '';
  return text.endsWith('\n') ? text : `${text}\n`;
}

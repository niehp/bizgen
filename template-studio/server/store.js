import fs from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));
const DATA_DIR = path.join(__dirname, '..', 'data');
const FILE = path.join(DATA_DIR, 'templates.json');

const SEEDS = [
  {
    id: 'seed-service-method',
    name: 'Spring Boot Service メソッド',
    content: `---
title: Spring Boot Service メソッド
outputExt: java
description: 日系 FC モジュールの Service メソッド雛形
vars:
  packageName:
    label: パッケージ名
    default: com.example.fc.service
  className:
    label: クラス名
    default: OrgTreeService
    required: true
  methodName:
    label: メソッド名
    default: getOrgTree
    required: true
  requestType:
    label: リクエスト型
    default: OrgTreeRequest
  responseType:
    label: レスポンス型
    default: List<OrgNodeDto>
  author:
    label: 作成者
    default: 開発チーム
  apiCalls:
    label: 呼出 API（ホワイトリスト）
    type: code
    description: 改行区切り。生成コードのコメントに展開されます。
    default: |
      bapfOrgApi.getTree(req)
      fmsCommon.checkKengen(req)
  scope:
    label: メソッド公開範囲
    type: select
    options: [public, protected]
    default: public
  transactional:
    label: トランザクション付与
    type: boolean
    default: true
---
package {{packageName}};

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {{className}} / {{methodName}}
 * 作成: {{author}}
 *
 * 呼出 API（ホワイトリスト）:
{{apiCalls:code}}
 */
@Service
@RequiredArgsConstructor
public class {{className}} {

    // @Transactional 付与: {{transactional:boolean}}
    @Transactional
    {{scope:select}} {{responseType}} {{methodName}}({{requestType}} req) {
        // TODO: 詳細設計書に従って実装すること
        return null;
    }
}
`,
  },
  {
    id: 'seed-react-component',
    name: 'React コンポーネント',
    content: `---
title: React コンポーネント
outputExt: jsx
description: props と state を持つ関数コンポーネント
vars:
  componentName:
    label: コンポーネント名
    default: UserCard
    required: true
  propsList:
    label: props（改行区切り）
    type: list
    default: |
      name
      email
      role
  useStateHook:
    label: useState を使う
    type: boolean
    default: true
  memoWrap:
    label: React.memo で包む
    type: boolean
    default: false
  note:
    label: 補足コメント
    type: textarea
    default: 画面仕様書の項目定義に従うこと
---
import React from 'react';

/**
 * {{componentName}}
 * {{note}}
 */
const {{componentName}} = ({ {{propsList:list}} }) => {
  // useState 利用: {{useStateHook:boolean}}

  return (
    <section className="{{componentName}}">
      <h2>{name}</h2>
      <p>{email}</p>
      <small>{role}</small>
    </section>
  );
};

// React.memo 利用: {{memoWrap:boolean}}
export default {{componentName}};
`,
  },
  {
    id: 'seed-spec-task',
    name: 'AI向け <spec> タスク票',
    content: `---
title: AI向け構造化仕様タスク票
outputExt: xml
description: 仕様書コンパイラの出力をコード生成契約に変換する
vars:
  specId:
    label: Spec ID
    default: FC-ORG-001
    required: true
  className:
    label: 対象クラス
    default: OrgTreeService
  methodName:
    label: 対象メソッド
    default: getOrgTree
  version:
    label: 版数
    default: "1.2"
  approval:
    label: 承認状態
    type: select
    options: [未承認, レビュー中, 承認済]
    default: 承認済
  baseDate:
    label: 処理基準日
    type: date
    default: 2026-11-13
  role:
    label: 役割（やらないことも明記）
    type: textarea
    default: 1 メソッドのみ実装する。他ファイルは変更しないこと。
  constraints:
    label: 制約（MUST / MUST NOT）
    type: code
    default: |
      MUST     署名を詳細設計書と逐字一致させること
      MUST NOT ホワイトリスト外の二方庫メソッドを呼び出してはならない
  apiList:
    label: API ホワイトリスト（改行区切り）
    type: list
    default: |
      BapfOrgApi.getTree
      FmsCommon.checkKengen
  acceptance:
    label: 受入れ基準
    type: textarea
    default: 署名一致／呼出集合が白名単の部分集合／単体テスト結果を添付
---
<spec id="{{specId}}" version="{{version}}" 承認状態="{{approval}}">
  <役割>{{className}}#{{methodName}} : {{role}}</役割>
  <前提>処理基準日 {{baseDate}}。L0 は CLAUDE.md を参照し複製しないこと。</前提>
  <制約>
{{constraints:code}}
  </制約>
  <APIホワイトリスト>{{apiList:list}}</APIホワイトリスト>
  <受入れ基準>{{acceptance}}</受入れ基準>
</spec>
`,
  },
  {
    id: 'seed-transform-demo',
    name: '変数変換デモ（命名規則・連番）',
    content: `---
title: 変数変換デモ
outputExt: java
description: 1 つの入力からクラス名 / フィールド名 / DB 名 / 連番を自動生成する
vars:
  entity:
    label: エンティティ名（snake_case で入力）
    default: org_tree_node
    required: true
  tableName:
    label: テーブル名
    default: MST_ORG_TREE
    transform: lower
  fields:
    label: 項目一覧（改行区切り）
    type: list
    joiner: ", "
    default: |
      会社コード
      組織コード
      親組織コード
      基準日
  edaNo:
    label: 枝番（連番）
    type: sequence
    sequence:
      start: 1
      end: 4
      step: 1
      pad: 3
      joiner: ", "
---
/**
 * {{entity | pascal}} : {{entity}}
 * テーブル: {{tableName}}
 */
public class {{entity | pascal}} {

    private final {{entity | camel}}Repository repository;

    // カラム名: {{entity | snake}}_cd
    // 定数名: {{entity | constant}}_PREFIX

    /** 枝番: {{edaNo}} */
    public void validate() {
        // 行番号: {{no | counter(pad=3)}} / {{no | counter(pad=3)}}
        // チェック項目（連番付き）
{{fields | seq(start=1, pad=2, format='        // {n}. {v} を検証する')}}
    }
}
`,
  },
  {
    id: 'seed-range-expansion',
    name: '範囲展開デモ（1〜10 ＆ 自動生成変数）',
    content: `---
title: 範囲展開デモ
outputExt: java
description: 1 変数を 1〜10 に展開して 10 件すべて表示。{{itemUpper}} / {{itemLower}} は自動生成変数。注釈は出力しない
keepFirstCase: true
vars:
  item:
    label: 項目名（範囲展開します）
    default: orgTree
    expand:
      enabled: true
      from: 1
      to: 10
      step: 1
      pad: 2
      prefix: item
      values:
        "3": OrgTree
        "4": orgTree
  pkg:
    label: パッケージ
    default: com.example.fc
---
package {{pkg}};

public class {{itemUpper}}Dto {

    private String {{itemLower}};
    private String {{item | snake}};
    private String {{item | camel}};

    public {{itemUpper}} get{{itemUpper}}() {
        return this.{{itemLower}};
    }

    public void set{{itemUpper}}(String {{itemLower}}) {
        this.{{itemLower}} = {{itemLower}};
    }
}
`,
  },
  {
    id: 'seed-multi-values',
    name: '複数値リストデモ（1 変数に複数の値）',
    content: `---
title: 複数値リストデモ
outputExt: java
description: 1 つの変数に複数の値を入力し、値 1 つにつき 1 件のコードを生成する。注釈・ダウンロードなし
keepFirstCase: true
vars:
  item:
    label: 項目名（複数値を入力します）
    default: OrgTree
    multi:
      values:
        - OrgTree
        - orgTree
        - UserProfile
        - userProfile
        - DeptNode
  pkg:
    label: パッケージ
    default: com.example.fc
---
package {{pkg}};

public class {{itemUpper}}Dto {

    private String {{itemLower}};
    private String {{item | snake}};
    private String {{item | camel}};

    public {{itemUpper}} get{{itemUpper}}() {
        return this.{{itemLower}};
    }

    public void set{{itemUpper}}(String {{itemLower}}) {
        this.{{itemLower}} = {{itemLower}};
    }
}
`,
  },
  {
    id: 'seed-table-input',
    name: 'TABLE 入力デモ（1 変数 = 1 列 / 1 行 = 1 件）',
    content: `---
title: TABLE 入力デモ
outputExt: java
description: TABLE の各行から 1 件ずつ生成。先頭大文字 / 小文字の自動生成変数は行ごとに算出。注釈・ダウンロードなし
keepFirstCase: true
vars:
  item:
    label: 項目名（先頭大小文字を混在）
    default: OrgTree
  pkg:
    label: パッケージ
    default: com.example.fc
  suffix:
    label: クラス接尾辞
    default: Dto
table:
  enabled: true
  rows:
    - { item: OrgTree, pkg: com.example.fc.master, suffix: Dto }
    - { item: orgTree, pkg: com.example.fc.master, suffix: Dto }
    - { item: UserProfile, pkg: com.example.fc.user, suffix: Dto }
    - { item: userProfile, pkg: com.example.fc.user, suffix: Dto }
    - { item: DeptNode, pkg: com.example.fc.org, suffix: Entity }
---
package {{pkg}};

public class {{itemUpper}}{{suffix}} {

    private String {{itemLower}};

    public {{itemUpper}}{{suffix}}() {
        this.{{itemLower}} = "";
    }

    public String get{{itemUpper}}() {
        return this.{{itemLower}};
    }

    public void set{{itemUpper}}(String {{itemLower}}) {
        this.{{itemLower}} = {{itemLower}};
    }
}
`,
  },
];

function ensure() {
  if (!fs.existsSync(DATA_DIR)) fs.mkdirSync(DATA_DIR, { recursive: true });
  const now = new Date().toISOString();
  if (!fs.existsSync(FILE)) {
    fs.writeFileSync(
      FILE,
      JSON.stringify(SEEDS.map((s) => ({ ...s, updatedAt: now })), null, 2),
      'utf8'
    );
    return;
  }
  // 組み込みテンプレートは常にコード側を真実源として同期する。
  // id が tpl-* のユーザー作成分は一切触らない。
  try {
    const items = JSON.parse(fs.readFileSync(FILE, 'utf8'));
    if (!Array.isArray(items)) return;
    let changed = false;
    for (const seed of SEEDS) {
      const hit = items.find((t) => t.id === seed.id);
      if (!hit) {
        items.push({ ...seed, updatedAt: now });
        changed = true;
      } else if (hit.content !== seed.content || hit.name !== seed.name) {
        hit.content = seed.content;
        hit.name = seed.name;
        hit.updatedAt = now;
        changed = true;
      }
    }
    if (changed) fs.writeFileSync(FILE, JSON.stringify(items, null, 2), 'utf8');
  } catch {
    // 破損している場合は書き換えず、read() 側のフォールバックに任せる
  }
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

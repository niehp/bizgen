import { parseJavaFile } from './java-parser.js';
const src = `package p;
/** 服务类 */
public class A {
  /** 仓库字段 */
  private final Repo repository;
  public static final String PREFIX = "ORD-";
  private int a, b;
  public void foo() { int local = 1; }
}
`;
const r = parseJavaFile('A.java', src);
console.log('fields', r.fields.map((f) => [f.name, f.type, f.comment, f.startLine]));
console.log('entries', r.entries.map((e) => [e.kind, e.fieldName || '', e.methodName || '', e.fieldComment || '']));

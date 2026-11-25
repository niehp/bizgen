package com.walk_nie.bizgen.agent.codeintel;

import java.util.ArrayList;
import java.util.List;

/**
 * JDT AST から抽出した Java 構造の強型モデル。
 * SQLite へ格納する各行と 1 対 1 に対応し、推論結果を一切含まない。
 */
public final class JavaIndexModel {

    private JavaIndexModel() {
    }

    /** 1 つの .java ファイル。ソース本文も索引に保持し、オフライン検索を可能にする。 */
    public static final class FileRecord {
        public final long id;
        public final String path;
        public final String packageName;
        public final long mtime;
        public final long size;
        public final String sha256;
        public final String source;
        public final int lineCount;

        public FileRecord(long id, String path, String packageName, long mtime, long size,
                          String sha256, String source, int lineCount) {
            this.id = id;
            this.path = path;
            this.packageName = packageName == null ? "" : packageName;
            this.mtime = mtime;
            this.size = size;
            this.sha256 = sha256 == null ? "" : sha256;
            this.source = source == null ? "" : source;
            this.lineCount = lineCount;
        }
    }

    /** クラス / インタフェース / enum / アノテーション。 */
    public static final class ClassRecord {
        public final long id;
        public final long fileId;
        public final String fqcn;
        public final String simpleName;
        public final String kind;
        public final String modifiers;
        public final String superClass;
        public final String interfaces;
        public final String javadoc;
        public final int startLine;
        public final int endLine;

        public ClassRecord(long id, long fileId, String fqcn, String simpleName, String kind,
                           String modifiers, String superClass, String interfaces, String javadoc,
                           int startLine, int endLine) {
            this.id = id;
            this.fileId = fileId;
            this.fqcn = fqcn == null ? "" : fqcn;
            this.simpleName = simpleName == null ? "" : simpleName;
            this.kind = kind == null ? "" : kind;
            this.modifiers = modifiers == null ? "" : modifiers;
            this.superClass = superClass == null ? "" : superClass;
            this.interfaces = interfaces == null ? "" : interfaces;
            this.javadoc = javadoc == null ? "" : javadoc;
            this.startLine = startLine;
            this.endLine = endLine;
        }
    }

    /** メソッド / コンストラクタ。本文も保持して類似メソッド判定に使う。 */
    public static final class MethodRecord {
        public final long id;
        public final long classId;
        public final long fileId;
        public final String name;
        public final String signature;
        public final String returnType;
        public final String params;
        public final String modifiers;
        public final String javadoc;
        public final int startLine;
        public final int endLine;
        public final String body;
        public final String bodyHash;
        /** 所属クラスの FQCN。DB 登録時に classId へ解決する。 */
        public final String ownerClass;

        public MethodRecord(long id, long classId, long fileId, String name, String signature,
                            String returnType, String params, String modifiers, String javadoc,
                            int startLine, int endLine, String body, String bodyHash, String ownerClass) {
            this.id = id;
            this.classId = classId;
            this.fileId = fileId;
            this.name = name == null ? "" : name;
            this.signature = signature == null ? "" : signature;
            this.returnType = returnType == null ? "" : returnType;
            this.params = params == null ? "" : params;
            this.modifiers = modifiers == null ? "" : modifiers;
            this.javadoc = javadoc == null ? "" : javadoc;
            this.startLine = startLine;
            this.endLine = endLine;
            this.body = body == null ? "" : body;
            this.bodyHash = bodyHash == null ? "" : bodyHash;
            this.ownerClass = ownerClass == null ? "" : ownerClass;
        }

        /** 検索結果表示用の 1 行見出し。 */
        public String label() {
            return signature.isEmpty() ? name + "(...)" : signature;
        }
    }

    /** フィールド（enum 定数を含む）。 */
    public static final class FieldRecord {
        public final long id;
        public final long classId;
        public final String name;
        public final String type;
        public final String modifiers;
        public final String javadoc;
        public final int startLine;
        /** 所属クラスの FQCN。DB 登録時に classId へ解決する。 */
        public final String ownerClass;

        public FieldRecord(long id, long classId, String name, String type, String modifiers,
                           String javadoc, int startLine, String ownerClass) {
            this.id = id;
            this.classId = classId;
            this.name = name == null ? "" : name;
            this.type = type == null ? "" : type;
            this.modifiers = modifiers == null ? "" : modifiers;
            this.javadoc = javadoc == null ? "" : javadoc;
            this.startLine = startLine;
            this.ownerClass = ownerClass == null ? "" : ownerClass;
        }
    }

    /** import 文。 */
    public static final class ImportRecord {
        public final long id;
        public final long fileId;
        public final String name;
        public final boolean staticImport;
        public final boolean wildcard;

        public ImportRecord(long id, long fileId, String name, boolean staticImport, boolean wildcard) {
            this.id = id;
            this.fileId = fileId;
            this.name = name == null ? "" : name;
            this.staticImport = staticImport;
            this.wildcard = wildcard;
        }
    }

    /** メソッド呼び出し（呼び出し関係のエッジ）。 */
    public static final class CallRecord {
        public final long id;
        /** 呼び出し元メソッド名と所属クラス。行番号から決定論的に割り当てる。 */
        public final String callerName;
        public final String callerOwner;
        public final long fileId;
        public final String calleeName;
        public final String qualifier;
        public final int line;
        public final String expression;

        public CallRecord(long id, String callerName, String callerOwner, long fileId,
                          String calleeName, String qualifier, int line, String expression) {
            this.id = id;
            this.callerName = callerName == null ? "" : callerName;
            this.callerOwner = callerOwner == null ? "" : callerOwner;
            this.fileId = fileId;
            this.calleeName = calleeName == null ? "" : calleeName;
            this.qualifier = qualifier == null ? "" : qualifier;
            this.line = line;
            this.expression = expression == null ? "" : expression;
        }
    }

    /** クラス / メソッド / フィールド / import の名前出現（参照検索用）。 */
    public static final class RefRecord {
        public final long id;
        public final long fileId;
        public final long classId;
        public final long methodId;
        public final String name;
        public final int line;
        public final String kind;

        public RefRecord(long id, long fileId, long classId, long methodId, String name,
                         int line, String kind) {
            this.id = id;
            this.fileId = fileId;
            this.classId = classId;
            this.methodId = methodId;
            this.name = name == null ? "" : name;
            this.line = line;
            this.kind = kind == null ? "" : kind;
        }
    }

    /** 1 ファイル分の解析結果。 */
    public static final class ParsedFile {
        public final FileRecord file;
        public final List<ClassRecord> classes = new ArrayList<ClassRecord>();
        public final List<MethodRecord> methods = new ArrayList<MethodRecord>();
        public final List<FieldRecord> fields = new ArrayList<FieldRecord>();
        public final List<ImportRecord> imports = new ArrayList<ImportRecord>();
        public final List<CallRecord> calls = new ArrayList<CallRecord>();
        public final List<RefRecord> refs = new ArrayList<RefRecord>();

        public ParsedFile(FileRecord file) {
            this.file = file;
        }
    }
}

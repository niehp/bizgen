package com.walk_nie.bizgen.agent.mdgen;

import com.walk_nie.bizgen.agent.ast.CompileGate;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.PackageDeclaration;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.comments.JavadocComment;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 確定的アセンブラ：シグネチャは MethodSpec、本体とコメント文は MethodBody から取り、
 * JavaParser で完全なクラスに組み立てる。三つのモードを支持する：
 *   NEW_FILE      … 新規 Java ファイルを作る
 *   ADD_METHOD    … 既存ファイルにメソッドを追加する
 *   MODIFY_METHOD … 既存ファイルのメソッドを差し替える
 *
 * <p>三層の標準 Javadoc を生成する（すべて日本語「何をするか／なぜ」）：
 * <ul>
 *   <li>パッケージ → package-info.java（別ファイルとして返す）</li>
 *   <li>クラス     → setJavadocComment</li>
 *   <li>メソッド   → setJavadocComment（@param 名は実引数から生成）</li>
 * </ul>
 * タグの形は JavadocBuilder が決め、モデルは説明文だけを書く——なぜなら
 * JavaParser は @param 名のズレを検査しないため、モデル採用だと誤りが見逃されるから。
 *
 * <p>組み立て後は「引数コメント数」「API ホワイトリスト」「javac」のゲートを通す——
 * コンパイルできないコードがレビューに流れるのを止めるため。
 */
public final class SourceAssembler {

    /** "ClassName.method(" 形式の呼び出しを拾う（ホワイトリスト検査用）。 */
    private static final Pattern API_CALL = Pattern.compile(
            "\\b([A-Z][A-Za-z0-9_]*)\\.([a-zA-Z_][A-Za-z0-9_]*)\\s*\\(");

    public record AssemblyResult(String source, String packageInfo, int appliedStatements, String mode) {
        /** 旧 4 引数との互換（packageInfo 無しとみなす）。 */
        public AssemblyResult(String source, int appliedStatements, String mode) {
            this(source, null, appliedStatements, mode);
        }
    }

    /** モードに応じて組み立てを振り分ける。 */
    public AssemblyResult assemble(MethodSpec spec, MethodBody body) {
        return switch (spec.operation()) {
            case NEW_FILE -> assembleNewFile(spec, body);
            case ADD_METHOD -> assembleAddMethod(spec, body);
            case MODIFY_METHOD -> assembleModifyMethod(spec, body);
        };
    }

    /** 新規ファイル：空の CU からクラスごと作る。 */
    private AssemblyResult assembleNewFile(MethodSpec spec, MethodBody body) {
        CompilationUnit cu = new CompilationUnit();
        if (hasText(spec.packageName())) cu.setPackageDeclaration(spec.packageName());
        addImports(cu, body.imports());

        ClassOrInterfaceDeclaration clazz = cu.addClass(spec.className()).setPublic(true);
        clazz.setJavadocComment(JavadocBuilder.classJavadoc(body));   // クラス注释
        MethodDeclaration method = newMethod(spec, body);
        clazz.addMember(method);
        return finish(spec, body, cu, "NEW_FILE");
    }

    /** 既存ファイルにメソッドを追加する。同名メソッドが有れば衝突を避けて拒否する。 */
    private AssemblyResult assembleAddMethod(MethodSpec spec, MethodBody body) {
        CompilationUnit cu = parseExisting(spec.targetFile());
        ClassOrInterfaceDeclaration clazz = findClass(cu, spec.className())
                .orElseThrow(() -> new IllegalArgumentException(
                        "既存ファイルに対象クラスが無い: " + spec.className() + " @ " + spec.targetFile()));
        boolean exists = clazz.getMethodsByName(spec.methodName()).stream()
                .anyMatch(m -> m.getParameters().size() == spec.parameters().size());
        if (exists) {
            throw new IllegalArgumentException(
                    "追加先に同名メソッドが既に存在する（MODIFY_METHOD を使うこと）: " + spec.methodName());
        }
        addImports(cu, body.imports());
        clazz.addMember(newMethod(spec, body));
        return finish(spec, body, cu, "ADD_METHOD");
    }

    /** 既存メソッドを差し替える。見つからなければ拒否（誤生成を防ぐため）。 */
    private AssemblyResult assembleModifyMethod(MethodSpec spec, MethodBody body) {
        CompilationUnit cu = parseExisting(spec.targetFile());
        ClassOrInterfaceDeclaration clazz = findClass(cu, spec.className())
                .orElseThrow(() -> new IllegalArgumentException(
                        "既存ファイルに対象クラスが無い: " + spec.className() + " @ " + spec.targetFile()));
        MethodDeclaration target = clazz.getMethodsByName(spec.methodName()).stream()
                .filter(m -> m.getParameters().size() == spec.parameters().size())
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "差し替え対象メソッドが無い: " + spec.methodName()
                                + "（引数 " + spec.parameters().size() + " 個）"));
        clazz.setJavadocComment(JavadocBuilder.classJavadoc(body));   // クラス注释も更新
        applyBodyAndComment(target, spec, body);
        addImports(cu, body.imports());
        return finish(spec, body, cu, "MODIFY_METHOD");
    }

    /** 新規メソッドを組み立てる（シグネチャ=確定側、本体+コメント=LLM 側）。 */
    private MethodDeclaration newMethod(MethodSpec spec, MethodBody body) {
        MethodDeclaration method = new MethodDeclaration();
        method.setPublic(true).setName(spec.methodName()).setType(spec.returnType());
        for (String param : spec.parameters()) method.addParameter(parseParameter(param));
        applyBodyAndComment(method, spec, body);
        return method;
    }

    /** メソッド本体と標準 Javadoc を適用する（新規・修正で共通）。 */
    private void applyBodyAndComment(MethodDeclaration method, MethodSpec spec, MethodBody body) {
        // メソッド Javadoc：@param 名は MethodSpec の実引数から生成（モデルには名前を書かせない）
        method.setJavadocComment(JavadocBuilder.methodJavadoc(spec, body));
        method.setType(spec.returnType());
        method.getParameters().clear();
        for (String param : spec.parameters()) method.addParameter(parseParameter(param));

        BlockStmt block = new BlockStmt();
        List<String> statements = body.statements() == null ? List.of() : body.statements();
        for (int i = 0; i < statements.size(); i++) {
            String text = statements.get(i);
            if (text == null || text.isBlank()) continue;
            Statement st = parseStatement(text);
            String comment = body.commentAt(i);
            if (hasText(comment)) st.setLineComment(comment); // 文末行コメント（なぜそう書くか）
            block.addStatement(st);
        }
        method.setBody(block);
    }

    /** package-info.java のソースを生成する（パッケージが無い／説明が無いときは空）。 */
    public Optional<String> packageInfoSource(MethodSpec spec, MethodBody body) {
        if (!hasText(spec.packageName())) return Optional.empty();
        if (!hasText(body.packageComment())) return Optional.empty();
        CompilationUnit cu = new CompilationUnit();
        cu.setPackageDeclaration(spec.packageName());
        PackageDeclaration pd = cu.getPackageDeclaration().orElseThrow();
        pd.setComment(new JavadocComment(JavadocBuilder.packageJavadoc(body)));
        return Optional.of(cu.toString());
    }

    /** 共通の後処理：ホワイトリスト → コンパイル → 結果。 */
    private AssemblyResult finish(MethodSpec spec, MethodBody body, CompilationUnit cu, String mode) {
        checkWhitelist(spec, body);
        String source = cu.toString();
        CompileGate.CompileResult compile = new CompileGate().compile(spec.className(), source);
        if (compile.failed()) {
            throw new IllegalArgumentException("生成コードのコンパイルに失敗、落盤を拒否:\n  "
                    + String.join("\n  ", compile.diagnostics()));
        }
        int count = body.statements() == null ? 0 : body.statements().size();
        return new AssemblyResult(source, count, mode);
    }

    /** 重複を避けつつ import を追加する（既存ファイルの import は保持）。 */
    private void addImports(CompilationUnit cu, List<String> imports) {
        if (imports == null) return;
        for (String imp : imports) {
            if (!hasText(imp)) continue;
            String name = imp.strip();
            boolean exists = cu.getImports().stream()
                    .anyMatch(i -> i.getNameAsString().equals(name));
            if (!exists) cu.addImport(name);
        }
    }

    /** 既存 .java を UTF-8 で読み、JavaParser で構文木にする。 */
    private CompilationUnit parseExisting(String file) {
        try {
            String source = Files.readString(Path.of(file), StandardCharsets.UTF_8);
            return StaticJavaParser.parse(source);
        } catch (IOException e) {
            throw new IllegalArgumentException("既存ファイルが読めない: " + file, e);
        }
    }

    /** 指定名のクラスを探す（ネスト型にも対応するため TypeDeclaration で探す）。 */
    private Optional<ClassOrInterfaceDeclaration> findClass(CompilationUnit cu, String name) {
        return cu.findAll(ClassOrInterfaceDeclaration.class).stream()
                .filter(c -> c.getNameAsString().equals(name))
                .findFirst();
    }

    /** ホワイトリスト外の "类名.方法(" 呼び出しがあれば拒否する。 */
    private void checkWhitelist(MethodSpec spec, MethodBody body) {
        if (spec.allowedApis().isEmpty()) return; // 仕様に宣言が無いときはゲートをスキップ
        Set<String> allowed = new HashSet<>();
        for (String api : spec.allowedApis()) {
            allowed.add(api.strip());
            int dot = api.lastIndexOf('.');
            if (dot > 0) allowed.add(api.substring(dot + 1).strip()); // 裸のメソッド名も許可
        }
        List<String> statements = body.statements() == null ? List.of() : body.statements();
        Matcher m = API_CALL.matcher(String.join("\n", statements));
        List<String> bad = new ArrayList<>();
        while (m.find()) {
            String call = m.group(1) + "." + m.group(2);
            if (!allowed.contains(call) && !allowed.contains(m.group(2))) bad.add(call);
        }
        if (!bad.isEmpty()) {
            throw new SecurityException("ホワイトリスト外 API に該当、処理を拒否: " + bad);
        }
    }

    private Parameter parseParameter(String text) {
        try {
            return StaticJavaParser.parseParameter(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("引数宣言が Java として不正: " + text, e);
        }
    }

    private Statement parseStatement(String text) {
        try {
            return StaticJavaParser.parseStatement(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("本体の文が Java として不正: " + text, e);
        }
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}

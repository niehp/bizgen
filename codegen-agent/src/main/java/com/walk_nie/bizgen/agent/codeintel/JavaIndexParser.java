package com.walk_nie.bizgen.agent.codeintel;

import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.dom.AST;
import org.eclipse.jdt.core.dom.ASTNode;
import org.eclipse.jdt.core.dom.ASTParser;
import org.eclipse.jdt.core.dom.ASTVisitor;
import org.eclipse.jdt.core.dom.AbstractTypeDeclaration;
import org.eclipse.jdt.core.dom.AnnotationTypeDeclaration;
import org.eclipse.jdt.core.dom.ClassInstanceCreation;
import org.eclipse.jdt.core.dom.CompilationUnit;
import org.eclipse.jdt.core.dom.EnumConstantDeclaration;
import org.eclipse.jdt.core.dom.EnumDeclaration;
import org.eclipse.jdt.core.dom.FieldDeclaration;
import org.eclipse.jdt.core.dom.ImportDeclaration;
import org.eclipse.jdt.core.dom.MethodDeclaration;
import org.eclipse.jdt.core.dom.MethodInvocation;
import org.eclipse.jdt.core.dom.Modifier;
import org.eclipse.jdt.core.dom.PackageDeclaration;
import org.eclipse.jdt.core.dom.SimpleName;
import org.eclipse.jdt.core.dom.SingleVariableDeclaration;
import org.eclipse.jdt.core.dom.SuperMethodInvocation;
import org.eclipse.jdt.core.dom.TypeDeclaration;
import org.eclipse.jdt.core.dom.VariableDeclarationFragment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * JDT AST による Java 構造抽出。推定は行わず、AST 上の事実だけを強型モデルへ写す。
 * 解決済みバインディングは使わないため、クラスパスの無い既存プロジェクトでも動作する。
 */
public final class JavaIndexParser {

    /** 1 ファイルを解析する。source は UTF-8 文字列であること。 */
    public JavaIndexModel.ParsedFile parse(String relativePath, String source, long mtime) {
        ASTParser parser = ASTParser.newParser(AST.JLS12);
        parser.setKind(ASTParser.K_COMPILATION_UNIT);
        @SuppressWarnings("unchecked")
        Map<String, String> options = new HashMap<String, String>(JavaCore.getOptions());
        options.put(JavaCore.COMPILER_SOURCE, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_COMPLIANCE, JavaCore.VERSION_1_8);
        options.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM, JavaCore.VERSION_1_8);
        // JDT は setSource より前にコンパイラオプションを設定する必要がある。
        // ここを逆にすると構文エラー扱いになり、修飾子フラグが壊れる。
        parser.setCompilerOptions(options);
        parser.setSource(source.toCharArray());
        parser.setUnitName(relativePath.replace('\\', '/'));
        parser.setResolveBindings(false);
        CompilationUnit unit = (CompilationUnit) parser.createAST(null);

        String packageName = "";
        PackageDeclaration packageDeclaration = unit.getPackage();
        if (packageDeclaration != null) {
            packageName = packageDeclaration.getName().getFullyQualifiedName();
        }
        JavaIndexModel.FileRecord file = new JavaIndexModel.FileRecord(
                0, relativePath, packageName, mtime, source.getBytes(StandardCharsets.UTF_8).length,
                sha256(source), source, lineCount(source));
        JavaIndexModel.ParsedFile parsed = new JavaIndexModel.ParsedFile(file);

        for (Object item : unit.imports()) {
            if (!(item instanceof ImportDeclaration)) {
                continue;
            }
            ImportDeclaration declaration = (ImportDeclaration) item;
            parsed.imports.add(new JavaIndexModel.ImportRecord(0, 0,
                    declaration.getName().getFullyQualifiedName(),
                    declaration.isStatic(), declaration.isOnDemand()));
        }

        // 型は入れ子也包括して再帰的に抽出する。
        for (Object item : unit.types()) {
            if (item instanceof AbstractTypeDeclaration) {
                collectType((AbstractTypeDeclaration) item, packageName, parsed, source, unit);
            }
        }

        // 呼び出しと名前出現はファイル全体を 1 回だけ走査する。
        collectUsages(unit, parsed);
        assignCallers(parsed);
        return parsed;
    }

    private void collectType(AbstractTypeDeclaration type, String outerName,
                             JavaIndexModel.ParsedFile parsed, String source, CompilationUnit unit) {
        String simpleName = type.getName().getIdentifier();
        String fqcn = outerName.isEmpty() ? simpleName : outerName + "." + simpleName;
        int startLine = line(unit, type.getStartPosition());
        int endLine = line(unit, type.getStartPosition() + type.getLength() - 1);
        JavaIndexModel.ClassRecord classRecord = new JavaIndexModel.ClassRecord(
                0, 0, fqcn, simpleName, kindOf(type), modifiers(type.modifiers()),
                superClassOf(type), interfacesOf(type), javadoc(type.getJavadoc(), source),
                startLine, endLine);
        parsed.classes.add(classRecord);

        List<JavaIndexModel.MethodRecord> methods = new ArrayList<JavaIndexModel.MethodRecord>();
        List<JavaIndexModel.FieldRecord> fields = new ArrayList<JavaIndexModel.FieldRecord>();

        for (Object item : type.bodyDeclarations()) {
            if (item instanceof MethodDeclaration) {
                MethodDeclaration method = (MethodDeclaration) item;
                String name = method.getName().getIdentifier();
                String returnType = method.isConstructor()
                        ? "" : (method.getReturnType2() == null ? "void" : method.getReturnType2().toString());
                List<String> params = new ArrayList<String>();
                for (Object parameter : method.parameters()) {
                    if (parameter instanceof SingleVariableDeclaration) {
                        SingleVariableDeclaration declaration = (SingleVariableDeclaration) parameter;
                        params.add(declaration.getType().toString() + " " + declaration.getName().getIdentifier());
                    }
                }
                String paramText = join(params, ", ");
                String signature = modifiers(method.modifiers())
                        + (returnType.isEmpty() ? "" : returnType + " ")
                        + name + "(" + paramText + ")";
                int methodStart = line(unit, method.getStartPosition());
                int methodEnd = line(unit, method.getStartPosition() + method.getLength() - 1);
                String body = sourceFragment(source, method.getStartPosition(), method.getLength());
                methods.add(new JavaIndexModel.MethodRecord(0, 0, 0, name, signature, returnType,
                        paramText, modifiers(method.modifiers()), javadoc(method.getJavadoc(), source),
                        methodStart, methodEnd, body, sha256(body), fqcn));
            } else if (item instanceof FieldDeclaration) {
                FieldDeclaration field = (FieldDeclaration) item;
                String fieldType = field.getType().toString();
                String docs = javadoc(field.getJavadoc(), source);
                int fieldLine = line(unit, field.getStartPosition());
                for (Object fragment : field.fragments()) {
                    if (fragment instanceof VariableDeclarationFragment) {
                        String name = ((VariableDeclarationFragment) fragment).getName().getIdentifier();
                        fields.add(new JavaIndexModel.FieldRecord(0, 0, name, fieldType,
                                modifiers(field.modifiers()), docs, fieldLine, fqcn));
                    }
                }
            } else if (item instanceof EnumConstantDeclaration) {
                EnumConstantDeclaration constant = (EnumConstantDeclaration) item;
                fields.add(new JavaIndexModel.FieldRecord(0, 0,
                        constant.getName().getIdentifier(), simpleName, "public static final",
                        javadoc(constant.getJavadoc(), source), line(unit, constant.getStartPosition()), fqcn));
            } else if (item instanceof AbstractTypeDeclaration) {
                collectType((AbstractTypeDeclaration) item, fqcn, parsed, source, unit);
            }
        }
        // この型に属するメソッド/フィールドを、入れ子型の分も後から classId で束ねられるよう順に積む。
        parsed.methods.addAll(methods);
        parsed.fields.addAll(fields);
        for (JavaIndexModel.MethodRecord method : methods) {
            parsed.refs.add(new JavaIndexModel.RefRecord(0, 0, 0, 0, method.name,
                    method.startLine, "METHOD_DECL:" + fqcn));
        }
        for (JavaIndexModel.FieldRecord field : fields) {
            parsed.refs.add(new JavaIndexModel.RefRecord(0, 0, 0, 0, field.name,
                    field.startLine, "FIELD_DECL:" + fqcn));
        }
    }

    /** メソッド呼び出しと全識別子の出現位置を記録する。 */
    private void collectUsages(CompilationUnit unit, final JavaIndexModel.ParsedFile parsed) {
        unit.accept(new ASTVisitor() {
            @Override
            public boolean visit(MethodInvocation node) {
                parsed.calls.add(new JavaIndexModel.CallRecord(0, "", "", 0,
                        node.getName().getIdentifier(),
                        node.getExpression() == null ? "" : node.getExpression().toString(),
                        line(unit, node.getStartPosition()), node.toString()));
                return true;
            }

            @Override
            public boolean visit(SuperMethodInvocation node) {
                parsed.calls.add(new JavaIndexModel.CallRecord(0, "", "", 0,
                        node.getName().getIdentifier(), "super",
                        line(unit, node.getStartPosition()), node.toString()));
                return true;
            }

            @Override
            public boolean visit(ClassInstanceCreation node) {
                parsed.calls.add(new JavaIndexModel.CallRecord(0, "", "", 0,
                        node.getType().toString(), "new",
                        line(unit, node.getStartPosition()), node.toString()));
                return true;
            }

            @Override
            public boolean visit(SimpleName node) {
                parsed.refs.add(new JavaIndexModel.RefRecord(0, 0, 0, 0,
                        node.getIdentifier(), line(unit, node.getStartPosition()), "NAME"));
                return true;
            }
        });
    }

    /** 呼び出し行を含むメソッドを呼び出し元とする。範囲が最も狭いものを選ぶ（入れ子クラス対応）。 */
    private static void assignCallers(JavaIndexModel.ParsedFile parsed) {
        List<JavaIndexModel.CallRecord> assigned = new ArrayList<JavaIndexModel.CallRecord>();
        for (JavaIndexModel.CallRecord call : parsed.calls) {
            JavaIndexModel.MethodRecord best = null;
            for (JavaIndexModel.MethodRecord method : parsed.methods) {
                if (method.startLine <= call.line && call.line <= method.endLine) {
                    if (best == null || (method.endLine - method.startLine)
                            < (best.endLine - best.startLine)) {
                        best = method;
                    }
                }
            }
            assigned.add(new JavaIndexModel.CallRecord(0,
                    best == null ? "" : best.name,
                    best == null ? "" : best.ownerClass,
                    call.fileId, call.calleeName, call.qualifier, call.line, call.expression));
        }
        parsed.calls.clear();
        parsed.calls.addAll(assigned);
    }

    private static String kindOf(AbstractTypeDeclaration type) {
        if (type instanceof EnumDeclaration) {
            return "ENUM";
        }
        if (type instanceof AnnotationTypeDeclaration) {
            return "ANNOTATION";
        }
        if (type instanceof TypeDeclaration) {
            return ((TypeDeclaration) type).isInterface() ? "INTERFACE" : "CLASS";
        }
        return "TYPE";
    }

    private static String superClassOf(AbstractTypeDeclaration type) {
        if (type instanceof TypeDeclaration) {
            return ((TypeDeclaration) type).getSuperclassType() == null
                    ? "" : ((TypeDeclaration) type).getSuperclassType().toString();
        }
        if (type instanceof EnumDeclaration) {
            return "java.lang.Enum";
        }
        return "";
    }

    private static String interfacesOf(AbstractTypeDeclaration type) {
        List<String> result = new ArrayList<String>();
        List<?> types = null;
        if (type instanceof TypeDeclaration) {
            types = ((TypeDeclaration) type).superInterfaceTypes();
        } else if (type instanceof EnumDeclaration) {
            types = ((EnumDeclaration) type).superInterfaceTypes();
        }
        if (types != null) {
            for (Object item : types) {
                result.add(item.toString());
            }
        }
        return join(result, ", ");
    }

    /** Javadoc から記号を除いた本文を返す（注釈の意図を検索可能にするため）。 */
    private static String javadoc(org.eclipse.jdt.core.dom.Javadoc javadoc, String source) {
        if (javadoc == null) {
            return "";
        }
        String raw = sourceFragment(source, javadoc.getStartPosition(), javadoc.getLength());
        String[] lines = raw.split("\\r?\\n");
        StringBuilder out = new StringBuilder();
        for (String line : lines) {
            String value = line.trim();
            if (value.startsWith("/**")) {
                value = value.substring(3);
            }
            if (value.endsWith("*/")) {
                value = value.substring(0, value.length() - 2);
            }
            if (value.startsWith("*")) {
                value = value.substring(1).trim();
            }
            if (!value.isEmpty()) {
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(value);
            }
        }
        return out.toString();
    }

    /**
     * 修飾子は flags ではなく AST の修飾子リストから読む。
     * JDT 3.18 では flags が暗黙修飾子を正しく反映しない場合があり、
     * インタフェースのメソッドが private と誤表示されるため。
     */
    private static String modifiers(List<?> extendedModifiers) {
        List<String> result = new ArrayList<String>();
        for (Object item : extendedModifiers) {
            if (!(item instanceof Modifier)) {
                continue;
            }
            Modifier modifier = (Modifier) item;
            if (modifier.isPublic()) {
                result.add("public");
            } else if (modifier.isProtected()) {
                result.add("protected");
            } else if (modifier.isPrivate()) {
                result.add("private");
            } else if (modifier.isStatic()) {
                result.add("static");
            } else if (modifier.isFinal()) {
                result.add("final");
            } else if (modifier.isAbstract()) {
                result.add("abstract");
            } else if (modifier.isSynchronized()) {
                result.add("synchronized");
            }
        }
        return join(result, " ");
    }

    private static String sourceFragment(String source, int start, int length) {
        if (start < 0 || length <= 0 || start >= source.length()) {
            return "";
        }
        int end = Math.min(source.length(), start + length);
        return source.substring(start, end);
    }

    private static int line(CompilationUnit unit, int position) {
        return position <= 0 ? 1 : unit.getLineNumber(position);
    }

    private static int lineCount(String source) {
        int count = 1;
        for (int i = 0; i < source.length(); i++) {
            if (source.charAt(i) == '\n') {
                count++;
            }
        }
        return count;
    }

    private static String join(List<String> values, String separator) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(separator);
            }
            out.append(value);
        }
        return out.toString();
    }

    /** 変更検知用の SHA-256。mtime だけでは信頼できないため本文ハッシュも持つ。 */
    public static String sha256(String text) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder();
            for (byte value : digest) {
                String hex = Integer.toHexString(value & 0xff);
                if (hex.length() == 1) {
                    out.append('0');
                }
                out.append(hex);
            }
            return out.toString();
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 の計算に失敗しました", e);
        }
    }
}

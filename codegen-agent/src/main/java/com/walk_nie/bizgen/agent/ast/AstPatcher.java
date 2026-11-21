package com.walk_nie.bizgen.agent.ast;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.stmt.BlockStmt;
import com.github.javaparser.ast.stmt.Statement;
import com.walk_nie.bizgen.agent.CodeDraft;
import com.walk_nie.bizgen.agent.CodeDraft.HunkKind;
import com.walk_nie.bizgen.agent.CodeDraft.PatchHunk;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 把 CodeDraft.patchHunks 用 JavaParser 渲染进目标源码的 AST。
 *
 * 这是「确定性留给代码」的核心：LLM 只给出 {目标方法, 语句文本, 插入位置}，
 * 如何落 AST、插到第几条语句、如何保持格式，全部由 Java 决定。
 * 模型给的 statement 若不是合法 Java 语句，JavaParser 解析当场失败并打回。
 */
public final class AstPatcher {

    public record PatchResult(String source, int appliedHunks) {}

    /** 把补丁应用到 sourceCode，返回渲染后的完整源码。 */
    public PatchResult apply(String sourceCode, CodeDraft draft) {
        CompilationUnit cu = StaticJavaParser.parse(sourceCode);
        int applied = 0;

        for (PatchHunk hunk : draft.patchHunks()) {
            MethodDeclaration method = findMethod(cu, hunk.targetMethod())
                    .orElseThrow(() -> new IllegalArgumentException("目标方法不存在: " + hunk.targetMethod()));
            BlockStmt body = method.getBody()
                    .orElseThrow(() -> new IllegalArgumentException("目标方法无方法体: " + hunk.targetMethod()));

            // import 也是 AST 操作，不拼字符串
            for (String imp : draft.imports()) {
                if (!cu.getImports().stream().anyMatch(i -> i.getNameAsString().equals(imp))) {
                    cu.addImport(imp);
                }
            }

            Statement statement = parseStatement(hunk.statement());
            switch (hunk.kind()) {
                case INSERT_BEFORE -> {
                    List<Statement> original = new ArrayList<>(body.getStatements());
                    body.getStatements().clear();
                    body.addStatement(statement);
                    original.forEach(body::addStatement);
                }
                case INSERT_AFTER -> body.addStatement(statement);
                case REPLACE -> {
                    if (body.getStatements().isEmpty()) {
                        throw new IllegalArgumentException("REPLACE 目标方法体为空: " + hunk.targetMethod());
                    }
                    int last = body.getStatements().size() - 1;
                    body.getStatements().get(last).replace(statement);
                }
            }
            applied++;
        }
        return new PatchResult(cu.toString(), applied);
    }

    private Statement parseStatement(String text) {
        try {
            return StaticJavaParser.parseStatement(text);
        } catch (Exception e) {
            throw new IllegalArgumentException("模型给出的语句不是合法 Java: " + text, e);
        }
    }

    private Optional<MethodDeclaration> findMethod(CompilationUnit cu, String name) {
        return cu.findAll(MethodDeclaration.class).stream()
                .filter(m -> m.getNameAsString().equals(name))
                .findFirst();
    }
}

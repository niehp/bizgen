package com.walk_nie.bizgen.agent.ast;

import com.walk_nie.bizgen.agent.CodeDraft;
import com.walk_nie.bizgen.agent.CodeDraft.HunkKind;
import com.walk_nie.bizgen.agent.CodeDraft.PatchHunk;
import com.walk_nie.bizgen.agent.ast.AstPatcher.PatchResult;
import com.walk_nie.bizgen.agent.ast.CompileGate.CompileResult;

import java.util.List;

/** 第 1 件的独立可运行验证：AST 渲染 → 编译闸门（含一个正例、一个反例）。 */
public final class AstDemo {

    private static final String TARGET_SOURCE = """
            package sample;
            import java.util.ArrayList;
            import java.util.List;
            public class Reconciler {
                public List<String> match() {
                    List<String> result = new ArrayList<>();
                    return result;
                }
            }
            """;

    public static void main(String[] args) {
        AstPatcher patcher = new AstPatcher();
        CompileGate gate = new CompileGate();

        // ---- 正例：LLM 给出合法的补丁计划 ----
        CodeDraft good = new CodeDraft(
                "在方法开头补一条与位置无关的日志语句",
                List.of(),
                List.of(new PatchHunk("match", "System.out.println(\"begin\");", HunkKind.INSERT_BEFORE))
        );
        PatchResult patched = patcher.apply(TARGET_SOURCE, good);
        System.out.println("=== 渲染后的源码（正例）===");
        System.out.println(patched.source());
        CompileResult ok = gate.compile("sample.Reconciler", patched.source());
        System.out.println("编译结果: " + (ok.ok() ? "通过" : "失败"));
        ok.diagnostics().forEach(System.out::println);

        // ---- 反例：LLM 给出的语句非法（语法错），JavaParser 当场拒绝 ----
        CodeDraft bad = new CodeDraft(
                "故意给一条非法语句",
                List.of(),
                List.of(new PatchHunk("match", "result.add( ;", HunkKind.INSERT_AFTER))
        );
        try {
            patcher.apply(TARGET_SOURCE, bad);
            throw new AssertionError("闸门失效：非法语句被接受");
        } catch (IllegalArgumentException expected) {
            System.out.println("\n[拒绝] 非法语句被 JavaParser 拦截: " + expected.getMessage());
        }

        // ---- 反例：语义错误（变量不存在），编译闸门拦截 ----
        CodeDraft semanticBad = new CodeDraft(
                "引用不存在的变量",
                List.of(),
                List.of(new PatchHunk("match", "return notDefined;", HunkKind.INSERT_AFTER))
        );
        PatchResult semPatched = patcher.apply(TARGET_SOURCE, semanticBad);
        CompileResult semResult = gate.compile("sample.Reconciler", semPatched.source());
        System.out.println("\n[拒绝] 语义错误被编译闸门拦截: ok=" + semResult.ok());
        if (semResult.ok()) throw new AssertionError("编译闸门失效");
        semResult.diagnostics().forEach(d -> System.out.println("   " + d));

        System.out.println("\nAST 渲染 + 编译闸门自检完成。");
    }
}

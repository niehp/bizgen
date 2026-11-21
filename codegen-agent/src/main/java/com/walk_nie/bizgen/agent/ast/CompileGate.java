package com.walk_nie.bizgen.agent.ast;

import javax.tools.*;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * 确定性编译闸门：用 JDK 内置 JavaCompiler 在内存中编译生成的源码。
 * 不落盘、不依赖构建工具。编译失败即打回，是流水线里的硬校验之一。
 */
public final class CompileGate {

    public record CompileResult(boolean ok, List<String> diagnostics) {
        public boolean failed() { return !ok; }
    }

    /** 编译单个源码字符串，className 需与 public 类名一致。 */
    public CompileResult compile(String className, String source) {
        List<String> messages = new ArrayList<>();
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            return new CompileResult(false, List.of("未找到 JavaCompiler，请在 JDK（非 JRE）上运行"));
        }
        DiagnosticCollector<JavaFileObject> collector = new DiagnosticCollector<>();
        try (StandardJavaFileManager fm = compiler.getStandardFileManager(collector, null, null)) {
            List<JavaFileObject> units = List.of(new StringSource(className, source));
            List<String> options = List.of("-proc:none"); // 禁用注解处理器，保持确定性
            boolean success = compiler.getTask(null, fm, collector, options, null, units).call();
            for (Diagnostic<? extends JavaFileObject> d : collector.getDiagnostics()) {
                messages.add(d.getKind() + " @" + d.getLineNumber() + ": " + d.getMessage(null));
            }
            return new CompileResult(success, messages);
        } catch (IOException e) {
            return new CompileResult(false, List.of("编译闸门 IO 异常: " + e.getMessage()));
        }
    }

    /** 把字符串包装成内存中的 .java 编译单元。 */
    private static final class StringSource extends SimpleJavaFileObject {
        private final String code;
        StringSource(String className, String code) {
            super(URI.create("string:///" + className.replace('.', '/') + Kind.SOURCE.extension), Kind.SOURCE);
            this.code = code;
        }
        @Override public CharSequence getCharContent(boolean ignoreEncoding) { return code; }
    }
}

package com.walk_nie.bizgen.agent.generator.java;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 書き出し結果。生成物のパスと、決定論的検証で得た警告を保持する。 */
public final class JavaWriteResult {

    private final Path classFile;
    private final Path packageInfoFile;
    private final JavaChangeIntent intent;
    private final List<String> warnings;

    public JavaWriteResult(Path classFile, Path packageInfoFile, JavaChangeIntent intent, List<String> warnings) {
        this.classFile = classFile;
        this.packageInfoFile = packageInfoFile;
        this.intent = intent;
        this.warnings = warnings == null ? new ArrayList<String>() : warnings;
    }

    public Path classFile() {
        return classFile;
    }

    public Path packageInfoFile() {
        return packageInfoFile;
    }

    public JavaChangeIntent intent() {
        return intent;
    }

    public List<String> warnings() {
        return Collections.unmodifiableList(warnings);
    }
}

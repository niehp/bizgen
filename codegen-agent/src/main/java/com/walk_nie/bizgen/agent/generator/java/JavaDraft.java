package com.walk_nie.bizgen.agent.generator.java;

/** モデルが生成した Java の生データ。パッケージコメントとクラス本体を分離して保持する。 */
public final class JavaDraft {

    private final String packageInfo;
    private final String classCode;
    private final String raw;

    public JavaDraft(String packageInfo, String classCode, String raw) {
        this.packageInfo = packageInfo == null ? "" : packageInfo;
        this.classCode = classCode == null ? "" : classCode;
        this.raw = raw == null ? "" : raw;
    }

    public String packageInfo() {
        return packageInfo;
    }

    public String classCode() {
        return classCode;
    }

    public String raw() {
        return raw;
    }

    public JavaDraft withClassCode(String code) {
        return new JavaDraft(packageInfo, code, raw);
    }
}

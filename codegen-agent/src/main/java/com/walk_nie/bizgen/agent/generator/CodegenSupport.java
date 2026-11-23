package com.walk_nie.bizgen.agent.generator;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaSourceWriter;
import com.walk_nie.bizgen.agent.llm.LlmMessage;
import com.walk_nie.bizgen.agent.llm.LlmRequest;
import com.walk_nie.bizgen.agent.llm.LlmResponse;
import com.walk_nie.bizgen.agent.pipeline.PipelineContext;
import com.walk_nie.bizgen.agent.term.TermDictionary;
import com.walk_nie.bizgen.agent.util.JavaCodeExtractor;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Java / Feature の両パイプラインが共有する決定論的サポート処理。
 *
 * 术语抽出・API 検索・プロンプト整形は Java 側で完結させ、モデルには選別済みの上下文だけを渡す。
 * 自己修正は「1 回だけ」をここで強制し、呼び出し回数が増えないようにする。
 */
public final class CodegenSupport {

    private CodegenSupport() {
    }

    /** 機能 md 本文から業務词汇を取り、术语库の日英マッピングを引いた結果。 */
    public static final class TermAnalysis {
        private final List<String> words;
        private final List<TermDictionary.TermEntry> entries;
        private final String text;

        public TermAnalysis(List<String> words, List<TermDictionary.TermEntry> entries, String text) {
            this.words = Collections.unmodifiableList(new ArrayList<String>(words));
            this.entries = Collections.unmodifiableList(new ArrayList<TermDictionary.TermEntry>(entries));
            this.text = text;
        }

        public List<String> words() {
            return words;
        }

        public List<TermDictionary.TermEntry> entries() {
            return entries;
        }

        public String text() {
            return text;
        }
    }

    public static TermAnalysis analyzeTerms(String featureBody, AgentConfig config) throws IOException {
        TermDictionary dictionary = TermDictionary.load(config.termRoot());
        List<String> words = dictionary.extractBusinessWords(featureBody);
        StringBuilder query = new StringBuilder(StrUtil.trimToEmpty(featureBody));
        for (String word : words) {
            query.append(' ').append(word);
        }
        List<TermDictionary.TermEntry> matched = dictionary.match(query.toString());
        return new TermAnalysis(words, matched, termsText(matched));
    }

    public static String termsText(List<TermDictionary.TermEntry> terms) {
        if (terms == null || terms.isEmpty()) {
            return "（該当术语なし）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("| 日本語 | 英語 |\n| --- | --- |\n");
        for (TermDictionary.TermEntry term : terms) {
            out.append("| ").append(term.japanese()).append(" | ")
                    .append(term.english()).append(" |\n");
        }
        return out.toString();
    }

    public static List<ApiCatalog.ApiEntry> matchApis(String featureBody,
                                                       List<TermDictionary.TermEntry> terms,
                                                       AgentConfig config) throws IOException {
        ApiCatalog catalog = ApiCatalog.load(config.apiRoot());
        StringBuilder query = new StringBuilder(StrUtil.trimToEmpty(featureBody));
        if (terms != null) {
            for (TermDictionary.TermEntry term : terms) {
                query.append(' ').append(term.japanese()).append(' ').append(term.english());
            }
        }
        return catalog.match(query.toString(), config.apiTopN());
    }

    public static String apisText(List<ApiCatalog.ApiEntry> apis) {
        if (apis == null || apis.isEmpty()) {
            return "（該当する既存 API なし。存在しない API を生成しないこと。）\n";
        }
        StringBuilder out = new StringBuilder();
        String currentClass = "";
        for (ApiCatalog.ApiEntry api : apis) {
            if (!currentClass.equals(api.className())) {
                currentClass = api.className();
                out.append("### ").append(currentClass).append('\n');
            }
            out.append("- `").append(api.signature()).append("` — ")
                    .append(api.description()).append('\n');
        }
        return out.toString();
    }

    public static String formatProblems(List<String> problems) {
        StringBuilder out = new StringBuilder();
        if (problems == null) {
            return out.toString();
        }
        for (int i = 0; i < problems.size(); i++) {
            out.append(i + 1).append(". ").append(problems.get(i)).append('\n');
        }
        return out.toString();
    }

    /** 自己修正の結果。invoked が false なら修正要求を送っていない。 */
    public static final class Correction {
        private final String code;
        private final boolean invoked;
        private final String rawResponse;

        public Correction(String code, boolean invoked, String rawResponse) {
            this.code = code;
            this.invoked = invoked;
            this.rawResponse = rawResponse;
        }

        public String code() {
            return code;
        }

        public boolean invoked() {
            return invoked;
        }

        public String rawResponse() {
            return rawResponse;
        }
    }

    /**
     * 検証違反に対する自己修正を 1 回だけ行う。
     * fullClass=true なら完全なクラス、false ならメソッド片のみを取り出す。
     */
    public static Correction correct(String promptName, String code, List<String> problems,
                                     boolean fullClass, List<TermDictionary.TermEntry> terms,
                                     List<ApiCatalog.ApiEntry> apis, String packageName,
                                     String className, PipelineContext context) throws IOException {
        AgentConfig config = context.config();
        if (!config.selfCorrect() || problems == null || problems.isEmpty()) {
            return new Correction(code, false, null);
        }

        VariableTable variables = new VariableTable();
        variables.put("code", code);
        variables.put("problemsText", formatProblems(problems));
        variables.put("termsText", termsText(terms));
        variables.put("apisText", apisText(apis));
        variables.put("packageName", packageName);
        variables.put("className", className);
        variables.put("outputRule", fullClass
                ? "package 宣言と class 宣言を含む、完全な Java クラス全体を出力する。"
                : "修正対象のメソッドのみを出力する。package / class 宣言は出力しない。");
        String prompt = variables.resolveText(context.repository().prompt(promptName));

        LlmRequest request = new LlmRequest(config.model(),
                Collections.singletonList(LlmMessage.user(prompt)),
                config.temperature(), config.maxTokens(), config.seed());
        LlmResponse response = context.ask(request);

        String corrected = fullClass
                ? JavaCodeExtractor.extract(response.content())
                : extractMethods(response.content());
        if (StrUtil.isBlank(corrected)) {
            corrected = code;
        }
        return new Correction(corrected, true, response.content());
    }

    /** メソッド片の修正結果からメソッドブロックだけを取り出す（決定論的）。 */
    public static String extractMethods(String raw) {
        String source = StrUtil.stripCodeFence(raw);
        List<JavaSourceWriter.MethodBlock> methods = JavaSourceWriter.extractMethods(source);
        if (methods.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder();
        for (JavaSourceWriter.MethodBlock method : methods) {
            if (out.length() > 0) {
                out.append("\n\n");
            }
            out.append(method.text());
        }
        return out.toString();
    }
}

package com.walk_nie.bizgen.agent.semantic;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.ExistingCodeSearcher;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeResult;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSearchLog;
import com.walk_nie.bizgen.agent.generator.java.SimilarCodeSnippet;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 意味解析の意図に従って、既存 Java 库・API 库を<b>決定論的に</b>検索する実行器。
 *
 * <p>「どれを探すか」はモデル（{@link SemanticSearchIntent}）、
 * 「どう走査し・どう採点し・どう切断し・何を採用するか」はすべてこのクラス。
 * 埋め込みモデルや外部サービスは一切使わない。無命中は必ず入力をログに残す（要件）。</p>
 */
public final class SemanticRetrievalExecutor {

    private SemanticRetrievalExecutor() {
    }

    /** モデルが立てた意図を実行し、本物の命中結果を返す。 */
    public static SemanticRetrievalResult execute(AgentConfig config, SemanticSearchIntent intent,
                                                  String featureTitle, String featureBody,
                                                  String targetFileRelative) {
        if (intent == null || !intent.drivesRetrieval()) {
            return new SemanticRetrievalResult(intent, null,
                    new ArrayList<ApiCatalog.ApiEntry>(), new ArrayList<String>(), false);
        }

        List<String> noHit = new ArrayList<String>();
        SimilarCodeResult codeResult = searchCode(config, intent, featureTitle, featureBody,
                targetFileRelative, noHit);
        List<ApiCatalog.ApiEntry> apis = searchApis(config, intent, featureTitle, featureBody, noHit);
        return new SemanticRetrievalResult(intent, codeResult, apis, noHit, true);
    }

    /** CODE 意図ごとに既存 Java 库を検索し、命中した本物のファイルをマージする。 */
    private static SimilarCodeResult searchCode(AgentConfig config, SemanticSearchIntent intent,
                                                 String featureTitle, String featureBody,
                                                 String targetFileRelative, List<String> noHit) {
        List<SemanticSearchIntent.Query> codeQueries = intent.queriesFor(SemanticSearchIntent.Target.CODE);
        if (codeQueries.isEmpty()) {
            return null;
        }

        List<SimilarCodeSnippet> merged = new ArrayList<SimilarCodeSnippet>();
        Set<String> seenPaths = new LinkedHashSet<String>();
        Set<String> allKeywords = new LinkedHashSet<String>();
        int scanned = 0;
        Path root = null;
        int charBudget = Math.max(800, config.maxSimilarCodeChars());

        for (SemanticSearchIntent.Query query : codeQueries) {
            allKeywords.addAll(query.keywords());
            SimilarCodeResult hit = ExistingCodeSearcher.searchByKeywords(
                    config, query.keywords(), targetFileRelative);
            if (root == null) {
                root = hit.root();
            }
            scanned = Math.max(scanned, hit.scannedFiles());
            if (hit.snippets().isEmpty()) {
                noHit.add("[CODE] " + query.purpose());
                SimilarCodeSearchLog.recordNoHit(config, "semantic-code", featureTitle,
                        query.purpose() + " / 検索語=" + query.keywords(), hit);
                continue;
            }
            for (SimilarCodeSnippet snippet : hit.snippets()) {
                if (!seenPaths.add(snippet.relativePath())) {
                    continue;
                }
                String content = snippet.content();
                if (merged.size() * content.length() > charBudget || content.length() > charBudget) {
                    continue;
                }
                merged.add(snippet);
            }
        }

        StringBuilder prompt = new StringBuilder();
        prompt.append("意味解析による検索語: ").append(new ArrayList<String>(allKeywords)).append('\n');
        prompt.append("走査 Java ファイル数: ").append(scanned)
                .append(" / 抽出: ").append(merged.size()).append("\n\n");
        if (merged.isEmpty()) {
            prompt.append("（意味解析の検索意図に対する既存コードの命中なし）");
        } else {
            for (int i = 0; i < merged.size(); i++) {
                SimilarCodeSnippet snippet = merged.get(i);
                prompt.append("===== 既存コード ").append(i + 1).append(": ")
                        .append(snippet.relativePath()).append(" (一致度 ")
                        .append(snippet.score()).append(") =====\n")
                        .append(snippet.content()).append("\n\n");
            }
        }
        return new SimilarCodeResult(root, merged, new ArrayList<String>(allKeywords),
                scanned, prompt.toString());
    }

    /** API 意図ごとに API 库を検索し、実在シグネチャだけを集める。 */
    private static List<ApiCatalog.ApiEntry> searchApis(AgentConfig config,
                                                         SemanticSearchIntent intent,
                                                         String featureTitle, String featureBody,
                                                         List<String> noHit) {
        List<SemanticSearchIntent.Query> apiQueries = intent.queriesFor(SemanticSearchIntent.Target.API);
        List<ApiCatalog.ApiEntry> merged = new ArrayList<ApiCatalog.ApiEntry>();
        if (apiQueries.isEmpty()) {
            return merged;
        }
        try {
            ApiCatalog catalog = ApiCatalog.load(config.apiRoot());
            Set<String> seen = new LinkedHashSet<String>();
            for (SemanticSearchIntent.Query query : apiQueries) {
                StringBuilder q = new StringBuilder();
                for (String keyword : query.keywords()) {
                    q.append(' ').append(keyword);
                }
                List<ApiCatalog.ApiEntry> matched = catalog.match(q.toString().trim(), config.apiTopN());
                if (matched.isEmpty()) {
                    noHit.add("[API] " + query.purpose());
                    logApiNoHit(config, featureTitle, query);
                    continue;
                }
                for (ApiCatalog.ApiEntry entry : matched) {
                    if (seen.add(entry.className() + "#" + entry.signature())
                            && merged.size() < config.apiTopN()) {
                        merged.add(entry);
                    }
                }
            }
        } catch (IOException e) {
            System.err.println("[semantic-retrieval] API 库の読み込みに失敗: " + e.getMessage());
        }
        return merged;
    }

    /** API 無命中も「入力をログする」要件を満たすため、similar-code.log と同じ機構で残す。 */
    private static void logApiNoHit(AgentConfig config, String featureTitle,
                                    SemanticSearchIntent.Query query) {
        SimilarCodeResult empty = new SimilarCodeResult(null,
                new ArrayList<SimilarCodeSnippet>(), query.keywords(), 0,
                "API 库に該当なし");
        SimilarCodeSearchLog.recordNoHit(config, "semantic-api", featureTitle,
                query.purpose() + " / 検索語=" + query.keywords(), empty);
    }
}

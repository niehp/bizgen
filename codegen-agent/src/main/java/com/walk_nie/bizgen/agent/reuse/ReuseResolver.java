package com.walk_nie.bizgen.agent.reuse;

import com.walk_nie.bizgen.agent.api.ApiCatalog;
import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.util.StrUtil;
import com.walk_nie.bizgen.agent.variable.VariableTable;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 再利用ブロックの選択・変数展開・検証を一箇所に集約する。
 *
 * <p>モデルに「どのテンプレートを使うか」を判断させても、採用と変数展開は必ずこのクラスが行う。
 * API_CALL テンプレートは API 库に実在シグネチャが無ければ採用しない。</p>
 */
public final class ReuseResolver {

    private final AgentConfig config;

    public ReuseResolver(AgentConfig config) {
        if (config == null) {
            throw new IllegalArgumentException("AgentConfig がありません");
        }
        this.config = config;
    }

    public AgentConfig config() {
        return config;
    }

    public ReusableBlockCatalog catalog() throws IOException {
        return ReusableBlockCatalog.load(config.blockRoot());
    }

    /** クエリから最適なブロックを 1 つだけ選ぶ。該当なし / 検証失敗なら null。 */
    public ReuseSelection resolve(String query, Map<String, String> variables) throws IOException {
        if (!config.reuseEnabled()) {
            return null;
        }
        ReusableBlockCatalog catalog = catalog();
        if (catalog.isEmpty()) {
            return null;
        }
        List<ReusableBlockCatalog.Scored> candidates =
                catalog.match(query, config.reuseTopN(), null);
        for (ReusableBlockCatalog.Scored candidate : candidates) {
            if (candidate.score() < config.reuseMinScore()) {
                continue;
            }
            ReuseSelection selection = expand(candidate.block(), candidate.score(), variables);
            if (selection != null) {
                return selection;
            }
        }
        return null;
    }

    /** id を直接指定する（function tool 用）。スコアは 0 として扱う。 */
    public ReuseSelection resolveById(String id, Map<String, String> variables) throws IOException {
        if (!config.reuseEnabled()) {
            return null;
        }
        ReusableBlock block = catalog().byId(id);
        if (block == null) {
            return null;
        }
        return expand(block, 0, variables);
    }

    /** テンプレートを検証して展開する。問題があれば null（黙って使わせない）。 */
    public ReuseSelection expand(ReusableBlock block, int score, Map<String, String> variables)
            throws IOException {
        String code = block.resolveCode(variables);
        List<String> unresolved = VariableTable.placeholders(code);
        if (!unresolved.isEmpty()) {
            return null;
        }
        if (block.kind() == ReusableBlock.Kind.API_CALL
                && !signatureExists(block.apiSignature())) {
            return null;
        }
        return new ReuseSelection(block.id(), block.kind(), code,
                block.apiSignature(), score, block.source(), unresolved);
    }

    /** API 库に登録されたシグネチャと空白を無視して一致するか。 */
    public boolean signatureExists(String signature) throws IOException {
        ApiCatalog catalog = ApiCatalog.load(config.apiRoot());
        String needle = ReuseVerifier.normalize(signature).toLowerCase(Locale.ROOT);
        for (ApiCatalog.ApiEntry entry : catalog.entries()) {
            if (ReuseVerifier.normalize(entry.signature()).toLowerCase(Locale.ROOT).equals(needle)) {
                return true;
            }
        }
        return false;
    }

    /** プランナーへ渡すテンプレート一覧。選択自体はこのクラスが決定論的に行う。 */
    public static String hintsText(ReusableBlockCatalog catalog) {
        if (catalog == null || catalog.isEmpty()) {
            return "（再利用テンプレートなし。通常のブロック分解を行うこと。）\n";
        }
        StringBuilder out = new StringBuilder();
        out.append("以下のテンプレートが利用可能です。説明・キーワードを blocks の description / apiQuery に"
                + "自然に含めてください（採用判断はコード側が行います）。\n");
        for (ReusableBlock block : catalog.blocks()) {
            out.append("- ").append(block.id()).append(" [").append(block.kind().name()).append("] ")
                    .append(block.description());
            if (!block.keywords().isEmpty()) {
                out.append(" / キーワード: ").append(String.join(", ", block.keywords()));
            }
            out.append('\n');
        }
        return out.toString();
    }

    /** 複数の再利用結果を prompt に注入する形に整形する。 */
    public static String promptText(List<ReuseSelection> selections) {
        StringBuilder out = new StringBuilder();
        if (selections == null || selections.isEmpty()) {
            return "（再利用テンプレートなし。通常どおりコードを書いてください。）\n";
        }
        out.append("固定フラグメント（コード側で選択・変数展開済み。一字も変更せずそのまま使うこと）:\n");
        for (ReuseSelection selection : selections) {
            out.append("### ").append(selection.blockId())
                    .append(" [").append(selection.kind().name()).append("]\n");
            if (!selection.apiSignature().isEmpty()) {
                out.append("確認済み API: `").append(selection.apiSignature()).append("`\n");
            }
            out.append("```java\n").append(selection.code()).append("\n```\n\n");
        }
        out.append("上記フラグメントはロジック・識別子・リテラルを含めて変更禁止です。\n");
        return out.toString();
    }

    /** どのテンプレートも採用されなかった理由を監査用に残すための薄いヘルパ。 */
    public static String describeQuery(String blockDescription, String apiQuery) {
        StringBuilder out = new StringBuilder(StrUtil.trimToEmpty(blockDescription));
        if (!StrUtil.isBlank(apiQuery) && !apiQuery.equals(blockDescription)) {
            out.append(' ').append(apiQuery);
        }
        return out.toString();
    }
}

package com.walk_nie.bizgen.agent.mdgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 階層 md ローダ（100% 確定的）。
 *
 * <p>役割：
 * <ol>
 *   <li>レベルごとに指定された md を読み、全ファイルへ同じ変数表で {{...}} を解決する。</li>
 *   <li>メソッド級は SpecParser で MethodSpec に解析（署名はここだけから来る）。</li>
 *   <li>上位 4 級は文脈として保持し、LLM へ「粗い順」に渡す。</li>
 * </ol>
 *
 * <p>なぜ変数解決をローダで一括するか：変数は共通 md（例：プロジェクト共通の
 * {{applicationId=APP001}}）で定義され、下位級（メソッド）で参照される。各ファイルを
 * 別々に解決すると渡し忘れが起きるため、1 つの変数表を全レベルへ共有する。
 * 共通→メソッドの順に resolve することで、定義が参照より先に効く。
 * 定義は共通側だけ許可し、メソッド級の {{key=value}} は拒否する（下位が契約を上書きしない）。
 *
 * <p>なぜ文脈を粗い順にStable Keepするか：LLM は先に読んだ共通制約を後続の
 * 具体実装へ反映しやすいため（プロンプト設計の確定的な一部）。
 */
public final class LayeredSpecLoader {

    /** レベル→md パス（メンバーは複数可：同じレベルに複数ファイルを渡せる）。 */
    public static LayeredSpec load(Path methodMd,
                                   Map<SpecLevel, List<Path>> layeredFiles,
                                   Map<String, String> vars) throws IOException {
        TemplateResolver resolver = new TemplateResolver(vars);

        // 1) 全階層のテキストを読み、変数を解決（ここで未解決変数は全件列挙して拒否）
        Map<SpecLevel, List<LayeredSpec.LayerDoc>> resolved = new LinkedHashMap<>();
        List<Path> methodFiles = new ArrayList<>();
        methodFiles.add(methodMd);
        if (layeredFiles != null && layeredFiles.containsKey(SpecLevel.METHOD)) {
            methodFiles.addAll(layeredFiles.get(SpecLevel.METHOD));
        }

        for (SpecLevel level : SpecLevel.values()) {
            List<Path> files = new ArrayList<>();
            if (level == SpecLevel.METHOD) {
                files.addAll(methodFiles);
            } else if (layeredFiles != null) {
                files.addAll(layeredFiles.getOrDefault(level, List.of()));
            }
            List<LayeredSpec.LayerDoc> docs = new ArrayList<>();
            for (Path p : files) {
                if (!Files.isRegularFile(p)) {
                    throw new IllegalArgumentException(level.japanese() + " md が無い: " + p);
                }
                String raw = Files.readString(p, StandardCharsets.UTF_8);
                if (!raw.isEmpty() && raw.charAt(0) == '\uFEFF') raw = raw.substring(1);
                if (level == SpecLevel.METHOD) {
                    Map<String, String> defs = TemplateResolver.definitions(raw);
                    if (!defs.isEmpty()) {
                        throw new IllegalArgumentException(
                                "メソッド級 md では変数定義 {{key=value}} は使用不可（共通 md で定義すること）: "
                                        + p + " -> " + defs.keySet());
                    }
                }
                docs.add(new LayeredSpec.LayerDoc(level, p.toString(), resolver.resolve(raw)));
            }
            resolved.put(level, docs);
        }

        // 2) メソッド級は SpecParser で解析（複数ある場合は最後の 1 つを採用＝明示的に上書き）
        List<LayeredSpec.LayerDoc> methodDocs = resolved.get(SpecLevel.METHOD);
        if (methodDocs.isEmpty()) {
            throw new IllegalArgumentException("メソッド級 md が指定されていない");
        }
        String methodText = methodDocs.get(methodDocs.size() - 1).content();
        MethodSpec method = SpecParser.parse(methodText);

        // 3) 上位 4 級を粗い順に文脈として集める（メソッド級は prompt の専用節に入れるため除外）
        List<LayeredSpec.LayerDoc> contexts = new ArrayList<>();
        for (SpecLevel level : SpecLevel.values()) {
            if (level == SpecLevel.METHOD) continue;
            contexts.addAll(resolved.get(level));
        }
        contexts.sort(Comparator.comparingInt(d -> d.level().ordinal()));

        return new LayeredSpec(method, List.copyOf(contexts), resolver.snapshot());
    }
}

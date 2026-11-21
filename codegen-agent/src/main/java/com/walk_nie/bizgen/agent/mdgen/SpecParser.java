package com.walk_nie.bizgen.agent.mdgen;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 方法概要 md の確定的パーサ。
 *
 * 対応セクション（見出しは日本語仕様書文体）：
 *   ## 対象          - key: value（パッケージ／クラス／メソッド／戻り値／引数／モード／対象ファイル）
 *   ## 処理概要      自由記述（そのまま LLM へ意味入力として渡す）
 *   ## 使用可能API   リスト項目（ホワイトリストの出所）
 *   ## 受入基準      リスト項目（判定可能な条件）
 *
 * 設計原則：解析は 100% 確定的。セクション欠落・必須項目欠落・書式エラーは即例外。
 * LLM に「仕様とは何か」を推させない——なぜなら仕様は契約であり、推論の対象ではないから。
 */
public final class SpecParser {

    private SpecParser() {}

    public static MethodSpec parse(Path md) throws IOException {
        String text = Files.readString(md, StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == '\uFEFF') text = text.substring(1); // BOM 除去
        return parse(text);
    }

    public static MethodSpec parse(String text) {
        Map<String, List<String>> sections = splitSections(text);

        List<String> target = sections.get("対象");
        require(target != null, "## 対象 セクションが無い");
        Map<String, String> kv = keyValues(target);

        String className = requiredField(kv, "クラス");
        String methodName = requiredField(kv, "メソッド");
        String returnType = requiredField(kv, "戻り値");
        String packageName = kv.getOrDefault("パッケージ", "");
        String argsLine = kv.get("引数");
        List<String> parameters = argsLine == null || argsLine.isBlank()
                ? List.of() : splitArgs(argsLine);

        // モードは既定 NEW_FILE。追加・修正のときだけ対象ファイルが必須。
        Operation operation = parseOperation(kv.get("モード"));
        String targetFile = kv.get("対象ファイル");
        if (operation != Operation.NEW_FILE) {
            require(targetFile != null && !targetFile.isBlank(),
                    "モード " + operation + " には ## 対象 の「対象ファイル」が必須");
        }

        List<String> summaryLines = sections.get("処理概要");
        require(summaryLines != null && !summaryLines.isEmpty(), "## 処理概要 が空");
        String summary = String.join("\n", summaryLines);

        // リスト節は先頭の "- " を落とす（ホワイトリストにゴミが混ざるのを防ぐため）
        List<String> apis = sections.getOrDefault("使用可能API", List.of()).stream()
                .map(SpecParser::stripBullet).toList();
        List<String> acceptance = sections.getOrDefault("受入基準", List.of()).stream()
                .map(SpecParser::stripBullet).toList();

        return new MethodSpec(packageName, className, methodName, returnType,
                parameters, summary, apis, acceptance, operation, targetFile);
    }

    /** 文字列→生成モード。未指定・不明は既定値にフォールバック（後方互換のため）。 */
    private static Operation parseOperation(String value) {
        if (value == null || value.isBlank()) return Operation.NEW_FILE;
        return switch (value.strip().toUpperCase()) {
            case "NEW_FILE", "新規", "新規作成" -> Operation.NEW_FILE;
            case "ADD_METHOD", "追加" -> Operation.ADD_METHOD;
            case "MODIFY_METHOD", "修正" -> Operation.MODIFY_METHOD;
            default -> throw new IllegalArgumentException(
                    "规格文件格式错误: 不明なモード「" + value + "」（NEW_FILE/ADD_METHOD/MODIFY_METHOD）");
        };
    }

    /** "## " 見出しで分割し、見出し名→その節の行（空白行除く）。 */
    private static Map<String, List<String>> splitSections(String text) {
        Map<String, List<String>> out = new LinkedHashMap<>();
        String current = null;
        for (String raw : text.split("\\R")) {
            String line = raw.strip();
            if (line.startsWith("## ")) {
                current = line.substring(3).strip();
                out.computeIfAbsent(current, k -> new ArrayList<>());
            } else if (line.startsWith("# ")) {
                current = null; // 文書タイトルは無視
            } else if (current != null && !line.isEmpty()) {
                out.get(current).add(line);
            }
        }
        return out;
    }

    /** "- key: value" 行を解析。コロンの無い行は無視。 */
    private static Map<String, String> keyValues(List<String> lines) {
        Map<String, String> kv = new LinkedHashMap<>();
        for (String line : lines) {
            String item = stripBullet(line);
            int colon = item.indexOf(':');
            if (colon > 0 && colon < item.length() - 1) {
                kv.put(item.substring(0, colon).strip(), item.substring(colon + 1).strip());
            }
        }
        return kv;
    }

    private static String stripBullet(String line) {
        String s = line.strip();
        return s.startsWith("- ") ? s.substring(2).strip() : s;
    }

    /** カンマで引数を分割するが、&lt;...&gt; ジェネリクス内のカンマは無視。 */
    private static List<String> splitArgs(String line) {
        List<String> out = new ArrayList<>();
        int depth = 0, start = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '<') depth++;
            else if (c == '>') depth = Math.max(0, depth - 1);
            else if (c == ',' && depth == 0) {
                out.add(line.substring(start, i).strip());
                start = i + 1;
            }
        }
        String last = line.substring(start).strip();
        if (!last.isEmpty()) out.add(last);
        return out;
    }

    private static String requiredField(Map<String, String> kv, String key) {
        String value = kv.get(key);
        require(value != null && !value.isBlank(), "## 対象 に必須項目「" + key + "」が無い");
        return value;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException("规格文件格式错误: " + message);
    }
}

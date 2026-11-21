package com.walk_nie.bizgen.agent.mdgen;

import com.walk_nie.bizgen.agent.VllmClient;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 「階層 md」から Java コードを生成する CLI エントリ。
 *
 * 使い方：
 *   CodegenFromMd <method.md> [オプション]
 *     --config <file>             … 設定ファイル（既定 config/agent.properties）
 *     --var applicationId=APP001  … {{...}} 変数（繰り返し可）
 *     --out <dir>                 … 出力先（既定 generated/）
 *
 * <p>LLM の情報（mock / live、ベース URL、モデル名）は設定ファイルから取得する。
 * {@code --live} は旧形式の互換指定としてのみ残す（非推奨、llm.mode を推奨）。
 *
 * <p>共通 md は <b>自動読込</b>：既定 specs/common/ 配下の *.md を再帰的に読み、
 * サブフォルダ名（java-common / project-common / feature / class）でレベルを判定する。
 * 直下の md はプロジェクト共通。CLI で --layer を書く必要はない。
 * 追加で明示したい文件时だけ --layer LEVEL=PATH（繰り返し可）を使う。
 *   --common-dir <dir>  … 共通フォルダを変更（環境変数 BIZGEN_COMMON_MD_DIR でも可）
 *   --no-common         … 自動読込を無効化（旧来の --layer のみに戻す）
 *
 * <p>位置引数の <method.md> はメソッド級（今回作るものの単一真源）。旧形式（1 ファイルだけ）もそのまま動く。
 * <p>設定ファイルに項目が無い場合のみ、環境変数 VLLM_BASE_URL / VLLM_MODEL /
 * BIZGEN_COMMON_MD_DIR で補う（設定ファイルが単一真源）。
 *
 * <p>確定性の境界：ファイル読み・変数解決・仕様解析・シグネチャ・三層 Javadoc の形・
 * ホワイトリスト・コンパイル・落盤は Java、コメント文とメソッド本体の下書きだけを LLM に任せる。
 */
public final class CodegenFromMd {

    public static void main(String[] args) throws Exception {
        if (args.length == 0) {
            usage();
            System.exit(2);
        }

        Path methodMd = Path.of(args[0]);
        AgentConfig config = AgentConfig.load(optionValue(args, "--config").map(Path::of).orElse(null));
        boolean legacyLive = contains(args, "--live");
        boolean live = config.live() || legacyLive;
        boolean noCommon = contains(args, "--no-common");
        Path outDir = optionValue(args, "--out").map(Path::of).orElse(Path.of("generated"));
        Path commonDir = optionValue(args, "--common-dir").map(Path::of).orElse(config.commonDir());
        Map<String, String> vars = collectVars(args);
        Map<SpecLevel, List<Path>> auto = noCommon
                ? Map.of() : CommonSpecScanner.scan(commonDir);
        Map<SpecLevel, List<Path>> layers = CommonSpecScanner.merge(auto, collectLayers(args));

        if (!Files.isRegularFile(methodMd)) {
            throw new IllegalArgumentException("仕様ファイルが無い: " + methodMd.toAbsolutePath());
        }

        System.out.println("=== 1) 階層 md を確定的に読込 + 変数解決 → LayeredSpec ===");
        System.out.println("  共通フォルダ自動読込: " + CommonSpecScanner.count(auto) + " 件 @ "
                + commonDir.toAbsolutePath() + (noCommon ? "（--no-common で無効）" : ""));
        LayeredSpec layered = LayeredSpecLoader.load(methodMd, layers, vars);
        MethodSpec spec = layered.method();
        System.out.println("  変数            : " + layered.vars());
        System.out.println("  読込んだ文脈    : " + layered.contexts().size() + " 件（" + levelsSummary(layered) + "）");
        for (LayeredSpec.LayerDoc d : layered.contexts()) {
            System.out.println("    [" + d.level().japanese() + "] " + d.source());
        }
        System.out.println("  モード          : " + spec.operation());
        System.out.println("  クラス/メソッド : " + spec.className() + "." + spec.methodName());
        System.out.println("  戻り値 / 引数   : " + spec.returnType() + " / " + spec.parameters());
        System.out.println("  使用可能API     : " + spec.allowedApis());
        System.out.println("  受入基準        : " + spec.acceptance());
        if (spec.targetFile() != null) System.out.println("  対象ファイル    : " + spec.targetFile());

        System.out.println("\n=== 2) 本体とコメント文の下書き（" + (live ? "ローカル vLLM" : "オフライン mock") + "）===");
        System.out.println("  設定ファイル  : " + config.source());
        System.out.println("  LLM モード    : " + config.mode()
                + (legacyLive && !config.live() ? "（--live 互換指定で LIVE に上書き）" : "（設定ファイル）"));
        System.out.println("  LLM ベースURL : " + config.baseUrl());
        System.out.println("  LLM モデル    : " + config.model());
        VllmClient client = new VllmClient(config.baseUrl(), config.model());
        BodyGenerator generator = new BodyGenerator(client);
        MethodBody body = live ? generator.generateLive(layered) : generator.generateMock(layered);
        System.out.println("  メソッド説明    : " + body.methodComment());
        System.out.println("  クラス説明      : " + body.classComment());
        System.out.println("  パッケージ説明  : " + body.packageComment());
        System.out.println("  引数説明        : " + body.paramComments());
        System.out.println("  文              : " + body.statements());

        System.out.println("\n=== 3) 確定的組立 + 三層 Javadoc + API ホワイトリスト + コンパイル ===");
        SourceAssembler assembler = new SourceAssembler();
        SourceAssembler.AssemblyResult result = assembler.assemble(spec, body);
        System.out.println("  モード=" + result.mode() + "、適用文数=" + result.appliedStatements()
                + "、三層 Javadoc 付与、コンパイル成功");

        System.out.println("\n=== 4) 落盤 ===");
        Files.createDirectories(outDir);
        Path outFile = outDir.resolve(spec.className() + ".java");
        // 既存ファイルを修正する場合、同名宛先へ上書きしてはいけない（元資産を壊さないため）
        if (!spec.operation().equals(Operation.NEW_FILE)
                && Path.of(spec.targetFile()).toAbsolutePath().normalize()
                        .equals(outFile.toAbsolutePath().normalize())) {
            outFile = outDir.resolve(spec.className() + "_generated.java");
        }
        Files.writeString(outFile, result.source(), StandardCharsets.UTF_8);
        System.out.println("  書き出し: " + outFile.toAbsolutePath());

        assembler.packageInfoSource(spec, body).ifPresent(pi -> {
            try {
                Path piFile = outDir.resolve("package-info.java");
                Files.writeString(piFile, pi, StandardCharsets.UTF_8);
                System.out.println("  書き出し: " + piFile.toAbsolutePath() + "（パッケージ Javadoc）");
            } catch (Exception e) {
                throw new IllegalStateException("package-info.java の書き出しに失敗", e);
            }
        });

        System.out.println("\n生成完了。\n" + result.source());
    }

    /** --var KEY=VALUE を繰り返し収集（後勝ち）。 */
    private static Map<String, String> collectVars(String[] args) {
        Map<String, String> vars = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (!"--var".equals(args[i])) continue;
            String item = args[i + 1];
            int eq = item.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--var は KEY=VALUE 形式: " + item);
            vars.put(item.substring(0, eq).strip(), item.substring(eq + 1).strip());
        }
        return vars;
    }

    /** --layer LEVEL=PATH を繰り返し収集。レベル名不正は即拒否（確定的ゲート）。 */
    private static Map<SpecLevel, List<Path>> collectLayers(String[] args) {
        Map<SpecLevel, List<Path>> layers = new LinkedHashMap<>();
        for (int i = 0; i < args.length - 1; i++) {
            if (!"--layer".equals(args[i])) continue;
            String item = args[i + 1];
            int eq = item.indexOf('=');
            if (eq <= 0) throw new IllegalArgumentException("--layer は LEVEL=PATH 形式: " + item);
            String name = item.substring(0, eq).strip();
            SpecLevel level = SpecLevel.fromCliName(name);
            if (level == null) {
                throw new IllegalArgumentException("不明なレベル「" + name + "」（java-common/project-common/feature/class/method）");
            }
            layers.computeIfAbsent(level, k -> new ArrayList<>()).add(Path.of(item.substring(eq + 1).strip()));
        }
        return layers;
    }

    private static String levelsSummary(LayeredSpec layered) {
        List<String> names = new ArrayList<>();
        for (LayeredSpec.LayerDoc d : layered.contexts()) {
            if (!names.contains(d.level().japanese())) names.add(d.level().japanese());
        }
        return String.join(" / ", names);
    }

    private static void usage() {
        System.err.println("使い方: CodegenFromMd <method.md> [--config <file>] [--var KEY=VALUE ...] [--out <dir>]");
        System.err.println("  LLM 情報は設定ファイルから取得（既定 config/agent.properties: llm.mode=mock|live / llm.base-url / llm.model）");
        System.err.println("  --live は旧形式の互換指定（非推奨）。--config で設定ファイルを変更可");
        System.err.println("  common フォルダの *.md は自動読込（設定 common.dir、--common-dir で変更、--no-common で無効）");
        System.err.println("  明示追加する場合のみ: [--layer LEVEL=PATH ...]");
        System.err.println("  LEVEL: java-common | project-common | feature | class | method");
    }

    private static boolean contains(String[] args, String flag) {
        for (String a : args) if (flag.equals(a)) return true;
        return false;
    }

    private static java.util.Optional<String> optionValue(String[] args, String opt) {
        for (int i = 0; i < args.length - 1; i++) {
            if (opt.equals(args[i])) return java.util.Optional.of(args[i + 1]);
        }
        return java.util.Optional.empty();
    }
}

package com.walk_nie.bizgen.agent.reuse;

import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 再利用ブロックのカタログ。spec/block/*.md をファイル名順に読み込み、
 * スコア順に決定論的に並べる。外部 API・埋め込みモデルは使わない。
 */
public final class ReusableBlockCatalog {

    private final List<ReusableBlock> blocks;
    private final Map<String, ReusableBlock> byId;

    private ReusableBlockCatalog(List<ReusableBlock> blocks) {
        this.blocks = Collections.unmodifiableList(new ArrayList<ReusableBlock>(blocks));
        Map<String, ReusableBlock> index = new LinkedHashMap<String, ReusableBlock>();
        for (ReusableBlock block : this.blocks) {
            ReusableBlock previous = index.put(block.id(), block);
            if (previous != null) {
                throw new IllegalStateException("再利用ブロック id が重複しています: " + block.id());
            }
        }
        this.byId = Collections.unmodifiableMap(index);
    }

    public static ReusableBlockCatalog load(Path root) throws IOException {
        List<ReusableBlock> blocks = new ArrayList<ReusableBlock>();
        if (root == null || !Files.isDirectory(root)) {
            return new ReusableBlockCatalog(blocks);
        }
        for (Path path : FileUtil.listMarkdown(root)) {
            blocks.add(ReusableBlock.parse(FileUtil.read(path),
                    path.getFileName().toString()));
        }
        return new ReusableBlockCatalog(blocks);
    }

    public List<ReusableBlock> blocks() {
        return blocks;
    }

    public boolean isEmpty() {
        return blocks.isEmpty();
    }

    public int size() {
        return blocks.size();
    }

    public ReusableBlock byId(String id) {
        if (StrUtil.isBlank(id)) {
            return null;
        }
        return byId.get(id.trim());
    }

    /**
     * クエリに一致するブロックをスコア順に返す。
     * 同点の場合は id の辞書順で固定し、実行環境による揺れを排除する。
     */
    public List<Scored> match(String query, int topN, ReusableBlock.Kind kind) {
        List<Scored> scored = new ArrayList<Scored>();
        for (ReusableBlock block : blocks) {
            if (kind != null && block.kind() != kind) {
                continue;
            }
            int score = block.score(query);
            if (score > 0) {
                scored.add(new Scored(block, score));
            }
        }
        Collections.sort(scored, new Comparator<Scored>() {
            @Override
            public int compare(Scored a, Scored b) {
                if (a.score != b.score) {
                    return b.score - a.score;
                }
                return a.block.id().compareTo(b.block.id());
            }
        });
        List<Scored> result = new ArrayList<Scored>();
        for (Scored item : scored) {
            if (topN > 0 && result.size() >= topN) {
                break;
            }
            result.add(item);
        }
        return result;
    }

    /** function tool / CLI 向けの整形text。 */
    public String promptText(List<Scored> matched) {
        StringBuilder out = new StringBuilder();
        if (matched.isEmpty()) {
            out.append("（条件に一致する再利用ブロックなし。勝手にテンプレートを作らないこと。）\n");
            return out.toString();
        }
        out.append("命中: ").append(matched.size()).append(" 件\n\n");
        for (Scored item : matched) {
            out.append("- id: ").append(item.block.id())
                    .append(" / kind: ").append(item.block.kind().name())
                    .append(" / score: ").append(item.score).append('\n');
            out.append("  説明: ").append(item.block.description()).append('\n');
            if (!item.block.apiSignature().isEmpty()) {
                out.append("  API: `").append(item.block.apiSignature()).append("`\n");
            }
            List<String> placeholders = com.walk_nie.bizgen.agent.variable.VariableTable
                    .placeholders(item.block.code());
            if (!placeholders.isEmpty()) {
                out.append("  必要変数: ").append(placeholders.toString()).append('\n');
            }
        }
        out.append("\n本文を取得するには apply_block_template を呼んでください。\n");
        return out.toString();
    }

    public String listText() {
        StringBuilder out = new StringBuilder();
        if (blocks.isEmpty()) {
            return "（再利用ブロックなし。spec/block/*.md に配置してください）\n";
        }
        for (ReusableBlock block : blocks) {
            out.append("- ").append(block.id()).append(" [").append(block.kind().name())
                    .append("] ").append(block.description())
                    .append("  (").append(block.source()).append(")\n");
        }
        out.append("合計: ").append(blocks.size()).append(" ブロック\n");
        return out.toString();
    }

    /** スコア付きの候補。 */
    public static final class Scored {
        private final ReusableBlock block;
        private final int score;

        public Scored(ReusableBlock block, int score) {
            this.block = block;
            this.score = score;
        }

        public ReusableBlock block() {
            return block;
        }

        public int score() {
            return score;
        }
    }
}

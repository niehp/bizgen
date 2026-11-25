package com.walk_nie.bizgen.agent.codeintel;

import com.walk_nie.bizgen.agent.config.AgentConfig;
import com.walk_nie.bizgen.agent.generator.java.JavaSourceWriter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;

/**
 * Java 編集の提案 → 差分確認 → 承認書き込みを強型状態機でOperationException なく進める。
 * モデルは提案までしかできず、承認トークンは CLI 表示のみ（ツール結果には出さない）。
 */
public final class JavaEditService {

    /** 編集提案の状態。遷移はこの列挙型だけが許可する。 */
    public enum Phase {
        PROPOSED, DIFFED, APPROVED, WRITTEN, REJECTED;

        public boolean canAdvanceTo(Phase next) {
            switch (this) {
                case PROPOSED:
                    return next == DIFFED || next == REJECTED;
                case DIFFED:
                    return next == APPROVED || next == REJECTED;
                case APPROVED:
                    return next == WRITTEN || next == REJECTED;
                default:
                    return false;
            }
        }
    }

    /** 提案の成果物。token は CLI のみが表示する。 */
    public static final class Proposal {
        public final String id;
        public final String path;
        public final String token;
        public final String summary;
        public final String rationale;
        public final String original;
        public final String proposed;
        /** 状態遷移のたびに更新するため final にしない。 */
        public Phase phase;

        public Proposal(String id, String path, String token, String summary, String rationale,
                        String original, String proposed, Phase phase) {
            this.id = id;
            this.path = path;
            this.token = token;
            this.summary = summary;
            this.rationale = rationale;
            this.original = original;
            this.proposed = proposed;
            this.phase = phase;
        }
    }

    /** 提案結果。LLM には token を渡さないため、表示用文字列を分けて持つ。 */
    public static final class ProposeOutcome {
        public final Proposal proposal;
        public final String forModel;
        public final String forHuman;

        ProposeOutcome(Proposal proposal, String forModel, String forHuman) {
            this.proposal = proposal;
            this.forModel = forModel;
            this.forHuman = forHuman;
        }
    }

    private static final int MAX_DIFF_LINES = 800;

    private final AgentConfig config;
    private final JavaIndexStore store;
    private final JavaIndexer indexer;

    public JavaEditService(AgentConfig config, JavaIndexStore store, JavaIndexer indexer) {
        this.config = config;
        this.store = store;
        this.indexer = indexer;
    }

    /** ファイル全体置換、またはメソッドの追加／置換の提案を作る。ディスクは書き換えない。 */
    public ProposeOutcome propose(String relativePath, String summary, String rationale,
                                  String newContent, String methodName, String newMethod)
            throws IOException, SQLException {
        String relative = normalize(relativePath);
        String original = readOriginal(relative);
        String proposed;
        if (newContent != null && !newContent.trim().isEmpty()) {
            proposed = stripFence(newContent);
        } else if (methodName != null && !methodName.trim().isEmpty()
                && newMethod != null && !newMethod.trim().isEmpty()) {
            String method = stripFence(newMethod);
            boolean exists = containsMethod(original, methodName);
            // 置換範囲には先行 Javadoc が含まれるため、先頭へ改行を付けて
            // 直前の } と新方法が 「}public」 と結合するのを防ぐ。
            String replacement = "\n" + method;
            proposed = exists
                    ? JavaSourceWriter.replaceMethod(original, methodName, replacement)
                    : JavaSourceWriter.insertMethods(original, method);
        } else {
            throw new IllegalArgumentException(
                    "newContent または methodName + newMethod のいずれかが必要です");
        }
        if (original.equals(proposed)) {
            throw new IllegalArgumentException("提案された変更は元ファイルと同一です");
        }
        String id = UUID.randomUUID().toString().substring(0, 8);
        String token = UUID.randomUUID().toString();
        Proposal proposal = new Proposal(id, relative, token, summary, rationale,
                original, proposed, Phase.PROPOSED);
        store.saveProposal(id, relative, Phase.PROPOSED.name(), token, summary, rationale,
                original, proposed);
        String forModel = "編集提案を作成しました。id=" + id + "。次に get_diff で差分を確認し、"
                + "人間の承認を得てから approve_java_edit を呼んでください。まだファイルは書き換わっていません。";
        String forHuman = "提案ID: " + id + "\n承認トークン: " + token
                + "\n対象: " + relative + "\n概要: " + summary + "\n根拠: " + rationale
                + "\n差分を見る: get_diff --proposal " + id
                + "\n承認する: approve-edit --proposal " + id + " --token " + token;
        return new ProposeOutcome(proposal, forModel, forHuman);
    }

    /** 提案の差分を返し、状態を DIFFED へ進める。 */
    public String diff(String proposalId) throws SQLException {
        Proposal proposal = load(proposalId);
        advance(proposal, Phase.DIFFED);
        StringBuilder out = new StringBuilder();
        out.append("提案ID: ").append(proposal.id).append("\n対象: ").append(proposal.path)
                .append("\n概要: ").append(proposal.summary).append('\n');
        out.append(JavaDiff.unified(proposal.original, proposal.proposed, MAX_DIFF_LINES));
        out.append("\n承認には approve_java_edit と CLI が表示した承認トークンが必要です。");
        return out.toString();
    }

    /** 承認トークンを検証し、バックアップを取ってから原子的に書き込む。 */
    public String approve(String proposalId, String approvalToken, boolean approved)
            throws IOException, SQLException {
        Proposal proposal = load(proposalId);
        if (!approved) {
            advance(proposal, Phase.REJECTED);
            return "提案 " + proposal.id + " を拒否しました。ファイルは変更されていません。";
        }
        if (config.editRequireApprovalToken()) {
            if (approvalToken == null || approvalToken.trim().isEmpty()) {
                throw new IllegalArgumentException(
                        "承認トークンが必要です。CLI の propose-edit / diff が表示するトークンを指定してください");
            }
            if (!proposal.token.equals(approvalToken.trim())) {
                throw new IllegalArgumentException("承認トークンが一致しません。書き込みを中止しました");
            }
        }
        advance(proposal, Phase.APPROVED);
        Path root = root();
        Path target = root.resolve(proposal.path).normalize().toAbsolutePath();
        if (!target.startsWith(root) || !target.getFileName().toString().toLowerCase().endsWith(".java")) {
            throw new IllegalArgumentException("書き込み先が索引ルート外、または Java ファイルではありません");
        }
        Files.createDirectories(target.getParent());
        if (Files.exists(target)) {
            String suffix = config.editBackupSuffix();
            if (!suffix.isEmpty()) {
                Path backup = target.resolveSibling(target.getFileName() + suffix);
                Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING);
            }
        }
        Path temp = target.resolveSibling(target.getFileName() + ".tmp");
        Files.write(temp, proposal.proposed.getBytes(StandardCharsets.UTF_8));
        Files.move(temp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        advance(proposal, Phase.WRITTEN);
        indexer.refresh();
        return "提案 " + proposal.id + " を承認し、" + proposal.path + " に書き込みました。"
                + "索引も更新済みです。必ず内容をレビューしてください。";
    }

    private void advance(Proposal proposal, Phase next) throws SQLException {
        if (!proposal.phase.canAdvanceTo(next)) {
            throw new IllegalStateException("不正な状態遷移です: " + proposal.phase + " → " + next
                    + "。propose → diff → approve の順に実行してください");
        }
        store.saveProposal(proposal.id, proposal.path, next.name(), proposal.token,
                proposal.summary, proposal.rationale, proposal.original, proposal.proposed);
        proposal.phase = next;
    }

    private Proposal load(String proposalId) throws SQLException {
        JavaIndexStore.ProposalRow row = store.proposal(proposalId);
        if (row == null) {
            throw new IllegalArgumentException("提案が見つかりません: " + proposalId);
        }
        return new Proposal(row.id, row.path, row.token, row.summary, row.rationale,
                row.original, row.proposed, Phase.valueOf(row.phase));
    }

    private String readOriginal(String relative) throws IOException, SQLException {
        String indexed = store.readSource(relative);
        if (indexed != null) {
            return indexed;
        }
        Path target = root().resolve(relative).normalize().toAbsolutePath();
        if (!Files.isRegularFile(target)) {
            throw new IllegalArgumentException("対象ファイルが見つかりません: " + relative);
        }
        return new String(Files.readAllBytes(target), StandardCharsets.UTF_8);
    }

    private Path root() {
        Path root = config.javaIndexRoot();
        if (root == null) {
            throw new IllegalStateException("path.javaIndexRoot が設定されていません");
        }
        return root.toAbsolutePath().normalize();
    }

    private static String normalize(String path) {
        String value = path.replace('\\', '/').trim();
        while (value.startsWith("./")) {
            value = value.substring(2);
        }
        if (value.isEmpty() || value.contains("..")) {
            throw new IllegalArgumentException("不正なパスです: " + path);
        }
        return value;
    }

    private static boolean containsMethod(String source, String methodName) {
        for (JavaSourceWriter.MethodBlock method : JavaSourceWriter.extractMethods(source)) {
            if (method.name().equals(methodName)) {
                return true;
            }
        }
        return false;
    }

    private static String stripFence(String text) {
        String value = text.trim();
        if (value.startsWith("```")) {
            int first = value.indexOf('\n');
            if (first > 0) {
                value = value.substring(first + 1);
            }
            int last = value.lastIndexOf("```");
            if (last >= 0) {
                value = value.substring(0, last);
            }
        }
        return value.trim();
    }

    /** 行単位 LCS による差分。巨大ファイルは全文置換として扱い、計算量を抑える。 */
    private static final class JavaDiff {
        static String unified(String before, String after, int maxLines) {
            String[] left = before.split("\\r?\\n", -1);
            String[] right = after.split("\\r?\\n", -1);
            if (left.length > maxLines || right.length > maxLines) {
                return "（ファイルが大きいため行単位差分を省略します: "
                        + left.length + " 行 → " + right.length + " 行）\n";
            }
            int n = left.length;
            int m = right.length;
            int[][] lcs = new int[n + 1][m + 1];
            for (int i = n - 1; i >= 0; i--) {
                for (int j = m - 1; j >= 0; j--) {
                    lcs[i][j] = left[i].equals(right[j])
                            ? lcs[i + 1][j + 1] + 1
                            : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
                }
            }
            StringBuilder out = new StringBuilder();
            out.append("--- 変更前\n+++ 変更後\n");
            int i = 0;
            int j = 0;
            while (i < n && j < m) {
                if (left[i].equals(right[j])) {
                    i++;
                    j++;
                } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
                    out.append("-").append(left[i]).append('\n');
                    i++;
                } else {
                    out.append("+").append(right[j]).append('\n');
                    j++;
                }
            }
            while (i < n) {
                out.append("-").append(left[i++]).append('\n');
            }
            while (j < m) {
                out.append("+").append(right[j++]).append('\n');
            }
            return out.toString();
        }
    }
}

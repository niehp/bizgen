package com.walk_nie.bizgen.agent.index;

import java.nio.file.*;
import java.util.List;

/** 第 2 件的独立可运行验证：建索引 → 从索引动态生成白名单 → 校验模型输出。 */
public final class WhitelistDemo {

    public static void main(String[] args) throws Exception {
        Path work = Files.createTempDirectory("codegen-idx-");
        try {
            // 在临时目录准备一个待索引的 Java 源文件
            Path pkgDir = work.resolve("src/main/java/com/example/repo");
            Files.createDirectories(pkgDir);
            Files.writeString(pkgDir.resolve("PaymentRepository.java"), """
                    package com.example.repo;
                    public class PaymentRepository {
                        public void findByDate() {}
                        public void save() {}
                    }
                    """);
            Files.writeString(pkgDir.resolve("ReceiptRepository.java"), """
                    package com.example.repo;
                    public class ReceiptRepository {
                        public void findOpen() {}
                    }
                    """);

            Path db = work.resolve("code_master.db");
            Path index = work.resolve("lucene_index_dir");
            CodeIndexManager.setStorage(db, index);
            CodeIndexManager.initDatabase();
            CodeIndexManager.syncProjectIndex(work.resolve("src/main/java").toString(), "com.example.repo");

            // 从刚生成的索引动态加载白名单
            ApiWhitelist whitelist = new ApiWhitelist(db);
            System.out.println("=== 从索引动态加载的白名单条目数: " + whitelist.size() + " ===");

            String[] probes = {
                    "PaymentRepository.findByDate()", // 索引中存在 → 允许
                    "ReceiptRepository.findOpen",     // 索引中存在 → 允许
                    "DangerUtils.dropAllTables"       // 索引中不存在 → 拒绝
            };
            for (String api : probes) {
                System.out.println("  " + api + " -> " + (whitelist.allows(api) ? "允许" : "拒绝"));
            }

            boolean rejected = whitelist.reject(List.of(probes)).contains(probes[2]);
            if (!whitelist.allows(probes[0]) || !rejected) {
                throw new AssertionError("动态白名单校验失败");
            }
            System.out.println("\n动态白名单自检完成。");
        } finally {
            // 清理临时目录
            try (var paths = Files.walk(work)) {
                paths.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
                    try { Files.deleteIfExists(p); } catch (Exception ignored) {}
                });
            }
        }
    }
}

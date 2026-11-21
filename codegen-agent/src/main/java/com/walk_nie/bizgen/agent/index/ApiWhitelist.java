package com.walk_nie.bizgen.agent.index;

import java.nio.file.Path;
import java.sql.*;
import java.util.HashSet;
import java.util.Set;

/**
 * 动态 API 白名单：从 CodeIndexManager 生成的 SQLite 索引（methods_index 表）
 * 读取所有已索引方法，作为「模型可调用 API」的允许集合。
 *
 * 这替代了硬编码白名单：新增方法只要被索引器扫描进库，自动进入白名单；
 * 模型给出的方法若不在索引中（即项目里不存在/未被索引），一律拒绝。
 * 白名单完全由 Java 侧生成，模型无权干预。
 */
public final class ApiWhitelist {

    private final Set<String> allowed = new HashSet<>();

    /** 默认读取当前目录的 code_master.db。 */
    public ApiWhitelist() { this(Path.of("code_master.db")); }

    public ApiWhitelist(Path sqliteFile) {
        if (!sqliteFile.toFile().exists()) {
            throw new IllegalStateException("索引库不存在，请先运行 CodeIndexManager: " + sqliteFile);
        }
        String sql = "SELECT package_name, class_name, method_name FROM methods_index";
        try (Connection conn = DriverManager.getConnection("jdbc:sqlite:" + sqliteFile);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String pkg = nullToEmpty(rs.getString("package_name"));
                String cls = nullToEmpty(rs.getString("class_name"));
                String method = nullToEmpty(rs.getString("method_name"));
                // 同时登记三种粒度：全限定名、类名.方法名、裸方法名
                allowed.add(cls + "." + method);
                allowed.add(method);
                if (!pkg.isEmpty()) allowed.add(pkg + "." + cls + "." + method);
            }
        } catch (SQLException e) {
            throw new IllegalStateException("读取索引库失败: " + e.getMessage(), e);
        }
    }

    public boolean allows(String apiCall) {
        if (apiCall == null || apiCall.isBlank()) return false;
        String normalized = apiCall.trim();
        // 容忍 "PaymentRepository.findByDate(..)" 这类带参数的写法
        int paren = normalized.indexOf('(');
        if (paren > 0) normalized = normalized.substring(0, paren).trim();
        return allowed.contains(normalized);
    }

    public int size() { return allowed.size(); }

    /** 导出允许集合快照（供 Temporal 视图 DTO 使用）。 */
    public Set<String> allowedSnapshot() { return java.util.Collections.unmodifiableSet(allowed); }

    /** 对一组 API 校验，返回不在白名单中的项。 */
    public java.util.List<String> reject(Iterable<String> apiCalls) {
        java.util.List<String> bad = new java.util.ArrayList<>();
        for (String api : apiCalls) if (!allows(api)) bad.add(api);
        return bad;
    }

    private static String nullToEmpty(String s) { return s == null ? "" : s; }
}

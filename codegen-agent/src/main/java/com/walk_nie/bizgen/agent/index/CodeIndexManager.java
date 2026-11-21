package com.walk_nie.bizgen.agent.index;

import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import org.apache.lucene.analysis.standard.StandardAnalyzer;
import org.apache.lucene.document.*;
import org.apache.lucene.index.*;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.*;
import org.apache.lucene.store.Directory;
import org.apache.lucene.store.FSDirectory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import java.util.stream.Stream;

public class CodeIndexManager {

    private static String SQLITE_URL = "jdbc:sqlite:code_master.db";
    private static String LUCENE_DIR = "lucene_index_dir";

    /** 允许注入存储位置（测试或自定义部署时指向绝对路径）。 */
    public static void setStorage(Path sqliteFile, Path luceneDir) {
        SQLITE_URL = "jdbc:sqlite:" + sqliteFile.toAbsolutePath();
        LUCENE_DIR = luceneDir.toAbsolutePath().toString();
    }

    // 实体传输对象
    public record MethodRecord(String packageName, String className, String methodName, String methodBody, int lineNumber, String filePath) {}

    public static void main(String[] args) {
        String projectRoot = "src/main/java";
        String targetPackage = "com.example";

        System.out.println("=== 1. 初始化存储底座 ===");
        initDatabase();

        System.out.println("=== 2. 执行增量扫描与双写同步 ===");
        syncProjectIndex(projectRoot, targetPackage);

        System.out.println("=== 3. 执行联合混合检索 ===");
        // 联合查询：在指定包下，全文搜索包含特定关键字的方法
        hybridSearch("com.example.service", "IllegalArgumentException");
    }

    /**
     * 初始化 SQLite 数据库与表结构
     */
    public static void initDatabase() {
        try (Connection conn = DriverManager.getConnection(SQLITE_URL);
             Statement stmt = conn.createStatement()) {
            stmt.execute("CREATE TABLE IF NOT EXISTS file_meta (file_path TEXT PRIMARY KEY, last_modified INTEGER NOT NULL);");
            stmt.execute("CREATE TABLE IF NOT EXISTS methods_index (id INTEGER PRIMARY KEY AUTOINCREMENT, package_name TEXT, class_name TEXT, method_name TEXT, method_body TEXT, line_number INTEGER, file_path TEXT);");
            stmt.execute("CREATE INDEX IF NOT EXISTS idx_file_path ON methods_index(file_path);");
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    /**
     * 核心双写同步逻辑（结合 SQLite 增量检测与 Lucene 索引更新）
     */
    public static void syncProjectIndex(String projectRoot, String targetPackage) {
        Path packageDir = Path.of(projectRoot, targetPackage.replace('.', '/'));
        if (!Files.exists(packageDir)) return;

        // 1. 从 SQLite 加载已有文件的修改时间戳缓存
        Map<String, Long> dbFileMeta = loadFileMetaFromSqlite();
        Set<String> diskFiles = new HashSet<>();
        List<Path> filesToParse = new ArrayList<>();

        try (Stream<Path> paths = Files.walk(packageDir)) {
            paths.filter(p -> p.toString().endsWith(".java")).forEach(file -> {
                String pathStr = file.toAbsolutePath().toString();
                diskFiles.add(pathStr);
                long currentMod = file.toFile().lastModified();

                // 增量判定：文件不在 SQLite 中或磁盘修改时间更新
                Long dbMod = dbFileMeta.get(pathStr);
                if (dbMod == null || currentMod > dbMod) {
                    filesToParse.add(file);
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        // 2. 找出已删除的文件
        Set<String> deletedFiles = new HashSet<>(dbFileMeta.keySet());
        deletedFiles.removeAll(diskFiles);

        // 如果没有变动且没有删除，直接跳过解析
        if (filesToParse.isEmpty() && deletedFiles.isEmpty()) {
            System.out.println("代码无变动，跳过重复解析。");
            return;
        }

        System.out.println("检测到新增/修改文件数: " + filesToParse.size() + " | 删除文件数: " + deletedFiles.size());

        // 3. 清理 SQLite 中已删除或变动文件的旧数据
        List<String> affectedFiles = new ArrayList<>(deletedFiles);
        filesToParse.forEach(p -> affectedFiles.add(p.toAbsolutePath().toString()));
        removeRecordsFromSqlite(affectedFiles);

        // 4. 多线程解析变动文件，并准备双写入库
        List<MethodRecord> parsedMethods = Collections.synchronizedList(new ArrayList<>());
        List<Map.Entry<String, Long>> newMetas = Collections.synchronizedList(new ArrayList<>());

        filesToParse.parallelStream().forEach(file -> {
            try {
                String pathStr = file.toAbsolutePath().toString();
                newMetas.add(Map.entry(pathStr, file.toFile().lastModified()));

                CompilationUnit cu = StaticJavaParser.parse(file);
                String pkgName = cu.getPackageDeclaration().map(pd -> pd.getNameAsString()).orElse("");

                cu.findAll(ClassOrInterfaceDeclaration.class).forEach(cls -> {
                    String clsName = cls.getNameAsString();
                    cls.getMethods().forEach(method -> {
                        if (method.getBody().isEmpty()) return;
                        String methodName = method.getNameAsString();
                        int line = method.getBegin().map(p -> p.line).orElse(0);

                        // 语句级换行压缩（保持不同语句独立换行，压缩单条语句内部换行）
                        List<String> stmtLines = new ArrayList<>();
                        for (com.github.javaparser.ast.stmt.Statement stmt : method.getBody().get().getStatements()) {
                            stmtLines.add(stmt.toString().replaceAll("\\s+", " ").trim());
                        }
                        String body = "{\n    " + String.join("\n    ", stmtLines) + "\n}";

                        parsedMethods.add(new MethodRecord(pkgName, clsName, methodName, body, line, pathStr));
                    });
                });
            } catch (Exception e) {
                // 忽略个别语法错误文件
            }
        });

        // 5. 写入 SQLite (作为元数据主存储)
        saveToSqlite(newMetas, parsedMethods);

        // 6. 重建或增量更新 Lucene 倒排索引（此处为全量重建或批量同步索引）
        rebuildLuceneIndex();
    }

    /**
     * 联合混合检索：利用 Lucene 同时对包名与方法体内容进行毫秒级复合全文检索
     */
    public static void hybridSearch(String targetPackage, String keyword) {
        long start = System.currentTimeMillis();
        Path indexPath = Path.of(LUCENE_DIR);

        try (Directory dir = FSDirectory.open(indexPath);
             DirectoryReader reader = DirectoryReader.open(dir)) {

            IndexSearcher searcher = new IndexSearcher(reader);
            StandardAnalyzer analyzer = new StandardAnalyzer();

            // 构建组合查询：packageName 必须匹配指定包，同时 methodBody 包含关键字
            BooleanQuery.Builder booleanQuery = new BooleanQuery.Builder();
            
            if (targetPackage != null && !targetPackage.isBlank()) {
                Query pkgQuery = new TermQuery(new Term("packageName", targetPackage));
                booleanQuery.add(pkgQuery, BooleanClause.Occur.MUST);
            }

            QueryParser parser = new QueryParser("methodBody", analyzer);
            Query keywordQuery = parser.parse(keyword);
            booleanQuery.add(keywordQuery, BooleanClause.Occur.MUST);

            TopDocs topDocs = searcher.search(booleanQuery.build(), 10);

            System.out.println("\n=== 混合检索结果 (命中: " + topDocs.totalHits.value + " 条) ===");
            for (ScoreDoc sd : topDocs.scoreDocs) {
                Document doc = searcher.doc(sd.doc);
                System.out.println("----------------------------------------");
                System.out.println("类名: " + doc.get("packageName") + "." + doc.get("className"));
                System.out.println("方法: " + doc.get("methodName") + " (行号: " + doc.get("lineNumber") + ")");
                System.out.println("文件: " + doc.get("filePath"));
                System.out.println("内容摘要:\n" + doc.get("methodBody"));
            }

        } catch (Exception e) {
            e.printStackTrace();
        }

        System.out.println("\n检索耗时: " + (System.currentTimeMillis() - start) + " ms");
    }

    // ================= SQLite 辅助持久化方法 =================

    private static Map<String, Long> loadFileMetaFromSqlite() {
        Map<String, Long> map = new HashMap<>();
        try (Connection conn = DriverManager.getConnection(SQLITE_URL);
             Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery("SELECT file_path, last_modified FROM file_meta;")) {
            while (rs.next()) {
                map.put(rs.getString("file_path"), rs.getLong("last_modified"));
            }
        } catch (SQLException e) {
            // 表不存在则忽略
        }
        return map;
    }

    private static void removeRecordsFromSqlite(List<String> filePaths) {
        String delMeta = "DELETE FROM file_meta WHERE file_path = ?;";
        String delMethods = "DELETE FROM methods_index WHERE file_path = ?;";
        try (Connection conn = DriverManager.getConnection(SQLITE_URL)) {
            conn.setAutoCommit(false);
            try (PreparedStatement p1 = conn.prepareStatement(delMeta);
                 PreparedStatement p2 = conn.prepareStatement(delMethods)) {
                for (String path : filePaths) {
                    p1.setString(1, path); p1.addBatch();
                    p2.setString(1, path); p2.addBatch();
                }
                p1.executeBatch(); p2.executeBatch();
                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    private static void saveToSqlite(List<Map.Entry<String, Long>> metas, List<MethodRecord> methods) {
        String metaSql = "INSERT OR REPLACE INTO file_meta(file_path, last_modified) VALUES(?, ?);";
        String methodSql = "INSERT INTO methods_index(package_name, class_name, method_name, method_body, line_number, file_path) VALUES(?, ?, ?, ?, ?, ?);";

        try (Connection conn = DriverManager.getConnection(SQLITE_URL)) {
            conn.setAutoCommit(false);
            try (PreparedStatement pMeta = conn.prepareStatement(metaSql);
                 PreparedStatement pMethod = conn.prepareStatement(methodSql)) {

                for (Map.Entry<String, Long> m : metas) {
                    pMeta.setString(1, m.getKey());
                    pMeta.setLong(2, m.getValue());
                    pMeta.addBatch();
                }
                pMeta.executeBatch();

                for (MethodRecord mr : methods) {
                    pMethod.setString(1, mr.packageName());
                    pMethod.setString(2, mr.className());
                    pMethod.setString(3, mr.methodName());
                    pMethod.setString(4, mr.methodBody());
                    pMethod.setInt(5, mr.lineNumber());
                    pMethod.setString(6, mr.filePath());
                    pMethod.addBatch();
                }
                pMethod.executeBatch();

                conn.commit();
            } catch (SQLException e) {
                conn.rollback();
                e.printStackTrace();
            }
        } catch (SQLException e) {
            e.printStackTrace();
        }
    }

    // ================= Lucene 索引重建方法 =================

    /**
     * 从 SQLite 中读取全量或增量数据，同步构建 Lucene 倒排索引
     */
    public static void rebuildLuceneIndex() {
        StandardAnalyzer analyzer = new StandardAnalyzer();
        Path indexPath = Path.of(LUCENE_DIR);
        IndexWriterConfig config = new IndexWriterConfig(analyzer);
        config.setOpenMode(IndexWriterConfig.OpenMode.CREATE); // 重建索引

        try (Directory directory = FSDirectory.open(indexPath)) {

            try (IndexWriter writer = new IndexWriter(directory, config);
                 Connection conn = DriverManager.getConnection(SQLITE_URL);
                 Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT package_name, class_name, method_name, method_body, line_number, file_path FROM methods_index;")) {

                while (rs.next()) {
                    Document doc = new Document();
                    doc.add(new StringField("packageName", rs.getString("package_name"), Field.Store.YES));
                    doc.add(new StringField("className", rs.getString("class_name"), Field.Store.YES));
                    doc.add(new StringField("methodName", rs.getString("method_name"), Field.Store.YES));
                    doc.add(new StringField("filePath", rs.getString("file_path"), Field.Store.YES));
                    doc.add(new StoredField("lineNumber", rs.getInt("line_number")));
                    
                    // 全文倒排索引字段
                    doc.add(new TextField("methodBody", rs.getString("method_body"), Field.Store.YES));

                    writer.addDocument(doc);
                }
                writer.commit();
                System.out.println("Lucene 倒排索引同步完成！");
            }
        } catch (IOException | SQLException e) {
            e.printStackTrace();
        }
    }
}
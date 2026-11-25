package com.walk_nie.bizgen.agent.codeintel;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** SQLite 索引の読み書き。すべてローカルファイル操作であり、外部へ送信しない。 */
public final class JavaIndexStore implements AutoCloseable {

    /** 検索ヒット。score が高いほど関連度が高く、同点は path → line の順で安定する。 */
    public static final class Hit {
        public final String path;
        public final int line;
        public final String kind;
        public final String text;
        public final int score;

        public Hit(String path, int line, String kind, String text, int score) {
            this.path = path == null ? "" : path;
            this.line = line;
            this.kind = kind == null ? "" : kind;
            this.text = text == null ? "" : text;
            this.score = score;
        }

        public String label() {
            return path + ":" + line + " [" + kind + "] " + text;
        }
    }

    private final Path dbPath;
    private Connection connection;

    public JavaIndexStore(Path dbPath) {
        this.dbPath = dbPath;
    }

    public Path dbPath() {
        return dbPath;
    }

    public void open() throws SQLException {
        if (connection != null && !connection.isClosed()) {
            return;
        }
        try {
            Class.forName("org.sqlite.JDBC");
        } catch (ClassNotFoundException e) {
            throw new SQLException("SQLite JDBC ドライバが見つかりません", e);
        }
        Path parent = dbPath.toAbsolutePath().getParent();
        if (parent != null) {
            try {
                Files.createDirectories(parent);
            } catch (Exception e) {
                throw new SQLException("索引ディレクトリの作成に失敗: " + parent, e);
            }
        }
        connection = DriverManager.getConnection("jdbc:sqlite:" + dbPath.toAbsolutePath().normalize());
        try (Statement statement = connection.createStatement()) {
            statement.execute("PRAGMA journal_mode=WAL");
            statement.execute("PRAGMA synchronous=NORMAL");
        }
        initialize();
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute("CREATE TABLE IF NOT EXISTS meta ("
                    + "key TEXT PRIMARY KEY, value TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS java_files ("
                    + "id INTEGER PRIMARY KEY, path TEXT NOT NULL UNIQUE, package_name TEXT NOT NULL, "
                    + "mtime INTEGER NOT NULL, size INTEGER NOT NULL, sha256 TEXT NOT NULL, "
                    + "source TEXT NOT NULL, line_count INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS classes ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, fqcn TEXT NOT NULL, "
                    + "simple_name TEXT NOT NULL, kind TEXT NOT NULL, modifiers TEXT NOT NULL, "
                    + "super_class TEXT NOT NULL, interfaces TEXT NOT NULL, javadoc TEXT NOT NULL, "
                    + "start_line INTEGER NOT NULL, end_line INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS methods ("
                    + "id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, file_id INTEGER NOT NULL, "
                    + "name TEXT NOT NULL, signature TEXT NOT NULL, return_type TEXT NOT NULL, "
                    + "params TEXT NOT NULL, modifiers TEXT NOT NULL, javadoc TEXT NOT NULL, "
                    + "start_line INTEGER NOT NULL, end_line INTEGER NOT NULL, "
                    + "body TEXT NOT NULL, body_hash TEXT NOT NULL, owner_class TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS fields ("
                    + "id INTEGER PRIMARY KEY, class_id INTEGER NOT NULL, name TEXT NOT NULL, "
                    + "type TEXT NOT NULL, modifiers TEXT NOT NULL, javadoc TEXT NOT NULL, "
                    + "start_line INTEGER NOT NULL, owner_class TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS imports ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, name TEXT NOT NULL, "
                    + "is_static INTEGER NOT NULL, is_wildcard INTEGER NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS calls ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, caller_name TEXT NOT NULL, "
                    + "caller_owner TEXT NOT NULL, callee_name TEXT NOT NULL, qualifier TEXT NOT NULL, "
                    + "line INTEGER NOT NULL, expression TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS refs ("
                    + "id INTEGER PRIMARY KEY, file_id INTEGER NOT NULL, name TEXT NOT NULL, "
                    + "line INTEGER NOT NULL, kind TEXT NOT NULL)");
            statement.execute("CREATE TABLE IF NOT EXISTS edit_proposals ("
                    + "id TEXT PRIMARY KEY, path TEXT NOT NULL, phase TEXT NOT NULL, "
                    + "approval_token TEXT NOT NULL, summary TEXT NOT NULL, rationale TEXT NOT NULL, "
                    + "original TEXT NOT NULL, proposed TEXT NOT NULL, created_at INTEGER NOT NULL)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_files_path ON java_files(path)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_classes_fqcn ON classes(fqcn)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_classes_simple ON classes(simple_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_methods_name ON methods(name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_methods_owner ON methods(owner_class)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_fields_name ON fields(name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_imports_name ON imports(name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calls_callee ON calls(callee_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_calls_caller ON calls(caller_name)");
            statement.execute("CREATE INDEX IF NOT EXISTS idx_refs_name ON refs(name)");
        }
    }

    /** 本文ハッシュが同じなら再解析が不要かを判定する。 */
    public boolean isCurrent(String path, String sha256) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT sha256 FROM java_files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() && sha256.equals(result.getString(1));
            }
        }
    }

    public Set<String> knownPaths() throws SQLException {
        Set<String> paths = new LinkedHashSet<String>();
        try (Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT path FROM java_files ORDER BY path")) {
            while (result.next()) {
                paths.add(result.getString(1));
            }
        }
        return paths;
    }

    public void deleteFile(String path) throws SQLException {
        Long fileId = fileId(path);
        if (fileId == null) {
            return;
        }
        try (Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM refs WHERE file_id = " + fileId);
            statement.execute("DELETE FROM calls WHERE file_id = " + fileId);
            statement.execute("DELETE FROM imports WHERE file_id = " + fileId);
            statement.execute("DELETE FROM fields WHERE class_id IN "
                    + "(SELECT id FROM classes WHERE file_id = " + fileId + ")");
            statement.execute("DELETE FROM methods WHERE class_id IN "
                    + "(SELECT id FROM classes WHERE file_id = " + fileId + ")");
            statement.execute("DELETE FROM classes WHERE file_id = " + fileId);
            statement.execute("DELETE FROM java_files WHERE id = " + fileId);
        }
    }

    private Long fileId(String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id FROM java_files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? Long.valueOf(result.getLong(1)) : null;
            }
        }
    }

    /** 1 ファイル分をトランザクション内で置き換える。 */
    public void upsert(JavaIndexModel.ParsedFile parsed) throws SQLException {
        boolean autoCommit = connection.getAutoCommit();
        connection.setAutoCommit(false);
        try {
            deleteFile(parsed.file.path);
            long fileId = insertFile(parsed.file);
            Map<String, Long> classIds = new HashMap<String, Long>();
            for (JavaIndexModel.ClassRecord record : parsed.classes) {
                long classId = insertClass(record, fileId);
                classIds.put(record.fqcn, Long.valueOf(classId));
            }
            for (JavaIndexModel.MethodRecord method : parsed.methods) {
                insertMethod(method, classId(classIds, method.ownerClass), fileId);
            }
            for (JavaIndexModel.FieldRecord field : parsed.fields) {
                insertField(field, classId(classIds, field.ownerClass));
            }
            for (JavaIndexModel.ImportRecord item : parsed.imports) {
                insertImport(item, fileId);
            }
            for (JavaIndexModel.CallRecord call : parsed.calls) {
                insertCall(call, fileId);
            }
            for (JavaIndexModel.RefRecord ref : parsed.refs) {
                insertRef(ref, fileId);
            }
            connection.commit();
        } catch (SQLException e) {
            connection.rollback();
            throw e;
        } finally {
            connection.setAutoCommit(autoCommit);
        }
    }

    private static long classId(Map<String, Long> classIds, String fqcn) {
        Long id = classIds.get(fqcn);
        return id == null ? 0L : id.longValue();
    }

    private long insertFile(JavaIndexModel.FileRecord file) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO java_files(path, package_name, mtime, size, sha256, source, line_count) "
                        + "VALUES(?,?,?,?,?,?,?)", Statement.RETURN_GENERATED_KEYS)) {
            statement.setString(1, file.path);
            statement.setString(2, file.packageName);
            statement.setLong(3, file.mtime);
            statement.setLong(4, file.size);
            statement.setString(5, file.sha256);
            statement.setString(6, file.source);
            statement.setInt(7, file.lineCount);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private long insertClass(JavaIndexModel.ClassRecord record, long fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO classes(file_id, fqcn, simple_name, kind, modifiers, super_class, "
                        + "interfaces, javadoc, start_line, end_line) VALUES(?,?,?,?,?,?,?,?,?,?)",
                Statement.RETURN_GENERATED_KEYS)) {
            statement.setLong(1, fileId);
            statement.setString(2, record.fqcn);
            statement.setString(3, record.simpleName);
            statement.setString(4, record.kind);
            statement.setString(5, record.modifiers);
            statement.setString(6, record.superClass);
            statement.setString(7, record.interfaces);
            statement.setString(8, record.javadoc);
            statement.setInt(9, record.startLine);
            statement.setInt(10, record.endLine);
            statement.executeUpdate();
            return generatedKey(statement);
        }
    }

    private void insertMethod(JavaIndexModel.MethodRecord method, long classId, long fileId)
            throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO methods(class_id, file_id, name, signature, return_type, params, "
                        + "modifiers, javadoc, start_line, end_line, body, body_hash, owner_class) "
                        + "VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)")) {
            statement.setLong(1, classId);
            statement.setLong(2, fileId);
            statement.setString(3, method.name);
            statement.setString(4, method.signature);
            statement.setString(5, method.returnType);
            statement.setString(6, method.params);
            statement.setString(7, method.modifiers);
            statement.setString(8, method.javadoc);
            statement.setInt(9, method.startLine);
            statement.setInt(10, method.endLine);
            statement.setString(11, method.body);
            statement.setString(12, method.bodyHash);
            statement.setString(13, method.ownerClass);
            statement.executeUpdate();
        }
    }

    private void insertField(JavaIndexModel.FieldRecord field, long classId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO fields(class_id, name, type, modifiers, javadoc, start_line, owner_class) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, classId);
            statement.setString(2, field.name);
            statement.setString(3, field.type);
            statement.setString(4, field.modifiers);
            statement.setString(5, field.javadoc);
            statement.setInt(6, field.startLine);
            statement.setString(7, field.ownerClass);
            statement.executeUpdate();
        }
    }

    private void insertImport(JavaIndexModel.ImportRecord item, long fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO imports(file_id, name, is_static, is_wildcard) VALUES(?,?,?,?)")) {
            statement.setLong(1, fileId);
            statement.setString(2, item.name);
            statement.setInt(3, item.staticImport ? 1 : 0);
            statement.setInt(4, item.wildcard ? 1 : 0);
            statement.executeUpdate();
        }
    }

    private void insertCall(JavaIndexModel.CallRecord call, long fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO calls(file_id, caller_name, caller_owner, callee_name, qualifier, line, expression) "
                        + "VALUES(?,?,?,?,?,?,?)")) {
            statement.setLong(1, fileId);
            statement.setString(2, call.callerName);
            statement.setString(3, call.callerOwner);
            statement.setString(4, call.calleeName);
            statement.setString(5, call.qualifier);
            statement.setInt(6, call.line);
            statement.setString(7, call.expression);
            statement.executeUpdate();
        }
    }

    private void insertRef(JavaIndexModel.RefRecord ref, long fileId) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT INTO refs(file_id, name, line, kind) VALUES(?,?,?,?)")) {
            statement.setLong(1, fileId);
            statement.setString(2, ref.name);
            statement.setInt(3, ref.line);
            statement.setString(4, ref.kind);
            statement.executeUpdate();
        }
    }

    private static long generatedKey(PreparedStatement statement) throws SQLException {
        try (ResultSet keys = statement.getGeneratedKeys()) {
            return keys.next() ? keys.getLong(1) : 0L;
        }
    }

    // ---------- 検索 ----------

    public List<Hit> searchClasses(String query, int limit) throws SQLException {
        String like = like(query);
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, c.start_line, c.fqcn, c.kind, c.javadoc "
                + "FROM classes c JOIN java_files f ON f.id = c.file_id "
                + "WHERE instr(lower(c.fqcn), ?) > 0 OR instr(lower(c.simple_name), ?) > 0 OR instr(lower(c.javadoc), ?) > 0 "
                + "ORDER BY c.fqcn LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, like);
            statement.setString(2, like);
            statement.setString(3, like);
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String text = resultSet.getString(3) + " [" + resultSet.getString(4) + "]";
                    String docs = resultSet.getString(5);
                    if (!docs.isEmpty()) {
                        text += " — " + docs;
                    }
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2),
                            "CLASS", text, 5));
                }
            }
        }
        return result;
    }

    public List<Hit> searchMethods(String query, int limit) throws SQLException {
        String like = like(query);
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, m.start_line, m.signature, m.owner_class, m.javadoc "
                + "FROM methods m JOIN java_files f ON f.id = m.file_id "
                + "WHERE instr(lower(m.name), ?) > 0 OR instr(lower(m.signature), ?) > 0 "
                + "OR instr(lower(m.javadoc), ?) > 0 OR instr(lower(m.owner_class), ?) > 0 "
                + "ORDER BY m.owner_class, m.start_line LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, like);
            statement.setString(2, like);
            statement.setString(3, like);
            statement.setString(4, like);
            statement.setInt(5, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String text = resultSet.getString(4) + "#" + resultSet.getString(3);
                    String docs = resultSet.getString(5);
                    if (!docs.isEmpty()) {
                        text += " — " + docs;
                    }
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2),
                            "METHOD", text, 4));
                }
            }
        }
        return result;
    }

    public List<Hit> searchFields(String query, int limit) throws SQLException {
        String like = like(query);
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, d.start_line, d.owner_class, d.name, d.type "
                + "FROM fields d JOIN classes c ON c.id = d.class_id "
                + "JOIN java_files f ON f.id = c.file_id "
                + "WHERE instr(lower(d.name), ?) > 0 OR instr(lower(d.owner_class), ?) > 0 OR instr(lower(d.javadoc), ?) > 0 "
                + "ORDER BY d.owner_class, d.start_line LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, like);
            statement.setString(2, like);
            statement.setString(3, like);
            statement.setInt(4, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2), "FIELD",
                            resultSet.getString(3) + "#" + resultSet.getString(4)
                                    + " : " + resultSet.getString(5), 3));
                }
            }
        }
        return result;
    }

    public List<Hit> searchImports(String query, int limit) throws SQLException {
        String like = like(query);
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, i.name FROM imports i JOIN java_files f ON f.id = i.file_id "
                + "WHERE instr(lower(i.name), ?) > 0 ORDER BY f.path, i.name LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, like);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new Hit(resultSet.getString(1), 1, "IMPORT", resultSet.getString(2), 2));
                }
            }
        }
        return result;
    }

    public List<Hit> searchSource(String query, int limit) throws SQLException {
        String like = like(query);
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT path, source FROM java_files WHERE instr(lower(source), ?) > 0 ORDER BY path LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, like);
            statement.setInt(2, Math.max(limit, 1));
            try (ResultSet resultSet = statement.executeQuery()) {
                String lowerQuery = query.toLowerCase();
                while (resultSet.next()) {
                    String path = resultSet.getString(1);
                    String source = resultSet.getString(2);
                    String[] lines = source.split("\\r?\\n");
                    for (int i = 0; i < lines.length && result.size() < limit; i++) {
                        if (lines[i].toLowerCase().contains(lowerQuery)) {
                            result.add(new Hit(path, i + 1, "SOURCE", lines[i].trim(), 1));
                        }
                    }
                }
            }
        }
        return result;
    }

    /** 索引済みファイルのソース本文を返す。未索引なら null。 */
    public String readSource(String path) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT source FROM java_files WHERE path = ?")) {
            statement.setString(1, path);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    /** クラス・メソッド・フィールド・本文を横断し、スコ順に安定ソートして返す。 */
    public List<Hit> keywordSearch(String query, int limit) throws SQLException {
        Map<String, Hit> merged = new java.util.LinkedHashMap<String, Hit>();
        List<Hit> all = new ArrayList<Hit>();
        all.addAll(searchClasses(query, limit));
        all.addAll(searchMethods(query, limit));
        all.addAll(searchFields(query, limit));
        all.addAll(searchImports(query, limit));
        all.addAll(searchSource(query, limit));
        java.util.Collections.sort(all, new java.util.Comparator<Hit>() {
            @Override
            public int compare(Hit left, Hit right) {
                int byScore = right.score - left.score;
                if (byScore != 0) {
                    return byScore;
                }
                int byPath = left.path.compareTo(right.path);
                return byPath != 0 ? byPath : left.line - right.line;
            }
        });
        for (Hit hit : all) {
            String key = hit.path + ":" + hit.line + ":" + hit.kind + ":" + hit.text;
            if (!merged.containsKey(key)) {
                merged.put(key, hit);
            }
            if (merged.size() >= limit) {
                break;
            }
        }
        return new ArrayList<Hit>(merged.values());
    }

    public List<Hit> references(String name, int limit) throws SQLException {
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, r.line, r.kind, f.source FROM refs r "
                + "JOIN java_files f ON f.id = r.file_id "
                + "WHERE lower(r.name) = lower(?) ORDER BY f.path, r.line LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    String text = sourceLine(resultSet.getString(4), resultSet.getInt(2));
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2),
                            "REF:" + resultSet.getString(3), text, 3));
                }
            }
        }
        sql = "SELECT f.path, c.line, c.caller_owner, c.caller_name, c.qualifier, c.callee_name "
                + "FROM calls c JOIN java_files f ON f.id = c.file_id "
                + "WHERE lower(c.callee_name) = lower(?) ORDER BY f.path, c.line LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setInt(2, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2), "CALL",
                            resultSet.getString(3) + "#" + resultSet.getString(4) + " → "
                                    + resultSet.getString(5) + "." + resultSet.getString(6), 5));
                }
            }
        }
        java.util.Collections.sort(result, new java.util.Comparator<Hit>() {
            @Override
            public int compare(Hit left, Hit right) {
                int byPath = left.path.compareTo(right.path);
                return byPath != 0 ? byPath : left.line - right.line;
            }
        });
        return result.size() > limit ? new ArrayList<Hit>(result.subList(0, limit)) : result;
    }

    public List<Hit> calls(String name, int limit) throws SQLException {
        List<Hit> result = new ArrayList<Hit>();
        String sql = "SELECT f.path, c.line, c.caller_owner, c.caller_name, c.callee_name, "
                + "c.qualifier, c.expression FROM calls c JOIN java_files f ON f.id = c.file_id "
                + "WHERE lower(c.callee_name) = lower(?) OR lower(c.caller_name) = lower(?) "
                + "ORDER BY f.path, c.line LIMIT ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, name);
            statement.setInt(3, limit);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new Hit(resultSet.getString(1), resultSet.getInt(2), "CALL_EDGE",
                            resultSet.getString(3) + "#" + resultSet.getString(4) + " → "
                                    + resultSet.getString(6) + "." + resultSet.getString(5)
                                    + " : " + resultSet.getString(7), 5));
                }
            }
        }
        return result;
    }

    /** 構造表示用。クラス情報 + メソッド + フィールド + import をまとめて返す。 */
    public String classStructure(String name, int maxBodyChars) throws SQLException {
        String sql = "SELECT c.id, f.path, c.fqcn, c.kind, c.modifiers, c.super_class, c.interfaces, "
                + "c.javadoc, c.start_line, c.end_line FROM classes c "
                + "JOIN java_files f ON f.id = c.file_id "
                + "WHERE lower(c.fqcn) = lower(?) OR lower(c.simple_name) = lower(?) "
                + "ORDER BY c.fqcn LIMIT 1";
        long classId = 0;
        StringBuilder out = new StringBuilder();
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, name);
            statement.setString(2, name);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return "クラスが見つかりません: " + name;
                }
                classId = resultSet.getLong(1);
                out.append("FQCN: ").append(resultSet.getString(3)).append('\n');
                out.append("種別: ").append(resultSet.getString(4)).append(' ')
                        .append(resultSet.getString(5)).append('\n');
                out.append("継承: ").append(resultSet.getString(6)).append('\n');
                out.append("実装: ").append(resultSet.getString(7)).append('\n');
                out.append("説明: ").append(resultSet.getString(8)).append('\n');
                out.append("位置: ").append(resultSet.getString(2)).append(':')
                        .append(resultSet.getInt(9)).append('-').append(resultSet.getInt(10)).append('\n');
            }
        }
        out.append("\n[import]\n");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT i.name FROM imports i WHERE i.file_id = "
                        + "(SELECT file_id FROM classes WHERE id = ?) ORDER BY i.name")) {
            statement.setLong(1, classId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.append("  ").append(resultSet.getString(1)).append('\n');
                }
            }
        }
        out.append("\n[fields]\n");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT name, type, modifiers, javadoc, start_line FROM fields "
                        + "WHERE class_id = ? ORDER BY start_line")) {
            statement.setLong(1, classId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.append("  L").append(resultSet.getInt(5)).append(' ')
                            .append(resultSet.getString(3)).append(' ')
                            .append(resultSet.getString(2)).append(' ')
                            .append(resultSet.getString(1)).append(" — ")
                            .append(resultSet.getString(4)).append('\n');
                }
            }
        }
        out.append("\n[methods]\n");
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT signature, javadoc, start_line, end_line, substr(body, 1, ?) "
                        + "FROM methods WHERE class_id = ? ORDER BY start_line")) {
            statement.setInt(1, maxBodyChars);
            statement.setLong(2, classId);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    out.append("  L").append(resultSet.getInt(3)).append('-')
                            .append(resultSet.getInt(4)).append(' ')
                            .append(resultSet.getString(1)).append('\n');
                    if (!resultSet.getString(2).isEmpty()) {
                        out.append("     説明: ").append(resultSet.getString(2)).append('\n');
                    }
                    String body = resultSet.getString(5);
                    if (!body.isEmpty()) {
                        out.append("     本文: ").append(body.replace('\n', ' ')).append('\n');
                    }
                }
            }
        }
        return out.toString();
    }

    /** 類似メソッド判定用の軽量レコード（本文は先頭だけ）。 */
    public static final class MethodSnippet {
        public final long id;
        public final String name;
        public final String signature;
        public final String ownerClass;
        public final String path;
        public final int startLine;
        public final String body;

        public MethodSnippet(long id, String name, String signature, String ownerClass,
                             String path, int startLine, String body) {
            this.id = id;
            this.name = name;
            this.signature = signature;
            this.ownerClass = ownerClass;
            this.path = path;
            this.startLine = startLine;
            this.body = body == null ? "" : body;
        }
    }

    public List<MethodSnippet> methodSnippets(int maxBodyChars) throws SQLException {
        List<MethodSnippet> result = new ArrayList<MethodSnippet>();
        String sql = "SELECT m.id, m.name, m.signature, m.owner_class, f.path, m.start_line, "
                + "substr(m.body, 1, ?) FROM methods m JOIN java_files f ON f.id = m.file_id "
                + "ORDER BY f.path, m.start_line";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxBodyChars);
            try (ResultSet resultSet = statement.executeQuery()) {
                while (resultSet.next()) {
                    result.add(new MethodSnippet(resultSet.getLong(1), resultSet.getString(2),
                            resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                            resultSet.getInt(6), resultSet.getString(7)));
                }
            }
        }
        return result;
    }

    public MethodSnippet methodSnippet(long id, int maxBodyChars) throws SQLException {
        String sql = "SELECT m.id, m.name, m.signature, m.owner_class, f.path, m.start_line, "
                + "substr(m.body, 1, ?) FROM methods m JOIN java_files f ON f.id = m.file_id "
                + "WHERE m.id = ?";
        try (PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setInt(1, maxBodyChars);
            statement.setLong(2, id);
            try (ResultSet resultSet = statement.executeQuery()) {
                if (!resultSet.next()) {
                    return null;
                }
                return new MethodSnippet(resultSet.getLong(1), resultSet.getString(2),
                        resultSet.getString(3), resultSet.getString(4), resultSet.getString(5),
                        resultSet.getInt(6), resultSet.getString(7));
            }
        }
    }

    public String stats() throws SQLException {
        StringBuilder out = new StringBuilder();
        String[] tables = {"java_files", "classes", "methods", "fields", "imports", "calls", "refs"};
        for (String table : tables) {
            try (Statement statement = connection.createStatement();
                 ResultSet result = statement.executeQuery("SELECT count(*) FROM " + table)) {
                if (result.next()) {
                    out.append(table).append('=').append(result.getLong(1)).append(' ');
                }
            }
        }
        return out.toString().trim();
    }

    // ---------- 編集提案の永続化（監査用） ----------

    public void saveProposal(String id, String path, String phase, String token, String summary,
                             String rationale, String original, String proposed) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "INSERT OR REPLACE INTO edit_proposals"
                        + "(id,path,phase,approval_token,summary,rationale,original,proposed,created_at) "
                        + "VALUES(?,?,?,?,?,?,?,?,?)")) {
            statement.setString(1, id);
            statement.setString(2, path);
            statement.setString(3, phase);
            statement.setString(4, token);
            statement.setString(5, summary);
            statement.setString(6, rationale);
            statement.setString(7, original);
            statement.setString(8, proposed);
            statement.setLong(9, System.currentTimeMillis());
            statement.executeUpdate();
        }
    }

    public String proposalPhase(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT phase FROM edit_proposals WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                return result.next() ? result.getString(1) : null;
            }
        }
    }

    public static final class ProposalRow {
        public final String id;
        public final String path;
        public final String phase;
        public final String token;
        public final String summary;
        public final String rationale;
        public final String original;
        public final String proposed;

        public ProposalRow(String id, String path, String phase, String token, String summary,
                           String rationale, String original, String proposed) {
            this.id = id;
            this.path = path;
            this.phase = phase;
            this.token = token;
            this.summary = summary;
            this.rationale = rationale;
            this.original = original;
            this.proposed = proposed;
        }
    }

    public ProposalRow proposal(String id) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "SELECT id,path,phase,approval_token,summary,rationale,original,proposed "
                        + "FROM edit_proposals WHERE id = ?")) {
            statement.setString(1, id);
            try (ResultSet result = statement.executeQuery()) {
                if (!result.next()) {
                    return null;
                }
                return new ProposalRow(result.getString(1), result.getString(2), result.getString(3),
                        result.getString(4), result.getString(5), result.getString(6),
                        result.getString(7), result.getString(8));
            }
        }
    }

    private static String like(String value) {
        return value == null ? "" : value.toLowerCase();
    }

    private static String sourceLine(String source, int line) {
        String[] lines = source.split("\\r?\\n");
        return line > 0 && line <= lines.length ? lines[line - 1].trim() : "";
    }

    @Override
    public void close() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // 閉じるだけなので失敗を無視する
            }
            connection = null;
        }
    }
}

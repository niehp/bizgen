package com.walk_nie.bizgen.agent.md;

import com.walk_nie.bizgen.agent.util.FileUtil;
import com.walk_nie.bizgen.agent.util.StrUtil;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 1 つの設計 md ファイル。先頭の --- で囲んだ front matter と本文を持つ。
 * front matter は変数定義・設計ドキュメント参照・生成モードなどのメタ情報を表す。
 */
public final class MdDoc {

    private final Path path;
    private final DesignLevel level;
    private final String name;
    private final Map<String, Object> frontMatter;
    private final String body;
    private final String title;

    public MdDoc(Path path, DesignLevel level, Map<String, Object> frontMatter, String body) {
        this.path = path;
        this.level = level;
        this.name = FileUtil.baseName(path);
        this.frontMatter = frontMatter == null
                ? new LinkedHashMap<String, Object>() : frontMatter;
        this.body = StrUtil.normalizeNewline(body == null ? "" : body).trim();
        this.title = extractTitle(this.body);
    }

    /** ファイルを読み込んで MdDoc にする。 */
    public static MdDoc parse(Path path, DesignLevel level) throws IOException {
        String text = FileUtil.read(path);
        return new MdDoc(path, level, parseFrontMatter(text), extractBody(text));
    }

    private static String extractBody(String text) {
        String normalized = StrUtil.normalizeNewline(text);
        if (!normalized.startsWith("---")) {
            return normalized;
        }
        int firstNewline = normalized.indexOf('\n');
        if (firstNewline < 0) {
            return "";
        }
        String afterOpen = normalized.substring(firstNewline + 1);
        int close = afterOpen.indexOf("\n---");
        if (close < 0) {
            return normalized;
        }
        int bodyStart = close + 4;
        if (bodyStart < afterOpen.length() && afterOpen.charAt(bodyStart) == '\n') {
            bodyStart++;
        }
        return afterOpen.substring(bodyStart);
    }

    private static Map<String, Object> parseFrontMatter(String text) {
        Map<String, Object> result = new LinkedHashMap<String, Object>();
        String normalized = StrUtil.normalizeNewline(text);
        if (!normalized.startsWith("---")) {
            return result;
        }
        int firstNewline = normalized.indexOf('\n');
        if (firstNewline < 0) {
            return result;
        }
        String afterOpen = normalized.substring(firstNewline + 1);
        int close = afterOpen.indexOf("\n---");
        if (close < 0) {
            return result;
        }
        String block = afterOpen.substring(0, close);
        String currentKey = null;
        for (String rawLine : block.split("\n")) {
            String line = rawLine.trim();
            if (line.isEmpty() || line.startsWith("#")) {
                continue;
            }
            if (line.startsWith("- ") && currentKey != null) {
                Object existing = result.get(currentKey);
                List<String> list;
                if (existing instanceof List) {
                    @SuppressWarnings("unchecked")
                    List<String> cast = (List<String>) existing;
                    list = cast;
                } else {
                    list = new ArrayList<String>();
                }
                list.add(StrUtil.trimToEmpty(line.substring(2)));
                result.put(currentKey, list);
                continue;
            }
            int colon = line.indexOf(':');
            if (colon <= 0) {
                currentKey = null;
                continue;
            }
            currentKey = line.substring(0, colon).trim();
            String value = line.substring(colon + 1).trim();
            result.put(currentKey, value);
        }
        return result;
    }

    private static String extractTitle(String body) {
        for (String line : body.split("\n")) {
            String trimmed = line.trim();
            if (trimmed.startsWith("# ")) {
                return trimmed.substring(2).trim();
            }
        }
        return "";
    }

    public Path path() {
        return path;
    }

    public DesignLevel level() {
        return level;
    }

    public String name() {
        return name;
    }

    public String body() {
        return body;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> frontMatter() {
        return Collections.unmodifiableMap(frontMatter);
    }

    public String stringValue(String key, String defaultValue) {
        Object value = frontMatter.get(key);
        return value == null || StrUtil.isBlank(String.valueOf(value))
                ? defaultValue : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    public List<String> listValue(String key) {
        Object value = frontMatter.get(key);
        if (value instanceof List) {
            return new ArrayList<String>((List<String>) value);
        }
        if (value != null && !StrUtil.isBlank(String.valueOf(value))) {
            List<String> single = new ArrayList<String>();
            single.add(String.valueOf(value).trim());
            return single;
        }
        return new ArrayList<String>();
    }

    @SuppressWarnings("unchecked")
    public Map<String, String> mapValue(String key) {
        Map<String, String> result = new LinkedHashMap<String, String>();
        Object value = frontMatter.get(key);
        if (value instanceof Map) {
            for (Map.Entry<String, Object> entry : ((Map<String, Object>) value).entrySet()) {
                result.put(entry.getKey(), String.valueOf(entry.getValue()));
            }
        } else if (value != null) {
            for (String line : String.valueOf(value).split("[\\n;]")) {
                int equal = line.indexOf('=');
                if (equal > 0) {
                    result.put(line.substring(0, equal).trim(), line.substring(equal + 1).trim());
                }
            }
        }
        return result;
    }
}

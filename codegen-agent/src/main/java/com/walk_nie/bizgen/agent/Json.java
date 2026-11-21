package com.walk_nie.bizgen.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 极简 JSON 解析器 + 序列化器（零外部依赖）。
 * 仅用于骨架演示：把 vLLM 回填的 JSON 文本解析为 Map/List/标量，
 * 再由 RecordMapper 还原成 record。生产环境可替换为 Jackson。
 *
 * 支持：对象、数组、字符串（含转义）、数字、true/false/null。
 */
public final class Json {
    private Json() {}

    public static Object parse(String text) {
        if (text == null) throw new IllegalArgumentException("json 文本为 null");
        Parser p = new Parser(text);
        Object value = p.readValue();
        p.skipWhitespace();
        if (!p.atEnd()) throw p.error("存在多余字符");
        return value;
    }

    /** 把字符串按 JSON 规则转义并加引号。 */
    public static String quote(String s) {
        if (s == null) return "null";
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
                }
            }
        }
        return out.append('"').toString();
    }

    private static final class Parser {
        private final String src;
        private int pos;

        Parser(String src) { this.src = src; }

        boolean atEnd() { return pos >= src.length(); }

        void skipWhitespace() {
            while (!atEnd()) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\n' || c == '\r' || c == '\t') pos++;
                else break;
            }
        }

        IllegalArgumentException error(String msg) {
            return new IllegalArgumentException("JSON 解析失败 @" + pos + ": " + msg);
        }

        Object readValue() {
            skipWhitespace();
            if (atEnd()) throw error("意外结束");
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> readObject();
                case '[' -> readArray();
                case '"' -> readString();
                case 't' -> readLiteral("true", Boolean.TRUE);
                case 'f' -> readLiteral("false", Boolean.FALSE);
                case 'n' -> readLiteral("null", null);
                default -> readNumber();
            };
        }

        private Map<String, Object> readObject() {
            pos++; // {
            Map<String, Object> map = new LinkedHashMap<>();
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == '}') { pos++; return map; }
            while (true) {
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != '"') throw error("对象键必须是字符串");
                String key = readString();
                skipWhitespace();
                if (atEnd() || src.charAt(pos) != ':') throw error("缺少冒号");
                pos++;
                map.put(key, readValue());
                skipWhitespace();
                if (atEnd()) throw error("对象未闭合");
                char sep = src.charAt(pos++);
                if (sep == '}') return map;
                if (sep != ',') throw error("对象分隔符应为 , 或 }");
            }
        }

        private List<Object> readArray() {
            pos++; // [
            List<Object> list = new ArrayList<>();
            skipWhitespace();
            if (!atEnd() && src.charAt(pos) == ']') { pos++; return list; }
            while (true) {
                list.add(readValue());
                skipWhitespace();
                if (atEnd()) throw error("数组未闭合");
                char sep = src.charAt(pos++);
                if (sep == ']') return list;
                if (sep != ',') throw error("数组分隔符应为 , 或 ]");
            }
        }

        private String readString() {
            pos++; // "
            StringBuilder sb = new StringBuilder();
            while (!atEnd()) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c == '\\') {
                    if (atEnd()) throw error("转义字符未完成");
                    char e = src.charAt(pos++);
                    switch (e) {
                        case '"', '\\', '/' -> sb.append(e);
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > src.length()) throw error("Unicode 转义不完整");
                            String hex = src.substring(pos, pos + 4);
                            try { sb.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException ex) { throw error("非法 Unicode 转义: " + hex); }
                            pos += 4;
                        }
                        default -> throw error("非法转义: \\" + e);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw error("字符串未闭合");
        }

        private Object readNumber() {
            int start = pos;
            if (!atEnd() && src.charAt(pos) == '-') pos++;
            while (!atEnd() && Character.isDigit(src.charAt(pos))) pos++;
            boolean decimal = false;
            if (!atEnd() && src.charAt(pos) == '.') {
                decimal = true; pos++;
                while (!atEnd() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (!atEnd() && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                decimal = true; pos++;
                if (!atEnd() && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                while (!atEnd() && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (start == pos) throw error("非法数字");
            String num = src.substring(start, pos);
            try {
                return decimal ? (Object) Double.valueOf(num) : (Object) Long.valueOf(num);
            } catch (NumberFormatException ex) {
                throw error("非法数字: " + num);
            }
        }

        private Object readLiteral(String literal, Object value) {
            if (!src.startsWith(literal, pos)) throw error("非法字面量");
            pos += literal.length();
            return value;
        }
    }
}

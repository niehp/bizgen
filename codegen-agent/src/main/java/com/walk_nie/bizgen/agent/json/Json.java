package com.walk_nie.bizgen.agent.json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 外部ライブラリに依存しない軽量 JSON パーサ／シリアライザ。
 * ローカル LLM のリクエスト／レスポンスと、LLM 出力の構造化結果の解釈にのみ使用する。
 * 型は Map / List / String / Number / Boolean / null に限定する。
 */
public final class Json {

    private Json() {
    }

    /** JSON 文字列をパースして Java オブジェクトに変換する。 */
    public static Object parse(String text) {
        if (text == null) {
            throw new IllegalArgumentException("JSON テキストが null です。");
        }
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skipWhitespace();
        if (!parser.atEnd()) {
            throw new IllegalArgumentException("JSON の末尾に不正な文字があります: " + parser.snippet());
        }
        return value;
    }

    /** Object を Map として扱う（安全なキャストヘルパ）。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> asObject(Object value) {
        if (value instanceof Map) {
            return (Map<String, Object>) value;
        }
        return new LinkedHashMap<String, Object>();
    }

    /** Object を List として扱う（安全なキャストヘルパ）。 */
    @SuppressWarnings("unchecked")
    public static List<Object> asArray(Object value) {
        if (value instanceof List) {
            return (List<Object>) value;
        }
        return new ArrayList<Object>();
    }

    /** Object を文字列として扱う。 */
    public static String asString(Object value) {
        return value == null ? "" : String.valueOf(value);
    }

    /** Object を整数として扱う。 */
    public static int asInt(Object value, int defaultValue) {
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        if (value instanceof String) {
            try {
                return Integer.parseInt(((String) value).trim());
            } catch (NumberFormatException ignored) {
                return defaultValue;
            }
        }
        return defaultValue;
    }

    /** Object を真偽値として扱う。 */
    public static boolean asBoolean(Object value, boolean defaultValue) {
        if (value instanceof Boolean) {
            return (Boolean) value;
        }
        if (value instanceof String) {
            return Boolean.parseBoolean(((String) value).trim());
        }
        return defaultValue;
    }

    /** Java オブジェクトを JSON 文字列に変換する。 */
    public static String stringify(Object value) {
        StringBuilder out = new StringBuilder();
        write(value, out);
        return out.toString();
    }

    private static void write(Object value, StringBuilder out) {
        if (value == null) {
            out.append("null");
        } else if (value instanceof String) {
            writeString((String) value, out);
        } else if (value instanceof Boolean || value instanceof Number) {
            out.append(String.valueOf(value));
        } else if (value instanceof Map) {
            out.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) value).entrySet()) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                writeString(String.valueOf(entry.getKey()), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (value instanceof Iterable) {
            out.append('[');
            boolean first = true;
            for (Object item : (Iterable<?>) value) {
                if (!first) {
                    out.append(',');
                }
                first = false;
                write(item, out);
            }
            out.append(']');
        } else {
            // 未知の型は文字列として扱う（LLM への入力で型落ちしないため）
            writeString(String.valueOf(value), out);
        }
    }

    private static void writeString(String text, StringBuilder out) {
        out.append('"');
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\b':
                    out.append("\\b");
                    break;
                case '\f':
                    out.append("\\f");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
    }

    /** 再帰下降パーサ。 */
    private static final class Parser {
        private final String text;
        private int index;

        Parser(String text) {
            this.text = text;
        }

        boolean atEnd() {
            return index >= text.length();
        }

        String snippet() {
            int end = Math.min(text.length(), index + 40);
            return text.substring(Math.min(index, end), end);
        }

        void skipWhitespace() {
            while (!atEnd() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        Object parseValue() {
            skipWhitespace();
            if (atEnd()) {
                throw new IllegalArgumentException("JSON が途中で終わっています。");
            }
            char c = text.charAt(index);
            switch (c) {
                case '{':
                    return parseObject();
                case '[':
                    return parseArray();
                case '"':
                    return parseString();
                case 't':
                    expect("true");
                    return Boolean.TRUE;
                case 'f':
                    expect("false");
                    return Boolean.FALSE;
                case 'n':
                    expect("null");
                    return null;
                default:
                    return parseNumber();
            }
        }

        private Map<String, Object> parseObject() {
            Map<String, Object> map = new LinkedHashMap<String, Object>();
            index++; // '{'
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == '}') {
                index++;
                return map;
            }
            while (true) {
                skipWhitespace();
                if (atEnd() || text.charAt(index) != '"') {
                    throw new IllegalArgumentException("オブジェクトのキーが文字列ではありません: " + snippet());
                }
                String key = parseString();
                skipWhitespace();
                if (atEnd() || text.charAt(index) != ':') {
                    throw new IllegalArgumentException("キーと値の区切り ':' がありません: " + snippet());
                }
                index++;
                Object value = parseValue();
                map.put(key, value);
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("オブジェクトが閉じられていません。");
                }
                char separator = text.charAt(index++);
                if (separator == '}') {
                    return map;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("オブジェクトの区切りが不正です: " + snippet());
                }
            }
        }

        private List<Object> parseArray() {
            List<Object> list = new ArrayList<Object>();
            index++; // '['
            skipWhitespace();
            if (!atEnd() && text.charAt(index) == ']') {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skipWhitespace();
                if (atEnd()) {
                    throw new IllegalArgumentException("配列が閉じられていません。");
                }
                char separator = text.charAt(index++);
                if (separator == ']') {
                    return list;
                }
                if (separator != ',') {
                    throw new IllegalArgumentException("配列の区切りが不正です: " + snippet());
                }
            }
        }

        private String parseString() {
            index++; // '"'
            StringBuilder out = new StringBuilder();
            while (!atEnd()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return out.toString();
                }
                if (c == '\\') {
                    if (atEnd()) {
                        break;
                    }
                    char escaped = text.charAt(index++);
                    switch (escaped) {
                        case '"':
                            out.append('"');
                            break;
                        case '\\':
                            out.append('\\');
                            break;
                        case '/':
                            out.append('/');
                            break;
                        case 'b':
                            out.append('\b');
                            break;
                        case 'f':
                            out.append('\f');
                            break;
                        case 'n':
                            out.append('\n');
                            break;
                        case 'r':
                            out.append('\r');
                            break;
                        case 't':
                            out.append('\t');
                            break;
                        case 'u':
                            if (index + 4 > text.length()) {
                                throw new IllegalArgumentException("Unicode エスケープが不正です。");
                            }
                            String hex = text.substring(index, index + 4);
                            out.append((char) Integer.parseInt(hex, 16));
                            index += 4;
                            break;
                        default:
                            out.append(escaped);
                    }
                } else {
                    out.append(c);
                }
            }
            throw new IllegalArgumentException("文字列が閉じられていません: " + out);
        }

        private Object parseNumber() {
            int start = index;
            while (!atEnd() && isNumberChar(text.charAt(index))) {
                index++;
            }
            if (start == index) {
                throw new IllegalArgumentException("不正な値です: " + snippet());
            }
            String number = text.substring(start, index);
            if (number.indexOf('.') >= 0 || number.indexOf('e') >= 0 || number.indexOf('E') >= 0) {
                return Double.valueOf(number);
            }
            return Long.valueOf(number);
        }

        private boolean isNumberChar(char c) {
            return c == '-' || c == '+' || c == '.' || (c >= '0' && c <= '9')
                    || c == 'e' || c == 'E';
        }

        private void expect(String literal) {
            if (!text.startsWith(literal, index)) {
                throw new IllegalArgumentException(literal + " を期待しました: " + snippet());
            }
            index += literal.length();
        }
    }
}

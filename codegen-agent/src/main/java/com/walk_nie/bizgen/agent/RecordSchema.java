package com.walk_nie.bizgen.agent;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * record 派生 JSON Schema + Map 还原 record。
 *
 * 单一真源：Schema 与反序列化都读同一组 RecordComponent，
 * 因此「模型输出结构」与「Java 类型」不可能漂移。
 * 支持：String / enum / 整数 / 浮点 / boolean / List / 嵌套 record。
 */
public final class RecordSchema {

    private RecordSchema() {}

    /** 生成 draft-2020-12 风格 JSON Schema（扁平、additionalProperties=false）。 */
    public static String toJsonSchema(Class<?> recordType) {
        if (!recordType.isRecord()) throw new IllegalArgumentException("只支持 record: " + recordType);
        StringBuilder sb = new StringBuilder();
        appendObject(sb, recordType, 0);
        return sb.toString();
    }

    private static void appendObject(StringBuilder sb, Class<?> type, int depth) {
        if (depth > 2) throw new IllegalStateException("schema 超过 2 层，请压平 record");
        sb.append('{')
          .append("\"type\":\"object\",")
          .append("\"additionalProperties\":false,")
          .append("\"properties\":{");
        RecordComponent[] components = type.getRecordComponents();
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(components[i].getName())).append(':');
            appendField(sb, components[i].getGenericType(), components[i].getType(), depth + 1);
        }
        sb.append("},\"required\":[");
        for (int i = 0; i < components.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(Json.quote(components[i].getName()));
        }
        sb.append("]}");
    }

    private static void appendField(StringBuilder sb, Type generic, Class<?> raw, int depth) {
        // List<T> / 嵌套泛型
        if (generic instanceof ParameterizedType pt) {
            Class<?> container = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(container)) {
                sb.append("{\"type\":\"array\",\"items\":");
                Type item = pt.getActualTypeArguments()[0];
                if (item instanceof Class<?> ic) appendField(sb, item, ic, depth + 1);
                else appendField(sb, item, Object.class, depth + 1);
                sb.append('}');
                return;
            }
        }
        if (raw == String.class) {
            sb.append("{\"type\":\"string\"}");
        } else if (raw.isEnum()) {
            sb.append("{\"type\":\"string\",\"enum\":[");
            Object[] constants = raw.getEnumConstants();
            for (int i = 0; i < constants.length; i++) {
                if (i > 0) sb.append(',');
                sb.append(Json.quote(((Enum<?>) constants[i]).name()));
            }
            sb.append("]}");
        } else if (raw == int.class || raw == Integer.class || raw == long.class || raw == Long.class) {
            sb.append("{\"type\":\"integer\"}");
        } else if (raw == BigDecimal.class || raw == double.class || raw == Double.class
                || raw == float.class || raw == Float.class) {
            sb.append("{\"type\":\"number\"}");
        } else if (raw == boolean.class || raw == Boolean.class) {
            sb.append("{\"type\":\"boolean\"}");
        } else if (raw.isRecord()) {
            appendObject(sb, raw, depth);
        } else {
            throw new IllegalStateException("不支持的字段类型: " + raw.getName());
        }
    }

    /** 把 Json.parse 得到的 Map 还原为 record 实例（严格：缺字段即报错）。 */
    @SuppressWarnings("unchecked")
    public static <T> T fromMap(Map<String, Object> map, Class<T> recordType) {
        if (!recordType.isRecord()) throw new IllegalArgumentException("只支持 record: " + recordType);
        try {
            RecordComponent[] components = recordType.getRecordComponents();
            Class<?>[] paramTypes = new Class<?>[components.length];
            Object[] args = new Object[components.length];
            for (int i = 0; i < components.length; i++) {
                RecordComponent rc = components[i];
                paramTypes[i] = rc.getType();
                if (!map.containsKey(rc.getName())) {
                    throw new IllegalArgumentException("模型输出缺少字段: " + rc.getName());
                }
                args[i] = convert(map.get(rc.getName()), rc.getGenericType(), rc.getType(), rc.getName());
            }
            var ctor = recordType.getDeclaredConstructor(paramTypes);
            ctor.setAccessible(true);
            return ctor.newInstance(args);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("还原 record 失败: " + recordType.getSimpleName(), e);
        }
    }

    @SuppressWarnings("unchecked")
    private static Object convert(Object value, Type generic, Class<?> raw, String fieldName) {
        if (value == null) return null;
        if (generic instanceof ParameterizedType pt) {
            Class<?> container = (Class<?>) pt.getRawType();
            if (List.class.isAssignableFrom(container)) {
                List<Object> src = (List<Object>) value;
                Type itemType = pt.getActualTypeArguments()[0];
                Class<?> itemRaw = itemType instanceof Class<?> c ? c : Object.class;
                List<Object> out = new ArrayList<>();
                for (Object element : src) out.add(convert(element, itemType, itemRaw, fieldName + "[]"));
                return out;
            }
        }
        if (raw == String.class) return String.valueOf(value);
        if (raw.isEnum()) {
            String name = String.valueOf(value);
            for (Object constant : raw.getEnumConstants()) {
                if (((Enum<?>) constant).name().equals(name)) return constant;
            }
            throw new IllegalArgumentException("字段 " + fieldName + " 的枚举值非法: " + name);
        }
        if (raw == int.class || raw == Integer.class) return ((Number) value).intValue();
        if (raw == long.class || raw == Long.class) return ((Number) value).longValue();
        if (raw == BigDecimal.class) return BigDecimal.valueOf(((Number) value).doubleValue());
        if (raw == double.class || raw == Double.class) return ((Number) value).doubleValue();
        if (raw == float.class || raw == Float.class) return ((Number) value).floatValue();
        if (raw == boolean.class || raw == Boolean.class) return (Boolean) value;
        if (raw.isRecord()) {
            return fromMap((Map<String, Object>) value, (Class<?>) raw);
        }
        throw new IllegalStateException("字段 " + fieldName + " 类型不支持: " + raw.getName());
    }
}

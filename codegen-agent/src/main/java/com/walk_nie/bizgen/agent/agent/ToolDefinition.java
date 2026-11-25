package com.walk_nie.bizgen.agent.agent;

import com.walk_nie.bizgen.agent.json.Json;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * function tool の定義。LLM には OpenAI 互換の JSON Schema として渡す。
 * 引数検証もこの定義に基づき、コード側で 100% 決定論的に行う。
 */
public final class ToolDefinition {

    private final String name;
    private final String description;
    private final Map<String, Object> parameters;
    private final List<String> required;

    public ToolDefinition(String name, String description,
                          Map<String, Object> parameters, List<String> required) {
        this.name = name;
        this.description = description;
        this.parameters = parameters == null ? new LinkedHashMap<String, Object>() : parameters;
        this.required = required == null ? new ArrayList<String>() : required;
    }

    public String name() {
        return name;
    }

    public String description() {
        return description;
    }

    public Map<String, Object> parameters() {
        return parameters;
    }

    public List<String> required() {
        return required;
    }

    /** OpenAI 互換 API の tools 配列要素。 */
    public Map<String, Object> toJsonObject() {
        Map<String, Object> function = new LinkedHashMap<String, Object>();
        function.put("name", name);
        function.put("description", description);
        function.put("parameters", parameters);
        Map<String, Object> wrapper = new LinkedHashMap<String, Object>();
        wrapper.put("type", "function");
        wrapper.put("function", function);
        return wrapper;
    }

    /** 必須パラメータの存在と型を検証する。問題があればエラーメッセージを返す。 */
    public List<String> validate(Map<String, Object> arguments) {
        List<String> errors = new ArrayList<String>();
        for (String key : required) {
            Object value = arguments.get(key);
            if (value == null || (value instanceof String && ((String) value).trim().isEmpty())) {
                errors.add("必須パラメータ '" + key + "' がありません");
            }
        }
        Object properties = parameters.get("properties");
        if (properties instanceof Map) {
            for (Map.Entry<?, ?> entry : ((Map<?, ?>) properties).entrySet()) {
                String key = String.valueOf(entry.getKey());
                Object value = arguments.get(key);
                if (value == null) {
                    continue;
                }
                Map<String, Object> schema = Json.asObject(entry.getValue());
                String type = Json.asString(schema.get("type"));
                if ("string".equals(type) && !(value instanceof String)) {
                    errors.add("パラメータ '" + key + "' は文字列である必要があります");
                } else if ("boolean".equals(type) && !(value instanceof Boolean)) {
                    errors.add("パラメータ '" + key + "' は真偽値である必要があります");
                } else if ("integer".equals(type) && !(value instanceof Number)) {
                    errors.add("パラメータ '" + key + "' は整数である必要があります");
                }
            }
        }
        return errors;
    }

    /** JSON Schema の object 型を組み立てるための小さなヘルパ。 */
    public static Map<String, Object> objectSchema(Map<String, Object> properties, List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<String, Object>();
        schema.put("type", "object");
        schema.put("properties", properties);
        schema.put("required", required);
        schema.put("additionalProperties", Boolean.FALSE);
        return schema;
    }

    public static Map<String, Object> stringProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    public static Map<String, Object> integerProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "integer");
        property.put("description", description);
        return property;
    }

    public static Map<String, Object> booleanProperty(String description) {
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "boolean");
        property.put("description", description);
        return property;
    }

    public static Map<String, Object> arrayProperty(String description) {
        Map<String, Object> items = new LinkedHashMap<String, Object>();
        items.put("type", "string");
        Map<String, Object> property = new LinkedHashMap<String, Object>();
        property.put("type", "array");
        property.put("items", items);
        property.put("description", description);
        return property;
    }
}

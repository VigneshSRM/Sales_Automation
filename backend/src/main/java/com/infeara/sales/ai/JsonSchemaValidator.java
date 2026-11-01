package com.infeara.sales.ai;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Minimal JSON-Schema (draft 2020-12 subset) validator: {@code type}, {@code required},
 * {@code properties}, {@code items}, {@code enum}, {@code minimum}, {@code maximum},
 * {@code minLength}, {@code maxLength} and {@code additionalProperties: false}.
 *
 * <p>Deliberately small: it covers the schemas this application owns, and because it is conservative
 * an unknown keyword can only make validation stricter, never looser.
 */
@Component
public class JsonSchemaValidator {

    private final ObjectMapper objectMapper;

    public JsonSchemaValidator(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public List<String> validate(JsonNode value, String schemaJson) {
        List<String> errors = new ArrayList<>();
        if (schemaJson == null || schemaJson.isBlank()) {
            return errors;
        }
        try {
            check(value, objectMapper.readTree(schemaJson), "$", errors);
        } catch (Exception ex) {
            errors.add("$.<schema>: unreadable schema");
        }
        return errors;
    }

    private void check(JsonNode value, JsonNode schema, String path, List<String> errors) {
        if (schema == null || !schema.isObject()) {
            return;
        }
        JsonNode enumNode = schema.get("enum");
        if (enumNode != null && enumNode.isArray()) {
            boolean found = false;
            for (JsonNode allowed : enumNode) {
                if (allowed.equals(value)) {
                    found = true;
                    break;
                }
            }
            if (!found) {
                errors.add(path + ": value is not one of the allowed values");
            }
        }

        String type = schema.has("type") ? schema.get("type").asText() : null;
        if (type != null && !matchesType(value, type)) {
            errors.add(path + ": expected " + type);
            return;
        }

        if (value != null && value.isObject()) {
            for (JsonNode required : iterable(schema.get("required"))) {
                if (!value.has(required.asText())) {
                    errors.add(path + "." + required.asText() + ": is required");
                }
            }
            JsonNode properties = schema.get("properties");
            if (properties != null && properties.isObject()) {
                for (String field : propertyNames(properties)) {
                    if (value.has(field)) {
                        check(value.get(field), properties.get(field), path + "." + field, errors);
                    }
                }
            }
            boolean closed = schema.has("additionalProperties") && !schema.get("additionalProperties").asBoolean();
            if (closed && properties != null) {
                for (String field : propertyNames(value)) {
                    if (!properties.has(field)) {
                        errors.add(path + "." + field + ": is not an allowed property");
                    }
                }
            }
        }

        if (value != null && value.isArray()) {
            JsonNode items = schema.get("items");
            for (int index = 0; index < value.size(); index++) {
                check(value.get(index), items, path + "[" + index + "]", errors);
            }
        }

        if (value != null && value.isNumber()) {
            if (schema.has("minimum") && value.asDouble() < schema.get("minimum").asDouble()) {
                errors.add(path + ": is below the minimum");
            }
            if (schema.has("maximum") && value.asDouble() > schema.get("maximum").asDouble()) {
                errors.add(path + ": is above the maximum");
            }
        }

        if (value != null && value.isTextual()) {
            int length = value.asText().length();
            if (schema.has("minLength") && length < schema.get("minLength").asInt()) {
                errors.add(path + ": is shorter than the minimum length");
            }
            if (schema.has("maxLength") && length > schema.get("maxLength").asInt()) {
                errors.add(path + ": is longer than the maximum length");
            }
        }
    }

    /**
     * Names of an object node's properties.
     *
     * <p>Deliberately goes through {@code ObjectMapper.convertValue} instead of the {@code JsonNode}
     * iteration API: Jackson 3 renamed those methods ({@code fieldNames} → {@code propertyNames} →
     * {@code properties}), and a conversion is version-independent.
     */
    @SuppressWarnings("unchecked")
    private java.util.Collection<String> propertyNames(JsonNode node) {
        java.util.Map<String, Object> asMap = objectMapper.convertValue(node, java.util.Map.class);
        return asMap.keySet();
    }

    private boolean matchesType(JsonNode value, String type) {
        if (value == null || value.isNull()) {
            return !"object".equals(type) && !"array".equals(type) && !"string".equals(type)
                    && !"number".equals(type) && !"boolean".equals(type);
        }
        return switch (type) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "number", "integer" -> value.isNumber();
            case "boolean" -> value.isBoolean();
            default -> true;
        };
    }

    private Iterable<JsonNode> iterable(JsonNode node) {
        return node != null && node.isArray() ? node : List.of();
    }
}

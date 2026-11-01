package com.infeara.sales.common.json;

import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * Single point of JSON (de)serialisation for JSON columns, snapshots and hashes.
 *
 * <p>Spring Boot 4 ships Jackson 3 ({@code tools.jackson.*}) — all Jackson usage is
 * funnelled through this class so that a provider/library change (ADR-001) touches
 * exactly one file. JSON content loaded from the database is untrusted: it is parsed
 * into typed records, never evaluated.
 */
@Component
public class JsonCodec {

    private final ObjectMapper mapper;

    public JsonCodec(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public static JsonCodec standalone() {
        return new JsonCodec(JsonMapper.builder().build());
    }

    public String write(Object value) {
        return value == null ? null : mapper.writeValueAsString(value);
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return mapper.readValue(json, type);
    }

    public <T> T read(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        return mapper.readValue(json, type);
    }

    public Map<String, Object> readMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        return mapper.readValue(json, new TypeReference<Map<String, Object>>() {
        });
    }

    public List<Map<String, Object>> readListOfMaps(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return mapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
        });
    }

    public JsonNode readTree(String json) {
        return json == null || json.isBlank() ? null : mapper.readTree(json);
    }

    public boolean isValidJson(String json) {
        try {
            mapper.readTree(json);
            return true;
        } catch (RuntimeException ex) {
            return false;
        }
    }
}

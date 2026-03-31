package io.eventlens.core.metadata;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;

final class MetadataJsonCodec {

    private static final ObjectMapper MAPPER = new ObjectMapper().findAndRegisterModules();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private MetadataJsonCodec() {
    }

    static String writeList(List<String> values) {
        return write(values == null ? List.of() : values);
    }

    static String writeMap(Map<String, String> values) {
        return write(values == null ? Map.of() : values);
    }

    static String writeObjectMap(Map<String, Object> values) {
        return write(values == null ? Map.of() : values);
    }

    static List<String> readList(String raw) {
        return read(raw, STRING_LIST, List.of());
    }

    static Map<String, String> readMap(String raw) {
        return read(raw, STRING_MAP, Map.of());
    }

    private static String write(Object value) {
        try {
            return MAPPER.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encode metadata JSON", e);
        }
    }

    private static <T> T read(String raw, TypeReference<T> type, T fallback) {
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return MAPPER.readValue(raw, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decode metadata JSON", e);
        }
    }
}

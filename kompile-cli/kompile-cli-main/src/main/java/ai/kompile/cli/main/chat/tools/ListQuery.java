package ai.kompile.cli.main.chat.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Shared, bounded query contract for agent-facing process and task lists. */
record ListQuery(String query, String status, String sortBy, String sortDir,
                 int offset, int limit, boolean includeDetails) {
    static final int DEFAULT_LIMIT = 20;
    static final int MAX_LIMIT = 100;

    static void addSchema(ObjectNode props, List<String> statuses, List<String> sortKeys,
                          String defaultSort, String defaultDirection) {
        props.putObject("query").put("type", "string").put("description",
                "Case-insensitive literal substring search across IDs and descriptive fields; combined with other filters.");
        enumSchema(props, "status", statuses, "all", "Status filter (default: all).");
        enumSchema(props, "sort_by", sortKeys, defaultSort, "Sort matching entries before pagination.");
        enumSchema(props, "sort_dir", List.of("asc", "desc"), defaultDirection, "Sort direction.");
        props.putObject("offset").put("type", "integer").put("minimum", 0).put("default", 0)
                .put("description", "Zero-based offset into filtered, sorted matches.");
        props.putObject("limit").put("type", "integer").put("minimum", 1).put("maximum", MAX_LIMIT)
                .put("default", DEFAULT_LIMIT).put("description", "Page size (default 20, maximum 100).");
        props.putObject("include_details").put("type", "boolean").put("default", false)
                .put("description", "Include descriptions/provenance and, for processes, metadata and recent log previews. Default: compact rows only.");
    }

    static void enumSchema(ObjectNode props, String name, List<String> values, String fallback, String description) {
        ObjectNode field = props.putObject(name).put("type", "string").put("default", fallback)
                .put("description", description);
        values.forEach(field.putArray("enum")::add);
    }

    static ListQuery parse(JsonNode params, List<String> statuses, List<String> sortKeys,
                           String defaultSort, String defaultDirection) {
        if (params.has("include_details") && !params.get("include_details").isBoolean()) {
            throw new IllegalArgumentException("include_details must be a boolean");
        }
        return new ListQuery(text(params, "query", "").trim().toLowerCase(Locale.ROOT),
                choice(params, "status", statuses, "all"),
                choice(params, "sort_by", sortKeys, defaultSort),
                choice(params, "sort_dir", List.of("asc", "desc"), defaultDirection),
                integer(params, "offset", 0, 0, Integer.MAX_VALUE),
                integer(params, "limit", DEFAULT_LIMIT, 1, MAX_LIMIT),
                params.path("include_details").asBoolean(false));
    }

    static String text(JsonNode params, String name, String fallback) {
        if (!params.has(name)) return fallback;
        if (!params.get(name).isTextual()) throw new IllegalArgumentException(name + " must be a string");
        return params.get(name).asText();
    }

    static String choice(JsonNode params, String name, List<String> values, String fallback) {
        String value = text(params, name, fallback).trim().toLowerCase(Locale.ROOT);
        if (!values.contains(value)) throw new IllegalArgumentException(name + " must be one of: " + String.join(", ", values));
        return value;
    }

    private static int integer(JsonNode params, String name, int fallback, int min, int max) {
        if (!params.has(name)) return fallback;
        JsonNode node = params.get(name);
        if (!node.isIntegralNumber() || !node.canConvertToInt() || node.intValue() < min || node.intValue() > max) {
            throw new IllegalArgumentException(name + " must be an integer between " + min + " and " + max);
        }
        return node.intValue();
    }

    boolean matches(String... values) {
        if (query.isEmpty()) return true;
        for (String value : values) {
            if (value != null && value.toLowerCase(Locale.ROOT).contains(query)) return true;
        }
        return false;
    }

    <T> List<T> page(List<T> matches) {
        int start = Math.min(offset, matches.size());
        return matches.subList(start, start + Math.min(limit, matches.size() - start));
    }

    Map<String, Object> metadata(int total, int matched, int returned) {
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("total", total);
        metadata.put("matched", matched);
        metadata.put("returned", returned);
        metadata.put("offset", offset);
        metadata.put("limit", limit);
        boolean hasMore = offset < matched && returned < matched - offset;
        metadata.put("hasMore", hasMore);
        if (hasMore) metadata.put("nextOffset", offset + returned);
        metadata.put("sortBy", sortBy);
        metadata.put("sortDir", sortDir);
        return metadata;
    }

    String summary(int total, int matched, int returned) {
        String range = returned == 0 ? "0" : (offset + 1) + "-" + (offset + returned);
        String summary = "Showing " + range + " of " + matched + " matching (" + total + " total).";
        if (offset < matched && returned < matched - offset) {
            summary += " More: offset=" + (offset + returned) + " limit=" + limit + " (keep the same filters/sort).";
        }
        return summary;
    }
}

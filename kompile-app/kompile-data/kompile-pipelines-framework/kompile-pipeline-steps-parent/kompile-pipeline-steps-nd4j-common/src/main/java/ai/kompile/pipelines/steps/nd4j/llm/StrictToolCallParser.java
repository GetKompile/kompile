/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.pipelines.steps.nd4j.llm;

import org.nd4j.shade.jackson.databind.DeserializationFeature;
import org.nd4j.shade.jackson.databind.JsonNode;
import org.nd4j.shade.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shared bridge from pipeline tool declarations to SameDiff's canonical,
 * fail-closed tool-call parser.
 */
public final class StrictToolCallParser {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);

    private StrictToolCallParser() {
    }

    public static final class ToolCall {
        private final String id;
        private final String name;
        private final Map<String, Object> arguments;

        private ToolCall(String id, String name, Map<String, Object> arguments) {
            this.id = id;
            this.name = name;
            this.arguments = Collections.unmodifiableMap(new LinkedHashMap<>(arguments));
        }

        public String getId() {
            return id;
        }

        public String getName() {
            return name;
        }

        public Map<String, Object> getArguments() {
            return arguments;
        }
    }

    public static final class ParseResult {
        private final List<ToolCall> toolCalls;
        private final List<String> errors;

        private ParseResult(List<ToolCall> toolCalls, List<String> errors) {
            this.toolCalls = List.copyOf(toolCalls);
            this.errors = List.copyOf(errors);
        }

        public List<ToolCall> getToolCalls() {
            return toolCalls;
        }

        public List<String> getErrors() {
            return errors;
        }
    }

    /**
     * Parse exactly one complete JSON tool-call envelope.
     *
     * <p>No marker extraction, fenced-JSON recovery, substring scan, or
     * cross-protocol fallback is performed.</p>
     */
    public static ParseResult parseJson(
            String rawText, Collection<String> declaredToolNames) {
        String raw = rawText == null ? "" : rawText.trim();
        if (raw.isEmpty()) {
            return new ParseResult(List.of(), List.of());
        }
        Set<String> declared = new LinkedHashSet<>();
        if (declaredToolNames != null) {
            declaredToolNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .forEach(declared::add);
        }

        JsonNode root = readJson(raw);
        if (root == null || !root.isObject()) {
            return new ParseResult(List.of(),
                    List.of("expected one complete JSON tool-call envelope"));
        }
        List<ToolCall> calls = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        extractCall(root, declared, calls, errors);
        if (calls.size() > 1) {
            calls.clear();
            errors.add("expected exactly one JSON tool call");
        } else if (calls.isEmpty() && errors.isEmpty()) {
            errors.add("required JSON tool call was missing or invalid");
        }
        return new ParseResult(calls, errors);
    }

    private static JsonNode readJson(String value) {
        try {
            return MAPPER.readTree(value);
        } catch (Exception ignored) {
            return null;
        }
    }

    private static void extractCall(JsonNode root, Set<String> declared,
                                    List<ToolCall> calls, List<String> errors) {
        JsonNode toolCalls = root.get("tool_calls");
        if (toolCalls != null) {
            if (!toolCalls.isArray() || toolCalls.size() != 1) {
                errors.add("expected exactly one JSON tool call");
                return;
            }
            extractFunction(toolCalls.get(0), declared, calls, errors);
            return;
        }

        JsonNode tool = root.has("tool") ? root.get("tool") : root.get("toolName");
        if (tool != null && tool.isTextual()) {
            JsonNode arguments = root.has("args") ? root.get("args") : root.get("arguments");
            add(tool.asText(), root.get("id"), arguments, declared, calls, errors);
            return;
        }

        if (root.has("function")) {
            extractFunction(root, declared, calls, errors);
            return;
        }

        JsonNode name = root.get("name");
        if (name != null && name.isTextual()) {
            JsonNode arguments = root.has("arguments")
                    ? root.get("arguments") : root.get("args");
            add(name.asText(), root.get("id"), arguments, declared, calls, errors);
        }
    }

    private static void extractFunction(JsonNode call, Set<String> declared,
                                        List<ToolCall> calls, List<String> errors) {
        if (call == null || !call.isObject()) {
            errors.add("invalid JSON tool call");
            return;
        }
        JsonNode function = call.get("function");
        if (function == null || !function.isObject()) {
            errors.add("invalid JSON tool call");
            return;
        }
        JsonNode name = function.get("name");
        JsonNode arguments = function.has("arguments")
                ? function.get("arguments") : function.get("args");
        if (arguments != null && arguments.isTextual()) {
            arguments = readJson(arguments.asText());
        }
        add(name == null ? null : name.asText(), call.get("id"), arguments,
                declared, calls, errors);
    }

    @SuppressWarnings("unchecked")
    private static void add(String name, JsonNode id, JsonNode arguments,
                            Set<String> declared, List<ToolCall> calls,
                            List<String> errors) {
        if (name == null || name.isBlank() || arguments == null || !arguments.isObject()) {
            errors.add("invalid arguments for tool " + name);
            return;
        }
        if (!declared.contains(name)) {
            errors.add("undeclared tool " + name);
            return;
        }
        Map<String, Object> values = MAPPER.convertValue(arguments, Map.class);
        calls.add(new ToolCall(id == null || id.isNull() ? null : id.asText(), name, values));
    }
}

/* Copyright 2025 Kompile Inc. Licensed under the Apache License, Version 2.0. */
package ai.kompile.graph.reasoning.explain;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.unified.MiniJson;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/** Versioned, bounded JSON codec for portable reasoning traces. */
public final class ReasoningTraceJsonCodec {

    public static final String FORMAT = "kompile.reasoning-trace";
    public static final int VERSION = 1;
    public static final int MAX_BYTES = 1024 * 1024;
    public static final int MAX_STEPS = 512;
    private static final int MAX_DEPTH = 64;
    private static final int MAX_PREMISES = 64;
    private static final int MAX_META = 64;
    private static final int MAX_TEXT_BYTES = 4096;
    private static final int MAX_JSON_NESTING = 16;

    private ReasoningTraceJsonCodec() { }

    public static String encode(String traceId, String suggestionId, ReasoningTrace trace) {
        requireId(traceId, "traceId");
        requireId(suggestionId, "suggestionId");
        if (trace == null) throw new IllegalArgumentException("trace is required");
        List<Map<String, Object>> rows = new ArrayList<>();
        IdentityHashMap<ReasoningTrace.Step, String> ids = new IdentityHashMap<>();
        Set<ReasoningTrace.Step> visiting = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        String rootId = encodeStep(trace.conclusion(), 1, rows, ids, visiting);
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("format", FORMAT);
        envelope.put("formatVersion", VERSION);
        envelope.put("traceId", traceId);
        envelope.put("suggestionId", suggestionId);
        envelope.put("rootStepId", rootId);
        envelope.put("steps", rows);
        String json = MiniJson.write(envelope);
        requireSize(json);
        return json;
    }

    public static ReasoningTrace decode(String json, String expectedSuggestionId) {
        requireSize(json);
        Object parsed = MiniJson.parse(json);
        if (!(parsed instanceof Map<?, ?> root)) throw new IllegalArgumentException("Trace must be an object");
        if (!FORMAT.equals(text(root.get("format")))) throw new IllegalArgumentException("Unsupported trace format");
        if (!exactVersion(root.get("formatVersion"))) {
            throw new IllegalArgumentException("Unsupported trace format version");
        }
        String suggestionId = required(root.get("suggestionId"), "suggestionId");
        requireId(suggestionId, "suggestionId");
        if (expectedSuggestionId != null && !expectedSuggestionId.equals(suggestionId)) {
            throw new IllegalArgumentException("Trace suggestion ID does not match artifact path");
        }
        String traceId = required(root.get("traceId"), "traceId");
        requireId(traceId, "traceId");
        if (!("process-trace:" + suggestionId).equals(traceId)) {
            throw new IllegalArgumentException("Trace ID does not match suggestion ID");
        }
        String rootId = required(root.get("rootStepId"), "rootStepId");
        Object values = root.get("steps");
        if (!(values instanceof List<?> list) || list.isEmpty() || list.size() > MAX_STEPS) {
            throw new IllegalArgumentException("Invalid trace step list");
        }
        Map<String, StepRow> rows = new LinkedHashMap<>();
        for (Object value : list) {
            if (!(value instanceof Map<?, ?> row)) throw new IllegalArgumentException("Trace step must be an object");
            StepRow decoded = decodeRow(row);
            if (rows.putIfAbsent(decoded.id(), decoded) != null) {
                throw new IllegalArgumentException("Duplicate trace step ID: " + decoded.id());
            }
        }
        if (!rows.containsKey(rootId)) throw new IllegalArgumentException("Trace root step is missing");
        Map<String, ReasoningTrace.Step> built = new HashMap<>();
        LinkedHashSet<String> visiting = new LinkedHashSet<>();
        ReasoningTrace.Step rootStep = build(rootId, 1, rows, built, visiting);
        if (built.size() != rows.size()) throw new IllegalArgumentException("Trace contains unreachable steps");
        return ReasoningTrace.of(rootStep);
    }

    private static String encodeStep(ReasoningTrace.Step step, int depth,
                                     List<Map<String, Object>> rows,
                                     IdentityHashMap<ReasoningTrace.Step, String> ids,
                                     Set<ReasoningTrace.Step> visiting) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Trace exceeds maximum depth");
        if (!visiting.add(step)) throw new IllegalArgumentException("Trace contains a cycle");
        String existing = ids.get(step);
        if (existing != null) {
            visiting.remove(step);
            return existing;
        }
        if (rows.size() >= MAX_STEPS) throw new IllegalArgumentException("Trace has too many steps");
        String id = "s" + rows.size();
        ids.put(step, id);
        rows.add(null);
        if (step.premises().size() > MAX_PREMISES) throw new IllegalArgumentException("Trace step has too many premises");
        List<String> premiseIds = new ArrayList<>();
        for (ReasoningTrace.Step premise : step.premises()) {
            premiseIds.add(encodeStep(premise, depth + 1, rows, ids, visiting));
        }
        validateScalar(step.confidence(), "confidence");
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", id);
        row.put("kind", step.kind().name());
        row.put("conclusion", bounded(step.conclusion(), "conclusion"));
        row.put("operation", bounded(step.operation(), "operation"));
        row.put("confidence", step.confidence());
        row.put("source", step.source() == null ? null : bounded(step.source(), "source"));
        row.put("premiseIds", premiseIds);
        if (step.opinion() != null) {
            Opinion opinion = step.opinion();
            validateOpinion(opinion);
            Map<String, Object> opinionRow = new LinkedHashMap<>();
            opinionRow.put("belief", opinion.belief());
            opinionRow.put("disbelief", opinion.disbelief());
            opinionRow.put("uncertainty", opinion.uncertainty());
            opinionRow.put("baseRate", opinion.baseRate());
            row.put("opinion", opinionRow);
        }
        if (step.meta().size() > MAX_META) throw new IllegalArgumentException("Trace step has too much metadata");
        TreeMap<String, String> meta = new TreeMap<>();
        step.meta().forEach((key, value) -> meta.put(
                bounded(key, "metadata key"), bounded(value, "metadata value")));
        row.put("meta", meta);
        rows.set(Integer.parseInt(id.substring(1)), row);
        visiting.remove(step);
        return id;
    }

    private static StepRow decodeRow(Map<?, ?> row) {
        String id = required(row.get("id"), "step id");
        requireId(id, "step id");
        ReasoningTrace.StepKind kind;
        try {
            kind = ReasoningTrace.StepKind.valueOf(required(row.get("kind"), "kind"));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Unknown trace step kind", e);
        }
        String conclusion = bounded(required(row.get("conclusion"), "conclusion"), "conclusion");
        String operation = bounded(text(row.get("operation")), "operation");
        double confidence = number(row.get("confidence"));
        validateScalar(confidence, "confidence");
        String source = row.get("source") == null ? null : bounded(text(row.get("source")), "source");
        List<String> premiseIds = strings(row.get("premiseIds"));
        if (premiseIds.size() > MAX_PREMISES) throw new IllegalArgumentException("Trace step has too many premises");
        Opinion opinion = decodeOpinion(row.get("opinion"));
        Map<String, String> meta = stringMap(row.get("meta"));
        return new StepRow(id, kind, conclusion, operation, confidence, source, premiseIds, opinion, meta);
    }

    private static ReasoningTrace.Step build(String id, int depth, Map<String, StepRow> rows,
                                             Map<String, ReasoningTrace.Step> built,
                                             Set<String> visiting) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("Trace exceeds maximum depth");
        if (built.containsKey(id)) return built.get(id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Trace contains a cycle");
        StepRow row = rows.get(id);
        if (row == null) throw new IllegalArgumentException("Missing trace premise: " + id);
        List<ReasoningTrace.Step> premises = row.premiseIds().stream()
                .map(premise -> build(premise, depth + 1, rows, built, visiting)).toList();
        ReasoningTrace.Step step = new ReasoningTrace.Step(
                row.kind(), row.conclusion(), row.operation(), row.confidence(), row.source(),
                premises, row.opinion(), row.meta());
        visiting.remove(id);
        built.put(id, step);
        return step;
    }

    private static Opinion decodeOpinion(Object value) {
        if (value == null) return null;
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException("Invalid trace opinion");
        Opinion opinion = new Opinion(number(map.get("belief")), number(map.get("disbelief")),
                number(map.get("uncertainty")), number(map.get("baseRate")));
        validateOpinion(opinion);
        return opinion;
    }

    private static void validateOpinion(Opinion opinion) {
        validateScalar(opinion.belief(), "opinion belief");
        validateScalar(opinion.disbelief(), "opinion disbelief");
        validateScalar(opinion.uncertainty(), "opinion uncertainty");
        validateScalar(opinion.baseRate(), "opinion base rate");
    }

    private static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) throw new IllegalArgumentException("Expected trace string list");
        return list.stream().map(item -> required(item, "premise id")).toList();
    }

    private static Map<String, String> stringMap(Object value) {
        if (value == null) return Map.of();
        if (!(value instanceof Map<?, ?> map) || map.size() > MAX_META) {
            throw new IllegalArgumentException("Invalid trace metadata");
        }
        Map<String, String> result = new LinkedHashMap<>();
        map.forEach((key, item) -> result.put(
                bounded(required(key, "metadata key"), "metadata key"),
                bounded(required(item, "metadata value"), "metadata value")));
        return result;
    }

    private static void requireSize(String json) {
        if (json == null || json.isBlank() || json.getBytes(StandardCharsets.UTF_8).length > MAX_BYTES) {
            throw new IllegalArgumentException("Invalid trace JSON size");
        }
        int depth = 0;
        boolean quoted = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (quoted) {
                if (escaped) escaped = false;
                else if (c == '\\') escaped = true;
                else if (c == '"') quoted = false;
                continue;
            }
            if (c == '"') quoted = true;
            else if (c == '{' || c == '[') {
                if (++depth > MAX_JSON_NESTING) throw new IllegalArgumentException("Trace JSON is too deeply nested");
            } else if (c == '}' || c == ']') {
                if (--depth < 0) throw new IllegalArgumentException("Malformed trace JSON nesting");
            }
        }
        if (quoted || depth != 0) throw new IllegalArgumentException("Malformed trace JSON nesting");
    }

    private static String bounded(String value, String field) {
        String normalized = value == null ? "" : value;
        if (normalized.getBytes(StandardCharsets.UTF_8).length > MAX_TEXT_BYTES) {
            throw new IllegalArgumentException("Trace " + field + " is too long");
        }
        return normalized;
    }

    private static void requireId(String id, String field) {
        if (id == null || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,199}") || id.contains("..")) {
            throw new IllegalArgumentException("Invalid trace " + field);
        }
    }

    private static void validateScalar(double value, String field) {
        if (!Double.isFinite(value) || value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("Invalid trace " + field);
        }
    }

    private static String required(Object value, String field) {
        String text = text(value);
        if (text == null || text.isBlank()) throw new IllegalArgumentException("Trace " + field + " is required");
        return text;
    }

    private static String text(Object value) { return value == null ? null : String.valueOf(value); }
    private static boolean exactVersion(Object value) {
        if (!(value instanceof Number number)) return false;
        double numeric = number.doubleValue();
        return Double.isFinite(numeric) && numeric == VERSION && Math.rint(numeric) == numeric;
    }
    private static double number(Object value) {
        double number = value instanceof Number n ? n.doubleValue() : Double.parseDouble(String.valueOf(value));
        if (!Double.isFinite(number)) throw new IllegalArgumentException("Trace number must be finite");
        return number;
    }

    private record StepRow(String id, ReasoningTrace.StepKind kind, String conclusion,
                           String operation, double confidence, String source,
                           List<String> premiseIds, Opinion opinion, Map<String, String> meta) { }
}

/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery.Goal;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery.Intervention;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery.MeasureSelector;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery.Mode;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery.Operation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Capability;
import ai.kompile.graph.reasoning.query.GraphQueryEngine.Intent;
import ai.kompile.graph.reasoning.unified.MiniJson;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads the transport-neutral {@code quantitative} request field into a {@link QuantitativeQuery}.
 *
 * <p>The HTTP endpoint, the Spring AI tool, the CLI tool, and the C library all accept the same
 * JSON-shaped object, so it is decoded here once. It may arrive as a map or as a JSON string.
 * Nothing is guessed: unknown keys are rejected with the valid key set, every intervention names
 * its operation, and every number must be finite.</p>
 *
 * <pre>{@code
 * {"target": {"text": "Gross Margin", "unit": "ratio", "dimensions": {"region": "APAC"}},
 *  "interventions": [{"target": {"text": "Sales"}, "operation": "SCALE", "value": 0.25}],
 *  "goal": {"control": {"text": "Sales"}, "targetValue": 0.5, "minimum": 0, "maximum": 1000},
 *  "dimensions": {"region": "APAC"}, "asOf": "2026-06-30", "topK": 5}
 * }</pre>
 *
 * <p>A selector may also be a bare string, read as its {@code text}. Which intents take the field,
 * and which members each requires, comes from {@link GraphQueryEngine#capabilityContract()}.</p>
 */
public final class QuantitativeRequestParser {

    /** Request field carrying the quantitative object. */
    public static final String FIELD = "quantitative";

    private static final String PREFIX = FIELD + ".";

    private static final List<String> SPEC_KEYS =
            List.of("target", "interventions", "goal", "dimensions", "asOf", "topK");
    private static final List<String> SELECTOR_KEYS =
            List.of("entityId", "text", "type", "unit", "dimensions");
    private static final List<String> INTERVENTION_KEYS = List.of("target", "operation", "value");
    private static final List<String> GOAL_KEYS =
            List.of("control", "targetValue", "minimum", "maximum", "tolerance", "maxIterations");
    private static final List<String> REQUIRED_GOAL_KEYS =
            List.of("control", "targetValue", "minimum", "maximum");

    private QuantitativeRequestParser() {
    }

    /** True when the engine contract requires a {@code quantitative.*} member for the intent. */
    public static boolean isQuantitative(Intent intent) {
        return intent != null && !requiredMembers(intent).isEmpty();
    }

    /** True when the capability requires a {@code quantitative.*} member. */
    public static boolean isQuantitative(Capability capability) {
        return capability != null
                && capability.requiredFields().stream().anyMatch(field -> field.startsWith(PREFIX));
    }

    /** Intents that take the {@code quantitative} field, in contract order. */
    public static List<Intent> quantitativeIntents() {
        return GraphQueryEngine.capabilityContract().stream()
                .filter(QuantitativeRequestParser::isQuantitative)
                .map(capability -> Intent.valueOf(capability.intent()))
                .toList();
    }

    /**
     * A minimal, valid {@code quantitative} value for the intent, covering exactly the members the
     * contract requires. Returned in {@code quantitative=...} form for error messages.
     */
    public static String example(Intent intent) {
        List<String> required = requiredMembers(intent);
        StringBuilder sb = new StringBuilder(FIELD).append("={\"target\":{\"text\":\"Gross Margin\"}");
        if (required.contains("interventions")) {
            sb.append(",\"interventions\":[{\"target\":{\"text\":\"Sales\"},"
                    + "\"operation\":\"SCALE\",\"value\":0.1}]");
        }
        if (required.contains("goal")) {
            sb.append(",\"goal\":{\"control\":{\"text\":\"Sales\"},\"targetValue\":0.5,"
                    + "\"minimum\":0,\"maximum\":1000}");
        }
        return sb.append('}').toString();
    }

    /**
     * One-line usage guide for the {@code quantitative} field, built from the same key sets the
     * parser enforces, for capability lists and tool descriptions.
     */
    public static String guidance() {
        return names(quantitativeIntents()) + " take a " + FIELD + " object. A selector (target, "
                + "interventions[].target, goal.control) gives entityId, text, or type; a bare string is "
                + "read as text. Each intervention names its operation (" + String.join(", ", operationNames())
                + ") and a value; SCALE takes a fraction (-0.16 means -16%). A goal needs "
                + String.join(", ", REQUIRED_GOAL_KEYS) + ". Example: " + example(Intent.SCENARIO);
    }

    /** JSON schema for the {@code quantitative} object, for transports that publish one. */
    public static Map<String, Object> jsonSchema() {
        Map<String, Object> selector = object(
                "Measure selector: give entityId, text, or type. A bare string is read as text.",
                Map.of(
                        "entityId", scalar("string", "Exact graph entity id."),
                        "text", scalar("string", "Measure name or phrase, resolved by ranked search."),
                        "type", scalar("string", "Entity type, e.g. FORMULA_CELL."),
                        "unit", scalar("string", "Expected unit, e.g. ratio or USD."),
                        "dimensions", dimensionsSchema()),
                SELECTOR_KEYS);

        Map<String, Object> intervention = object(
                "One immutable change applied before evaluation.",
                Map.of(
                        "target", selector,
                        "operation", enumeration("SET replaces the value, ADD adds to it, SCALE "
                                + "multiplies by (1 + value): -0.16 means -16%.", operationNames()),
                        "value", scalar("number", "Finite change amount.")),
                INTERVENTION_KEYS);
        intervention.put("required", INTERVENTION_KEYS);

        Map<String, Object> interventions = new LinkedHashMap<>();
        interventions.put("type", "array");
        interventions.put("description", "Required for SCENARIO; optional fixed changes for "
                + "SOLVE_TARGET and MODELS. CALCULATE takes none.");
        interventions.put("items", intervention);

        Map<String, Object> goal = object(
                "Bounded goal seek, required for SOLVE_TARGET: find the control value in "
                        + "[minimum, maximum] that brings the target to targetValue.",
                Map.of(
                        "control", selector,
                        "targetValue", scalar("number", "Value the target should reach."),
                        "minimum", scalar("number", "Lower bound for the control."),
                        "maximum", scalar("number", "Upper bound for the control; above minimum."),
                        "tolerance", scalar("number", "Accepted target error; default 1e-6."),
                        "maxIterations", scalar("integer", "Search budget; default 100, maximum 1000.")),
                GOAL_KEYS);
        goal.put("required", REQUIRED_GOAL_KEYS);

        Map<String, Object> schema = object(
                "Quantitative request for MODELS, CALCULATE, SCENARIO, and SOLVE_TARGET. "
                        + "Operations and units are never guessed.",
                Map.of(
                        "target", selector,
                        "interventions", interventions,
                        "goal", goal,
                        "dimensions", dimensionsSchema(),
                        "asOf", scalar("string", "ISO-8601 instant or date bounding observations."),
                        "topK", scalar("integer", "Ranked model candidates to return; default 10, maximum 100.")),
                SPEC_KEYS);
        schema.put("required", List.of("target"));
        return schema;
    }

    /**
     * Decode {@code spec} for {@code intent}.
     *
     * @param spec           the request's {@code quantitative} value: a map, a JSON string, or absent
     * @param topK           the request's flat {@code topK}, used when the spec gives none
     * @param queryEmbedding optional embedding that ranks model candidates
     * @return the query, or {@code null} for a non-quantitative intent without a spec
     * @throws IllegalArgumentException naming the offending field when the spec cannot be used
     */
    public static QuantitativeQuery parse(
            Intent intent, Object spec, Integer topK, double[] queryEmbedding) {
        Object decoded = decode(spec, FIELD);
        boolean absent = decoded == null
                || decoded instanceof String text && text.isBlank()
                || decoded instanceof Map<?, ?> map && map.isEmpty();
        List<String> required = requiredMembers(intent);
        if (required.isEmpty()) {
            if (absent) {
                return null;
            }
            throw new IllegalArgumentException(FIELD + " only applies to "
                    + names(quantitativeIntents()) + ", not " + intent + ".");
        }

        Map<String, Object> fields = absent ? Map.of() : fields(decoded, FIELD, SPEC_KEYS);
        List<String> missing = required.stream()
                .filter(member -> !present(decode(fields.get(member), PREFIX + member)))
                .map(member -> PREFIX + member)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(intent + " requires " + String.join(" and ", missing)
                    + ". Example: " + example(intent));
        }

        MeasureSelector target = selector(fields.get("target"), PREFIX + "target");
        List<Intervention> interventions = interventions(fields.get("interventions"));
        Goal goal = fields.get("goal") == null ? null : goal(fields.get("goal"));
        Map<String, String> dimensions = dimensions(fields.get("dimensions"), PREFIX + "dimensions");
        Instant asOf = asOf(fields.get("asOf"));
        int limit = fields.get("topK") != null
                ? positiveInt(fields.get("topK"), PREFIX + "topK")
                : topK != null && topK > 0 ? topK : 0;
        return new QuantitativeQuery(Mode.valueOf(intent.name()), target, interventions,
                dimensions, asOf, queryEmbedding, limit, goal);
    }

    private static List<String> requiredMembers(Intent intent) {
        if (intent == null) {
            return List.of();
        }
        return GraphQueryEngine.capabilityContract().stream()
                .filter(capability -> capability.intent().equals(intent.name()))
                .flatMap(capability -> capability.requiredFields().stream())
                .filter(field -> field.startsWith(PREFIX))
                .map(field -> field.substring(PREFIX.length()))
                .toList();
    }

    private static MeasureSelector selector(Object raw, String path) {
        Object value = decode(raw, path);
        if (value instanceof String text) {
            if (text.isBlank()) {
                throw new IllegalArgumentException(path + " needs entityId, text, or type.");
            }
            return MeasureSelector.text(text.trim());
        }
        Map<String, Object> fields = fields(value, path, SELECTOR_KEYS);
        MeasureSelector selector = new MeasureSelector(
                text(fields.get("entityId"), path + ".entityId"),
                text(fields.get("text"), path + ".text"),
                text(fields.get("type"), path + ".type"),
                text(fields.get("unit"), path + ".unit"),
                dimensions(fields.get("dimensions"), path + ".dimensions"));
        if (!selector.specified()) {
            throw new IllegalArgumentException(path + " needs entityId, text, or type.");
        }
        return selector;
    }

    private static List<Intervention> interventions(Object raw) {
        String path = PREFIX + "interventions";
        Object value = decode(raw, path);
        if (value == null) {
            return List.of();
        }
        List<?> items;
        if (value instanceof Collection<?> collection) {
            items = new ArrayList<>(collection);
        } else if (value instanceof Map<?, ?>) {
            items = List.of(value);
        } else {
            throw new IllegalArgumentException(path + " must be an array of {target, operation, value}.");
        }
        List<Intervention> out = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            String itemPath = path + "[" + i + "]";
            Map<String, Object> fields = fields(decode(items.get(i), itemPath), itemPath, INTERVENTION_KEYS);
            if (!present(fields.get("operation"))) {
                throw new IllegalArgumentException(itemPath + ".operation is required: one of "
                        + String.join(", ", operationNames()) + " (SCALE takes a fraction: -0.16 means -16%).");
            }
            Operation operation = operation(fields.get("operation"), itemPath + ".operation");
            if (fields.get("value") == null) {
                throw new IllegalArgumentException(itemPath + ".value is required.");
            }
            double amount = number(fields.get("value"), itemPath + ".value");
            if (fields.get("target") == null) {
                throw new IllegalArgumentException(itemPath + ".target is required.");
            }
            out.add(new Intervention(selector(fields.get("target"), itemPath + ".target"), operation, amount));
        }
        return out;
    }

    private static Goal goal(Object raw) {
        String path = PREFIX + "goal";
        Map<String, Object> fields = fields(decode(raw, path), path, GOAL_KEYS);
        List<String> missing = REQUIRED_GOAL_KEYS.stream()
                .filter(key -> fields.get(key) == null)
                .toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException(path + " needs " + String.join(", ", REQUIRED_GOAL_KEYS)
                    + "; missing " + String.join(", ", missing) + ".");
        }
        MeasureSelector control = selector(fields.get("control"), path + ".control");
        double targetValue = number(fields.get("targetValue"), path + ".targetValue");
        double minimum = number(fields.get("minimum"), path + ".minimum");
        double maximum = number(fields.get("maximum"), path + ".maximum");
        if (minimum >= maximum) {
            throw new IllegalArgumentException(path + ".minimum must be below " + path + ".maximum.");
        }
        double tolerance = 0.0;
        if (fields.get("tolerance") != null) {
            tolerance = number(fields.get("tolerance"), path + ".tolerance");
            if (tolerance <= 0.0) {
                throw new IllegalArgumentException(path + ".tolerance must be positive.");
            }
        }
        int maxIterations = fields.get("maxIterations") == null
                ? 0 : positiveInt(fields.get("maxIterations"), path + ".maxIterations");
        return new Goal(control, targetValue, minimum, maximum, tolerance, maxIterations);
    }

    private static Operation operation(Object value, String path) {
        String name = String.valueOf(value).trim().toUpperCase(Locale.ROOT);
        try {
            return Operation.valueOf(name);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(path + " must be one of " + String.join(", ", operationNames())
                    + ", not '" + value + "'.", e);
        }
    }

    private static Map<String, String> dimensions(Object raw, String path) {
        Object value = decode(raw, path);
        if (value == null) {
            return Map.of();
        }
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be an object of dimension names to values.");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            String text = text(entry.getValue(), path + "." + key);
            if (text == null) {
                throw new IllegalArgumentException(path + "." + key + " needs a value.");
            }
            out.put(key, text);
        }
        return out;
    }

    private static Instant asOf(Object value) {
        if (value == null) {
            return null;
        }
        String text = value instanceof String s ? s.trim() : "";
        try {
            return Instant.parse(text);
        } catch (DateTimeParseException notAnInstant) {
            try {
                return LocalDate.parse(text).atStartOfDay(ZoneOffset.UTC).toInstant();
            } catch (DateTimeParseException notADate) {
                throw new IllegalArgumentException(PREFIX + "asOf must be an ISO-8601 instant "
                        + "(2026-06-30T00:00:00Z) or date (2026-06-30), not '" + value + "'.");
            }
        }
    }

    private static Map<String, Object> fields(Object value, String path, List<String> keys) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException(path + " must be a JSON object with keys "
                    + String.join(", ", keys) + ".");
        }
        Map<String, Object> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            String key = String.valueOf(entry.getKey());
            if (!keys.contains(key)) {
                throw new IllegalArgumentException("Unknown key " + path + "." + key
                        + "; valid keys are " + String.join(", ", keys) + ".");
            }
            out.put(key, entry.getValue());
        }
        return out;
    }

    private static Object decode(Object value, String path) {
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                try {
                    return MiniJson.parse(trimmed);
                } catch (RuntimeException e) {
                    throw new IllegalArgumentException(path + " is not valid JSON: " + e.getMessage(), e);
                }
            }
        }
        return value;
    }

    private static boolean present(Object value) {
        if (value instanceof String text) {
            return !text.isBlank();
        }
        if (value instanceof Collection<?> collection) {
            return !collection.isEmpty();
        }
        if (value instanceof Map<?, ?> map) {
            return !map.isEmpty();
        }
        return value != null;
    }

    private static String text(Object value, String path) {
        if (value == null) {
            return null;
        }
        if (value instanceof String || value instanceof Number || value instanceof Boolean) {
            String text = String.valueOf(value).trim();
            return text.isEmpty() ? null : text;
        }
        throw new IllegalArgumentException(path + " must be a string.");
    }

    private static double number(Object value, String path) {
        double parsed;
        if (value instanceof Number n) {
            parsed = n.doubleValue();
        } else if (value instanceof String s) {
            try {
                parsed = Double.parseDouble(s.trim());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException(path + " must be a number, not '" + s + "'.", e);
            }
        } else {
            throw new IllegalArgumentException(path + " must be a number.");
        }
        if (!Double.isFinite(parsed)) {
            throw new IllegalArgumentException(path + " must be finite.");
        }
        return parsed;
    }

    private static int positiveInt(Object value, String path) {
        double parsed = number(value, path);
        if (parsed < 1 || parsed > Integer.MAX_VALUE || parsed != Math.rint(parsed)) {
            throw new IllegalArgumentException(path + " must be a positive integer.");
        }
        return (int) parsed;
    }

    private static List<String> operationNames() {
        return Arrays.stream(Operation.values()).map(Enum::name).toList();
    }

    private static String names(List<Intent> intents) {
        return String.join(", ", intents.stream().map(Enum::name).toList());
    }

    private static Map<String, Object> dimensionsSchema() {
        Map<String, Object> schema = scalar("object", "Dimension name to value, e.g. {\"region\":\"APAC\"}.");
        schema.put("additionalProperties", Map.of("type", "string"));
        return schema;
    }

    private static Map<String, Object> object(
            String description, Map<String, Object> properties, List<String> order) {
        Map<String, Object> ordered = new LinkedHashMap<>();
        order.forEach(key -> ordered.put(key, properties.get(key)));
        Map<String, Object> schema = scalar("object", description);
        schema.put("properties", ordered);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> enumeration(String description, List<String> values) {
        Map<String, Object> schema = scalar("string", description);
        schema.put("enum", values);
        return schema;
    }

    private static Map<String, Object> scalar(String type, String description) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", type);
        schema.put("description", description);
        return schema;
    }
}

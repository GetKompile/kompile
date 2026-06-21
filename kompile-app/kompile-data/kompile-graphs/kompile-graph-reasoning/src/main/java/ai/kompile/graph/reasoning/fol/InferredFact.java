/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.graph.reasoning.fol;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A persisted, versioned inferred fact produced by a PSL or MEBN inference run.
 *
 * <p>An {@code InferredFact} captures:
 * <ul>
 *   <li>The conclusion: which atom or RV instance was inferred and its soft-truth value.</li>
 *   <li>Derivation provenance: which {@link Fact}s / {@link Finding}s supported the conclusion
 *       (by their atom/grounded keys) and which rules fired.</li>
 *   <li>Version lineage: a monotonically increasing {@code version} counter so that
 *       successive inference runs on an evolving KB can be compared.</li>
 *   <li>Run identity: the {@code runId} links back to the full inference run record so that
 *       all facts produced in one session can be grouped and compared.</li>
 * </ul>
 *
 * <p>Unlike {@link EntailmentRecord} (which is a transient in-memory audit trail),
 * {@code InferredFact} is designed to be persisted (via {@link InferredFactStore}) and
 * serialized to JSON (see {@link InferredFact#toJson()} / {@link InferredFact#fromJson}).</p>
 *
 * @param atomKey            canonical atom or grounded-RV key (e.g. {@code "State(alice)"})
 * @param value              soft-truth value in [0, 1] (PSL) or posterior probability (MEBN)
 * @param confidence         optional confidence score in [0, 1] (separate from raw value);
 *                           typically the complement of the uncertainty; may equal {@code value}
 * @param supportingFactKeys atom keys of the {@link Fact}s / {@link Finding}s that justified this
 * @param supportingRuleIds  display strings or rule identifiers that fired to produce this
 * @param runId              identifier of the inference run that produced this fact
 * @param version            monotonically increasing version counter for this atom key;
 *                           a higher version supersedes lower versions for the same atom
 * @param inferredAt         timestamp when this fact was inferred
 */
public record InferredFact(
        String atomKey,
        double value,
        double confidence,
        List<String> supportingFactKeys,
        List<String> supportingRuleIds,
        String runId,
        long version,
        Instant inferredAt
) {

    public InferredFact {
        Objects.requireNonNull(atomKey, "atomKey must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(inferredAt, "inferredAt must not be null");
        if (value < 0.0 || value > 1.0) {
            throw new IllegalArgumentException("value must be in [0,1], got: " + value);
        }
        if (confidence < 0.0 || confidence > 1.0) {
            throw new IllegalArgumentException("confidence must be in [0,1], got: " + confidence);
        }
        supportingFactKeys = (supportingFactKeys == null) ? List.of() : List.copyOf(supportingFactKeys);
        supportingRuleIds = (supportingRuleIds == null) ? List.of() : List.copyOf(supportingRuleIds);
    }

    // ─── Factory ────────────────────────────────────────────────────────────────

    /**
     * Create an {@code InferredFact} from an {@link EntailmentRecord}, assigning the given
     * version and using the record's posterior as both value and confidence.
     *
     * @param record  the entailment audit record
     * @param version the version number to assign
     * @return the corresponding inferred fact
     */
    public static InferredFact fromEntailment(EntailmentRecord record, long version) {
        Objects.requireNonNull(record, "record must not be null");
        return new InferredFact(
                record.groundedRvOrAtomKey(),
                record.posterior(),
                record.posterior(),
                record.supportingFindingKeys(),
                record.activatedRules(),
                record.inferenceRunId(),
                version,
                record.computedAt()
        );
    }

    /**
     * Create an {@code InferredFact} directly, with confidence equal to value and
     * using the current time as the timestamp.
     *
     * @param atomKey            atom key
     * @param value              soft-truth value in [0, 1]
     * @param supportingFactKeys supporting fact keys
     * @param supportingRuleIds  supporting rule identifiers
     * @param runId              inference run identifier
     * @param version            version counter
     * @return the inferred fact
     */
    public static InferredFact of(String atomKey, double value,
                                   List<String> supportingFactKeys,
                                   List<String> supportingRuleIds,
                                   String runId, long version) {
        return new InferredFact(atomKey, value, value, supportingFactKeys,
                supportingRuleIds, runId, version, Instant.now());
    }

    // ─── Learned-weight provenance ───────────────────────────────────────────────

    /**
     * The learned rule weights that produced this fact, parsed from the {@code "<weight>: <rule>"}
     * prefix of each {@link #supportingRuleIds()} entry — the format PSL rule displays use (a non-hard
     * rule renders as e.g. {@code "3.20: State(a) & Link(a,b) -> State(b) ^2"}). Entries without a
     * numeric weight prefix (e.g. hard rules) are omitted.
     *
     * <p>This is how "probabilistic facts with learned weights" surface end-to-end: when inference runs
     * under a learned-weight program (see {@code PslWeightLearningService.learnAndApply}), the fact's
     * probability is {@link #value()} and the contributing rules' <em>learned</em> weights are read
     * here — no schema change needed, since the weight already travels in the rule display.</p>
     *
     * @return map of supporting-rule display → learned weight (insertion-ordered; never null)
     */
    public Map<String, Double> ruleWeights() {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (String ruleId : supportingRuleIds) {
            if (ruleId == null) {
                continue;
            }
            int colon = ruleId.indexOf(':');
            if (colon > 0) {
                try {
                    weights.put(ruleId, Double.parseDouble(ruleId.substring(0, colon).trim()));
                } catch (NumberFormatException ignored) {
                    // not a weight-prefixed rule (e.g. a hard rule) — skip
                }
            }
        }
        return weights;
    }

    // ─── JSON serialization (hand-rolled; no jackson-databind) ──────────────────

    /**
     * Serialize this record to a JSON string.
     *
     * <p>Only the jackson-annotations dependency is present in this module, so we hand-roll
     * a minimal JSON encoder here. The format is compatible with standard JSON parsers.</p>
     *
     * @return JSON representation of this inferred fact
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder();
        sb.append('{');
        appendStr(sb, "atomKey", atomKey); sb.append(',');
        appendDouble(sb, "value", value); sb.append(',');
        appendDouble(sb, "confidence", confidence); sb.append(',');
        appendStrList(sb, "supportingFactKeys", supportingFactKeys); sb.append(',');
        appendStrList(sb, "supportingRuleIds", supportingRuleIds); sb.append(',');
        appendStr(sb, "runId", runId); sb.append(',');
        appendLong(sb, "version", version); sb.append(',');
        appendStr(sb, "inferredAt", inferredAt.toString());
        sb.append('}');
        return sb.toString();
    }

    /**
     * Deserialize an {@code InferredFact} from a JSON string produced by {@link #toJson()}.
     *
     * @param json the JSON string
     * @return the deserialized {@code InferredFact}
     * @throws IllegalArgumentException if the JSON cannot be parsed
     */
    public static InferredFact fromJson(String json) {
        if (json == null || json.isBlank()) {
            throw new IllegalArgumentException("JSON input must not be null or blank");
        }
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) {
            throw new IllegalArgumentException("Expected JSON object: " + json);
        }
        s = s.substring(1, s.length() - 1); // strip braces

        // Parse into key→value map (handles simple string, number, array values)
        java.util.Map<String, String> raw = new java.util.LinkedHashMap<>();
        parseRawJson(s, raw);

        String atomKey = unquote(raw.get("atomKey"));
        double value = Double.parseDouble(raw.getOrDefault("value", "0.0"));
        double confidence = Double.parseDouble(raw.getOrDefault("confidence", String.valueOf(value)));
        List<String> factKeys = parseStrList(raw.getOrDefault("supportingFactKeys", "[]"));
        List<String> ruleIds = parseStrList(raw.getOrDefault("supportingRuleIds", "[]"));
        String runId = unquote(raw.get("runId"));
        long version = Long.parseLong(raw.getOrDefault("version", "1"));
        Instant inferredAt = Instant.parse(unquote(raw.get("inferredAt")));

        return new InferredFact(atomKey, value, confidence, factKeys, ruleIds, runId, version, inferredAt);
    }

    // ─── Minimal JSON helpers ────────────────────────────────────────────────────

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append('"').append(escapeJson(key)).append("\":\"")
                .append(escapeJson(value == null ? "" : value)).append('"');
    }

    private static void appendDouble(StringBuilder sb, String key, double v) {
        sb.append('"').append(escapeJson(key)).append("\":").append(v);
    }

    private static void appendLong(StringBuilder sb, String key, long v) {
        sb.append('"').append(escapeJson(key)).append("\":").append(v);
    }

    private static void appendStrList(StringBuilder sb, String key, List<String> list) {
        sb.append('"').append(escapeJson(key)).append("\":[");
        for (int i = 0; i < list.size(); i++) {
            if (i > 0) sb.append(',');
            sb.append('"').append(escapeJson(list.get(i))).append('"');
        }
        sb.append(']');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t");
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            s = s.substring(1, s.length() - 1);
        }
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n")
                .replace("\\r", "\r").replace("\\t", "\t");
    }

    private static List<String> parseStrList(String raw) {
        String s = raw.trim();
        if (s.equals("[]") || s.equals("null")) return List.of();
        if (!s.startsWith("[") || !s.endsWith("]")) return List.of();
        s = s.substring(1, s.length() - 1).trim();
        if (s.isEmpty()) return List.of();
        List<String> result = new java.util.ArrayList<>();
        // Split on commas not inside quotes (simple case: no embedded commas in values)
        int start = 0;
        boolean inQuote = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '"') inQuote = !inQuote;
            else if (c == ',' && !inQuote) {
                result.add(unquote(s.substring(start, i).trim()));
                start = i + 1;
            }
        }
        result.add(unquote(s.substring(start).trim()));
        return List.copyOf(result);
    }

    /**
     * Parse a flat JSON object body (between the outer braces) into a raw string map.
     * Handles string, number, and array values. Does not handle nested objects.
     */
    private static void parseRawJson(String body, java.util.Map<String, String> out) {
        int i = 0;
        int n = body.length();
        while (i < n) {
            // skip whitespace
            while (i < n && body.charAt(i) <= ' ') i++;
            if (i >= n || body.charAt(i) != '"') break;
            // read key
            int ks = i + 1;
            i = findEndQuote(body, ks);
            String key = body.substring(ks, i);
            i++; // skip closing "
            // skip whitespace and colon
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ':')) i++;
            // read value (string, number, or array)
            if (i >= n) break;
            char ch = body.charAt(i);
            String value;
            if (ch == '"') {
                // string value
                int vs = i + 1;
                i = findEndQuote(body, vs);
                value = "\"" + body.substring(vs, i) + "\"";
                i++; // skip closing "
            } else if (ch == '[') {
                // array value
                int depth = 0;
                int start = i;
                boolean inQ = false;
                while (i < n) {
                    char c = body.charAt(i);
                    if (c == '"') inQ = !inQ;
                    else if (!inQ && c == '[') depth++;
                    else if (!inQ && c == ']') {
                        depth--;
                        if (depth == 0) { i++; break; }
                    }
                    i++;
                }
                value = body.substring(start, i);
            } else {
                // number or literal
                int start = i;
                while (i < n && body.charAt(i) != ',' && body.charAt(i) != '}') i++;
                value = body.substring(start, i).trim();
            }
            out.put(key, value);
            // skip comma
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ',')) i++;
        }
    }

    /** Find the end of a quoted string starting at {@code start} (first unescaped {@code "}). */
    private static int findEndQuote(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }
}

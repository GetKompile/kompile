/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.knowledgegraph.audit;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable record of a single mutation to the inferred-fact store.
 *
 * <p>Event types:
 * <ul>
 *   <li>{@code ASSERTED}   — observed/agent assert via KbGroundingService.assertFact</li>
 *   <li>{@code DERIVED}    — MAP inference round produced a new InferredFact version</li>
 *   <li>{@code CORRECTED}  — human override via the correction API; defaults to PIN=true</li>
 *   <li>{@code TOMBSTONED} — soft-delete (strength → SUPPRESSED)</li>
 *   <li>{@code WEIGHT_TUNED} — a PSL rule weight was adjusted</li>
 *   <li>{@code STRENGTH_CHANGED} — fact moved between confidence bands without value change</li>
 * </ul>
 *
 * <p>Serialization is hand-rolled JSON (no jackson-databind) following the same pattern as
 * {@code InferredFact.toJson()} in the lib.
 */
public record FactAuditEvent(
        String eventId,
        String eventType,
        String atomKey,
        Instant occurredAt,
        String actor,
        String sessionId,
        double valueBefore,
        double valueAfter,
        double confidenceBefore,
        double confidenceAfter,
        String strengthLayerBefore,
        String strengthLayerAfter,
        String runId,
        String derivationTrailRef,
        boolean pinnedAfter,
        String ruleId,
        double weightBefore,
        double weightAfter,
        String correctionReason
) {

    /** Factory for DERIVED events emitted after a cascade re-ground. */
    public static FactAuditEvent derived(String atomKey, double valueBefore, double valueAfter,
                                          double confidenceBefore, double confidenceAfter,
                                          String runId, String sessionId) {
        return new FactAuditEvent(
                UUID.randomUUID().toString(),
                "DERIVED",
                atomKey,
                Instant.now(),
                "CASCADE",
                sessionId != null ? sessionId : "cascade",
                valueBefore, valueAfter,
                confidenceBefore, confidenceAfter,
                null, null,
                runId,
                runId != null ? "runId:" + runId : null,
                false,
                null,
                Double.NaN, Double.NaN,
                null
        );
    }

    /** Factory for ASSERTED events emitted when an agent asserts a fact. */
    public static FactAuditEvent asserted(String atomKey, double valueAfter,
                                           String sessionId, String actor) {
        return new FactAuditEvent(
                UUID.randomUUID().toString(),
                "ASSERTED",
                atomKey,
                Instant.now(),
                actor != null ? actor : "AGENT:" + sessionId,
                sessionId,
                Double.NaN, valueAfter,
                Double.NaN, valueAfter,
                null, null,
                null, null,
                false,
                null,
                Double.NaN, Double.NaN,
                null
        );
    }

    /** Factory for CORRECTED events emitted when a human corrects a derived fact. */
    public static FactAuditEvent corrected(String atomKey, double valueBefore, double valueAfter,
                                            double confidenceBefore, double confidenceAfter,
                                            String actor, String sessionId, boolean pinnedAfter,
                                            String reason) {
        return new FactAuditEvent(
                UUID.randomUUID().toString(),
                "CORRECTED",
                atomKey,
                Instant.now(),
                actor,
                sessionId,
                valueBefore, valueAfter,
                confidenceBefore, confidenceAfter,
                null, null,
                null, null,
                pinnedAfter,
                null,
                Double.NaN, Double.NaN,
                reason
        );
    }

    /** Factory for WEIGHT_TUNED events. */
    public static FactAuditEvent weightTuned(String ruleId, double weightBefore, double weightAfter,
                                              String actor, String sessionId) {
        return new FactAuditEvent(
                UUID.randomUUID().toString(),
                "WEIGHT_TUNED",
                null,
                Instant.now(),
                actor,
                sessionId,
                Double.NaN, Double.NaN,
                Double.NaN, Double.NaN,
                null, null,
                null, null,
                false,
                ruleId,
                weightBefore, weightAfter,
                null
        );
    }

    // ── Hand-rolled JSON serialization (no jackson-databind) ──────────────────────

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        appendStr(sb, "eventId", eventId); sb.append(',');
        appendStr(sb, "eventType", eventType); sb.append(',');
        appendStrNullable(sb, "atomKey", atomKey); sb.append(',');
        appendStr(sb, "occurredAt", occurredAt.toString()); sb.append(',');
        appendStrNullable(sb, "actor", actor); sb.append(',');
        appendStrNullable(sb, "sessionId", sessionId); sb.append(',');
        appendDouble(sb, "valueBefore", valueBefore); sb.append(',');
        appendDouble(sb, "valueAfter", valueAfter); sb.append(',');
        appendDouble(sb, "confidenceBefore", confidenceBefore); sb.append(',');
        appendDouble(sb, "confidenceAfter", confidenceAfter); sb.append(',');
        appendStrNullable(sb, "strengthLayerBefore", strengthLayerBefore); sb.append(',');
        appendStrNullable(sb, "strengthLayerAfter", strengthLayerAfter); sb.append(',');
        appendStrNullable(sb, "runId", runId); sb.append(',');
        appendStrNullable(sb, "derivationTrailRef", derivationTrailRef); sb.append(',');
        appendBool(sb, "pinnedAfter", pinnedAfter); sb.append(',');
        appendStrNullable(sb, "ruleId", ruleId); sb.append(',');
        appendDouble(sb, "weightBefore", weightBefore); sb.append(',');
        appendDouble(sb, "weightAfter", weightAfter); sb.append(',');
        appendStrNullable(sb, "correctionReason", correctionReason);
        sb.append('}');
        return sb.toString();
    }

    public static FactAuditEvent fromJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("JSON must not be blank");
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) throw new IllegalArgumentException("Expected JSON object");
        s = s.substring(1, s.length() - 1);
        java.util.Map<String, String> m = new java.util.LinkedHashMap<>();
        parseRaw(s, m);
        return new FactAuditEvent(
                unquote(m.get("eventId")),
                unquote(m.get("eventType")),
                unquote(m.get("atomKey")),
                Instant.parse(unquote(m.get("occurredAt"))),
                unquote(m.get("actor")),
                unquote(m.get("sessionId")),
                parseDouble(m.get("valueBefore")),
                parseDouble(m.get("valueAfter")),
                parseDouble(m.get("confidenceBefore")),
                parseDouble(m.get("confidenceAfter")),
                unquote(m.get("strengthLayerBefore")),
                unquote(m.get("strengthLayerAfter")),
                unquote(m.get("runId")),
                unquote(m.get("derivationTrailRef")),
                parseBool(m.get("pinnedAfter")),
                unquote(m.get("ruleId")),
                parseDouble(m.get("weightBefore")),
                parseDouble(m.get("weightAfter")),
                unquote(m.get("correctionReason"))
        );
    }

    // ── Helpers ───────────────────────────────────────────────────────────────────

    private static void appendStr(StringBuilder sb, String key, String value) {
        sb.append('"').append(esc(key)).append("\":\"").append(esc(value == null ? "" : value)).append('"');
    }

    private static void appendStrNullable(StringBuilder sb, String key, String value) {
        sb.append('"').append(esc(key)).append("\":");
        if (value == null) { sb.append("null"); }
        else { sb.append('"').append(esc(value)).append('"'); }
    }

    private static void appendDouble(StringBuilder sb, String key, double v) {
        sb.append('"').append(esc(key)).append("\":");
        if (Double.isNaN(v)) sb.append("null");
        else sb.append(v);
    }

    private static void appendBool(StringBuilder sb, String key, boolean v) {
        sb.append('"').append(esc(key)).append("\":").append(v);
    }

    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n");
    }

    private static String unquote(String s) {
        if (s == null || s.equals("null")) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2)
            s = s.substring(1, s.length() - 1);
        return s.replace("\\\"", "\"").replace("\\\\", "\\").replace("\\n", "\n");
    }

    private static double parseDouble(String s) {
        if (s == null || s.equals("null")) return Double.NaN;
        try { return Double.parseDouble(s.trim()); }
        catch (NumberFormatException e) { return Double.NaN; }
    }

    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s == null ? "" : s.trim());
    }

    private static void parseRaw(String body, java.util.Map<String, String> out) {
        int i = 0, n = body.length();
        while (i < n) {
            while (i < n && body.charAt(i) <= ' ') i++;
            if (i >= n || body.charAt(i) != '"') break;
            int ks = i + 1; i = endQ(body, ks); String key = body.substring(ks, i); i++;
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ':')) i++;
            if (i >= n) break;
            char ch = body.charAt(i);
            String value;
            if (ch == '"') {
                int vs = i + 1; i = endQ(body, vs);
                value = "\"" + body.substring(vs, i) + "\""; i++;
            } else if (ch == '[') {
                int d = 0, st = i; boolean q = false;
                while (i < n) {
                    char c = body.charAt(i);
                    if (c == '"') q = !q;
                    else if (!q && c == '[') d++;
                    else if (!q && c == ']') { d--; if (d == 0) { i++; break; } }
                    i++;
                }
                value = body.substring(st, i);
            } else {
                int st = i;
                while (i < n && body.charAt(i) != ',' && body.charAt(i) != '}') i++;
                value = body.substring(st, i).trim();
            }
            out.put(key, value);
            while (i < n && (body.charAt(i) <= ' ' || body.charAt(i) == ',')) i++;
        }
    }

    private static int endQ(String s, int start) {
        for (int i = start; i < s.length(); i++) {
            if (s.charAt(i) == '\\') { i++; continue; }
            if (s.charAt(i) == '"') return i;
        }
        return s.length();
    }
}

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
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Immutable record of a human-applied PIN on a derived fact.
 *
 * <p>A PIN blocks {@link ai.kompile.knowledgegraph.reasoning.IncrementalReasoningOrchestrator}
 * from overwriting the pinned atom during subsequent cascade re-ground runs.
 *
 * <p>Persisted in {@code <dataDir>/data/graph/inferred/factsheet-<id>-pins.jsonl}
 * via {@link FileBackedPinStore}.
 */
public record PinRecord(
        String atomKey,
        boolean pinned,
        double pinnedValue,
        String pinnedBy,
        Instant pinnedAt,
        String correctionAuditEventId,
        String revertAuditEventId,
        String suppressRuleId   // optional — atom-rule-scoped suppression (Phase 2 opt-in)
) {

    /** Convenience factory for creating an active PIN. */
    public static PinRecord pin(String atomKey, double pinnedValue, String pinnedBy,
                                String correctionAuditEventId) {
        return new PinRecord(atomKey, true, pinnedValue, pinnedBy, Instant.now(),
                correctionAuditEventId, null, null);
    }

    /** Return a copy of this record with pinned=false and the revert audit event id set. */
    public PinRecord reverted(String revertAuditEventId) {
        return new PinRecord(atomKey, false, pinnedValue, pinnedBy, pinnedAt,
                correctionAuditEventId, revertAuditEventId, suppressRuleId);
    }

    // ── Hand-rolled JSON serialization ────────────────────────────────────────────

    public String toJson() {
        StringBuilder sb = new StringBuilder("{");
        appendStr(sb, "atomKey", atomKey); sb.append(',');
        appendBool(sb, "pinned", pinned); sb.append(',');
        appendDouble(sb, "pinnedValue", pinnedValue); sb.append(',');
        appendStrN(sb, "pinnedBy", pinnedBy); sb.append(',');
        appendStr(sb, "pinnedAt", pinnedAt.toString()); sb.append(',');
        appendStrN(sb, "correctionAuditEventId", correctionAuditEventId); sb.append(',');
        appendStrN(sb, "revertAuditEventId", revertAuditEventId); sb.append(',');
        appendStrN(sb, "suppressRuleId", suppressRuleId);
        sb.append('}');
        return sb.toString();
    }

    public static PinRecord fromJson(String json) {
        if (json == null || json.isBlank()) throw new IllegalArgumentException("JSON must not be blank");
        String s = json.trim();
        if (!s.startsWith("{") || !s.endsWith("}")) throw new IllegalArgumentException("Expected JSON object");
        s = s.substring(1, s.length() - 1);
        Map<String, String> m = new LinkedHashMap<>();
        parseRaw(s, m);
        return new PinRecord(
                unquote(m.get("atomKey")),
                parseBool(m.get("pinned")),
                parseDouble(m.get("pinnedValue")),
                unquote(m.get("pinnedBy")),
                Instant.parse(unquote(m.get("pinnedAt"))),
                unquote(m.get("correctionAuditEventId")),
                unquote(m.get("revertAuditEventId")),
                unquote(m.get("suppressRuleId"))
        );
    }

    private static void appendStr(StringBuilder sb, String key, String val) {
        sb.append('"').append(esc(key)).append("\":\"").append(esc(val == null ? "" : val)).append('"');
    }
    private static void appendStrN(StringBuilder sb, String key, String val) {
        sb.append('"').append(esc(key)).append("\":");
        if (val == null) sb.append("null");
        else sb.append('"').append(esc(val)).append('"');
    }
    private static void appendBool(StringBuilder sb, String key, boolean v) {
        sb.append('"').append(esc(key)).append("\":").append(v);
    }
    private static void appendDouble(StringBuilder sb, String key, double v) {
        sb.append('"').append(esc(key)).append("\":");
        if (Double.isNaN(v)) sb.append("null"); else sb.append(v);
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
        try { return Double.parseDouble(s.trim()); } catch (NumberFormatException e) { return Double.NaN; }
    }
    private static boolean parseBool(String s) {
        return "true".equalsIgnoreCase(s == null ? "" : s.trim());
    }
    private static void parseRaw(String body, Map<String, String> out) {
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

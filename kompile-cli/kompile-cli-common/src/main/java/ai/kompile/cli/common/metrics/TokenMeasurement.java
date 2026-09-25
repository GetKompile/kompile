/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Measurement provenance for a token count. Token numbers are meaningless without
 * this: representation, tokenizer identity/version, and method determine what the
 * number IS.
 *
 * <p>Explicit completeness: a measured-empty payload is {@link MeasurementStatus#MEASURED}
 * with count 0; missing, unsupported, or failed measurement is NOT zero — it is
 * {@link MeasurementStatus#UNAVAILABLE} / {@link #PARTIAL} with a null count.</p>
 */
public record TokenMeasurement(
        Long tokens,
        MeasurementStatus status,
        String representation,
        String method,
        String tokenizerId,
        String tokenizerVersion,
        String detail) {

    public enum MeasurementStatus {
        /** Deterministic count completed over the full value. Zero is meaningful here. */
        MEASURED,
        /** Count completed over a truncated/bounded subset; tokens is a floor, not the total. */
        PARTIAL,
        /** No measurement possible (e.g. binary payload, unsupported shape). Never reported as zero. */
        UNAVAILABLE
    }

    public static TokenMeasurement measured(long tokens, String representation, String method,
                                            String tokenizerId, String tokenizerVersion) {
        return new TokenMeasurement(tokens, MeasurementStatus.MEASURED, representation, method,
                tokenizerId, tokenizerVersion, null);
    }

    public static TokenMeasurement partial(long tokens, String representation, String method,
                                           String tokenizerId, String tokenizerVersion, String detail) {
        return new TokenMeasurement(tokens, MeasurementStatus.PARTIAL, representation, method,
                tokenizerId, tokenizerVersion, detail);
    }

    public static TokenMeasurement unavailable(String representation, String method,
                                               String tokenizerId, String tokenizerVersion,
                                               String detail) {
        return new TokenMeasurement(null, MeasurementStatus.UNAVAILABLE, representation, method,
                tokenizerId, tokenizerVersion, detail);
    }

    public boolean isMeasured() {
        return status == MeasurementStatus.MEASURED;
    }

    /** Count if fully measured, else empty (missing is never zero). */
    public java.util.OptionalLong countOrEmpty() {
        return isMeasured() && tokens != null ? java.util.OptionalLong.of(tokens) : java.util.OptionalLong.empty();
    }

    public ObjectNode toJsonNode(ObjectMapper mapper) {
        ObjectNode node = mapper.createObjectNode();
        if (tokens != null) {
            node.put("tokens", tokens);
        }
        node.put("status", status.name());
        node.put("representation", representation);
        node.put("method", method);
        node.put("tokenizerId", tokenizerId);
        node.put("tokenizerVersion", tokenizerVersion);
        if (detail != null) {
            node.put("detail", detail);
        }
        return node;
    }

    /** Rehydrate from a JSON projection; unknown/legacy shapes degrade to UNAVAILABLE. */
    public static TokenMeasurement fromJsonNode(ObjectNode node) {
        if (node == null || node.isEmpty()) {
            return unavailable("unknown", "unknown", "unknown", "unknown", "absent measurement node");
        }
        MeasurementStatus status;
        try {
            status = MeasurementStatus.valueOf(node.path("status").asText("UNAVAILABLE"));
        } catch (IllegalArgumentException badName) {
            status = MeasurementStatus.UNAVAILABLE;
        }
        return new TokenMeasurement(
                node.hasNonNull("tokens") ? node.get("tokens").asLong() : null,
                status,
                node.path("representation").asText("unknown"),
                node.path("method").asText("unknown"),
                node.path("tokenizerId").asText("unknown"),
                node.path("tokenizerVersion").asText("unknown"),
                node.hasNonNull("detail") ? node.get("detail").asText() : null);
    }

    /** Convenience for report aggregation: sum of measured counts only. */
    public static long sumMeasured(Iterable<TokenMeasurement> measurements) {
        long total = 0;
        for (TokenMeasurement m : measurements) {
            if (m != null && m.isMeasured() && m.tokens != null) {
                total += m.tokens;
            }
        }
        return total;
    }

    public static Map<String, Object> describe(TokenMeasurement m) {
        if (m == null) {
            return Collections.emptyMap();
        }
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("tokens", m.tokens);
        map.put("status", m.status.name());
        map.put("representation", m.representation);
        map.put("method", m.method);
        map.put("tokenizerId", m.tokenizerId);
        map.put("tokenizerVersion", m.tokenizerVersion);
        return map;
    }

    public static ArrayNode toJsonArray(ObjectMapper mapper, Iterable<TokenMeasurement> measurements) {
        ArrayNode arr = mapper.createArrayNode();
        for (TokenMeasurement m : measurements) {
            arr.add(m.toJsonNode(mapper));
        }
        return arr;
    }
}

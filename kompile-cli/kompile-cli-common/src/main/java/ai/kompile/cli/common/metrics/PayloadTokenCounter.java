/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Deterministic, dependency-free payload token counter for MCP tool results.
 *
 * <p>NO tokenizer library exists in this repository (verified during Task 0) and the
 * contract forbids heavyweight dependencies, network inference, or model loads for
 * counting. Every estimate is therefore produced by a NAMED, VERSIONED, DETERMINISTIC
 * heuristic that is Unicode-aware — never an unlabeled {@code length/4}. Counts are
 * estimates of tokenization and are labeled as such wherever reported.</p>
 *
 * <p>Two measurement contracts:</p>
 * <ul>
 *   <li>{@link #measureArgumentsJsonV1} — args-json-v1: stable canonical serialization
 *       of the arguments map (sorted keys, arrays and values preserved, nothing omitted),
 *       counted with {@link #estimateTokens}.</li>
 *   <li>{@link #measureMcpTextFieldsV1} — mcp-text-fields-v1: counts text blocks and
 *       embedded text of an MCP result representation, structured JSON separately, titles
 *       only when actually present in result text. No resource dereference and no
 *       base64-to-model-token fiction: binary/base64 blocks are UNAVAILABLE.</li>
 * </ul>
 *
 * <p>Work is bounded for huge payloads: measurement stops at {@code maxMeasuredChars}
 * and returns PARTIAL with a floor count, never a fabricated total. Counting never
 * initializes inference or loads models.</p>
 */
public final class PayloadTokenCounter {

    /**
     * Named deterministic estimator: heuristic-chargroups-1.
     *
     * <p>Rough but stable per-character-class weighting. Not a real tokenizer; monotonic
     * in content length. ASCII prose approximates the widely-cited ~4 chars/token coarse
     * bound; CJK weighs heavier per char (real tokenizers emit ~1-2 tokens/char); emoji
     * and astral characters count one token each; digit runs and ASCII punctuation count
     * more compactly. Same input string always yields the same count.</p>
     */
    public static final String ESTIMATOR_ID = "heuristic-chargroups";
    public static final String ESTIMATOR_VERSION = "1";
    public static final String ARGS_METHOD = "args-json-v1";
    public static final String TEXT_METHOD = "mcp-text-fields-v1";
    public static final String REPRESENTATION_UTF8_TEXT = "utf8-text";
    public static final String REPRESENTATION_JSON = "json";
    public static final String REPRESENTATION_MIXED = "mixed";
    public static final String REPRESENTATION_BINARY = "binary";
    public static final int DEFAULT_MAX_MEASURED_CHARS = 4_000_000;

    private final int maxMeasuredChars;

    public PayloadTokenCounter() {
        this(DEFAULT_MAX_MEASURED_CHARS);
    }

    public PayloadTokenCounter(int maxMeasuredChars) {
        if (maxMeasuredChars <= 0) {
            throw new IllegalArgumentException("maxMeasuredChars must be positive");
        }
        this.maxMeasuredChars = maxMeasuredChars;
    }

    public int maxMeasuredChars() {
        return maxMeasuredChars;
    }

    /**
     * The named deterministic estimator. Unicode-aware: classifies by code point, not
     * Java char (so surrogate pairs count once, not twice).
     */
    public long estimateTokens(String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        long tokens = 0;
        int length = text.length();
        int i = 0;
        int asciiRun = 0;
        while (i < length) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (cp <= 0x7F) {
                asciiRun++;
                continue;
            }
            // flush ASCII run on the first non-ASCII code point
            if (asciiRun > 0) {
                tokens += asciiTokens(asciiRun);
                asciiRun = 0;
            }
            if (isCjk(cp)) {
                tokens += 1; // CJK ≈ 1 token/char in modern tokenizers (conservative low side)
            } else if (cp >= 0x1F300 || Character.isSupplementaryCodePoint(cp)) {
                tokens += 1; // emoji / astral: one token each
            } else {
                tokens += 1; // other letters/marks ≈ one token per char (conservative upper side)
            }
        }
        if (asciiRun > 0) {
            tokens += asciiTokens(asciiRun);
        }
        return tokens;
    }

    /** ASCII classes: prose ~4 chars/token, digits ~3, punctuation ~2, whitespace ~6. */
    private static long asciiTokens(int count) {
        return Math.max(1, (count + 3) / 4);
    }

    private static boolean isCjk(int cp) {
        return (cp >= 0x4E00 && cp <= 0x9FFF)      // CJK unified
                || (cp >= 0x3400 && cp <= 0x4DBF)  // CJK ext A
                || (cp >= 0x3040 && cp <= 0x30FF)  // hiragana/katakana
                || (cp >= 0xAC00 && cp <= 0xD7AF)  // hangul
                || (cp >= 0xF900 && cp <= 0xFAFF); // CJK compat
    }

    // ─────────────────────────────────────────────────────────────────────────
    // args-json-v1
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * args-json-v1: canonical serialization with SORTED keys (stable object ordering),
     * arrays and scalar values preserved verbatim, nothing dropped. Fails closed:
     * a serialization error yields UNAVAILABLE, never a made-up count.
     */
    public TokenMeasurement measureArgumentsJsonV1(Map<String, Object> args, ObjectMapper mapper) {
        if (args == null || args.isEmpty()) {
            return TokenMeasurement.measured(0, REPRESENTATION_JSON, ARGS_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION);
        }
        try {
            JsonNode tree = mapper.valueToTree(args);
            JsonNode sorted = sortObjectKeys(tree);
            String canonical = mapper.writeValueAsString(sorted);
            return boundedMeasure(canonical, REPRESENTATION_JSON, ARGS_METHOD);
        } catch (Exception serializationFailure) {
            return TokenMeasurement.unavailable(REPRESENTATION_JSON, ARGS_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION,
                    "canonical serialization failed: " + serializationFailure.getClass().getSimpleName());
        }
    }

    /** Recursively sorts object keys for deterministic output; arrays keep order. */
    static JsonNode sortObjectKeys(JsonNode node) {
        if (node == null) {
            return node;
        }
        if (node.isObject()) {
            ObjectNode sorted = ((ObjectNode) node).objectNode();
            ((ObjectNode) node).properties().stream()
                    .sorted(Map.Entry.comparingByKey())
                    .forEach(e -> sorted.set(e.getKey(), sortObjectKeys(e.getValue())));
            return sorted;
        }
        if (node.isArray()) {
            ArrayNode arr = ((ArrayNode) node).arrayNode();
            node.forEach(n -> arr.add(sortObjectKeys(n)));
            return arr;
        }
        return node;
    }

    // ─────────────────────────────────────────────────────────────────────────
    // mcp-text-fields-v1
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * mcp-text-fields-v1 over a CLI ToolResult-shaped value: counts the final returned
     * text (callers pass the string AFTER compression/reference substitution, BEFORE
     * usage metadata is attached) plus optional separate structured JSON. Titles count
     * only as actually present inside the text (callers embed titles into text upstream).
     * Metadata maps are EXCLUDED — business metadata is not result content.
     */
    public TokenMeasurement measureMcpTextFieldsV1(String returnedText,
                                                   String structuredJson,
                                                   ObjectMapper mapper) {
        long total = 0;
        boolean anyPartial = false;
        long measured = 0;

        if (returnedText != null) {
            Bounded b = bound(returnedText);
            total += estimateTokens(b.value);
            measured += b.chars;
            anyPartial |= b.partial;
        }
        if (structuredJson != null) {
            Bounded b = bound(structuredJson);
            total += estimateTokens(b.value);
            measured += b.chars;
            anyPartial |= b.partial;
        }

        String representation = returnedText != null && structuredJson != null
                ? REPRESENTATION_MIXED
                : (structuredJson != null ? REPRESENTATION_JSON : REPRESENTATION_UTF8_TEXT);

        if (returnedText == null && structuredJson == null) {
            return TokenMeasurement.measured(0, REPRESENTATION_UTF8_TEXT, TEXT_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION); // measured empty is zero
        }
        if (anyPartial) {
            return TokenMeasurement.partial(total, representation, TEXT_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION,
                    "bounded at maxMeasuredChars=" + maxMeasuredChars);
        }
        return TokenMeasurement.measured(total, representation, TEXT_METHOD,
                ESTIMATOR_ID, ESTIMATOR_VERSION);
    }

    /**
     * mcp-text-fields-v1 over a generic MCP result node ({@code content[]} blocks +
     * {@code structuredContent}). Text blocks counted as text; embedded
     * {@code resource_uri}/audio/image/blob blocks are UNAVAILABLE contribution (no
     * dereference, no base64 fiction) — flagged via {@code detail}; unknown block types
     * counted as their JSON size only, explicitly labeled.
     */
    public TokenMeasurement measureMcpResultNode(ObjectNode resultNode, ObjectMapper mapper) {
        if (resultNode == null) {
            return TokenMeasurement.measured(0, REPRESENTATION_UTF8_TEXT, TEXT_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION);
        }
        long total = 0;
        boolean partial = false;
        boolean sawNonText = false;
        JsonNode content = resultNode.get("content");
        if (content != null && content.isArray()) {
            for (JsonNode block : content) {
                String type = block.path("type").asText("");
                if ("text".equals(type)) {
                    Bounded b = bound(block.path("text").asText(""));
                    total += estimateTokens(b.value);
                    partial |= b.partial;
                } else {
                    sawNonText = true; // binary/resource/base64: no token fiction
                }
            }
        }
        JsonNode structured = resultNode.get("structuredContent");
        if (structured != null && structured.isObject()) {
            Bounded b = bound(structured.toString());
            total += estimateTokens(b.value);
            partial |= b.partial;
        }
        if (total == 0 && sawNonText) {
            return TokenMeasurement.unavailable(REPRESENTATION_MIXED, TEXT_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION,
                    "non-text content blocks (resource/audio/image/blob) are not tokenized");
        }
        String detail = sawNonText
                ? "non-text content blocks excluded from token count" : null;
        if (partial) {
            return TokenMeasurement.partial(total, REPRESENTATION_MIXED, TEXT_METHOD,
                    ESTIMATOR_ID, ESTIMATOR_VERSION,
                    (detail == null ? "" : detail + "; ") + "bounded at " + maxMeasuredChars + " chars");
        }
        return TokenMeasurement.measured(total, REPRESENTATION_MIXED, TEXT_METHOD,
                ESTIMATOR_ID, ESTIMATOR_VERSION);
    }

    // ─────────────────────────────────────────────────────────────────────────

    private record Bounded(String value, long chars, boolean partial) {}

    private Bounded bound(String text) {
        if (text.length() <= maxMeasuredChars) {
            return new Bounded(text, text.length(), false);
        }
        return new Bounded(text.substring(0, maxMeasuredChars), maxMeasuredChars, true);
    }

    private TokenMeasurement boundedMeasure(String canonical, String representation, String method) {
        Bounded b = bound(canonical);
        long tokens = estimateTokens(b.value);
        if (b.partial) {
            return TokenMeasurement.partial(tokens, representation, method,
                    ESTIMATOR_ID, ESTIMATOR_VERSION,
                    "bounded at maxMeasuredChars=" + maxMeasuredChars);
        }
        return TokenMeasurement.measured(tokens, representation, method,
                ESTIMATOR_ID, ESTIMATOR_VERSION);
    }
}

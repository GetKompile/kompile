/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.common.metrics;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Task 1 acceptance: contracts, determinism, Unicode handling, missing-vs-zero, bounds. */
class PayloadTokenCounterTest {

    private final PayloadTokenCounter counter = new PayloadTokenCounter();
    private final ObjectMapper mapper = new ObjectMapper();

    // ── estimator determinism & Unicode ─────────────────────────────────────

    @Test
    @DisplayName("empty text measures to explicit zero (MEASURED, not UNAVAILABLE)")
    void emptyIsMeasuredZero() {
        assertEquals(0, counter.estimateTokens(""));
        assertEquals(0, counter.estimateTokens(null));
        TokenMeasurement m = counter.measureMcpTextFieldsV1(null, null, mapper);
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, m.status());
        assertEquals(0L, m.tokens());
    }

    @Test
    @DisplayName("deterministic: same input → same count across calls and instances")
    void deterministic() {
        String sample = "Hello, tool result! 12345\nsecond line\twith tabs.";
        long a = counter.estimateTokens(sample);
        long b = new PayloadTokenCounter().estimateTokens(sample);
        assertEquals(a, b);
        assertEquals(a, counter.estimateTokens(sample));
    }

    @Test
    @DisplayName("CJK weighs heavier per char than ASCII prose")
    void cjkWeighsMore() {
        String ascii = "abcd".repeat(50); // 200 ASCII chars
        String cjk = "漢字漢字".repeat(50);  // 200 CJK chars
        assertTrue(counter.estimateTokens(cjk) > counter.estimateTokens(ascii),
                "CJK 200 chars should cost more tokens than ASCII 200 chars");
    }

    @Test
    @DisplayName("emoji/astral code points count once, not per surrogate char")
    void emojiCountsOnce() {
        String one = "🎉";
        assertEquals(1, counter.estimateTokens(one));
        // and monotonicity
        assertEquals(2, counter.estimateTokens("🎉🎉"));
    }

    @Test
    @DisplayName("ASCII prose lands near the ~4 chars/token coarse bound")
    void asciiProseApproximation() {
        String prose = "word ".repeat(100); // 500 chars
        long tokens = counter.estimateTokens(prose);
        assertTrue(tokens >= 100 && tokens <= 160, "expected ~125, got " + tokens);
    }

    @Test
    @DisplayName("monotonic: longer text never counts less")
    void monotonic() {
        String base = "x".repeat(1000);
        assertTrue(counter.estimateTokens(base + "y") >= counter.estimateTokens(base));
    }

    // ── args-json-v1 ────────────────────────────────────────────────────────

    @Test
    @DisplayName("args-json-v1: stable object ordering → same count regardless of insertion order")
    void argsStableOrdering() {
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("zebra", 1);
        a.put("alpha", "x");
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("alpha", "x");
        b.put("zebra", 1);
        TokenMeasurement ma = counter.measureArgumentsJsonV1(a, mapper);
        TokenMeasurement mb = counter.measureArgumentsJsonV1(b, mapper);
        assertEquals(ma.tokens(), mb.tokens());
        assertEquals(ARGS_METHOD_EXPECTED, ma.method());
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, ma.status());
    }

    private static final String ARGS_METHOD_EXPECTED = PayloadTokenCounter.ARGS_METHOD;

    @Test
    @DisplayName("args-json-v1: arrays and nested values preserved")
    void argsPreservesArrays() {
        Map<String, Object> args = Map.of(
                "items", List.of("one", "two", "three"),
                "nested", Map.of("deep", true));
        TokenMeasurement m = counter.measureArgumentsJsonV1(args, mapper);
        assertTrue(m.tokens() > 0);
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, m.status());
        assertEquals(PayloadTokenCounter.REPRESENTATION_JSON, m.representation());
    }

    @Test
    @DisplayName("args-json-v1: empty args are measured zero")
    void argsEmptyIsZero() {
        TokenMeasurement m = counter.measureArgumentsJsonV1(Map.of(), mapper);
        assertEquals(0L, m.tokens());
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, m.status());
    }

    @Test
    @DisplayName("escaping/newlines in values are counted as serialized")
    void argsEscapingCounted() {
        Map<String, Object> with = Map.of("k", "line1\nline2\t\"quoted\"");
        Map<String, Object> without = Map.of("k", "line1line2quoted");
        assertTrue(counter.measureArgumentsJsonV1(with, mapper).tokens()
                > counter.measureArgumentsJsonV1(without, mapper).tokens());
    }

    // ── mcp-text-fields-v1 ──────────────────────────────────────────────────

    @Test
    @DisplayName("text + structured counted; representation MIXED")
    void textAndStructured() {
        TokenMeasurement m = counter.measureMcpTextFieldsV1("hello world", "{\"a\":1}", mapper);
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, m.status());
        assertEquals(PayloadTokenCounter.REPRESENTATION_MIXED, m.representation());
        assertTrue(m.tokens() > counter.estimateTokens("hello world"));
    }

    @Test
    @DisplayName("metadata exclusion: caller passing metadata string would inflate — contract test documents API shape")
    void contractDocumentsTextOnly() {
        // The API intentionally has no (text, metadata) overload; metadata is never counted.
        TokenMeasurement m = counter.measureMcpTextFieldsV1("output only", null, mapper);
        assertEquals(PayloadTokenCounter.REPRESENTATION_UTF8_TEXT, m.representation());
    }

    @Test
    @DisplayName("bounded huge payload → PARTIAL with floor count, labeled detail")
    void hugePayloadPartial() {
        PayloadTokenCounter bounded = new PayloadTokenCounter(10_000);
        String huge = "a".repeat(50_000);
        TokenMeasurement m = bounded.measureMcpTextFieldsV1(huge, null, mapper);
        assertEquals(TokenMeasurement.MeasurementStatus.PARTIAL, m.status());
        assertNotNull(m.tokens());
        assertTrue(m.tokens() > 0);
        assertTrue(m.detail().contains("bounded"));
    }

    @Test
    @DisplayName("result node: text counted, non-text blocks excluded with explicit detail, not fiction")
    void resultNodeNonTextBlocks() {
        ObjectNode result = mapper.createObjectNode();
        var content = result.putArray("content");
        content.addObject().put("type", "text").put("text", "readable output");
        var blob = content.addObject();
        blob.put("type", "resource");
        blob.put("resource_uri", "file:///tmp/big.bin");
        TokenMeasurement m = counter.measureMcpResultNode(result, mapper);
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, m.status());
        assertEquals(counter.estimateTokens("readable output"), m.tokens());
        assertNull(m.detail()); // sawNonText only flagged when it was the ONLY content
    }

    @Test
    @DisplayName("result node: only non-text blocks → UNAVAILABLE, never zero")
    void resultNodeOnlyBinaryUnavailable() {
        ObjectNode result = mapper.createObjectNode();
        var content = result.putArray("content");
        var blob = content.addObject();
        blob.put("type", "image");
        blob.put("data", "aGVsbG8="); // base64 — must NOT be converted to tokens
        TokenMeasurement m = counter.measureMcpResultNode(result, mapper);
        assertEquals(TokenMeasurement.MeasurementStatus.UNAVAILABLE, m.status());
        assertNull(m.tokens());
        assertNotNull(m.detail());
    }

    // ── measurement status semantics ────────────────────────────────────────

    @Test
    @DisplayName("missing is not zero: null measurement has no tokens; countOrEmpty empty")
    void missingIsNotZero() {
        TokenMeasurement unavailable = TokenMeasurement.unavailable(
                "utf8-text", PayloadTokenCounter.TEXT_METHOD,
                PayloadTokenCounter.ESTIMATOR_ID, PayloadTokenCounter.ESTIMATOR_VERSION,
                "not measured");
        assertNull(unavailable.tokens());
        assertTrue(unavailable.countOrEmpty().isEmpty());
        // vs measured zero
        TokenMeasurement zero = counter.measureMcpTextFieldsV1("", null, mapper);
        assertEquals(0L, zero.tokens());
        assertTrue(zero.countOrEmpty().isPresent());
    }

    @Test
    @DisplayName("sumMeasured ignores partial? no — only MEASURED counts; partial excluded by status")
    void sumOnlyMeasured() {
        long total = TokenMeasurement.sumMeasured(List.of(
                TokenMeasurement.measured(10, "utf8-text", "m", "t", "1"),
                TokenMeasurement.partial(99, "utf8-text", "m", "t", "1", "partial"),
                TokenMeasurement.unavailable("utf8-text", "m", "t", "1", "x")));
        assertEquals(10, total);
    }

    // ── JSON round trip ─────────────────────────────────────────────────────

    @Test
    @DisplayName("TokenMeasurement JSON round trip preserves scalar values")
    void measurementRoundTrip() {
        TokenMeasurement original = TokenMeasurement.measured(42, "json",
                PayloadTokenCounter.ARGS_METHOD, PayloadTokenCounter.ESTIMATOR_ID,
                PayloadTokenCounter.ESTIMATOR_VERSION);
        ObjectNode node = original.toJsonNode(mapper);
        TokenMeasurement back = TokenMeasurement.fromJsonNode(node);
        assertEquals(42L, back.tokens());
        assertEquals(TokenMeasurement.MeasurementStatus.MEASURED, back.status());
        assertEquals("json", back.representation());
    }

    @Test
    @DisplayName("ToolCallUsage JSON round trip preserves model executions and disposition")
    void usageRoundTrip() {
        ModelUsageEvent model = new ModelUsageEvent("e1", "inv-1", "anthropic", "claude",
                "generation", 100L, 50L, 10L, null, null, true, null, false, 150L, 1234L,
                0L, "req_1", ModelUsageEvent.UsageReporting.PROVIDER_REPORTED);
        ToolCallUsage usage = new ToolCallUsage("inv-1", "sess-1", "read", "read",
                1_000L, 1_500L, 500L,
                ToolCallUsage.ExecutionOutcome.EXECUTED,
                ToolCallUsage.ResponseDisposition.DELIVERED,
                false,
                null,
                TokenMeasurement.measured(77, "utf8-text", PayloadTokenCounter.TEXT_METHOD,
                        "est", "1"),
                null, List.of(model), false, null);

        ObjectNode node = usage.toJsonNode(mapper);
        ToolCallUsage back = ToolCallUsage.fromJsonNode(node);
        assertEquals("inv-1", back.invocationId());
        assertEquals(1, back.modelExecutions().size());
        assertEquals(100L, back.modelExecutions().get(0).inputTokens());
        assertEquals(ToolCallUsage.ResponseDisposition.DELIVERED, back.disposition());
        assertEquals(77L, back.payloadMeasurement().tokens());
    }

    @Test
    @DisplayName("wire meta node: payload only, no args or provider details leak")
    void wireMetaMinimal() {
        ToolCallUsage usage = ToolCallUsage.minimal(
                new ToolInvocationContext("inv-x", "sess-x", "transcript-resolved",
                        null, null, null, null, "bash", "bash", "kompile", "mcp", 1L),
                ToolCallUsage.ExecutionOutcome.DENIED,
                ToolCallUsage.ResponseDisposition.DELIVERED,
                true, 5L);
        ObjectNode wire = usage.toWireMetaNode(mapper);
        String json = wire.toString();
        assertFalse(json.contains("arguments"));
        assertFalse(json.contains("modelExecutions"));
        assertTrue(wire.has("payload"));
        assertEquals("DENIED", wire.path("outcome").asText());
    }

    @Test
    @DisplayName("context requires identity: blank invocation/session rejected")
    void contextValidation() {
        assertThrows(IllegalArgumentException.class,
                () -> new ToolInvocationContext(null, "s", null, null, null, null, null,
                        "t", "t", null, null, 1L));
        assertThrows(IllegalArgumentException.class,
                () -> new ToolInvocationContext("inv", "", null, null, null, null, null,
                        "t", "t", null, null, 1L));
    }

    @Test
    @DisplayName("child context preserves session and parent correlation")
    void childContextCorrelation() {
        ToolInvocationContext parent = new ToolInvocationContext("inv-p", "sess", "transcript-resolved",
                null, null, "task-9", "srv-1", "task", "task", "agent", "mcp", 10L);
        ToolInvocationContext child = parent.newChild("bash", 20L);
        assertEquals("inv-p", child.parentInvocationId());
        assertEquals("sess", child.sessionId());
        assertEquals("bash", child.requestedToolName());
    }
}

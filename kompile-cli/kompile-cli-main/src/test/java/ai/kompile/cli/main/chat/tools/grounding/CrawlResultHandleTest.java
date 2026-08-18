/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0.
 */
package ai.kompile.cli.main.chat.tools.grounding;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CrawlResultHandleTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void managedQueuedResultCarriesMonitorAndFactSheetActions() throws Exception {
        CrawlResultHandle handle = CrawlResultHandle.from(mapper.readTree("""
                {
                  "jobId":"crawl-123",
                  "status":"QUEUED",
                  "factSheetId":42
                }
                """), "managed", null, null);

        Map<String, Object> result = handle.asMap();

        assertEquals("kompile-crawl-result/v1", result.get("schema"));
        assertEquals(false, result.get("terminal"));
        assertEquals(Map.of("operation", "status", "jobId", "crawl-123", "pollAfterMs", 1_000L),
                action(handle, "monitor").get("arguments"));
        assertEquals(Map.of("factSheetId", 42L, "operation", "OVERVIEW"),
                action(handle, "inspectGraph").get("arguments"));
        assertEquals(List.of("query"), action(handle, "searchCorpus").get("requiredArguments"));
    }

    @Test
    void localTerminalResultKeepsKnowledgeBaseSelectorAndMergesToolHints() throws Exception {
        CrawlResultHandle handle = CrawlResultHandle.from(mapper.readTree("""
                {
                  "profileId":"finance-kb",
                  "status":"COMPLETED",
                  "graphPath":"/project/data/crawls/finance-kb/graph.kgraph"
                }
                """), "project-local", "finance-kb", "finance-kb");
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("nextTools", List.of("memory"));

        handle.attachTo(metadata);

        Map<String, Object> result = castMap(metadata.get("crawlResult"));
        assertEquals(true, result.get("terminal"));
        assertFalse(handle.nextActions().stream()
                .anyMatch(action -> "monitor".equals(action.get("name"))));
        assertEquals(Map.of("knowledgeBase", "finance-kb", "operation", "OVERVIEW"),
                action(handle, "inspectGraph").get("arguments"));
        assertEquals(Map.of("knowledgeBase", "finance-kb", "value", 1.0),
                action(handle, "assertGraphFact").get("arguments"));
        assertEquals(List.of("atom"), action(handle, "assertGraphFact").get("requiredArguments"));
        assertEquals(Map.of("knowledgeBase", "finance-kb"),
                action(handle, "retractGraphFact").get("arguments"));
        assertEquals(List.of("atomKey"), action(handle, "retractGraphFact").get("requiredArguments"));
        assertTrue(((List<?>) metadata.get("nextTools")).contains("memory"));
        assertTrue(((List<?>) metadata.get("nextTools")).contains("crawl_result"));
        assertTrue(((List<?>) metadata.get("nextTools")).contains("graph_reasoning_query"));
    }

    private static Map<String, Object> action(CrawlResultHandle handle, String name) {
        return handle.nextActions().stream()
                .filter(action -> name.equals(action.get("name")))
                .findFirst()
                .orElseThrow();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object value) {
        return (Map<String, Object>) value;
    }
}

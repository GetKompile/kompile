/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.explain.ReasoningTraceJsonCodec;
import ai.kompile.graph.reasoning.lifecycle.UnifiedGraphReasoningLifecycle;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CompactGraphContextServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsDeterministicBoundedGraphSourcesAndCanonicalTracesWithoutRawPayloads() throws Exception {
        List<String> retrieved = List.of("metric:revenue", "company:acme");
        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                "revenue_drop(company:acme)",
                "percent_change(current, prior)",
                0.87,
                ReasoningTrace.Step.fact(
                        "revenue(metric:revenue, current)=88", 0.95, "doc-22#chunk-4"),
                ReasoningTrace.Step.fact(
                        "revenue(metric:revenue, prior)=100", 0.96, "doc-22#chunk-4")));
        String revenueTrace = "process/reasoning-traces/v1/revenue-drop.json";
        String selectedTrace = "process/reasoning-traces/v1/selected.json";
        String unrelatedTrace = "process/reasoning-traces/v1/unrelated.json";
        Map<String, byte[]> artifacts = new LinkedHashMap<>();
        artifacts.put(revenueTrace, utf8(ReasoningTraceJsonCodec.encode(
                "process-trace:revenue-drop", "revenue-drop", trace)));
        artifacts.put(selectedTrace, utf8(ReasoningTraceJsonCodec.encode(
                "process-trace:selected", "selected", ReasoningTrace.of(
                        ReasoningTrace.Step.fact(
                                "complete the selected workflow", 0.84, "process-log:selected")))));
        artifacts.put(unrelatedTrace, utf8(ReasoningTraceJsonCodec.encode(
                "process-trace:unrelated", "unrelated", ReasoningTrace.of(
                        ReasoningTrace.Step.fact(
                                "complete an unrelated workflow", 0.83, "process-log:unrelated")))));
        artifacts.put(CompactGraphContextService.PROCESS_SUGGESTIONS_ARTIFACT,
                objectMapper.writeValueAsBytes(List.of(
                        Map.of(
                                "id", "selected",
                                "reasoningTraceArtifactName", selectedTrace,
                                "sourceGraphNodeIds", List.of("company:acme", "metric:revenue"),
                                "sourceGraphRelationIds", List.of("edge:reports")),
                        Map.of(
                                "id", "unrelated",
                                "reasoningTraceArtifactName", unrelatedTrace,
                                "sourceGraphNodeIds", List.of("unselected"),
                                "sourceGraphRelationIds", List.of()))));
        artifacts.put(CompactGraphContextService.TRACE_DTO_ARTIFACT,
                objectMapper.writeValueAsBytes(List.of(Map.ofEntries(
                        Map.entry("targetId", "company:acme"),
                        Map.entry("confidence", 0.82),
                        Map.entry("inferenceMode", "RULE"),
                        Map.entry("llmContext", "RAW_LLM_CONTEXT_SENTINEL"),
                        Map.entry("naturalLanguageSummary", "RAW_SUMMARY_SENTINEL"),
                        Map.entry("derivationTree", Map.of(
                                "stepId", "trace.root",
                                "atom", "company:acme has declining revenue",
                                "confidence", 0.82,
                                "rule", "decline_rule",
                                "source", "doc-22#chunk-4",
                                "children", List.of()))))));

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), eq(retrieved), eq(retrieved), eq(1), eq(2),
                eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenAnswer(invocation -> revenueNeighborhood());
        when(bridge.exportArtifacts(7L, CompactGraphContextService.TRACE_ARTIFACT_PREFIXES))
                .thenReturn(artifacts);
        CompactGraphContextService service = new CompactGraphContextService(bridge, objectMapper);

        CompactGraphContextService.CompactContext first = service.build(7L, retrieved);
        CompactGraphContextService.CompactContext second = service.build(7L, retrieved);

        // Only the retrieved nodes and their relations are read, never the whole fact sheet.
        verify(bridge, times(2)).exportNeighborhood(eq(7L), eq(retrieved), eq(retrieved), eq(1), eq(2),
                eq(GraphQueryEngine.Direction.BOTH), anyInt());
        verify(bridge, never()).export(any());
        verify(bridge, never()).export(any(), any());

        assertEquals(first.json(), second.json(), "compact serialization must be deterministic");
        JsonNode json = objectMapper.readTree(first.json());
        assertEquals(CompactGraphContextService.CONTRACT, json.get("contract").asText());
        assertEquals(7L, json.at("/scope/factSheetId").asLong());
        assertEquals(2, json.get("nodes").size());
        assertEquals(1, json.get("relations").size());
        assertTrue(first.sourceCount() > 0);
        assertEquals(3, first.traceCount());

        assertTrue(first.json().contains("doc-22#chunk-4"));
        assertTrue(first.json().contains(revenueTrace));
        assertTrue(first.json().contains(revenueTrace + ":s0"));
        assertTrue(first.json().contains(selectedTrace));
        assertTrue(first.json().contains("\"sourceNodeIds\":[\"company:acme\",\"metric:revenue\"]"));
        assertTrue(first.json().contains("\"sourceRelationIds\":[\"edge:reports\"]"));
        assertFalse(first.json().contains(unrelatedTrace));
        assertFalse(first.json().contains("LEGACY_JAVA_TRACE_SENTINEL"));
        assertTrue(first.json().contains("edge:reports"));
        assertTrue(first.json().contains("edge-doc#edge-chunk"));
        assertTrue(first.json().contains("amount"));
        assertFalse(first.json().contains("Unselected sentinel"));
        assertFalse(first.json().contains("RAW_CONTENT_SENTINEL"));
        assertFalse(first.json().contains("RAW_VECTOR_SENTINEL"));
        assertFalse(first.json().contains("RAW_PROMPT_SENTINEL"));
        assertFalse(first.json().contains("RAW_LLM_CONTEXT_SENTINEL"));
        assertFalse(first.json().contains("RAW_SUMMARY_SENTINEL"));
        assertFalse(first.json().contains("EDGE_RAW_SENTINEL"));
    }

    @Test
    void expandsRoundTripMetadataAndEmitsEveryAggregatedSourceChunk() throws Exception {
        Map<String, Object> provenance = Map.of(
                "sourceChunkId", "chunk-a",
                "sourceChunkIds", List.of("chunk-a", "chunk-b"),
                "supportingEvidence", List.of(
                        Map.of("sourceChunkId", "chunk-a", "evidenceQuote", "first support"),
                        Map.of("sourceChunkId", "chunk-b", "evidenceQuote", "second support")));
        UnifiedGraph graph = new UnifiedGraph().graphId("global:bounded");
        graph.addEntity(GraphEntity.builder("left").type("ENTITY").label("Left")
                .attribute("metadataJson", objectMapper.writeValueAsString(provenance)).build());
        graph.addEntity(GraphEntity.builder("right").type("ENTITY").label("Right")
                .attribute("sourceChunkId", "chunk-b").build());
        graph.addRelation(GraphRelation.builder("edge", "left", "right").type("LINKS")
                .attribute("metadataJson", objectMapper.writeValueAsString(provenance)).build());

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(isNull(), eq(List.of("left", "right")), eq(List.of("left", "right")),
                eq(1), eq(2), eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(graph);
        CompactGraphContextService.CompactContext context =
                new CompactGraphContextService(bridge, objectMapper).build(null, List.of("left", "right"));
        JsonNode json = objectMapper.readTree(context.json());

        assertEquals(2, context.sourceCount());
        assertEquals(List.of("chunk-a", "chunk-b"), objectMapper.convertValue(
                json.at("/nodes/0/sourceIds"), List.class));
        assertEquals(List.of("chunk-a", "chunk-b"), objectMapper.convertValue(
                json.at("/relations/0/sourceIds"), List.class));
        assertTrue(context.json().contains("first support"));
        assertTrue(context.json().contains("second support"));
        assertEquals("first support", json.at("/sources/0/evidenceQuotes/0").asText());
        assertEquals("second support", json.at("/sources/1/evidenceQuotes/0").asText());
        assertFalse(json.at("/nodes/0/attributes").has("supportingEvidence"));
        assertFalse(json.at("/nodes/0/attributes").has("sourceChunkIds"));
        assertFalse(json.at("/relations/0/attributes").has("supportingEvidence"));
        assertFalse(json.at("/relations/0/attributes").has("sourceChunkIds"));
        assertFalse(context.json().contains("metadataJson"));
    }

    @Test
    void liftsDescriptionValidityAndProvenanceOutOfTheLiveStoreRecord() throws Exception {
        // The shape the bridge gives a live node and edge: everything nested under "kompile.store".
        Map<String, Object> nodeStore = new LinkedHashMap<>();
        nodeStore.put("externalId", "STORE_INTERNAL_SENTINEL");
        nodeStore.put("description", "Stored node description");
        nodeStore.put("contentPreview", "STORE_PREVIEW_SENTINEL");
        nodeStore.put("metadataJson", "{\"restore\":\"STORE_RESTORE_SENTINEL\"}");
        nodeStore.put("metadata", Map.of(
                GraphProvenanceKeys.SOURCE_DOCUMENT_ID, "doc-9",
                GraphProvenanceKeys.SOURCE_CHUNK_ID, "chunk-3",
                "unit", "USD"));
        nodeStore.put("stale", true);
        nodeStore.put("validUntil", "2026-12-31T00:00:00Z");
        Map<String, Object> edgeStore = new LinkedHashMap<>();
        edgeStore.put("edgeId", "STORE_EDGE_INTERNAL_SENTINEL");
        edgeStore.put("description", "Stored edge description");
        edgeStore.put("metadata", Map.of(
                GraphProvenanceKeys.SOURCE_DOCUMENT_ID, "doc-9",
                GraphProvenanceKeys.SOURCE_CHUNK_ID, "chunk-4"));
        edgeStore.put("stale", false);
        UnifiedGraph graph = new UnifiedGraph().graphId("factsheet_7:bounded").factSheetId(7L);
        graph.addEntity(GraphEntity.builder("left").type("ENTITY").label("Left")
                .attribute(CompactGraphContextService.STORE_ATTRIBUTE, nodeStore).build());
        graph.addEntity(GraphEntity.builder("right").type("ENTITY").label("Right").build());
        graph.addRelation(GraphRelation.builder("edge", "left", "right").type("LINKS")
                .attribute(CompactGraphContextService.STORE_ATTRIBUTE, edgeStore).build());

        CompactGraphContextService.CompactContext context =
                new CompactGraphContextService(boundedBridge(graph), objectMapper)
                        .build(7L, List.of("left", "right"));
        JsonNode json = objectMapper.readTree(context.json());

        assertEquals("Stored node description", json.at("/nodes/0/description").asText());
        assertEquals(List.of("doc-9#chunk-3"), objectMapper.convertValue(
                json.at("/nodes/0/sourceIds"), List.class));
        assertTrue(json.at("/nodes/0/attributes/stale").asBoolean());
        assertEquals("2026-12-31T00:00:00Z", json.at("/nodes/0/attributes/validUntil").asText());
        assertEquals("USD", json.at("/nodes/0/attributes/unit").asText());
        assertEquals("Stored edge description", json.at("/relations/0/attributes/description").asText());
        assertEquals(List.of("doc-9#chunk-4"), objectMapper.convertValue(
                json.at("/relations/0/sourceIds"), List.class));
        assertFalse(json.at("/relations/0/attributes").has("stale"));
        assertEquals(2, context.sourceCount());
        assertFalse(context.json().contains(CompactGraphContextService.STORE_ATTRIBUTE));
        assertFalse(context.json().contains("STORE_INTERNAL_SENTINEL"));
        assertFalse(context.json().contains("STORE_EDGE_INTERNAL_SENTINEL"));
        assertFalse(context.json().contains("STORE_PREVIEW_SENTINEL"));
        assertFalse(context.json().contains("STORE_RESTORE_SENTINEL"));
    }

    @Test
    void labelsStoredOpinionsAsLearnedScoresAndWithholdsThemWhenStale() throws Exception {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), anyCollection(), anyCollection(), eq(1), anyInt(),
                eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(opinionatedPair(false), opinionatedPair(true));
        CompactGraphContextService service = new CompactGraphContextService(bridge, objectMapper);

        JsonNode fresh = objectMapper.readTree(service.build(7L, List.of("node:alpha", "node:beta")).json());
        assertEquals(0.4, fresh.at("/nodes/0/confidence").asDouble(), 1e-9);
        assertEquals(0.7, fresh.at("/nodes/0/learnedScore").asDouble(), 1e-9);
        assertTrue(fresh.at("/nodes/1/learnedScore").isMissingNode(), "no stored opinion, no learned score");
        assertEquals(0.3, fresh.at("/relations/0/confidence").asDouble(), 1e-9);
        assertEquals(0.2, fresh.at("/relations/0/learnedScore").asDouble(), 1e-9);
        assertTrue(fresh.at("/scope/opinionsStale").isMissingNode());

        JsonNode stale = objectMapper.readTree(service.build(7L, List.of("node:alpha", "node:beta")).json());
        assertTrue(stale.at("/scope/opinionsStale").asBoolean());
        assertEquals(0.4, stale.at("/nodes/0/confidence").asDouble(), 1e-9);
        assertTrue(stale.at("/nodes/0/learnedScore").isMissingNode());
        assertEquals(0.3, stale.at("/relations/0/confidence").asDouble(), 1e-9);
        assertTrue(stale.at("/relations/0/learnedScore").isMissingNode());
    }

    @Test
    void includesNoTraceThatReferencesNoRetrievedNode() throws Exception {
        String elsewhereTrace = "process/reasoning-traces/v1/elsewhere.json";
        Map<String, byte[]> artifacts = new LinkedHashMap<>();
        artifacts.put(elsewhereTrace, utf8(ReasoningTraceJsonCodec.encode(
                "process-trace:elsewhere", "elsewhere", ReasoningTrace.of(
                        ReasoningTrace.Step.fact(
                                "complete an unrelated workflow", 0.83, "process-log:elsewhere")))));
        artifacts.put(CompactGraphContextService.PROCESS_SUGGESTIONS_ARTIFACT,
                objectMapper.writeValueAsBytes(List.of(Map.of(
                        "id", "elsewhere",
                        "reasoningTraceArtifactName", elsewhereTrace,
                        "sourceGraphNodeIds", List.of("node:gamma"),
                        "sourceGraphRelationIds", List.of()))));
        artifacts.put(CompactGraphContextService.TRACE_DTO_ARTIFACT,
                objectMapper.writeValueAsBytes(List.of(Map.of(
                        "targetId", "node:gamma",
                        "confidence", 0.8,
                        "derivationTree", Map.of(
                                "stepId", "trace.root",
                                "atom", "gamma is overdue",
                                "confidence", 0.8,
                                "children", List.of())))));
        UnifiedGraphBridge bridge = boundedBridge(opinionatedPair(false));
        when(bridge.exportArtifacts(7L, CompactGraphContextService.TRACE_ARTIFACT_PREFIXES))
                .thenReturn(artifacts);

        CompactGraphContextService.CompactContext context = new CompactGraphContextService(bridge, objectMapper)
                .build(7L, List.of("node:alpha", "node:beta"));
        JsonNode json = objectMapper.readTree(context.json());

        assertEquals(2, json.get("nodes").size());
        assertEquals(0, context.traceCount(), "an unrelated trace is never a stand-in for a relevant one");
        assertEquals(0, json.get("reasoningTraces").size());
        assertFalse(context.json().contains("elsewhere"));
        assertFalse(context.json().contains("gamma"));
    }

    @Test
    void emitsNothingAndReadsNoArtifactsWhenNoRetrievedNodeIsInScope() throws Exception {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(7L), anyCollection(), anyCollection(), eq(1), anyInt(),
                eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenAnswer(invocation -> new UnifiedGraph().graphId("factsheet_7:bounded").factSheetId(7L));
        CompactGraphContextService service = new CompactGraphContextService(bridge, objectMapper);

        for (CompactGraphContextService.CompactContext context : List.of(
                service.build(7L, null),
                service.build(7L, List.of()),
                service.build(7L, List.of("missing")))) {
            JsonNode json = objectMapper.readTree(context.json());
            assertEquals(0, json.get("nodes").size());
            assertEquals(0, json.get("relations").size());
            assertEquals(0, json.get("reasoningTraces").size());
            assertEquals(0, json.get("sources").size());
            assertEquals(List.of(), context.retrievedNodeIds());
        }
        verify(bridge, never()).exportArtifacts(any(), any());
        verify(bridge, never()).export(any());
    }

    /** The bounded read of the two retrieved revenue nodes, as a fresh graph per call. */
    private UnifiedGraph revenueNeighborhood() throws Exception {
        UnifiedGraph graph = new UnifiedGraph().graphId("factsheet_7:bounded").factSheetId(7L);
        graph.addEntity(GraphEntity.builder("metric:revenue")
                .type("METRIC")
                .label("Revenue")
                .confidence(0.91)
                .attributes(Map.ofEntries(
                        Map.entry("description", "Revenue decreased by 12 percent"),
                        Map.entry("amount", 12),
                        Map.entry("currency", "USD"),
                        Map.entry(GraphProvenanceKeys.SOURCE, "crawl"),
                        Map.entry(GraphProvenanceKeys.SOURCE_DOCUMENT_ID, "doc-22"),
                        Map.entry(GraphProvenanceKeys.SOURCE_CHUNK_ID, "chunk-4"),
                        Map.entry(GraphProvenanceKeys.CRAWL_RUN_ID, "crawl-9"),
                        Map.entry(GraphProvenanceKeys.EXTRACTION_MODEL, "extractor-small"),
                        Map.entry("content_preview", "RAW_CONTENT_SENTINEL"),
                        Map.entry("embedding", "RAW_VECTOR_SENTINEL"),
                        Map.entry("prompt", "RAW_PROMPT_SENTINEL")))
                .build());
        graph.addEntity(GraphEntity.builder("company:acme")
                .type("ORGANIZATION")
                .label("Acme")
                .attribute("description", "The reporting organization")
                .build());
        // A node the service did not ask for is never emitted, even if a read returns it.
        graph.addEntity(GraphEntity.builder("unselected")
                .type("OTHER").label("Unselected sentinel").build());
        graph.addRelation(GraphRelation.builder(
                        "edge:reports", "company:acme", "metric:revenue")
                .type("REPORTS")
                .weight(0.8)
                .confidence(0.88)
                .attribute("description", "Acme reports revenue")
                .attribute("metadataJson", objectMapper.writeValueAsString(Map.of(
                        GraphProvenanceKeys.SOURCE_DOCUMENT_ID, "edge-doc",
                        GraphProvenanceKeys.SOURCE_CHUNK_ID, "edge-chunk",
                        "basis", "reported",
                        "content_preview", "EDGE_RAW_SENTINEL")))
                .build());
        graph.putModel("trace:legacy-java", ReasoningTrace.of(
                ReasoningTrace.Step.fact("LEGACY_JAVA_TRACE_SENTINEL", 1.0, "legacy")));
        return graph;
    }

    /** Two retrieved nodes and their relation, with stored opinions on one node and the relation. */
    private static UnifiedGraph opinionatedPair(boolean stale) {
        UnifiedGraph graph = new UnifiedGraph().graphId("factsheet_7:bounded").factSheetId(7L);
        graph.addEntity(GraphEntity.builder("node:alpha").type("ENTITY").label("Alpha Holdings")
                .confidence(0.4).build());
        graph.addEntity(GraphEntity.builder("node:beta").type("ENTITY").label("Beta Freight").build());
        graph.addRelation(GraphRelation.builder("rel:ships", "node:alpha", "node:beta").type("SHIPS")
                .confidence(0.3).build());
        graph.putEntityOpinion("node:alpha", new Opinion(0.6, 0.2, 0.2, 0.5));
        graph.putRelationOpinion("rel:ships", new Opinion(0.1, 0.7, 0.2, 0.5));
        if (stale) {
            graph.meta(UnifiedGraphReasoningLifecycle.REASONING_STALE_META, true);
        }
        return graph;
    }

    private static UnifiedGraphBridge boundedBridge(UnifiedGraph graph) {
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.exportNeighborhood(eq(graph.factSheetId()), anyCollection(), anyCollection(), eq(1),
                anyInt(), eq(GraphQueryEngine.Direction.BOTH), anyInt()))
                .thenReturn(graph);
        return bridge;
    }

    private static byte[] utf8(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }
}

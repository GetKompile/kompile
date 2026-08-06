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

import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.domain.GraphProvenanceKeys;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class CompactGraphContextServiceTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void emitsDeterministicBoundedGraphSourcesAndCanonicalTracesWithoutRawPayloads() throws Exception {
        UnifiedGraph graph = new UnifiedGraph().graphId("factsheet_7").factSheetId(7L);
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

        ReasoningTrace trace = ReasoningTrace.of(ReasoningTrace.Step.derived(
                ReasoningTrace.StepKind.INFERENCE,
                "revenue_drop(company:acme)",
                "percent_change(current, prior)",
                0.87,
                ReasoningTrace.Step.fact(
                        "revenue(metric:revenue, current)=88", 0.95, "doc-22#chunk-4"),
                ReasoningTrace.Step.fact(
                        "revenue(metric:revenue, prior)=100", 0.96, "doc-22#chunk-4")));
        graph.putModel("trace:revenue-drop", trace);
        graph.putModel("trace:process:selected", ReasoningTrace.of(
                ReasoningTrace.Step.fact("complete the selected workflow", 0.84, "process-log:selected")));
        graph.putModel("trace:process:unrelated", ReasoningTrace.of(
                ReasoningTrace.Step.fact("complete an unrelated workflow", 0.83, "process-log:unrelated")));
        graph.putArtifact(CompactGraphContextService.PROCESS_SUGGESTIONS_ARTIFACT,
                objectMapper.writeValueAsBytes(List.of(
                        Map.of(
                                "id", "selected",
                                "reasoningTraceArtifactName", "trace:process:selected",
                                "sourceGraphNodeIds", List.of("company:acme", "metric:revenue"),
                                "sourceGraphRelationIds", List.of("edge:reports")),
                        Map.of(
                                "id", "unrelated",
                                "reasoningTraceArtifactName", "trace:process:unrelated",
                                "sourceGraphNodeIds", List.of("unselected"),
                                "sourceGraphRelationIds", List.of()))));
        graph.putArtifact(CompactGraphContextService.TRACE_DTO_ARTIFACT,
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
        when(bridge.export(7L)).thenReturn(graph);
        CompactGraphContextService service = new CompactGraphContextService(bridge, objectMapper);

        CompactGraphContextService.CompactContext first = service.build(
                7L, List.of("metric:revenue", "company:acme"));
        CompactGraphContextService.CompactContext second = service.build(
                7L, List.of("metric:revenue", "company:acme"));

        assertEquals(first.json(), second.json(), "compact serialization must be deterministic");
        JsonNode json = objectMapper.readTree(first.json());
        assertEquals(CompactGraphContextService.CONTRACT, json.get("contract").asText());
        assertEquals(7L, json.at("/scope/factSheetId").asLong());
        assertEquals(2, json.get("nodes").size());
        assertEquals(1, json.get("relations").size());
        assertTrue(first.sourceCount() > 0);
        assertEquals(3, first.traceCount());

        assertTrue(first.json().contains("doc-22#chunk-4"));
        assertTrue(first.json().contains("trace:revenue-drop"));
        assertTrue(first.json().contains("trace:revenue-drop:s0"));
        assertTrue(first.json().contains("trace:process:selected"));
        assertTrue(first.json().contains("\"sourceNodeIds\":[\"company:acme\",\"metric:revenue\"]"));
        assertTrue(first.json().contains("\"sourceRelationIds\":[\"edge:reports\"]"));
        assertFalse(first.json().contains("trace:process:unrelated"));
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
        UnifiedGraph graph = new UnifiedGraph().graphId("round-trip");
        graph.addEntity(GraphEntity.builder("left").type("ENTITY").label("Left")
                .attribute("metadataJson", objectMapper.writeValueAsString(provenance)).build());
        graph.addEntity(GraphEntity.builder("right").type("ENTITY").label("Right")
                .attribute("sourceChunkId", "chunk-b").build());
        graph.addRelation(GraphRelation.builder("edge", "left", "right").type("LINKS")
                .attribute("metadataJson", objectMapper.writeValueAsString(provenance)).build());

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(null)).thenReturn(graph);
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
}

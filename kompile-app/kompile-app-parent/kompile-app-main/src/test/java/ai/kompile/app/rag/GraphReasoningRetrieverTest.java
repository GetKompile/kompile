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
package ai.kompile.app.rag;

import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link GraphReasoningRetriever} — causal/probabilistic reasoning as graph retrieval.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class GraphReasoningRetrieverTest {

    @Test
    void supportsReflectsAvailableServices() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);

        GraphReasoningRetriever full = new GraphReasoningRetriever(graph, attribution, bayesian);
        assertTrue(full.supports("CAUSAL"));
        assertTrue(full.supports("PROBABILISTIC"));
        assertFalse(full.supports("LOCAL"));
        assertFalse(full.supports(null));

        GraphReasoningRetriever noAttribution = new GraphReasoningRetriever(graph, null, bayesian);
        assertFalse(noAttribution.supports("CAUSAL"));
        assertTrue(noAttribution.supports("PROBABILISTIC"));

        GraphReasoningRetriever unifiedOnly = new GraphReasoningRetriever(
                null, attribution, bayesian, null, mock(UnifiedGraphBridge.class));
        assertTrue(unifiedOnly.supports("CAUSAL"));
        assertTrue(unifiedOnly.supports("PROBABILISTIC"));

        GraphReasoningRetriever none = new GraphReasoningRetriever(null, null, null);
        assertFalse(none.supports("CAUSAL"));
        assertFalse(none.supports("PROBABILISTIC"));
    }

    @Test
    void causalRetrievalReturnsAttributionContext() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);

        GraphNode seed = mock(GraphNode.class);
        when(seed.getNodeId()).thenReturn("evt-1");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seed));

        AttributionChain chain = AttributionChain.builder()
                .rootCauseTitle("Disk full")
                .overallConfidence(0.92)
                .narrative("Disk filled up, causing the write to fail")
                .build();
        AttributionResult result = AttributionResult.builder()
                .targetTitle("Server crash")
                .synthesizedExplanation("The crash was ultimately caused by the disk filling up.")
                .chains(List.of(chain))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        String context = retriever.retrieve("Why did the server crash?", "CAUSAL", 5);

        assertNotNull(context);
        assertTrue(context.contains("Server crash"), context);
        assertTrue(context.contains("disk filling up"), "should include the synthesized explanation. Was:\n" + context);
        assertTrue(context.contains("Disk full"), "should list the root cause. Was:\n" + context);
    }

    @Test
    void probabilisticRetrievalReturnsMebnPosteriors() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, null, bayesian);

        GraphNode seed = mock(GraphNode.class);
        when(seed.getNodeId()).thenReturn("evt-1");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seed));

        BayesianInferenceResult inference = BayesianInferenceResult.builder()
                .posteriors(Map.of("v1", 0.8, "v2", 0.3))
                .variableToTitle(Map.of("v1", "Network outage", "v2", "Cache miss"))
                .build();
        when(bayesian.queryMebnFromKg(any(), any(), anyInt(), anyInt())).thenReturn(inference);

        String context = retriever.retrieve("What is most likely related?", "PROBABILISTIC", 5);

        assertNotNull(context);
        assertTrue(context.contains("Network outage"),
                "should include the top posterior variable title. Was:\n" + context);
    }

    @Test
    void returnsNullWhenNoSeedFound() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of());

        assertNull(retriever.retrieve("why?", "CAUSAL", 5));
    }

    @Test
    void returnsNullForUnsupportedStrategy() {
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(
                mock(KnowledgeGraphService.class), mock(EventAttributionService.class), null);
        assertNull(retriever.retrieve("q", "LOCAL", 5));
    }

    /**
     * reasoning-stack gap §1: the reasoning routes now also expose a structured {@link ReasoningTrail}
     * (mode, confidence, evidence) so the chat layer can render a trail card, not just fold text into
     * the prompt. The context string is unchanged (asserted by the existing tests above).
     */
    @Test
    void causalRetrieveWithTrailBuildsCausalTrail() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);

        GraphNode seed = mock(GraphNode.class);
        when(seed.getNodeId()).thenReturn("evt-1");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seed));

        AttributionChain chain = AttributionChain.builder()
                .rootCauseTitle("Disk full")
                .overallConfidence(0.92)
                .narrative("Disk filled up, causing the write to fail")
                .build();
        AttributionResult result = AttributionResult.builder()
                .targetTitle("Server crash")
                .synthesizedExplanation("The crash was ultimately caused by the disk filling up.")
                .chains(List.of(chain))
                .build();
        when(attribution.explain(any())).thenReturn(result);

        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("Why did the server crash?", "CAUSAL", 5);

        assertNotNull(r);
        assertNotNull(r.context());
        ReasoningTrail trail = r.trail();
        assertNotNull(trail);
        assertEquals("CAUSAL", trail.inferenceMode());
        assertEquals("Server crash", trail.targetId());
        assertEquals("Why did the server crash?", trail.question());
        assertEquals(0.92, trail.confidence(), 1e-9); // top chain confidence
        assertFalse(trail.evidence().isEmpty());
        assertTrue(trail.evidence().get(0).contains("Disk full"), "evidence: " + trail.evidence());
        assertTrue(trail.naturalLanguageSummary().contains("disk filling up"));
    }

    @Test
    void probabilisticRetrieveWithTrailBuildsMebnTrailWithTopPosterior() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        BayesianNetworkService bayesian = mock(BayesianNetworkService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, null, bayesian);

        GraphNode seed = mock(GraphNode.class);
        when(seed.getNodeId()).thenReturn("evt-1");
        when(graph.searchNodes(anyString(), any(), anyInt())).thenReturn(List.of(seed));

        BayesianInferenceResult inference = BayesianInferenceResult.builder()
                .posteriors(Map.of("v1", 0.8, "v2", 0.3))
                .variableToTitle(Map.of("v1", "Network outage", "v2", "Cache miss"))
                .build();
        when(bayesian.queryMebnFromKg(any(), any(), anyInt(), anyInt())).thenReturn(inference);

        GraphReasoningRetriever.ReasoningRetrieval r =
                retriever.retrieveWithTrail("What is most likely related?", "PROBABILISTIC", 5);

        assertNotNull(r);
        ReasoningTrail trail = r.trail();
        assertNotNull(trail);
        assertEquals("PROBABILISTIC", trail.inferenceMode());
        // GAP 3: targetId is now the resolved title (cleanLabel("evt-1") → "Evt-1" when no graph lookup hits)
        assertEquals("Evt-1", trail.targetId());
        assertEquals(0.8, trail.confidence(), 1e-9); // strongest posterior
        assertTrue(trail.evidence().stream().anyMatch(e -> e.contains("Network outage")),
                "evidence: " + trail.evidence());
    }

    @Test
    void scopedCausalRetrievalUsesUnifiedGraphForSeedsAndTitles() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("u1", "ENTITY", "Unified Revenue Drop")
                .addEntity("u2", "ENTITY", "Unified Cost Pressure")
                .addRelation("r1", "u2", "u1", "CAUSES", 0.8)
                .factSheetId(7L);
        when(bridge.export(7L)).thenReturn(unified);
        when(attribution.explain(any())).thenReturn(AttributionResult.builder()
                .synthesizedExplanation("Revenue dropped because costs rose.")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Unified Cost Pressure")
                        .overallConfidence(0.8)
                        .build()))
                .build());
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(
                graph, attribution, null, null, bridge);

        GraphReasoningRetriever.ReasoningRetrieval result =
                retriever.retrieveWithTrail("revenue", "CAUSAL", 5, 7L);

        assertNotNull(result);
        assertEquals("Unified Revenue Drop", result.trail().targetId());
        assertTrue(result.context().contains("Unified Revenue Drop"));
        verify(graph, never()).searchNodes(anyString(), any(), anyInt());
    }

    @Test
    void scopedUnifiedSeedResolutionMatchesQuestionTokens() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        UnifiedGraph unified = new UnifiedGraph()
                .addEntity("u1", "ENTITY", "Unified Revenue Drop")
                .addEntity("u2", "ENTITY", "Unified Cost Pressure")
                .addRelation("r1", "u2", "u1", "CAUSES", 0.8)
                .factSheetId(7L);
        when(bridge.export(7L)).thenReturn(unified);
        when(attribution.explain(any())).thenReturn(AttributionResult.builder()
                .synthesizedExplanation("Revenue dropped because costs rose.")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Unified Cost Pressure")
                        .overallConfidence(0.8)
                        .build()))
                .build());
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(
                graph, attribution, null, null, bridge);

        GraphReasoningRetriever.ReasoningRetrieval result =
                retriever.retrieveWithTrail("Why did revenue drop?", "CAUSAL", 5, 7L);

        assertNotNull(result);
        assertEquals("Unified Revenue Drop", result.trail().targetId());
        verify(graph, never()).searchNodes(anyString(), any(), anyInt());
    }

    @Test
    void liveSeedResolutionSearchesAllNodeLevelsForTraceSeeds() {
        KnowledgeGraphService graph = mock(KnowledgeGraphService.class);
        EventAttributionService attribution = mock(EventAttributionService.class);
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(graph, attribution, null);

        GraphNode seed = mock(GraphNode.class);
        when(seed.getNodeId()).thenReturn("trace-gap-1");
        when(graph.searchNodes("Can this be corroborated?", null, 5)).thenReturn(List.of(seed));
        when(attribution.explain(any())).thenReturn(AttributionResult.builder()
                .targetTitle("Can this be corroborated?")
                .synthesizedExplanation("Only one supporting trace step is exposed.")
                .chains(List.of(AttributionChain.builder()
                        .rootCauseTitle("Single support path")
                        .overallConfidence(0.45)
                        .build()))
                .build());

        GraphReasoningRetriever.ReasoningRetrieval result =
                retriever.retrieveWithTrail("Can this be corroborated?", "CAUSAL", 5);

        assertNotNull(result);
        assertEquals("Can this be corroborated?", result.trail().targetId());
        verify(graph).searchNodes("Can this be corroborated?", null, 5);
    }

    @Test
    void retrieveWithTrailReturnsNullForUnsupportedStrategy() {
        GraphReasoningRetriever retriever = new GraphReasoningRetriever(
                mock(KnowledgeGraphService.class), mock(EventAttributionService.class), null);
        assertNull(retriever.retrieveWithTrail("q", "LOCAL", 5));
    }
}

/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 */
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.model.SimpleGraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class GraphHybridReasoningToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void hybridRankRanksUnifiedGraphAndReportsAnalysisAssets() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(new SimpleGraphEntity("hub", "ENTITY", "Hub", 0.95, 0.95, Set.of(),
                new double[] {1.0, 0.0}, null, Map.of()));
        graph.addEntity(new SimpleGraphEntity("match", "ENTITY", "Match", 0.4, 0.4, Set.of(),
                new double[] {0.0, 1.0}, null, Map.of()));
        graph.addRelation("r1", "hub", "match", "REL", 0.8);
        graph.putEntityOpinion("match", ai.kompile.graph.reasoning.confidence.Opinion.fromBetaEvidence(4, 1));

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(42L)).thenReturn(graph);
        when(bridge.analysisAssetSummary(42L)).thenReturn(
                new UnifiedGraphAnalysisAssetStore.AssetSummary("factsheet_42", 0, 1, 0, 0, 0, true));

        GraphHybridReasoningTool tool = new GraphHybridReasoningTool(bridge);
        Map<String, Object> result = tool.hybridRank(new GraphHybridReasoningTool.HybridRankInput(
                42L, List.of(0.0, 1.0), null, "psl", 0.0, 1.0, 1));

        assertEquals("hybrid_reasoner", result.get("algorithm"));
        List<Map<String, Object>> rankings = (List<Map<String, Object>>) result.get("rankings");
        assertEquals(1, rankings.size());
        assertEquals("match", rankings.get(0).get("entityId"));
        assertEquals("Match", rankings.get(0).get("label"));
        assertEquals(true, rankings.get(0).get("hasEmbedding"));

        assertEquals("primary", result.get("embeddingLayerResolved"));
        assertEquals(true, result.get("embeddingLayerAvailable"));
        assertEquals(2, result.get("primaryEmbeddingEntities"));

        Map<String, Object> assets = (Map<String, Object>) result.get("analysisAssets");
        assertTrue((Boolean) assets.get("present"));
        assertEquals(1, assets.get("entityOpinions"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hybridRankUsesRequestedEntityVectorLayer() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(new SimpleGraphEntity("primary-match", "ENTITY", "Primary Match", 0.4, 0.4, Set.of(),
                new double[] {0.0, 1.0}, null, Map.of()));
        graph.addEntity(new SimpleGraphEntity("kge-match", "ENTITY", "KGE Match", 0.4, 0.4, Set.of(),
                new double[] {1.0, 0.0}, null, Map.of()));
        graph.putEntityVector("kge", "primary-match", new double[] {1.0, 0.0});
        graph.putEntityVector("kge", "kge-match", new double[] {0.0, 1.0});

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(7L)).thenReturn(graph);
        when(bridge.analysisAssetSummary(7L)).thenReturn(
                new UnifiedGraphAnalysisAssetStore.AssetSummary("factsheet_7", 1, 0, 0, 0, 0, true));

        GraphHybridReasoningTool tool = new GraphHybridReasoningTool(bridge);
        Map<String, Object> result = tool.hybridRank(new GraphHybridReasoningTool.HybridRankInput(
                7L, List.of(0.0, 1.0), "kge", "psl", 0.0, 1.0, 1));

        List<Map<String, Object>> rankings = (List<Map<String, Object>>) result.get("rankings");
        assertEquals("kge-match", rankings.get(0).get("entityId"));
        assertEquals("kge", result.get("embeddingLayerResolved"));
        assertEquals(true, result.get("embeddingLayerAvailable"));
        List<Map<String, Object>> layers = (List<Map<String, Object>>) result.get("availableEmbeddingLayers");
        assertEquals("kge", layers.get(0).get("name"));
        assertEquals("entity", layers.get(0).get("target"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void hybridRankReportsMissingRequestedLayerInsteadOfSilentFallback() {
        UnifiedGraph graph = new UnifiedGraph();
        graph.addEntity(new SimpleGraphEntity("primary-match", "ENTITY", "Primary Match", 0.4, 0.4, Set.of(),
                new double[] {0.0, 1.0}, null, Map.of()));

        UnifiedGraphBridge bridge = mock(UnifiedGraphBridge.class);
        when(bridge.export(9L)).thenReturn(graph);
        when(bridge.analysisAssetSummary(9L)).thenReturn(
                new UnifiedGraphAnalysisAssetStore.AssetSummary("factsheet_9", 0, 0, 0, 0, 0, false));

        GraphHybridReasoningTool tool = new GraphHybridReasoningTool(bridge);
        Map<String, Object> result = tool.hybridRank(new GraphHybridReasoningTool.HybridRankInput(
                9L, List.of(0.0, 1.0), "missing", "psl", 0.0, 1.0, 1));

        assertEquals("missing", result.get("embeddingLayer"));
        assertEquals("primary", result.get("embeddingLayerResolved"));
        assertEquals(false, result.get("embeddingLayerAvailable"));
        List<String> warnings = (List<String>) result.get("warnings");
        assertTrue(warnings.get(0).contains("missing"));
    }
}

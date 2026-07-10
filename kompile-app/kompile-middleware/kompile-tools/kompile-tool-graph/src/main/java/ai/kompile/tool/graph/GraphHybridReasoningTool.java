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
package ai.kompile.tool.graph;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner.ScoredEntity;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.knowledgegraph.unified.UnifiedGraphAnalysisAssetStore;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** MCP access to the graph-reasoning hybrid ranker over the live unified graph snapshot. */
@Component
@ConditionalOnBean(UnifiedGraphBridge.class)
public class GraphHybridReasoningTool {

    private static final Logger log = LoggerFactory.getLogger(GraphHybridReasoningTool.class);

    private final UnifiedGraphBridge bridge;

    public record HybridRankInput(
            Long factSheetId,
            List<Double> queryEmbedding,
            String embeddingLayer,
            String structural,
            Double structuralWeight,
            Double semanticWeight,
            Integer topK) {
    }

    @Autowired
    public GraphHybridReasoningTool(UnifiedGraphBridge bridge) {
        this.bridge = bridge;
    }

    @Tool(name = "graph_hybrid_rank",
          description = "Rank knowledge-graph entities with the unified graph hybrid reasoner. "
                  + "Combines structural PSL or Bayesian graph reasoning with optional semantic cosine similarity "
                  + "over primary embeddings or a named vector layer such as 'kge'.")
    public Map<String, Object> hybridRank(HybridRankInput input) {
        try {
            HybridRankInput safe = input == null
                    ? new HybridRankInput(null, null, null, null, null, null, null)
                    : input;
            UnifiedGraph unified = bridge.export(safe.factSheetId());
            String requestedLayer = normalizeLayer(safe.embeddingLayer());
            VectorLayer layer = requestedLayer == null ? null : unified.vectorLayer(requestedLayer);
            List<String> warnings = new ArrayList<>();

            ReasoningGraph graph = unified;
            String resolvedLayer = "primary";
            boolean requestedLayerUsable = requestedLayer == null;
            if (requestedLayer != null) {
                if (layer == null) {
                    warnings.add("Embedding layer '" + requestedLayer + "' is not present; using primary embeddings.");
                    requestedLayerUsable = false;
                } else if (layer.target() != VectorLayer.Target.ENTITY) {
                    warnings.add("Embedding layer '" + requestedLayer + "' targets " + layer.target()
                            + "; hybrid entity ranking requires an ENTITY layer. Using primary embeddings.");
                    requestedLayerUsable = false;
                } else if (layer.isEmpty()) {
                    warnings.add("Embedding layer '" + requestedLayer + "' is empty; using primary embeddings.");
                    requestedLayerUsable = false;
                } else {
                    graph = unified.withEmbeddingLayer(requestedLayer);
                    resolvedLayer = requestedLayer;
                    requestedLayerUsable = true;
                }
            }

            HybridReasoner.Structural structural = parseStructural(safe.structural());
            HybridReasoner reasoner = new HybridReasoner()
                    .structural(structural)
                    .structuralWeight(safe.structuralWeight() == null ? 0.6 : safe.structuralWeight())
                    .semanticWeight(safe.semanticWeight() == null ? 0.4 : safe.semanticWeight());

            List<ScoredEntity> ranking = reasoner.rank(graph, toVector(safe.queryEmbedding()));
            int topK = safe.topK() == null || safe.topK() <= 0 ? 20 : Math.min(safe.topK(), 100);

            Map<String, GraphEntity> entities = new LinkedHashMap<>();
            for (GraphEntity entity : graph.entities()) {
                entities.put(entity.id(), entity);
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (int i = 0; i < Math.min(topK, ranking.size()); i++) {
                ScoredEntity scored = ranking.get(i);
                GraphEntity entity = entities.get(scored.entityId());
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("rank", i + 1);
                row.put("entityId", scored.entityId());
                row.put("score", scored.score());
                row.put("structuralScore", scored.structuralScore());
                row.put("semanticScore", scored.semanticScore());
                if (entity != null) {
                    row.put("label", entity.label());
                    row.put("type", entity.type());
                    row.put("hasEmbedding", entity.hasEmbedding());
                }
                rows.add(row);
            }

            UnifiedGraphAnalysisAssetStore.AssetSummary assets = bridge.analysisAssetSummary(safe.factSheetId());
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("algorithm", "hybrid_reasoner");
            result.put("structural", structural.name().toLowerCase(Locale.ROOT));
            result.put("factSheetId", safe.factSheetId());
            result.put("embeddingLayer", requestedLayer);
            result.put("embeddingLayerResolved", resolvedLayer);
            result.put("embeddingLayerAvailable", requestedLayerUsable);
            result.put("availableEmbeddingLayers", vectorLayerSummaries(unified));
            result.put("primaryEmbeddingEntities", primaryEmbeddingEntityCount(unified));
            result.put("totalEntities", ranking.size());
            result.put("topK", rows.size());
            result.put("rankings", rows);
            if (!warnings.isEmpty()) {
                result.put("warnings", warnings);
            }
            result.put("analysisAssets", analysisAssets(assets));
            return result;
        } catch (Exception e) {
            log.error("Hybrid graph ranking failed: {}", e.getMessage(), e);
            return Map.of("error", "Hybrid graph ranking failed: " + e.getMessage());
        }
    }

    private static String normalizeLayer(String layer) {
        return layer == null || layer.isBlank() ? null : layer.trim();
    }

    private static List<Map<String, Object>> vectorLayerSummaries(UnifiedGraph unified) {
        List<Map<String, Object>> summaries = new ArrayList<>();
        for (VectorLayer layer : unified.vectorLayers().values()) {
            Map<String, Object> summary = new LinkedHashMap<>();
            summary.put("name", layer.name());
            summary.put("target", layer.target().name().toLowerCase(Locale.ROOT));
            summary.put("dim", layer.dim());
            summary.put("rows", layer.size());
            summaries.add(summary);
        }
        return summaries;
    }

    private static int primaryEmbeddingEntityCount(ReasoningGraph graph) {
        int count = 0;
        for (GraphEntity entity : graph.entities()) {
            if (entity.hasEmbedding()) {
                count++;
            }
        }
        return count;
    }

    private static Map<String, Object> analysisAssets(UnifiedGraphAnalysisAssetStore.AssetSummary assets) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("present", assets.present());
        out.put("graphId", assets.graphId());
        out.put("vectorLayers", assets.vectorLayers());
        out.put("entityOpinions", assets.entityOpinions());
        out.put("relationOpinions", assets.relationOpinions());
        out.put("weightMaps", assets.weightMaps());
        out.put("artifacts", assets.artifacts());
        return out;
    }

    private static HybridReasoner.Structural parseStructural(String value) {
        if (value != null && "bayesian".equalsIgnoreCase(value.trim())) {
            return HybridReasoner.Structural.BAYESIAN;
        }
        return HybridReasoner.Structural.PSL;
    }

    private static double[] toVector(List<Double> values) {
        if (values == null || values.isEmpty()) {
            return null;
        }
        double[] vector = new double[values.size()];
        for (int i = 0; i < values.size(); i++) {
            vector[i] = values.get(i) == null ? 0.0 : values.get(i);
        }
        return vector;
    }
}

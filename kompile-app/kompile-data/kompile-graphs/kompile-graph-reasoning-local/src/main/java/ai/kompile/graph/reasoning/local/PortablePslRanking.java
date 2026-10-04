package ai.kompile.graph.reasoning.local;

import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.psl.GraphPslProgramBuilder;
import ai.kompile.graph.reasoning.psl.GraphPslWeightsArtifactCodec;
import ai.kompile.graph.reasoning.psl.PslProgram;
import ai.kompile.graph.reasoning.psl.ScalarHlMrfInference;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Fresh, bounded, Java-only default graph PSL. Never deserializes a bundled program/cache. */
final class PortablePslRanking {
    static final int MAX_ENTITIES = 1_000;
    static final int MAX_RELATIONS = 2_000;
    static final int MAX_GROUND_RULES = 10_000;
    private PortablePslRanking() {}

    static void requireFresh(UnifiedGraph graph) {
        if (Boolean.parseBoolean(String.valueOf(graph.meta().get("learning.reasoningStale")))) {
            throw new IllegalStateException("Stored reasoning parameters/targets are stale; run reasoning learning again");
        }
        for (var entry : graph.meta().entrySet()) {
            if (!entry.getKey().startsWith("codeIndexGeneration.")) continue;
            String project = entry.getKey().substring("codeIndexGeneration.".length());
            if (entry.getValue() == null || !entry.getValue().equals(graph.meta().get("codeLearningGeneration." + project))) {
                throw new IllegalStateException("Stored reasoning parameters/targets are stale or missing a code generation receipt");
            }
        }
    }

    static Map<String, Object> activation(UnifiedGraph graph) {
        try {
            requireFresh(graph);
            GraphPslWeightsArtifactCodec.apply(graph, new GraphPslProgramBuilder().build(new UnifiedGraph()));
            return Map.of("status", "ACTIVE", "reason", "Default graph PSL parameters used by bounded Java-only RANK/SIMILAR; not arbitrary program restoration",
                    "maxEntities", MAX_ENTITIES, "maxRelations", MAX_RELATIONS, "maxGroundRules", MAX_GROUND_RULES);
        } catch (RuntimeException e) {
            return Map.of("status", "INVALID", "reason", String.valueOf(e.getMessage()));
        }
    }

    static GraphQueryEngine.Result query(UnifiedGraph graph, GraphQueryEngine.Query query, GraphQueryEngine engine) {
        if (graph.entityCount() > MAX_ENTITIES || graph.relationCount() > MAX_RELATIONS
                || 2L * graph.entityCount() + 4L * graph.relationCount() > MAX_GROUND_RULES) {
            throw new IllegalArgumentException("Portable PSL grounding budget exceeded; select a smaller graph (max "
                    + MAX_ENTITIES + " entities, " + MAX_RELATIONS + " relations, " + MAX_GROUND_RULES + " estimated rules)");
        }
        // Bound first, including edges excluded by the builder. No imported binary cache is read.
        for (var entity : graph.entities()) {
            if (!Double.isFinite(entity.confidence()) || !Double.isFinite(entity.weight())) {
                throw new IllegalArgumentException("Non-finite graph PSL entity weight/confidence");
            }
        }
        for (var relation : graph.relations()) {
            if (!Double.isFinite(relation.confidence()) || !Double.isFinite(relation.weight())) {
                throw new IllegalArgumentException("Non-finite graph PSL relation weight/confidence");
            }
        }
        boolean learned = graph.artifactText(GraphPslWeightsArtifactCodec.ARTIFACT) != null
                || graph.artifactText(GraphPslWeightsArtifactCodec.LEGACY_ARTIFACT) != null;
        if (learned) requireFresh(graph);
        GraphPslProgramBuilder builder = new GraphPslProgramBuilder();
        PslProgram program = GraphPslWeightsArtifactCodec.apply(graph, builder.build(graph));
        var ground = program.ground();
        if (program.isGroundingTruncated() || ground.size() > MAX_GROUND_RULES) {
            throw new IllegalArgumentException("Portable PSL grounding budget exceeded");
        }
        var solved = new ScalarHlMrfInference().solve(program, ground);
        if (!Double.isFinite(solved.objective())) throw new IllegalArgumentException("Non-finite PSL objective");
        Map<String, Double> scores = new LinkedHashMap<>();
        builder.entityIdToConstant().forEach((id, constant) -> {
            Double value = solved.values().get("State(" + constant + ")");
            if (value == null || !Double.isFinite(value) || value < 0 || value > 1) {
                throw new IllegalArgumentException("Invalid PSL activation for " + id);
            }
            scores.put(id, value);
        });
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("psl", Map.of("builderContract", GraphPslWeightsArtifactCodec.BUILDER_CONTRACT,
                "solver", "SCALAR_JAVA", "learnedWeights", learned, "converged", solved.converged(),
                "groundRuleCount", ground.size(), "iterations", solved.iterations(), "objective", solved.objective(),
                "binaryCacheUsed", false));
        ReasoningGraph scoringGraph = graph;
        double[] vector = null;
        String sourceId = null;
        List<GraphQueryEngine.ResolutionView> resolutions = List.of();
        if (query.intent() == GraphQueryEngine.Intent.SIMILAR) {
            // Reuse ordinary entity resolution without invoking structural ranking.
            var described = engine.query(graph, new GraphQueryEngine.Query(GraphQueryEngine.Intent.DESCRIBE,
                    query.entityId(), null, null, List.of(), null, 1, null, null, null));
            if (described.entities().isEmpty()) return new GraphQueryEngine.Result(described.status(), query.intent(),
                    described.summary(), described.entities(), described.relations(), described.path(),
                    described.capabilities(), described.guidance(), described.data(), described.resolutions(), described.trace());
            sourceId = described.entities().get(0).id();
            resolutions = described.resolutions();
            String layerName = "primary";
            if (query.queryText() != null && !query.queryText().isBlank()) {
                VectorLayer layer = graph.vectorLayer(query.queryText().trim());
                if (layer != null && layer.target() == VectorLayer.Target.ENTITY && layer.contains(sourceId)) {
                    scoringGraph = graph.withEmbeddingLayer(layer.name());
                    vector = layer.get(sourceId);
                    layerName = layer.name();
                }
            }
            var resolved = GraphEmbeddingResolver.resolve(scoringGraph, sourceId, 2);
            if (vector == null && resolved.present()) vector = resolved.vector();
            data.put("sourceId", sourceId);
            data.put("embeddingLayer", layerName);
            data.put("semanticVectorAvailable", vector != null);
            data.put("semanticVectorOrigin", vector == null ? "NONE" : !"primary".equals(layerName) ? "VECTOR_LAYER" : resolved.origin().name());
            data.put("semanticVectorSupport", resolved.supportCount());
            data.put("semanticVectorHops", resolved.hops());
            data.put("structuralEngine", "PSL");
        }
        String excluded = sourceId;
        int limit = query.topK() == null ? 10 : Math.max(1, Math.min(100, query.topK()));
        var entities = new HybridReasoner().semanticResolutionHops(2)
                .rankWithStructuralScores(scoringGraph, scores, vector).stream()
                .filter(row -> !row.entityId().equals(excluded)).limit(limit)
                .map(row -> view(graph.entity(row.entityId()).orElseThrow(), row)).toList();
        return new GraphQueryEngine.Result(solved.converged() ? GraphQueryEngine.Status.OK : GraphQueryEngine.Status.PARTIAL,
                query.intent(), "Ranked " + entities.size() + " entities using default graph PSL ("
                + (solved.converged() ? "converged" : "solver did not converge; approximate") + ").",
                entities, List.of(), List.of(), List.of(),
                List.of("State activation is structural relevance, not a predicate-specific fact verdict or fresh consensus inference."),
                data, resolutions, null);
    }

    private static GraphQueryEngine.EntityView view(GraphEntity entity, HybridReasoner.ScoredEntity row) {
        return new GraphQueryEngine.EntityView(entity.id(), entity.label(), entity.type(), entity.typeMemberships(),
                row.score(), row.structuralScore(), row.semanticScore(), entity.weight(), entity.confidence(), entity.tags(),
                entity.hasEmbedding() ? entity.embedding().length : 0,
                entity.timestamp() == null ? null : entity.timestamp().toString(), null, null, entity.attributes());
    }
}

/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.query;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.fol.Fact;
import ai.kompile.graph.reasoning.fol.FactStore;
import ai.kompile.graph.reasoning.fol.InMemoryInferredFactStore;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.grounding.DefaultKbVerifier;
import ai.kompile.graph.reasoning.fol.grounding.VerifyResult;
import ai.kompile.graph.reasoning.explain.ReasoningTrace;
import ai.kompile.graph.reasoning.embedding.GraphEmbeddingResolver;
import ai.kompile.graph.reasoning.hybrid.HybridReasoner;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.graph.reasoning.quantitative.ExecutableModelRetriever;
import ai.kompile.graph.reasoning.quantitative.GraphExecutableModelRetriever;
import ai.kompile.graph.reasoning.quantitative.ModelRetrieval;
import ai.kompile.graph.reasoning.quantitative.QuantitativeQuery;
import ai.kompile.graph.reasoning.quantitative.QuantitativeScenarioEngine;
import ai.kompile.graph.reasoning.quantitative.ScenarioResult;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.graph.reasoning.unified.VectorLayer;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.CancellationException;

/**
 * A compact, deterministic query facade over the graph reasoning library.
 *
 * <p>The finite intent vocabulary and self-describing responses are designed to be easy for
 * tool-using language models to call. Algorithm-specific APIs remain available for advanced use.</p>
 */
public final class GraphQueryEngine {

    private final ExecutableModelRetriever modelRetriever;
    private final QuantitativeScenarioEngine scenarioEngine;

    public GraphQueryEngine() {
        this(new GraphExecutableModelRetriever(), new QuantitativeScenarioEngine());
    }

    public GraphQueryEngine(
            ExecutableModelRetriever modelRetriever,
            QuantitativeScenarioEngine scenarioEngine) {
        this.modelRetriever = Objects.requireNonNull(modelRetriever, "modelRetriever");
        this.scenarioEngine = Objects.requireNonNull(scenarioEngine, "scenarioEngine");
    }

    public enum Intent {
        CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, RELATIONS, DESCRIBE, NEIGHBORS, PATH,
        TIMELINE, FACTS, SIMILAR, VERIFY, WHY, WHY_NOT, RANK, ASSETS, ARTIFACT,
        MODELS, CALCULATE, SCENARIO, SOLVE_TARGET
    }

    public enum Direction { OUTGOING, INCOMING, BOTH }

    public enum Status {
        OK, SUPPORTED, REFUTED, UNKNOWN, NOT_FOUND, INVALID,
        PARTIAL, AMBIGUOUS, INFEASIBLE, FAILED
    }

    public record Query(
            Intent intent,
            String entityId,
            String targetId,
            Direction direction,
            List<String> relationTypes,
            Integer maxDepth,
            Integer topK,
            double[] queryEmbedding,
            HybridReasoner.Structural structural,
            String queryText,
            QuantitativeQuery quantitative) {

        public Query {
            relationTypes = relationTypes == null ? List.of() : List.copyOf(relationTypes);
            queryEmbedding = queryEmbedding == null ? null : queryEmbedding.clone();
        }

        /** Backward-compatible constructor retained for existing graph query callers. */
        public Query(
                Intent intent,
                String entityId,
                String targetId,
                Direction direction,
                List<String> relationTypes,
                Integer maxDepth,
                Integer topK,
                double[] queryEmbedding,
                HybridReasoner.Structural structural,
                String queryText) {
            this(intent, entityId, targetId, direction, relationTypes, maxDepth, topK,
                    queryEmbedding, structural, queryText, null);
        }

        @Override
        public double[] queryEmbedding() {
            return queryEmbedding == null ? null : queryEmbedding.clone();
        }

        public static Query capabilities() {
            return new Query(Intent.CAPABILITIES, null, null, null, List.of(), null, null, null, null, null);
        }

        public static Query overview() {
            return new Query(Intent.OVERVIEW, null, null, null, List.of(), null, null, null, null, null);
        }

        public static Query schema() {
            return new Query(Intent.SCHEMA, null, null, null, List.of(), null, null, null, null, null);
        }

        public static Query search(String queryText) {
            return new Query(Intent.SEARCH, null, null, null, List.of(), null, 10, null, null, queryText);
        }

        public static Query describe(String entityId) {
            return new Query(Intent.DESCRIBE, entityId, null, null, List.of(), null, null, null, null, null);
        }

        public static Query neighbors(String entityId) {
            return new Query(Intent.NEIGHBORS, entityId, null, Direction.BOTH, List.of(), null, 20, null, null, null);
        }

        public static Query path(String sourceId, String targetId) {
            return new Query(Intent.PATH, sourceId, targetId, Direction.OUTGOING, List.of(), 4, null, null, null, null);
        }

        public static Query relations(String entityId, String queryText) {
            return new Query(Intent.RELATIONS, entityId, null, Direction.BOTH, List.of(), null, 20,
                    null, null, queryText);
        }

        public static Query timeline(String entityId) {
            return new Query(Intent.TIMELINE, entityId, null, Direction.BOTH, List.of(), null, 50,
                    null, null, null);
        }

        public static Query facts(String queryText) {
            return new Query(Intent.FACTS, null, null, null, List.of(), null, 50,
                    null, null, queryText);
        }

        public static Query similar(String entityId) {
            return new Query(Intent.SIMILAR, entityId, null, null, List.of(), null, 10,
                    null, null, null);
        }

        public static Query assets(String entityId, String selector) {
            return new Query(Intent.ASSETS, entityId, null, null, List.of(), null, 20,
                    null, null, selector);
        }

        public static Query artifact(String name) {
            return new Query(Intent.ARTIFACT, null, null, null, List.of(), null, null,
                    null, null, name);
        }

        public static Query claim(Intent intent, String sourceId, String relationType, String targetId) {
            if (intent != Intent.VERIFY && intent != Intent.WHY && intent != Intent.WHY_NOT) {
                throw new IllegalArgumentException("Claim intent must be VERIFY, WHY, or WHY_NOT");
            }
            return new Query(intent, sourceId, targetId, Direction.OUTGOING,
                    List.of(relationType), null, null, null, null, null);
        }

        public static Query rank(int topK) {
            return new Query(Intent.RANK, null, null, null, List.of(), null, topK, null, null, null);
        }

        public static Query models(QuantitativeQuery quantitative) {
            return quantitative(Intent.MODELS, quantitative);
        }

        public static Query calculate(QuantitativeQuery quantitative) {
            return quantitative(Intent.CALCULATE, quantitative);
        }

        public static Query scenario(QuantitativeQuery quantitative) {
            return quantitative(Intent.SCENARIO, quantitative);
        }

        public static Query solveTarget(QuantitativeQuery quantitative) {
            return quantitative(Intent.SOLVE_TARGET, quantitative);
        }

        private static Query quantitative(Intent intent, QuantitativeQuery quantitative) {
            return new Query(intent, null, null, null, List.of(), null, null,
                    quantitative == null ? null : quantitative.queryEmbedding(),
                    null, null, quantitative);
        }
    }

    public record EntityView(
            String id,
            String label,
            String type,
            Set<String> typeMemberships,
            double score,
            Double structuralScore,
            Double semanticScore,
            double weight,
            double confidence,
            Set<String> tags,
            int embeddingDimension,
            String timestamp,
            String validFrom,
            String validUntil,
            Map<String, Object> attributes) {
    }

    public record RelationView(
            String id,
            String type,
            String sourceId,
            String sourceLabel,
            String targetId,
            String targetLabel,
            double weight,
            double confidence,
            boolean directed,
            Set<String> tags,
            int embeddingDimension,
            String timestamp,
            String validFrom,
            String validUntil,
            Map<String, Object> attributes) {
    }

    public record ResolutionView(
            String role,
            String input,
            String resolvedId,
            String resolvedLabel,
            double score,
            List<EntityView> candidates) {

        public ResolutionView {
            candidates = candidates == null ? List.of() : List.copyOf(candidates);
        }
    }

    public record PathStep(EntityView entity, RelationView via) {
    }

    public record Capability(
            String intent,
            String purpose,
            List<String> requiredFields,
            Map<String, Object> defaults) {
    }

    public record Result(
            Status status,
            Intent intent,
            String summary,
            List<EntityView> entities,
            List<RelationView> relations,
            List<PathStep> path,
            List<Capability> capabilities,
            List<String> guidance,
            Map<String, Object> data,
            List<ResolutionView> resolutions,
            ReasoningTrace trace) {

        public Result {
            entities = entities == null ? List.of() : List.copyOf(entities);
            relations = relations == null ? List.of() : List.copyOf(relations);
            path = path == null ? List.of() : List.copyOf(path);
            capabilities = capabilities == null ? List.of() : List.copyOf(capabilities);
            guidance = guidance == null ? List.of() : List.copyOf(guidance);
            data = data == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(data));
            resolutions = resolutions == null ? List.of() : List.copyOf(resolutions);
        }

        public Result(
                Status status,
                Intent intent,
                String summary,
                List<EntityView> entities,
                List<RelationView> relations,
                List<PathStep> path,
                List<Capability> capabilities,
                List<String> guidance) {
            this(status, intent, summary, entities, relations, path, capabilities, guidance,
                    Map.of(), List.of(), null);
        }
    }

    public Result query(ReasoningGraph graph, Query query) {
        Objects.requireNonNull(graph, "graph");
        if (query == null || query.intent() == null) {
            return traced(graph, query, query, List.of(),
                    invalid(null, "intent is required", "Call CAPABILITIES to inspect supported queries."));
        }
        ResolvedQuery resolved = resolveQuery(graph, query);
        Query effective = resolved.query();
        Result raw = switch (effective.intent()) {
            case CAPABILITIES -> capabilities();
            case OVERVIEW -> overview(graph);
            case SCHEMA -> schema(graph);
            case SEARCH -> search(graph, effective);
            case RELATIONS -> relations(graph, effective);
            case DESCRIBE -> describe(graph, effective);
            case NEIGHBORS -> neighbors(graph, effective);
            case PATH -> path(graph, effective);
            case TIMELINE -> timeline(graph, effective);
            case FACTS -> facts(graph, effective);
            case SIMILAR -> similar(graph, effective);
            case VERIFY, WHY, WHY_NOT -> claim(graph, effective);
            case RANK -> rank(graph, effective);
            case ASSETS -> assets(graph, effective);
            case ARTIFACT -> artifact(graph, effective);
            case MODELS, CALCULATE, SCENARIO, SOLVE_TARGET -> quantitative(graph, effective);
        };
        return traced(graph, query, effective, resolved.resolutions(), raw);
    }

    /**
     * Immutable model/transport-facing contract for every executable graph query intent.
     *
     * <p>Adapters must derive operation names and required-field guidance from this source rather
     * than maintaining a second list that can drift from engine execution.</p>
     */
    public static List<Capability> capabilityContract() {
        List<Capability> values = List.of(
                new Capability("CAPABILITIES", "List this query contract.", List.of(), Map.of()),
                new Capability("OVERVIEW", "Summarize graph size, coverage, metadata, and available analysis assets.",
                        List.of(), Map.of()),
                new Capability("SCHEMA", "Enumerate entity types, relation types, tags, and attribute keys.",
                        List.of(), Map.of()),
                new Capability("SEARCH", "Resolve names or phrases to entity ids.",
                        List.of("queryText"), Map.of("topK", 10)),
                new Capability("RELATIONS", "Search and rank relations by type, endpoint, text, or metadata.",
                        List.of(), Map.of("topK", 20)),
                new Capability("DESCRIBE", "Inspect one entity and its incident evidence.",
                        List.of("entityId"), Map.of()),
                new Capability("NEIGHBORS", "List directly related entities and relations.",
                        List.of("entityId"), Map.of("direction", "BOTH", "topK", 20)),
                new Capability("PATH", "Find the shortest evidence path between two entities.",
                        List.of("entityId", "targetId"),
                        Map.of("direction", "OUTGOING", "maxDepth", 4)),
                new Capability("TIMELINE", "Return timestamped graph events in chronological order.",
                        List.of(), Map.of("topK", 50)),
                new Capability("FACTS", "Query the graph's first-order entity-type and relation fact projection.",
                        List.of(), Map.of("topK", 50)),
                new Capability("SIMILAR", "Rank entities using stored embeddings with structural fallback after resolving the source entity.",
                        List.of("entityId"), Map.of("topK", 10)),
                new Capability("VERIFY", "Check whether a typed relation claim is supported, refuted, or unknown.",
                        List.of("entityId", "targetId", "relationTypes[0]"), Map.of()),
                new Capability("WHY", "Return evidence supporting or refuting a typed relation claim.",
                        List.of("entityId", "targetId", "relationTypes[0]"), Map.of()),
                new Capability("WHY_NOT", "Explain why a typed relation claim is not supported.",
                        List.of("entityId", "targetId", "relationTypes[0]"), Map.of()),
                new Capability("RANK", "Rank important entities with hybrid PSL/Bayesian structure and an optional query embedding.",
                        List.of(), Map.of("structural", "PSL", "topK", 10)),
                new Capability("ASSETS", "Inspect vectors, opinions, weight maps, metadata, and artifacts.",
                        List.of(), Map.of()),
                new Capability("ARTIFACT", "Read a named UTF-8 graph artifact.",
                        List.of("queryText"), Map.of()),
                new Capability("MODELS", "Retrieve ranked executable models and dependency gaps.",
                        List.of("quantitative.target"), Map.of("topK", 10)),
                new Capability("CALCULATE", "Evaluate a graph-derived quantitative target.",
                        List.of("quantitative.target"), Map.of()),
                new Capability("SCENARIO", "Apply immutable interventions and compare with baseline.",
                        List.of("quantitative.target", "quantitative.interventions"), Map.of()),
                new Capability("SOLVE_TARGET", "Goal-seek one bounded graph-resident control.",
                        List.of("quantitative.target", "quantitative.goal"), Map.of()));
        return values;
    }

    private Result capabilities() {
        return new Result(Status.OK, Intent.CAPABILITIES,
                "Supports complete read access plus ranked executable-model retrieval, deterministic "
                        + "calculation, immutable scenarios, and bounded goal seeking.",
                List.of(), List.of(), List.of(), capabilityContract(),
                List.of("Entity fields accept either exact ids or names/phrases; resolutions are ranked and traced."));
    }

    private Result overview(ReasoningGraph graph) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityCount", graph.entityCount());
        data.put("relationCount", graph.relationCount());
        data.put("directedRelations", graph.relations().stream().filter(GraphRelation::directed).count());
        data.put("undirectedRelations", graph.relations().stream().filter(r -> !r.directed()).count());
        data.put("timestampedEntities", graph.entities().stream().filter(e -> e.timestamp() != null).count());
        data.put("timestampedRelations", graph.relations().stream().filter(r -> r.timestamp() != null).count());
        data.put("primaryEmbeddedEntities", graph.entities().stream().filter(GraphEntity::hasEmbedding).count());
        data.put("embeddedRelations", graph.relations().stream().filter(GraphRelation::hasEmbedding).count());
        if (graph instanceof UnifiedGraph unified) {
            data.put("graphId", unified.graphId());
            data.put("factSheetId", unified.factSheetId());
            data.put("ontologyTypeCount", unified.types().size());
            data.put("vectorLayerCount", unified.vectorLayers().size());
            data.put("entityOpinionCount", unified.entityOpinions().size());
            data.put("relationOpinionCount", unified.relationOpinions().size());
            data.put("weightMapCount", unified.weightMaps().size());
            data.put("artifactCount", unified.artifacts().size());
            data.put("metadata", unified.meta());
        }
        return dataResult(Intent.OVERVIEW, "Graph overview contains " + graph.entityCount()
                + " entities and " + graph.relationCount() + " relations.", data);
    }

    private Result schema(ReasoningGraph graph) {
        Map<String, Integer> entityTypes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> relationTypes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> entityTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> relationTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> entityAttributes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> relationAttributes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        for (GraphEntity entity : graph.entities()) {
            for (String type : entity.typeMemberships()) {
                entityTypes.merge(type, 1, Integer::sum);
            }
            entityTags.addAll(entity.tags());
            entityAttributes.addAll(entity.attributes().keySet());
        }
        for (GraphRelation relation : graph.relations()) {
            relationTypes.merge(relation.type(), 1, Integer::sum);
            relationTags.addAll(relation.tags());
            relationAttributes.addAll(relation.attributes().keySet());
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityTypes", entityTypes);
        data.put("relationTypes", relationTypes);
        data.put("entityTags", entityTags);
        data.put("relationTags", relationTags);
        data.put("entityAttributeKeys", entityAttributes);
        data.put("relationAttributeKeys", relationAttributes);
        return dataResult(Intent.SCHEMA, "Found " + entityTypes.size() + " entity type(s) and "
                + relationTypes.size() + " relation type(s).", data);
    }

    private Result relations(ReasoningGraph graph, Query query) {
        java.util.stream.Stream<GraphRelation> stream = blank(query.entityId())
                ? graph.relations().stream()
                : graph.relationsOf(query.entityId()).stream();
        Set<String> types = query.relationTypes().stream()
                .filter(type -> !blank(type))
                .map(GraphQueryEngine::normalizePredicate)
                .collect(java.util.stream.Collectors.toSet());
        String text = blank(query.queryText()) ? null : query.queryText().toLowerCase(Locale.ROOT);
        List<GraphRelation> matches = stream
                .filter(relation -> types.isEmpty()
                        || types.contains(normalizePredicate(relation.type())))
                .filter(relation -> text == null || relationSearchText(graph, relation).contains(text))
                .sorted(Comparator.comparingDouble(
                                (GraphRelation relation) -> relation.weight() * relation.confidence()).reversed()
                        .thenComparing(GraphRelation::id))
                .limit(bounded(query.topK(), 20, 200))
                .toList();
        return new Result(Status.OK, Intent.RELATIONS,
                "Found " + matches.size() + " ranked relation(s).",
                List.of(), matches.stream().map(r -> relationView(graph, r)).toList(),
                List.of(), List.of(), List.of());
    }

    private Result timeline(ReasoningGraph graph, Query query) {
        java.util.stream.Stream<GraphRelation> stream = blank(query.entityId())
                ? graph.relations().stream()
                : graph.relationsOf(query.entityId()).stream();
        Set<String> types = query.relationTypes().stream()
                .filter(type -> !blank(type))
                .map(GraphQueryEngine::normalizePredicate)
                .collect(java.util.stream.Collectors.toSet());
        List<GraphRelation> events = stream
                .filter(relation -> relation.timestamp() != null)
                .filter(relation -> types.isEmpty()
                        || types.contains(normalizePredicate(relation.type())))
                .sorted(Comparator.comparing(GraphRelation::timestamp)
                        .thenComparing(GraphRelation::id))
                .limit(bounded(query.topK(), 50, 500))
                .toList();
        return new Result(Status.OK, Intent.TIMELINE,
                "Found " + events.size() + " timestamped relation event(s) in chronological order.",
                List.of(), events.stream().map(r -> relationView(graph, r)).toList(),
                List.of(), List.of(),
                events.isEmpty() ? List.of("The selected graph scope has no relation timestamps.") : List.of());
    }

    private Result facts(ReasoningGraph graph, Query query) {
        String filter = blank(query.queryText()) ? null : query.queryText().toLowerCase(Locale.ROOT);
        String entityScope = blank(query.entityId()) ? null : query.entityId();
        Set<String> relationTypes = query.relationTypes().stream()
                .filter(type -> !blank(type))
                .map(GraphQueryEngine::normalizePredicate)
                .collect(java.util.stream.Collectors.toSet());
        List<Map<String, Object>> facts = new ArrayList<>();
        for (GraphEntity entity : graph.entities()) {
            if (entityScope != null && !entity.id().equals(entityScope)) {
                continue;
            }
            for (String type : entity.typeMemberships()) {
                String atom = type + "(" + entity.id() + ")";
                if (filter == null || atom.toLowerCase(Locale.ROOT).contains(filter)) {
                    facts.add(Map.of("atom", atom, "kind", "entity_type",
                            "confidence", entity.confidence(), "source", entity.id()));
                }
            }
        }
        for (GraphRelation relation : graph.relations()) {
            if (entityScope != null && !relation.sourceId().equals(entityScope)
                    && !relation.targetId().equals(entityScope)) {
                continue;
            }
            if (!relationTypes.isEmpty()
                    && !relationTypes.contains(normalizePredicate(relation.type()))) {
                continue;
            }
            String atom = normalizePredicate(relation.type()) + "("
                    + relation.sourceId() + "," + relation.targetId() + ")";
            if (filter == null || atom.toLowerCase(Locale.ROOT).contains(filter)
                    || relationSearchText(graph, relation).contains(filter)) {
                facts.add(Map.of("atom", atom, "kind", "relation",
                        "confidence", relation.confidence(), "source", relation.id()));
            }
        }
        facts.sort(Comparator
                .comparingDouble((Map<String, Object> fact) -> ((Number) fact.get("confidence")).doubleValue())
                .reversed()
                .thenComparing(fact -> String.valueOf(fact.get("atom"))));
        int limit = bounded(query.topK(), 50, 1000);
        if (facts.size() > limit) {
            facts = new ArrayList<>(facts.subList(0, limit));
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("facts", facts);
        if (entityScope != null) {
            data.put("scopeEntityId", entityScope);
        }
        return dataResult(Intent.FACTS, "Returned " + facts.size() + " ranked graph fact(s).", data);
    }

    private Result similar(ReasoningGraph graph, Query query) {
        if (blank(query.entityId())) {
            return invalid(Intent.SIMILAR, "entityId or entity phrase is required",
                    "Use SEARCH or pass a human-readable entity name.");
        }
        GraphEntity source = graph.entity(query.entityId()).orElse(null);
        if (source == null) {
            return notFound(Intent.SIMILAR, query.entityId());
        }
        ReasoningGraph scoringGraph = graph;
        double[] vector = query.queryEmbedding();
        String layerName = "primary";
        String vectorOrigin = vector != null ? "QUERY" : "NONE";
        int vectorSupport = 0;
        int vectorHops = 0;
        if (graph instanceof UnifiedGraph unified && !blank(query.queryText())) {
            VectorLayer layer = unified.vectorLayer(query.queryText().trim());
            if (layer != null && layer.target() == VectorLayer.Target.ENTITY && layer.contains(source.id())) {
                scoringGraph = unified.withEmbeddingLayer(layer.name());
                vector = layer.get(source.id());
                layerName = layer.name();
                vectorOrigin = "VECTOR_LAYER";
                vectorSupport = 1;
            }
        }
        if (vector == null) {
            GraphEmbeddingResolver.Resolved resolved = GraphEmbeddingResolver.resolve(
                    scoringGraph, source.id(), 2);
            if (resolved.present()) {
                vector = resolved.vector();
                vectorOrigin = resolved.origin().name();
                vectorSupport = resolved.supportCount();
                vectorHops = resolved.hops();
                if (resolved.origin() == GraphEmbeddingResolver.Origin.NEIGHBORHOOD) {
                    layerName = "graph-resolved";
                }
            }
        }
        HybridReasoner.Structural structural = query.structural() == null
                ? HybridReasoner.Structural.PSL : query.structural();
        List<HybridReasoner.ScoredEntity> ranked = new HybridReasoner()
                .structural(structural)
                .semanticResolutionHops(2)
                .rank(scoringGraph, vector);
        int limit = bounded(query.topK(), 10, 100);
        List<EntityView> entities = ranked.stream()
                .filter(score -> !score.entityId().equals(source.id()))
                .limit(limit)
                .map(score -> graph.entity(score.entityId())
                        .map(entity -> entityView(entity, score.score(),
                                score.structuralScore(), score.semanticScore()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceId", source.id());
        data.put("embeddingLayer", layerName);
        data.put("semanticVectorAvailable", vector != null);
        data.put("semanticVectorOrigin", vectorOrigin);
        data.put("semanticVectorSupport", vectorSupport);
        data.put("semanticVectorHops", vectorHops);
        data.put("structuralEngine", structural.name());
        String basis = vector == null
                ? structural + " structure only"
                : layerName + " embeddings and " + structural + " structure";
        return new Result(Status.OK, Intent.SIMILAR,
                "Ranked " + entities.size() + " entities similar to " + source.label()
                        + " using " + basis + ".",
                entities, List.of(), List.of(), List.of(),
                vector == null ? List.of("No source vector was available; ranking is structural only.") : List.of(),
                data, List.of(), null);
    }

    private Result assets(ReasoningGraph graph, Query query) {
        if (!(graph instanceof UnifiedGraph unified)) {
            return dataResult(Intent.ASSETS, "This ReasoningGraph has no UnifiedGraph analysis assets.",
                    Map.of("unifiedGraph", false));
        }
        int limit = bounded(query.topK(), 20, 200);
        List<Map<String, Object>> layers = unified.vectorLayers().values().stream()
                .map(layer -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("name", layer.name());
                    row.put("target", layer.target().name());
                    row.put("dimension", layer.dim());
                    row.put("dtype", layer.dtype().name());
                    row.put("rowCount", layer.size());
                    row.put("sampleIds", layer.ids().stream().limit(limit).toList());
                    return row;
                })
                .toList();
        List<Map<String, Object>> weightMaps = unified.weightMaps().entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "name", entry.getKey(),
                        "entryCount", entry.getValue().size(),
                        "sampleKeys", entry.getValue().keySet().stream().limit(limit).toList()))
                .toList();
        List<Map<String, Object>> artifacts = unified.artifacts().entrySet().stream()
                .map(entry -> Map.<String, Object>of(
                        "name", entry.getKey(), "bytes", entry.getValue().length))
                .toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unifiedGraph", true);
        data.put("graphId", unified.graphId());
        data.put("factSheetId", unified.factSheetId());
        data.put("metadata", unified.meta());
        data.put("vectorLayers", layers);
        data.put("entityOpinions", opinionSummary(unified.entityOpinions(), limit));
        data.put("relationOpinions", opinionSummary(unified.relationOpinions(), limit));
        data.put("weightMaps", weightMaps);
        data.put("artifacts", artifacts);

        if (!blank(query.entityId())) {
            GraphEntity entity = graph.entity(query.entityId()).orElse(null);
            if (entity != null) {
                Map<String, Object> selected = new LinkedHashMap<>();
                selected.put("entity", entityView(entity, entity.weight(), null, null));
                selected.put("primaryEmbedding", entity.embedding());
                selected.put("opinion", opinionMap(unified.entityOpinion(entity.id())));
                selected.put("vectors", vectorsFor(unified, VectorLayer.Target.ENTITY, entity.id()));
                selected.put("weights", weightsFor(unified, entity.id()));
                data.put("selectedEntity", selected);
            }
        }

        if (!blank(query.queryText())) {
            String selector = query.queryText().trim();
            VectorLayer selectedLayer = unified.vectorLayers().values().stream()
                    .filter(layer -> layer.name().equalsIgnoreCase(selector))
                    .findFirst().orElse(null);
            Map<String, Double> selectedWeights = unified.weightMaps().entrySet().stream()
                    .filter(entry -> entry.getKey().equalsIgnoreCase(selector))
                    .map(Map.Entry::getValue).findFirst().orElse(null);
            GraphRelation selectedRelation = graph.relations().stream()
                    .filter(relation -> relation.id().equalsIgnoreCase(selector))
                    .findFirst().orElse(null);
            boolean selectorMatched = false;
            if (selectedLayer != null) {
                Map<String, Object> selected = new LinkedHashMap<>();
                selected.put("name", selectedLayer.name());
                selected.put("target", selectedLayer.target().name());
                selected.put("dimension", selectedLayer.dim());
                selected.put("dtype", selectedLayer.dtype().name());
                selected.put("rowCount", selectedLayer.size());
                selected.put("rows", selectedLayer.rows().entrySet().stream().limit(limit)
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue,
                                (left, right) -> left, LinkedHashMap::new)));
                selected.put("truncated", selectedLayer.size() > limit);
                data.put("selectedVectorLayer", selected);
                selectorMatched = true;
            }
            if (selectedWeights != null) {
                Map<String, Double> values = selectedWeights.entrySet().stream().limit(limit)
                        .collect(java.util.stream.Collectors.toMap(
                                Map.Entry::getKey, Map.Entry::getValue,
                                (left, right) -> left, LinkedHashMap::new));
                data.put("selectedWeightMap", Map.of(
                        "name", selector, "entryCount", selectedWeights.size(),
                        "values", values, "truncated", selectedWeights.size() > limit));
                selectorMatched = true;
            }
            if (selectedRelation != null) {
                Map<String, Object> selected = new LinkedHashMap<>();
                selected.put("relation", relationView(graph, selectedRelation));
                selected.put("primaryEmbedding", selectedRelation.embedding());
                selected.put("opinion", opinionMap(unified.relationOpinion(selectedRelation.id())));
                selected.put("vectors", vectorsFor(unified, VectorLayer.Target.RELATION, selectedRelation.id()));
                selected.put("weights", weightsFor(unified, selectedRelation.id()));
                data.put("selectedRelation", selected);
                selectorMatched = true;
            }
            Map<String, double[]> globalVectors = vectorsFor(
                    unified, VectorLayer.Target.GLOBAL, selector);
            if (!globalVectors.isEmpty()) {
                data.put("selectedGlobalVectors", globalVectors);
                selectorMatched = true;
            }
            Map<String, Double> selectedKeyWeights = weightsFor(unified, selector);
            if (!selectedKeyWeights.isEmpty()) {
                data.put("selectedKeyWeights", selectedKeyWeights);
                selectorMatched = true;
            }
            if (!selectorMatched) {
                data.put("selectorMatched", false);
            }
        }
        return dataResult(Intent.ASSETS,
                "Found " + layers.size() + " vector layer(s), "
                        + weightMaps.size() + " weight map(s), and "
                        + artifacts.size() + " artifact(s).", data);
    }

    private Result artifact(ReasoningGraph graph, Query query) {
        if (blank(query.queryText())) {
            return invalid(Intent.ARTIFACT, "queryText must name an artifact",
                    "Call ASSETS to list available artifact names.");
        }
        if (!(graph instanceof UnifiedGraph unified)) {
            return invalid(Intent.ARTIFACT, "Artifacts require a UnifiedGraph",
                    "Call OVERVIEW to inspect the graph implementation.");
        }
        byte[] bytes = unified.artifact(query.queryText().trim());
        if (bytes == null) {
            return new Result(Status.NOT_FOUND, Intent.ARTIFACT,
                    "Artifact not found: " + query.queryText(), List.of(), List.of(), List.of(),
                    List.of(), List.of("Call ASSETS to list artifact names."));
        }
        String text = new String(bytes, StandardCharsets.UTF_8);
        int maxChars = 20_000;
        boolean truncated = text.length() > maxChars;
        if (truncated) {
            text = text.substring(0, maxChars);
        }
        return dataResult(Intent.ARTIFACT, "Read artifact " + query.queryText()
                + " (" + bytes.length + " bytes).",
                Map.of("name", query.queryText().trim(), "bytes", bytes.length,
                        "text", text, "truncated", truncated));
    }

    private Result search(ReasoningGraph graph, Query query) {
        if (blank(query.queryText())) {
            return invalid(Intent.SEARCH, "queryText is required",
                    "Use a name, type, label, tag, or metadata phrase.");
        }
        int limit = bounded(query.topK(), 10, 100);
        List<EntityView> matches = rankResolutionCandidates(graph, query.queryText(), limit);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("scoreBasis", "lexical + stored entity prior");
        data.put("storedPrior", "clamp01(weight * confidence)");
        data.put("inferenceInvoked", false);
        return new Result(Status.OK, Intent.SEARCH,
                "Found " + matches.size() + " matching entity(s) for '" + query.queryText()
                        + "' using retrieval-only scoring (no graph inference).",
                matches, List.of(), List.of(), List.of(),
                matches.isEmpty()
                        ? List.of("Try fewer or broader terms, then use the returned id with DESCRIBE.",
                        "SEARCH scores use lexical matching plus stored entity weight*confidence; no PSL/Bayesian inference is invoked.")
                        : List.of("Use a returned id with DESCRIBE, NEIGHBORS, or PATH.",
                        "SEARCH scores use lexical matching plus stored entity weight*confidence; no PSL/Bayesian inference is invoked."),
                data, List.of(), null);
    }

    private Result describe(ReasoningGraph graph, Query query) {
        if (blank(query.entityId())) {
            return invalid(Intent.DESCRIBE, "entityId is required", "Select an entity id from RANK or NEIGHBORS.");
        }
        GraphEntity entity = graph.entity(query.entityId()).orElse(null);
        if (entity == null) {
            return notFound(Intent.DESCRIBE, query.entityId());
        }
        List<GraphRelation> incident = filteredRelations(graph, entity.id(), Direction.BOTH, query.relationTypes());
        List<RelationView> evidence = incident.stream().map(r -> relationView(graph, r)).toList();
        return new Result(Status.OK, Intent.DESCRIBE,
                entity.label() + " has " + evidence.size() + " matching incident relation(s).",
                List.of(entityView(entity, entity.weight(), null, null)), evidence,
                List.of(), List.of(), List.of());
    }

    private Result neighbors(ReasoningGraph graph, Query query) {
        if (blank(query.entityId())) {
            return invalid(Intent.NEIGHBORS, "entityId is required", "Select an entity id from RANK.");
        }
        if (!graph.containsEntity(query.entityId())) {
            return notFound(Intent.NEIGHBORS, query.entityId());
        }
        Direction direction = query.direction() == null ? Direction.BOTH : query.direction();
        int limit = bounded(query.topK(), 20, 100);
        List<GraphRelation> evidence = filteredRelations(graph, query.entityId(), direction, query.relationTypes());
        if (evidence.size() > limit) {
            evidence = evidence.subList(0, limit);
        }
        Set<String> ids = new LinkedHashSet<>();
        for (GraphRelation relation : evidence) {
            ids.add(relation.sourceId().equals(query.entityId()) ? relation.targetId() : relation.sourceId());
        }
        List<EntityView> entities = ids.stream()
                .map(graph::entity)
                .flatMap(Optional::stream)
                .map(e -> entityView(e, e.weight(), null, null))
                .toList();
        return new Result(Status.OK, Intent.NEIGHBORS,
                "Found " + entities.size() + " neighbor(s) through " + evidence.size() + " relation(s).",
                entities, evidence.stream().map(r -> relationView(graph, r)).toList(),
                List.of(), List.of(), List.of());
    }

    private Result path(ReasoningGraph graph, Query query) {
        if (blank(query.entityId()) || blank(query.targetId())) {
            return invalid(Intent.PATH, "entityId and targetId are required",
                    "Use entityId as the source and targetId as the destination.");
        }
        if (!graph.containsEntity(query.entityId())) {
            return notFound(Intent.PATH, query.entityId());
        }
        if (!graph.containsEntity(query.targetId())) {
            return notFound(Intent.PATH, query.targetId());
        }
        Direction direction = query.direction() == null ? Direction.OUTGOING : query.direction();
        int maxDepth = bounded(query.maxDepth(), 4, 12);
        Map<String, Link> parent = new HashMap<>();
        Set<String> seen = new HashSet<>();
        ArrayDeque<NodeDepth> queue = new ArrayDeque<>();
        seen.add(query.entityId());
        queue.add(new NodeDepth(query.entityId(), 0));
        while (!queue.isEmpty() && !seen.contains(query.targetId())) {
            NodeDepth current = queue.removeFirst();
            if (current.depth() >= maxDepth) {
                continue;
            }
            for (GraphRelation relation : filteredRelations(
                    graph, current.id(), direction, query.relationTypes())) {
                String next = other(current.id(), relation);
                if (next != null && graph.containsEntity(next) && seen.add(next)) {
                    parent.put(next, new Link(current.id(), relation));
                    queue.addLast(new NodeDepth(next, current.depth() + 1));
                }
            }
        }
        if (!seen.contains(query.targetId())) {
            return new Result(Status.NOT_FOUND, Intent.PATH,
                    "No matching path found within maxDepth=" + maxDepth + ".",
                    List.of(), List.of(), List.of(), List.of(),
                    List.of("Try direction=BOTH, increase maxDepth, or remove relationTypes."));
        }
        List<String> ids = new ArrayList<>();
        List<GraphRelation> relations = new ArrayList<>();
        String cursor = query.targetId();
        ids.add(cursor);
        while (!cursor.equals(query.entityId())) {
            Link link = parent.get(cursor);
            relations.add(link.relation());
            cursor = link.parentId();
            ids.add(cursor);
        }
        Collections.reverse(ids);
        Collections.reverse(relations);
        List<PathStep> steps = new ArrayList<>();
        for (int i = 0; i < ids.size(); i++) {
            GraphEntity entity = graph.entity(ids.get(i)).orElseThrow();
            steps.add(new PathStep(entityView(entity, entity.weight(), null, null),
                    i == 0 ? null : relationView(graph, relations.get(i - 1))));
        }
        return new Result(Status.OK, Intent.PATH,
                "Found a path with " + relations.size() + " relation(s).",
                List.of(), relations.stream().map(r -> relationView(graph, r)).toList(),
                steps, List.of(), List.of());
    }

    private Result claim(ReasoningGraph graph, Query query) {
        if (blank(query.entityId()) || blank(query.targetId()) || query.relationTypes().size() != 1
                || blank(query.relationTypes().get(0))) {
            return invalid(query.intent(),
                    "entityId, targetId, and exactly one relationTypes value are required",
                    "Resolve both ids with SEARCH, then provide the claimed relation type.");
        }
        if (!graph.containsEntity(query.entityId())) {
            return notFound(query.intent(), query.entityId());
        }
        if (!graph.containsEntity(query.targetId())) {
            return notFound(query.intent(), query.targetId());
        }

        String relationType = normalizePredicate(query.relationTypes().get(0));
        String atom = claimAtom(relationType, query.entityId(), query.targetId());
        // Claim verification needs facts whose subject is the claimed source only. Restricting the
        // adapter to outgoing adjacency avoids scanning/materializing a million-edge graph and lets
        // storage-backed ReasoningGraph implementations answer VERIFY/WHY with one indexed lookup.
        VerificationStores stores = graphFacts(graph.outgoing(query.entityId()));
        VerifyResult verdict = new DefaultKbVerifier(
                stores.inferred(), stores.observed()).verify(atom);

        List<GraphRelation> direct = claimRelations(
                graph, query.entityId(), query.targetId(), relationType);
        List<GraphRelation> counter = claimRelations(
                graph, query.entityId(), query.targetId(), "NOT_" + relationType);
        List<GraphRelation> evidence = new ArrayList<>(direct);
        evidence.addAll(counter);

        Status status = switch (verdict.status()) {
            case SUPPORTED -> Status.SUPPORTED;
            case REFUTED -> Status.REFUTED;
            case UNKNOWN -> Status.UNKNOWN;
        };
        String readableClaim = graph.entity(query.entityId()).map(GraphEntity::label).orElse(query.entityId())
                + " --" + relationType + "--> "
                + graph.entity(query.targetId()).map(GraphEntity::label).orElse(query.targetId());
        String summary = switch (query.intent()) {
            case VERIFY -> readableClaim + " is " + verdict.status()
                    + " at confidence " + String.format(Locale.ROOT, "%.3f", verdict.confidence()) + ".";
            case WHY -> verdict.status() == VerifyResult.Status.UNKNOWN
                    ? "No graph fact supports or refutes " + readableClaim + "."
                    : readableClaim + " is " + verdict.status() + " because of "
                            + evidence.size() + " matching graph relation(s).";
            case WHY_NOT -> verdict.status() == VerifyResult.Status.SUPPORTED
                    ? readableClaim + " is already supported."
                    : readableClaim + " is not supported because no matching positive relation is present."
                            + (counter.isEmpty() ? "" : " Explicit counter-evidence is present.");
            default -> throw new IllegalStateException("Unexpected claim intent: " + query.intent());
        };

        List<String> guidance = new ArrayList<>();
        guidance.addAll(verdict.nearMissSuggestions());
        List<RelationView> evidenceViews = new ArrayList<>(
                evidence.stream().map(r -> relationView(graph, r)).toList());
        List<PathStep> alternativePath = List.of();
        if (query.intent() == Intent.WHY_NOT && verdict.status() == VerifyResult.Status.UNKNOWN) {
            guidance.add("Missing graph fact: " + atom);
            Result alternative = path(graph, new Query(
                    Intent.PATH, query.entityId(), query.targetId(), Direction.BOTH,
                    List.of(), 3, null, null, null, null));
            if (alternative.status() == Status.OK) {
                alternativePath = alternative.path();
                Set<String> present = evidenceViews.stream()
                        .map(RelationView::id).collect(java.util.stream.Collectors.toSet());
                for (RelationView relation : alternative.relations()) {
                    if (present.add(relation.id())) {
                        evidenceViews.add(relation);
                    }
                }
                summary += " The entities are connected indirectly by "
                        + alternative.relations().size() + " relation(s), shown as alternative evidence.";
                guidance.add("Compare the alternative path's relation types and directions with the missing claim.");
            } else {
                guidance.add("Inspect NEIGHBORS for both entities; no short alternative path was found.");
            }
        }
        return new Result(status, query.intent(), summary, List.of(),
                evidenceViews, alternativePath, List.of(), guidance);
    }

    private Result quantitative(ReasoningGraph graph, Query query) {
        QuantitativeQuery supplied = query.quantitative();
        if (supplied == null || supplied.target() == null || !supplied.target().specified()) {
            return invalid(query.intent(),
                    "quantitative.target is required",
                    "Use MODELS with a target selector before calculating or running a scenario.");
        }

        QuantitativeQuery.Mode mode = switch (query.intent()) {
            case MODELS -> QuantitativeQuery.Mode.MODELS;
            case CALCULATE -> QuantitativeQuery.Mode.CALCULATE;
            case SCENARIO -> QuantitativeQuery.Mode.SCENARIO;
            case SOLVE_TARGET -> QuantitativeQuery.Mode.SOLVE_TARGET;
            default -> throw new IllegalStateException("Not a quantitative intent: " + query.intent());
        };
        QuantitativeQuery effective = new QuantitativeQuery(
                mode, supplied.target(), supplied.interventions(), supplied.dimensions(),
                supplied.asOf(), supplied.queryEmbedding(), supplied.topK(), supplied.goal());
        if (mode == QuantitativeQuery.Mode.SOLVE_TARGET && effective.goal() == null) {
            return invalid(query.intent(), "quantitative.goal is required for SOLVE_TARGET",
                    "Provide a bounded control selector, target value, minimum, and maximum.");
        }

        ModelRetrieval retrieval = modelRetriever.retrieve(graph, effective);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("retrieval", retrieval);
        data.put("candidates", retrieval.candidates());
        data.put("gaps", retrieval.gaps());
        if (retrieval.plan() != null) {
            data.put("plan", retrieval.plan());
        }

        if (mode == QuantitativeQuery.Mode.MODELS || !retrieval.executable()) {
            return new Result(status(retrieval.status()), query.intent(),
                    "Executable model retrieval " + retrieval.status() + " with "
                            + retrieval.candidates().size() + " ranked candidate(s) and "
                            + retrieval.gaps().size() + " gap(s).",
                    List.of(), List.of(), List.of(), List.of(),
                    retrieval.gaps().isEmpty()
                            ? List.of()
                            : List.of("Inspect gaps and candidate rejection reasons before execution."),
                    data, List.of(), retrieval.trace());
        }

        if (mode == QuantitativeQuery.Mode.SOLVE_TARGET) {
            QuantitativeScenarioEngine.GoalSeekResult goal =
                    scenarioEngine.solveTarget(graph, retrieval);
            data.put("goalSeek", goal);
            if (goal.scenario() != null) {
                data.put("scenario", goal.scenario());
            }
            String summary = "Goal seek " + goal.status() + " for "
                    + retrieval.plan().targetEntityId()
                    + (goal.controlValue() == null
                            ? "." : " at control value " + goal.controlValue() + ".");
            ReasoningTrace trace = combineQuantitativeTrace(
                    ReasoningTrace.StepKind.OPTIMIZATION, summary,
                    retrieval.trace(), goal.trace());
            return new Result(status(goal.status()), query.intent(), summary,
                    List.of(), List.of(), List.of(), List.of(), goal.warnings(),
                    data, List.of(), trace);
        }

        ScenarioResult scenario = scenarioEngine.execute(graph, retrieval);
        data.put("scenario", scenario);
        String summary = mode == QuantitativeQuery.Mode.CALCULATE
                ? "Calculated " + scenario.targetEntityId() + " = " + scenario.scenarioValue() + "."
                : "Scenario " + scenario.status() + " for " + scenario.targetEntityId()
                        + ": baseline=" + scenario.baselineValue()
                        + ", scenario=" + scenario.scenarioValue()
                        + ", delta=" + scenario.delta() + ".";
        ReasoningTrace trace = combineQuantitativeTrace(
                ReasoningTrace.StepKind.CALCULATION, summary,
                retrieval.trace(), scenario.trace());
        return new Result(status(scenario.status()), query.intent(), summary,
                List.of(), List.of(), List.of(), List.of(), scenario.warnings(),
                data, List.of(), trace);
    }

    private static ReasoningTrace combineQuantitativeTrace(
            ReasoningTrace.StepKind kind,
            String conclusion,
            ReasoningTrace... traces) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        double confidence = 1.0;
        for (ReasoningTrace trace : traces) {
            if (trace != null) {
                premises.add(trace.conclusion());
                confidence = Math.min(confidence, trace.conclusion().confidence());
            }
        }
        if (premises.isEmpty()) {
            confidence = 0.0;
        }
        return ReasoningTrace.of(ReasoningTrace.Step.derived(
                kind, conclusion, "quantitative_query_pipeline",
                confidence, null, Map.of("traceCount", String.valueOf(premises.size())), premises));
    }

    private static Status status(ModelRetrieval.Status status) {
        return switch (status) {
            case READY -> Status.OK;
            case PARTIAL -> Status.PARTIAL;
            case AMBIGUOUS -> Status.AMBIGUOUS;
            case NOT_FOUND -> Status.NOT_FOUND;
            case INVALID -> Status.INVALID;
        };
    }

    private static Status status(ScenarioResult.Status status) {
        return switch (status) {
            case COMPLETED -> Status.OK;
            case PARTIAL -> Status.PARTIAL;
            case FAILED -> Status.FAILED;
            case INFEASIBLE -> Status.INFEASIBLE;
        };
    }

    private static Status status(QuantitativeScenarioEngine.GoalSeekResult.Status status) {
        return switch (status) {
            case SOLVED -> Status.OK;
            case INFEASIBLE -> Status.INFEASIBLE;
            case FAILED -> Status.FAILED;
        };
    }

    private Result rank(ReasoningGraph graph, Query query) {
        int limit = bounded(query.topK(), 10, 100);
        HybridReasoner.Structural structural = query.structural() == null
                ? HybridReasoner.Structural.PSL : query.structural();
        List<HybridReasoner.ScoredEntity> ranked = new HybridReasoner()
                .structural(structural)
                .semanticResolutionHops(2)
                .rank(graph, query.queryEmbedding());
        List<EntityView> entities = ranked.stream()
                .limit(limit)
                .map(score -> graph.entity(score.entityId())
                        .map(entity -> entityView(entity, score.score(),
                                score.structuralScore(), score.semanticScore()))
                        .orElse(null))
                .filter(Objects::nonNull)
                .toList();
        String mode = query.queryEmbedding() == null ? "structural" : "hybrid structural+semantic";
        return new Result(Status.OK, Intent.RANK,
                "Ranked " + entities.size() + " entity(s) using " + structural + " " + mode + " reasoning.",
                entities, List.of(), List.of(), List.of(),
                query.queryEmbedding() == null
                        ? List.of("Provide queryEmbedding to include semantic similarity.")
                        : List.of());
    }

    private ResolvedQuery resolveQuery(ReasoningGraph graph, Query original) {
        if (original == null || original.intent() == null) {
            return new ResolvedQuery(original, List.of());
        }
        boolean resolveSource = switch (original.intent()) {
            case RELATIONS, DESCRIBE, NEIGHBORS, PATH, TIMELINE, FACTS, SIMILAR,
                    VERIFY, WHY, WHY_NOT, ASSETS -> true;
            default -> false;
        };
        boolean resolveTarget = switch (original.intent()) {
            case PATH, VERIFY, WHY, WHY_NOT -> true;
            default -> false;
        };
        String entityId = original.entityId();
        String targetId = original.targetId();
        List<ResolutionView> resolutions = new ArrayList<>();
        if (resolveSource && !blank(entityId)) {
            ResolutionView resolution = resolveEntity(graph, "entityId", entityId);
            resolutions.add(resolution);
            if (!blank(resolution.resolvedId())) {
                entityId = resolution.resolvedId();
            }
        }
        if (resolveTarget && !blank(targetId)) {
            ResolutionView resolution = resolveEntity(graph, "targetId", targetId);
            resolutions.add(resolution);
            if (!blank(resolution.resolvedId())) {
                targetId = resolution.resolvedId();
            }
        }
        List<String> relationTypes = original.relationTypes().stream()
                .map(type -> blank(type) ? type : normalizePredicate(type))
                .toList();
        Query effective = new Query(original.intent(), entityId, targetId, original.direction(),
                relationTypes, original.maxDepth(), original.topK(), original.queryEmbedding(),
                original.structural(), original.queryText(), original.quantitative());
        return new ResolvedQuery(effective, resolutions);
    }

    private ResolutionView resolveEntity(
            ReasoningGraph graph, String role, String input) {
        GraphEntity direct = entityById(graph, input);
        List<EntityView> candidates;
        if (direct != null) {
            candidates = List.of(entityView(direct, 1.0, null, null));
        } else {
            candidates = rankResolutionCandidates(graph, input, 5);
        }
        EntityView top = candidates.isEmpty() ? null : candidates.get(0);
        boolean ambiguous = direct == null && candidates.size() > 1
                && top.score() - candidates.get(1).score() < 0.03;
        EntityView selected = ambiguous ? null : top;
        return new ResolutionView(role, input,
                selected == null ? null : selected.id(),
                selected == null ? null : selected.label(),
                top == null ? 0.0 : top.score(), candidates);
    }

    private static GraphEntity entityById(ReasoningGraph graph, String input) {
        if (blank(input)) {
            return null;
        }
        String normalizedInput = input.trim();
        GraphEntity exact = graph.entity(normalizedInput).orElse(null);
        if (exact != null) {
            return exact;
        }
        for (GraphEntity entity : graph.entities()) {
            checkInterrupted();
            if (entity.id().equalsIgnoreCase(normalizedInput)) {
                return entity;
            }
        }
        return null;
    }

    private static List<EntityView> rankResolutionCandidates(
            ReasoningGraph graph,
            String queryText,
            int limit) {
        if (blank(queryText)) {
            return List.of();
        }
        List<EntityMatch> matches = new ArrayList<>();
        double maxLexical = 0.0;
        for (GraphEntity entity : graph.entities()) {
            checkInterrupted();
            double lexical = lexicalScore(entity, queryText);
            if (lexical <= 0.0) {
                continue;
            }
            int exactRank = entity.id().equalsIgnoreCase(queryText.trim()) ? 2
                    : entity.label().equalsIgnoreCase(queryText.trim()) ? 1 : 0;
            double storedPrior = storedEntityPrior(entity);
            matches.add(new EntityMatch(entity, lexical, storedPrior, exactRank, 0.0));
            maxLexical = Math.max(maxLexical, lexical);
        }
        List<EntityMatch> scored = new ArrayList<>(matches.size());
        for (EntityMatch match : matches) {
            checkInterrupted();
            double lexical = maxLexical == 0.0 ? 0.0 : match.lexical() / maxLexical;
            double score = clamp01(0.85 * lexical + 0.15 * match.storedPrior());
            if (match.exactRank() == 2) {
                score = 1.0;
            } else if (match.exactRank() == 1) {
                score = clamp01(0.98 + 0.02 * match.storedPrior());
            }
            scored.add(new EntityMatch(match.entity(), match.lexical(), match.storedPrior(),
                    match.exactRank(), score));
        }
        checkInterrupted();
        scored.sort((left, right) -> {
            int exact = Integer.compare(right.exactRank(), left.exactRank());
            if (exact != 0) return exact;
            int score = Double.compare(right.score(), left.score());
            if (score != 0) return score;
            int lexical = Double.compare(right.lexical(), left.lexical());
            if (lexical != 0) return lexical;
            return left.entity().id().compareTo(right.entity().id());
        });
        checkInterrupted();
        return scored.stream().limit(limit)
                .map(match -> entityView(match.entity(), match.score(), null, null))
                .toList();
    }

    private Result traced(
            ReasoningGraph graph,
            Query original,
            Query effective,
            List<ResolutionView> resolutions,
            Result raw) {
        List<ReasoningTrace.Step> premises = new ArrayList<>();
        for (ResolutionView resolution : resolutions) {
            Map<String, String> meta = new LinkedHashMap<>();
            meta.put("role", resolution.role());
            meta.put("input", resolution.input());
            meta.put("resolvedId", String.valueOf(resolution.resolvedId()));
            meta.put("candidateIds", resolution.candidates().stream()
                    .map(EntityView::id).collect(java.util.stream.Collectors.joining(",")));
            meta.put("scoreBasis", "lexical + stored entity prior; exact id/name priority");
            meta.put("inferenceInvoked", "false");
            premises.add(new ReasoningTrace.Step(
                    ReasoningTrace.StepKind.QUERY,
                    resolution.resolvedId() == null
                            ? "Unresolved " + resolution.role() + ": " + resolution.input()
                            : resolution.role() + " " + resolution.input() + " -> " + resolution.resolvedId(),
                    "automatic_entity_resolution", clamp01(resolution.score()), "graph",
                    List.of(), null, meta));
        }

        Set<String> tracedEntities = new HashSet<>();
        for (EntityView entity : raw.entities()) {
            premises.add(entityTraceStep(graph, entity, raw.intent()));
            tracedEntities.add(entity.id());
        }
        for (PathStep step : raw.path()) {
            if (step.entity() != null && tracedEntities.add(step.entity().id())) {
                premises.add(entityTraceStep(graph, step.entity(), Intent.PATH));
            }
        }
        for (RelationView relation : raw.relations()) {
            Opinion opinion = graph instanceof UnifiedGraph unified
                    ? unified.relationOpinion(relation.id()) : null;
            String atom = normalizePredicate(relation.type()) + "("
                    + relation.sourceId() + "," + relation.targetId() + ")";
            premises.add(ReasoningTrace.Step.fact(atom,
                    clamp01(Math.min(relation.weight(), relation.confidence())),
                    relation.id(), opinion));
        }

        Object factRows = raw.data().get("facts");
        if (factRows instanceof Iterable<?> rows) {
            for (Object value : rows) {
                if (!(value instanceof Map<?, ?> row) || row.get("atom") == null) {
                    continue;
                }
                Object confidence = row.get("confidence");
                double score = confidence instanceof Number number ? number.doubleValue() : 1.0;
                premises.add(ReasoningTrace.Step.fact(String.valueOf(row.get("atom")),
                        clamp01(score), String.valueOf(row.get("source"))));
            }
        } else if (!raw.data().isEmpty()) {
            premises.add(ReasoningTrace.Step.fact(
                    "Returned data fields: " + String.join(",", raw.data().keySet()),
                    raw.status() == Status.OK ? 1.0 : 0.0, "graph-query-data"));
        }

        Intent intent = raw.intent() != null ? raw.intent()
                : effective != null ? effective.intent()
                : original != null ? original.intent() : null;
        if (raw.trace() != null) {
            premises.add(raw.trace().conclusion());
        }

        ReasoningTrace.StepKind rootKind = switch (intent == null ? Intent.CAPABILITIES : intent) {
            case RANK, SIMILAR, VERIFY, WHY, WHY_NOT -> ReasoningTrace.StepKind.INFERENCE;
            case MODELS -> ReasoningTrace.StepKind.QUERY;
            case CALCULATE, SCENARIO -> ReasoningTrace.StepKind.CALCULATION;
            case SOLVE_TARGET -> ReasoningTrace.StepKind.OPTIMIZATION;
            default -> ReasoningTrace.StepKind.QUERY;
        };
        Map<String, String> meta = new LinkedHashMap<>();
        meta.put("status", raw.status().name());
        meta.put("intent", intent == null ? "UNKNOWN" : intent.name());
        meta.put("entityCount", String.valueOf(raw.entities().size()));
        meta.put("relationCount", String.valueOf(raw.relations().size()));
        meta.put("pathLength", String.valueOf(raw.path().size()));
        meta.put("resolutionCount", String.valueOf(resolutions.size()));
        if (original != null && !blank(original.entityId())) {
            meta.put("requestedEntity", original.entityId());
        }
        if (effective != null && !blank(effective.entityId())) {
            meta.put("effectiveEntity", effective.entityId());
        }
        if (original != null && !blank(original.targetId())) {
            meta.put("requestedTarget", original.targetId());
        }
        if (effective != null && !blank(effective.targetId())) {
            meta.put("effectiveTarget", effective.targetId());
        }
        double confidence = resultConfidence(raw);
        ReasoningTrace.Step root = ReasoningTrace.Step.derived(
                rootKind,
                raw.summary() == null ? raw.status().name() : raw.summary(),
                intent == null ? "graph_query" : "graph_query:" + intent.name().toLowerCase(Locale.ROOT),
                confidence, null, meta, premises);
        return new Result(raw.status(), raw.intent(), raw.summary(), raw.entities(), raw.relations(),
                raw.path(), raw.capabilities(), raw.guidance(), raw.data(), resolutions,
                ReasoningTrace.of(root));
    }

    private static ReasoningTrace.Step entityTraceStep(
            ReasoningGraph graph, EntityView entity, Intent intent) {
        boolean inferred = intent == Intent.RANK || intent == Intent.SIMILAR;
        Opinion opinion = graph instanceof UnifiedGraph unified
                ? unified.entityOpinion(entity.id()) : null;
        double confidence = entity.score() > 0.0 ? entity.score() : entity.confidence();
        String source = inferred ? "hybrid_rank"
                : intent == Intent.SEARCH ? "lexical_entity_search" : "graph_lookup";
        Map<String, String> metadata = new LinkedHashMap<>();
        metadata.put("type", entity.type());
        if (intent == Intent.SEARCH) {
            metadata.put("scoreBasis", "lexical + stored entity prior");
            metadata.put("inferenceInvoked", "false");
        }
        return new ReasoningTrace.Step(
                inferred ? ReasoningTrace.StepKind.INFERENCE : ReasoningTrace.StepKind.QUERY,
                "entity " + entity.id() + " (" + entity.label() + ")",
                source, clamp01(confidence), entity.id(), List.of(), opinion, metadata);
    }

    private static double resultConfidence(Result result) {
        if (result.status() == Status.INVALID || result.status() == Status.NOT_FOUND
                || result.status() == Status.UNKNOWN || result.status() == Status.FAILED
                || result.status() == Status.INFEASIBLE) {
            return 0.0;
        }
        if (result.trace() != null) {
            return clamp01(result.trace().conclusion().confidence());
        }
        if (!result.entities().isEmpty()) {
            EntityView entity = result.entities().get(0);
            return clamp01(entity.score() > 0.0 ? entity.score() : entity.confidence());
        }
        if (!result.relations().isEmpty()) {
            return result.relations().stream()
                    .mapToDouble(relation -> Math.min(relation.weight(), relation.confidence()))
                    .map(GraphQueryEngine::clamp01).max().orElse(1.0);
        }
        return 1.0;
    }

    private static Result dataResult(Intent intent, String summary, Map<String, Object> data) {
        return new Result(Status.OK, intent, summary, List.of(), List.of(), List.of(),
                List.of(), List.of(), data, List.of(), null);
    }

    private static String relationSearchText(ReasoningGraph graph, GraphRelation relation) {
        String source = graph.entity(relation.sourceId()).map(GraphEntity::label).orElse("");
        String target = graph.entity(relation.targetId()).map(GraphEntity::label).orElse("");
        return String.join(" ", relation.id(), relation.type(), normalizePredicate(relation.type()),
                relation.sourceId(), source, relation.targetId(), target,
                String.join(" ", relation.tags()), relation.attributes().toString())
                .toLowerCase(Locale.ROOT);
    }

    private static Map<String, Object> opinionSummary(
            Map<String, Opinion> opinions, int limit) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("count", opinions.size());
        result.put("sampleIds", opinions.keySet().stream().limit(limit).toList());
        result.put("truncated", opinions.size() > limit);
        result.put("averageExpectation", opinions.values().stream()
                .mapToDouble(Opinion::expectation).average().orElse(0.0));
        result.put("averageUncertainty", opinions.values().stream()
                .mapToDouble(Opinion::uncertainty).average().orElse(0.0));
        return result;
    }

    private static Map<String, Object> opinionMap(Opinion opinion) {
        if (opinion == null) {
            return Map.of();
        }
        return Map.of(
                "belief", opinion.belief(),
                "disbelief", opinion.disbelief(),
                "uncertainty", opinion.uncertainty(),
                "baseRate", opinion.baseRate(),
                "expectation", opinion.expectation());
    }

    private static Map<String, double[]> vectorsFor(
            UnifiedGraph graph, VectorLayer.Target target, String id) {
        Map<String, double[]> vectors = new LinkedHashMap<>();
        for (VectorLayer layer : graph.vectorLayers().values()) {
            if (layer.target() == target && layer.contains(id)) {
                vectors.put(layer.name(), layer.get(id));
            }
        }
        return vectors;
    }

    private static Map<String, Double> weightsFor(UnifiedGraph graph, String id) {
        Map<String, Double> weights = new LinkedHashMap<>();
        for (Map.Entry<String, Map<String, Double>> entry : graph.weightMaps().entrySet()) {
            Double value = entry.getValue().get(id);
            if (value != null) {
                weights.put(entry.getKey(), value);
            }
        }
        return weights;
    }

    private static double clamp01(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0, Math.min(1.0, value));
    }

    private static List<GraphRelation> filteredRelations(
            ReasoningGraph graph, String entityId, Direction direction, List<String> relationTypes) {
        List<GraphRelation> candidates = switch (direction) {
            case OUTGOING -> directionalRelations(
                    graph.outgoing(entityId), graph.incoming(entityId));
            case INCOMING -> directionalRelations(
                    graph.incoming(entityId), graph.outgoing(entityId));
            case BOTH -> distinctRelations(graph.relationsOf(entityId));
        };
        Set<String> types = new HashSet<>();
        for (String type : relationTypes) {
            if (!blank(type)) {
                types.add(normalizePredicate(type));
            }
        }
        return candidates.stream()
                .filter(r -> types.isEmpty() || types.contains(normalizePredicate(r.type())))
                .sorted(Comparator.comparingDouble(GraphRelation::weight).reversed()
                        .thenComparing(GraphRelation::id))
                .toList();
    }

    private static List<GraphRelation> directionalRelations(
            List<GraphRelation> primary, List<GraphRelation> reverse) {
        LinkedHashMap<String, GraphRelation> relations = new LinkedHashMap<>();
        for (GraphRelation relation : primary) relations.putIfAbsent(relation.id(), relation);
        for (GraphRelation relation : reverse) {
            if (!relation.directed()) relations.putIfAbsent(relation.id(), relation);
        }
        return new ArrayList<>(relations.values());
    }

    private static List<GraphRelation> distinctRelations(List<GraphRelation> candidates) {
        LinkedHashMap<String, GraphRelation> relations = new LinkedHashMap<>();
        for (GraphRelation relation : candidates) relations.putIfAbsent(relation.id(), relation);
        return new ArrayList<>(relations.values());
    }

    private static String other(String entityId, GraphRelation relation) {
        if (relation.sourceId().equals(entityId)) {
            return relation.targetId();
        }
        if (relation.targetId().equals(entityId)) {
            return relation.sourceId();
        }
        return null;
    }

    private static VerificationStores graphFacts(Iterable<GraphRelation> relations) {
        FactStore facts = new FactStore();
        InMemoryInferredFactStore inferred = new InMemoryInferredFactStore();
        for (GraphRelation relation : relations) {
            String type = normalizePredicate(relation.type());
            boolean negated = type.startsWith("NOT_") && type.length() > 4;
            String predicate = negated ? type.substring(4) : type;
            String atom = claimAtom(predicate, relation.sourceId(), relation.targetId());
            double value = Math.max(0.0, Math.min(1.0,
                    Math.min(relation.weight(), relation.confidence())));
            Instant timestamp = relation.timestamp() == null ? Instant.now() : relation.timestamp();
            if (negated) {
                inferred.store(InferredFact.of("~" + atom, value,
                        List.of("graph-relation:" + relation.id()), List.of(),
                        "graph-query-adapter", 0));
            } else {
                facts.assertFact(new Fact(atom, value, relation.id(), timestamp, true));
            }
        }
        return new VerificationStores(facts, inferred);
    }

    private static List<GraphRelation> claimRelations(
            ReasoningGraph graph, String sourceId, String targetId, String relationType) {
        return graph.outgoing(sourceId).stream()
                .filter(relation -> relation.targetId().equals(targetId))
                .filter(relation -> normalizePredicate(relation.type()).equals(relationType))
                .sorted(Comparator.comparingDouble(GraphRelation::confidence).reversed())
                .toList();
    }

    private static String claimAtom(String relationType, String sourceId, String targetId) {
        return relationType + "(" + sourceId + "," + targetId + ")";
    }

    private static String normalizePredicate(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9_]", "_");
    }

    private static double lexicalScore(GraphEntity entity, String queryText) {
        String normalized = queryText.trim().toLowerCase(Locale.ROOT);
        String[] terms = normalized.split("\\s+");
        double score = lexicalScore(entity, terms);
        String label = entity.label().toLowerCase(Locale.ROOT);
        String id = entity.id().toLowerCase(Locale.ROOT);
        String memberships = String.join(" ", entity.typeMemberships()).toLowerCase(Locale.ROOT);
        if (id.equals(normalized)) {
            score += 12.0;
        } else if (id.contains(normalized)) {
            score += 4.0;
        }
        if (label.equals(normalized)) {
            score += 10.0;
        } else if (label.contains(normalized)) {
            score += 5.0;
        }
        if (memberships.contains(normalized)) {
            score += 2.0;
        }
        return score;
    }

    private static double lexicalScore(GraphEntity entity, String[] terms) {
        String label = entity.label().toLowerCase(Locale.ROOT);
        String type = entity.type().toLowerCase(Locale.ROOT);
        String id = entity.id().toLowerCase(Locale.ROOT);
        String tags = String.join(" ", entity.tags()).toLowerCase(Locale.ROOT);
        String attributes = entity.attributes().toString().toLowerCase(Locale.ROOT);
        double score = 0.0;
        for (String term : terms) {
            if (label.equals(term)) {
                score += 4.0;
            } else if (label.contains(term)) {
                score += 3.0;
            }
            if (id.contains(term)) {
                score += 2.0;
            }
            if (type.contains(term) || tags.contains(term)) {
                score += 1.5;
            }
            if (attributes.contains(term)) {
                score += 0.5;
            }
        }
        return terms.length == 0 ? 0.0 : score / terms.length;
    }

    private static EntityView entityView(
            GraphEntity entity, double score, Double structuralScore, Double semanticScore) {
        TemporalBounds bounds = temporalBounds(entity);
        return new EntityView(entity.id(), entity.label(), entity.type(), entity.typeMemberships(), score,
                structuralScore, semanticScore, entity.weight(), entity.confidence(), entity.tags(),
                entity.hasEmbedding() ? entity.embedding().length : 0,
                entity.timestamp() == null ? null : entity.timestamp().toString(),
                bounds.validFrom(), bounds.validUntil(), entity.attributes());
    }

    private static RelationView relationView(ReasoningGraph graph, GraphRelation relation) {
        String source = graph.entity(relation.sourceId()).map(GraphEntity::label).orElse(relation.sourceId());
        String target = graph.entity(relation.targetId()).map(GraphEntity::label).orElse(relation.targetId());
        TemporalBounds bounds = temporalBounds(relation);
        return new RelationView(relation.id(), relation.type(), relation.sourceId(), source,
                relation.targetId(), target, relation.weight(), relation.confidence(),
                relation.directed(), relation.tags(),
                relation.hasEmbedding() ? relation.embedding().length : 0,
                relation.timestamp() == null ? null : relation.timestamp().toString(),
                bounds.validFrom(), bounds.validUntil(), relation.attributes());
    }

    private static TemporalBounds temporalBounds(GraphEntity entity) {
        try {
            var interval = entity.validTime();
            return interval == null ? new TemporalBounds(null, null)
                    : new TemporalBounds(interval.start() == null ? null : interval.start().toString(),
                            interval.end() == null ? null : interval.end().toString());
        } catch (RuntimeException ignored) {
            return new TemporalBounds(null, null);
        }
    }

    private static TemporalBounds temporalBounds(GraphRelation relation) {
        try {
            var interval = relation.validTime();
            return interval == null ? new TemporalBounds(null, null)
                    : new TemporalBounds(interval.start() == null ? null : interval.start().toString(),
                            interval.end() == null ? null : interval.end().toString());
        } catch (RuntimeException ignored) {
            return new TemporalBounds(null, null);
        }
    }

    private static Result invalid(Intent intent, String summary, String guidance) {
        return new Result(Status.INVALID, intent, summary, List.of(), List.of(), List.of(),
                List.of(), List.of(guidance));
    }

    private static Result notFound(Intent intent, String id) {
        return new Result(Status.NOT_FOUND, intent, "Entity not found: " + id,
                List.of(), List.of(), List.of(), List.of(),
                List.of("Inspect resolutions.candidates or use SEARCH to choose an unambiguous entity id."));
    }

    private static double storedEntityPrior(GraphEntity entity) {
        return clamp01(entity.weight() * entity.confidence());
    }

    private static void checkInterrupted() {
        if (Thread.currentThread().isInterrupted()) {
            throw new CancellationException("Graph entity retrieval interrupted");
        }
    }

    private static int bounded(Integer value, int defaultValue, int maximum) {
        if (value == null || value <= 0) {
            return defaultValue;
        }
        return Math.min(value, maximum);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private record ResolvedQuery(Query query, List<ResolutionView> resolutions) {
    }

    private record EntityMatch(
            GraphEntity entity,
            double lexical,
            double storedPrior,
            int exactRank,
            double score) {
    }

    private record TemporalBounds(String validFrom, String validUntil) {
    }

    private record VerificationStores(FactStore observed, InMemoryInferredFactStore inferred) {
    }

    private record NodeDepth(String id, int depth) {
    }

    private record Link(String parentId, GraphRelation relation) {
    }
}

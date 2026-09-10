/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 */
package ai.kompile.graph.reasoning.unified;

import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.query.GraphQueryEngine;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Streaming global-query execution over a compact {@link UnifiedGraphArchive}.
 *
 * <p>The compatibility {@link GraphQueryEngine} remains authoritative for materialized and
 * entity-scoped reasoning. This engine covers operations that otherwise require loading every
 * relation object. It uses row cursors, bounded top-k heaps, and the optional mmap CSR index, so
 * memory is O(entity labels) for relation text queries and O(V) primitive arrays for structural
 * rank rather than O(E) relation objects.</p>
 */
public final class UnifiedGraphArchiveQueryEngine {

    private static final double DAMPING = 0.85;
    private static final String MAX_REASONING_ENTITIES =
            "kompile.graph.archiveReasoningMaxEntities";
    private static final String MAX_REASONING_RELATIONS =
            "kompile.graph.archiveReasoningMaxRelations";
    private static final int DEFAULT_REASONING_MAX_ENTITIES = 100_000;
    private static final int DEFAULT_REASONING_MAX_RELATIONS = 500_000;
    private static final int PAGE_RANK_ITERATIONS = Math.max(1,
            Integer.getInteger("kompile.graph.archiveRankIterations", 20));

    public boolean supports(GraphQueryEngine.Query query) {
        if (query == null || query.intent() == null) return false;
        return switch (query.intent()) {
            case CAPABILITIES, OVERVIEW, SCHEMA, SEARCH, SIMILAR, RANK, ASSETS, ARTIFACT -> true;
            case RELATIONS, TIMELINE, FACTS -> blank(query.entityId());
            default -> false;
        };
    }

    public GraphQueryEngine.Result query(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        if (!supports(query)) {
            throw new IllegalArgumentException("Archive-native query does not support "
                    + (query == null ? null : query.intent()));
        }
        if (!archive.hasCompactTopology()) {
            throw new IOException("Archive-native global queries require compact KGraph v3");
        }
        return switch (query.intent()) {
            case CAPABILITIES -> new GraphQueryEngine().query(new UnifiedGraph(), query);
            case OVERVIEW -> overview(archive);
            case SCHEMA -> schema(archive);
            case SEARCH -> search(archive, query);
            case RELATIONS -> relations(archive, query);
            case TIMELINE -> timeline(archive, query);
            case FACTS -> facts(archive, query);
            case SIMILAR -> similar(archive, query);
            case RANK -> rank(archive, query);
            case ASSETS -> assets(archive, query);
            case ARTIFACT -> artifact(archive, query);
            default -> throw new IllegalArgumentException("Unsupported archive query " + query.intent());
        };
    }

    private GraphQueryEngine.Result overview(UnifiedGraphArchive archive) throws IOException {
        long timestampedEntities = 0;
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                if (entity.timestamp() != null) timestampedEntities++;
            }
        }
        long directed = 0;
        long timestampedRelations = 0;
        try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (link.directed()) directed++;
                if (link.timestamp() != null) timestampedRelations++;
            }
        }
        long primaryEntities = vectorCount(archive, UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS);
        long primaryRelations = vectorCount(archive, UnifiedGraphFormat.PRIMARY_RELATION_VECTORS);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityCount", archive.entityCount());
        data.put("relationCount", archive.linkCount());
        data.put("directedRelations", directed);
        data.put("undirectedRelations", archive.linkCount() - directed);
        data.put("timestampedEntities", timestampedEntities);
        data.put("timestampedRelations", timestampedRelations);
        data.put("primaryEmbeddedEntities", primaryEntities);
        data.put("embeddedRelations", primaryRelations);
        data.put("formatVersion", archive.formatVersion());
        data.put("compactTopology", true);
        data.put("mmapAdjacency", archive.hasAdjacencyIndex());
        data.put("mutationJournalRecords", archive.journalSnapshot().recordCount());
        data.put("vectorLayerCount", archive.vectorLayerDescriptors().size());
        data.put("artifactCount", archive.artifacts().size());
        data.put("metadata", manifestMap(archive.manifest().get("meta")));
        return dataResult(GraphQueryEngine.Intent.OVERVIEW,
                "Graph overview contains " + archive.entityCount() + " entities and "
                        + archive.linkCount() + " relations.", data);
    }

    private GraphQueryEngine.Result schema(UnifiedGraphArchive archive) throws IOException {
        Map<String, Integer> entityTypes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Map<String, Integer> relationTypes = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> entityTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> relationTags = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> entityAttributes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        Set<String> relationAttributes = new TreeSet<>(String.CASE_INSENSITIVE_ORDER);
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                for (String type : entity.typeMemberships()) entityTypes.merge(type, 1, Integer::sum);
                entityTags.addAll(entity.tags());
                entityAttributes.addAll(entity.attributes().keySet());
            }
        }
        try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                relationTypes.merge(link.type(), 1, Integer::sum);
                relationTags.addAll(link.tags());
                relationAttributes.addAll(link.attributes().keySet());
            }
        }
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("entityTypes", entityTypes);
        data.put("relationTypes", relationTypes);
        data.put("entityTags", entityTags);
        data.put("relationTags", relationTags);
        data.put("entityAttributeKeys", entityAttributes);
        data.put("relationAttributeKeys", relationAttributes);
        data.put("declaredSchema", archive.schemaIndex());
        return dataResult(GraphQueryEngine.Intent.SCHEMA,
                "Found " + entityTypes.size() + " entity type(s) and "
                        + relationTypes.size() + " relation type(s).", data);
    }

    private GraphQueryEngine.Result search(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        if (blank(query.queryText())) {
            return invalid(GraphQueryEngine.Intent.SEARCH, "queryText is required",
                    "Use a name, type, label, tag, or metadata phrase.");
        }
        int limit = bounded(query.topK(), 10, 100);
        String needle = query.queryText().trim().toLowerCase(Locale.ROOT);
        Comparator<ScoredEntity> best = Comparator.comparingDouble(ScoredEntity::score).reversed()
                .thenComparing(ScoredEntity::id);
        PriorityQueue<ScoredEntity> top = worstFirst(best);
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                double score = lexicalScore(entity, needle);
                if (score > 0) {
                    offer(top, new ScoredEntity(entity.id(), score, null, null, 0), limit, best);
                }
            }
        }
        List<ScoredEntity> ranked = sorted(top, best);
        List<GraphQueryEngine.EntityView> views = entityViews(archive, ranked);
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, GraphQueryEngine.Intent.SEARCH,
                "Found " + views.size() + " matching entity(s) for '" + query.queryText() + "'.",
                views, List.of(), List.of(), List.of(), views.isEmpty()
                ? List.of("Try fewer or broader terms, then use the returned id with DESCRIBE.")
                : List.of("Use a returned id with DESCRIBE, NEIGHBORS, or PATH."));
    }

    private GraphQueryEngine.Result relations(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        int limit = bounded(query.topK(), 20, 200);
        Set<String> types = normalizedTypes(query.relationTypes());
        String text = blank(query.queryText()) ? null : query.queryText().toLowerCase(Locale.ROOT);
        Map<String, String> labels = loadLabels(archive);
        Comparator<ScoredLink> best = Comparator.comparingDouble(ScoredLink::score).reversed()
                .thenComparing(candidate -> candidate.link().id());
        PriorityQueue<ScoredLink> top = worstFirst(best);
        try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (!types.isEmpty() && !types.contains(normalizePredicate(link.type()))) continue;
                if (text != null && !linkSearchText(link, labels).contains(text)) continue;
                offer(top, new ScoredLink(link, link.weight() * link.confidence()), limit, best);
            }
        }
        List<GraphQueryEngine.RelationView> views = sorted(top, best).stream()
                .map(item -> relationView(item.link(), labels)).toList();
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, GraphQueryEngine.Intent.RELATIONS,
                "Found " + views.size() + " ranked relation(s).", List.of(), views,
                List.of(), List.of(), List.of());
    }

    private GraphQueryEngine.Result timeline(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        int limit = bounded(query.topK(), 50, 500);
        Set<String> types = normalizedTypes(query.relationTypes());
        Comparator<TimedLink> best = Comparator.comparing((TimedLink item) -> item.link().timestamp())
                .thenComparing(item -> item.link().id());
        PriorityQueue<TimedLink> top = worstFirst(best);
        try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (link.timestamp() == null) continue;
                if (!types.isEmpty() && !types.contains(normalizePredicate(link.type()))) continue;
                offer(top, new TimedLink(link), limit, best);
            }
        }
        Map<String, String> labels = loadLabels(archive);
        List<GraphQueryEngine.RelationView> views = sorted(top, best).stream()
                .map(item -> relationView(item.link(), labels)).toList();
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, GraphQueryEngine.Intent.TIMELINE,
                "Found " + views.size() + " timestamped relation event(s) in chronological order.",
                List.of(), views, List.of(), List.of(), views.isEmpty()
                ? List.of("The selected graph scope has no relation timestamps.") : List.of());
    }

    private GraphQueryEngine.Result facts(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        int limit = bounded(query.topK(), 50, 1000);
        String filter = blank(query.queryText()) ? null : query.queryText().toLowerCase(Locale.ROOT);
        Set<String> types = normalizedTypes(query.relationTypes());
        Comparator<FactCandidate> best = Comparator.comparingDouble(FactCandidate::confidence).reversed()
                .thenComparing(FactCandidate::atom);
        PriorityQueue<FactCandidate> top = worstFirst(best);
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                for (String type : entity.typeMemberships()) {
                    String atom = type + "(" + entity.id() + ")";
                    if (filter == null || atom.toLowerCase(Locale.ROOT).contains(filter)) {
                        offer(top, new FactCandidate(atom, "entity_type", entity.confidence(), entity.id()),
                                limit, best);
                    }
                }
            }
        }
        Map<String, String> labels = filter == null ? Map.of() : loadLabels(archive);
        try (UnifiedGraphArchive.LinkCursor cursor = archive.openLinks()) {
            UnifiedGraphArchive.Link link;
            while ((link = cursor.next()) != null) {
                if (!types.isEmpty() && !types.contains(normalizePredicate(link.type()))) continue;
                String atom = normalizePredicate(link.type()) + "(" + link.sourceId() + ","
                        + link.targetId() + ")";
                if (filter == null || atom.toLowerCase(Locale.ROOT).contains(filter)
                        || linkSearchText(link, labels).contains(filter)) {
                    offer(top, new FactCandidate(atom, "relation", link.confidence(), link.id()),
                            limit, best);
                }
            }
        }
        List<Map<String, Object>> facts = sorted(top, best).stream().map(candidate -> Map.<String, Object>of(
                "atom", candidate.atom(), "kind", candidate.kind(),
                "confidence", candidate.confidence(), "source", candidate.source())).toList();
        return dataResult(GraphQueryEngine.Intent.FACTS,
                "Returned " + facts.size() + " ranked graph fact(s).", Map.of("facts", facts));
    }

    private GraphQueryEngine.Result similar(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        if (blank(query.entityId())) {
            return invalid(GraphQueryEngine.Intent.SIMILAR, "entityId or entity phrase is required",
                    "Use SEARCH or pass a human-readable entity name.");
        }
        GraphEntity source = resolveEntity(archive, query.entityId());
        if (source == null) return notFound(GraphQueryEngine.Intent.SIMILAR, query.entityId());
        int limit = bounded(query.topK(), 10, 100);
        String layer = chooseEntityLayer(archive, query.queryText());
        double[] sourceVector = query.queryEmbedding();
        if (sourceVector == null && layer != null) sourceVector = findVector(archive, layer, source.id());
        StructuralRanking structural;
        try (CompactAdjacencyCodec.Index index = archive.hasAdjacencyIndex()
                ? archive.openAdjacencyIndex() : null) {
            structural = index == null ? null : pageRank(index, source.id(), archive.journalSnapshot());
        }
        List<ScoredEntity> ranked = rankEntities(
                archive, layer, sourceVector, structural, source.id(), limit);
        List<GraphQueryEngine.EntityView> views = entityViews(archive, ranked);
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceId", source.id());
        data.put("embeddingLayer", layer == null ? "none" : layer);
        data.put("semanticVectorAvailable", sourceVector != null);
        data.put("structuralEngine", archive.hasAdjacencyIndex() ? "MMAP_PERSONALIZED_PAGERANK" : "NONE");
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, GraphQueryEngine.Intent.SIMILAR,
                "Ranked " + views.size() + " entities similar to " + source.label()
                        + " using streaming archive vectors and structure.",
                views, List.of(), List.of(), List.of(), sourceVector == null
                ? List.of("No source vector was available; ranking is structural only.") : List.of(),
                data, List.of(), null);
    }

    private GraphQueryEngine.Result rank(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        if (query.structural() != null) {
            return explicitStructuralRank(archive, query);
        }
        if (!archive.hasAdjacencyIndex()) {
            return invalid(GraphQueryEngine.Intent.RANK, "Compact archive has no adjacency index",
                    "Migrate the KGraph to the current compact format before global structural rank.");
        }
        int limit = bounded(query.topK(), 10, 100);
        String layer = query.queryEmbedding() == null ? null : chooseEntityLayer(archive, null);
        StructuralRanking structural;
        try (CompactAdjacencyCodec.Index index = archive.openAdjacencyIndex()) {
            structural = pageRank(index, null, archive.journalSnapshot());
        }
        List<ScoredEntity> ranked = rankEntities(
                archive, layer, query.queryEmbedding(), structural, null, limit);
        List<GraphQueryEngine.EntityView> views = entityViews(archive, ranked);
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, GraphQueryEngine.Intent.RANK,
                "Ranked " + views.size() + " entity(s) using mmap PageRank"
                        + (query.queryEmbedding() == null ? "." : " plus streaming semantic similarity."),
                views, List.of(), List.of(), List.of(), query.queryEmbedding() == null
                ? List.of("Provide queryEmbedding to include semantic similarity.") : List.of());
    }

    private GraphQueryEngine.Result explicitStructuralRank(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        int maxEntities = boundedReasoningLimit(MAX_REASONING_ENTITIES, DEFAULT_REASONING_MAX_ENTITIES);
        int maxRelations = boundedReasoningLimit(MAX_REASONING_RELATIONS, DEFAULT_REASONING_MAX_RELATIONS);
        if (archive.entityCount() > maxEntities || archive.linkCount() > maxRelations) {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("requestedStructural", query.structural().name());
            data.put("entityCount", archive.entityCount());
            data.put("relationCount", archive.linkCount());
            data.put("maxEntities", maxEntities);
            data.put("maxRelations", maxRelations);
            return new GraphQueryEngine.Result(GraphQueryEngine.Status.INVALID,
                    GraphQueryEngine.Intent.RANK,
                    "Explicit " + query.structural() + " ranking exceeds the bounded archive reasoning budget.",
                    List.of(), List.of(), List.of(), List.of(),
                    List.of("Increase " + MAX_REASONING_ENTITIES + " and/or "
                            + MAX_REASONING_RELATIONS + " only when full-graph reasoning is intended."),
                    data, List.of(), null);
        }
        // Explicit structural selection is the opt-in boundary for the full HybridReasoner path.
        // The default archive rank above remains mmap PageRank and never materializes all links.
        UnifiedGraph graph = archive.materializeForReasoning(maxEntities, maxRelations);
        return new GraphQueryEngine().query(graph, query);
    }

    private GraphQueryEngine.Result assets(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        int limit = bounded(query.topK(), 20, 200);
        List<Map<String, Object>> layers = new ArrayList<>();
        for (Map<String, Object> descriptor : archive.vectorLayerDescriptors()) {
            String name = String.valueOf(descriptor.get("name"));
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("name", name);
            row.put("target", descriptor.get("target"));
            row.put("dimension", descriptor.get("dim"));
            row.put("dtype", descriptor.get("dtype"));
            row.put("rowCount", descriptor.get("count"));
            List<String> samples = new ArrayList<>();
            try (VectorBlobCodec.RowCursor cursor = archive.openVectorRows(name)) {
                VectorBlobCodec.VectorRow vector;
                while (samples.size() < limit && (vector = cursor.next()) != null) samples.add(vector.id());
            }
            row.put("sampleIds", samples);
            layers.add(Collections.unmodifiableMap(row));
        }
        List<Map<String, Object>> artifacts = archive.artifacts().stream()
                .map(item -> Map.<String, Object>of("name", item.name(), "bytes", item.bytes())).toList();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("unifiedGraph", true);
        data.put("formatVersion", archive.formatVersion());
        data.put("metadata", manifestMap(archive.manifest().get("meta")));
        data.put("vectorLayers", layers);
        data.put("weightMapsPresent", manifestSections(archive).contains(UnifiedGraphFormat.ENTRY_WEIGHTS));
        data.put("artifacts", artifacts);
        if (!blank(query.queryText())) {
            String selector = query.queryText().trim();
            Map<String, Object> selected = sampleVectorLayer(archive, selector, limit);
            if (selected != null) data.put("selectedVectorLayer", selected);
            else data.put("selectorMatched", false);
        }
        return dataResult(GraphQueryEngine.Intent.ASSETS,
                "Found " + layers.size() + " vector layer(s) and "
                        + artifacts.size() + " artifact(s).", data);
    }

    private GraphQueryEngine.Result artifact(
            UnifiedGraphArchive archive, GraphQueryEngine.Query query) throws IOException {
        if (blank(query.queryText())) {
            return invalid(GraphQueryEngine.Intent.ARTIFACT, "queryText must name an artifact",
                    "Call ASSETS to list available artifact names.");
        }
        String name = query.queryText().trim();
        UnifiedGraphArchive.ArtifactContent content = archive.readArtifact(name, 20_000);
        if (content == null) {
            return new GraphQueryEngine.Result(GraphQueryEngine.Status.NOT_FOUND,
                    GraphQueryEngine.Intent.ARTIFACT, "Artifact not found: " + name,
                    List.of(), List.of(), List.of(), List.of(),
                    List.of("Call ASSETS to list artifact names."));
        }
        return dataResult(GraphQueryEngine.Intent.ARTIFACT,
                "Read artifact " + name + " (" + content.totalBytes() + " bytes).",
                Map.of("name", name, "bytes", content.totalBytes(),
                        "text", new String(content.bytes(), StandardCharsets.UTF_8),
                        "truncated", content.truncated()));
    }

    private List<ScoredEntity> rankEntities(
            UnifiedGraphArchive archive,
            String layer,
            double[] queryVector,
            StructuralRanking structural,
            String excludedId,
            int limit) throws IOException {
        Comparator<ScoredEntity> best = Comparator.comparingDouble(ScoredEntity::score).reversed()
                .thenComparing(ScoredEntity::id);
        PriorityQueue<ScoredEntity> top = worstFirst(best);
        boolean semantic = queryVector != null && layer != null;
        if (semantic) {
            try (VectorBlobCodec.RowCursor cursor = archive.openVectorRows(layer)) {
                VectorBlobCodec.VectorRow row;
                while ((row = cursor.next()) != null) {
                    if (row.id().equals(excludedId)) continue;
                    double[] values = row.values();
                    double semanticScore = cosine(queryVector, values);
                    if (!Double.isFinite(semanticScore)) continue;
                    Integer ordinal = null;
                    double structuralScore = 0.0;
                    if (structural != null) ordinal = structural.ordinal(row.id());
                    if (structural != null && ordinal == null) continue;
                    if (ordinal != null) structuralScore = structural.scores()[ordinal];
                    double normalizedSemantic = (semanticScore + 1.0) * 0.5;
                    double score = structural == null ? normalizedSemantic
                            : 0.5 * normalizedSemantic + 0.5 * structuralScore;
                    offer(top, new ScoredEntity(row.id(), score,
                            structural == null ? null : structuralScore,
                            normalizedSemantic, values.length), limit, best);
                }
            }
        } else if (structural != null) {
            for (int ordinal = 0; ordinal < structural.scores().length; ordinal++) {
                String id = structural.ids().get(ordinal);
                if (id.equals(excludedId)) continue;
                offer(top, new ScoredEntity(id, structural.scores()[ordinal],
                        structural.scores()[ordinal], null, 0), limit, best);
            }
        }
        return sorted(top, best);
    }

    private static StructuralRanking pageRank(
            CompactAdjacencyCodec.Index index,
            String sourceId,
            UnifiedGraphMutationJournal.Snapshot journal) throws IOException {
        List<String> ids = new ArrayList<>(index.nodeCount() + journal.entityDelta());
        for (int ordinal = 0; ordinal < index.nodeCount(); ordinal++) ids.add(index.nodeId(ordinal));
        Map<String, Integer> extras = new LinkedHashMap<>();
        for (UnifiedGraphMutationJournal.EntityState state : journal.entityStates().values()) {
            addExtraOrdinal(index, ids, extras, state.current().id());
        }
        for (UnifiedGraphMutationJournal.RelationState state : journal.relationStates().values()) {
            if (state.current() == null) continue;
            addExtraOrdinal(index, ids, extras, state.current().sourceId());
            addExtraOrdinal(index, ids, extras, state.current().targetId());
        }
        int nodes = ids.size();
        if (nodes == 0) {
            return new StructuralRanking(new double[0], List.of(), Map.of(), Map.of());
        }
        boolean[] replacedBaseOrdinals = new boolean[index.linkCount()];
        for (UnifiedGraphMutationJournal.RelationState state : journal.relationStates().values()) {
            if (state.baseLink() != null && state.baseLink().index() >= 0) {
                int ordinal = state.baseLink().index();
                if (ordinal >= replacedBaseOrdinals.length) {
                    throw new IOException("Journal base relation ordinal is out of range");
                }
                replacedBaseOrdinals[ordinal] = true;
            }
        }
        double[] outgoing = new double[nodes];
        index.forEachCore((ordinal, from, to, type, weight, confidence, directed) -> {
            if (replacedBaseOrdinals[ordinal]) return;
            double edgeWeight = Math.max(1.0e-12, Math.abs(weight * confidence));
            outgoing[from] += edgeWeight;
            if (!directed && to != from) outgoing[to] += edgeWeight;
        });
        for (UnifiedGraphMutationJournal.RelationState state : journal.relationStates().values()) {
            if (state.current() != null) adjustOutgoing(index, extras, outgoing, state.current(), 1.0);
        }
        for (int node = 0; node < outgoing.length; node++) {
            if (outgoing[node] < 1.0e-9) outgoing[node] = 0.0;
        }
        Integer source = sourceId == null ? null : ordinal(index, extras, sourceId);
        if (sourceId != null && source == null) {
            return new StructuralRanking(
                    new double[nodes], ids, index.nodeOrdinalsView(), extras);
        }
        double[] score = new double[nodes];
        if (source == null) Arrays.fill(score, 1.0 / nodes);
        else score[source] = 1.0;
        double[] next = new double[nodes];
        for (int iteration = 0; iteration < PAGE_RANK_ITERATIONS; iteration++) {
            Arrays.fill(next, source == null ? (1.0 - DAMPING) / nodes : 0.0);
            if (source != null) next[source] = 1.0 - DAMPING;
            double dangling = 0.0;
            for (int node = 0; node < nodes; node++) if (outgoing[node] == 0.0) dangling += score[node];
            if (source == null) {
                double spread = DAMPING * dangling / nodes;
                for (int node = 0; node < nodes; node++) next[node] += spread;
            } else {
                next[source] += DAMPING * dangling;
            }
            double[] current = score;
            double[] target = next;
            index.forEachCore((ordinal, from, to, type, weight, confidence, directed) -> {
                if (replacedBaseOrdinals[ordinal]) return;
                double edgeWeight = Math.max(1.0e-12, Math.abs(weight * confidence));
                target[to] += DAMPING * current[from] * edgeWeight / outgoing[from];
                if (!directed && to != from) {
                    target[from] += DAMPING * current[to] * edgeWeight / outgoing[to];
                }
            });
            for (UnifiedGraphMutationJournal.RelationState state : journal.relationStates().values()) {
                if (state.current() != null) {
                    adjustContribution(index, extras, outgoing, current, target,
                            state.current(), DAMPING);
                }
            }
            double[] swap = score;
            score = next;
            next = swap;
        }
        double max = 0.0;
        for (double value : score) max = Math.max(max, value);
        if (max > 0.0) for (int i = 0; i < score.length; i++) score[i] /= max;
        return new StructuralRanking(score, ids, index.nodeOrdinalsView(), extras);
    }

    private static void addExtraOrdinal(
            CompactAdjacencyCodec.Index index,
            List<String> ids,
            Map<String, Integer> extras,
            String id) {
        if (index.nodeOrdinal(id) == null && !extras.containsKey(id)) {
            extras.put(id, ids.size());
            ids.add(id);
        }
    }

    private static Integer ordinal(
            CompactAdjacencyCodec.Index index, Map<String, Integer> extras, String id) {
        Integer base = index.nodeOrdinal(id);
        return base != null ? base : extras.get(id);
    }

    private static void adjustOutgoing(
            CompactAdjacencyCodec.Index index,
            Map<String, Integer> extras,
            double[] outgoing,
            UnifiedGraphArchive.Link link,
            double sign) throws IOException {
        Integer source = ordinal(index, extras, link.sourceId());
        Integer target = ordinal(index, extras, link.targetId());
        if (source == null || target == null) throw new IOException("Journal relation endpoint is missing");
        double weight = Math.max(1.0e-12, Math.abs(link.weight() * link.confidence())) * sign;
        outgoing[source] += weight;
        if (!link.directed() && !source.equals(target)) outgoing[target] += weight;
    }

    private static void adjustContribution(
            CompactAdjacencyCodec.Index index,
            Map<String, Integer> extras,
            double[] outgoing,
            double[] current,
            double[] targetScores,
            UnifiedGraphArchive.Link link,
            double damping) throws IOException {
        Integer source = ordinal(index, extras, link.sourceId());
        Integer target = ordinal(index, extras, link.targetId());
        if (source == null || target == null) throw new IOException("Journal relation endpoint is missing");
        double weight = Math.max(1.0e-12, Math.abs(link.weight() * link.confidence()));
        if (outgoing[source] > 0.0) {
            targetScores[target] += damping * current[source] * weight / outgoing[source];
        }
        if (!link.directed() && !source.equals(target) && outgoing[target] > 0.0) {
            targetScores[source] += damping * current[target] * weight / outgoing[target];
        }
    }

    private record StructuralRanking(
            double[] scores,
            List<String> ids,
            Map<String, Integer> baseOrdinals,
            Map<String, Integer> extras) {
        Integer ordinal(String id) {
            Integer base = baseOrdinals.get(id);
            return base != null ? base : extras.get(id);
        }
    }

    private static List<GraphQueryEngine.EntityView> entityViews(
            UnifiedGraphArchive archive, List<ScoredEntity> ranked) throws IOException {
        if (ranked.isEmpty()) return List.of();
        Map<String, ScoredEntity> selected = new HashMap<>();
        for (ScoredEntity score : ranked) selected.put(score.id(), score);
        Map<String, GraphQueryEngine.EntityView> views = new HashMap<>();
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                ScoredEntity score = selected.get(entity.id());
                if (score != null) views.put(entity.id(), entityView(entity, score));
            }
        }
        return ranked.stream().map(item -> views.get(item.id())).filter(java.util.Objects::nonNull).toList();
    }

    private static GraphQueryEngine.EntityView entityView(GraphEntity entity, ScoredEntity score) {
        return new GraphQueryEngine.EntityView(entity.id(), entity.label(), entity.type(),
                entity.typeMemberships(), score.score(), score.structural(), score.semantic(),
                entity.weight(), entity.confidence(), entity.tags(), score.embeddingDimension(),
                instant(entity.timestamp()), attributeText(entity.attributes(), "validFrom"),
                attributeText(entity.attributes(), "validUntil"), entity.attributes());
    }

    private static GraphQueryEngine.RelationView relationView(
            UnifiedGraphArchive.Link link, Map<String, String> labels) {
        return new GraphQueryEngine.RelationView(link.id(), link.type(), link.sourceId(),
                labels.getOrDefault(link.sourceId(), link.sourceId()), link.targetId(),
                labels.getOrDefault(link.targetId(), link.targetId()), link.weight(), link.confidence(),
                link.directed(), link.tags(), 0, instant(link.timestamp()),
                attributeText(link.attributes(), "validFrom"), attributeText(link.attributes(), "validUntil"),
                link.attributes());
    }

    private static Map<String, String> loadLabels(UnifiedGraphArchive archive) throws IOException {
        Map<String, String> labels = new HashMap<>(Math.max(16, archive.entityCount() * 4 / 3));
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                labels.put(entity.id(), blank(entity.label()) ? entity.id() : entity.label());
            }
        }
        return labels;
    }

    private static GraphEntity resolveEntity(UnifiedGraphArchive archive, String selector) throws IOException {
        GraphEntity labelMatch = null;
        try (UnifiedGraphArchive.EntityCursor cursor = archive.openEntities()) {
            GraphEntity entity;
            while ((entity = cursor.next()) != null) {
                if (entity.id().equals(selector)) return entity;
                if (labelMatch == null && entity.label().equalsIgnoreCase(selector)) labelMatch = entity;
            }
        }
        return labelMatch;
    }

    private static String chooseEntityLayer(UnifiedGraphArchive archive, String selector) throws IOException {
        List<Map<String, Object>> layers = archive.vectorLayerDescriptors();
        if (!blank(selector)) {
            for (Map<String, Object> layer : layers) {
                if (selector.equalsIgnoreCase(String.valueOf(layer.get("name")))
                        && "ENTITY".equals(String.valueOf(layer.get("target")))) {
                    return String.valueOf(layer.get("name"));
                }
            }
        }
        for (Map<String, Object> layer : layers) {
            if (UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS.equals(layer.get("name"))) {
                return UnifiedGraphFormat.PRIMARY_ENTITY_VECTORS;
            }
        }
        for (Map<String, Object> layer : layers) {
            if ("ENTITY".equals(String.valueOf(layer.get("target")))) {
                return String.valueOf(layer.get("name"));
            }
        }
        return null;
    }

    private static double[] findVector(
            UnifiedGraphArchive archive, String layer, String id) throws IOException {
        try (VectorBlobCodec.RowCursor cursor = archive.openVectorRows(layer)) {
            VectorBlobCodec.VectorRow row;
            while ((row = cursor.next()) != null) if (row.id().equals(id)) return row.values();
        }
        return null;
    }

    private static long vectorCount(UnifiedGraphArchive archive, String layerName) throws IOException {
        for (Map<String, Object> descriptor : archive.vectorLayerDescriptors()) {
            if (layerName.equals(descriptor.get("name")) && descriptor.get("count") instanceof Number count) {
                return count.longValue();
            }
        }
        return 0;
    }

    private static Map<String, Object> sampleVectorLayer(
            UnifiedGraphArchive archive, String selector, int limit) throws IOException {
        Map<String, Object> descriptor = archive.vectorLayerDescriptors().stream()
                .filter(layer -> selector.equalsIgnoreCase(String.valueOf(layer.get("name"))))
                .findFirst().orElse(null);
        if (descriptor == null) return null;
        Map<String, double[]> rows = new LinkedHashMap<>();
        try (VectorBlobCodec.RowCursor cursor = archive.openVectorRows(String.valueOf(descriptor.get("name")))) {
            VectorBlobCodec.VectorRow row;
            while (rows.size() < limit && (row = cursor.next()) != null) rows.put(row.id(), row.values());
        }
        Map<String, Object> selected = new LinkedHashMap<>();
        selected.put("name", descriptor.get("name"));
        selected.put("target", descriptor.get("target"));
        selected.put("dimension", descriptor.get("dim"));
        selected.put("dtype", descriptor.get("dtype"));
        selected.put("rowCount", descriptor.get("count"));
        selected.put("rows", rows);
        long count = descriptor.get("count") instanceof Number number ? number.longValue() : rows.size();
        selected.put("truncated", count > rows.size());
        return selected;
    }

    private static Set<String> manifestSections(UnifiedGraphArchive archive) {
        Object raw = archive.manifest().get("sections");
        if (!(raw instanceof List<?> values)) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (Object value : values) if (value != null) result.add(String.valueOf(value));
        return result;
    }

    private static Map<String, Object> manifestMap(Object raw) {
        if (!(raw instanceof Map<?, ?> value)) return Map.of();
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> item : value.entrySet()) {
            if (item.getKey() != null) result.put(String.valueOf(item.getKey()), item.getValue());
        }
        return Collections.unmodifiableMap(result);
    }

    private static double lexicalScore(GraphEntity entity, String needle) {
        String id = entity.id().toLowerCase(Locale.ROOT);
        String label = entity.label().toLowerCase(Locale.ROOT);
        String type = entity.type().toLowerCase(Locale.ROOT);
        String text = entityText(entity);
        double score;
        if (id.equals(needle) || label.equals(needle)) score = 1.0;
        else if (id.startsWith(needle) || label.startsWith(needle)) score = 0.9;
        else if (id.contains(needle) || label.contains(needle)) score = 0.8;
        else if (type.equals(needle)) score = 0.7;
        else if (text.contains(needle)) score = 0.55;
        else {
            String[] terms = needle.split("[^a-z0-9]+", -1);
            int nonBlank = 0;
            int matched = 0;
            for (String term : terms) {
                if (term.isBlank()) continue;
                nonBlank++;
                if (text.contains(term)) matched++;
            }
            score = nonBlank == 0 || matched == 0 ? 0.0 : 0.5 * matched / nonBlank;
        }
        return score == 0.0 ? 0.0 : score * Math.max(0.0, entity.confidence());
    }

    private static String entityText(GraphEntity entity) {
        return (entity.id() + " " + entity.label() + " " + entity.type() + " "
                + entity.typeMemberships() + " " + entity.tags() + " " + entity.attributes())
                .toLowerCase(Locale.ROOT);
    }

    private static String linkSearchText(
            UnifiedGraphArchive.Link link, Map<String, String> labels) {
        return (link.id() + " " + link.type() + " " + link.sourceId() + " "
                + labels.getOrDefault(link.sourceId(), "") + " " + link.targetId() + " "
                + labels.getOrDefault(link.targetId(), "") + " " + link.tags() + " "
                + link.attributes()).toLowerCase(Locale.ROOT);
    }

    private static Set<String> normalizedTypes(List<String> values) {
        if (values == null || values.isEmpty()) return Set.of();
        Set<String> result = new LinkedHashSet<>();
        for (String value : values) if (!blank(value)) result.add(normalizePredicate(value));
        return result;
    }

    private static String normalizePredicate(String value) {
        if (value == null) return "";
        return value.trim().replace('-', '_').replace(' ', '_').toLowerCase(Locale.ROOT);
    }

    private static double cosine(double[] left, double[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) return Double.NaN;
        double dot = 0.0;
        double leftNorm = 0.0;
        double rightNorm = 0.0;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        return leftNorm == 0.0 || rightNorm == 0.0 ? Double.NaN
                : dot / Math.sqrt(leftNorm * rightNorm);
    }

    private static String instant(Instant value) { return value == null ? null : value.toString(); }

    private static String attributeText(Map<String, Object> attributes, String key) {
        Object value = attributes == null ? null : attributes.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static int bounded(Integer requested, int fallback, int maximum) {
        return requested == null || requested <= 0 ? fallback : Math.min(requested, maximum);
    }

    private static int boundedReasoningLimit(String property, int fallback) {
        return Math.max(1, Integer.getInteger(property, fallback));
    }

    private static boolean blank(String value) { return value == null || value.isBlank(); }

    private static <T> PriorityQueue<T> worstFirst(Comparator<T> bestFirst) {
        return new PriorityQueue<>(bestFirst.reversed());
    }

    private static <T> void offer(
            PriorityQueue<T> heap, T candidate, int limit, Comparator<T> bestFirst) {
        if (heap.size() < limit) {
            heap.add(candidate);
        } else if (bestFirst.compare(candidate, heap.peek()) < 0) {
            heap.poll();
            heap.add(candidate);
        }
    }

    private static <T> List<T> sorted(PriorityQueue<T> heap, Comparator<T> bestFirst) {
        List<T> result = new ArrayList<>(heap);
        result.sort(bestFirst);
        return result;
    }

    private static GraphQueryEngine.Result dataResult(
            GraphQueryEngine.Intent intent, String summary, Map<String, Object> data) {
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.OK, intent, summary,
                List.of(), List.of(), List.of(), List.of(), List.of(), data, List.of(), null);
    }

    private static GraphQueryEngine.Result invalid(
            GraphQueryEngine.Intent intent, String summary, String guidance) {
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.INVALID, intent, summary,
                List.of(), List.of(), List.of(), List.of(), List.of(guidance));
    }

    private static GraphQueryEngine.Result notFound(GraphQueryEngine.Intent intent, String selector) {
        return new GraphQueryEngine.Result(GraphQueryEngine.Status.NOT_FOUND, intent,
                "Entity not found: " + selector, List.of(), List.of(), List.of(), List.of(),
                List.of("Use SEARCH to choose an exact entity id."));
    }

    private record ScoredEntity(
            String id, double score, Double structural, Double semantic, int embeddingDimension) { }
    private record ScoredLink(UnifiedGraphArchive.Link link, double score) { }
    private record TimedLink(UnifiedGraphArchive.Link link) { }
    private record FactCandidate(String atom, String kind, double confidence, String source) { }
}

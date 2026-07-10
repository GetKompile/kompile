/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 */

package ai.kompile.app.ontology;

import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.Cardinality;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Resolves the graph's LABELED RELATIONS into ontology schema — the missing half of structural
 * ontology derivation, which promotes entity types to classes but leaves relationship definitions
 * as untyped placeholders. For every relation label observed on real edges (SENT_BY, BELONGS_TO,
 * MENTIONS, custom domain labels, the miner's PRECEDES/DIRECTLY_FOLLOWS…) this induces:
 *
 * <ul>
 *   <li><b>domain/range</b> — the dominant (source entity type → target entity type) pair when it
 *       carries ≥ {@link #DOMINANT_SHARE} of the label's edges, alias-resolved onto the schema's
 *       canonical class names. {@code OwlOntologyBridge} turns these into {@code rdfs:domain}/
 *       {@code rdfs:range} on the OWL object property, {@code ontologyAxioms()} emits
 *       DOMAIN/RANGE axioms for the PSL/MEBN side, and conformance checking gains per-relation
 *       endpoint validation;</li>
 *   <li><b>cardinality</b> — from observed out/in degree per endpoint (ONE_TO_ONE …
 *       MANY_TO_MANY);</li>
 *   <li><b>richer metadata</b> — support, dominant-pair share, symmetry share and flag
 *       ({@code OwlOntologyBridge} turns it into {@code owl:SymmetricProperty}), observed degree
 *       averages (exact fan-out/fan-in of 1 becomes {@code owl:Functional}/
 *       {@code owl:InverseFunctionalProperty} there) — persisted on the definition's metadata map
 *       so OWL/MEBN layers can consume more over time without re-mining the graph.</li>
 * </ul>
 *
 * <p>Merging never overwrites human-authored values: existing non-blank domain/range/cardinality
 * stand; only gaps are filled and metadata refreshed. Update-and-rebind follows the same pattern
 * as post-OWL type induction. Deterministic and idempotent — a second pass over the same graph
 * changes nothing.</p>
 */
@Service
public class RelationSchemaResolutionService {

    private static final Logger log = LoggerFactory.getLogger(RelationSchemaResolutionService.class);

    /** Labels observed on fewer edges than this are noise, not schema. */
    static final int MIN_SUPPORT = 3;
    /** Share of a label's edges the dominant (source,target)-type pair needs to claim domain/range. */
    static final double DOMINANT_SHARE = 0.6;
    /** Share of edges with a same-label reverse edge for the symmetry flag. */
    static final double SYMMETRIC_SHARE = 0.8;
    /** Mean degree above this counts as "many" for cardinality induction. */
    static final double MANY_THRESHOLD = 1.25;

    private final KnowledgeGraphService knowledgeGraphService;
    private final GraphOntologyBindingService bindingService;
    private final ProcessEngineService processEngineService;

    public RelationSchemaResolutionService(KnowledgeGraphService knowledgeGraphService,
                                           GraphOntologyBindingService bindingService,
                                           ProcessEngineService processEngineService) {
        this.knowledgeGraphService = knowledgeGraphService;
        this.bindingService = bindingService;
        this.processEngineService = processEngineService;
    }

    /** Outcome of one resolution pass. */
    public record Result(boolean changed, int labelsObserved, int definitionsAdded,
                         int definitionsEnriched, int version) {
        public static Result unchanged(int labelsObserved, int version) {
            return new Result(false, labelsObserved, 0, 0, version);
        }
    }

    /** Resolve labeled relations of the fact sheet's graph into its governing ontology. */
    public Result resolveRelationSchema(long factSheetId) {
        try {
            Optional<OntologySchema> active = bindingService.resolveActiveOntology(factSheetId);
            if (active.isEmpty()) {
                active = bindingService.autoProvisionStructuralOntology(factSheetId);
            }
            if (active.isEmpty()) {
                return Result.unchanged(0, 0);
            }
            OntologySchema schema = active.get();

            Map<String, String> canonicalTypeByAlias = canonicalTypeIndex(schema);
            Map<String, String> typeByNodeId = new LinkedHashMap<>();
            for (GraphNode node : knowledgeGraphService.getNodesInFactSheet(factSheetId)) {
                String type = entityType(node);
                if (node.getNodeId() != null && type != null) {
                    typeByNodeId.put(node.getNodeId(), type);
                }
            }

            Map<String, LabelStats> statsByLabel = collectLabelStats(
                    knowledgeGraphService.getEdgesInFactSheet(factSheetId), typeByNodeId);

            List<RelationshipTypeDefinition> definitions = schema.getRelationshipTypes() != null
                    ? new ArrayList<>(schema.getRelationshipTypes())
                    : new ArrayList<>();
            int added = 0;
            int enriched = 0;
            for (Map.Entry<String, LabelStats> entry : statsByLabel.entrySet()) {
                LabelStats stats = entry.getValue();
                if (stats.total < MIN_SUPPORT) {
                    continue;
                }
                RelationshipTypeDefinition existing = definitions.stream()
                        .filter(d -> entry.getKey().equalsIgnoreCase(d.getType()))
                        .findFirst().orElse(null);
                if (existing == null) {
                    definitions.add(buildDefinition(entry.getKey(), stats, canonicalTypeByAlias));
                    added++;
                } else if (enrich(existing, stats, canonicalTypeByAlias)) {
                    enriched++;
                }
            }
            if (added == 0 && enriched == 0) {
                return Result.unchanged(statsByLabel.size(), schema.getVersion());
            }

            schema.setRelationshipTypes(definitions);
            Map<String, Object> metadata = schema.getMetadata() != null
                    ? new LinkedHashMap<>(schema.getMetadata()) : new LinkedHashMap<>();
            metadata.put("relationSchemaResolvedAt", Instant.now().toString());
            metadata.put("relationSchemaLabelsObserved", statsByLabel.size());
            schema.setMetadata(metadata);
            schema.setUpdatedBy("graph-relation-resolution");

            OntologySchema updated = processEngineService.updateOntology(schema.getId(), schema);
            bindingService.bindOntology(factSheetId, updated.getId(), updated.getVersion());
            log.info("Relation schema resolution for factSheet={}: {} labels observed, {} added, "
                            + "{} enriched → ontology {} v{}",
                    factSheetId, statsByLabel.size(), added, enriched,
                    updated.getId(), updated.getVersion());
            return new Result(true, statsByLabel.size(), added, enriched, updated.getVersion());
        } catch (Exception e) {
            log.warn("Relation schema resolution failed for factSheet={} — {}", factSheetId, e.getMessage());
            return Result.unchanged(0, 0);
        }
    }

    // ── statistics over labeled edges ────────────────────────────────────────────

    private static final class LabelStats {
        int total;
        final Map<String, Integer> pairCounts = new LinkedHashMap<>();   // "src|tgt" type pair → count
        final Set<String> sourceNodes = new LinkedHashSet<>();
        final Set<String> targetNodes = new LinkedHashSet<>();
        final Set<String> directedNodePairs = new LinkedHashSet<>();     // "srcNode>tgtNode"
        int symmetricHits;
    }

    private Map<String, LabelStats> collectLabelStats(List<GraphEdge> edges,
                                                      Map<String, String> typeByNodeId) {
        Map<String, LabelStats> statsByLabel = new LinkedHashMap<>();
        if (edges == null) {
            return statsByLabel;
        }
        for (GraphEdge edge : edges) {
            if (edge == null || edge.getRelationType() == null || edge.getRelationType().isBlank()) {
                continue;
            }
            String sourceType = typeByNodeId.get(edge.getSourceNodeId());
            String targetType = typeByNodeId.get(edge.getTargetNodeId());
            if (sourceType == null || targetType == null) {
                continue; // endpoint outside the entity layer (SOURCE roots etc.)
            }
            String label = edge.getRelationType().trim();
            LabelStats stats = statsByLabel.computeIfAbsent(label, k -> new LabelStats());
            stats.total++;
            stats.pairCounts.merge(sourceType + "|" + targetType, 1, Integer::sum);
            stats.sourceNodes.add(edge.getSourceNodeId());
            stats.targetNodes.add(edge.getTargetNodeId());
            stats.directedNodePairs.add(edge.getSourceNodeId() + ">" + edge.getTargetNodeId());
        }
        // Symmetry: fraction of edges whose same-label reverse edge also exists.
        for (LabelStats stats : statsByLabel.values()) {
            for (String pair : stats.directedNodePairs) {
                int sep = pair.indexOf('>');
                String reverse = pair.substring(sep + 1) + ">" + pair.substring(0, sep);
                if (stats.directedNodePairs.contains(reverse)) {
                    stats.symmetricHits++;
                }
            }
        }
        return statsByLabel;
    }

    // ── definition building / enrichment ─────────────────────────────────────────

    private RelationshipTypeDefinition buildDefinition(String label, LabelStats stats,
                                                       Map<String, String> canonicalTypeByAlias) {
        String[] dominant = dominantPair(stats);
        RelationshipTypeDefinition definition = RelationshipTypeDefinition.builder()
                .type(label)
                .sourceEntityType(dominant != null ? canonical(dominant[0], canonicalTypeByAlias) : null)
                .targetEntityType(dominant != null ? canonical(dominant[1], canonicalTypeByAlias) : null)
                .cardinality(induceCardinality(stats))
                .description("Resolved from " + stats.total + " labeled graph edge(s).")
                .metadata(resolutionMetadata(stats))
                .build();
        return definition;
    }

    /** Fill gaps on an existing definition — human-authored values always stand. */
    private boolean enrich(RelationshipTypeDefinition definition, LabelStats stats,
                           Map<String, String> canonicalTypeByAlias) {
        boolean changed = false;
        String[] dominant = dominantPair(stats);
        if (dominant != null && isBlank(definition.getSourceEntityType())) {
            definition.setSourceEntityType(canonical(dominant[0], canonicalTypeByAlias));
            changed = true;
        }
        if (dominant != null && isBlank(definition.getTargetEntityType())) {
            definition.setTargetEntityType(canonical(dominant[1], canonicalTypeByAlias));
            changed = true;
        }
        if (definition.getCardinality() == null) {
            definition.setCardinality(induceCardinality(stats));
            changed = true;
        }
        Map<String, Object> existingMeta = definition.getMetadata();
        if (existingMeta == null || !existingMeta.containsKey("relationResolution")) {
            Map<String, Object> merged = existingMeta != null
                    ? new LinkedHashMap<>(existingMeta) : new LinkedHashMap<>();
            merged.putAll(resolutionMetadata(stats));
            definition.setMetadata(merged);
            changed = true;
        }
        return changed;
    }

    /** The dominant (sourceType, targetType) pair, or null when no pair reaches DOMINANT_SHARE. */
    private static String[] dominantPair(LabelStats stats) {
        Map.Entry<String, Integer> best = null;
        for (Map.Entry<String, Integer> e : stats.pairCounts.entrySet()) {
            if (best == null || e.getValue() > best.getValue()) {
                best = e;
            }
        }
        if (best == null || best.getValue() < stats.total * DOMINANT_SHARE) {
            return null;
        }
        int sep = best.getKey().indexOf('|');
        return new String[]{best.getKey().substring(0, sep), best.getKey().substring(sep + 1)};
    }

    private static Cardinality induceCardinality(LabelStats stats) {
        double avgOut = stats.sourceNodes.isEmpty() ? 0 : (double) stats.total / stats.sourceNodes.size();
        double avgIn = stats.targetNodes.isEmpty() ? 0 : (double) stats.total / stats.targetNodes.size();
        boolean manyOut = avgOut > MANY_THRESHOLD; // one source relates to many targets
        boolean manyIn = avgIn > MANY_THRESHOLD;   // one target is related from many sources
        if (manyOut && manyIn) {
            return Cardinality.MANY_TO_MANY;
        }
        if (manyOut) {
            return Cardinality.ONE_TO_MANY;
        }
        if (manyIn) {
            return Cardinality.MANY_TO_ONE;
        }
        return Cardinality.ONE_TO_ONE;
    }

    private static Map<String, Object> resolutionMetadata(LabelStats stats) {
        Map<String, Object> meta = new LinkedHashMap<>();
        Map<String, Object> resolution = new LinkedHashMap<>();
        resolution.put("observedEdges", stats.total);
        String[] dominant = dominantPair(stats);
        if (dominant != null) {
            resolution.put("dominantPairShare",
                    (double) stats.pairCounts.get(dominant[0] + "|" + dominant[1]) / stats.total);
        }
        resolution.put("distinctTypePairs", stats.pairCounts.size());
        double symmetricShare = stats.directedNodePairs.isEmpty()
                ? 0.0 : (double) stats.symmetricHits / stats.directedNodePairs.size();
        resolution.put("symmetricShare", symmetricShare);
        // Consumed by OwlOntologyBridge → owl:SymmetricProperty (prp-symp materializes reverse
        // edges); avgOut/avgIn of exactly 1 → owl:Functional/InverseFunctionalProperty there too.
        resolution.put("symmetric", symmetricShare >= SYMMETRIC_SHARE);
        resolution.put("avgOutDegree", stats.sourceNodes.isEmpty()
                ? 0.0 : (double) stats.total / stats.sourceNodes.size());
        resolution.put("avgInDegree", stats.targetNodes.isEmpty()
                ? 0.0 : (double) stats.total / stats.targetNodes.size());
        resolution.put("resolvedAt", Instant.now().toString());
        meta.put("relationResolution", resolution);
        return meta;
    }

    // ── entity-type canonicalization ─────────────────────────────────────────────

    /** Schema class names + aliases → canonical name, keyed by normalized token. */
    private static Map<String, String> canonicalTypeIndex(OntologySchema schema) {
        Map<String, String> index = new LinkedHashMap<>();
        if (schema.getEntityTypes() == null) {
            return index;
        }
        for (EntityTypeDefinition et : schema.getEntityTypes()) {
            if (et == null || et.getName() == null) {
                continue;
            }
            index.putIfAbsent(normalize(et.getName()), et.getName());
            if (et.getAliases() != null) {
                for (String alias : et.getAliases()) {
                    if (alias != null && !alias.isBlank()) {
                        index.putIfAbsent(normalize(alias), et.getName());
                    }
                }
            }
        }
        return index;
    }

    /** Map a raw graph {@code entity_type} onto the schema's canonical class name when known. */
    private static String canonical(String rawType, Map<String, String> canonicalTypeByAlias) {
        return canonicalTypeByAlias.getOrDefault(normalize(rawType), rawType);
    }

    private static String normalize(String s) {
        return s.trim().toUpperCase(Locale.ROOT).replaceAll("[\\s_-]+", "");
    }

    private static String entityType(GraphNode node) {
        // Declared type via the canonical reader (entity_type + camelCase variant) — deliberately
        // NOT category-first resolveEntityType: domain/range induction types relations by the
        // crawl's own declaration.
        return ai.kompile.core.graphrag.typing.GraphNodeTypes.resolveDeclaredType(node.getMetadata());
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}

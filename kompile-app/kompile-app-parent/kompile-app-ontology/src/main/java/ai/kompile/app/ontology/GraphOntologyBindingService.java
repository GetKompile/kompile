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
package ai.kompile.app.ontology;

import ai.kompile.app.web.dto.ontology.GraphConformanceReport;
import ai.kompile.core.graphrag.conformance.GraphConformanceChecker;
import ai.kompile.core.graphrag.conformance.GraphConformanceSummary;
import ai.kompile.core.graphrag.conformance.OntologyAutoProvisioner;
import ai.kompile.core.graphrag.conformance.OntologyAxiom;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.core.graphrag.format.GraphExtractionValidator;
import ai.kompile.core.graphrag.model.schema.GraphSchema;
import ai.kompile.core.graphrag.model.schema.NodeType;
import ai.kompile.core.graphrag.model.schema.PropertyType;
import ai.kompile.core.graphrag.model.schema.RelationshipType;
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityTypeDefinition;
import ai.kompile.process.ontology.FieldDefinition;
import ai.kompile.process.ontology.FieldType;
import ai.kompile.process.ontology.OntologyConformanceValidator;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.ontology.RelationshipTypeDefinition;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Bridges the knowledge graph to its governing {@link OntologySchema} — the keystone that makes the
 * ontology actually <em>validate</em> the graph instead of being a derived dead-end.
 *
 * <p>This lives in {@code kompile-app-main} because it is the only module that can see both
 * {@link ProcessEngineService} (where ontologies are persisted) and {@link KnowledgeGraphService}
 * (the graph) — those two modules are siblings. It resolves the active ontology for a fact sheet,
 * adapts each {@link GraphNode}'s metadata into a property map, and runs the store-agnostic
 * {@link OntologyConformanceValidator} (the engine built in A-1).
 *
 * <p><strong>Binding resolution</strong> (priority order): (1) an explicit binding stored on a
 * fact-sheet-scoped Lucene graph descriptor node; (2) the process-level binding, a
 * {@link ProcessDefinition} for the fact sheet that carries an {@code ontologySchemaId}
 * (APPROVED/LIVE preferred).
 *
     * <p>Scope: entity conformance (unknown type + field constraints) and relationship conformance
     * (relations not defined in the ontology + source-side cardinality) for edges that carry a semantic
     * {@code relationType}; purely structural edges (no relationType) are skipped.
 */
@Slf4j
@Service
public class GraphOntologyBindingService
        implements GraphConformanceChecker, OntologyProjectionProvider, OntologyAutoProvisioner {

    /** Cap on per-report violation detail so the response stays bounded on large graphs. */
    private static final int MAX_VIOLATIONS = 200;
    private static final String BINDING_EXTERNAL_ID = "__graph_ontology_binding__";
    private static final String ONTOLOGY_SCHEMA_ID = "ontologySchemaId";
    private static final String ONTOLOGY_VERSION = "ontologyVersion";

    private final ProcessEngineService processEngineService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final OntologyDerivationService ontologyDerivationService;
    /**
     * Serialize schema merges by the durable ontology resource, not merely by fact sheet. Multiple
     * fact sheets may intentionally share one governing ontology and must not derive competing
     * versions from the same stale base.
     */
    private final Map<String, Object> schemaMergeLocks = new ConcurrentHashMap<>();

    public GraphOntologyBindingService(ProcessEngineService processEngineService,
                                       KnowledgeGraphService knowledgeGraphService,
                                       OntologyDerivationService ontologyDerivationService) {
        this.processEngineService = processEngineService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.ontologyDerivationService = ontologyDerivationService;
    }

    /**
     * Resolve the {@link OntologySchema} that governs the given fact sheet's graph, or empty if none
     * is bound. See the class javadoc for the resolution priority.
     */
    public Optional<OntologySchema> resolveActiveOntology(Long factSheetId) {
        if (factSheetId == null) {
            return Optional.empty();
        }
        Optional<OntologySchema> explicit = resolveExplicitGraphBinding(factSheetId);
        if (explicit.isPresent()) {
            return explicit;
        }
        return resolveProcessBinding(factSheetId);
    }

    // ── OntologyProjectionProvider: lightweight projections for the extraction/reasoning pipeline ──

    @Override
    public boolean hasBoundOntology(Long factSheetId) {
        return resolveActiveOntology(factSheetId).isPresent();
    }

    @Override
    public List<String> allowedEntityTypes(Long factSheetId) {
        OntologySchema schema = resolveActiveOntology(factSheetId).orElse(null);
        if (schema == null || schema.getEntityTypes() == null) {
            return List.of();
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (EntityTypeDefinition entity : schema.getEntityTypes()) {
            if (entity == null) continue;
            if (hasText(entity.getName())) allowed.add(entity.getName());
            if (entity.getAliases() != null) entity.getAliases().stream()
                    .filter(GraphOntologyBindingService::hasText).forEach(allowed::add);
        }
        return List.copyOf(allowed);
    }

    @Override
    public List<String> allowedRelationshipTypes(Long factSheetId) {
        OntologySchema schema = resolveActiveOntology(factSheetId).orElse(null);
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        LinkedHashSet<String> allowed = new LinkedHashSet<>();
        for (RelationshipTypeDefinition relationship : schema.getRelationshipTypes()) {
            if (relationship == null) continue;
            if (hasText(relationship.getType())) allowed.add(relationship.getType());
            if (hasText(relationship.getCanonicalType())) allowed.add(relationship.getCanonicalType());
            if (relationship.getObservedTypes() != null) relationship.getObservedTypes().stream()
                    .filter(GraphOntologyBindingService::hasText).forEach(allowed::add);
        }
        return List.copyOf(allowed);
    }

    @Override
    public List<OntologyAxiom> ontologyAxioms(Long factSheetId) {
        OntologySchema schema = resolveActiveOntology(factSheetId).orElse(null);
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        List<OntologyAxiom> axioms = new ArrayList<>();
        for (RelationshipTypeDefinition rel : schema.getRelationshipTypes()) {
            if (rel == null || rel.getType() == null || rel.getType().isBlank()) {
                continue;
            }
            LinkedHashSet<String> predicates = new LinkedHashSet<>();
            predicates.add(rel.getType());
            if (hasText(rel.getCanonicalType())) predicates.add(rel.getCanonicalType());
            if (rel.getObservedTypes() != null) rel.getObservedTypes().stream()
                    .filter(GraphOntologyBindingService::hasText).forEach(predicates::add);
            for (String predicate : predicates) {
                if (hasText(rel.getSourceEntityType())) {
                    axioms.add(new OntologyAxiom(
                            OntologyAxiom.Kind.DOMAIN, predicate, rel.getSourceEntityType()));
                }
                if (hasText(rel.getTargetEntityType())) {
                    axioms.add(new OntologyAxiom(
                            OntologyAxiom.Kind.RANGE, predicate, rel.getTargetEntityType()));
                }
            }
        }
        return axioms;
    }

    /**
     * Validate every ENTITY node in the fact sheet against its bound ontology.
     *
     * @return a {@link GraphConformanceReport}; {@code ontologyBound=false} when nothing is bound
     */
    public GraphConformanceReport checkConformance(Long factSheetId) {
        Optional<OntologySchema> bound = resolveActiveOntology(factSheetId);
        if (bound.isEmpty()) {
            return GraphConformanceReport.notBound(factSheetId);
        }
        OntologySchema schema = bound.get();

        final int pageSize = 1_000;
        int entitiesChecked = 0;
        int unknown = 0;
        int nonConformant = 0;
        List<GraphConformanceReport.NodeViolation> violations = new java.util.ArrayList<>();

        int nodeCursor = 0;
        KnowledgeGraphService.GraphPage<GraphNode> nodePage;
        do {
            nodePage = knowledgeGraphService.getNodesInFactSheetPage(factSheetId, nodeCursor, pageSize);
            for (GraphNode node : nodePage.items()) {
                if (node.getNodeType() != NodeLevel.ENTITY) {
                    continue;
                }
                entitiesChecked++;
                String entityType = extractEntityType(node);
                OntologyConformanceValidator.EntityConformance result =
                        OntologyConformanceValidator.validateEntity(schema, entityType, node.getMetadata());
                if (result.unknownType()) {
                    unknown++;
                }
                if (!result.conformant()) {
                    nonConformant++;
                    if (violations.size() < MAX_VIOLATIONS) {
                        violations.add(new GraphConformanceReport.NodeViolation(
                                node.getNodeId(), node.getTitle(), entityType,
                                result.unknownType(), result.violations()));
                    }
                }
            }
            nodeCursor = nodePage.nextCursor();
        } while (nodePage.hasMore());

        int edgesChecked = 0;
        int nonConformantEdges = 0;
        List<GraphConformanceReport.EdgeViolation> edgeViolations = new java.util.ArrayList<>();
        java.util.Map<String, Long> outgoing = new java.util.HashMap<>();
        java.util.Map<String, OntologyConformanceValidator.RelationshipConformance> cardinalities =
                new java.util.HashMap<>();
        java.util.Map<String, GraphConformanceReport.EdgeViolation> cardinalitySamples =
                new java.util.HashMap<>();

        int edgeCursor = 0;
        KnowledgeGraphService.GraphPage<GraphEdge> edgePage;
        do {
            edgePage = knowledgeGraphService.getEdgesInFactSheetPage(factSheetId, edgeCursor, pageSize);
            java.util.Set<String> endpointIds = new java.util.HashSet<>();
            for (GraphEdge edge : edgePage.items()) {
                if (isSemanticEdge(edge)) {
                    endpointIds.add(edge.getSourceNode().getNodeId());
                    endpointIds.add(edge.getTargetNode().getNodeId());
                }
            }
            java.util.Map<String, GraphNode> nodeById = new java.util.HashMap<>();
            for (GraphNode node : knowledgeGraphService.getNodesByIds(new java.util.ArrayList<>(endpointIds))) {
                if (node != null && node.getNodeId() != null) {
                    nodeById.put(node.getNodeId(), node);
                }
            }
            for (GraphEdge edge : edgePage.items()) {
                if (!isSemanticEdge(edge)) {
                    continue;
                }
                edgesChecked++;
                String relType = edge.getRelationType();
                String sourceType = extractEntityType(edge.resolvedSourceNode(nodeById));
                String targetType = extractEntityType(edge.resolvedTargetNode(nodeById));
                OntologyConformanceValidator.RelationshipConformance rc =
                        OntologyConformanceValidator.validateRelationship(schema, sourceType, relType, targetType);
                if (!rc.allowed()) {
                    nonConformantEdges++;
                    if (edgeViolations.size() < MAX_VIOLATIONS) {
                        edgeViolations.add(new GraphConformanceReport.EdgeViolation(
                                edge.getEdgeId(), relType, sourceType, targetType, rc.reason()));
                    }
                } else if (rc.cardinality() != null) {
                    String key = edge.getSourceNode().getNodeId() + "::" + relType;
                    outgoing.merge(key, 1L, Long::sum);
                    cardinalities.putIfAbsent(key, rc);
                    cardinalitySamples.putIfAbsent(key, new GraphConformanceReport.EdgeViolation(
                            edge.getEdgeId(), relType, sourceType, targetType, ""));
                }
            }
            edgeCursor = edgePage.nextCursor();
        } while (edgePage.hasMore());

        for (java.util.Map.Entry<String, Long> entry : outgoing.entrySet()) {
            OntologyConformanceValidator.RelationshipConformance rc = cardinalities.get(entry.getKey());
            if (!OntologyConformanceValidator.withinSourceCardinality(rc.cardinality(), entry.getValue())) {
                nonConformantEdges++;
                if (edgeViolations.size() < MAX_VIOLATIONS) {
                    GraphConformanceReport.EdgeViolation sample = cardinalitySamples.get(entry.getKey());
                    edgeViolations.add(new GraphConformanceReport.EdgeViolation(
                            sample.edgeId(), sample.relationshipType(), sample.sourceType(), sample.targetType(),
                            "Cardinality " + rc.cardinality() + " violated: source has " + entry.getValue()
                                    + " outgoing '" + sample.relationshipType() + "' edges"));
                }
            }
        }

        Double score = conformanceScore(entitiesChecked, nonConformant);
        String message = String.format(
                "Checked %d ENTITY node(s) against ontology '%s' v%d: %d non-conformant (%d unknown type); "
                        + "conformance %.1f%%. Checked %d relationship(s): %d non-conformant.",
                entitiesChecked, schema.getName(), schema.getVersion(), nonConformant, unknown,
                (score == null ? 1.0 : score) * 100.0, edgesChecked, nonConformantEdges);
        log.info("Graph conformance factSheet={}: {}", factSheetId, message);
        return new GraphConformanceReport(factSheetId, true, schema.getId(), schema.getVersion(),
                schema.getName(), entitiesChecked, unknown, nonConformant, score, violations,
                edgesChecked, nonConformantEdges, edgeViolations, message);
    }

    /** True if the edge carries a semantic relationType and has both endpoints (so it can be validated). */
    private static boolean isSemanticEdge(GraphEdge edge) {
        return edge.getRelationType() != null && !edge.getRelationType().isBlank()
                && edge.getSourceNode() != null && edge.getTargetNode() != null;
    }

    /**
     * Fraction (0..1) of checked entities that conform, rounded to 4 dp. An empty graph is vacuously
     * conformant (1.0). Feeds the graph-health time series (Phase 7).
     */
    private static Double conformanceScore(int checked, int nonConformant) {
        if (checked <= 0) {
            return 1.0;
        }
        double frac = (double) (checked - nonConformant) / checked;
        return Math.round(frac * 10000.0) / 10000.0;
    }

    /**
     * {@link GraphConformanceChecker} SPI: exposes {@link #checkConformance} as an ontology-model-free
     * {@link GraphConformanceSummary} so the knowledge-graph layer (maintenance / write hooks) can
     * trigger conformance without depending on the OntologySchema model.
     */
    @Override
    public GraphConformanceSummary checkFactSheet(Long factSheetId) {
        GraphConformanceReport report = checkConformance(factSheetId);
        return new GraphConformanceSummary(report.factSheetId(), report.ontologyBound(),
                report.ontologyName(), report.entitiesChecked(), report.unknownTypeCount(),
                report.nonConformantCount(), report.conformanceScore(), report.message());
    }

    // ── binding management ───────────────────────────────────────────────────────

    /**
     * Bind an ontology to a fact sheet's graph. The binding is stored as a scoped CUSTOM descriptor
     * node, so it lives in the same Lucene graph index as every other graph artifact and participates
     * in the normal graph export/import path.
     */
    public OntologyBinding bindOntology(Long factSheetId, String ontologySchemaId, Integer ontologyVersion) {
        if (factSheetId == null) {
            throw new IllegalArgumentException("factSheetId is required");
        }
        if (ontologySchemaId == null || ontologySchemaId.isBlank()) {
            throw new IllegalArgumentException("ontologySchemaId is required");
        }
        OntologySchema ontology = loadOntology(ontologySchemaId, ontologyVersion)
                .orElseThrow(() -> new IllegalArgumentException("No ontology found for id=" + ontologySchemaId
                        + (ontologyVersion != null ? " v" + ontologyVersion : " (latest)")));
        int resolvedVersion = ontologyVersion != null ? ontologyVersion : ontology.getVersion();
        Map<String, Object> metadata = new HashMap<>();
        metadata.put(ONTOLOGY_SCHEMA_ID, ontologySchemaId);
        metadata.put(ONTOLOGY_VERSION, resolvedVersion);
        GraphNode descriptor = knowledgeGraphService
                .getNodeByExternalIdInFactSheet(BINDING_EXTERNAL_ID, NodeLevel.CUSTOM, factSheetId)
                .map(existing -> knowledgeGraphService.updateNode(existing.getNodeId(), existing.getTitle(),
                        existing.getDescription(), metadata))
                .orElseGet(() -> knowledgeGraphService.createNode(NodeLevel.CUSTOM, BINDING_EXTERNAL_ID,
                        "Graph ontology binding", "Governing ontology for this fact-sheet graph",
                        metadata, factSheetId));
        log.info("Bound ontology {} v{} to factSheet={} in Lucene graph descriptor {}",
                ontologySchemaId, resolvedVersion, factSheetId, descriptor.getNodeId());
        return new OntologyBinding(factSheetId, descriptor.getNodeId(), ontologySchemaId, resolvedVersion);
    }

    public record OntologyBinding(Long factSheetId, String descriptorNodeId,
                                  String ontologySchemaId, Integer ontologyVersion) {}

    /**
     * Ensure the fact sheet's graph has a governing ontology so OWL/PSL enrichment is not inert.
     * Idempotent: returns the already-bound ontology when present; otherwise derives a deterministic
     * <b>structural</b> ontology (no LLM) from the crawled graph, persists it as a draft, and binds it.
     * Best-effort — any failure (empty graph, persistence/binding error) logs and returns empty rather
     * than breaking the enrichment pass.
     */
    public Optional<OntologySchema> autoProvisionStructuralOntology(Long factSheetId) {
        if (factSheetId == null) {
            return Optional.empty();
        }
        Optional<OntologySchema> existing = resolveActiveOntology(factSheetId);
        if (existing.isPresent()) {
            return existing;
        }
        try {
            OntologySchema draft = ontologyDerivationService.deriveStructuralDraft(factSheetId);
            OntologySchema saved = processEngineService.createOntology(draft);
            bindOntology(factSheetId, saved.getId(), saved.getVersion());
            log.info("Auto-provisioned + bound structural ontology {} v{} for factSheet={} ({} entity types, {} relationships)",
                    saved.getId(), saved.getVersion(), factSheetId,
                    saved.getEntityTypes() == null ? 0 : saved.getEntityTypes().size(),
                    saved.getRelationshipTypes() == null ? 0 : saved.getRelationshipTypes().size());
            return Optional.of(saved);
        } catch (RuntimeException e) {
            log.warn("Auto-provision of structural ontology failed for factSheet={}: {}", factSheetId, e.toString());
            return Optional.empty();
        }
    }

    /**
     * Merge the exact frozen crawl schema into the durable governing ontology before OWL runs.
     * This preserves authoritative zero-instance types and relationship declarations that cannot be
     * reconstructed by sampling graph facts. Connection families remain metadata facets and are not
     * mapped to OWL {@code subPropertyOf}.
     */
    public Optional<OntologySchema> mergeFrozenGraphSchema(
            Long factSheetId, GraphSchema graphSchema) {
        if (factSheetId == null || graphSchema == null) {
            return autoProvisionStructuralOntology(factSheetId);
        }
        synchronized (ontologyMutationLock(factSheetId)) {
            return mergeFrozenGraphSchemaLocked(factSheetId, graphSchema);
        }
    }

    /**
     * Run a complete crawl ontology transaction under the same resource lock as the frozen-schema
     * merge. The post-OWL type/relation induction stages create further ontology versions and must not
     * interleave when fact sheets share one governing ontology.
     */
    public void withOntologyMutationLock(Long factSheetId, Runnable mutation) {
        Objects.requireNonNull(mutation, "mutation");
        synchronized (ontologyMutationLock(factSheetId)) {
            mutation.run();
        }
    }

    private Object ontologyMutationLock(Long factSheetId) {
        Optional<OntologySchema> active = resolveActiveOntology(factSheetId);
        String lockKey = active.filter(schema -> hasText(schema.getId()))
                .map(schema -> "ontology:" + schema.getId())
                .orElse("fact-sheet:" + factSheetId);
        return schemaMergeLocks.computeIfAbsent(lockKey, ignored -> new Object());
    }

    private Optional<OntologySchema> mergeFrozenGraphSchemaLocked(
            Long factSheetId, GraphSchema graphSchema) {
        try {
            Optional<OntologySchema> active = resolveActiveOntology(factSheetId)
                    .map(this::latestOntologyVersion);
            OntologySchema base;
            if (active.isPresent()) {
                base = copyOntology(active.get());
            } else {
                try {
                    base = ontologyDerivationService.deriveStructuralDraft(factSheetId);
                } catch (RuntimeException noObservedGraphSchema) {
                    base = OntologySchema.builder()
                            .name("Crawl graph schema " + factSheetId)
                            .updatedBy("crawl-schema-prepass")
                            .build();
                }
            }

            List<EntityTypeDefinition> entities = mergeEntityDefinitions(
                    base.getEntityTypes(), graphSchema);
            Map<String, String> canonicalEntityNames = entityCanonicalIndex(entities);
            List<RelationshipTypeDefinition> relationships = mergeRelationshipDefinitions(
                    base.getRelationshipTypes(), graphSchema, canonicalEntityNames,
                    entityParentIndex(entities));
            Map<String, Object> metadata = base.getMetadata() == null
                    ? new LinkedHashMap<>() : new LinkedHashMap<>(base.getMetadata());
            metadata.put("crawlGraphSchemaIntegrated", true);
            metadata.put("crawlGraphSchemaNodeTypes", entities.size());
            metadata.put("crawlGraphSchemaRelationshipTypes", relationships.size());

            boolean changed = !Objects.equals(base.getEntityTypes(), entities)
                    || !Objects.equals(base.getRelationshipTypes(), relationships)
                    || !Objects.equals(base.getMetadata(), metadata);
            base.setEntityTypes(entities);
            base.setRelationshipTypes(relationships.isEmpty() ? List.of() : relationships);
            base.setMetadata(metadata);
            base.setUpdatedBy("crawl-schema-prepass");

            OntologySchema persisted;
            if (active.isEmpty()) {
                persisted = processEngineService.createOntology(base);
            } else if (changed) {
                persisted = processEngineService.updateOntology(active.get().getId(), base);
            } else {
                bindOntology(factSheetId, active.get().getId(), active.get().getVersion());
                return active;
            }
            bindOntology(factSheetId, persisted.getId(), persisted.getVersion());
            return Optional.of(persisted);
        } catch (RuntimeException e) {
            log.warn("Frozen graph schema merge failed for factSheet={}: {}", factSheetId, e.toString());
            return Optional.empty();
        }
    }

    private static List<EntityTypeDefinition> mergeEntityDefinitions(
            List<EntityTypeDefinition> existing, GraphSchema graphSchema) {
        List<EntityTypeDefinition> merged = existing == null
                ? new ArrayList<>()
                : existing.stream().filter(Objects::nonNull)
                        .map(GraphOntologyBindingService::copyEntity).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Map<String, EntityTypeDefinition> index = entityDefinitionIndex(merged);
        Map<String, String> canonicalByRaw = new LinkedHashMap<>();
        if (graphSchema.getNodeTypes() != null) {
            for (NodeType node : graphSchema.getNodeTypes()) {
                if (node == null || !hasText(node.getLabel())) continue;
                String raw = node.getLabel().trim();
                EntityTypeDefinition definition = index.get(normalize(raw));
                if (definition == null) {
                    definition = EntityTypeDefinition.builder()
                            .name(raw)
                            .description(node.getDescription())
                            .aliases(List.of())
                            .confidence(1.0d)
                            .fields(toFields(node.getProperties()))
                            .build();
                    merged.add(definition);
                    index.put(normalize(raw), definition);
                } else {
                    if (!hasText(definition.getDescription()) && hasText(node.getDescription())) {
                        definition.setDescription(node.getDescription());
                    }
                    definition.setAliases(mergeStrings(definition.getAliases(),
                            definition.getName().equalsIgnoreCase(raw) ? List.of() : List.of(raw)));
                    definition.setFields(mergeFields(definition.getFields(), toFields(node.getProperties())));
                }
                canonicalByRaw.put(normalize(raw), definition.getName());
            }
            canonicalByRaw.clear();
            canonicalByRaw.putAll(entityCanonicalIndex(merged));
            for (NodeType node : graphSchema.getNodeTypes()) {
                if (node == null || !hasText(node.getLabel()) || !hasText(node.getParentType())) continue;
                EntityTypeDefinition child = index.get(normalize(node.getLabel()));
                String parent = canonicalByRaw.getOrDefault(
                        normalize(node.getParentType()), node.getParentType().trim());
                if (child != null && !child.getName().equalsIgnoreCase(parent)) {
                    String existingParent = canonicalByRaw.getOrDefault(
                            normalize(child.getParentType()), child.getParentType());
                    if (hasText(existingParent)
                            && !normalize(existingParent).equals(normalize(parent))) {
                        throw new IllegalArgumentException("Entity type '" + child.getName()
                                + "' already has parent '" + child.getParentType()
                                + "'; crawl schema proposed conflicting parent '" + parent + "'");
                    }
                    if (!hasText(child.getParentType())) child.setParentType(parent);
                }
            }
        }
        return List.copyOf(merged);
    }

    private static List<RelationshipTypeDefinition> mergeRelationshipDefinitions(
            List<RelationshipTypeDefinition> existing,
            GraphSchema graphSchema,
            Map<String, String> canonicalEntityNames,
            Map<String, String> entityParents) {
        List<RelationshipTypeDefinition> merged = existing == null
                ? new ArrayList<>()
                : existing.stream().filter(Objects::nonNull)
                        .map(GraphOntologyBindingService::copyRelationship).collect(java.util.stream.Collectors.toCollection(ArrayList::new));
        Map<String, RelationshipTypeDefinition> byType = new LinkedHashMap<>();
        merged.forEach(definition -> indexRelationshipDefinition(byType, definition));
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        Map<String, Set<String>> targets = new LinkedHashMap<>();
        Map<String, Set<String>> patternsByType = new LinkedHashMap<>();
        if (graphSchema.getPatterns() != null) {
            graphSchema.getPatterns().forEach(pattern ->
                    GraphExtractionValidator.parseRelationPattern(pattern).ifPresent(signature -> {
                        String relationKey = normalize(signature.relationType());
                        sources.computeIfAbsent(relationKey, ignored -> new LinkedHashSet<>())
                                .add(canonicalEntityNames.getOrDefault(normalize(signature.sourceType()), signature.sourceType()));
                        targets.computeIfAbsent(relationKey, ignored -> new LinkedHashSet<>())
                                .add(canonicalEntityNames.getOrDefault(normalize(signature.targetType()), signature.targetType()));
                        patternsByType.computeIfAbsent(relationKey, ignored -> new LinkedHashSet<>())
                                .add(signature.expression());
                    }));
        }
        if (graphSchema.getRelationshipTypes() != null) {
            for (RelationshipType relation : graphSchema.getRelationshipTypes()) {
                if (relation == null || !hasText(relation.getType())) continue;
                String key = normalize(relation.getType());
                RelationshipTypeDefinition definition = findRelationshipDefinition(byType, relation);
                if (definition == null) {
                    definition = RelationshipTypeDefinition.builder()
                            .type(relation.getType().trim())
                            .description(relation.getDescription())
                            .observedTypes(relation.getAliases())
                            .sourceEntityType(singleValue(sources.get(key)))
                            .targetEntityType(singleValue(targets.get(key)))
                            .metadata(relationshipMetadata(null, relation.getConnectionFamily(),
                                    sources.get(key), targets.get(key), patternsByType.get(key)))
                            .build();
                    merged.add(definition);
                    indexRelationshipDefinition(byType, definition);
                } else {
                    if (!hasText(definition.getDescription()) && hasText(relation.getDescription())) {
                        definition.setDescription(relation.getDescription());
                    }
                    List<String> observed = relation.getAliases();
                    if (!definition.getType().equalsIgnoreCase(relation.getType())) {
                        observed = mergeStrings(observed, List.of(relation.getType()));
                    }
                    definition.setObservedTypes(mergeStrings(
                            definition.getObservedTypes(), observed));
                    definition.setSourceEntityType(mergeEndpointConstraint(
                            definition.getType(), "source", definition.getSourceEntityType(),
                            sources.get(key), canonicalEntityNames, entityParents));
                    definition.setTargetEntityType(mergeEndpointConstraint(
                            definition.getType(), "target", definition.getTargetEntityType(),
                            targets.get(key), canonicalEntityNames, entityParents));
                    definition.setMetadata(relationshipMetadata(
                            definition.getMetadata(), relation.getConnectionFamily(),
                            sources.get(key), targets.get(key), patternsByType.get(key)));
                }
            }
        }
        return List.copyOf(merged);
    }

    private static void indexRelationshipDefinition(
            Map<String, RelationshipTypeDefinition> index,
            RelationshipTypeDefinition definition) {
        if (definition == null) return;
        if (hasText(definition.getType())) {
            index.putIfAbsent(normalize(definition.getType()), definition);
        }
        if (hasText(definition.getCanonicalType())) {
            index.putIfAbsent(normalize(definition.getCanonicalType()), definition);
        }
        if (definition.getObservedTypes() != null) {
            definition.getObservedTypes().stream()
                    .filter(GraphOntologyBindingService::hasText)
                    .forEach(alias -> index.putIfAbsent(normalize(alias), definition));
        }
    }

    private static RelationshipTypeDefinition findRelationshipDefinition(
            Map<String, RelationshipTypeDefinition> index, RelationshipType relation) {
        LinkedHashSet<RelationshipTypeDefinition> matches = new LinkedHashSet<>();
        RelationshipTypeDefinition primary = index.get(normalize(relation.getType()));
        if (primary != null) matches.add(primary);
        if (relation.getAliases() != null) {
            relation.getAliases().stream().filter(GraphOntologyBindingService::hasText)
                    .map(GraphOntologyBindingService::normalize)
                    .map(index::get).filter(Objects::nonNull).forEach(matches::add);
        }
        if (matches.size() > 1) {
            throw new IllegalArgumentException("Relationship aliases for '" + relation.getType()
                    + "' resolve to multiple ontology relationship definitions");
        }
        return matches.stream().findFirst().orElse(null);
    }

    private static Map<String, EntityTypeDefinition> entityDefinitionIndex(
            List<EntityTypeDefinition> definitions) {
        Map<String, EntityTypeDefinition> index = new LinkedHashMap<>();
        for (EntityTypeDefinition definition : definitions) {
            if (definition == null || !hasText(definition.getName())) continue;
            index.putIfAbsent(normalize(definition.getName()), definition);
            if (definition.getAliases() != null) {
                definition.getAliases().stream().filter(GraphOntologyBindingService::hasText)
                        .forEach(alias -> index.putIfAbsent(normalize(alias), definition));
            }
        }
        return index;
    }

    private static Map<String, String> entityCanonicalIndex(List<EntityTypeDefinition> definitions) {
        Map<String, String> index = new LinkedHashMap<>();
        entityDefinitionIndex(definitions).forEach((key, value) -> index.put(key, value.getName()));
        return index;
    }

    private static Map<String, String> entityParentIndex(List<EntityTypeDefinition> definitions) {
        Map<String, String> parents = new LinkedHashMap<>();
        Map<String, String> canonical = entityCanonicalIndex(definitions);
        for (EntityTypeDefinition definition : definitions) {
            if (definition == null || !hasText(definition.getName()) || !hasText(definition.getParentType())) {
                continue;
            }
            String parent = canonical.getOrDefault(
                    normalize(definition.getParentType()), definition.getParentType());
            parents.put(normalize(definition.getName()), normalize(parent));
        }
        return parents;
    }

    private static EntityTypeDefinition copyEntity(EntityTypeDefinition source) {
        return EntityTypeDefinition.builder()
                .name(source.getName()).description(source.getDescription())
                .aliases(source.getAliases() == null ? null : List.copyOf(source.getAliases()))
                .localizedLabels(source.getLocalizedLabels() == null ? null : Map.copyOf(source.getLocalizedLabels()))
                .classification(source.getClassification()).templateSource(source.getTemplateSource())
                .templateVersion(source.getTemplateVersion()).confidence(source.getConfidence())
                .fields(source.getFields() == null ? null : new ArrayList<>(source.getFields()))
                .rules(source.getRules() == null ? null : new ArrayList<>(source.getRules()))
                .provenance(source.getProvenance() == null ? null : new ArrayList<>(source.getProvenance()))
                .changeHistory(source.getChangeHistory() == null ? null : new ArrayList<>(source.getChangeHistory()))
                .parentType(source.getParentType()).build();
    }

    private static RelationshipTypeDefinition copyRelationship(RelationshipTypeDefinition source) {
        return RelationshipTypeDefinition.builder()
                .type(source.getType()).sourceEntityType(source.getSourceEntityType())
                .targetEntityType(source.getTargetEntityType()).description(source.getDescription())
                .cardinality(source.getCardinality()).canonicalType(source.getCanonicalType())
                .observedTypes(source.getObservedTypes() == null ? null : List.copyOf(source.getObservedTypes()))
                .inverseTypes(source.getInverseTypes() == null ? null : List.copyOf(source.getInverseTypes()))
                .actionCategories(source.getActionCategories() == null ? null : List.copyOf(source.getActionCategories()))
                .controlSignatures(source.getControlSignatures() == null ? null : List.copyOf(source.getControlSignatures()))
                .policyMetadata(source.getPolicyMetadata() == null ? null : Map.copyOf(source.getPolicyMetadata()))
                .flipWhenSwapped(source.getFlipWhenSwapped())
                .emitAlreadyCanonical(source.getEmitAlreadyCanonical())
                .metadata(source.getMetadata() == null ? null : new LinkedHashMap<>(source.getMetadata()))
                .transitive(source.isTransitive()).build();
    }

    private static OntologySchema copyOntology(OntologySchema source) {
        return OntologySchema.builder().id(source.getId()).name(source.getName())
                .version(source.getVersion()).templateId(source.getTemplateId())
                .createdAt(source.getCreatedAt()).updatedAt(source.getUpdatedAt())
                .updatedBy(source.getUpdatedBy())
                .entityTypes(source.getEntityTypes()).relationshipTypes(source.getRelationshipTypes())
                .globalRules(source.getGlobalRules()).metadata(source.getMetadata()).build();
    }

    private static List<FieldDefinition> toFields(List<PropertyType> properties) {
        if (properties == null) return null;
        return properties.stream().filter(Objects::nonNull)
                .filter(property -> hasText(property.getName()))
                .map(property -> FieldDefinition.builder().name(property.getName())
                        .type(toFieldType(property.getType())).build()).toList();
    }

    private static FieldType toFieldType(String type) {
        if (!hasText(type)) return FieldType.STRING;
        return switch (normalize(type)) {
            case "INTEGER" -> FieldType.INTEGER;
            case "DECIMAL" -> FieldType.DECIMAL;
            case "BOOLEAN" -> FieldType.BOOLEAN;
            case "DATE", "YEAR", "YEARMONTH" -> FieldType.DATE;
            case "DATETIME" -> FieldType.DATETIME;
            default -> FieldType.STRING;
        };
    }

    private static List<FieldDefinition> mergeFields(
            List<FieldDefinition> existing, List<FieldDefinition> additions) {
        if (existing == null && additions == null) return null;
        Map<String, FieldDefinition> merged = new LinkedHashMap<>();
        if (existing != null) existing.stream().filter(Objects::nonNull)
                .filter(field -> hasText(field.getName()))
                .forEach(field -> merged.putIfAbsent(normalize(field.getName()), field));
        if (additions != null) additions.stream().filter(Objects::nonNull)
                .filter(field -> hasText(field.getName()))
                .forEach(field -> merged.putIfAbsent(normalize(field.getName()), field));
        return List.copyOf(merged.values());
    }

    private static List<String> mergeStrings(List<String> first, List<String> second) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (first != null) first.stream().filter(GraphOntologyBindingService::hasText)
                .map(String::trim).forEach(values::add);
        if (second != null) second.stream().filter(GraphOntologyBindingService::hasText)
                .map(String::trim).forEach(values::add);
        return List.copyOf(values);
    }

    private static Map<String, Object> relationshipMetadata(
            Map<String, Object> existing,
            String family,
            Set<String> sources,
            Set<String> targets,
            Set<String> patterns) {
        Map<String, Object> metadata = existing == null
                ? new LinkedHashMap<>() : new LinkedHashMap<>(existing);
        Object existingFamily = metadata.get("connectionFamily");
        if (hasText(family) && existingFamily != null && hasText(existingFamily.toString())
                && !normalize(family).equals(normalize(existingFamily.toString()))) {
            throw new IllegalArgumentException("Relationship connection family conflict: existing='"
                    + existingFamily + "', proposed='" + family + "'");
        }
        if (hasText(family) && existingFamily == null) metadata.put("connectionFamily", family.trim());
        mergeMetadataStrings(metadata, "sourceEntityTypes", sources);
        mergeMetadataStrings(metadata, "targetEntityTypes", targets);
        mergeMetadataStrings(metadata, "endpointPatterns", patterns);
        return metadata.isEmpty() ? null : metadata;
    }

    private static void mergeMetadataStrings(
            Map<String, Object> metadata, String key, Set<String> additions) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        Object current = metadata.get(key);
        if (current instanceof Iterable<?> iterable) {
            for (Object value : iterable) {
                if (value != null && hasText(value.toString())) values.add(value.toString().trim());
            }
        } else if (current != null && hasText(current.toString())) {
            values.add(current.toString().trim());
        }
        if (additions != null) {
            additions.stream().filter(GraphOntologyBindingService::hasText)
                    .map(String::trim).forEach(values::add);
        }
        if (!values.isEmpty()) metadata.put(key, List.copyOf(values));
    }

    private static String mergeEndpointConstraint(
            String relationType, String role, String existing, Set<String> proposed,
            Map<String, String> canonicalEntityNames, Map<String, String> entityParents) {
        if (proposed == null || proposed.isEmpty()) return existing;
        if (!hasText(existing)) return singleValue(proposed);
        boolean compatible = proposed.stream().filter(GraphOntologyBindingService::hasText)
                .allMatch(value -> isSameOrSubtype(
                        canonicalEntityNames.getOrDefault(normalize(value), value),
                        canonicalEntityNames.getOrDefault(normalize(existing), existing),
                        entityParents));
        if (!compatible) {
            throw new IllegalArgumentException("Relationship '" + relationType + "' already has "
                    + role + " entity type '" + existing + "'; crawl schema proposed " + proposed);
        }
        return existing;
    }

    private static boolean isSameOrSubtype(
            String actual, String expected, Map<String, String> parents) {
        String current = normalize(actual);
        String target = normalize(expected);
        LinkedHashSet<String> visited = new LinkedHashSet<>();
        while (visited.add(current)) {
            if (current.equals(target)) return true;
            current = parents.get(current);
            if (current == null) return false;
        }
        return false;
    }

    /** Re-read the latest version after acquiring the ontology-scoped merge lock. */
    private OntologySchema latestOntologyVersion(OntologySchema resolved) {
        if (resolved == null || !hasText(resolved.getId())) return resolved;
        try {
            return processEngineService.listOntologies().stream()
                    .filter(candidate -> candidate != null && resolved.getId().equals(candidate.getId()))
                    .max(Comparator.comparingInt(OntologySchema::getVersion))
                    .orElse(resolved);
        } catch (RuntimeException e) {
            log.debug("Could not refresh latest ontology {} before schema merge: {}",
                    resolved.getId(), e.getMessage());
            return resolved;
        }
    }

    private static String singleValue(Set<String> values) {
        return values != null && values.size() == 1 ? values.iterator().next() : null;
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toUpperCase(Locale.ROOT)
                .replaceAll("[^A-Z0-9]+", "");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /** {@link OntologyAutoProvisioner} SPI — the crawl's deriveOntology enrichment step calls this. */
    @Override
    public void provisionOntology(long factSheetId) {
        autoProvisionStructuralOntology(factSheetId);
    }

    @Override
    public void provisionOntology(long factSheetId, GraphSchema graphSchema) {
        mergeFrozenGraphSchema(factSheetId, graphSchema);
    }

    /** Remove the Lucene graph descriptor that carries the explicit ontology binding. */
    public void unbindOntology(Long factSheetId) {
        if (factSheetId == null) {
            return;
        }
        knowledgeGraphService
                .getNodeByExternalIdInFactSheet(BINDING_EXTERNAL_ID, NodeLevel.CUSTOM, factSheetId)
                .ifPresent(node -> knowledgeGraphService.deleteNode(node.getNodeId()));
    }

    // ── binding resolution ─────────────────────────────────────────────────────

    /** Resolve the explicit binding from the fact-sheet-scoped Lucene graph descriptor. */
    private Optional<OntologySchema> resolveExplicitGraphBinding(Long factSheetId) {
        try {
            return knowledgeGraphService
                    .getNodeByExternalIdInFactSheet(BINDING_EXTERNAL_ID, NodeLevel.CUSTOM, factSheetId)
                    .flatMap(node -> {
                        Map<String, Object> metadata = node.getMetadata();
                        if (metadata == null) {
                            return Optional.empty();
                        }
                        Object id = metadata.get(ONTOLOGY_SCHEMA_ID);
                        if (!(id instanceof String ontologyId) || ontologyId.isBlank()) {
                            return Optional.empty();
                        }
                        Object rawVersion = metadata.get(ONTOLOGY_VERSION);
                        Integer version = rawVersion instanceof Number number ? number.intValue() : null;
                        return loadOntology(ontologyId, version);
                    });
        } catch (Exception e) {
            log.warn("Could not resolve Lucene graph ontology binding for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Priority 2: a {@link ProcessDefinition} bound to this fact sheet that carries an ontology,
     * preferring LIVE/APPROVED definitions.
     */
    private Optional<OntologySchema> resolveProcessBinding(Long factSheetId) {
        List<ProcessDefinition> definitions;
        try {
            definitions = processEngineService.listProcessDefinitions();
        } catch (Exception e) {
            log.warn("Could not list process definitions while resolving ontology for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
        if (definitions == null) {
            return Optional.empty();
        }
        return definitions.stream()
                .filter(d -> factSheetId.equals(d.getFactSheetId()))
                .filter(d -> d.getOntologySchemaId() != null && !d.getOntologySchemaId().isBlank())
                .sorted(Comparator.comparingInt(d -> statusRank(d.getStatus())))
                .map(d -> loadOntology(d.getOntologySchemaId(),
                        d.getOntologyVersion() > 0 ? d.getOntologyVersion() : null))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
    }

    /** Load an ontology by id; a null version resolves to the latest (via {@code listOntologies}). */
    private Optional<OntologySchema> loadOntology(String id, Integer version) {
        if (id == null) {
            return Optional.empty();
        }
        try {
            if (version != null) {
                return Optional.ofNullable(processEngineService.getOntology(id, version));
            }
            return processEngineService.listOntologies().stream()
                    .filter(o -> id.equals(o.getId()))
                    .findFirst();
        } catch (Exception e) {
            log.warn("Could not load ontology id={} v={}: {}", id, version, e.getMessage());
            return Optional.empty();
        }
    }

    private static int statusRank(ProcessStatus status) {
        if (status == null) {
            return 5;
        }
        return switch (status) {
            case LIVE -> 0;
            case APPROVED -> 1;
            case IN_REVIEW -> 2;
            case DRAFT -> 3;
            case RETIRED -> 4;
        };
    }

    // ── entity-type extraction ───────────────────────────────────────────────────

    /**
     * Resolve a node's semantic entity type via the canonical {@link GraphNodeTypes} resolver
     * ({@code entity_category} → {@code entity_type} → {@code entity_subtype}), falling back to the
     * structural {@link NodeLevel} name. Shared with the enrichment normalizer so they never diverge.
     */
    private static String extractEntityType(GraphNode node) {
        String fallback = node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
        return GraphNodeTypes.resolveEntityType(node.getMetadata(), fallback);
    }

}

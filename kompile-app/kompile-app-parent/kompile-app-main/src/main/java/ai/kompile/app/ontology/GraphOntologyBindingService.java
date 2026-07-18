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
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityTypeDefinition;
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
import java.util.List;
import java.util.Map;
import java.util.Optional;

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
        return schema.getEntityTypes().stream()
                .filter(e -> e != null && e.getName() != null && !e.getName().isBlank())
                .map(EntityTypeDefinition::getName)
                .toList();
    }

    @Override
    public List<String> allowedRelationshipTypes(Long factSheetId) {
        OntologySchema schema = resolveActiveOntology(factSheetId).orElse(null);
        if (schema == null || schema.getRelationshipTypes() == null) {
            return List.of();
        }
        return schema.getRelationshipTypes().stream()
                .filter(r -> r != null && r.getType() != null && !r.getType().isBlank())
                .map(RelationshipTypeDefinition::getType)
                .toList();
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
            if (rel.getSourceEntityType() != null && !rel.getSourceEntityType().isBlank()) {
                axioms.add(new OntologyAxiom(OntologyAxiom.Kind.DOMAIN, rel.getType(), rel.getSourceEntityType()));
            }
            if (rel.getTargetEntityType() != null && !rel.getTargetEntityType().isBlank()) {
                axioms.add(new OntologyAxiom(OntologyAxiom.Kind.RANGE, rel.getType(), rel.getTargetEntityType()));
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

    /** {@link OntologyAutoProvisioner} SPI — the crawl's deriveOntology enrichment step calls this. */
    @Override
    public void provisionOntology(long factSheetId) {
        autoProvisionStructuralOntology(factSheetId);
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

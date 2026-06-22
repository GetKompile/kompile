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
import ai.kompile.core.graphrag.conformance.OntologyAxiom;
import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NamedGraph;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.service.NamedGraphService;
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
import java.util.List;
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
 * <p><strong>Binding resolution</strong> (priority order): (1) an explicit graph-level binding —
 * the planned home is a typed {@code NamedGraph.ontologySchemaId} column, a one-method change once
 * it exists; until then this falls through to (2) the process-level binding, an
 * {@link ProcessDefinition} for the fact sheet that carries an {@code ontologySchemaId}
 * (APPROVED/LIVE preferred).
 *
     * <p>Scope: entity conformance (unknown type + field constraints) and relationship conformance
     * (relations not defined in the ontology + source-side cardinality) for edges that carry a semantic
     * {@code relationType}; purely structural edges (no relationType) are skipped.
 */
@Slf4j
@Service
public class GraphOntologyBindingService implements GraphConformanceChecker, OntologyProjectionProvider {

    /** Cap on per-report violation detail so the response stays bounded on large graphs. */
    private static final int MAX_VIOLATIONS = 200;

    private final ProcessEngineService processEngineService;
    private final KnowledgeGraphService knowledgeGraphService;
    private final NamedGraphService namedGraphService;

    public GraphOntologyBindingService(ProcessEngineService processEngineService,
                                       KnowledgeGraphService knowledgeGraphService,
                                       NamedGraphService namedGraphService) {
        this.processEngineService = processEngineService;
        this.knowledgeGraphService = knowledgeGraphService;
        this.namedGraphService = namedGraphService;
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

        List<GraphNode> entities = knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        int unknown = 0;
        int nonConformant = 0;
        List<GraphConformanceReport.NodeViolation> violations = new java.util.ArrayList<>();

        for (GraphNode node : entities) {
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

        // Relationship/edge conformance: validate edges carrying a semantic relationType against the
        // ontology's RelationshipTypeDefinitions (source/target entity types + the relation) plus
        // source-side cardinality. Structural edges (null relationType) are skipped. When the ontology
        // declares no relationship types, validateRelationship treats every edge as allowed.
        List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
        if (edges == null) {
            edges = List.of();
        }
        int edgesChecked = 0;
        int nonConformantEdges = 0;
        List<GraphConformanceReport.EdgeViolation> edgeViolations = new java.util.ArrayList<>();
        java.util.Map<String, java.util.Map<String, Long>> outgoing = new java.util.HashMap<>();
        for (GraphEdge edge : edges) {
            if (isSemanticEdge(edge)) {
                outgoing.computeIfAbsent(edge.getSourceNode().getNodeId(), k -> new java.util.HashMap<>())
                        .merge(edge.getRelationType(), 1L, Long::sum);
            }
        }
        java.util.Set<String> cardinalityFlagged = new java.util.HashSet<>();
        for (GraphEdge edge : edges) {
            if (!isSemanticEdge(edge)) {
                continue;
            }
            edgesChecked++;
            String relType = edge.getRelationType();
            String sourceType = extractEntityType(edge.getSourceNode());
            String targetType = extractEntityType(edge.getTargetNode());
            OntologyConformanceValidator.RelationshipConformance rc =
                    OntologyConformanceValidator.validateRelationship(schema, sourceType, relType, targetType);
            if (!rc.allowed()) {
                nonConformantEdges++;
                if (edgeViolations.size() < MAX_VIOLATIONS) {
                    edgeViolations.add(new GraphConformanceReport.EdgeViolation(
                            edge.getEdgeId(), relType, sourceType, targetType, rc.reason()));
                }
            } else if (rc.cardinality() != null) {
                String srcId = edge.getSourceNode().getNodeId();
                long count = outgoing.getOrDefault(srcId, java.util.Map.of()).getOrDefault(relType, 0L);
                if (!OntologyConformanceValidator.withinSourceCardinality(rc.cardinality(), count)
                        && cardinalityFlagged.add(srcId + "::" + relType)) {
                    nonConformantEdges++;
                    if (edgeViolations.size() < MAX_VIOLATIONS) {
                        edgeViolations.add(new GraphConformanceReport.EdgeViolation(
                                edge.getEdgeId(), relType, sourceType, targetType,
                                "Cardinality " + rc.cardinality() + " violated: source has " + count
                                        + " outgoing '" + relType + "' edges"));
                    }
                }
            }
        }

        Double score = conformanceScore(entities.size(), nonConformant);
        String message = String.format(
                "Checked %d ENTITY node(s) against ontology '%s' v%d: %d non-conformant (%d unknown type); "
                        + "conformance %.1f%%. Checked %d relationship(s): %d non-conformant.",
                entities.size(), schema.getName(), schema.getVersion(), nonConformant, unknown,
                (score == null ? 1.0 : score) * 100.0, edgesChecked, nonConformantEdges);
        log.info("Graph conformance factSheet={}: {}", factSheetId, message);
        return new GraphConformanceReport(factSheetId, true, schema.getId(), schema.getVersion(),
                schema.getName(), entities.size(), unknown, nonConformant, score, violations,
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
     * Bind an ontology to a fact sheet's graph (the priority-1 explicit binding). Validates that the
     * ontology exists, then stamps {@code ontologySchemaId}/{@code ontologyVersion} on the fact
     * sheet's {@link NamedGraph} — find-or-create a registry row scoped to the fact sheet if none
     * exists, so binding is reliable even when the fact sheet has no named graph yet.
     *
     * @return the bound {@link NamedGraph}
     * @throws IllegalArgumentException if the fact sheet or ontology id is missing, or the ontology
     *                                  cannot be found
     */
    public NamedGraph bindOntology(Long factSheetId, String ontologySchemaId, Integer ontologyVersion) {
        if (factSheetId == null) {
            throw new IllegalArgumentException("factSheetId is required");
        }
        if (ontologySchemaId == null || ontologySchemaId.isBlank()) {
            throw new IllegalArgumentException("ontologySchemaId is required");
        }
        if (loadOntology(ontologySchemaId, ontologyVersion).isEmpty()) {
            throw new IllegalArgumentException("No ontology found for id=" + ontologySchemaId
                    + (ontologyVersion != null ? " v" + ontologyVersion : " (latest)"));
        }
        NamedGraph target = namedGraphService.getGraphsByFactSheet(factSheetId).stream()
                .findFirst()
                .orElseGet(() -> namedGraphService.createGraph(
                        "Fact sheet " + factSheetId + " graph",
                        "Auto-created to bind a governing ontology", null, factSheetId, "bound_ontology"));
        NamedGraph bound = namedGraphService.bindOntology(target.getGraphId(), ontologySchemaId, ontologyVersion);
        log.info("Bound ontology {} v{} to factSheet={} (graph {})",
                ontologySchemaId, ontologyVersion, factSheetId, bound.getGraphId());
        return bound;
    }

    /** Clear any explicit ontology binding on the fact sheet's named graph(s). */
    public void unbindOntology(Long factSheetId) {
        if (factSheetId == null) {
            return;
        }
        for (NamedGraph g : namedGraphService.getGraphsByFactSheet(factSheetId)) {
            if (g.getOntologySchemaId() != null) {
                namedGraphService.bindOntology(g.getGraphId(), null, null);
            }
        }
    }

    // ── binding resolution ─────────────────────────────────────────────────────

    /**
     * Priority 1: an explicit graph-level binding — a {@link NamedGraph} scoped to this fact sheet
     * carrying an {@code ontologySchemaId}. Returns the first such bound ontology that loads; empty
     * if none, so resolution falls through to the process-level binding.
     */
    private Optional<OntologySchema> resolveExplicitGraphBinding(Long factSheetId) {
        List<NamedGraph> graphs;
        try {
            graphs = namedGraphService.getGraphsByFactSheet(factSheetId);
        } catch (Exception e) {
            log.warn("Could not resolve named graphs while binding ontology for factSheet={}: {}",
                    factSheetId, e.getMessage());
            return Optional.empty();
        }
        if (graphs == null) {
            return Optional.empty();
        }
        return graphs.stream()
                .filter(g -> g.getOntologySchemaId() != null && !g.getOntologySchemaId().isBlank())
                .map(g -> loadOntology(g.getOntologySchemaId(), g.getOntologyVersion()))
                .filter(Optional::isPresent)
                .map(Optional::get)
                .findFirst();
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

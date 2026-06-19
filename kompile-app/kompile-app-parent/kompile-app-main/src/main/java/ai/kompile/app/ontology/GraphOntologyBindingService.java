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
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.OntologyConformanceValidator;
import ai.kompile.process.ontology.OntologySchema;
import ai.kompile.process.service.ProcessEngineService;
import ai.kompile.process.workflow.ProcessDefinition;
import ai.kompile.process.workflow.ProcessStatus;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Comparator;
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
 * <p><strong>Binding resolution</strong> (priority order): (1) an explicit graph-level binding —
 * the planned home is a typed {@code NamedGraph.ontologySchemaId} column, a one-method change once
 * it exists; until then this falls through to (2) the process-level binding, an
 * {@link ProcessDefinition} for the fact sheet that carries an {@code ontologySchemaId}
 * (APPROVED/LIVE preferred).
 *
 * <p>Scope: entity conformance (unknown type + field constraints). Relationship/cardinality
 * conformance is intentionally deferred — graph edges carry structural {@code EdgeType}s, not the
 * ontology's semantic relationship names, so wiring it now would be noisy.
 */
@Slf4j
@Service
public class GraphOntologyBindingService implements GraphConformanceChecker {

    /** Cap on per-report violation detail so the response stays bounded on large graphs. */
    private static final int MAX_VIOLATIONS = 200;

    private static final List<String> CATEGORY_KEYS =
            List.of("entity_category", "entityCategory", "resolution_category", "resolutionCategory");
    private static final List<String> TYPE_KEYS = List.of("entity_type", "entityType");
    private static final List<String> SUBTYPE_KEYS = List.of("entity_subtype", "entitySubtype");

    private final ProcessEngineService processEngineService;
    private final KnowledgeGraphService knowledgeGraphService;

    public GraphOntologyBindingService(ProcessEngineService processEngineService,
                                       KnowledgeGraphService knowledgeGraphService) {
        this.processEngineService = processEngineService;
        this.knowledgeGraphService = knowledgeGraphService;
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

        String message = String.format(
                "Checked %d ENTITY node(s) against ontology '%s' v%d: %d non-conformant (%d unknown type).",
                entities.size(), schema.getName(), schema.getVersion(), nonConformant, unknown);
        log.info("Graph conformance factSheet={}: {}", factSheetId, message);
        return new GraphConformanceReport(factSheetId, true, schema.getId(), schema.getVersion(),
                schema.getName(), entities.size(), unknown, nonConformant, violations, message);
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
                report.nonConformantCount(), report.message());
    }

    // ── binding resolution ─────────────────────────────────────────────────────

    /**
     * Priority 1: an explicit graph-level binding. The planned home is a typed
     * {@code NamedGraph.ontologySchemaId} column; until that exists this returns empty so resolution
     * falls through to the process-level binding. Adding the column is a one-method change here.
     */
    private Optional<OntologySchema> resolveExplicitGraphBinding(Long factSheetId) {
        return Optional.empty();
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
     * Resolve a node's semantic entity type from its metadata, mirroring the cascade used by
     * GraphCompactionService: {@code entity_category} → {@code entity_type} → {@code entity_subtype}
     * → the structural {@link NodeLevel} name.
     */
    private static String extractEntityType(GraphNode node) {
        Map<String, Object> meta = node.getMetadata();
        String value = firstNonBlank(meta, CATEGORY_KEYS);
        if (value == null) {
            value = firstNonBlank(meta, TYPE_KEYS);
        }
        if (value == null) {
            value = firstNonBlank(meta, SUBTYPE_KEYS);
        }
        if (value == null) {
            value = node.getNodeType() != null ? node.getNodeType().name() : "UNKNOWN";
        }
        return value;
    }

    private static String firstNonBlank(Map<String, Object> meta, List<String> keys) {
        if (meta == null) {
            return null;
        }
        for (String key : keys) {
            Object o = meta.get(key);
            if (o instanceof String s && !s.isBlank()) {
                return s;
            }
        }
        return null;
    }
}

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

import ai.kompile.app.web.dto.ontology.OwlClassificationResponse;
import ai.kompile.app.web.dto.ontology.OwlReasoningResponse;
import ai.kompile.core.graphrag.conformance.OwlDerivedRuleProvider;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.OntologySchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

/**
 * Orchestrates the full OWL 2 RL reasoning pipeline over a bound {@link OntologySchema}.
 *
 * <h2>Pipeline</h2>
 * <ol>
 *   <li>Resolve the bound {@link OntologySchema} via {@link GraphOntologyBindingService}.</li>
 *   <li>Convert it to an {@link OwlOntology} TBox via {@link OwlOntologyBridge}.</li>
 *   <li>Run {@link OwlRlReasoner} over an empty ABox (TBox-only reasoning) to materialise
 *       entailments: transitive closure + domain/range propagation + inconsistency detection.</li>
 *   <li>Return an {@link OwlReasoningResponse} DTO ready for the REST controller.</li>
 * </ol>
 *
 * <p>When no ontology is bound to the fact sheet, returns a default unbound response (all counts
 * zero, {@code ontologyBound=false}, {@code reasonerActive=false}).</p>
 *
 * <h2>OWL-RL → PSL feed-back (step 4)</h2>
 * <p>The OWL RL result is also exposed to {@link GraphOntologyBindingService} callers via
 * {@link #buildOwlDerivedPslRules(Long)} so that {@code IncrementalReasoningOrchestrator} can
 * inject additional PSL rules derived from OWL entailments (e.g. subClassOf transitivity as
 * soft typing rules) alongside the plain DOMAIN/RANGE axiom rules already compiled by
 * {@code OntologyToPslRuleCompiler}.</p>
 */
@Service
public class OwlReasoningService implements OwlDerivedRuleProvider {

    private static final Logger log = LoggerFactory.getLogger(OwlReasoningService.class);

    /** Maximum number of sample entailment strings returned in the REST response. */
    private static final int MAX_SAMPLE_ENTAILMENTS = 10;

    private final GraphOntologyBindingService bindingService;
    private final OwlOntologyBridge bridge;
    private final OwlRlReasoner reasoner;
    private final KnowledgeGraphService knowledgeGraphService;

    public OwlReasoningService(GraphOntologyBindingService bindingService,
                                OwlOntologyBridge bridge,
                                KnowledgeGraphService knowledgeGraphService) {
        this.bindingService = bindingService;
        this.bridge = bridge;
        this.knowledgeGraphService = knowledgeGraphService;
        this.reasoner = new OwlRlReasoner();
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Public API
    // ─────────────────────────────────────────────────────────────────────────────

    /**
     * Run OWL 2 RL reasoning over the ontology bound to {@code factSheetId} and return a
     * structured response.
     *
     * @param factSheetId the fact sheet whose bound ontology to reason over
     * @return a populated {@link OwlReasoningResponse}; never {@code null}
     */
    public OwlReasoningResponse reason(long factSheetId) {
        Optional<OntologySchema> schemaOpt = bindingService.resolveActiveOntology(factSheetId);

        if (schemaOpt.isEmpty()) {
            log.debug("OwlReasoningService: no bound ontology for factSheetId={}", factSheetId);
            return unbound(factSheetId);
        }

        OntologySchema schema = schemaOpt.get();
        log.info("OwlReasoningService: running OWL RL for factSheetId={}, schema='{}'",
                factSheetId, schema.getId());

        OwlOntology tbox = bridge.toOwlOntology(schema);

        // Reason over the REAL crawled ABox so the response reflects actual instance-level entailments
        // (transitive closure + inferred types), not just TBox structure.
        ReasoningGraph abox = buildAbox(factSheetId);

        OwlRlResult result = reasoner.reason(abox, tbox);

        return buildResponse(factSheetId, schema, tbox, result);
    }

    /**
     * Run OWL classification (realization) on demand over the fact sheet's real graph and persist the
     * results — inferred is-a type memberships + has-a transitive-closure edges. Auto-provisions a
     * structural ontology if none is bound, so there is always something to classify against.
     *
     * @param factSheetId the fact sheet to classify
     * @return a summary of what was classified + materialized
     */
    public OwlClassificationResponse classify(long factSheetId) {
        Optional<OntologySchema> schemaOpt = bindingService.autoProvisionStructuralOntology(factSheetId);
        if (schemaOpt.isEmpty()) {
            return OwlClassificationResponse.unbound(factSheetId);
        }
        OntologySchema schema = schemaOpt.get();
        OwlOntology tbox = bridge.toOwlOntology(schema);
        ReasoningGraph abox = buildAbox(factSheetId);
        OwlRlResult result = reasoner.reason(abox, tbox);
        MaterializationStats stats = materializeInferences(factSheetId, result);
        log.info("OwlReasoningService.classify factSheet={}: {} entities typed, {} has-a edges materialized",
                factSheetId, stats.entitiesClassified(), stats.edgesMaterialized());
        return OwlClassificationResponse.builder()
                .factSheetId(factSheetId)
                .ontologyBound(true)
                .ontologyName(schema.getName() != null ? schema.getName() : schema.getId())
                .inferredTypeCount(result.inferredTypes().size())
                .inferredRelationCount(result.inferredRelations().size())
                .entitiesClassified(stats.entitiesClassified())
                .edgesMaterialized(stats.edgesMaterialized())
                .consistent(result.isConsistent())
                .reasonerActive(true)
                .build();
    }

    /**
     * Implements {@link OwlDerivedRuleProvider}: derives additional PSL rule strings from the
     * OWL RL entailment for {@code factSheetId}.
     *
     * <p>These rules supplement the plain DOMAIN/RANGE axiom rules already produced by
     * {@code OntologyToPslRuleCompiler}. Specifically, inferred type assertions from the OWL RL
     * pass are encoded as soft PSL {@code has_type} rules, enabling OWL-RL-derived subClassOf
     * reasoning to influence PSL grounding in the cascade.</p>
     *
     * <p>Returns an empty list when no ontology is bound or the OWL RL pass produces no
     * inferred types.</p>
     *
     * @param factSheetId the fact sheet to derive rules for
     * @param ruleWeight  soft rule weight (source: {@code KbConfig.ontologyRuleWeight})
     * @return PSL rule strings, each ready for {@code PslProgram.addRule(String)}
     */
    @Override
    public List<String> owlDerivedPslRules(long factSheetId, double ruleWeight) {
        // Reason over whatever ontology is bound. Provisioning is explicit — the crawl's deriveOntology
        // enrichment step + the Classify action both bind via OntologyAutoProvisioner — so this is not
        // a side-effect of rule generation.
        Optional<OntologySchema> schemaOpt = bindingService.resolveActiveOntology(factSheetId);
        if (schemaOpt.isEmpty()) return List.of();

        OwlOntology tbox = bridge.toOwlOntology(schemaOpt.get());
        // Reason over the REAL crawled entities (typed by their ontology entity_type) + their edges,
        // so OWL-RL computes has-a transitive closure over the actual graph and feeds instance types
        // into PSL grounding. Without a real ABox these rules ground over nothing.
        ReasoningGraph abox = buildAbox(factSheetId);
        OwlRlResult result = reasoner.reason(abox, tbox);

        List<String> rules = new ArrayList<>();

        // Inferred types: entity is inferred to belong to a class → soft typing rule
        // Format: "<weight>: has_type_CLASSNAME(?X) ^2" (a "belief" rule; no antecedent,
        // acts as a soft prior for the inferred membership)
        for (Map.Entry<String, String> entry : result.inferredTypes().entrySet()) {
            String classIri = entry.getValue();
            String localName = localNameFromIri(classIri);
            if (localName == null || localName.isBlank()) continue;
            String normalized = localName.toLowerCase().replaceAll("[^a-z0-9_]", "_");
            rules.add(ruleWeight + ": has_type_" + normalized + "(?X) ^2");
        }

        // Transitive-closure edges become subPropertyOf / chain rules at the PSL level:
        // emit "rel_TYPE(?X, ?Z)" soft rules for each unique (type, source-class, target-class)
        // triple inferred from the transitive-closure pass.
        // We collect unique property types from the inferred transitive relations.
        result.inferredRelations().stream()
                .map(GraphRelation::type)
                .distinct()
                .forEach(propType -> {
                    String normalized = propType.toLowerCase().replaceAll("[^a-z0-9_]", "_");
                    // Soft rule: existing direct edges of this type give evidence for transitive ones
                    rules.add(ruleWeight + ": " + normalized + "(?X, ?Y) & " + normalized
                            + "(?Y, ?Z) -> " + normalized + "(?X, ?Z) ^2");
                });

        // Persist the entailments back into the graph (best-effort) so they're queryable + visible.
        materializeInferences(factSheetId, result);

        log.debug("OwlReasoningService.owlDerivedPslRules: {} PSL rules from OWL RL for factSheetId={}",
                rules.size(), factSheetId);
        return rules;
    }

    // ─────────────────────────────────────────────────────────────────────────────
    // Private helpers
    // ─────────────────────────────────────────────────────────────────────────────

    private OwlReasoningResponse buildResponse(long factSheetId,
                                                OntologySchema schema,
                                                OwlOntology tbox,
                                                OwlRlResult result) {
        int classCount          = tbox.classes().size();
        int objectPropCount     = tbox.objectProperties().size();
        int dataPropCount       = tbox.dataProperties().size();
        // Axiom count = structural axioms in the TBox (classes + properties + inferred subClassOf)
        int axiomCount          = classCount + objectPropCount + dataPropCount
                                  + result.inferredTypes().size();

        int entailments         = result.inferredRelations().size()
                                  + result.inferredTypes().size();

        // Inconsistencies → DTO entries
        List<OwlReasoningResponse.InconsistencyEntry> inconsistencies =
                result.inconsistencies().stream()
                        .map(i -> OwlReasoningResponse.InconsistencyEntry.builder()
                                .description(i.message())
                                .build())
                        .collect(Collectors.toList());

        // Sample entailments — human-readable strings for the UI
        List<String> sampleEntailments = buildSampleEntailments(tbox, result);

        return OwlReasoningResponse.builder()
                .factSheetId(factSheetId)
                .ontologyBound(true)
                .ontologyName(schema.getName() != null ? schema.getName() : schema.getId())
                .classCount(classCount)
                .objectPropertyCount(objectPropCount)
                .dataPropertyCount(dataPropCount)
                .axiomCount(axiomCount)
                .entailmentsMaterialized(entailments)
                .inferredTypeCount(result.inferredTypes().size())
                .inferredRelationCount(result.inferredRelations().size())
                .consistent(result.isConsistent())
                .inconsistencies(inconsistencies)
                .sampleEntailments(sampleEntailments)
                .reasonerActive(true)
                .build();
    }

    /**
     * Produce up to {@value #MAX_SAMPLE_ENTAILMENTS} human-readable entailment strings.
     *
     * <p>Priority order:
     * <ol>
     *   <li>Object-property domain/range axioms → {@code "domain(prop) ⊑ DomainClass"} etc.</li>
     *   <li>Inferred types → {@code "entity ∈ ClassName"}</li>
     *   <li>Transitive-closure relation types → {@code "TYPE is transitive"}</li>
     * </ol>
     */
    private List<String> buildSampleEntailments(OwlOntology tbox, OwlRlResult result) {
        List<String> samples = new ArrayList<>();

        // 1. Domain/range axioms from object properties
        for (var prop : tbox.objectProperties().values()) {
            if (samples.size() >= MAX_SAMPLE_ENTAILMENTS) break;
            String localProp = prop.localName();
            if (prop.domainClassIri() != null) {
                String domainLocal = localNameFromIri(prop.domainClassIri());
                samples.add("domain(" + localProp + ") ⊑ " + domainLocal);
            }
            if (prop.rangeClassIri() != null) {
                String rangeLocal = localNameFromIri(prop.rangeClassIri());
                samples.add("range(" + localProp + ") ⊑ " + rangeLocal);
            }
        }

        // 2. Inferred types from OWL RL (entity membership assertions)
        if (samples.size() < MAX_SAMPLE_ENTAILMENTS) {
            for (var entry : result.inferredTypes().entrySet()) {
                if (samples.size() >= MAX_SAMPLE_ENTAILMENTS) break;
                String entityId   = entry.getKey();
                String classLocal = localNameFromIri(entry.getValue());
                samples.add(entityId + " ∈ " + (classLocal != null ? classLocal : entry.getValue()));
            }
        }

        // 3. Transitive properties (property names that produced transitive-closure edges)
        if (samples.size() < MAX_SAMPLE_ENTAILMENTS) {
            result.inferredRelations().stream()
                    .map(GraphRelation::type)
                    .distinct()
                    .limit(MAX_SAMPLE_ENTAILMENTS - samples.size())
                    .forEach(t -> samples.add(t + " is transitive (closure inferred)"));
        }

        return samples.size() > MAX_SAMPLE_ENTAILMENTS
                ? samples.subList(0, MAX_SAMPLE_ENTAILMENTS)
                : samples;
    }

    /**
     * Default unbound response — no ontology attached, all counts zero, no reasoning ran.
     */
    private static OwlReasoningResponse unbound(long factSheetId) {
        return OwlReasoningResponse.builder()
                .factSheetId(factSheetId)
                .ontologyBound(false)
                .ontologyName(null)
                .classCount(0)
                .objectPropertyCount(0)
                .dataPropertyCount(0)
                .axiomCount(0)
                .entailmentsMaterialized(0)
                .consistent(true)       // vacuously consistent
                .inconsistencies(List.of())
                .sampleEntailments(List.of())
                .reasonerActive(false)
                .build();
    }

    /** Extract the local name (fragment after {@code #} or last path segment after {@code /}). */
    private static String localNameFromIri(String iri) {
        if (iri == null) return null;
        int hash = iri.lastIndexOf('#');
        if (hash >= 0 && hash < iri.length() - 1) return iri.substring(hash + 1);
        int slash = iri.lastIndexOf('/');
        if (slash >= 0 && slash < iri.length() - 1) return iri.substring(slash + 1);
        return iri;
    }

    /**
     * Build an OWL ABox from the fact sheet's real crawled entities — each typed by its ontology
     * {@code entity_type} (so OWL domain/range + subClassOf rules classify the real instances) and
     * connected by their inter-entity edges (so transitive object properties produce real closure).
     * Returns an empty graph when no {@link KnowledgeGraphService} is wired.
     */
    private ReasoningGraph buildAbox(long factSheetId) {
        MutableReasoningGraph abox = new MutableReasoningGraph();
        if (knowledgeGraphService == null) {
            return abox;
        }
        for (GraphNode node : knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY)) {
            String type = entityType(node);
            if (type == null || node.getNodeId() == null) continue;
            String label = node.getTitle() != null ? node.getTitle() : node.getNodeId();
            abox.addEntity(node.getNodeId(), type, label);
        }
        for (GraphEdge edge : knowledgeGraphService.getEdgesInFactSheet(factSheetId)) {
            if (Boolean.TRUE.equals(edge.getStale())) continue;
            String src = edge.getSourceNode() != null ? edge.getSourceNode().getNodeId() : null;
            String tgt = edge.getTargetNode() != null ? edge.getTargetNode().getNodeId() : null;
            if (src == null || tgt == null) continue;
            if (abox.entity(src).isEmpty() || abox.entity(tgt).isEmpty()) continue; // entity pairs only
            // Prefer the semantic relationType (matches ontology relationship types, e.g. "CONTAINS")
            // over the structural EdgeType enum, so transitive/closure properties resolve.
            String type = edge.getRelationType() != null && !edge.getRelationType().isBlank()
                    ? edge.getRelationType()
                    : (edge.getEdgeType() != null ? edge.getEdgeType().name() : "");
            double weight = edge.getConfidence() != null ? edge.getConfidence() : 1.0;
            abox.addRelation(edge.getEdgeId(), src, tgt, type, weight);
        }
        return abox;
    }

    /** Ontology entity type from a node's {@code entity_type} metadata (written at extraction). */
    private static String entityType(GraphNode node) {
        if (node == null || node.getMetadata() == null) return null;
        Object val = node.getMetadata().get("entity_type");
        return (val instanceof String s && !s.isBlank()) ? s.trim() : null;
    }

    /**
     * Persist the OWL-RL entailments back into the graph (best-effort) so downstream queries, RAG, and
     * the visualizer can see them: transitive-closure (has-a) edges become real {@code INFERRED}
     * {@link GraphEdge}s (idempotent on the endpoint pair), and inferred is-a types are recorded in the
     * entity nodes' {@code owlInferredTypes} metadata. Never throws into the enrichment pass.
     */
    private MaterializationStats materializeInferences(long factSheetId, OwlRlResult result) {
        if (knowledgeGraphService == null || result == null) return new MaterializationStats(0, 0);
        int edges = 0;
        try {
            int edgeCap = 5000;
            for (GraphRelation rel : result.inferredRelations()) {
                if (edges >= edgeCap) {
                    log.warn("OWL inferred-edge materialization capped at {} for factSheet={}", edgeCap, factSheetId);
                    break;
                }
                String src = rel.sourceId();
                String tgt = rel.targetId();
                if (src == null || tgt == null || src.equals(tgt)) continue;
                if (knowledgeGraphService.edgeExists(src, tgt)) continue; // idempotent; don't shadow asserted edges
                knowledgeGraphService.createEdgeWithMetadata(
                        src, tgt, EdgeType.HIERARCHICAL, 0.7,
                        rel.type(), "OWL-RL inferred transitive closure (" + rel.type() + ")",
                        null, EdgeProvenance.INFERRED, factSheetId);
                edges++;
            }
            int typed = materializeInferredTypes(factSheetId, result.inferredTypes());
            if (edges > 0 || typed > 0) {
                log.info("Materialized OWL inferences for factSheet={}: {} transitive has-a edges, {} typed entities",
                        factSheetId, edges, typed);
            }
            return new MaterializationStats(edges, typed);
        } catch (RuntimeException e) {
            log.warn("Materializing OWL inferences failed for factSheet={}: {}", factSheetId, e.toString());
            return new MaterializationStats(edges, 0);
        }
    }

    /** Counts from a materialization pass — surfaced by the on-demand {@link #classify(long)} run. */
    private record MaterializationStats(int edgesMaterialized, int entitiesClassified) {}

    /** Record inferred is-a class memberships in entity nodes' {@code owlInferredTypes} metadata. */
    private int materializeInferredTypes(long factSheetId, Map<String, String> inferredTypes) {
        if (inferredTypes == null || inferredTypes.isEmpty()) return 0;
        Map<String, LinkedHashSet<String>> byEntity = new LinkedHashMap<>();
        for (Map.Entry<String, String> e : inferredTypes.entrySet()) {
            String localName = localNameFromIri(e.getValue());
            if (e.getKey() == null || localName == null || localName.isBlank()) continue;
            byEntity.computeIfAbsent(e.getKey(), k -> new LinkedHashSet<>()).add(localName);
        }
        if (byEntity.isEmpty()) return 0;
        Map<String, GraphNode> nodesById = knowledgeGraphService
                .getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY).stream()
                .filter(n -> n.getNodeId() != null)
                .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a));
        int updated = 0;
        int cap = 1000;
        for (Map.Entry<String, LinkedHashSet<String>> e : byEntity.entrySet()) {
            if (updated >= cap) {
                log.warn("OWL inferred-type materialization capped at {} entities for factSheet={}", cap, factSheetId);
                break;
            }
            GraphNode node = nodesById.get(e.getKey());
            if (node == null) continue;
            Map<String, Object> meta = node.getMetadata() != null
                    ? new HashMap<>(node.getMetadata()) : new HashMap<>();
            meta.put("owlInferredTypes", new ArrayList<>(e.getValue()));
            knowledgeGraphService.updateNode(e.getKey(), null, null, meta);
            updated++;
        }
        return updated;
    }
}

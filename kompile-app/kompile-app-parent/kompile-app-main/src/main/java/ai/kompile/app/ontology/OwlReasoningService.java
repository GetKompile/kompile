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
import ai.kompile.core.graphrag.typing.GraphNodeTypes;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlOntology;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlReasoner;
import ai.kompile.graph.reasoning.mebn.type.owl.OwlRlResult;
import ai.kompile.graph.reasoning.mebn.type.owl.TableMemberOntologyBridge;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.model.GraphRelation;
import ai.kompile.graph.reasoning.model.MutableReasoningGraph;
import ai.kompile.graph.reasoning.model.ReasoningGraph;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.process.ontology.EntityTypeDefinition;
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
import java.util.Set;
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
        // Typed table members crawled from workbook master tables (SKU, CHANNEL, ...) join the
        // TBox as classes so schema axioms about them participate in this same pass.
        tbox = TableMemberOntologyBridge.ontologyFromTableMembers(abox, tbox);

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
        tbox = TableMemberOntologyBridge.ontologyFromTableMembers(abox, tbox);
        OwlRlResult result = reasoner.reason(abox, tbox);
        MaterializationStats stats = materializeInferences(factSheetId, schema, result);
        log.info("OwlReasoningService.classify factSheet={}: {} entities typed, {} has-a edges materialized",
                factSheetId, stats.entitiesClassified(), stats.edgesMaterialized());
        return OwlClassificationResponse.builder()
                .factSheetId(factSheetId)
                .ontologyBound(true)
                .ontologyName(schema.getName() != null ? schema.getName() : schema.getId())
                .inferredTypeCount(result.inferredTypeCount())
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
        tbox = TableMemberOntologyBridge.ontologyFromTableMembers(abox, tbox);
        OwlRlResult result = reasoner.reason(abox, tbox);

        List<String> rules = new ArrayList<>();

        // Inferred types: entity is inferred to belong to a class → soft typing rule
        // Format: "<weight>: has_type_CLASSNAME(?X) ^2" (a "belief" rule; no antecedent,
        // acts as a soft prior for the inferred membership)
        Set<String> inferredTypeRuleNames = new LinkedHashSet<>();
        for (Map.Entry<String, List<String>> entry : result.inferredTypeCandidates().entrySet()) {
            for (String classIri : entry.getValue()) {
                String localName = localNameFromIri(classIri);
                if (localName == null || localName.isBlank()) continue;
                inferredTypeRuleNames.add(localName.toLowerCase().replaceAll("[^a-z0-9_]", "_"));
            }
        }
        inferredTypeRuleNames.forEach(normalized ->
                rules.add(ruleWeight + ": has_type_" + normalized + "(?X) ^2"));

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
        materializeInferences(factSheetId, schemaOpt.get(), result);

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
                                  + result.inferredTypeCount();

        int entailments         = result.inferredRelations().size()
                                  + result.inferredTypeCount();

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
                .inferredTypeCount(result.inferredTypeCount())
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
            for (var entry : result.inferredTypeCandidates().entrySet()) {
                String entityId = entry.getKey();
                for (String classIri : entry.getValue()) {
                    if (samples.size() >= MAX_SAMPLE_ENTAILMENTS) break;
                    String classLocal = localNameFromIri(classIri);
                    samples.add(entityId + " ∈ " + (classLocal != null ? classLocal : classIri));
                }
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
            if (node.getNodeId() == null) continue;
            Map<String, Object> metadata = nonNullMetadata(node);
            String type = primaryReasoningType(metadata);
            String label = node.getTitle() != null ? node.getTitle() : node.getNodeId();
            List<String> typeMemberships = GraphNodeTypes.resolveTypeMemberships(metadata);
            if (!typeMemberships.isEmpty()) {
                metadata = new LinkedHashMap<>(metadata);
                metadata.putIfAbsent("entity_types", typeMemberships);
            }
            GraphEntity entity = GraphEntity.builder(node.getNodeId())
                    .type(type)
                    .label(label)
                    .attributes(metadata)
                    .build();
            if (entity.typeMemberships().isEmpty()) continue;
            if (entity.type() == null || entity.type().isBlank()) {
                entity = GraphEntity.builder(node.getNodeId())
                        .type(entity.typeMemberships().iterator().next())
                        .label(label)
                        .attributes(metadata)
                        .build();
            }
            abox.addEntity(entity);
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

    /** Primary reasoning type from the full crawl type chain, preferring the most specific type. */
    private static String primaryReasoningType(Map<String, Object> metadata) {
        List<String> memberships = GraphNodeTypes.resolveTypeMemberships(metadata);
        return memberships.isEmpty() ? null : memberships.get(0);
    }

    private static Map<String, Object> nonNullMetadata(GraphNode node) {
        if (node == null || node.getMetadata() == null) return Map.of();
        Map<String, Object> clean = new LinkedHashMap<>();
        node.getMetadata().forEach((key, value) -> {
            if (key != null && value != null) {
                clean.put(key, value);
            }
        });
        return clean;
    }

    /**
     * Persist the OWL-RL entailments back into the graph (best-effort) so downstream queries, RAG, and
     * the visualizer can see them: transitive-closure (has-a) edges become real {@code INFERRED}
     * {@link GraphEdge}s (idempotent on the endpoint pair), and inferred is-a types are recorded in the
     * entity nodes' {@code owlInferredTypes} metadata. Never throws into the enrichment pass.
     */
    private MaterializationStats materializeInferences(long factSheetId, OntologySchema schema, OwlRlResult result) {
        if (knowledgeGraphService == null || result == null) return new MaterializationStats(0, 0);
        int edges = 0;
        try {
            int edgeCap = 5000;
            List<KnowledgeGraphService.EdgeSpec> edgeSpecs = new ArrayList<>();
            for (GraphRelation rel : result.inferredRelations()) {
                if (edgeSpecs.size() >= edgeCap) {
                    log.warn("OWL inferred-edge materialization capped at {} for factSheet={}", edgeCap, factSheetId);
                    break;
                }
                String src = rel.sourceId();
                String tgt = rel.targetId();
                if (src == null || tgt == null || src.equals(tgt)) continue;
                edgeSpecs.add(new KnowledgeGraphService.EdgeSpec(
                        src, tgt, EdgeType.HIERARCHICAL, 0.7,
                        "OWL-RL inferred transitive closure (" + rel.type() + ")",
                        rel.type(), null, EdgeProvenance.INFERRED, factSheetId));
            }
            edges = knowledgeGraphService.createEdgesBatch(edgeSpecs);
            int typed = materializeInferredTypes(factSheetId, result.inferredTypeCandidates(),
                    schemaParentByType(schema));
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

    private record InferredTypeCandidate(String localName, String classIri) {}

    /** Record inferred is-a class memberships in entity nodes' {@code owlInferredTypes} metadata. */
    private int materializeInferredTypes(long factSheetId, Map<String, ?> inferredTypes) {
        return materializeInferredTypes(factSheetId, inferredTypes, Map.of());
    }

    /** Record inferred is-a class memberships and schema/crawl hierarchy metadata on entity nodes. */
    private int materializeInferredTypes(long factSheetId,
                                         Map<String, ?> inferredTypes,
                                         Map<String, String> parentByType) {
        if (inferredTypes == null || inferredTypes.isEmpty()) return 0;
        Map<String, LinkedHashSet<InferredTypeCandidate>> byEntity = new LinkedHashMap<>();
        for (Map.Entry<String, ?> e : inferredTypes.entrySet()) {
            appendInferredTypeCandidates(byEntity, e.getKey(), e.getValue());
        }
        if (byEntity.isEmpty()) return 0;
        Map<String, GraphNode> nodesById = knowledgeGraphService
                .getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY).stream()
                .filter(n -> n.getNodeId() != null)
                .collect(Collectors.toMap(GraphNode::getNodeId, n -> n, (a, b) -> a));
        List<KnowledgeGraphService.NodeUpdate> updates = new ArrayList<>();
        int cap = 1000;
        for (Map.Entry<String, LinkedHashSet<InferredTypeCandidate>> e : byEntity.entrySet()) {
            if (updates.size() >= cap) {
                log.warn("OWL inferred-type materialization capped at {} entities for factSheet={}", cap, factSheetId);
                break;
            }
            GraphNode node = nodesById.get(e.getKey());
            if (node == null) continue;
            Map<String, Object> meta = node.getMetadata() != null
                    ? new HashMap<>(node.getMetadata()) : new HashMap<>();
            List<String> localNames = e.getValue().stream()
                    .map(InferredTypeCandidate::localName)
                    .distinct()
                    .toList();
            meta.put("owlInferredTypes", new ArrayList<>(localNames));
            meta.put("ontology.typeCandidates", mergeOwlTypeCandidates(
                    meta.get("ontology.typeCandidates"), e.getValue()));
            List<Object> hierarchy = mergeTypeHierarchy(
                    meta.get("ontology.typeHierarchy"),
                    crawlTypeHierarchyMetadata(meta),
                    owlTypeHierarchyMetadata(meta, e.getValue(), parentByType));
            if (!hierarchy.isEmpty()) {
                meta.put("ontology.typeHierarchy", hierarchy);
            }
            updates.add(new KnowledgeGraphService.NodeUpdate(e.getKey(), null, null, meta));
        }
        return knowledgeGraphService.updateNodesBatch(updates);
    }

    private static Map<String, String> schemaParentByType(OntologySchema schema) {
        if (schema == null || schema.getEntityTypes() == null) {
            return Map.of();
        }
        Map<String, String> parents = new LinkedHashMap<>();
        for (EntityTypeDefinition entityType : schema.getEntityTypes()) {
            if (entityType == null || entityType.getName() == null || entityType.getName().isBlank()
                    || entityType.getParentType() == null || entityType.getParentType().isBlank()) {
                continue;
            }
            parents.put(entityType.getName(), entityType.getParentType());
        }
        return parents;
    }

    private static List<Map<String, Object>> crawlTypeHierarchyMetadata(Map<String, Object> metadata) {
        List<Map<String, Object>> result = new ArrayList<>();
        Double confidence = doubleValue(firstNonNull(metadata,
                "ontology.typeConfidence", "typeConfidence", "typeInferenceScore", "gnn.score", "kge.score", "confidence"));
        for (GraphNodeTypes.TypeHierarchyEdge hierarchy : GraphNodeTypes.resolveTypeHierarchy(metadata)) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", hierarchy.type());
            row.put("parentType", hierarchy.parentType());
            row.put("depth", 1);
            if (confidence != null) {
                row.put("confidence", confidence);
            }
            row.put("source", "crawl-schema");
            row.put("basis", hierarchy.typeKey() + "/" + hierarchy.parentKey());
            row.put("evidence", List.of(
                    hierarchy.typeKey() + "=" + hierarchy.type(),
                    hierarchy.parentKey() + "=" + hierarchy.parentType()));
            result.add(row);
        }
        return result;
    }

    private static List<Map<String, Object>> owlTypeHierarchyMetadata(
            Map<String, Object> metadata,
            LinkedHashSet<InferredTypeCandidate> inferredTypes,
            Map<String, String> parentByType) {
        if (inferredTypes == null || inferredTypes.isEmpty() || parentByType == null || parentByType.isEmpty()) {
            return List.of();
        }
        LinkedHashSet<String> knownTypes = new LinkedHashSet<>(GraphNodeTypes.resolveTypeMemberships(metadata));
        inferredTypes.stream().map(InferredTypeCandidate::localName).forEach(knownTypes::add);

        LinkedHashSet<String> inferredLocalNames = inferredTypes.stream()
                .map(InferredTypeCandidate::localName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        Map<String, String> iriByLocalName = inferredTypes.stream()
                .collect(Collectors.toMap(InferredTypeCandidate::localName, InferredTypeCandidate::classIri, (a, b) -> a,
                        LinkedHashMap::new));

        List<Map<String, Object>> result = new ArrayList<>();
        for (String type : knownTypes) {
            String parent = parentByType.get(type);
            if (parent == null || parent.isBlank() || type.equalsIgnoreCase(parent)) {
                continue;
            }
            if (!knownTypes.contains(parent) && !inferredLocalNames.contains(parent)) {
                continue;
            }
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("type", type);
            row.put("parentType", parent);
            row.put("depth", 1);
            row.put("confidence", 1.0d);
            row.put("source", "owl-rl");
            row.put("basis", "schema-parentType");
            String evidence = iriByLocalName.getOrDefault(parent, "parentType:" + type + "->" + parent);
            row.put("evidence", List.of(evidence));
            result.add(row);
        }
        return result;
    }

    @SafeVarargs
    private static List<Object> mergeTypeHierarchy(Object existing, List<Map<String, Object>>... additions) {
        List<Object> hierarchy = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        appendExistingTypeHierarchy(hierarchy, seen, existing);
        for (List<Map<String, Object>> rows : additions) {
            if (rows == null) {
                continue;
            }
            for (Map<String, Object> row : rows) {
                if (row != null && seen.add(typeHierarchyKey(row))) {
                    hierarchy.add(row);
                }
            }
        }
        return hierarchy;
    }

    private static void appendExistingTypeHierarchy(List<Object> hierarchy,
                                                    LinkedHashSet<String> seen,
                                                    Object existing) {
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                appendExistingTypeHierarchy(hierarchy, seen, item);
            }
            return;
        }
        if (existing instanceof Map<?, ?> rawMap) {
            Map<String, Object> row = stringKeyMap(rawMap);
            if (seen.add(typeHierarchyKey(row))) {
                hierarchy.add(row);
            }
            return;
        }
        if (existing instanceof String s && !s.isBlank() && seen.add(s.trim())) {
            hierarchy.add(s.trim());
        }
    }

    private static String typeHierarchyKey(Map<String, Object> row) {
        return String.valueOf(firstNonNull(row, "type", "childType", "subType", "subtype", "sourceType"))
                + "->"
                + String.valueOf(firstNonNull(row, "parentType", "superType", "supertype", "category", "targetType"))
                + "|"
                + String.valueOf(firstNonNull(row, "source", "inferenceSource", "engine", "model"));
    }

    private static void appendInferredTypeCandidates(
            Map<String, LinkedHashSet<InferredTypeCandidate>> byEntity,
            String entityId,
            Object rawTypes) {
        if (entityId == null || rawTypes == null) return;
        if (rawTypes instanceof Iterable<?> iterable) {
            for (Object rawType : iterable) {
                appendInferredTypeCandidate(byEntity, entityId, rawType);
            }
            return;
        }
        appendInferredTypeCandidate(byEntity, entityId, rawTypes);
    }

    private static void appendInferredTypeCandidate(
            Map<String, LinkedHashSet<InferredTypeCandidate>> byEntity,
            String entityId,
            Object rawType) {
        if (!(rawType instanceof String classIri) || classIri.isBlank()) return;
        String localName = localNameFromIri(classIri);
        if (localName == null || localName.isBlank()) return;
        byEntity.computeIfAbsent(entityId, k -> new LinkedHashSet<>())
                .add(new InferredTypeCandidate(localName, classIri));
    }

    private static List<Object> mergeOwlTypeCandidates(Object existing,
                                                        LinkedHashSet<InferredTypeCandidate> inferredTypes) {
        List<Object> candidates = new ArrayList<>();
        LinkedHashSet<String> seen = new LinkedHashSet<>();
        if (existing instanceof List<?> list) {
            for (Object item : list) {
                appendExistingTypeCandidate(candidates, seen, item);
            }
        } else if (existing != null) {
            appendExistingTypeCandidate(candidates, seen, existing);
        }
        for (InferredTypeCandidate inferredType : inferredTypes) {
            Map<String, Object> owlCandidate = owlTypeCandidateMetadata(inferredType);
            if (seen.add(typeCandidateKey(owlCandidate))) {
                candidates.add(owlCandidate);
            }
        }
        return candidates;
    }

    private static void appendExistingTypeCandidate(List<Object> candidates,
                                                    LinkedHashSet<String> seen,
                                                    Object existing) {
        if (existing instanceof Map<?, ?> rawMap) {
            Map<String, Object> map = stringKeyMap(rawMap);
            if (hasCandidateType(map)) {
                if (seen.add(typeCandidateKey(map))) {
                    candidates.add(map);
                }
                return;
            }
            for (Map.Entry<String, Object> entry : map.entrySet()) {
                Map<String, Object> normalized = typeCandidateMapEntry(entry.getKey(), entry.getValue());
                if (normalized != null && seen.add(typeCandidateKey(normalized))) {
                    candidates.add(normalized);
                }
            }
            return;
        }
        if (existing instanceof String type && !type.isBlank()) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            normalized.put("type", type.trim());
            if (seen.add(typeCandidateKey(normalized))) {
                candidates.add(normalized);
            }
        }
    }

    private static Map<String, Object> typeCandidateMapEntry(String type, Object value) {
        if (type == null || type.isBlank()) {
            return null;
        }
        Map<String, Object> normalized = new LinkedHashMap<>();
        normalized.put("type", type.trim());
        if (value instanceof Number number) {
            normalized.put("confidence", number.doubleValue());
        } else if (value instanceof Map<?, ?> nested) {
            Map<String, Object> nestedMap = stringKeyMap(nested);
            Object confidence = firstNonNull(nestedMap,
                    "confidence", "score", "probability", "posterior", "truthValue");
            if (confidence instanceof Number number) {
                normalized.put("confidence", number.doubleValue());
            }
            copyIfPresent(nestedMap, normalized, "source", "source", "inferenceSource", "engine", "model");
            copyIfPresent(nestedMap, normalized, "basis", "basis", "basisType", "reason", "ruleId");
            Object evidence = firstNonNull(nestedMap, "evidence", "evidenceIds", "findings", "bindings");
            if (evidence != null) {
                normalized.put("evidence", evidence);
            }
        }
        return normalized;
    }

    private static Map<String, Object> stringKeyMap(Map<?, ?> rawMap) {
        Map<String, Object> map = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
            if (entry.getKey() != null && entry.getValue() != null) {
                map.put(String.valueOf(entry.getKey()), entry.getValue());
            }
        }
        return map;
    }

    private static boolean hasCandidateType(Map<String, Object> map) {
        return firstNonNull(map, "type", "candidateType", "typeName", "inferredType", "label", "iri") != null;
    }

    private static Object firstNonNull(Map<String, Object> map, String... keys) {
        for (String key : keys) {
            Object value = map.get(key);
            if (value != null) {
                return value;
            }
        }
        return null;
    }

    private static Double doubleValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    private static void copyIfPresent(Map<String, Object> from,
                                      Map<String, Object> to,
                                      String targetKey,
                                      String... sourceKeys) {
        Object value = firstNonNull(from, sourceKeys);
        if (value != null) {
            to.put(targetKey, value);
        }
    }

    private static String typeCandidateKey(Map<String, Object> candidate) {
        return String.valueOf(firstNonNull(candidate,
                "type", "candidateType", "typeName", "inferredType", "label", "iri"))
                + "|"
                + String.valueOf(firstNonNull(candidate, "source", "inferenceSource", "engine", "model"))
                + "|"
                + String.valueOf(firstNonNull(candidate, "basis", "basisType", "reason", "ruleId"));
    }

    private static Map<String, Object> owlTypeCandidateMetadata(InferredTypeCandidate inferredType) {
        Map<String, Object> candidate = new LinkedHashMap<>();
        candidate.put("type", inferredType.localName());
        candidate.put("confidence", 1.0d);
        candidate.put("source", "owl-rl");
        candidate.put("basis", "class-subsumption");
        if (inferredType.classIri() != null && !inferredType.classIri().isBlank()) {
            candidate.put("evidence", List.of(inferredType.classIri()));
        }
        return candidate;
    }
}

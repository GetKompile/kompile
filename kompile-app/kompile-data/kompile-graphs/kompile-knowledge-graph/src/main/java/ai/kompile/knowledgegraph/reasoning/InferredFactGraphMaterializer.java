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
package ai.kompile.knowledgegraph.reasoning;

import ai.kompile.graph.reasoning.confidence.Opinion;
import ai.kompile.graph.reasoning.confidence.StrengthBand;
import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer.InferredGraphSink;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer.ParsedAtom;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.io.model.EdgeMetadata;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * [L-6] Persists probabilistic inferences (PSL / MEBN / FOL) back into the knowledge graph so they
 * accumulate across sessions instead of being discarded when the inference request ends.
 *
 * <p>The materialization <em>logic</em> (atom grammar, binary→relation / unary→attribute routing,
 * accounting) lives in the infra-free library's {@link InferredFactMaterializer}. This class is only
 * the store side: an {@link InferredGraphSink} that resolves the atom's entities in the live store and
 * writes binary predicates as {@link EdgeProvenance#INFERRED} edges (so {@code provenanceType}
 * round-trips — see M-10) and unary predicates as {@code inferred.<Pred>} node attributes. Because the
 * inferred edges are ordinary edges, they export through the normal portability path and travel with
 * the graph on snapshot/clone.</p>
 */
@Service
public class InferredFactGraphMaterializer {

    private static final Logger log = LoggerFactory.getLogger(InferredFactGraphMaterializer.class);

    private final KnowledgeGraphService graphService;
    private final ObjectMapper mapper;

    public InferredFactGraphMaterializer(KnowledgeGraphService graphService, ObjectMapper mapper) {
        this.graphService = graphService;
        this.mapper = mapper;
    }

    /** Outcome of a materialization run (store view: relations become edges). */
    public record MaterializationResult(int edgesCreated, int attributesSet, int skipped) {
        public int total() {
            return edgesCreated + attributesSet;
        }
    }

    /**
     * Materialize a batch of inferred facts into the live store for the given fact sheet. Delegates
     * the parsing/classification/accounting to the library and only supplies the store sink.
     *
     * @param facts       the inferred facts (e.g. from an {@code InferredFactStore} after a run)
     * @param factSheetId the fact sheet whose entities the atoms refer to
     * @return counts of edges created, node attributes set, and facts skipped
     */
    public MaterializationResult materialize(Collection<InferredFact> facts, Long factSheetId) {
        var result = InferredFactMaterializer.materialize(facts, new StoreSink(factSheetId));
        log.info("Materialized {} inferred edges + {} attributes ({} skipped) for fact sheet {}",
                result.relationsAdded(), result.attributesSet(), result.skipped(), factSheetId);
        return new MaterializationResult(result.relationsAdded(), result.attributesSet(), result.skipped());
    }

    /** Store sink: writes inferred relations as INFERRED edges and unary atoms as node attributes. */
    private final class StoreSink implements InferredGraphSink {

        private final Long factSheetId;

        StoreSink(Long factSheetId) {
            this.factSheetId = factSheetId;
        }

        @Override
        public boolean addRelation(ParsedAtom atom, InferredFact fact) {
            GraphNode from = resolveNode(atom.args().get(0));
            GraphNode to = resolveNode(atom.args().get(1));
            if (from == null || to == null) {
                return false; // can't anchor the inference to existing entities
            }
            try {
                // Check for an existing INFERRED edge with the same predicate for fusion
                Optional<GraphEdge> existing = findInferredEdge(
                        atom.predicate(), from.getNodeId(), to.getNodeId());

                if (existing.isPresent()) {
                    // Multi-source fusion: cumulative Jøsang fusion of prior + new opinion
                    double priorConfidence = existing.get().getConfidence() != null
                            ? existing.get().getConfidence()
                            : (existing.get().getWeight() != null ? existing.get().getWeight() : 0.5);
                    Opinion prior = Opinion.fromSoftTruth(priorConfidence, 1L);
                    Opinion newOp = Opinion.fromSoftTruth(fact.value(), 1L);
                    Opinion fused = prior.cumulativeFuse(newOp);
                    log.debug("InferredFactGraphMaterializer: fusing edge '{}': prior={} + new={} → fused={}",
                            fact.atomKey(), priorConfidence, fact.value(), fused.expectation());

                    graphService.createEdgeWithMetadata(
                            from.getNodeId(), to.getNodeId(),
                            EdgeType.USER_DEFINED,
                            fused.expectation(),           // fused weight
                            atom.predicate(),
                            "Inferred (fused): " + fact.atomKey(),
                            buildMetaJson(fact, fused),
                            EdgeProvenance.INFERRED,
                            factSheetId);
                } else {
                    graphService.createEdgeWithMetadata(
                            from.getNodeId(), to.getNodeId(),
                            EdgeType.USER_DEFINED,
                            fact.value(),                  // weight = soft-truth value
                            atom.predicate(),              // label → relationType
                            "Inferred: " + fact.atomKey(),
                            buildMetaJson(fact, null),
                            EdgeProvenance.INFERRED,
                            factSheetId);
                }
                return true;
            } catch (Exception e) {
                log.warn("Failed to persist inferred edge for '{}': {}", fact.atomKey(), e.getMessage());
                return false;
            }
        }

        /**
         * Find an existing INFERRED edge between two nodes with the given predicate (relationType).
         * Searches in-memory from the fact-sheet edge list to avoid an extra DB round-trip per fact.
         */
        private Optional<GraphEdge> findInferredEdge(String predicate, String fromNodeId, String toNodeId) {
            try {
                List<GraphEdge> edges = graphService.getEdgesInFactSheet(factSheetId);
                return edges.stream()
                        .filter(e -> EdgeProvenance.INFERRED.equals(e.getProvenanceType()))
                        .filter(e -> predicate.equals(e.getRelationType()))
                        .filter(e -> e.getSourceNode() != null
                                && fromNodeId.equals(e.getSourceNode().getNodeId()))
                        .filter(e -> e.getTargetNode() != null
                                && toNodeId.equals(e.getTargetNode().getNodeId()))
                        .findFirst();
            } catch (Exception e) {
                log.debug("findInferredEdge: could not query edges for factSheet={}: {}", factSheetId, e.getMessage());
                return Optional.empty();
            }
        }

        @Override
        public boolean setAttribute(ParsedAtom atom, InferredFact fact) {
            GraphNode node = resolveNode(atom.args().get(0));
            if (node == null) {
                return false;
            }
            try {
                String base = "inferred." + atom.predicate();
                Map<String, Object> attrs = new LinkedHashMap<>();
                attrs.put(base, fact.value());
                attrs.put(base + ".confidence", fact.confidence());
                attrs.put(base + ".runId", fact.runId());
                attrs.put(base + ".version", fact.version());
                graphService.updateNode(node.getNodeId(), null, null, attrs);
                return true;
            } catch (Exception e) {
                log.warn("Failed to persist inferred attribute for '{}': {}", fact.atomKey(), e.getMessage());
                return false;
            }
        }

        /** Resolve an atom argument (an entity external id) to a node anywhere in the fact sheet. */
        private GraphNode resolveNode(String externalId) {
            for (NodeLevel level : NodeLevel.values()) {
                Optional<GraphNode> n = graphService.getNodeByExternalIdInFactSheet(externalId, level, factSheetId);
                if (n.isPresent()) {
                    return n.get();
                }
            }
            return null;
        }
    }

    /**
     * Build the edge metaJson carrying the inference's confidence, source run, and support.
     *
     * @param fact         the inferred fact
     * @param fusedOpinion when non-null, overrides the confidence with the fused expectation
     *                     and adds opinion components + fusion marker to metadata
     */
    private String buildMetaJson(InferredFact fact, @Nullable Opinion fusedOpinion) throws Exception {
        double effectiveConfidence = fusedOpinion != null ? fusedOpinion.expectation() : fact.confidence();
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("inferenceRunId", fact.runId());
        support.put("inferenceVersion", fact.version());
        support.put("strengthBand", StrengthBand.fromScalar(effectiveConfidence).name());
        if (fact.supportingRuleIds() != null && !fact.supportingRuleIds().isEmpty()) {
            support.put("supportingRuleIds", fact.supportingRuleIds());
        }
        if (fusedOpinion != null) {
            support.put("fused", true);
            support.put("fusedBelief", fusedOpinion.belief());
            support.put("fusedDisbelief", fusedOpinion.disbelief());
            support.put("fusedUncertainty", fusedOpinion.uncertainty());
        }
        EdgeMetadata meta = EdgeMetadata.builder()
                .confidence(effectiveConfidence)
                .provenance("inference:" + fact.runId())   // the run is the source
                .occurredAt(fact.inferredAt().toString())
                .metadata(support)                          // supporting rules + version
                .provenanceType(EdgeProvenance.INFERRED.name())
                .build();
        return mapper.writeValueAsString(meta);
    }
}

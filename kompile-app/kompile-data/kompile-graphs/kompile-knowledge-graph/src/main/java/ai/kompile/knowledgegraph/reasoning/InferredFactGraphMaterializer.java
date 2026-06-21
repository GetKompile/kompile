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

import ai.kompile.graph.reasoning.fol.InferredFact;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer.InferredGraphSink;
import ai.kompile.graph.reasoning.fol.InferredFactMaterializer.ParsedAtom;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.io.model.EdgeMetadata;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.LinkedHashMap;
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
                graphService.createEdgeWithMetadata(
                        from.getNodeId(), to.getNodeId(),
                        EdgeType.USER_DEFINED,
                        fact.value(),                  // weight = soft-truth value
                        atom.predicate(),              // label → relationType
                        "Inferred: " + fact.atomKey(),
                        buildMetaJson(fact),
                        EdgeProvenance.INFERRED,
                        factSheetId);
                return true;
            } catch (Exception e) {
                log.warn("Failed to persist inferred edge for '{}': {}", fact.atomKey(), e.getMessage());
                return false;
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

    /** Build the edge metaJson carrying the inference's confidence, source run, and support. */
    private String buildMetaJson(InferredFact fact) throws Exception {
        Map<String, Object> support = new LinkedHashMap<>();
        support.put("inferenceRunId", fact.runId());
        support.put("inferenceVersion", fact.version());
        if (fact.supportingRuleIds() != null && !fact.supportingRuleIds().isEmpty()) {
            support.put("supportingRuleIds", fact.supportingRuleIds());
        }
        EdgeMetadata meta = EdgeMetadata.builder()
                .confidence(fact.confidence())
                .provenance("inference:" + fact.runId())   // the run is the source
                .occurredAt(fact.inferredAt().toString())
                .metadata(support)                          // supporting rules + version
                .provenanceType(EdgeProvenance.INFERRED.name())
                .build();
        return mapper.writeValueAsString(meta);
    }
}

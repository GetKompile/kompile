/*
 *   Copyright 2025 Kompile Inc.
 *
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *
 *  http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 * limitations under the License.
 */

package ai.kompile.crawl.graph.ontology;

import ai.kompile.core.graphrag.conformance.OntologyProjectionProvider;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Tags graph nodes (and optionally edges) as conformant or non-conformant against the ontology
 * bound to a fact sheet.
 *
 * <p><b>LENIENT policy</b>: this tagger NEVER deletes or drops any node or edge. It only writes
 * two metadata markers into each node's existing metadata map:
 * <ul>
 *   <li>{@code ontology.conformant} — {@code "true"} or {@code "false"}</li>
 *   <li>{@code ontology.violation} — human-readable reason when {@code "false"}; absent when
 *       conformant</li>
 * </ul>
 *
 * <p><b>NO-OP conditions</b> (returns zero counts, zero writes):
 * <ul>
 *   <li>{@link OntologyProjectionProvider} was not wired (null) — no Spring bean provides it</li>
 *   <li>{@link OntologyProjectionProvider#hasBoundOntology(Long)} returns {@code false}</li>
 *   <li>{@link OntologyProjectionProvider#allowedEntityTypes(Long)} returns an empty list</li>
 *   <li>{@link KnowledgeGraphService} was not wired (null)</li>
 * </ul>
 *
 * <p>Reaching {@link KnowledgeGraphService}: injected {@code @Autowired(required=false)}, matching
 * the pattern established by {@link ai.kompile.crawl.graph.GraphPersistenceHelper} and all sibling
 * crawl-graph collaborators.</p>
 *
 * <p>Node-metadata update path: reads {@link GraphNode#getMetadata()} (store-agnostic parsed view),
 * merges the two conformance keys, then persists via
 * {@link KnowledgeGraphService#updateNode(String, String, String, Map)} — the same path used by
 * {@code EntityCategoryServiceImpl}, {@code EntityNormalizationService}, and other enrichment
 * services.</p>
 */
@Component
public class OntologyConformanceTagger {

    private static final Logger log = LoggerFactory.getLogger(OntologyConformanceTagger.class);

    /**
     * Metadata key written to each node indicating whether it conforms to the bound ontology.
     * Value is the string {@code "true"} or {@code "false"}.
     */
    public static final String META_CONFORMANT = "ontology.conformant";

    /**
     * Metadata key written to non-conforming nodes with a human-readable violation reason.
     * Absent on conforming nodes (key is removed if previously set).
     */
    public static final String META_VIOLATION = "ontology.violation";

    /**
     * Metadata key written to non-conforming edges with a human-readable violation reason.
     * Absent on conforming edges.
     */
    public static final String META_EDGE_VIOLATION = "ontology.edge.violation";

    /** Chunk size for {@link KnowledgeGraphService#updateNodeKgeMetadataBatch} flush calls. */
    private static final int BATCH_CHUNK = 512;

    @Autowired(required = false)
    @Nullable
    private OntologyProjectionProvider ontologyProvider;

    @Autowired(required = false)
    @Nullable
    private KnowledgeGraphService knowledgeGraphService;

    /**
     * Result counters from a single {@link #tag} invocation.
     *
     * @param nodesTaggedConformant    entity nodes tagged {@code ontology.conformant=true}
     * @param nodesTaggedNonConformant entity nodes tagged {@code ontology.conformant=false}
     * @param edgesTaggedConformant    edges tagged conformant (0 when relationship checking disabled)
     * @param edgesTaggedNonConformant edges tagged non-conformant
     */
    public record TagResult(
            int nodesTaggedConformant,
            int nodesTaggedNonConformant,
            int edgesTaggedConformant,
            int edgesTaggedNonConformant) {

        /** Zero-counts result; returned when the stage is a no-op. */
        public static TagResult empty() {
            return new TagResult(0, 0, 0, 0);
        }

        /** Total nodes processed (conformant + non-conformant). */
        public int totalNodesTagged() {
            return nodesTaggedConformant + nodesTaggedNonConformant;
        }

        /** Total edges processed (conformant + non-conformant). */
        public int totalEdgesTagged() {
            return edgesTaggedConformant + edgesTaggedNonConformant;
        }
    }

    /**
     * Tag all ENTITY nodes (and optionally edges) in {@code factSheetId} for ontology conformance.
     *
     * <p>The stage is a clean no-op (returns {@link TagResult#empty()}) when:
     * <ul>
     *   <li>{@code ontologyProvider} is null (bean absent)</li>
     *   <li>{@code knowledgeGraphService} is null (bean absent)</li>
     *   <li>{@link OntologyProjectionProvider#hasBoundOntology} is {@code false}</li>
     *   <li>{@link OntologyProjectionProvider#allowedEntityTypes} is empty</li>
     * </ul>
     *
     * @param factSheetId    fact sheet to tag
     * @param tagEdges       when {@code true} edges are also checked against
     *                       {@link OntologyProjectionProvider#allowedRelationshipTypes}
     * @return counts of tagged conformant/non-conformant nodes and edges
     */
    public TagResult tag(long factSheetId, boolean tagEdges) {
        // ── Guard: required collaborators absent ──────────────────────────────
        if (ontologyProvider == null) {
            log.debug("[OntologyConformanceTagger factSheet={}] no bound ontology, skipping: " +
                    "OntologyProjectionProvider not wired", factSheetId);
            return TagResult.empty();
        }
        if (knowledgeGraphService == null) {
            log.debug("[OntologyConformanceTagger factSheet={}] no bound ontology, skipping: " +
                    "KnowledgeGraphService not wired", factSheetId);
            return TagResult.empty();
        }

        // ── Guard: no bound ontology ──────────────────────────────────────────
        if (!ontologyProvider.hasBoundOntology(factSheetId)) {
            log.debug("[OntologyConformanceTagger factSheet={}] no bound ontology, skipping", factSheetId);
            return TagResult.empty();
        }

        List<String> allowedTypes = ontologyProvider.allowedEntityTypes(factSheetId);
        if (allowedTypes == null || allowedTypes.isEmpty()) {
            log.debug("[OntologyConformanceTagger factSheet={}] no bound ontology, skipping: " +
                    "allowedEntityTypes is empty", factSheetId);
            return TagResult.empty();
        }

        // Build a lowercase set for case-insensitive membership check
        Set<String> allowedLower = allowedTypes.stream()
                .map(t -> t.toLowerCase(Locale.ROOT))
                .collect(Collectors.toSet());

        log.info("[OntologyConformanceTagger factSheet={}] tagging nodes against ontology " +
                "({} allowed entity types)", factSheetId, allowedLower.size());

        // ── Tag ENTITY nodes (batched via updateNodeKgeMetadataBatch) ────────
        int nodesConformant    = 0;
        int nodesNonConformant = 0;

        List<GraphNode> entityNodes = knowledgeGraphService.getNodesByTypeInFactSheet(
                factSheetId, NodeLevel.ENTITY);

        // Buffer tag-delta updates (only the two conformance keys, not the full metadata).
        // updateNodeKgeMetadataBatch MERGES, so existing metadata keys are preserved.
        List<KnowledgeGraphService.NodeMetadataUpdate> nodeBatch = new ArrayList<>(BATCH_CHUNK);

        for (GraphNode node : entityNodes) {
            try {
                String entityType = extractEntityType(node);
                boolean conformant = entityType != null
                        && allowedLower.contains(entityType.toLowerCase(Locale.ROOT));

                Map<String, Object> tagDelta = new LinkedHashMap<>(2);
                if (conformant) {
                    tagDelta.put(META_CONFORMANT, "true");
                    nodesConformant++;
                } else {
                    tagDelta.put(META_CONFORMANT, "false");
                    String reason = entityType == null
                            ? "entity type unknown (null)"
                            : "unknown entity type '" + entityType + "' not in bound ontology";
                    tagDelta.put(META_VIOLATION, reason);
                    nodesNonConformant++;
                }
                nodeBatch.add(new KnowledgeGraphService.NodeMetadataUpdate(node.getNodeId(), tagDelta));

                if (nodeBatch.size() >= BATCH_CHUNK) {
                    knowledgeGraphService.updateNodeKgeMetadataBatch(nodeBatch);
                    nodeBatch.clear();
                }
            } catch (Exception e) {
                log.debug("[OntologyConformanceTagger factSheet={}] Failed to tag node {}: {}",
                        factSheetId, node.getNodeId(), e.getMessage());
            }
        }
        if (!nodeBatch.isEmpty()) {
            knowledgeGraphService.updateNodeKgeMetadataBatch(nodeBatch);
            // nodeBatch goes out of scope here — no need to clear
        }

        log.info("[OntologyConformanceTagger factSheet={}] node tagging complete: " +
                "conformant={}, nonConformant={}", factSheetId, nodesConformant, nodesNonConformant);

        // ── Optionally tag edges ───────────────────────────────────────────────
        int edgesConformant    = 0;
        int edgesNonConformant = 0;

        if (tagEdges) {
            List<String> allowedRelTypes = ontologyProvider.allowedRelationshipTypes(factSheetId);
            if (allowedRelTypes != null && !allowedRelTypes.isEmpty()) {
                Set<String> allowedRelLower = allowedRelTypes.stream()
                        .map(r -> r.toLowerCase(Locale.ROOT))
                        .collect(Collectors.toSet());

                List<GraphEdge> edges = knowledgeGraphService.getEdgesInFactSheet(factSheetId);
                // Buffer non-conformant edge metadata updates; flush in one batch RPC
                List<KnowledgeGraphService.EdgeMetadataUpdate> edgeBatch = new ArrayList<>();
                for (GraphEdge edge : edges) {
                    try {
                        String relType = extractRelationshipType(edge);
                        boolean conformant = relType != null
                                && allowedRelLower.contains(relType.toLowerCase(Locale.ROOT));

                        if (conformant) {
                            edgesConformant++;
                            // conformant edges: no metadata write needed (keep the store lean)
                        } else {
                            String reason = relType == null
                                    ? "relationship type unknown (null)"
                                    : "unknown relationship type '" + relType + "' not in bound ontology";
                            edgeBatch.add(new KnowledgeGraphService.EdgeMetadataUpdate(
                                    edge.getEdgeId(), Map.of(META_EDGE_VIOLATION, reason)));
                            edgesNonConformant++;
                        }
                    } catch (Exception e) {
                        log.debug("[OntologyConformanceTagger factSheet={}] Failed to tag edge {}: {}",
                                factSheetId, edge.getEdgeId(), e.getMessage());
                    }
                }
                if (!edgeBatch.isEmpty()) {
                    knowledgeGraphService.updateEdgeMetadataBatch(edgeBatch);
                }

                log.info("[OntologyConformanceTagger factSheet={}] edge tagging complete: " +
                        "conformant={}, nonConformant={}", factSheetId, edgesConformant, edgesNonConformant);
            }
        }

        return new TagResult(nodesConformant, nodesNonConformant, edgesConformant, edgesNonConformant);
    }

    /**
     * Extract the entity type string from a node's metadata, using the {@code entity_type} key
     * that the extraction pipeline writes (set by {@code GraphPersistenceHelper},
     * {@code BulkGraphSyncService}, and {@code MatrixGraphRagService}).
     *
     * @param node the node to inspect
     * @return the entity type string, or {@code null} if absent or blank
     */
    private String extractEntityType(GraphNode node) {
        if (node == null) return null;
        Map<String, Object> meta = node.getMetadata();
        if (meta == null) return null;
        Object val = meta.get("entity_type");
        if (val instanceof String s && !s.isBlank()) {
            return s.trim();
        }
        return null;
    }

    /**
     * Extract the relationship type string from an edge, using the {@code relationType} column
     * (JPA) or adjacency key (matrix store) exposed via {@link GraphEdge#getRelationType()},
     * with {@link GraphEdge#getDescription()} as a fallback.
     *
     * @param edge the edge to inspect
     * @return the relationship type string, or {@code null} if absent or blank
     */
    private String extractRelationshipType(GraphEdge edge) {
        if (edge == null) return null;
        // relationType is the semantic label written by createEdge(…, relationType, …)
        String rel = edge.getRelationType();
        if (rel != null && !rel.isBlank()) {
            return rel.trim();
        }
        // Fallback: description often carries the semantic label (e.g. "WORKS_AT")
        String desc = edge.getDescription();
        if (desc != null && !desc.isBlank()) {
            return desc.trim();
        }
        return null;
    }
}

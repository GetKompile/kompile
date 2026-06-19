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

package ai.kompile.knowledgegraph.resolution;

import ai.kompile.knowledgegraph.domain.EdgeType;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Materializes product barcodes as first-class graph structure: a canonical
 * {@link NodeLevel#IDENTIFIER} node per GTIN-14, linked to product entities via
 * {@link EdgeType#RESOLVES_TO} edges. This is the durable, queryable home of the
 * many-to-many observed-code → product mapping:
 *
 * <ul>
 *   <li><b>Many codes → one product</b> — vendor/format variants converge as
 *       several identifier nodes all resolving to the same product entity.</li>
 *   <li><b>One code → many products</b> — a recycled / reassigned code appears as
 *       a single identifier node resolving to more than one product. That is a
 *       <em>collision</em>, surfaced for review rather than silently merged
 *       (the "corroborate, else queue" policy).</li>
 * </ul>
 *
 * <p>The link-planning core ({@link #planLinks}) and observation extraction
 * ({@link #collectObservations}) are pure and unit-testable without a database;
 * {@link #materialize} performs the graph I/O.
 */
@Service
public class BarcodeIdentityGraphService {

    private static final Logger log = LoggerFactory.getLogger(BarcodeIdentityGraphService.class);

    /** External-id prefix for identifier nodes; the key is "gtin:" + GTIN-14. */
    static final String GTIN_EXTERNAL_ID_PREFIX = "gtin:";

    private final KnowledgeGraphService knowledgeGraphService;

    public BarcodeIdentityGraphService(KnowledgeGraphService knowledgeGraphService) {
        this.knowledgeGraphService = knowledgeGraphService;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    /** One product carrying one usable GTIN-14, with where it was observed. */
    public record ProductObservation(String productNodeId, String productTitle, String gtin14, String source) {
    }

    /** A planned identifier → product edge with its observation vote count. */
    public record IdentifierLink(String gtin14, String productNodeId, String productTitle, int votes) {
    }

    /** A GTIN-14 that resolves to more than one product — a recycled-code collision. */
    public record IdentifierCollision(String gtin14, List<String> productNodeIds, List<String> productTitles) {
    }

    /** The full plan derived from a set of observations. */
    public record IdentifierLinkPlan(Set<String> gtins, List<IdentifierLink> links,
                                     List<IdentifierCollision> collisions) {
    }

    /** Outcome of a materialize run. */
    public record MaterializeResult(int identifierNodes, int resolveEdges,
                                    List<IdentifierCollision> collisions) {
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PURE PLANNING CORE (unit-testable)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Group observations by canonical GTIN-14 into identifier→product links and
     * detect collisions (a GTIN resolving to more than one product). Votes count
     * the distinct product-entity observations backing each link.
     */
    static IdentifierLinkPlan planLinks(List<ProductObservation> observations) {
        // gtin14 -> (productNodeId -> votes), insertion-ordered for determinism.
        Map<String, LinkedHashMap<String, Integer>> byGtin = new LinkedHashMap<>();
        Map<String, String> productTitles = new LinkedHashMap<>();

        for (ProductObservation o : observations) {
            if (o.gtin14() == null || o.gtin14().isBlank() || o.productNodeId() == null) {
                continue;
            }
            byGtin.computeIfAbsent(o.gtin14(), k -> new LinkedHashMap<>())
                    .merge(o.productNodeId(), 1, Integer::sum);
            productTitles.putIfAbsent(o.productNodeId(), o.productTitle());
        }

        List<IdentifierLink> links = new ArrayList<>();
        List<IdentifierCollision> collisions = new ArrayList<>();
        for (Map.Entry<String, LinkedHashMap<String, Integer>> e : byGtin.entrySet()) {
            String gtin = e.getKey();
            Map<String, Integer> products = e.getValue();
            for (Map.Entry<String, Integer> pe : products.entrySet()) {
                links.add(new IdentifierLink(gtin, pe.getKey(), productTitles.get(pe.getKey()), pe.getValue()));
            }
            if (products.size() > 1) {
                List<String> ids = new ArrayList<>(products.keySet());
                List<String> titles = new ArrayList<>();
                for (String id : ids) {
                    titles.add(productTitles.get(id));
                }
                collisions.add(new IdentifierCollision(gtin, ids, titles));
            }
        }
        return new IdentifierLinkPlan(new LinkedHashSet<>(byGtin.keySet()), links, collisions);
    }

    /**
     * Extract product observations from a single graph node's metadata: the
     * canonical {@code observedGtins} list (written by Stage-1 resolution) plus any
     * raw barcode attribute keys. Every candidate is canonicalized and filtered to
     * codes usable as a global identity (valid check digit, non-restricted prefix).
     */
    static List<ProductObservation> collectObservations(GraphNode node) {
        Map<String, Object> meta = node.getMetadata();
        if (meta == null || meta.isEmpty()) {
            return List.of();
        }
        String source = firstNonBlank(meta, "vendor", "source", "sourceType", "sourceDocumentId");

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        Object observed = meta.get("observedGtins");
        if (observed != null) {
            for (String s : observed.toString().split(",")) {
                candidates.add(s.trim());
            }
        }
        for (Map.Entry<String, Object> e : meta.entrySet()) {
            if (BarcodeNormalizer.isBarcodeAttributeKey(e.getKey()) && e.getValue() != null) {
                candidates.add(e.getValue().toString());
            }
        }

        LinkedHashSet<String> gtins = new LinkedHashSet<>();
        for (String c : candidates) {
            if (c == null || c.isBlank()) {
                continue;
            }
            BarcodeNormalizer.BarcodeId id = BarcodeNormalizer.parse(c);
            if (id.usableAsGlobalIdentity()) {
                gtins.add(id.gtin14());
            }
        }

        List<ProductObservation> observations = new ArrayList<>();
        for (String g : gtins) {
            observations.add(new ProductObservation(node.getNodeId(), node.getTitle(), g, source));
        }
        return observations;
    }

    private static String firstNonBlank(Map<String, Object> meta, String... keys) {
        for (String key : keys) {
            Object v = meta.get(key);
            if (v != null && !v.toString().isBlank()) {
                return v.toString();
            }
        }
        return "unknown";
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // GRAPH MATERIALIZATION (I/O)
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Build (or refresh) identifier nodes and RESOLVES_TO edges for every product
     * entity in scope. Existing edges are left intact (idempotent). Returns counts
     * plus any recycled-code collisions needing review.
     *
     * @param factSheetId optional fact-sheet scope (null = all entities)
     */
    @Transactional
    public MaterializeResult materialize(Long factSheetId) {
        IdentifierLinkPlan plan = planLinks(collectAllObservations(factSheetId));

        Map<String, String> idNodeIdByGtin = new LinkedHashMap<>();
        for (String gtin : plan.gtins()) {
            idNodeIdByGtin.put(gtin, upsertIdentifierNode(gtin, factSheetId));
        }

        int edgeCount = 0;
        for (IdentifierLink link : plan.links()) {
            String idNodeId = idNodeIdByGtin.get(link.gtin14());
            if (idNodeId == null) {
                continue;
            }
            if (!knowledgeGraphService.edgeExists(idNodeId, link.productNodeId())) {
                double weight = Math.min(1.0, 0.6 + 0.1 * link.votes());
                knowledgeGraphService.createEdge(idNodeId, link.productNodeId(),
                        EdgeType.RESOLVES_TO, weight,
                        "Observed " + link.votes() + "x → " + link.productTitle());
                edgeCount++;
            }
        }

        if (!plan.collisions().isEmpty()) {
            log.warn("Barcode identity: {} recycled-code collision(s) need review (gtin → products): {}",
                    plan.collisions().size(), plan.collisions());
        }
        return new MaterializeResult(idNodeIdByGtin.size(), edgeCount, plan.collisions());
    }

    /** Recycled-code collisions in scope, computed without mutating the graph. */
    @Transactional(readOnly = true)
    public List<IdentifierCollision> previewCollisions(Long factSheetId) {
        return planLinks(collectAllObservations(factSheetId)).collisions();
    }

    /** Product node IDs that a raw barcode resolves to (empty if none / unparseable). */
    @Transactional(readOnly = true)
    public List<String> productNodeIdsForGtin(String rawCode) {
        String gtin14 = BarcodeNormalizer.parse(rawCode).gtin14();
        if (gtin14 == null) {
            return List.of();
        }
        Optional<GraphNode> idNode = knowledgeGraphService.getNodeByExternalId(
                GTIN_EXTERNAL_ID_PREFIX + gtin14, NodeLevel.IDENTIFIER);
        if (idNode.isEmpty()) {
            return List.of();
        }
        return knowledgeGraphService.getEdgesByType(idNode.get().getNodeId(), EdgeType.RESOLVES_TO).stream()
                .map(e -> e.getTargetNode().getNodeId())
                .distinct()
                .toList();
    }

    /** Canonical GTIN-14s that resolve to a given product node. */
    @Transactional(readOnly = true)
    public List<String> gtinsForProduct(String productNodeId) {
        return knowledgeGraphService.getEdgesForNode(productNodeId).stream()
                .filter(e -> e.getEdgeType() == EdgeType.RESOLVES_TO)
                .filter(e -> productNodeId.equals(e.getTargetNode().getNodeId()))
                .map(e -> e.getSourceNode().getTitle())
                .distinct()
                .toList();
    }

    private List<ProductObservation> collectAllObservations(Long factSheetId) {
        List<GraphNode> entities = factSheetId == null
                ? knowledgeGraphService.getNodesByType(NodeLevel.ENTITY)
                : knowledgeGraphService.getNodesByTypeInFactSheet(factSheetId, NodeLevel.ENTITY);
        List<ProductObservation> observations = new ArrayList<>();
        for (GraphNode node : entities) {
            observations.addAll(collectObservations(node));
        }
        return observations;
    }

    private String upsertIdentifierNode(String gtin14, Long factSheetId) {
        String externalId = GTIN_EXTERNAL_ID_PREFIX + gtin14;
        Optional<GraphNode> existing = factSheetId == null
                ? knowledgeGraphService.getNodeByExternalId(externalId, NodeLevel.IDENTIFIER)
                : knowledgeGraphService.getNodeByExternalIdInFactSheet(externalId, NodeLevel.IDENTIFIER, factSheetId);
        if (existing.isPresent()) {
            return existing.get().getNodeId();
        }
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("gtin14", gtin14);
        meta.put("identifierType", "GTIN");
        GraphNode node = knowledgeGraphService.createNode(NodeLevel.IDENTIFIER, externalId,
                gtin14, "Barcode " + gtin14, meta, factSheetId);
        return node.getNodeId();
    }
}

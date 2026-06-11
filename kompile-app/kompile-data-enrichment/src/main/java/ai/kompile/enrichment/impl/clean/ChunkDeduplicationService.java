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
package ai.kompile.enrichment.impl.clean;

import ai.kompile.core.embeddings.VectorStore;
import ai.kompile.core.indexers.CrossIndexIdResolver;
import ai.kompile.core.indexers.IndexerService;
import ai.kompile.enrichment.config.EnrichmentConfig;
import ai.kompile.enrichment.impl.EnrichmentAuditService;
import ai.kompile.knowledgegraph.domain.EdgeProvenance;
import ai.kompile.knowledgegraph.domain.GraphEdge;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.repository.GraphEdgeRepository;
import ai.kompile.knowledgegraph.repository.GraphNodeRepository;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * MinHash-based near-duplicate chunk detection and merge.
 * Algorithm: k-shingle → MinHash signatures → Jaccard estimation → merge duplicates.
 * O(n) per chunk, no LLM or embeddings needed.
 */
@Service("enrichmentChunkDeduplicationService")
public class ChunkDeduplicationService {
    private static final Logger log = LoggerFactory.getLogger(ChunkDeduplicationService.class);

    private final GraphNodeRepository nodeRepository;
    private final GraphEdgeRepository edgeRepository;
    private final KnowledgeGraphService knowledgeGraphService;
    private final EnrichmentAuditService auditService;
    private final ObjectMapper objectMapper;
    private final VectorStore vectorStore;
    private final IndexerService indexerService;
    private final CrossIndexIdResolver crossIndexIdResolver;

    public ChunkDeduplicationService(GraphNodeRepository nodeRepository,
                                     GraphEdgeRepository edgeRepository,
                                     KnowledgeGraphService knowledgeGraphService,
                                     EnrichmentAuditService auditService,
                                     ObjectMapper objectMapper,
                                     @Autowired(required = false) VectorStore vectorStore,
                                     @Autowired(required = false) IndexerService indexerService,
                                     @Autowired(required = false) CrossIndexIdResolver crossIndexIdResolver) {
        this.nodeRepository = nodeRepository;
        this.edgeRepository = edgeRepository;
        this.knowledgeGraphService = knowledgeGraphService;
        this.auditService = auditService;
        this.objectMapper = objectMapper;
        this.vectorStore = vectorStore;
        this.indexerService = indexerService;
        this.crossIndexIdResolver = crossIndexIdResolver;
    }

    public int deduplicateChunks(Long factSheetId, String jobId, EnrichmentConfig config) {
        List<GraphNode> snippets = nodeRepository.findByFactSheetIdAndNodeType(factSheetId, NodeLevel.SNIPPET);
        if (snippets.size() < 2) {
            log.info("Fewer than 2 SNIPPET nodes for factSheet {}, skipping dedup", factSheetId);
            return 0;
        }

        int shingleSize = config.getDeduplicationShingleSize();
        double threshold = config.getDeduplicationJaccardThreshold();
        int numHashes = 128;

        // Build MinHash signatures for all snippets
        long[][] signatures = new long[snippets.size()][numHashes];
        long[] hashCoeffsA = new long[numHashes];
        long[] hashCoeffsB = new long[numHashes];
        Random rng = new Random(42);
        for (int i = 0; i < numHashes; i++) {
            hashCoeffsA[i] = rng.nextLong();
            hashCoeffsB[i] = rng.nextLong();
        }

        for (int i = 0; i < snippets.size(); i++) {
            String text = getSnippetText(snippets.get(i));
            Set<String> shingles = buildShingles(text, shingleSize);
            signatures[i] = computeMinHash(shingles, hashCoeffsA, hashCoeffsB, numHashes);
        }

        // Find duplicate pairs via signature comparison
        int deduped = 0;
        boolean[] deleted = new boolean[snippets.size()];

        for (int i = 0; i < snippets.size(); i++) {
            if (deleted[i]) continue;
            for (int j = i + 1; j < snippets.size(); j++) {
                if (deleted[j]) continue;
                double jaccard = estimateJaccard(signatures[i], signatures[j], numHashes);
                if (jaccard >= threshold) {
                    GraphNode winner = snippets.get(i);
                    GraphNode loser = snippets.get(j);

                    // Pick the winner: higher confidence, then longer description, then earlier created
                    if (shouldSwap(winner, loser)) {
                        winner = snippets.get(j);
                        loser = snippets.get(i);
                        deleted[i] = true;
                    } else {
                        deleted[j] = true;
                    }

                    mergeChunks(factSheetId, jobId, winner, loser, jaccard);
                    deduped++;
                }
            }
        }

        log.info("Deduplicated {} chunk pairs out of {} total snippets for factSheet {}",
                deduped, snippets.size(), factSheetId);
        return deduped;
    }

    private void mergeChunks(Long factSheetId, String jobId, GraphNode winner, GraphNode loser, double similarity) {
        try {
            String beforeJson = objectMapper.writeValueAsString(Map.of(
                    "nodeId", loser.getNodeId(),
                    "title", loser.getTitle() != null ? loser.getTitle() : "",
                    "externalId", loser.getExternalId() != null ? loser.getExternalId() : "",
                    "edgeCount", loser.getEdgeCount() != null ? loser.getEdgeCount() : 0
            ));

            // Redirect edges from loser to winner, preserving semantic edge data.
            List<GraphEdge> loserEdges = edgeRepository.findBySourceNodeIdOrTargetNodeId(loser.getId());
            List<String> edgeIdsToDelete = new ArrayList<>();
            for (GraphEdge edge : loserEdges) {
                String srcId = edge.getSourceNode().getNodeId();
                String tgtId = edge.getTargetNode().getNodeId();
                String newSource = srcId.equals(loser.getNodeId()) ? winner.getNodeId() : srcId;
                String newTarget = tgtId.equals(loser.getNodeId()) ? winner.getNodeId() : tgtId;
                if (!newSource.equals(newTarget)
                        && !knowledgeGraphService.edgeExists(newSource, newTarget,
                        edge.getEdgeType(), firstNonBlank(edge.getLabel(), edge.getDescription()), edge.getFactSheetId())) {
                    EdgeProvenance prov = edge.getProvenance() != null
                            ? EdgeProvenance.valueOf(edge.getProvenance()) : EdgeProvenance.EXTRACTED;
                    knowledgeGraphService.createEdgeWithMetadata(newSource, newTarget,
                            edge.getEdgeType(), edge.getWeight(), edge.getLabel(), edge.getDescription(),
                            edge.getMetadataJson(), prov, edge.getFactSheetId());
                }
                edgeIdsToDelete.add(edge.getEdgeId());
            }

            if (!edgeIdsToDelete.isEmpty()) {
                knowledgeGraphService.deleteEdgesBulk(edgeIdsToDelete);
            }

            // Delete loser node from graph
            knowledgeGraphService.deleteNode(loser.getNodeId());

            // Delete from vector store and keyword index
            deleteFromIndices(loser);

            auditService.logAction(factSheetId, jobId, "CLEAN", "CHUNK_DEDUP",
                    loser.getNodeId(), "SNIPPET", beforeJson, null,
                    String.format("Merged duplicate chunk '%s' into '%s' (Jaccard=%.2f)",
                            loser.getTitle(), winner.getTitle(), similarity));
        } catch (Exception e) {
            log.error("Failed to merge chunk {} into {}: {}", loser.getNodeId(), winner.getNodeId(), e.getMessage());
        }
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void deleteFromIndices(GraphNode loser) {
        if (crossIndexIdResolver == null) {
            log.debug("CrossIndexIdResolver not available, skipping index cleanup for node {}", loser.getNodeId());
            return;
        }

        List<String> indexDocIds = crossIndexIdResolver.resolveIndexDocumentIds(loser.getExternalId());
        if (indexDocIds.isEmpty()) {
            log.debug("No index document IDs found for graph node externalId={}", loser.getExternalId());
            return;
        }

        if (vectorStore != null) {
            try {
                vectorStore.delete(indexDocIds);
                log.debug("Deleted {} entries from vector store for node {}", indexDocIds.size(), loser.getNodeId());
            } catch (Exception e) {
                log.warn("Failed to delete from vector store for node {}: {}", loser.getNodeId(), e.getMessage());
            }
        }

        if (indexerService != null) {
            try {
                indexerService.deleteFromKeywordIndex(indexDocIds);
                log.debug("Deleted {} entries from keyword index for node {}", indexDocIds.size(), loser.getNodeId());
            } catch (Exception e) {
                log.warn("Failed to delete from keyword index for node {}: {}", loser.getNodeId(), e.getMessage());
            }
        }
    }

    private boolean shouldSwap(GraphNode winner, GraphNode loser) {
        double wConf = winner.getConfidence() != null ? winner.getConfidence() : 0;
        double lConf = loser.getConfidence() != null ? loser.getConfidence() : 0;
        if (lConf > wConf) return true;
        if (lConf < wConf) return false;
        int wLen = winner.getDescription() != null ? winner.getDescription().length() : 0;
        int lLen = loser.getDescription() != null ? loser.getDescription().length() : 0;
        return lLen > wLen;
    }

    private String getSnippetText(GraphNode node) {
        String text = node.getContentPreview();
        if (text == null || text.isBlank()) {
            text = node.getDescription();
        }
        if (text == null || text.isBlank()) {
            text = node.getTitle();
        }
        return text != null ? text.toLowerCase().trim() : "";
    }

    Set<String> buildShingles(String text, int k) {
        Set<String> shingles = new HashSet<>();
        if (text.length() < k) {
            shingles.add(text);
            return shingles;
        }
        for (int i = 0; i <= text.length() - k; i++) {
            shingles.add(text.substring(i, i + k));
        }
        return shingles;
    }

    long[] computeMinHash(Set<String> shingles, long[] a, long[] b, int numHashes) {
        long[] sig = new long[numHashes];
        Arrays.fill(sig, Long.MAX_VALUE);
        for (String shingle : shingles) {
            long hash = shingle.hashCode();
            for (int i = 0; i < numHashes; i++) {
                long val = a[i] * hash + b[i];
                if (val < sig[i]) {
                    sig[i] = val;
                }
            }
        }
        return sig;
    }

    double estimateJaccard(long[] sig1, long[] sig2, int numHashes) {
        int matches = 0;
        for (int i = 0; i < numHashes; i++) {
            if (sig1[i] == sig2[i]) matches++;
        }
        return (double) matches / numHashes;
    }
}

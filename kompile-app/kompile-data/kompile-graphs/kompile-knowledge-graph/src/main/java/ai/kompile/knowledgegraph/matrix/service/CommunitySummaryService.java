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
package ai.kompile.knowledgegraph.matrix.service;

import ai.kompile.core.embeddings.EmbeddingModel;
import ai.kompile.core.llm.chat.LLMChat;
import ai.kompile.knowledgegraph.matrix.algorithms.MatrixGraphAlgorithms;
import ai.kompile.knowledgegraph.matrix.model.AdjacencyMatrixGraph;
import ai.kompile.knowledgegraph.matrix.model.MatrixGraphNode;
import org.nd4j.linalg.api.ndarray.INDArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Microsoft-GraphRAG-style community detection + summarization for GLOBAL ("sensemaking") search.
 * <p>
 * Detects communities in the live graph (via {@link MatrixGraphAlgorithms#spectralClustering}, which
 * itself falls back to connected components), then has the LLM write a concise "community report" for
 * each. GLOBAL queries are answered by selecting the query-relevant community reports rather than the
 * previous query-agnostic top-PageRank-nodes-plus-stats approach. Reports are cached per graph and
 * rebuilt on demand.
 * </p>
 */
@Service
public class CommunitySummaryService {

    private static final Logger log = LoggerFactory.getLogger(CommunitySummaryService.class);

    /** Upper bound on communities to detect (keeps summarization cost bounded). */
    private static final int MAX_COMMUNITIES = 20;
    /** Communities smaller than this are skipped (singletons add noise, not sensemaking value). */
    private static final int MIN_COMMUNITY_SIZE = 2;
    private static final int MAX_DIGEST_ENTITIES = 30;
    private static final int MAX_DIGEST_RELATIONSHIPS = 40;

    @Autowired(required = false)
    private EmbeddingModel embeddingModel;
    @Autowired(required = false)
    private LLMChat llmChat;

    private final Map<String, List<CommunityReport>> cache = new ConcurrentHashMap<>();

    public CommunitySummaryService() {}

    /** Test constructor. */
    public CommunitySummaryService(EmbeddingModel embeddingModel, LLMChat llmChat) {
        this.embeddingModel = embeddingModel;
        this.llmChat = llmChat;
    }

    /**
     * A summarized community of related entities.
     *
     * @param communityId      detector-assigned community id
     * @param summary          LLM-written report (or the raw digest if no LLM is configured)
     * @param memberNodeIds    node ids belonging to the community
     * @param summaryEmbedding embedding of {@code summary} for query-relevance ranking (nullable)
     */
    public record CommunityReport(int communityId, String summary, List<String> memberNodeIds,
                                  INDArray summaryEmbedding) {}

    /** Returns cached reports for the graph, building (and caching) them on first request. */
    public List<CommunityReport> getOrBuildReports(AdjacencyMatrixGraph graph) {
        return cache.computeIfAbsent(graph.getGraphId(), id -> computeReports(graph));
    }

    /** Forces a (re)build of the reports for the graph, replacing any cached value. */
    public List<CommunityReport> rebuildReports(AdjacencyMatrixGraph graph) {
        List<CommunityReport> reports = computeReports(graph);
        cache.put(graph.getGraphId(), reports);
        return reports;
    }

    /** Drops cached reports for a graph (e.g. after the graph changes). */
    public void invalidate(String graphId) {
        cache.remove(graphId);
    }

    private List<CommunityReport> computeReports(AdjacencyMatrixGraph graph) {
        int n = graph.getNodeCount();
        if (n == 0) {
            return List.of();
        }
        // Adaptive community count; spectralClustering falls back to connected components internally.
        int k = Math.max(1, Math.min(MAX_COMMUNITIES, (int) Math.round(Math.sqrt(n / 2.0))));
        Map<String, Integer> assignment = MatrixGraphAlgorithms.spectralClustering(graph, k);

        Map<Integer, List<String>> byCommunity = new HashMap<>();
        for (Map.Entry<String, Integer> e : assignment.entrySet()) {
            byCommunity.computeIfAbsent(e.getValue(), x -> new ArrayList<>()).add(e.getKey());
        }

        List<CommunityReport> reports = new ArrayList<>();
        for (Map.Entry<Integer, List<String>> e : byCommunity.entrySet()) {
            List<String> memberIds = e.getValue();
            if (memberIds.size() < MIN_COMMUNITY_SIZE) {
                continue;
            }
            String digest = buildDigest(graph, memberIds);
            String summary = summarize(digest);
            INDArray emb = null;
            if (embeddingModel != null && summary != null && !summary.isBlank()) {
                INDArray e2 = embeddingModel.embed(summary);
                if (e2 != null && !e2.isEmpty()) {
                    emb = e2;
                }
            }
            reports.add(new CommunityReport(e.getKey(), summary, memberIds, emb));
        }
        log.info("Built {} community reports for graph {} ({} nodes, {} communities detected)",
                reports.size(), graph.getGraphId(), n, byCommunity.size());
        return reports;
    }

    private String buildDigest(AdjacencyMatrixGraph graph, List<String> memberIds) {
        StringBuilder sb = new StringBuilder();
        sb.append("Entities:\n");
        int limit = Math.min(memberIds.size(), MAX_DIGEST_ENTITIES);
        for (int i = 0; i < limit; i++) {
            graph.getNode(memberIds.get(i)).ifPresent(node -> {
                sb.append("- ").append(node.getTitle());
                if (node.getNodeType() != null) {
                    sb.append(" [").append(node.getNodeType()).append("]");
                }
                if (node.getDescription() != null && !node.getDescription().isEmpty()) {
                    sb.append(": ").append(node.getDescription());
                }
                sb.append("\n");
            });
        }

        sb.append("Relationships:\n");
        Set<String> memberSet = new HashSet<>(memberIds);
        int relCount = 0;
        outer:
        for (String id : memberIds) {
            for (String edgeType : graph.getEdgeTypes()) {
                for (Map.Entry<String, Double> nb : graph.getNeighbors(id, edgeType)) {
                    if (memberSet.contains(nb.getKey())) {
                        String src = graph.getNode(id).map(MatrixGraphNode::getTitle).orElse(id);
                        String tgt = graph.getNode(nb.getKey()).map(MatrixGraphNode::getTitle).orElse(nb.getKey());
                        sb.append("- ").append(src).append(" --").append(edgeType).append("--> ").append(tgt).append("\n");
                        if (++relCount >= MAX_DIGEST_RELATIONSHIPS) {
                            break outer;
                        }
                    }
                }
            }
        }
        return sb.toString();
    }

    private String summarize(String digest) {
        if (llmChat == null) {
            return digest;
        }
        String prompt = """
                Summarize the following community of related entities from a knowledge graph into a
                concise paragraph. Capture the community's main theme, its key entities, and how they
                relate. Be factual and specific.

                %s

                Summary:""".formatted(digest);
        try {
            return llmChat.prompt().user(prompt).call().content();
        } catch (Exception e) {
            log.warn("Community summarization failed, falling back to raw digest: {}", e.getMessage());
            return digest;
        }
    }
}

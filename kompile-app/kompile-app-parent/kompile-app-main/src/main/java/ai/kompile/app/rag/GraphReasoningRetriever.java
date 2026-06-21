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
package ai.kompile.app.rag;

import ai.kompile.graph.reasoning.domain.AttributionChain;
import ai.kompile.graph.reasoning.domain.AttributionQuery;
import ai.kompile.graph.reasoning.domain.AttributionResult;
import ai.kompile.graph.reasoning.domain.BayesianInferenceResult;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Reasoning-augmented graph retrieval: turns a natural-language query into causal or probabilistic
 * context by routing to the (already live-store-bound) event-attribution reasoning services. This is
 * what lets the chat answer "why did X happen?" (causal chains) and "what is most likely?" (MEBN/Bayesian
 * posteriors) as part of retrieval, instead of only embedding/keyword/graph-structural lookup.
 *
 * <p>The NL query is mapped to seed graph nodes via {@link KnowledgeGraphService#searchNodes}, then:</p>
 * <ul>
 *   <li><b>CAUSAL</b> → {@link EventAttributionService#explain} (backward causal traversal + chains)</li>
 *   <li><b>PROBABILISTIC</b> → {@link BayesianNetworkService#queryMebnFromKg} (situation-specific MEBN posteriors)</li>
 * </ul>
 *
 * <p>All collaborators are optional; {@link #supports(String)} is false for a strategy whose services
 * are absent, so callers fall back to standard graph RAG.</p>
 */
@Service
public class GraphReasoningRetriever {

    private static final Logger log = LoggerFactory.getLogger(GraphReasoningRetriever.class);

    public static final String CAUSAL = "CAUSAL";
    public static final String PROBABILISTIC = "PROBABILISTIC";

    @Autowired(required = false)
    private KnowledgeGraphService graphService;
    @Autowired(required = false)
    private EventAttributionService attributionService;
    @Autowired(required = false)
    private BayesianNetworkService bayesianService;

    public GraphReasoningRetriever() {}

    /** Test constructor. */
    public GraphReasoningRetriever(KnowledgeGraphService graphService,
                                  EventAttributionService attributionService,
                                  BayesianNetworkService bayesianService) {
        this.graphService = graphService;
        this.attributionService = attributionService;
        this.bayesianService = bayesianService;
    }

    /** Whether the given strategy is a reasoning strategy whose backing services are available. */
    public boolean supports(String strategy) {
        if (strategy == null) {
            return false;
        }
        return switch (strategy.trim().toUpperCase()) {
            case CAUSAL -> graphService != null && attributionService != null;
            case PROBABILISTIC -> graphService != null && bayesianService != null;
            default -> false;
        };
    }

    /**
     * Retrieves reasoning context for the query, or {@code null} if the strategy is unsupported or no
     * seed entity could be resolved (so the caller can fall back to standard retrieval).
     */
    public String retrieve(String query, String strategy, int maxResults) {
        if (query == null || query.isBlank() || !supports(strategy)) {
            return null;
        }
        int k = Math.max(1, maxResults);
        List<String> seedIds = resolveSeeds(query, k);
        if (seedIds.isEmpty()) {
            log.debug("No seed entities resolved for reasoning query '{}'", query);
            return null;
        }
        return switch (strategy.trim().toUpperCase()) {
            case CAUSAL -> causalContext(query, seedIds.get(0));
            case PROBABILISTIC -> probabilisticContext(seedIds, k);
            default -> null;
        };
    }

    private List<String> resolveSeeds(String query, int k) {
        try {
            List<GraphNode> nodes = graphService.searchNodes(query, NodeLevel.ENTITY, k);
            if (nodes == null) {
                return List.of();
            }
            return nodes.stream()
                    .map(GraphNode::getNodeId)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
        } catch (Exception e) {
            log.warn("Seed resolution failed for reasoning query: {}", e.getMessage());
            return List.of();
        }
    }

    private String causalContext(String query, String targetNodeId) {
        try {
            AttributionResult result = attributionService.explain(AttributionQuery.builder()
                    .targetNodeId(targetNodeId)
                    .naturalLanguageQuery(query)
                    .useLlm(true)
                    .build());
            if (result == null) {
                return null;
            }
            StringBuilder sb = new StringBuilder("Causal analysis");
            if (result.getTargetTitle() != null) {
                sb.append(" for: ").append(result.getTargetTitle());
            }
            sb.append("\n");
            if (result.getSynthesizedExplanation() != null && !result.getSynthesizedExplanation().isBlank()) {
                sb.append(result.getSynthesizedExplanation()).append("\n\n");
            }
            if (result.getChains() != null && !result.getChains().isEmpty()) {
                sb.append("Causal chains:\n");
                for (AttributionChain chain : result.getChains()) {
                    sb.append(String.format("- Root cause: %s (confidence %.2f)",
                            chain.getRootCauseTitle(), chain.getOverallConfidence()));
                    if (chain.getNarrative() != null && !chain.getNarrative().isBlank()) {
                        sb.append(" — ").append(chain.getNarrative());
                    }
                    sb.append("\n");
                }
            }
            return sb.toString();
        } catch (Exception e) {
            log.warn("Causal retrieval failed: {}", e.getMessage());
            return null;
        }
    }

    private String probabilisticContext(List<String> seedIds, int maxResults) {
        try {
            BayesianInferenceResult result = bayesianService.queryMebnFromKg(seedIds, Map.of(), 3, 100);
            if (result == null || result.getPosteriors() == null || result.getPosteriors().isEmpty()) {
                return null;
            }
            StringBuilder sb = new StringBuilder("Probabilistic (MEBN) analysis — most likely related factors:\n");
            result.getPosteriors().entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(maxResults)
                    .forEach(e -> {
                        String title = result.getVariableToTitle() != null
                                ? result.getVariableToTitle().getOrDefault(e.getKey(), e.getKey())
                                : e.getKey();
                        sb.append("- ").append(title)
                                .append(": P(true)=").append(String.format("%.3f", e.getValue())).append("\n");
                    });
            return sb.toString();
        } catch (Exception e) {
            log.warn("Probabilistic retrieval failed: {}", e.getMessage());
            return null;
        }
    }
}

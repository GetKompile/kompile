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
import ai.kompile.graph.reasoning.explain.ConfidenceBreakdown;
import ai.kompile.graph.reasoning.explain.ReasoningTraceRenderer;
import ai.kompile.graph.reasoning.explain.ReasoningTrail;
import ai.kompile.graph.reasoning.model.GraphEntity;
import ai.kompile.graph.reasoning.unified.UnifiedGraph;
import ai.kompile.event.attribution.service.BayesianNetworkService;
import ai.kompile.event.attribution.service.EventAttributionService;
import ai.kompile.knowledgegraph.confidence.KbConfig;
import ai.kompile.knowledgegraph.confidence.KbConfigManager;
import ai.kompile.knowledgegraph.domain.GraphNode;
import ai.kompile.knowledgegraph.domain.NodeLevel;
import ai.kompile.knowledgegraph.reasoning.TraceHumanizer;
import ai.kompile.knowledgegraph.service.KnowledgeGraphService;
import ai.kompile.knowledgegraph.unified.UnifiedGraphBridge;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
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
    @Autowired(required = false)
    private KbConfigManager kbConfigManager;
    @Autowired(required = false)
    private UnifiedGraphBridge unifiedGraphBridge;

    public GraphReasoningRetriever() {}

    /** Test constructor (no KbConfigManager — uses defaults). */
    public GraphReasoningRetriever(KnowledgeGraphService graphService,
                                  EventAttributionService attributionService,
                                  BayesianNetworkService bayesianService) {
        this.graphService = graphService;
        this.attributionService = attributionService;
        this.bayesianService = bayesianService;
    }

    /** Test constructor with explicit KbConfigManager. */
    public GraphReasoningRetriever(KnowledgeGraphService graphService,
                                  EventAttributionService attributionService,
                                  BayesianNetworkService bayesianService,
                                  KbConfigManager kbConfigManager) {
        this(graphService, attributionService, bayesianService, kbConfigManager, null);
    }

    /** Test constructor with explicit KbConfigManager and unified graph bridge. */
    public GraphReasoningRetriever(KnowledgeGraphService graphService,
                                  EventAttributionService attributionService,
                                  BayesianNetworkService bayesianService,
                                  KbConfigManager kbConfigManager,
                                  UnifiedGraphBridge unifiedGraphBridge) {
        this.graphService = graphService;
        this.attributionService = attributionService;
        this.bayesianService = bayesianService;
        this.kbConfigManager = kbConfigManager;
        this.unifiedGraphBridge = unifiedGraphBridge;
    }

    private KbConfig kbCfg() {
        return kbConfigManager != null ? kbConfigManager.current() : KbConfig.defaults();
    }

    /** Whether the given strategy is a reasoning strategy whose backing services are available. */
    public boolean supports(String strategy) {
        if (strategy == null) {
            return false;
        }
        boolean hasSeedSource = graphService != null || unifiedGraphBridge != null;
        return switch (strategy.trim().toUpperCase()) {
            case CAUSAL -> hasSeedSource && attributionService != null;
            case PROBABILISTIC -> hasSeedSource && bayesianService != null;
            default -> false;
        };
    }

    /**
     * A reasoning retrieval result: the prompt {@code context} string plus a structured
     * {@link ReasoningTrail} (mode, confidence, evidence) the chat layer can surface as a trail card.
     */
    public record ReasoningRetrieval(String context, ReasoningTrail trail) {}

    /**
     * Retrieves reasoning context for the query, or {@code null} if the strategy is unsupported or no
     * seed entity could be resolved (so the caller can fall back to standard retrieval).
     */
    public String retrieve(String query, String strategy, int maxResults) {
        return retrieve(query, strategy, maxResults, null);
    }

    /** Scoped variant that resolves reasoning seeds and titles from the unified graph when possible. */
    public String retrieve(String query, String strategy, int maxResults, Long factSheetId) {
        ReasoningRetrieval r = retrieveWithTrail(query, strategy, maxResults, factSheetId);
        return r != null ? r.context() : null;
    }

    /**
     * Like {@link #retrieve} but also returns a structured {@link ReasoningTrail} so the chat layer
     * can render the reasoning as a trail card instead of only folding the text into the LLM prompt
     * (reasoning-stack gap §1). Returns {@code null} when the strategy is unsupported or no seed
     * entity resolves.
     */
    public ReasoningRetrieval retrieveWithTrail(String query, String strategy, int maxResults) {
        return retrieveWithTrail(query, strategy, maxResults, null);
    }

    /** Scoped variant that prefers the unified graph for seed resolution and title grounding. */
    public ReasoningRetrieval retrieveWithTrail(String query, String strategy, int maxResults, Long factSheetId) {
        if (query == null || query.isBlank() || !supports(strategy)) {
            return null;
        }
        int k = Math.max(1, maxResults);
        UnifiedGraph unified = unifiedGraph(factSheetId);
        List<String> seedIds = resolveSeeds(query, k, unified);
        if (seedIds.isEmpty()) {
            log.debug("No seed entities resolved for reasoning query '{}'", query);
            return null;
        }
        return switch (strategy.trim().toUpperCase()) {
            case CAUSAL -> causalContext(query, seedIds.get(0), unified);
            case PROBABILISTIC -> probabilisticContext(query, seedIds, k, unified);
            default -> null;
        };
    }

    private List<String> resolveSeeds(String query, int k, UnifiedGraph unified) {
        if (unified != null) {
            List<String> ids = resolveUnifiedSeeds(query, k, unified);
            if (!ids.isEmpty()) {
                return ids;
            }
        }
        if (graphService == null) {
            return List.of();
        }
        try {
            List<GraphNode> nodes = graphService.searchNodes(query, null, k);
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

    private List<String> resolveUnifiedSeeds(String query, int k, UnifiedGraph unified) {
        Set<String> queryTokens = tokenize(query);
        if (queryTokens.isEmpty()) {
            return List.of();
        }
        return unified.entities().stream()
                .map(entity -> new SeedCandidate(entity.id(), seedScore(entity, query, queryTokens)))
                .filter(candidate -> candidate.score() > 0)
                .sorted(Comparator.comparingDouble(SeedCandidate::score).reversed()
                        .thenComparing(SeedCandidate::entityId, Comparator.nullsLast(String::compareTo)))
                .limit(k)
                .map(SeedCandidate::entityId)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    private record SeedCandidate(String entityId, double score) {}

    private double seedScore(GraphEntity entity, String query, Set<String> queryTokens) {
        String label = normalize(entity.label());
        String id = normalize(entity.id());
        String text = entitySearchText(entity);
        Set<String> entityTokens = tokenize(text);
        if (entityTokens.isEmpty()) {
            return 0.0;
        }
        double score = 0.0;
        String normalizedQuery = normalize(query);
        if (!label.isBlank() && label.equals(normalizedQuery)) {
            score += 10.0;
        } else if (!label.isBlank() && label.contains(normalizedQuery)) {
            score += 5.0;
        }
        if (!id.isBlank() && id.equals(normalizedQuery)) {
            score += 8.0;
        }
        int overlap = 0;
        for (String token : queryTokens) {
            if (entityTokens.contains(token)) {
                overlap++;
            }
        }
        if (overlap == 0) {
            return score;
        }
        score += overlap;
        score += (double) overlap / Math.max(queryTokens.size(), entityTokens.size());
        if (entity.label() != null) {
            Set<String> labelTokens = tokenize(entity.label());
            for (String token : queryTokens) {
                if (labelTokens.contains(token)) {
                    score += 0.5;
                }
            }
        }
        return score;
    }

    private String entitySearchText(GraphEntity entity) {
        StringBuilder sb = new StringBuilder();
        if (entity.id() != null) sb.append(entity.id()).append(' ');
        if (entity.label() != null) sb.append(entity.label()).append(' ');
        if (entity.type() != null) sb.append(entity.type()).append(' ');
        entity.typeMemberships().forEach(t -> sb.append(t).append(' '));
        entity.tags().forEach(t -> sb.append(t).append(' '));
        entity.attributes().values().forEach(v -> sb.append(v).append(' '));
        return sb.toString().toLowerCase(Locale.ROOT);
    }

    private static Set<String> tokenize(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : normalize(text).split("[^a-z0-9]+")) {
            if (token.length() >= 3 && !isStopWord(token)) {
                tokens.add(token);
            }
        }
        return tokens;
    }

    private static String normalize(String text) {
        return text == null ? "" : text.toLowerCase(Locale.ROOT).trim();
    }

    private static boolean isStopWord(String token) {
        return switch (token) {
            case "the", "and", "for", "with", "what", "why", "how", "did", "does", "most",
                    "likely", "related", "cause", "caused", "happen", "happened", "show", "find" -> true;
            default -> false;
        };
    }

    private ReasoningRetrieval causalContext(String query, String targetNodeId, UnifiedGraph unified) {
        try {
            AttributionResult result = attributionService.explain(AttributionQuery.builder()
                    .targetNodeId(targetNodeId)
                    .naturalLanguageQuery(query)
                    .useLlm(true)
                    .build());
            if (result == null) {
                return null;
            }
            // GAP 3: when attribution service can't resolve the title, fall back to graph service lookup,
            // then to a static clean-label (never emit the raw node id to the prompt).
            String targetTitle;
            if (result.getTargetTitle() != null && !result.getTargetTitle().isBlank()) {
                targetTitle = result.getTargetTitle();
            } else {
                targetTitle = resolveNodeTitle(targetNodeId, unified);
            }
            StringBuilder sb = new StringBuilder("Causal analysis for: ").append(targetTitle).append("\n");
            if (result.getSynthesizedExplanation() != null && !result.getSynthesizedExplanation().isBlank()) {
                sb.append(result.getSynthesizedExplanation()).append("\n\n");
            }
            List<String> evidence = new ArrayList<>();
            double topConfidence = 0.0;
            if (result.getChains() != null && !result.getChains().isEmpty()) {
                sb.append("Causal chains:\n");
                for (AttributionChain chain : result.getChains()) {
                    String line = String.format("Root cause: %s (confidence %.2f)",
                            chain.getRootCauseTitle(), chain.getOverallConfidence());
                    if (chain.getNarrative() != null && !chain.getNarrative().isBlank()) {
                        line += " — " + chain.getNarrative();
                    }
                    sb.append("- ").append(line).append("\n");
                    evidence.add(line);
                    topConfidence = Math.max(topConfidence, chain.getOverallConfidence());
                }
            }
            ReasoningTrail trail = ReasoningTrail.builder(targetTitle)
                    .question(query)
                    .inferenceMode(CAUSAL)
                    .confidence(topConfidence)
                    .naturalLanguageSummary(result.getSynthesizedExplanation() != null
                            && !result.getSynthesizedExplanation().isBlank()
                            ? result.getSynthesizedExplanation() : "Causal analysis for " + targetTitle)
                    .evidence(evidence)
                    .build();
            appendTrailSection(sb, trail, kbCfg());
            return new ReasoningRetrieval(sb.toString(), trail);
        } catch (Exception e) {
            log.warn("Causal retrieval failed: {}", e.getMessage());
            return null;
        }
    }

    private ReasoningRetrieval probabilisticContext(String query, List<String> seedIds, int maxResults,
                                                    UnifiedGraph unified) {
        try {
            BayesianInferenceResult result = bayesianService.queryMebnFromKg(seedIds, Map.of(), 3, 100);
            if (result == null || result.getPosteriors() == null || result.getPosteriors().isEmpty()) {
                return null;
            }
            // GAP 3: resolve seed title for the trail (never emit raw node id)
            String seedTitle = resolveNodeTitle(seedIds.get(0), unified);
            StringBuilder sb = new StringBuilder("Probabilistic (MEBN) analysis — most likely related factors:\n");
            List<String> evidence = new ArrayList<>();
            double[] topConfidence = {0.0};
            result.getPosteriors().entrySet().stream()
                    .sorted((a, b) -> Double.compare(b.getValue(), a.getValue()))
                    .limit(maxResults)
                    .forEach(e -> {
                        // GAP 3: when variableToTitle misses, resolve via graph service; fallback to clean label
                        String title;
                        if (result.getVariableToTitle() != null
                                && result.getVariableToTitle().containsKey(e.getKey())) {
                            title = result.getVariableToTitle().get(e.getKey());
                        } else {
                            title = resolveNodeTitle(e.getKey(), unified);
                        }
                        String line = title + ": P(true)=" + String.format("%.3f", e.getValue());
                        sb.append("- ").append(line).append("\n");
                        evidence.add(line);
                        topConfidence[0] = Math.max(topConfidence[0], e.getValue());
                    });
            ReasoningTrail trail = ReasoningTrail.builder(seedTitle)
                    .question(query)
                    .inferenceMode(PROBABILISTIC)
                    .confidence(topConfidence[0])
                    .breakdown(ConfidenceBreakdown.ofMebn(topConfidence[0]))
                    .naturalLanguageSummary("Probabilistic MEBN posterior ranking for " + seedTitle)
                    .evidence(evidence)
                    .build();
            appendTrailSection(sb, trail, kbCfg());
            return new ReasoningRetrieval(sb.toString(), trail);
        } catch (Exception e) {
            log.warn("Probabilistic retrieval failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Resolve a node id to a human-readable title. Tries the graph service first, then falls
     * back to {@link TraceHumanizer#cleanLabel(String)} (no graph required). Never returns
     * a raw UUID or path-bearing string.
     *
     * @param nodeId internal node id or entity id
     * @return human-readable title; never null
     */
    private String resolveNodeTitle(String nodeId, UnifiedGraph unified) {
        if (nodeId == null || nodeId.isBlank()) return nodeId != null ? nodeId : "";
        if (unified != null) {
            String title = unified.entity(nodeId)
                    .map(GraphEntity::label)
                    .filter(label -> !label.isBlank())
                    .orElse(null);
            if (title != null) return title;
        }
        if (graphService != null) {
            // Try internal node id first
            String byNodeId = graphService.getNode(nodeId)
                    .map(n -> n.getTitle() != null && !n.getTitle().isBlank() ? n.getTitle() : null)
                    .orElse(null);
            if (byNodeId != null) return byNodeId;
            // Try as external id across entity level
            String byExt = graphService.getNodeByExternalId(nodeId, NodeLevel.ENTITY)
                    .map(n -> n.getTitle() != null && !n.getTitle().isBlank() ? n.getTitle() : null)
                    .orElse(null);
            if (byExt != null) return byExt;
        }
        return TraceHumanizer.cleanLabel(nodeId);
    }

    private UnifiedGraph unifiedGraph(Long factSheetId) {
        if (factSheetId == null || unifiedGraphBridge == null) {
            return null;
        }
        try {
            return unifiedGraphBridge.export(factSheetId);
        } catch (RuntimeException ex) {
            log.warn("Falling back to live reasoning retrieval; unified export failed for factSheet={}",
                    factSheetId, ex);
            return null;
        }
    }

    private static void appendTrailSection(StringBuilder sb, ReasoningTrail trail, KbConfig cfg) {
        if (cfg == null || !cfg.isReasoningTrailInPromptEnabled() || trail == null || trail.evidence().isEmpty()) {
            return;
        }
        String traceContext = ReasoningTraceRenderer.toLlmContext(trail, cfg.getReasoningTrailPromptMaxLines());
        if (!traceContext.isBlank()) {
            sb.append('\n').append(traceContext).append('\n');
        }
    }
}

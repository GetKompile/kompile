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
package ai.kompile.knowledgegraph.agent;

import ai.kompile.core.graphrag.agent.MultiAgentGraphBuilder;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionConfig;
import ai.kompile.core.graphrag.agent.RelationExtractionAgent.ExtractionResult;
import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.retrievers.RetrievedDoc;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Default implementation of {@link MultiAgentGraphBuilder} that runs each agent
 * sequentially, collects their graphs, and merges them according to the selected
 * {@link GraphMergeStrategy}.
 *
 * <p>Entity deduplication is by entity ID. Relationship deduplication is by the
 * triple (source, target, type). When duplicates are found, the merge strategy
 * determines which version to keep.
 */
public class DefaultMultiAgentGraphBuilder implements MultiAgentGraphBuilder {

    private static final Logger log = LoggerFactory.getLogger(DefaultMultiAgentGraphBuilder.class);

    @Override
    public MergedGraphResult buildGraph(
            List<RetrievedDoc> chunks,
            List<RelationExtractionAgent> agents,
            GraphMergeStrategy strategy,
            ExtractionConfig config) {

        long startTime = System.currentTimeMillis();
        Map<String, AgentContribution> contributions = new LinkedHashMap<>();
        Map<String, ExtractionResult> agentResults = new LinkedHashMap<>();

        // Run each agent and collect results
        for (RelationExtractionAgent agent : agents) {
            try {
                log.info("Running agent '{}' on {} chunks", agent.getId(), chunks.size());
                ExtractionResult result = agent.extract(chunks, config);
                agentResults.put(agent.getId(), result);

                if (result.metrics() != null) {
                    log.info("Agent '{}' extracted {} entities, {} relations in {}ms",
                            agent.getId(),
                            result.metrics().entitiesExtracted(),
                            result.metrics().relationsExtracted(),
                            result.metrics().extractionTimeMs());
                }
            } catch (Exception e) {
                log.error("Agent '{}' failed during extraction", agent.getId(), e);
                // Create empty result for failed agent
                Graph emptyGraph = new Graph();
                emptyGraph.setEntities(List.of());
                emptyGraph.setRelationships(List.of());
                agentResults.put(agent.getId(), new ExtractionResult(
                        emptyGraph,
                        new RelationExtractionAgent.AgentMetrics(
                                agent.getId(), 0, 0, 0, chunks.size(), null,
                                Map.of("error", e.getMessage()))));
            }
        }

        // Merge results
        Graph mergedGraph = mergeGraphs(agentResults, strategy);

        // Calculate per-agent contributions
        Set<String> mergedEntityIds = mergedGraph.getEntities() != null
                ? mergedGraph.getEntities().stream().map(Entity::getId).collect(Collectors.toSet())
                : Set.of();
        Set<String> mergedRelKeys = mergedGraph.getRelationships() != null
                ? mergedGraph.getRelationships().stream()
                .map(r -> r.getSource() + "|" + r.getTarget() + "|" + r.getType())
                .collect(Collectors.toSet())
                : Set.of();

        for (Map.Entry<String, ExtractionResult> entry : agentResults.entrySet()) {
            String agentId = entry.getKey();
            ExtractionResult result = entry.getValue();
            Graph agentGraph = result.graph();

            int entitiesExtracted = agentGraph.getEntities() != null ? agentGraph.getEntities().size() : 0;
            int relationsExtracted = agentGraph.getRelationships() != null ? agentGraph.getRelationships().size() : 0;

            // Count how many of this agent's outputs survived the merge
            Set<String> agentEntityIds = agentGraph.getEntities() != null
                    ? agentGraph.getEntities().stream().map(Entity::getId).collect(Collectors.toSet())
                    : Set.of();
            int entitiesRetained = (int) agentEntityIds.stream().filter(mergedEntityIds::contains).count();

            Set<String> agentRelKeys = agentGraph.getRelationships() != null
                    ? agentGraph.getRelationships().stream()
                    .map(r -> r.getSource() + "|" + r.getTarget() + "|" + r.getType())
                    .collect(Collectors.toSet())
                    : Set.of();
            int relationsRetained = (int) agentRelKeys.stream().filter(mergedRelKeys::contains).count();

            Set<String> entityTypes = agentGraph.getEntities() != null
                    ? agentGraph.getEntities().stream().map(Entity::getType).filter(Objects::nonNull).collect(Collectors.toSet())
                    : Set.of();
            Set<String> relationTypes = agentGraph.getRelationships() != null
                    ? agentGraph.getRelationships().stream().map(Relationship::getType).filter(Objects::nonNull).collect(Collectors.toSet())
                    : Set.of();

            contributions.put(agentId, new AgentContribution(
                    agentId,
                    entitiesExtracted,
                    relationsExtracted,
                    entitiesRetained,
                    relationsRetained,
                    result.metrics() != null ? result.metrics().extractionTimeMs() : 0,
                    entityTypes,
                    relationTypes
            ));
        }

        long totalTimeMs = System.currentTimeMillis() - startTime;
        int totalEntities = mergedGraph.getEntities() != null ? mergedGraph.getEntities().size() : 0;
        int totalRelations = mergedGraph.getRelationships() != null ? mergedGraph.getRelationships().size() : 0;

        log.info("Multi-agent build complete: {} agents, {} entities, {} relations, strategy={}, {}ms",
                agents.size(), totalEntities, totalRelations, strategy, totalTimeMs);

        return new MergedGraphResult(
                mergedGraph, contributions,
                totalEntities, totalRelations,
                totalTimeMs, strategy
        );
    }

    private Graph mergeGraphs(Map<String, ExtractionResult> agentResults, GraphMergeStrategy strategy) {
        return switch (strategy) {
            case UNION -> mergeUnion(agentResults);
            case INTERSECTION -> mergeIntersection(agentResults);
            case HIGHEST_CONFIDENCE -> mergeHighestConfidence(agentResults);
            case FIRST_WINS -> mergeFirstWins(agentResults);
        };
    }

    private Graph mergeUnion(Map<String, ExtractionResult> agentResults) {
        Map<String, Entity> entityMap = new LinkedHashMap<>();
        Map<String, Relationship> relMap = new LinkedHashMap<>();

        for (ExtractionResult result : agentResults.values()) {
            Graph g = result.graph();
            if (g.getEntities() != null) {
                for (Entity e : g.getEntities()) {
                    entityMap.merge(e.getId(), e, (existing, incoming) ->
                            betterConfidence(existing, incoming) ? existing : incoming);
                }
            }
            if (g.getRelationships() != null) {
                for (Relationship r : g.getRelationships()) {
                    String key = r.getSource() + "|" + r.getTarget() + "|" + r.getType();
                    relMap.merge(key, r, (existing, incoming) ->
                            betterConfidence(existing, incoming) ? existing : incoming);
                }
            }
        }

        return buildGraph(entityMap, relMap);
    }

    private Graph mergeIntersection(Map<String, ExtractionResult> agentResults) {
        // Count how many agents produced each entity/relationship
        Map<String, Integer> entityVotes = new HashMap<>();
        Map<String, Integer> relVotes = new HashMap<>();
        Map<String, Entity> entityMap = new LinkedHashMap<>();
        Map<String, Relationship> relMap = new LinkedHashMap<>();

        for (ExtractionResult result : agentResults.values()) {
            Graph g = result.graph();
            if (g.getEntities() != null) {
                for (Entity e : g.getEntities()) {
                    entityVotes.merge(e.getId(), 1, Integer::sum);
                    entityMap.merge(e.getId(), e, (existing, incoming) ->
                            betterConfidence(existing, incoming) ? existing : incoming);
                }
            }
            if (g.getRelationships() != null) {
                for (Relationship r : g.getRelationships()) {
                    String key = r.getSource() + "|" + r.getTarget() + "|" + r.getType();
                    relVotes.merge(key, 1, Integer::sum);
                    relMap.merge(key, r, (existing, incoming) ->
                            betterConfidence(existing, incoming) ? existing : incoming);
                }
            }
        }

        // Keep only those with 2+ votes
        entityMap.entrySet().removeIf(e -> entityVotes.getOrDefault(e.getKey(), 0) < 2);
        relMap.entrySet().removeIf(e -> relVotes.getOrDefault(e.getKey(), 0) < 2);

        // Also remove relationships whose entities were removed
        Set<String> remainingEntityIds = entityMap.keySet();
        relMap.entrySet().removeIf(e -> {
            Relationship r = e.getValue();
            return !remainingEntityIds.contains(r.getSource()) || !remainingEntityIds.contains(r.getTarget());
        });

        return buildGraph(entityMap, relMap);
    }

    private Graph mergeHighestConfidence(Map<String, ExtractionResult> agentResults) {
        // Same as union — merge always keeps higher confidence
        return mergeUnion(agentResults);
    }

    private Graph mergeFirstWins(Map<String, ExtractionResult> agentResults) {
        Map<String, Entity> entityMap = new LinkedHashMap<>();
        Map<String, Relationship> relMap = new LinkedHashMap<>();

        for (ExtractionResult result : agentResults.values()) {
            Graph g = result.graph();
            if (g.getEntities() != null) {
                for (Entity e : g.getEntities()) {
                    entityMap.putIfAbsent(e.getId(), e);
                }
            }
            if (g.getRelationships() != null) {
                for (Relationship r : g.getRelationships()) {
                    String key = r.getSource() + "|" + r.getTarget() + "|" + r.getType();
                    relMap.putIfAbsent(key, r);
                }
            }
        }

        return buildGraph(entityMap, relMap);
    }

    private boolean betterConfidence(Entity existing, Entity incoming) {
        double ec = existing.getConfidence() != null ? existing.getConfidence() : 0.0;
        double ic = incoming.getConfidence() != null ? incoming.getConfidence() : 0.0;
        return ec >= ic;
    }

    private boolean betterConfidence(Relationship existing, Relationship incoming) {
        double ec = existing.getConfidence() != null ? existing.getConfidence() : 0.0;
        double ic = incoming.getConfidence() != null ? incoming.getConfidence() : 0.0;
        return ec >= ic;
    }

    private Graph buildGraph(Map<String, Entity> entityMap, Map<String, Relationship> relMap) {
        Graph graph = new Graph();
        graph.setEntities(new ArrayList<>(entityMap.values()));
        graph.setRelationships(new ArrayList<>(relMap.values()));
        return graph;
    }
}

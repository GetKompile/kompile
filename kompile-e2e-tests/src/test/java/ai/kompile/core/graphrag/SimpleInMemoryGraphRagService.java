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

package ai.kompile.core.graphrag;

import ai.kompile.core.graphrag.model.Entity;
import ai.kompile.core.graphrag.model.Graph;
import ai.kompile.core.graphrag.model.Relationship;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Simple in-memory implementation of GraphRagService for testing purposes.
 * <p>
 * This implementation provides basic text-matching search without requiring
 * external dependencies like embedding models or LLMs. It demonstrates the
 * expected behavior of a GraphRagService implementation.
 * </p>
 */
public class SimpleInMemoryGraphRagService implements GraphRagService {

    private Graph storedGraph;

    /**
     * Creates a new SimpleInMemoryGraphRagService with no initial data.
     */
    public SimpleInMemoryGraphRagService() {
        this.storedGraph = null;
    }

    /**
     * Sets the stored graph for queries that don't provide their own.
     *
     * @param graph the graph to store
     */
    public void setStoredGraph(Graph graph) {
        this.storedGraph = graph;
    }

    @Override
    public GraphRagResult answerQuery(GraphRagQuery query) {
        if (query == null) {
            return GraphRagResult.builder()
                    .answer("Error: Query cannot be null.")
                    .formattedContext("")
                    .build();
        }

        // Use provided graph or fall back to stored graph
        Graph graph = query.getGraph() != null ? query.getGraph() : storedGraph;

        if (graph == null || graph.getEntities() == null || graph.getEntities().isEmpty()) {
            return GraphRagResult.builder()
                    .answer("I don't have any knowledge graph data to answer your question.")
                    .formattedContext("")
                    .build();
        }

        // Determine search type (default to LOCAL if null)
        SearchType searchType = query.getSearchType() != null ? query.getSearchType() : SearchType.LOCAL;

        // Get k (default to 5 if invalid)
        int k = query.getK() > 0 ? query.getK() : 5;

        // Perform search
        String context;
        if (searchType == SearchType.GLOBAL) {
            context = performGlobalSearch(graph, query.getQuery(), k);
        } else {
            context = performLocalSearch(graph, query.getQuery(), k);
        }

        // Generate answer based on context
        String answer = generateAnswer(query.getQuery(), context, graph);

        return GraphRagResult.builder().answer(answer).formattedContext(context).build();
    }

    /**
     * Performs local search - finds entities matching the query and their immediate neighbors.
     */
    private String performLocalSearch(Graph graph, String queryText, int k) {
        if (queryText == null || queryText.isEmpty()) {
            return "";
        }

        String queryLower = queryText.toLowerCase();
        List<Entity> matchingEntities = new ArrayList<>();

        // Simple text matching to find relevant entities
        for (Entity entity : graph.getEntities()) {
            double score = calculateRelevanceScore(entity, queryLower);
            if (score > 0) {
                matchingEntities.add(entity);
            }
        }

        // Sort by relevance and limit to k
        matchingEntities = matchingEntities.stream()
                .sorted((a, b) -> Double.compare(
                        calculateRelevanceScore(b, queryLower),
                        calculateRelevanceScore(a, queryLower)))
                .limit(k)
                .collect(Collectors.toList());

        // Build context from matching entities
        return buildEntityContext(matchingEntities, graph);
    }

    /**
     * Performs global search - provides an overview of the entire graph structure.
     */
    private String performGlobalSearch(Graph graph, String queryText, int k) {
        StringBuilder context = new StringBuilder();

        context.append("Knowledge Graph Overview:\n");
        context.append(String.format("- Total entities: %d\n", graph.getEntities().size()));
        context.append(String.format("- Total relationships: %d\n",
                graph.getRelationships() != null ? graph.getRelationships().size() : 0));

        // Group entities by type
        Map<String, List<Entity>> entitiesByType = graph.getEntities().stream()
                .filter(e -> e.getType() != null)
                .collect(Collectors.groupingBy(Entity::getType));

        context.append("\nEntity Types:\n");
        for (Map.Entry<String, List<Entity>> entry : entitiesByType.entrySet()) {
            context.append(String.format("- %s: %d entities\n", entry.getKey(), entry.getValue().size()));
        }

        // List top entities (by connection count or just first k)
        context.append("\nKey Entities:\n");
        List<Entity> topEntities = graph.getEntities().stream()
                .limit(k)
                .collect(Collectors.toList());

        for (Entity entity : topEntities) {
            context.append(formatEntityForContext(entity));
        }

        // Add relationship summary
        if (graph.getRelationships() != null && !graph.getRelationships().isEmpty()) {
            context.append("\nKey Relationships:\n");
            graph.getRelationships().stream()
                    .limit(k)
                    .forEach(rel -> context.append(formatRelationshipForContext(rel, graph)));
        }

        return context.toString();
    }

    /**
     * Calculates a simple relevance score based on text matching.
     */
    private double calculateRelevanceScore(Entity entity, String queryLower) {
        double score = 0.0;

        if (entity.getTitle() != null && entity.getTitle().toLowerCase().contains(queryLower)) {
            score += 2.0;
        }

        if (entity.getDescription() != null && entity.getDescription().toLowerCase().contains(queryLower)) {
            score += 1.0;
        }

        if (entity.getType() != null && queryLower.contains(entity.getType().toLowerCase())) {
            score += 0.5;
        }

        // Check for partial word matches
        String[] queryWords = queryLower.split("\\s+");
        for (String word : queryWords) {
            if (word.length() > 2) {
                if (entity.getTitle() != null && entity.getTitle().toLowerCase().contains(word)) {
                    score += 0.5;
                }
                if (entity.getDescription() != null && entity.getDescription().toLowerCase().contains(word)) {
                    score += 0.25;
                }
            }
        }

        return score;
    }

    /**
     * Builds context string from a list of entities.
     */
    private String buildEntityContext(List<Entity> entities, Graph graph) {
        if (entities.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("Relevant Knowledge:\n\n");

        Set<String> entityIds = entities.stream()
                .map(Entity::getId)
                .collect(Collectors.toSet());

        for (Entity entity : entities) {
            context.append(formatEntityForContext(entity));

            // Add related entities
            if (graph.getRelationships() != null) {
                List<Relationship> relatedRels = graph.getRelationships().stream()
                        .filter(r -> entity.getId().equals(r.getSource()) || entity.getId().equals(r.getTarget()))
                        .limit(3)
                        .collect(Collectors.toList());

                if (!relatedRels.isEmpty()) {
                    context.append("  Related to:\n");
                    for (Relationship rel : relatedRels) {
                        String otherId = entity.getId().equals(rel.getSource()) ? rel.getTarget() : rel.getSource();
                        graph.getEntities().stream()
                                .filter(e -> e.getId().equals(otherId))
                                .findFirst()
                                .ifPresent(other -> context.append(String.format("    - %s (%s)\n",
                                        other.getTitle(), rel.getType())));
                    }
                }
            }
            context.append("\n");
        }

        return context.toString();
    }

    /**
     * Formats an entity for display in context.
     */
    private String formatEntityForContext(Entity entity) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Entity: %s", entity.getTitle()));
        if (entity.getType() != null) {
            sb.append(String.format(" [%s]", entity.getType()));
        }
        sb.append("\n");

        if (entity.getDescription() != null && !entity.getDescription().isEmpty()) {
            sb.append(String.format("  Description: %s\n", entity.getDescription()));
        }

        return sb.toString();
    }

    /**
     * Formats a relationship for display in context.
     */
    private String formatRelationshipForContext(Relationship rel, Graph graph) {
        String sourceName = graph.getEntities().stream()
                .filter(e -> e.getId().equals(rel.getSource()))
                .map(Entity::getTitle)
                .findFirst()
                .orElse(rel.getSource());

        String targetName = graph.getEntities().stream()
                .filter(e -> e.getId().equals(rel.getTarget()))
                .map(Entity::getTitle)
                .findFirst()
                .orElse(rel.getTarget());

        return String.format("- %s -[%s]-> %s\n", sourceName, rel.getType(), targetName);
    }

    /**
     * Generates an answer based on the query and context.
     * In a real implementation, this would use an LLM.
     */
    private String generateAnswer(String query, String context, Graph graph) {
        if (context == null || context.isEmpty()) {
            return "I couldn't find relevant information in the knowledge graph to answer your question.";
        }

        // Simple answer generation without LLM
        StringBuilder answer = new StringBuilder();
        answer.append("Based on the knowledge graph:\n\n");

        if (query != null && !query.isEmpty()) {
            String queryLower = query.toLowerCase();

            // Try to provide a targeted answer based on query keywords
            if (queryLower.contains("who") || queryLower.contains("person")) {
                List<Entity> people = graph.getEntities().stream()
                        .filter(e -> "PERSON".equalsIgnoreCase(e.getType()))
                        .collect(Collectors.toList());

                if (!people.isEmpty()) {
                    answer.append("People found:\n");
                    for (Entity person : people) {
                        answer.append(String.format("- %s: %s\n",
                                person.getTitle(),
                                person.getDescription() != null ? person.getDescription() : "No description"));
                    }
                }
            } else if (queryLower.contains("what") || queryLower.contains("organization") || queryLower.contains("company")) {
                List<Entity> orgs = graph.getEntities().stream()
                        .filter(e -> "ORGANIZATION".equalsIgnoreCase(e.getType()))
                        .collect(Collectors.toList());

                if (!orgs.isEmpty()) {
                    answer.append("Organizations found:\n");
                    for (Entity org : orgs) {
                        answer.append(String.format("- %s: %s\n",
                                org.getTitle(),
                                org.getDescription() != null ? org.getDescription() : "No description"));
                    }
                }
            }

            // Add general information
            if (answer.length() < 50) {
                answer.append("The knowledge graph contains information about:\n");
                graph.getEntities().stream()
                        .limit(5)
                        .forEach(e -> answer.append(String.format("- %s (%s)\n",
                                e.getTitle(), e.getType() != null ? e.getType() : "Unknown")));
            }
        } else {
            answer.append(context);
        }

        return answer.toString();
    }
}

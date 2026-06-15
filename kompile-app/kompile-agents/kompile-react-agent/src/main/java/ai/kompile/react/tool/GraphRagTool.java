/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.react.tool;

import ai.kompile.core.graphrag.GraphRagService;
import ai.kompile.core.graphrag.query.GraphRagQuery;
import ai.kompile.core.graphrag.query.GraphRagResult;
import ai.kompile.core.graphrag.query.SearchType;
import ai.kompile.react.model.ToolDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;

/**
 * Tool that allows the ReAct agent to query the knowledge graph.
 * Provides both local and global search capabilities.
 */
@Slf4j
@RequiredArgsConstructor
public class GraphRagTool {

    private final GraphRagService graphRagService;

    /**
     * Create a tool definition for graph search.
     */
    public ToolDefinition createSearchTool() {
        return ToolDefinition.builder()
                .name("search_knowledge_graph")
                .description("Search the knowledge graph for information about entities, relationships, " +
                        "and concepts. Use this when you need to look up facts or find connections " +
                        "between entities. Returns relevant context from the graph.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "query", Map.of(
                                        "type", "string",
                                        "description", "The search query to find relevant information"
                                ),
                                "search_type", Map.of(
                                        "type", "string",
                                        "enum", List.of("LOCAL", "GLOBAL"),
                                        "description", "LOCAL for specific entity lookup, GLOBAL for broad topic search"
                                ),
                                "k", Map.of(
                                        "type", "integer",
                                        "description", "Maximum number of results to return (default: 5)"
                                )
                        ),
                        "required", List.of("query")
                ))
                .executor(this::executeSearch)
                .parallelizable(true)
                .build();
    }

    /**
     * Create a tool definition for entity lookup.
     */
    public ToolDefinition createEntityLookupTool() {
        return ToolDefinition.builder()
                .name("lookup_entity")
                .description("Look up a specific entity in the knowledge graph by name. " +
                        "Returns the entity's properties and relationships.")
                .parameters(Map.of(
                        "type", "object",
                        "properties", Map.of(
                                "entity_name", Map.of(
                                        "type", "string",
                                        "description", "The name of the entity to look up"
                                ),
                                "include_relationships", Map.of(
                                        "type", "boolean",
                                        "description", "Whether to include related entities (default: true)"
                                )
                        ),
                        "required", List.of("entity_name")
                ))
                .executor(this::executeLookup)
                .parallelizable(true)
                .build();
    }

    private String executeSearch(Map<String, Object> args) {
        String query = (String) args.get("query");
        if (query == null || query.isBlank()) {
            return "Error: query is required";
        }

        SearchType searchType = SearchType.LOCAL;
        if (args.containsKey("search_type")) {
            try {
                searchType = SearchType.valueOf((String) args.get("search_type"));
            } catch (Exception e) {
                log.warn("Invalid search type, using LOCAL");
            }
        }

        int k = 5;
        if (args.containsKey("k")) {
            try {
                k = ((Number) args.get("k")).intValue();
            } catch (Exception e) {
                log.warn("Invalid k value, using 5");
            }
        }

        try {
            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(query)
                    .searchType(searchType)
                    .k(k)
                    .build();

            GraphRagResult result = graphRagService.answerQuery(graphQuery);

            if (result == null) {
                return "No results found for query: " + query;
            }

            return formatResult(result);

        } catch (Exception e) {
            log.error("Graph search failed: {}", e.getMessage(), e);
            return "Error searching knowledge graph: " + e.getMessage();
        }
    }

    private String executeLookup(Map<String, Object> args) {
        String entityName = (String) args.get("entity_name");
        if (entityName == null || entityName.isBlank()) {
            return "Error: entity_name is required";
        }

        try {
            // Use a local search with the entity name as query
            GraphRagQuery graphQuery = GraphRagQuery.builder()
                    .query(entityName)
                    .searchType(SearchType.LOCAL)
                    .k(1)
                    .build();

            GraphRagResult result = graphRagService.answerQuery(graphQuery);

            if (result == null) {
                return "Entity not found: " + entityName;
            }

            return formatResult(result);

        } catch (Exception e) {
            log.error("Entity lookup failed: {}", e.getMessage(), e);
            return "Error looking up entity: " + e.getMessage();
        }
    }

    private String formatResult(GraphRagResult result) {
        StringBuilder sb = new StringBuilder();

        if (result.getAnswer() != null) {
            sb.append("**Summary**: ").append(result.getAnswer()).append("\n\n");
        }

        if (result.getFormattedContext() != null) {
            sb.append("**Context**:\n").append(result.getFormattedContext()).append("\n");
        }

        return sb.toString();
    }
}

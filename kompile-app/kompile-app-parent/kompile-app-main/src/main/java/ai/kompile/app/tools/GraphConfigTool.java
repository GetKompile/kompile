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

package ai.kompile.app.tools;

import ai.kompile.app.web.controllers.GraphExtractionController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import java.util.*;

/**
 * MCP Tool for knowledge graph extraction configuration.
 * Exposes graph extraction config, schema modes, entity/relationship types, and model providers.
 */
@Component
public class GraphConfigTool {

    private static final Logger logger = LoggerFactory.getLogger(GraphConfigTool.class);

    private final GraphExtractionController graphExtractionController;

    @Autowired
    public GraphConfigTool(@Autowired(required = false) GraphExtractionController graphExtractionController) {
        this.graphExtractionController = graphExtractionController;
    }

    public record GetGraphExtractionConfigInput() {}
    public record ResetGraphExtractionConfigInput() {}
    public record ToggleGraphExtractionInput() {}
    public record GetGraphExtractionStatusInput() {}
    public record GetSchemaModesInput() {}
    public record GetSuggestedEntityTypesInput() {}
    public record GetSuggestedRelationshipTypesInput() {}
    public record GetGraphModelProvidersInput() {}

    @Tool(name = "get_graph_extraction_config",
            description = "Gets the current knowledge graph extraction configuration.")
    public Map<String, Object> getGraphExtractionConfig(GetGraphExtractionConfigInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting graph extraction config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "reset_graph_extraction_config",
            description = "Resets graph extraction configuration to defaults.")
    public Map<String, Object> resetGraphExtractionConfig(ResetGraphExtractionConfigInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.resetConfig();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error resetting graph extraction config: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "toggle_graph_extraction",
            description = "Toggles entity extraction enabled/disabled.")
    public Map<String, Object> toggleGraphExtraction(ToggleGraphExtractionInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.toggleEnabled();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error toggling graph extraction: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_graph_extraction_status",
            description = "Gets the current enabled status of graph extraction.")
    public Map<String, Object> getGraphExtractionStatus(GetGraphExtractionStatusInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getStatus();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting graph extraction status: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_graph_schema_modes",
            description = "Gets available schema enforcement modes for knowledge graph extraction.")
    public Map<String, Object> getSchemaModes(GetSchemaModesInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getSchemaModes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting schema modes: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_suggested_entity_types",
            description = "Gets suggested entity types for knowledge graph schema definition.")
    public Map<String, Object> getSuggestedEntityTypes(GetSuggestedEntityTypesInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getSuggestedEntityTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting suggested entity types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_suggested_relationship_types",
            description = "Gets suggested relationship types for knowledge graph schema definition.")
    public Map<String, Object> getSuggestedRelationshipTypes(GetSuggestedRelationshipTypesInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getSuggestedRelationshipTypes();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting suggested relationship types: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }

    @Tool(name = "get_graph_model_providers",
            description = "Gets available LLM providers for entity extraction.")
    public Map<String, Object> getGraphModelProviders(GetGraphModelProvidersInput input) {
        try {
            if (graphExtractionController == null) return Map.of("status", "error", "error", "Graph extraction not available");
            ResponseEntity<?> response = graphExtractionController.getModelProviders();
            return Map.of("status", "success", "data", response.getBody());
        } catch (Exception e) {
            logger.error("Error getting graph model providers: {}", e.getMessage(), e);
            return Map.of("status", "error", "error", e.getMessage());
        }
    }
}

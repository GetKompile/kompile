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

package ai.kompile.app.web.controllers;

import ai.kompile.query.transformer.QueryTransformerProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * REST controller for managing query transformer configuration.
 */
@RestController
@RequestMapping("/api/query-transformer")
@CrossOrigin(origins = "*")
public class QueryTransformerConfigController {

    private static final Logger logger = LoggerFactory.getLogger(QueryTransformerConfigController.class);

    private final QueryTransformerProperties properties;

    @Autowired
    public QueryTransformerConfigController(
            @Autowired(required = false) QueryTransformerProperties properties
    ) {
        this.properties = properties;
    }

    /**
     * Get current query transformer configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<Map<String, Object>> getConfig() {
        if (properties == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false,
                    "message", "Query transformer module not loaded"
            ));
        }

        Map<String, Object> config = new LinkedHashMap<>();
        config.put("available", true);
        config.put("enabled", properties.isEnabled());
        config.put("type", properties.getType());
        config.put("maxQueries", properties.getMaxQueries());
        config.put("includeOriginal", properties.isIncludeOriginal());
        config.put("systemPrompt", properties.getSystemPrompt());
        config.put("temperature", properties.getTemperature());
        config.put("maxTokens", properties.getMaxTokens());

        return ResponseEntity.ok(config);
    }

    /**
     * Update query transformer configuration.
     */
    @PutMapping("/config")
    public ResponseEntity<Map<String, Object>> updateConfig(@RequestBody Map<String, Object> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Query transformer module not loaded"
            ));
        }

        logger.info("Updating query transformer configuration: {}", request);

        if (request.containsKey("enabled")) {
            properties.setEnabled((Boolean) request.get("enabled"));
        }
        if (request.containsKey("type")) {
            properties.setType((String) request.get("type"));
        }
        if (request.containsKey("maxQueries")) {
            properties.setMaxQueries((Integer) request.get("maxQueries"));
        }
        if (request.containsKey("includeOriginal")) {
            properties.setIncludeOriginal((Boolean) request.get("includeOriginal"));
        }
        if (request.containsKey("systemPrompt")) {
            properties.setSystemPrompt((String) request.get("systemPrompt"));
        }
        if (request.containsKey("temperature")) {
            properties.setTemperature(((Number) request.get("temperature")).doubleValue());
        }
        if (request.containsKey("maxTokens")) {
            properties.setMaxTokens((Integer) request.get("maxTokens"));
        }

        return getConfig();
    }

    /**
     * Toggle query transformer on/off.
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleQueryTransformer(@RequestBody Map<String, Boolean> request) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Query transformer module not loaded"
            ));
        }

        Boolean enabled = request.get("enabled");
        if (enabled != null) {
            properties.setEnabled(enabled);
            logger.info("Query transformer {}", enabled ? "enabled" : "disabled");
        }

        return ResponseEntity.ok(Map.of(
                "success", true,
                "enabled", properties.isEnabled()
        ));
    }

    /**
     * Set transformer type.
     */
    @PostMapping("/type/{type}")
    public ResponseEntity<Map<String, Object>> setTransformerType(@PathVariable String type) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Query transformer module not loaded"
            ));
        }

        // Validate type
        List<String> validTypes = List.of("passthrough", "compression", "expansion", "hyde", "step-back", "multi-query");
        if (!validTypes.contains(type.toLowerCase())) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "message", "Invalid transformer type: " + type,
                    "validTypes", validTypes
            ));
        }

        properties.setType(type.toLowerCase());
        logger.info("Query transformer type set to: {}", type);

        return ResponseEntity.ok(Map.of(
                "success", true,
                "type", properties.getType()
        ));
    }

    /**
     * Get available transformer types with descriptions.
     */
    @GetMapping("/types")
    public ResponseEntity<List<Map<String, Object>>> getTransformerTypes() {
        return ResponseEntity.ok(List.of(
                Map.of(
                        "type", "passthrough",
                        "name", "Passthrough",
                        "description", "No transformation - query is used as-is",
                        "requiresLlm", false
                ),
                Map.of(
                        "type", "compression",
                        "name", "Compression",
                        "description", "Compresses conversation history into a standalone query",
                        "requiresLlm", true
                ),
                Map.of(
                        "type", "expansion",
                        "name", "Expansion",
                        "description", "Generates multiple related queries to improve retrieval coverage",
                        "requiresLlm", true
                ),
                Map.of(
                        "type", "hyde",
                        "name", "HyDE (Hypothetical Document Embedding)",
                        "description", "Generates a hypothetical answer to use for retrieval",
                        "requiresLlm", true
                ),
                Map.of(
                        "type", "step-back",
                        "name", "Step-Back",
                        "description", "Generates broader abstract queries for complex questions",
                        "requiresLlm", true
                ),
                Map.of(
                        "type", "multi-query",
                        "name", "Multi-Query Decomposition",
                        "description", "Breaks complex queries into simpler sub-queries",
                        "requiresLlm", true
                )
        ));
    }

    /**
     * Apply a preset configuration.
     */
    @PostMapping("/preset/{preset}")
    public ResponseEntity<Map<String, Object>> applyPreset(@PathVariable String preset) {
        if (properties == null) {
            return ResponseEntity.status(503).body(Map.of(
                    "success", false,
                    "message", "Query transformer module not loaded"
            ));
        }

        switch (preset.toLowerCase()) {
            case "simple":
                // Simple preset - passthrough, no transformation
                properties.setEnabled(true);
                properties.setType("passthrough");
                break;
            case "conversational":
                // Good for chat-like interactions
                properties.setEnabled(true);
                properties.setType("compression");
                properties.setTemperature(0.3);
                break;
            case "comprehensive":
                // Maximize retrieval coverage
                properties.setEnabled(true);
                properties.setType("expansion");
                properties.setMaxQueries(5);
                properties.setIncludeOriginal(true);
                properties.setTemperature(0.7);
                break;
            case "precise":
                // For complex factual queries
                properties.setEnabled(true);
                properties.setType("step-back");
                properties.setTemperature(0.3);
                break;
            case "hyde":
                // For semantic similarity focused retrieval
                properties.setEnabled(true);
                properties.setType("hyde");
                properties.setTemperature(0.5);
                break;
            default:
                return ResponseEntity.badRequest().body(Map.of(
                        "success", false,
                        "message", "Unknown preset: " + preset,
                        "availablePresets", List.of("simple", "conversational", "comprehensive", "precise", "hyde")
                ));
        }

        logger.info("Applied query transformer preset: {}", preset);
        return getConfig();
    }

    /**
     * Get available presets.
     */
    @GetMapping("/presets")
    public ResponseEntity<List<Map<String, Object>>> getPresets() {
        return ResponseEntity.ok(List.of(
                Map.of(
                        "preset", "simple",
                        "name", "Simple",
                        "description", "No transformation - direct query processing",
                        "type", "passthrough"
                ),
                Map.of(
                        "preset", "conversational",
                        "name", "Conversational",
                        "description", "Best for chat-like interactions with conversation history",
                        "type", "compression"
                ),
                Map.of(
                        "preset", "comprehensive",
                        "name", "Comprehensive",
                        "description", "Maximizes retrieval coverage with multiple expanded queries",
                        "type", "expansion"
                ),
                Map.of(
                        "preset", "precise",
                        "name", "Precise",
                        "description", "Best for complex factual queries using step-back reasoning",
                        "type", "step-back"
                ),
                Map.of(
                        "preset", "hyde",
                        "name", "Semantic",
                        "description", "Uses hypothetical answers for semantic-focused retrieval",
                        "type", "hyde"
                )
        ));
    }
}

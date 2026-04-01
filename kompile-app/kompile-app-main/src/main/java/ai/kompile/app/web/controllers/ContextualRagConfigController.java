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

import ai.kompile.app.config.ContextualRagConfig;
import ai.kompile.app.services.ContextualChunkEnricher;
import ai.kompile.app.services.ContextualRagConfigService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * REST API for managing Contextual RAG configuration.
 *
 * <p>This controller provides endpoints for:</p>
 * <ul>
 *   <li>Getting and updating contextual RAG settings</li>
 *   <li>Applying configuration presets</li>
 *   <li>Managing caches</li>
 *   <li>Getting status information</li>
 * </ul>
 *
 * <p>All configuration changes are persisted to:
 * ~/.kompile/config/contextual-rag-config.json</p>
 */
@RestController
@RequestMapping("/api/contextual-rag")
@CrossOrigin(origins = "*")
public class ContextualRagConfigController {

    private static final Logger log = LoggerFactory.getLogger(ContextualRagConfigController.class);

    private final ContextualRagConfigService configService;
    private final ContextualChunkEnricher enricher;

    @Autowired
    public ContextualRagConfigController(
            ContextualRagConfigService configService,
            @Autowired(required = false) ContextualChunkEnricher enricher) {
        this.configService = configService;
        this.enricher = enricher;
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CONFIGURATION ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets the current contextual RAG configuration.
     */
    @GetMapping("/config")
    public ResponseEntity<ContextualRagConfig> getConfiguration() {
        return ResponseEntity.ok(configService.getConfiguration());
    }

    /**
     * Updates the contextual RAG configuration.
     * Only non-null fields in the request will be updated.
     */
    @PostMapping("/config")
    public ResponseEntity<ContextualRagConfig> updateConfiguration(@RequestBody ContextualRagConfig update) {
        try {
            ContextualRagConfig updated = configService.updateConfiguration(update);
            return ResponseEntity.ok(updated);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid configuration update: {}", e.getMessage());
            return ResponseEntity.badRequest().body(null);
        }
    }

    /**
     * Resets configuration to defaults.
     */
    @PostMapping("/config/reset")
    public ResponseEntity<ContextualRagConfig> resetConfiguration() {
        ContextualRagConfig reset = configService.resetConfiguration();
        return ResponseEntity.ok(reset);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // PRESET ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets available configuration presets.
     */
    @GetMapping("/presets")
    public ResponseEntity<List<ContextualRagConfigService.PresetInfo>> getPresets() {
        return ResponseEntity.ok(configService.getAvailablePresets());
    }

    /**
     * Applies a configuration preset.
     *
     * @param presetName Name of the preset (disabled, minimal, fast, balanced, quality)
     */
    @PostMapping("/presets/{presetName}")
    public ResponseEntity<ContextualRagConfig> applyPreset(@PathVariable String presetName) {
        try {
            ContextualRagConfig config = configService.applyPreset(presetName);
            return ResponseEntity.ok(config);
        } catch (IllegalArgumentException e) {
            log.warn("Invalid preset: {}", e.getMessage());
            return ResponseEntity.badRequest().body(null);
        }
    }

    /**
     * Gets a specific preset configuration without applying it.
     */
    @GetMapping("/presets/{presetName}")
    public ResponseEntity<ContextualRagConfig> getPreset(@PathVariable String presetName) {
        ContextualRagConfig preset = configService.getPreset(presetName);
        if (preset == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(preset);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // STATUS ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets the current status of contextual RAG.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new HashMap<>();

        ContextualRagConfig config = configService.getConfiguration();
        status.put("enabled", config.getEnabled());
        status.put("sourceAttributionEnabled", config.getSourceAttributionEnabled());
        status.put("llmProvider", config.getLlmProvider());
        status.put("llmModel", config.getLlmModel());
        status.put("cachingEnabled", config.getCachingEnabled());
        status.put("configFilePath", configService.getConfigFilePath());

        if (enricher != null) {
            status.put("enricherAvailable", true);
            status.put("cacheStats", enricher.getCacheStats());
        } else {
            status.put("enricherAvailable", false);
        }

        return ResponseEntity.ok(status);
    }

    /**
     * Gets the default prompt template.
     */
    @GetMapping("/prompt-template/default")
    public ResponseEntity<Map<String, String>> getDefaultPromptTemplate() {
        return ResponseEntity.ok(Map.of(
                "template", ContextualRagConfig.getDefaultPromptTemplate()
        ));
    }

    /**
     * Gets the current prompt template in use.
     */
    @GetMapping("/prompt-template")
    public ResponseEntity<Map<String, Object>> getCurrentPromptTemplate() {
        String template = configService.getPromptTemplate();
        boolean isCustom = configService.getConfiguration().getContextPromptTemplate() != null;

        return ResponseEntity.ok(Map.of(
                "template", template,
                "isCustom", isCustom
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // CACHE MANAGEMENT
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Clears the contextual enrichment caches.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, Object>> clearCaches() {
        if (enricher == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "message", "Enricher not available"
            ));
        }

        Map<String, Object> statsBefore = enricher.getCacheStats();
        enricher.clearCaches();
        Map<String, Object> statsAfter = enricher.getCacheStats();

        return ResponseEntity.ok(Map.of(
                "success", true,
                "clearedDocumentSummaries", statsBefore.get("documentSummaryCount"),
                "clearedChunkContexts", statsBefore.get("chunkContextCount"),
                "currentStats", statsAfter
        ));
    }

    /**
     * Gets cache statistics.
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        if (enricher == null) {
            return ResponseEntity.ok(Map.of(
                    "available", false
            ));
        }

        Map<String, Object> stats = new HashMap<>(enricher.getCacheStats());
        stats.put("available", true);
        stats.put("cachingEnabled", configService.getConfiguration().getCachingEnabled());

        return ResponseEntity.ok(stats);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // TOGGLE ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Quick toggle for enabling/disabling contextual enrichment.
     */
    @PostMapping("/toggle")
    public ResponseEntity<Map<String, Object>> toggleEnabled(@RequestBody Map<String, Boolean> request) {
        Boolean enabled = request.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing 'enabled' field"
            ));
        }

        ContextualRagConfig update = ContextualRagConfig.builder()
                .enabled(enabled)
                .build();

        ContextualRagConfig updated = configService.updateConfiguration(update);

        return ResponseEntity.ok(Map.of(
                "enabled", updated.getEnabled(),
                "message", enabled ? "Contextual enrichment enabled" : "Contextual enrichment disabled"
        ));
    }

    /**
     * Quick toggle for enabling/disabling source attribution.
     */
    @PostMapping("/source-attribution/toggle")
    public ResponseEntity<Map<String, Object>> toggleSourceAttribution(@RequestBody Map<String, Boolean> request) {
        Boolean enabled = request.get("enabled");
        if (enabled == null) {
            return ResponseEntity.badRequest().body(Map.of(
                    "error", "Missing 'enabled' field"
            ));
        }

        ContextualRagConfig update = ContextualRagConfig.builder()
                .sourceAttributionEnabled(enabled)
                .build();

        ContextualRagConfig updated = configService.updateConfiguration(update);

        return ResponseEntity.ok(Map.of(
                "sourceAttributionEnabled", updated.getSourceAttributionEnabled(),
                "message", enabled ? "Source attribution enabled" : "Source attribution disabled"
        ));
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // LLM PROVIDER INFO
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Gets available LLM providers and their recommended models.
     */
    @GetMapping("/providers")
    public ResponseEntity<List<ProviderInfo>> getProviders() {
        List<ProviderInfo> providers = List.of(
                new ProviderInfo("openai", "OpenAI", List.of(
                        new ModelInfo("gpt-4o-mini", "GPT-4o Mini", "Fast and cost-effective, recommended for most use cases"),
                        new ModelInfo("gpt-4o", "GPT-4o", "Higher quality, better for complex documents"),
                        new ModelInfo("gpt-4-turbo", "GPT-4 Turbo", "Large context window")
                )),
                new ProviderInfo("anthropic", "Anthropic", List.of(
                        new ModelInfo("claude-3-haiku-20240307", "Claude 3 Haiku", "Fast and efficient"),
                        new ModelInfo("claude-3-sonnet-20240229", "Claude 3 Sonnet", "Balanced performance"),
                        new ModelInfo("claude-3-5-sonnet-20241022", "Claude 3.5 Sonnet", "High quality")
                )),
                new ProviderInfo("gemini", "Google Gemini", List.of(
                        new ModelInfo("gemini-1.5-flash", "Gemini 1.5 Flash", "Fast and cost-effective"),
                        new ModelInfo("gemini-1.5-pro", "Gemini 1.5 Pro", "Higher quality")
                )),
                new ProviderInfo("ollama", "Ollama (Local)", List.of(
                        new ModelInfo("llama3.2", "Llama 3.2", "Open source, runs locally"),
                        new ModelInfo("mistral", "Mistral", "Fast local model"),
                        new ModelInfo("qwen2.5", "Qwen 2.5", "Good multilingual support")
                ))
        );

        return ResponseEntity.ok(providers);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // DEBUG & SAMPLING ENDPOINTS
    // ═══════════════════════════════════════════════════════════════════════════

    /**
     * Tests chunk contextualization with sample text.
     * This allows developers to preview how chunks will be reworded before indexing.
     */
    @PostMapping("/debug/test-contextualization")
    public ResponseEntity<Map<String, Object>> testContextualization(@RequestBody TestContextRequest request) {
        if (enricher == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "Enricher not available - ensure ChatModel is configured"
            ));
        }

        String chunkText = request.chunkText();
        String documentTitle = request.documentTitle() != null ? request.documentTitle() : "Test Document";
        String documentSummary = request.documentSummary();
        int chunkIndex = request.chunkIndex() != null ? request.chunkIndex() : 0;
        int totalChunks = request.totalChunks() != null ? request.totalChunks() : 1;

        try {
            long startTime = System.currentTimeMillis();

            // Build a simple RetrievedDoc for testing
            ai.kompile.core.retrievers.RetrievedDoc testChunk = ai.kompile.core.retrievers.RetrievedDoc.builder()
                    .id("test-chunk-" + System.currentTimeMillis())
                    .text(chunkText)
                    .metadata(new HashMap<>())
                    .build();

            // Use the enricher to contextualize
            ai.kompile.core.retrievers.RetrievedDoc enriched = enricher.enrichSingleChunk(
                    testChunk, documentSummary, documentTitle, chunkIndex, totalChunks);

            long elapsedMs = System.currentTimeMillis() - startTime;

            // Extract the contextual prefix from metadata
            String contextPrefix = null;
            if (enriched.getMetadata() != null && enriched.getMetadata().containsKey("contextual_prefix")) {
                contextPrefix = enriched.getMetadata().get("contextual_prefix").toString();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "originalText", chunkText,
                    "contextualizedText", enriched.getText(),
                    "contextPrefix", contextPrefix != null ? contextPrefix : "",
                    "wasContextualized", enriched.getMetadata() != null &&
                            Boolean.TRUE.equals(enriched.getMetadata().get("contextualized")),
                    "processingTimeMs", elapsedMs,
                    "metadata", enriched.getMetadata() != null ? enriched.getMetadata() : Map.of()
            ));

        } catch (Exception e) {
            log.error("Error testing contextualization: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", e.getMessage(),
                    "originalText", chunkText
            ));
        }
    }

    /**
     * Tests document summarization.
     */
    @PostMapping("/debug/test-summary")
    public ResponseEntity<Map<String, Object>> testDocumentSummary(@RequestBody TestSummaryRequest request) {
        if (enricher == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "Enricher not available - ensure ChatModel is configured"
            ));
        }

        String documentText = request.documentText();
        String documentTitle = request.documentTitle() != null ? request.documentTitle() : "Test Document";

        if (documentText == null || documentText.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "documentText is required"
            ));
        }

        try {
            long startTime = System.currentTimeMillis();

            // Create a simple chunk to trigger summary generation
            ai.kompile.core.retrievers.RetrievedDoc testChunk = ai.kompile.core.retrievers.RetrievedDoc.builder()
                    .id("summary-test")
                    .text("Sample chunk for summary test.")
                    .metadata(new HashMap<>())
                    .build();

            // Enrich with the full document text to generate summary
            ai.kompile.core.retrievers.RetrievedDoc enriched = enricher.enrichSingleChunk(
                    testChunk, documentText, documentTitle, 0, 1);

            long elapsedMs = System.currentTimeMillis() - startTime;

            String documentSummary = null;
            if (enriched.getMetadata() != null && enriched.getMetadata().containsKey("document_summary")) {
                documentSummary = enriched.getMetadata().get("document_summary").toString();
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "documentTitle", documentTitle,
                    "documentLength", documentText.length(),
                    "summary", documentSummary != null ? documentSummary : "Summary not generated",
                    "summaryGenerated", documentSummary != null,
                    "processingTimeMs", elapsedMs
            ));

        } catch (Exception e) {
            log.error("Error generating document summary: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Batch test multiple chunks with contextualization.
     */
    @PostMapping("/debug/test-batch")
    public ResponseEntity<Map<String, Object>> testBatchContextualization(@RequestBody TestBatchRequest request) {
        if (enricher == null) {
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", "Enricher not available - ensure ChatModel is configured"
            ));
        }

        List<String> chunks = request.chunks();
        String documentTitle = request.documentTitle() != null ? request.documentTitle() : "Test Document";
        String documentText = request.documentText();

        if (chunks == null || chunks.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of(
                    "success", false,
                    "error", "chunks list is required"
            ));
        }

        try {
            long startTime = System.currentTimeMillis();

            // Convert to RetrievedDocs
            List<ai.kompile.core.retrievers.RetrievedDoc> testChunks = new java.util.ArrayList<>();
            for (int i = 0; i < chunks.size(); i++) {
                testChunks.add(ai.kompile.core.retrievers.RetrievedDoc.builder()
                        .id("test-chunk-" + i)
                        .text(chunks.get(i))
                        .metadata(new HashMap<>())
                        .build());
            }

            // Enrich all chunks
            List<ai.kompile.core.retrievers.RetrievedDoc> enrichedChunks =
                    enricher.enrichChunks(testChunks, documentText, documentTitle);

            long elapsedMs = System.currentTimeMillis() - startTime;

            // Build results
            List<Map<String, Object>> results = new java.util.ArrayList<>();
            for (int i = 0; i < enrichedChunks.size(); i++) {
                ai.kompile.core.retrievers.RetrievedDoc enriched = enrichedChunks.get(i);
                String original = i < chunks.size() ? chunks.get(i) : "";

                String contextPrefix = null;
                if (enriched.getMetadata() != null && enriched.getMetadata().containsKey("contextual_prefix")) {
                    contextPrefix = enriched.getMetadata().get("contextual_prefix").toString();
                }

                results.add(Map.of(
                        "index", i,
                        "originalText", original,
                        "contextualizedText", enriched.getText(),
                        "contextPrefix", contextPrefix != null ? contextPrefix : "",
                        "wasContextualized", enriched.getMetadata() != null &&
                                Boolean.TRUE.equals(enriched.getMetadata().get("contextualized"))
                ));
            }

            return ResponseEntity.ok(Map.of(
                    "success", true,
                    "totalChunks", chunks.size(),
                    "processedChunks", enrichedChunks.size(),
                    "processingTimeMs", elapsedMs,
                    "avgTimePerChunk", chunks.size() > 0 ? elapsedMs / chunks.size() : 0,
                    "results", results
            ));

        } catch (Exception e) {
            log.error("Error in batch contextualization test: {}", e.getMessage(), e);
            return ResponseEntity.ok(Map.of(
                    "success", false,
                    "error", e.getMessage()
            ));
        }
    }

    /**
     * Preview how the prompt will be constructed for a given chunk.
     */
    @PostMapping("/debug/preview-prompt")
    public ResponseEntity<Map<String, Object>> previewPrompt(@RequestBody TestContextRequest request) {
        String chunkText = request.chunkText();
        String documentTitle = request.documentTitle() != null ? request.documentTitle() : "Test Document";
        String documentSummary = request.documentSummary() != null ? request.documentSummary() : "No summary provided";
        int chunkIndex = request.chunkIndex() != null ? request.chunkIndex() : 0;
        int totalChunks = request.totalChunks() != null ? request.totalChunks() : 1;

        String promptTemplate = configService.getPromptTemplate();
        String renderedPrompt = promptTemplate
                .replace("{document_title}", documentTitle)
                .replace("{document_summary}", documentSummary)
                .replace("{chunk_text}", chunkText != null ? chunkText : "")
                .replace("{chunk_index}", String.valueOf(chunkIndex + 1))
                .replace("{total_chunks}", String.valueOf(totalChunks));

        return ResponseEntity.ok(Map.of(
                "promptTemplate", promptTemplate,
                "renderedPrompt", renderedPrompt,
                "placeholders", Map.of(
                        "document_title", documentTitle,
                        "document_summary", documentSummary,
                        "chunk_text", chunkText != null ? chunkText : "",
                        "chunk_index", chunkIndex + 1,
                        "total_chunks", totalChunks
                )
        ));
    }

    /**
     * Sample existing indexed chunks with their contextualization.
     * This allows developers to inspect how chunks were processed.
     */
    @GetMapping("/debug/sample-chunks")
    public ResponseEntity<Map<String, Object>> sampleIndexedChunks(
            @RequestParam(defaultValue = "5") int limit,
            @RequestParam(required = false) String sourceFilter) {

        // This would need integration with VectorStore to sample actual chunks
        // For now, return info about the feature
        return ResponseEntity.ok(Map.of(
                "available", false,
                "message", "Chunk sampling requires VectorStore integration. Use /debug/test-* endpoints to test contextualization.",
                "requestedLimit", limit,
                "sourceFilter", sourceFilter != null ? sourceFilter : "none"
        ));
    }

    /**
     * Compare original vs contextualized text side by side.
     */
    @PostMapping("/debug/compare")
    public ResponseEntity<Map<String, Object>> compareContextualization(@RequestBody TestContextRequest request) {
        Map<String, Object> result = new HashMap<>();

        String originalText = request.chunkText();
        result.put("original", Map.of(
                "text", originalText,
                "length", originalText != null ? originalText.length() : 0,
                "wordCount", originalText != null ? originalText.split("\\s+").length : 0
        ));

        // Get contextualized version if enricher is available
        if (enricher != null && originalText != null) {
            try {
                ai.kompile.core.retrievers.RetrievedDoc testChunk = ai.kompile.core.retrievers.RetrievedDoc.builder()
                        .id("compare-test")
                        .text(originalText)
                        .metadata(new HashMap<>())
                        .build();

                ai.kompile.core.retrievers.RetrievedDoc enriched = enricher.enrichSingleChunk(
                        testChunk,
                        request.documentSummary(),
                        request.documentTitle() != null ? request.documentTitle() : "Test Document",
                        request.chunkIndex() != null ? request.chunkIndex() : 0,
                        request.totalChunks() != null ? request.totalChunks() : 1);

                String contextualizedText = enriched.getText();
                String contextPrefix = enriched.getMetadata() != null ?
                        (String) enriched.getMetadata().get("contextual_prefix") : null;

                result.put("contextualized", Map.of(
                        "text", contextualizedText,
                        "length", contextualizedText.length(),
                        "wordCount", contextualizedText.split("\\s+").length,
                        "contextPrefix", contextPrefix != null ? contextPrefix : "",
                        "addedLength", contextualizedText.length() - originalText.length(),
                        "addedWords", contextualizedText.split("\\s+").length - originalText.split("\\s+").length
                ));

                result.put("success", true);
                result.put("enricherUsed", true);

            } catch (Exception e) {
                result.put("success", false);
                result.put("error", e.getMessage());
            }
        } else {
            result.put("success", false);
            result.put("enricherUsed", false);
            result.put("message", "Enricher not available or no text provided");
        }

        return ResponseEntity.ok(result);
    }

    // ═══════════════════════════════════════════════════════════════════════════
    // HELPER RECORDS
    // ═══════════════════════════════════════════════════════════════════════════

    public record ProviderInfo(String id, String displayName, List<ModelInfo> models) {}
    public record ModelInfo(String id, String displayName, String description) {}

    public record TestContextRequest(
            String chunkText,
            String documentTitle,
            String documentSummary,
            Integer chunkIndex,
            Integer totalChunks
    ) {}

    public record TestSummaryRequest(
            String documentText,
            String documentTitle
    ) {}

    public record TestBatchRequest(
            List<String> chunks,
            String documentTitle,
            String documentText
    ) {}
}

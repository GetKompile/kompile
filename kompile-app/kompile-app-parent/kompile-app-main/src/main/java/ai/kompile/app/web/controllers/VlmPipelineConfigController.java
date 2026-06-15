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

import ai.kompile.modelmanager.cache.PipelineCacheEntry;
import ai.kompile.modelmanager.cache.PipelineOutputCache;
import ai.kompile.modelmanager.vlm.dynamic.*;
import ai.kompile.modelmanager.vlm.registry.VlmPipelineRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.stream.Collectors;

/**
 * REST API controller for VLM pipeline configuration management.
 *
 * <p>Provides CRUD operations for dynamic VLM pipelines, stages, and model sets.
 * Works with {@link VlmPipelineRegistry} for persistence and validation.</p>
 *
 * <h2>API Endpoints</h2>
 * <table>
 *   <tr><th>Method</th><th>Endpoint</th><th>Description</th></tr>
 *   <tr><td>GET</td><td>/api/vlm/config/pipelines</td><td>List all pipelines</td></tr>
 *   <tr><td>GET</td><td>/api/vlm/config/pipelines/{id}</td><td>Get pipeline</td></tr>
 *   <tr><td>POST</td><td>/api/vlm/config/pipelines</td><td>Create pipeline</td></tr>
 *   <tr><td>PUT</td><td>/api/vlm/config/pipelines/{id}</td><td>Update pipeline</td></tr>
 *   <tr><td>DELETE</td><td>/api/vlm/config/pipelines/{id}</td><td>Delete pipeline</td></tr>
 *   <tr><td>POST</td><td>/api/vlm/config/pipelines/validate</td><td>Validate pipeline</td></tr>
 *   <tr><td>GET</td><td>/api/vlm/config/stages</td><td>List all stages</td></tr>
 *   <tr><td>POST</td><td>/api/vlm/config/stages</td><td>Create custom stage</td></tr>
 *   <tr><td>GET</td><td>/api/vlm/config/model-sets</td><td>List all model sets</td></tr>
 *   <tr><td>POST</td><td>/api/vlm/config/model-sets</td><td>Create custom model set</td></tr>
 *   <tr><td>DELETE</td><td>/api/vlm/config/model-sets/{id}</td><td>Delete custom model set</td></tr>
 * </table>
 *
 * @author Kompile Inc.
 */
@RestController
@RequestMapping("/api/vlm/config")
@CrossOrigin(origins = "*")
public class VlmPipelineConfigController {

    private static final Logger log = LoggerFactory.getLogger(VlmPipelineConfigController.class);

    private final VlmPipelineRegistry registry;

    @Autowired(required = false)
    private PipelineOutputCache outputCache;

    public VlmPipelineConfigController() {
        this.registry = VlmPipelineRegistry.getInstance();
    }

    // ==================== Pipeline Endpoints ====================

    /**
     * List all pipelines (builtin + custom).
     */
    @GetMapping("/pipelines")
    public ResponseEntity<List<PipelineListItem>> listPipelines(
            @RequestParam(required = false, defaultValue = "false") boolean customOnly) {

        Collection<VlmPipelineDefinition> pipelines = customOnly ?
            registry.getCustomPipelines() : registry.getAllPipelines();

        List<PipelineListItem> items = pipelines.stream()
            .map(this::toPipelineListItem)
            .sorted(Comparator.comparing(p -> !p.isBuiltin))
            .toList();

        return ResponseEntity.ok(items);
    }

    /**
     * Get a pipeline by ID.
     */
    @GetMapping("/pipelines/{pipelineId}")
    public ResponseEntity<VlmPipelineDefinition> getPipeline(@PathVariable String pipelineId) {
        return registry.getPipeline(pipelineId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a new pipeline.
     */
    @PostMapping("/pipelines")
    public ResponseEntity<Map<String, Object>> createPipeline(@RequestBody VlmPipelineDefinition pipeline) {
        // Don't allow creating as builtin
        pipeline.setBuiltin(false);

        List<String> errors = registry.registerPipeline(pipeline);

        Map<String, Object> response = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            response.put("success", true);
            response.put("pipelineId", pipeline.getPipelineId());
            response.put("message", "Pipeline created successfully");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            response.put("success", false);
            response.put("errors", errors);
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Update an existing pipeline.
     */
    @PutMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Object>> updatePipeline(
            @PathVariable String pipelineId,
            @RequestBody VlmPipelineDefinition pipeline) {

        List<String> errors = registry.updatePipeline(pipelineId, pipeline);

        Map<String, Object> response = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            response.put("success", true);
            response.put("pipelineId", pipelineId);
            response.put("message", "Pipeline updated successfully");
            return ResponseEntity.ok(response);
        } else {
            response.put("success", false);
            response.put("errors", errors);
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Delete a pipeline.
     */
    @DeleteMapping("/pipelines/{pipelineId}")
    public ResponseEntity<Map<String, Object>> deletePipeline(@PathVariable String pipelineId) {
        Map<String, Object> response = new LinkedHashMap<>();

        Optional<VlmPipelineDefinition> existing = registry.getPipeline(pipelineId);
        if (existing.isEmpty()) {
            response.put("success", false);
            response.put("error", "Pipeline not found: " + pipelineId);
            return ResponseEntity.notFound().build();
        }

        if (existing.get().isBuiltin()) {
            response.put("success", false);
            response.put("error", "Cannot delete builtin pipeline");
            return ResponseEntity.badRequest().body(response);
        }

        boolean deleted = registry.deletePipeline(pipelineId);
        response.put("success", deleted);
        response.put("pipelineId", pipelineId);
        response.put("message", deleted ? "Pipeline deleted" : "Delete failed");

        return ResponseEntity.ok(response);
    }

    /**
     * Validate a pipeline configuration without saving.
     */
    @PostMapping("/pipelines/validate")
    public ResponseEntity<Map<String, Object>> validatePipeline(@RequestBody VlmPipelineDefinition pipeline) {
        List<String> errors = registry.validatePipeline(pipeline);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("valid", errors.isEmpty());
        response.put("errors", errors);

        return ResponseEntity.ok(response);
    }

    // ==================== Stage Endpoints ====================

    /**
     * List all stages (builtin + custom).
     */
    @GetMapping("/stages")
    public ResponseEntity<List<StageListItem>> listStages(
            @RequestParam(required = false, defaultValue = "false") boolean customOnly) {

        Collection<VlmStageDefinition> stages = customOnly ?
            registry.getCustomStages() : registry.getAllStages();

        List<StageListItem> items = stages.stream()
            .map(this::toStageListItem)
            .sorted(Comparator.comparing(s -> !s.isBuiltin))
            .toList();

        return ResponseEntity.ok(items);
    }

    /**
     * Get a stage by ID.
     */
    @GetMapping("/stages/{stageId}")
    public ResponseEntity<VlmStageDefinition> getStage(@PathVariable String stageId) {
        return registry.getStage(stageId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a custom stage.
     */
    @PostMapping("/stages")
    public ResponseEntity<Map<String, Object>> createStage(@RequestBody VlmStageDefinition stage) {
        // Don't allow creating as builtin
        stage.setBuiltin(false);

        List<String> errors = registry.registerStage(stage);

        Map<String, Object> response = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            response.put("success", true);
            response.put("stageId", stage.getStageId());
            response.put("message", "Stage created successfully");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            response.put("success", false);
            response.put("errors", errors);
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Delete a custom stage.
     */
    @DeleteMapping("/stages/{stageId}")
    public ResponseEntity<Map<String, Object>> deleteStage(@PathVariable String stageId) {
        Map<String, Object> response = new LinkedHashMap<>();

        Optional<VlmStageDefinition> existing = registry.getStage(stageId);
        if (existing.isEmpty()) {
            response.put("success", false);
            response.put("error", "Stage not found: " + stageId);
            return ResponseEntity.notFound().build();
        }

        if (existing.get().isBuiltin()) {
            response.put("success", false);
            response.put("error", "Cannot delete builtin stage");
            return ResponseEntity.badRequest().body(response);
        }

        boolean deleted = registry.deleteStage(stageId);
        response.put("success", deleted);
        response.put("stageId", stageId);
        response.put("message", deleted ? "Stage deleted" : "Delete failed");

        return ResponseEntity.ok(response);
    }

    // ==================== Model Set Endpoints ====================

    /**
     * List all model sets (builtin + custom).
     */
    @GetMapping("/model-sets")
    public ResponseEntity<List<ModelSetListItem>> listModelSets(
            @RequestParam(required = false, defaultValue = "false") boolean customOnly) {

        Collection<VlmCustomModelSet> modelSets = customOnly ?
            registry.getCustomModelSets() : registry.getAllModelSets();

        List<ModelSetListItem> items = modelSets.stream()
            .map(this::toModelSetListItem)
            .sorted(Comparator.comparing(m -> !m.isBuiltin))
            .toList();

        return ResponseEntity.ok(items);
    }

    /**
     * Get a model set by ID.
     */
    @GetMapping("/model-sets/{setId}")
    public ResponseEntity<VlmCustomModelSet> getModelSet(@PathVariable String setId) {
        return registry.getModelSet(setId)
            .map(ResponseEntity::ok)
            .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create a custom model set.
     */
    @PostMapping("/model-sets")
    public ResponseEntity<Map<String, Object>> createModelSet(@RequestBody VlmCustomModelSet modelSet) {
        // Don't allow creating as builtin
        modelSet.setBuiltin(false);

        List<String> errors = registry.registerModelSet(modelSet);

        Map<String, Object> response = new LinkedHashMap<>();
        if (errors.isEmpty()) {
            response.put("success", true);
            response.put("setId", modelSet.getSetId());
            response.put("message", "Model set created successfully");
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        } else {
            response.put("success", false);
            response.put("errors", errors);
            return ResponseEntity.badRequest().body(response);
        }
    }

    /**
     * Delete a custom model set.
     */
    @DeleteMapping("/model-sets/{setId}")
    public ResponseEntity<Map<String, Object>> deleteModelSet(@PathVariable String setId) {
        Map<String, Object> response = new LinkedHashMap<>();

        Optional<VlmCustomModelSet> existing = registry.getModelSet(setId);
        if (existing.isEmpty()) {
            response.put("success", false);
            response.put("error", "Model set not found: " + setId);
            return ResponseEntity.notFound().build();
        }

        if (existing.get().isBuiltin()) {
            response.put("success", false);
            response.put("error", "Cannot delete builtin model set");
            return ResponseEntity.badRequest().body(response);
        }

        boolean deleted = registry.deleteModelSet(setId);
        response.put("success", deleted);
        response.put("setId", setId);
        response.put("message", deleted ? "Model set deleted" : "Delete failed");

        return ResponseEntity.ok(response);
    }

    // ==================== Registry Stats ====================

    /**
     * Get registry statistics.
     */
    @GetMapping("/stats")
    public ResponseEntity<Map<String, Object>> getStats() {
        return ResponseEntity.ok(registry.getStats());
    }

    /**
     * Clear all custom configurations.
     */
    @PostMapping("/clear-custom")
    public ResponseEntity<Map<String, Object>> clearCustomConfigs() {
        registry.clearCustomConfigs();

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "All custom configurations cleared");
        response.put("stats", registry.getStats());

        return ResponseEntity.ok(response);
    }

    // ==================== Cache Management ====================

    /**
     * Get cache statistics.
     */
    @GetMapping("/cache/stats")
    public ResponseEntity<Map<String, Object>> getCacheStats() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("available", false);
            response.put("message", "Pipeline output cache is not configured");
            return ResponseEntity.ok(response);
        }

        PipelineOutputCache.CacheStats stats = outputCache.getStats();
        response.put("available", true);
        response.put("totalEntries", stats.totalEntries());
        response.put("finalOutputEntries", stats.finalOutputEntries());
        response.put("stageCheckpointEntries", stats.stageCheckpointEntries());
        response.put("totalSizeBytes", stats.totalSizeBytes());
        response.put("totalSizeMB", stats.totalSizeBytes() / (1024.0 * 1024.0));
        response.put("hitCount", stats.hitCount());
        response.put("missCount", stats.missCount());
        response.put("hitRate", stats.hitRate());

        return ResponseEntity.ok(response);
    }

    /**
     * Browse cache entries with pagination.
     * Returns entries enriched with pipeline display names and model set info.
     */
    @GetMapping("/cache/entries")
    public ResponseEntity<Map<String, Object>> listCacheEntries(
            @RequestParam(required = false, defaultValue = "0") int offset,
            @RequestParam(required = false, defaultValue = "50") int limit,
            @RequestParam(required = false) String type) {

        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("available", false);
            response.put("entries", Collections.emptyList());
            response.put("total", 0);
            return ResponseEntity.ok(response);
        }

        // Get entries
        List<PipelineCacheEntry> entries;
        long total;
        if ("final".equalsIgnoreCase(type)) {
            entries = outputCache.listEntries(PipelineCacheEntry.EntryType.FINAL_OUTPUT, offset, limit);
            total = outputCache.countEntries(PipelineCacheEntry.EntryType.FINAL_OUTPUT);
        } else if ("checkpoint".equalsIgnoreCase(type)) {
            entries = outputCache.listEntries(PipelineCacheEntry.EntryType.STAGE_CHECKPOINT, offset, limit);
            total = outputCache.countEntries(PipelineCacheEntry.EntryType.STAGE_CHECKPOINT);
        } else {
            entries = outputCache.listEntries(offset, limit);
            total = outputCache.countEntries(null);
        }

        // Convert to browsable DTOs
        List<CacheEntryListItem> items = entries.stream()
                .map(this::toCacheEntryListItem)
                .collect(Collectors.toList());

        response.put("available", true);
        response.put("entries", items);
        response.put("total", total);
        response.put("offset", offset);
        response.put("limit", limit);

        return ResponseEntity.ok(response);
    }

    /**
     * Get a single cache entry by its cache key string.
     */
    @GetMapping("/cache/entries/{cacheKey}")
    public ResponseEntity<Map<String, Object>> getCacheEntry(@PathVariable String cacheKey) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("found", false);
            return ResponseEntity.ok(response);
        }

        // Search through entries to find by key
        List<PipelineCacheEntry> all = outputCache.listEntries(0, 10000);
        Optional<PipelineCacheEntry> match = all.stream()
                .filter(e -> cacheKey.equals(e.getCacheKey()) ||
                             cacheKey.equals(e.getCacheKey() != null ? e.getCacheKey().replace(':', '_') : ""))
                .findFirst();

        if (match.isEmpty()) {
            response.put("found", false);
            return ResponseEntity.ok(response);
        }

        PipelineCacheEntry entry = match.get();
        CacheEntryListItem item = toCacheEntryListItem(entry);
        response.put("found", true);
        response.put("entry", item);

        // Include output preview (first 500 chars)
        if (entry.getSerializedOutput() != null) {
            String preview = entry.getSerializedOutput().length() > 500
                    ? entry.getSerializedOutput().substring(0, 500) + "..."
                    : entry.getSerializedOutput();
            response.put("outputPreview", preview);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Invalidate cache for a specific content hash.
     */
    @DeleteMapping("/cache/content/{contentHash}")
    public ResponseEntity<Map<String, Object>> invalidateCacheByContent(@PathVariable String contentHash) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("success", false);
            response.put("error", "Pipeline output cache is not configured");
            return ResponseEntity.badRequest().body(response);
        }

        int removed = outputCache.removeByContentHash(contentHash);
        response.put("success", true);
        response.put("removedEntries", removed);
        response.put("contentHash", contentHash);

        return ResponseEntity.ok(response);
    }

    /**
     * Invalidate cache for a specific pipeline.
     */
    @DeleteMapping("/cache/pipeline/{pipelineId}")
    public ResponseEntity<Map<String, Object>> invalidateCacheByPipeline(@PathVariable String pipelineId) {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("success", false);
            response.put("error", "Pipeline output cache is not configured");
            return ResponseEntity.badRequest().body(response);
        }

        int removed = outputCache.removeByPipeline(pipelineId);
        response.put("success", true);
        response.put("removedEntries", removed);
        response.put("pipelineId", pipelineId);

        return ResponseEntity.ok(response);
    }

    /**
     * Evict expired cache entries.
     */
    @PostMapping("/cache/evict")
    public ResponseEntity<Map<String, Object>> evictExpiredCache() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("success", false);
            response.put("error", "Pipeline output cache is not configured");
            return ResponseEntity.badRequest().body(response);
        }

        int evicted = outputCache.evictExpired();
        response.put("success", true);
        response.put("evictedEntries", evicted);

        return ResponseEntity.ok(response);
    }

    /**
     * Clear all cache entries.
     */
    @PostMapping("/cache/clear")
    public ResponseEntity<Map<String, Object>> clearCache() {
        Map<String, Object> response = new LinkedHashMap<>();

        if (outputCache == null || !outputCache.isAvailable()) {
            response.put("success", false);
            response.put("error", "Pipeline output cache is not configured");
            return ResponseEntity.badRequest().body(response);
        }

        outputCache.clear();
        response.put("success", true);
        response.put("message", "Cache cleared");

        return ResponseEntity.ok(response);
    }

    // ==================== DTO Conversion ====================

    private PipelineListItem toPipelineListItem(VlmPipelineDefinition pipeline) {
        PipelineListItem item = new PipelineListItem();
        item.pipelineId = pipeline.getPipelineId();
        item.displayName = pipeline.getDisplayName();
        item.description = pipeline.getDescription();
        item.pipelineType = pipeline.getPipelineType() != null ?
            pipeline.getPipelineType().name() : null;
        item.isBuiltin = pipeline.isBuiltin();
        item.enabled = pipeline.isEnabled();
        item.stageCount = pipeline.isSequence() ?
            pipeline.getStages().size() : pipeline.getGraphNodes().size();
        item.extractionTypes = pipeline.getExtractionTypes();
        item.modelSetId = pipeline.getModelSetId();
        item.createdAt = pipeline.getCreatedAt();
        item.updatedAt = pipeline.getUpdatedAt();
        return item;
    }

    private StageListItem toStageListItem(VlmStageDefinition stage) {
        StageListItem item = new StageListItem();
        item.stageId = stage.getStageId();
        item.displayName = stage.getDisplayName();
        item.inputDescription = stage.getInputDescription();
        item.outputDescription = stage.getOutputDescription();
        item.modelComponentKey = stage.getModelComponentKey();
        item.requiresModel = stage.isRequiresModel();
        item.isBuiltin = stage.isBuiltin();
        return item;
    }

    private ModelSetListItem toModelSetListItem(VlmCustomModelSet modelSet) {
        ModelSetListItem item = new ModelSetListItem();
        item.setId = modelSet.getSetId();
        item.displayName = modelSet.getDisplayName();
        item.description = modelSet.getDescription();
        item.source = modelSet.getSource() != null ? modelSet.getSource().name() : null;
        item.huggingFaceRepo = modelSet.getHuggingFaceRepo();
        item.componentCount = modelSet.getComponents().size();
        item.isBuiltin = modelSet.isBuiltin();
        item.createdAt = modelSet.getCreatedAt();
        item.updatedAt = modelSet.getUpdatedAt();
        return item;
    }

    private CacheEntryListItem toCacheEntryListItem(PipelineCacheEntry entry) {
        CacheEntryListItem item = new CacheEntryListItem();
        item.cacheKey = entry.getCacheKey();
        item.entryType = entry.getEntryType() != null ? entry.getEntryType().name() : null;
        item.pipelineId = entry.getPipelineId();
        item.stageId = entry.getStageId();
        item.contentHash = entry.getContentHash();
        item.contentHashShort = entry.getContentHash() != null && entry.getContentHash().length() > 12
                ? entry.getContentHash().substring(0, 12) + "..."
                : entry.getContentHash();
        item.outputClassName = entry.getOutputClassName();
        item.hitCount = entry.getHitCount();
        item.sizeBytes = entry.getSizeBytes();
        item.sizeMB = entry.getSizeBytes() / (1024.0 * 1024.0);
        item.createdAt = entry.getCreatedAt() != null ? entry.getCreatedAt().toString() : null;
        item.lastAccessedAt = entry.getLastAccessedAt() != null ? entry.getLastAccessedAt().toString() : null;
        item.metadata = entry.getMetadata();

        // Enrich with pipeline display name from registry
        if (entry.getPipelineId() != null) {
            registry.getPipeline(entry.getPipelineId())
                    .ifPresent(p -> {
                        item.pipelineDisplayName = p.getDisplayName();
                        item.modelSetId = p.getModelSetId();
                    });
        }

        // Extract model info from metadata if available
        if (entry.getMetadata() != null) {
            Object modelId = entry.getMetadata().get("modelSetId");
            if (modelId != null && item.modelSetId == null) {
                item.modelSetId = modelId.toString();
            }
        }

        return item;
    }

    // ==================== DTO Classes ====================

    public static class PipelineListItem {
        public String pipelineId;
        public String displayName;
        public String description;
        public String pipelineType;
        public boolean isBuiltin;
        public boolean enabled;
        public int stageCount;
        public List<String> extractionTypes;
        public String modelSetId;
        public long createdAt;
        public long updatedAt;
    }

    public static class StageListItem {
        public String stageId;
        public String displayName;
        public String inputDescription;
        public String outputDescription;
        public String modelComponentKey;
        public boolean requiresModel;
        public boolean isBuiltin;
    }

    public static class ModelSetListItem {
        public String setId;
        public String displayName;
        public String description;
        public String source;
        public String huggingFaceRepo;
        public int componentCount;
        public boolean isBuiltin;
        public long createdAt;
        public long updatedAt;
    }

    public static class CacheEntryListItem {
        public String cacheKey;
        public String entryType;
        public String pipelineId;
        public String pipelineDisplayName;
        public String stageId;
        public String contentHash;
        public String contentHashShort;
        public String outputClassName;
        public String modelSetId;
        public long hitCount;
        public long sizeBytes;
        public double sizeMB;
        public String createdAt;
        public String lastAccessedAt;
        public Map<String, Object> metadata;
    }
}

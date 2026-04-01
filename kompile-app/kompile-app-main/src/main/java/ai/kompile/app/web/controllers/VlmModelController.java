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

import ai.kompile.modelmanager.vlm.*;
import ai.kompile.ocr.integration.OcrPipelineService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.awt.image.BufferedImage;
import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import javax.imageio.ImageIO;

/**
 * REST API controller for VLM (Vision-Language Model) management.
 *
 * Provides endpoints for:
 * - Listing available VLM model sets
 * - Downloading model sets
 * - Checking download status
 * - Getting pipeline stage information
 * - Managing VLM extraction configurations
 */
@RestController
@RequestMapping("/api/vlm")
@CrossOrigin(origins = "*")
public class VlmModelController {

    private static final Logger log = LoggerFactory.getLogger(VlmModelController.class);

    private final VlmDocumentExtractorService vlmService;
    private final OcrPipelineService ocrPipelineService;

    // Track active downloads
    private final Map<String, DownloadProgress> activeDownloads = new ConcurrentHashMap<>();

    @Autowired
    public VlmModelController(@Autowired(required = false) OcrPipelineService ocrPipelineService) {
        this.vlmService = new VlmDocumentExtractorService();
        this.ocrPipelineService = ocrPipelineService;
    }

    // ==================== Model Set Endpoints ====================

    /**
     * Get all available VLM model sets.
     */
    @GetMapping("/model-sets")
    public ResponseEntity<List<ModelSetInfo>> getModelSets() {
        List<ModelSetInfo> modelSets = new ArrayList<>();

        for (VlmModelSet set : VlmModelSet.getAllModelSets()) {
            ModelSetInfo info = toModelSetInfo(set);
            modelSets.add(info);
        }

        return ResponseEntity.ok(modelSets);
    }

    /**
     * Get a specific model set by ID.
     */
    @GetMapping("/model-sets/{setId}")
    public ResponseEntity<ModelSetInfo> getModelSet(@PathVariable String setId) {
        VlmModelSet modelSet = VlmModelSet.getModelSet(setId);
        if (modelSet == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toModelSetInfo(modelSet));
    }

    /**
     * Get cache status for all model sets.
     */
    @GetMapping("/model-sets/status")
    public ResponseEntity<Map<String, Object>> getModelSetsStatus() {
        Map<String, Boolean> cacheStatus = VlmModels.getCacheStatus();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cacheDirectory", VlmModels.getCacheDirectory().toString());
        response.put("modelSets", cacheStatus);
        response.put("readyModels", vlmService.getReadyModels());
        return ResponseEntity.ok(response);
    }

    /**
     * Download a model set.
     */
    @PostMapping("/model-sets/{setId}/download")
    public ResponseEntity<Map<String, Object>> downloadModelSet(@PathVariable String setId) {
        VlmModelSet modelSet = VlmModelSet.getModelSet(setId);
        if (modelSet == null) {
            return ResponseEntity.notFound().build();
        }

        // Check if already downloading
        if (activeDownloads.containsKey(setId)) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("message", "Download already in progress");
            response.put("setId", setId);
            return ResponseEntity.status(HttpStatus.CONFLICT).body(response);
        }

        // Start async download
        DownloadProgress progress = new DownloadProgress(setId, modelSet.getDisplayName());
        activeDownloads.put(setId, progress);

        CompletableFuture.runAsync(() -> {
            try {
                VlmModelSetDownloader.DownloadSetResult result = VlmModels.download(setId,
                    (component, pct) -> {
                        progress.currentComponent = component.getFileName();
                        progress.componentProgress = pct;
                        progress.componentsCompleted = (int) (pct * modelSet.getComponents().size());
                    });

                progress.complete = true;
                progress.success = result.isSuccess();
                progress.message = result.isSuccess() ? "Download complete" : "Download failed: " + result.getFailures();

                log.info("VLM model set download completed: {} - success={}", setId, result.isSuccess());
            } catch (Exception e) {
                log.error("VLM model set download failed: {}", setId, e);
                progress.complete = true;
                progress.success = false;
                progress.message = "Download failed: " + e.getMessage();
            }
        });

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("message", "Download started");
        response.put("setId", setId);
        response.put("totalComponents", modelSet.getComponents().size());
        return ResponseEntity.accepted().body(response);
    }

    /**
     * Get download progress for a model set.
     */
    @GetMapping("/model-sets/{setId}/download/status")
    public ResponseEntity<Map<String, Object>> getDownloadStatus(@PathVariable String setId) {
        DownloadProgress progress = activeDownloads.get(setId);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("setId", setId);

        if (progress == null) {
            // Check if model is already cached
            boolean cached = VlmModels.isCached(setId);
            response.put("downloading", false);
            response.put("cached", cached);
            response.put("complete", cached);
            response.put("success", cached);
            return ResponseEntity.ok(response);
        }

        response.put("downloading", !progress.complete);
        response.put("complete", progress.complete);
        response.put("success", progress.success);
        response.put("currentComponent", progress.currentComponent);
        response.put("componentProgress", progress.componentProgress);
        response.put("componentsCompleted", progress.componentsCompleted);
        response.put("message", progress.message);

        // Clean up completed downloads
        if (progress.complete) {
            activeDownloads.remove(setId);
        }

        return ResponseEntity.ok(response);
    }

    /**
     * Delete a cached model set.
     */
    @DeleteMapping("/model-sets/{setId}")
    public ResponseEntity<Map<String, Object>> deleteModelSet(@PathVariable String setId) {
        try {
            VlmModels.deleteFromCache(setId);

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", true);
            response.put("message", "Model set deleted");
            response.put("setId", setId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("success", false);
            response.put("error", e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(response);
        }
    }

    // ==================== Pipeline Stage Endpoints ====================

    /**
     * Get all pipeline stages with documentation.
     */
    @GetMapping("/pipeline-stages")
    public ResponseEntity<List<PipelineStageInfo>> getPipelineStages() {
        List<PipelineStageInfo> stages = new ArrayList<>();

        for (VlmPipelineStage stage : VlmPipelineStage.values()) {
            PipelineStageInfo info = new PipelineStageInfo();
            info.id = stage.name();
            info.displayName = stage.getDisplayName();
            info.inputDescription = stage.getInputDescription();
            info.outputDescription = stage.getOutputDescription();
            info.modelComponentKey = stage.getModelComponentKey();
            info.requiresModel = stage.requiresModel();
            stages.add(info);
        }

        return ResponseEntity.ok(stages);
    }

    // ==================== Extraction Type Endpoints ====================

    /**
     * Get all extraction types.
     */
    @GetMapping("/extraction-types")
    public ResponseEntity<List<ExtractionTypeInfo>> getExtractionTypes() {
        List<ExtractionTypeInfo> types = new ArrayList<>();

        for (VlmExtractionType type : VlmExtractionType.values()) {
            ExtractionTypeInfo info = new ExtractionTypeInfo();
            info.id = type.getId();
            info.description = type.getDescription();
            info.defaultModelSetId = type.getDefaultModelSet() != null ?
                type.getDefaultModelSet().getSetId() : null;
            info.defaultModelSetName = type.getDefaultModelSet() != null ?
                type.getDefaultModelSet().getDisplayName() : null;
            types.add(info);
        }

        return ResponseEntity.ok(types);
    }

    // ==================== Configuration Preset Endpoints ====================

    /**
     * Get available extraction configuration presets.
     */
    @GetMapping("/presets")
    public ResponseEntity<List<PresetInfo>> getPresets() {
        List<PresetInfo> presets = new ArrayList<>();

        // Scanned Documents preset
        PresetInfo scanned = new PresetInfo();
        scanned.id = "scanned-documents";
        scanned.name = "Scanned Documents";
        scanned.description = "Full VLM processing for scanned PDFs and images";
        scanned.enabledExtractions = List.of("document-understanding", "table-extraction");
        presets.add(scanned);

        // Text PDFs preset
        PresetInfo textPdfs = new PresetInfo();
        textPdfs.id = "text-pdfs";
        textPdfs.name = "Text PDFs";
        textPdfs.description = "Text extraction with table support";
        textPdfs.enabledExtractions = List.of("table-extraction");
        presets.add(textPdfs);

        // Scientific Papers preset
        PresetInfo scientific = new PresetInfo();
        scientific.id = "scientific-papers";
        scientific.name = "Scientific Papers";
        scientific.description = "Document understanding with figures and tables";
        scientific.enabledExtractions = List.of("document-understanding", "table-extraction", "figure-understanding");
        presets.add(scientific);

        // Forms preset
        PresetInfo forms = new PresetInfo();
        forms.id = "forms";
        forms.name = "Forms & Invoices";
        forms.description = "Form field extraction with document understanding";
        forms.enabledExtractions = List.of("document-understanding", "form-extraction");
        presets.add(forms);

        // Comprehensive preset
        PresetInfo comprehensive = new PresetInfo();
        comprehensive.id = "comprehensive";
        comprehensive.name = "Comprehensive";
        comprehensive.description = "All extraction types enabled";
        comprehensive.enabledExtractions = List.of(
            "document-understanding", "table-extraction",
            "figure-understanding", "form-extraction", "image-embedding"
        );
        presets.add(comprehensive);

        return ResponseEntity.ok(presets);
    }

    /**
     * Get required models for a preset.
     */
    @GetMapping("/presets/{presetId}/models")
    public ResponseEntity<Map<String, Object>> getPresetModels(@PathVariable String presetId) {
        VlmExtractionConfig config;

        switch (presetId) {
            case "scanned-documents":
                config = VlmModels.configForScannedDocuments();
                break;
            case "text-pdfs":
                config = VlmModels.configForTextPdfs();
                break;
            case "scientific-papers":
                config = VlmModels.configForScientificPapers();
                break;
            case "forms":
                config = VlmModels.configForForms();
                break;
            case "comprehensive":
                config = VlmModels.configComprehensive();
                break;
            default:
                return ResponseEntity.notFound().build();
        }

        Set<VlmModelSet> requiredSets = config.getRequiredModelSets();
        List<ModelSetInfo> modelSets = new ArrayList<>();
        for (VlmModelSet set : requiredSets) {
            modelSets.add(toModelSetInfo(set));
        }

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("presetId", presetId);
        response.put("requiredModelSets", modelSets);
        response.put("totalComponents", requiredSets.stream()
            .mapToInt(s -> s.getComponents().size())
            .sum());

        return ResponseEntity.ok(response);
    }

    /**
     * Ensure all models for a preset are downloaded.
     */
    @PostMapping("/presets/{presetId}/ensure")
    public ResponseEntity<Map<String, Object>> ensurePresetModels(@PathVariable String presetId) {
        VlmExtractionConfig config;

        switch (presetId) {
            case "scanned-documents":
                config = VlmModels.configForScannedDocuments();
                break;
            case "text-pdfs":
                config = VlmModels.configForTextPdfs();
                break;
            case "scientific-papers":
                config = VlmModels.configForScientificPapers();
                break;
            case "forms":
                config = VlmModels.configForForms();
                break;
            case "comprehensive":
                config = VlmModels.configComprehensive();
                break;
            default:
                return ResponseEntity.notFound().build();
        }

        List<String> failures = vlmService.ensureModelsForConfig(config);

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("presetId", presetId);
        response.put("success", failures.isEmpty());
        response.put("failures", failures);
        response.put("readyModels", vlmService.getReadyModels());

        return ResponseEntity.ok(response);
    }

    // ==================== Service Status ====================

    /**
     * Get VLM service status and cache statistics.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("cacheStats", vlmService.getCacheStats());
        response.put("activeDownloads", activeDownloads.keySet());
        response.put("availableModelSets", VlmModelSet.getAvailableModelSetIds());
        return ResponseEntity.ok(response);
    }

    // ==================== Benchmark Endpoints ====================

    /**
     * Benchmark VLM throughput by processing an uploaded image.
     * Returns detailed token generation metrics including tokens/sec, TTFT, and token counts.
     *
     * @param file Image file (PNG, JPG) or single-page PDF to process
     * @param iterations Number of iterations to run for averaging (default 1, max 10)
     * @param maxTokens Maximum tokens to generate per iteration (default 4096)
     */
    @PostMapping(value = "/benchmark", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> benchmarkVlm(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "iterations", defaultValue = "1") int iterations,
            @RequestParam(value = "maxTokens", defaultValue = "4096") int maxTokens,
            @RequestParam(value = "modelId", required = false) String modelId) {

        if (ocrPipelineService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "OcrPipelineService not available"));
        }

        iterations = Math.max(1, Math.min(iterations, 10));
        maxTokens = Math.max(1, Math.min(maxTokens, 8192));

        try {
            // Save uploaded file to temp
            File tempFile = File.createTempFile("vlm_benchmark_", "_" + file.getOriginalFilename());
            tempFile.deleteOnExit();
            file.transferTo(tempFile);

            List<Map<String, Object>> iterationResults = new ArrayList<>();
            double totalTps = 0;
            long totalGenMs = 0;
            int totalGenTokens = 0;

            for (int i = 0; i < iterations; i++) {
                // Use OcrPipelineService to process the file with VLM
                String effectiveModelId = modelId != null ? modelId : "smoldocling-256m";
                var results = ocrPipelineService.processPdfWithVlm(tempFile, effectiveModelId);

                if (results != null && !results.isEmpty()) {
                    var result = results.get(0);
                    Map<String, Object> metadata = result.getMetadata();

                    Map<String, Object> iterResult = new LinkedHashMap<>();
                    iterResult.put("iteration", i + 1);
                    iterResult.put("processingTimeMs", result.getProcessingTimeMs());

                    if (metadata != null) {
                        iterResult.put("generatedTokens", metadata.getOrDefault("generatedTokens", 0));
                        iterResult.put("promptTokens", metadata.getOrDefault("promptTokens", 0));
                        iterResult.put("tokensPerSecond", metadata.getOrDefault("tokensPerSecond", 0.0));
                        iterResult.put("generateTimeMs", metadata.getOrDefault("generateTimeMs", 0L));

                        Object tpsObj = metadata.get("tokensPerSecond");
                        if (tpsObj instanceof Number) {
                            totalTps += ((Number) tpsObj).doubleValue();
                        }
                        Object genMsObj = metadata.get("generateTimeMs");
                        if (genMsObj instanceof Number) {
                            totalGenMs += ((Number) genMsObj).longValue();
                        }
                        Object genTokObj = metadata.get("generatedTokens");
                        if (genTokObj instanceof Number) {
                            totalGenTokens += ((Number) genTokObj).intValue();
                        }
                    }

                    iterResult.put("textLength", result.getText() != null ? result.getText().length() : 0);
                    iterResult.put("success", result.isSuccess());
                    iterationResults.add(iterResult);
                }
            }

            // Build summary
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("iterations", iterationResults);
            response.put("summary", Map.of(
                    "totalIterations", iterations,
                    "avgTokensPerSecond", iterations > 0 ? Math.round((totalTps / iterations) * 100.0) / 100.0 : 0,
                    "avgGenerateTimeMs", iterations > 0 ? totalGenMs / iterations : 0,
                    "avgGeneratedTokens", iterations > 0 ? totalGenTokens / iterations : 0,
                    "totalTokensGenerated", totalGenTokens,
                    "modelId", modelId != null ? modelId : "default"
            ));

            // Cleanup
            tempFile.delete();

            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("VLM benchmark failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Benchmark failed: " + e.getMessage()));
        }
    }

    /**
     * Quick benchmark using an already-loaded VLM model with a blank test image.
     * Useful for measuring raw throughput without uploading a file.
     */
    @GetMapping("/benchmark/quick")
    public ResponseEntity<Map<String, Object>> quickBenchmark(
            @RequestParam(value = "maxTokens", defaultValue = "256") int maxTokens) {

        if (ocrPipelineService == null) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("error", "OcrPipelineService not available"));
        }

        try {
            // Create a simple test image (white 224x224)
            BufferedImage testImage = new BufferedImage(224, 224, BufferedImage.TYPE_INT_RGB);
            File tempFile = File.createTempFile("vlm_quick_bench_", ".png");
            tempFile.deleteOnExit();
            ImageIO.write(testImage, "png", tempFile);

            long startTime = System.nanoTime();
            var results = ocrPipelineService.processPdfWithVlm(tempFile, "smoldocling-256m");
            long totalMs = (System.nanoTime() - startTime) / 1_000_000;

            Map<String, Object> response = new LinkedHashMap<>();
            response.put("totalTimeMs", totalMs);

            if (results != null && !results.isEmpty()) {
                var result = results.get(0);
                Map<String, Object> metadata = result.getMetadata();
                if (metadata != null) {
                    response.put("generatedTokens", metadata.getOrDefault("generatedTokens", 0));
                    response.put("tokensPerSecond", metadata.getOrDefault("tokensPerSecond", 0.0));
                    response.put("generateTimeMs", metadata.getOrDefault("generateTimeMs", 0L));
                    response.put("promptTokens", metadata.getOrDefault("promptTokens", 0));
                }
                response.put("textLength", result.getText() != null ? result.getText().length() : 0);
                response.put("success", result.isSuccess());
            }

            tempFile.delete();
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Quick VLM benchmark failed", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Benchmark failed: " + e.getMessage()));
        }
    }

    // ==================== Helper Methods ====================

    private ModelSetInfo toModelSetInfo(VlmModelSet set) {
        ModelSetInfo info = new ModelSetInfo();
        info.setId = set.getSetId();
        info.displayName = set.getDisplayName();
        info.description = set.getDescription();
        info.architecture = set.getArchitecture();
        info.inputSize = set.getInputSize();
        info.hiddenSize = set.getHiddenSize();
        info.cached = VlmModels.isCached(set.getSetId());

        List<ComponentInfo> components = new ArrayList<>();
        for (VlmModelComponent comp : set.getComponents()) {
            ComponentInfo compInfo = new ComponentInfo();
            compInfo.componentKey = comp.getComponentKey();
            compInfo.fileName = comp.getFileName();
            compInfo.pipelineStage = comp.getPipelineStage() != null ?
                comp.getPipelineStage().name() : null;
            compInfo.inputShape = comp.getInputShape();
            compInfo.outputShape = comp.getOutputShape();
            components.add(compInfo);
        }
        info.components = components;

        return info;
    }

    // ==================== DTO Classes ====================

    public static class ModelSetInfo {
        public String setId;
        public String displayName;
        public String description;
        public String architecture;
        public int inputSize;
        public int hiddenSize;
        public boolean cached;
        public List<ComponentInfo> components;
    }

    public static class ComponentInfo {
        public String componentKey;
        public String fileName;
        public String pipelineStage;
        public String inputShape;
        public String outputShape;
    }

    public static class PipelineStageInfo {
        public String id;
        public String displayName;
        public String inputDescription;
        public String outputDescription;
        public String modelComponentKey;
        public boolean requiresModel;
    }

    public static class ExtractionTypeInfo {
        public String id;
        public String description;
        public String defaultModelSetId;
        public String defaultModelSetName;
    }

    public static class PresetInfo {
        public String id;
        public String name;
        public String description;
        public List<String> enabledExtractions;
    }

    private static class DownloadProgress {
        final String setId;
        final String displayName;
        volatile boolean complete = false;
        volatile boolean success = false;
        volatile String currentComponent = "";
        volatile double componentProgress = 0;
        volatile int componentsCompleted = 0;
        volatile String message = "Starting download...";

        DownloadProgress(String setId, String displayName) {
            this.setId = setId;
            this.displayName = displayName;
        }
    }
}

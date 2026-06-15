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

package ai.kompile.staging.web;

import ai.kompile.staging.execution.VlmExecutionService;
import ai.kompile.staging.execution.VlmImageEmbeddingService;
import ai.kompile.staging.web.dto.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.*;

/**
 * REST API controller for VLM execution - image generation, OCR, and DocTags parsing.
 * Complements VlmModelController which handles model set management.
 */
@ConditionalOnClass(name = "ai.kompile.staging.catalog.CatalogService")
@ConditionalOnProperty(name = "kompile.staging.app.enabled", havingValue = "true")
@RestController
@RequestMapping("/api/vlm")
@CrossOrigin(origins = "*")
public class VlmExecutionController {

    private static final Logger log = LoggerFactory.getLogger(VlmExecutionController.class);

    private final VlmExecutionService executionService;
    private final ObjectMapper objectMapper;

    @Autowired
    private VlmImageEmbeddingService embeddingService;

    @Autowired
    public VlmExecutionController(VlmExecutionService executionService) {
        this.executionService = executionService;
        this.objectMapper = new ObjectMapper();
    }

    // ==================== Model Management ====================

    /**
     * List available VLM models for execution.
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, String>>> getModels() {
        return ResponseEntity.ok(executionService.getAvailableModels());
    }

    /**
     * Load a VLM model for execution.
     */
    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> loadModel(@RequestBody Map<String, String> request) {
        String modelSetId = request.get("modelSetId");
        if (modelSetId == null || modelSetId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "success", false,
                "error", "modelSetId is required"
            ));
        }
        return ResponseEntity.ok(executionService.loadModel(modelSetId));
    }

    /**
     * Unload the current VLM model.
     */
    @DeleteMapping("/unload")
    public ResponseEntity<Map<String, Object>> unloadModel() {
        return ResponseEntity.ok(executionService.unloadModel());
    }

    /**
     * Get model execution status.
     */
    @GetMapping("/execution/status")
    public ResponseEntity<Map<String, Object>> getExecutionStatus() {
        return ResponseEntity.ok(executionService.getModelStatus());
    }

    // ==================== Generation ====================

    /**
     * Generate text from an image using the loaded VLM model.
     * Accepts multipart/form-data with image file and generation parameters.
     */
    @PostMapping(value = "/generate", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<VlmGenerateResponse> generate(
            @RequestPart("image") MultipartFile image,
            @RequestPart(value = "request", required = false) String requestJson) {

        try {
            VlmGenerateRequest request;
            if (requestJson != null && !requestJson.isBlank()) {
                request = objectMapper.readValue(requestJson, VlmGenerateRequest.class);
            } else {
                request = VlmGenerateRequest.builder().build();
            }

            byte[] imageData = image.getBytes();
            VlmGenerateResponse response = executionService.generate(imageData, request);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Generation failed", e);
            return ResponseEntity.ok(VlmGenerateResponse.builder()
                .success(false)
                .error("Generation failed: " + e.getMessage())
                .build());
        }
    }

    /**
     * Batch generate text from multiple images.
     */
    @PostMapping(value = "/generate/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<VlmGenerateResponse>> generateBatch(
            @RequestPart("images") MultipartFile[] images,
            @RequestPart(value = "request", required = false) String requestJson) {

        try {
            VlmGenerateRequest request;
            if (requestJson != null && !requestJson.isBlank()) {
                request = objectMapper.readValue(requestJson, VlmGenerateRequest.class);
            } else {
                request = VlmGenerateRequest.builder().build();
            }

            List<byte[]> imageDataList = new ArrayList<>();
            for (MultipartFile img : images) {
                imageDataList.add(img.getBytes());
            }

            List<VlmGenerateResponse> responses = executionService.generateBatch(imageDataList, request);
            return ResponseEntity.ok(responses);

        } catch (Exception e) {
            log.error("Batch generation failed", e);
            return ResponseEntity.ok(List.of(VlmGenerateResponse.builder()
                .success(false)
                .error("Batch generation failed: " + e.getMessage())
                .build()));
        }
    }

    // ==================== OCR ====================

    /**
     * Perform OCR on an uploaded image.
     */
    @PostMapping(value = "/ocr/recognize", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<OcrRecognizeResponse> ocrRecognize(
            @RequestPart("image") MultipartFile image) {

        try {
            byte[] imageData = image.getBytes();
            OcrRecognizeResponse response = executionService.recognizeText(imageData);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("OCR recognition failed", e);
            return ResponseEntity.ok(OcrRecognizeResponse.builder()
                .success(false)
                .error("OCR recognition failed: " + e.getMessage())
                .build());
        }
    }

    /**
     * Batch OCR on multiple images.
     */
    @PostMapping(value = "/ocr/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<List<OcrRecognizeResponse>> ocrBatch(
            @RequestPart("images") MultipartFile[] images) {

        try {
            List<byte[]> imageDataList = new ArrayList<>();
            for (MultipartFile img : images) {
                imageDataList.add(img.getBytes());
            }

            List<OcrRecognizeResponse> responses = executionService.recognizeBatch(imageDataList);
            return ResponseEntity.ok(responses);

        } catch (Exception e) {
            log.error("Batch OCR failed", e);
            return ResponseEntity.ok(List.of(OcrRecognizeResponse.builder()
                .success(false)
                .error("Batch OCR failed: " + e.getMessage())
                .build()));
        }
    }

    /**
     * Get available OCR engines.
     */
    @GetMapping("/ocr/engines")
    public ResponseEntity<List<Map<String, String>>> getOcrEngines() {
        return ResponseEntity.ok(executionService.getAvailableOcrEngines());
    }

    /**
     * Update OCR configuration.
     */
    @PutMapping("/ocr/config")
    public ResponseEntity<Map<String, Object>> updateOcrConfig(@RequestBody OcrConfigRequest config) {
        return ResponseEntity.ok(executionService.updateOcrConfig(config));
    }

    /**
     * Get current OCR configuration.
     */
    @GetMapping("/ocr/config")
    public ResponseEntity<Map<String, Object>> getOcrConfig() {
        return ResponseEntity.ok(executionService.getOcrConfig());
    }

    // ==================== DocTags Parsing ====================

    /**
     * Parse DocTags output to structured document.
     */
    @PostMapping("/doctags/parse")
    public ResponseEntity<DocTagsParseResponse> parseDocTags(@RequestBody DocTagsParseRequest request) {
        DocTagsParseResponse response = executionService.parseDocTags(request.getRawDocTags());
        return ResponseEntity.ok(response);
    }

    /**
     * Convert DocTags to markdown.
     */
    @PostMapping("/doctags/to-markdown")
    public ResponseEntity<Map<String, Object>> docTagsToMarkdown(@RequestBody DocTagsParseRequest request) {
        String markdown = executionService.docTagsToMarkdown(request.getRawDocTags());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("markdown", markdown);
        return ResponseEntity.ok(response);
    }

    /**
     * Convert DocTags to HTML.
     */
    @PostMapping("/doctags/to-html")
    public ResponseEntity<Map<String, Object>> docTagsToHtml(@RequestBody DocTagsParseRequest request) {
        String html = executionService.docTagsToHtml(request.getRawDocTags());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("success", true);
        response.put("html", html);
        return ResponseEntity.ok(response);
    }

    // ==================== Image Embedding ====================

    /**
     * Embed a single image using the loaded VLM's vision encoder.
     * Returns the vision encoder output as a float array.
     */
    @PostMapping(value = "/embed", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> embedImage(
            @RequestPart("image") MultipartFile image) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            byte[] imageData = image.getBytes();
            float[] embedding = embeddingService.embedImage(imageData);
            response.put("success", true);
            response.put("embedding", embedding);
            response.put("dimensions", embedding.length);
            log.debug("VLM image embedding produced: {} dimensions", embedding.length);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Image embedding failed", e);
            response.put("success", false);
            response.put("error", "Image embedding failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Embed multiple images in batch using the loaded VLM's vision encoder.
     */
    @PostMapping(value = "/embed/batch", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Map<String, Object>> embedImageBatch(
            @RequestPart("images") MultipartFile[] images) {

        Map<String, Object> response = new LinkedHashMap<>();
        try {
            List<byte[]> imageDataList = new ArrayList<>();
            for (MultipartFile img : images) {
                imageDataList.add(img.getBytes());
            }

            List<float[]> embeddings = embeddingService.embedImageBatch(imageDataList);
            response.put("success", true);
            response.put("embeddings", embeddings);
            response.put("count", embeddings.size());
            if (!embeddings.isEmpty()) {
                response.put("dimensions", embeddings.get(0).length);
            }
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            log.error("Batch image embedding failed", e);
            response.put("success", false);
            response.put("error", "Batch image embedding failed: " + e.getMessage());
            return ResponseEntity.ok(response);
        }
    }

    /**
     * Get image embedding info (model ID, dimensions, loaded status).
     */
    @GetMapping("/embed/info")
    public ResponseEntity<Map<String, Object>> getEmbeddingInfo() {
        return ResponseEntity.ok(embeddingService.getEmbeddingInfo());
    }

    // ==================== Tiling Preview ====================

    /**
     * Preview image tiling settings.
     */
    @PostMapping(value = "/tiling/preview", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<TilingPreviewResponse> previewTiling(
            @RequestPart("image") MultipartFile image,
            @RequestParam(value = "maxTiles", defaultValue = "4") int maxTiles) {

        try {
            byte[] imageData = image.getBytes();
            TilingPreviewResponse response = executionService.previewTiling(imageData, maxTiles);
            return ResponseEntity.ok(response);

        } catch (Exception e) {
            log.error("Tiling preview failed", e);
            return ResponseEntity.ok(TilingPreviewResponse.builder()
                .success(false)
                .error("Tiling preview failed: " + e.getMessage())
                .build());
        }
    }
}

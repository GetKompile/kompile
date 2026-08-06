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

package ai.kompile.ocr.integration;

import ai.kompile.cli.common.routing.ServiceEndpointsConfigManager;
import ai.kompile.modelmanager.registry.ModelType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.rendering.PDFRenderer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.multipart.MultipartFile;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.*;

/**
 * REST controller for OCR debugging and testing.
 * Provides a transparent layer over the model staging VLM execution service
 * and the model registry. Calls the staging service via HTTP.
 */
@RestController
@RequestMapping("/api/ocr/debug")
public class OcrDebugController {

    private static final Logger logger = LoggerFactory.getLogger(OcrDebugController.class);

    private static final Set<ModelType> OCR_VLM_TYPES = Set.of(
            ModelType.OCR_DETECTION, ModelType.OCR_RECOGNITION, ModelType.OCR_TABLE,
            ModelType.LAYOUT_MODEL, ModelType.OCR_PIPELINE, ModelType.VLM_PIPELINE
    );

    private final RestTemplate restTemplate;
    private String stagingBaseUrlOverride;

    @Autowired
    public OcrDebugController(
            @Autowired(required = false) RestTemplate restTemplate) {
        this.restTemplate = restTemplate != null ? restTemplate : new RestTemplate();
    }

    /**
     * Sets the staging service base URL. Can be called at runtime.
     */
    public void setStagingBaseUrl(String url) {
        this.stagingBaseUrlOverride = url;
    }

    // ---- Status ----

    /**
     * Returns real status: VLM loaded state, model count from registry, staging reachability.
     */
    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> getStatus() {
        Map<String, Object> status = new LinkedHashMap<>();

        // Registry model count
        List<Map<String, Object>> ocrModels = getOcrVlmModels();
        status.put("registryModelCount", ocrModels.size());

        // Try to get VLM status from staging
        Map<String, Object> vlmStatus = callStagingGet("/api/vlm/execution/status");
        if (vlmStatus != null) {
            status.put("stagingReachable", true);
            status.put("vlmLoaded", vlmStatus.getOrDefault("modelReady", false));
            status.put("vlmModelId", vlmStatus.getOrDefault("activeModelId", null));
            status.put("vlmModelStatus", vlmStatus.getOrDefault("modelStatus", "unknown"));
        } else {
            status.put("stagingReachable", false);
            status.put("vlmLoaded", false);
            status.put("vlmModelId", null);
            status.put("vlmModelStatus", "staging_unreachable");
        }

        status.put("stagingUrl", stagingBase());

        return ResponseEntity.ok(status);
    }

    // ---- Models (from registry) ----

    /**
     * Lists models from the registry filtered to OCR/VLM types.
     */
    @GetMapping("/models")
    public ResponseEntity<List<Map<String, Object>>> listModels() {
        return ResponseEntity.ok(getOcrVlmModels());
    }

    // ---- VLM Load/Unload (delegated to staging via HTTP) ----

    /**
     * Loads a VLM model via the staging service.
     */
    @PostMapping("/load-vlm")
    public ResponseEntity<Map<String, Object>> loadVlm(@RequestBody Map<String, String> request) {
        String modelId = request.get("modelId");
        if (modelId == null || modelId.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of("success", false, "error", "modelId required"));
        }

        try {
            Map<String, Object> body = Map.of("modelSetId", modelId);
            Map<String, Object> response = callStagingPost("/api/vlm/load", body);
            if (response != null) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(502).body(Map.of(
                        "success", false,
                        "error", "Staging service unreachable at " + stagingBase()));
            }
        } catch (Exception e) {
            logger.error("Failed to load VLM model {}: {}", modelId, e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "error", e.getMessage()));
        }
    }

    /**
     * Unloads the current VLM model via the staging service.
     */
    @PostMapping("/unload-vlm")
    public ResponseEntity<Map<String, Object>> unloadVlm() {
        try {
            Map<String, Object> response = callStagingDelete("/api/vlm/unload");
            if (response != null) {
                return ResponseEntity.ok(response);
            } else {
                return ResponseEntity.status(502).body(Map.of(
                        "success", false,
                        "error", "Staging service unreachable at " + stagingBase()));
            }
        } catch (Exception e) {
            logger.error("Failed to unload VLM: {}", e.getMessage());
            return ResponseEntity.internalServerError().body(Map.of(
                    "success", false, "error", e.getMessage()));
        }
    }

    /**
     * Gets current VLM load status from staging.
     */
    @GetMapping("/vlm-status")
    public ResponseEntity<Map<String, Object>> getVlmStatus() {
        Map<String, Object> status = callStagingGet("/api/vlm/execution/status");
        if (status != null) {
            return ResponseEntity.ok(status);
        }
        return ResponseEntity.status(502).body(Map.of(
                "reachable", false,
                "error", "Staging service unreachable at " + stagingBase()));
    }

    // ---- Test OCR (delegates to staging VLM generate) ----

    /**
     * Processes a file through the staging VLM service.
     * Returns a transparent execution trace with timing, token counts, and raw/parsed output.
     */
    @PostMapping("/test")
    public ResponseEntity<Map<String, Object>> testOcr(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "modelId", required = false) String modelId,
            @RequestParam(value = "maxTokens", defaultValue = "4096") int maxTokens,
            @RequestParam(value = "outputFormat", defaultValue = "DOCTAGS") String outputFormat,
            @RequestParam(value = "page", required = false) Integer page) {

        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, Object>> traceSteps = new ArrayList<>();
        long totalStart = System.currentTimeMillis();

        try {
            result.put("fileName", file.getOriginalFilename());
            result.put("fileSize", file.getSize());

            // Step 1: Save file
            long stepStart = System.currentTimeMillis();
            File tempFile = Files.createTempFile("ocr_test_", "_" + file.getOriginalFilename()).toFile();
            tempFile.deleteOnExit();
            file.transferTo(tempFile);
            traceSteps.add(traceStep("file_save", System.currentTimeMillis() - stepStart,
                    "Saved upload to temp file"));

            // Step 2: Extract image from PDF (if PDF)
            stepStart = System.currentTimeMillis();
            byte[] imageBytes;
            String filename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "unknown";
            boolean isPdf = filename.toLowerCase().endsWith(".pdf");

            if (isPdf) {
                int targetPage = page != null ? page : 1;
                try (PDDocument document = Loader.loadPDF(tempFile)) {
                    int totalPages = document.getNumberOfPages();
                    result.put("totalPages", totalPages);

                    if (targetPage < 1 || targetPage > totalPages) {
                        result.put("success", false);
                        result.put("error", "Page " + targetPage + " out of range (1-" + totalPages + ")");
                        return ResponseEntity.badRequest().body(result);
                    }

                    PDFRenderer renderer = new PDFRenderer(document);
                    BufferedImage pageImage = renderer.renderImageWithDPI(targetPage - 1, 300);
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    ImageIO.write(pageImage, "PNG", baos);
                    imageBytes = baos.toByteArray();
                    result.put("pageProcessed", targetPage);
                }
                traceSteps.add(traceStep("pdf_render", System.currentTimeMillis() - stepStart,
                        "Rendered PDF page " + (page != null ? page : 1) + " at 300 DPI"));
            } else {
                imageBytes = Files.readAllBytes(tempFile.toPath());
                result.put("totalPages", 1);
                result.put("pageProcessed", 1);
                traceSteps.add(traceStep("image_load", System.currentTimeMillis() - stepStart,
                        "Loaded image file (" + imageBytes.length + " bytes)"));
            }

            // Step 3: Call staging VLM generate
            stepStart = System.currentTimeMillis();
            Map<String, Object> vlmResponse = callStagingVlmGenerate(imageBytes, filename,
                    modelId, maxTokens);
            long generateTime = System.currentTimeMillis() - stepStart;

            if (vlmResponse == null) {
                traceSteps.add(traceStep("vlm_generate", generateTime,
                        "FAILED: Staging service unreachable at " + stagingBase()));
                result.put("success", false);
                result.put("error", "Staging service unreachable at " + stagingBase());
                result.put("steps", traceSteps);
                result.put("totalTimeMs", System.currentTimeMillis() - totalStart);
                tempFile.delete();
                return ResponseEntity.status(502).body(result);
            }

            boolean vlmSuccess = Boolean.TRUE.equals(vlmResponse.get("success"));
            String rawOutput = (String) vlmResponse.get("generatedText");

            // Extract metrics if available
            int tokensGenerated = 0;
            double tokensPerSecond = 0;
            @SuppressWarnings("unchecked")
            Map<String, Object> metrics = (Map<String, Object>) vlmResponse.get("metrics");
            if (metrics != null) {
                tokensGenerated = metrics.get("tokensGenerated") != null ?
                        ((Number) metrics.get("tokensGenerated")).intValue() : 0;
                if (tokensGenerated > 0 && generateTime > 0) {
                    tokensPerSecond = tokensGenerated * 1000.0 / generateTime;
                }
            }

            String generateDetail = vlmSuccess ?
                    String.format("Generated %d tokens (%.1f tok/s)", tokensGenerated, tokensPerSecond) :
                    "FAILED: " + vlmResponse.getOrDefault("error", "unknown error");
            traceSteps.add(traceStep("vlm_generate", generateTime, generateDetail));

            // Step 4: Parse output (if doctags)
            String parsedOutput = rawOutput;
            if (vlmSuccess && rawOutput != null && "DOCTAGS".equalsIgnoreCase(outputFormat)) {
                stepStart = System.currentTimeMillis();
                Map<String, Object> parseResponse = callStagingDoctagsParse(rawOutput, "markdown");
                if (parseResponse != null && parseResponse.get("markdown") != null) {
                    parsedOutput = (String) parseResponse.get("markdown");
                    traceSteps.add(traceStep("doctags_parse", System.currentTimeMillis() - stepStart,
                            "Parsed DocTags to markdown"));
                } else {
                    traceSteps.add(traceStep("doctags_parse", System.currentTimeMillis() - stepStart,
                            "DocTags parsing skipped or failed"));
                }
            }

            // Build result
            result.put("success", vlmSuccess);
            result.put("modelId", modelId != null ? modelId : "default");
            result.put("steps", traceSteps);
            result.put("rawOutput", rawOutput);
            result.put("parsedOutput", parsedOutput);
            result.put("outputFormat", outputFormat);
            result.put("tokensGenerated", tokensGenerated);
            result.put("tokensPerSecond", Math.round(tokensPerSecond * 10.0) / 10.0);
            result.put("totalTimeMs", System.currentTimeMillis() - totalStart);

            if (!vlmSuccess) {
                result.put("error", vlmResponse.getOrDefault("error", "VLM generation failed"));
            }

            tempFile.delete();
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("OCR test failed: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            result.put("steps", traceSteps);
            result.put("totalTimeMs", System.currentTimeMillis() - totalStart);
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ---- PDF Page Rendering (kept for comparison view) ----

    /**
     * Renders a PDF page as base64 PNG for side-by-side comparison.
     */
    @PostMapping("/render-page")
    public ResponseEntity<Map<String, Object>> renderPdfPage(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "page", defaultValue = "1") int page,
            @RequestParam(value = "dpi", defaultValue = "150") int dpi) {

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("page", page);

        try {
            String filename = file.getOriginalFilename();
            if (filename == null) filename = "unknown";

            boolean isPdf = filename.toLowerCase().endsWith(".pdf");
            File tempFile = Files.createTempFile("pdf_render_", "_" + filename).toFile();
            tempFile.deleteOnExit();
            file.transferTo(tempFile);

            BufferedImage pageImage;
            if (isPdf) {
                try (PDDocument document = Loader.loadPDF(tempFile)) {
                    int totalPages = document.getNumberOfPages();
                    result.put("totalPages", totalPages);

                    if (page < 1 || page > totalPages) {
                        result.put("success", false);
                        result.put("error", "Page " + page + " out of range (1-" + totalPages + ")");
                        return ResponseEntity.badRequest().body(result);
                    }

                    PDFRenderer renderer = new PDFRenderer(document);
                    pageImage = renderer.renderImageWithDPI(page - 1, dpi);
                }
            } else {
                pageImage = ImageIO.read(tempFile);
                result.put("totalPages", 1);
            }

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            ImageIO.write(pageImage, "PNG", baos);
            String base64Image = Base64.getEncoder().encodeToString(baos.toByteArray());

            result.put("success", true);
            result.put("imageData", base64Image);
            result.put("width", pageImage.getWidth());
            result.put("height", pageImage.getHeight());
            result.put("format", "PNG");
            result.put("dpi", dpi);

            tempFile.delete();
            return ResponseEntity.ok(result);

        } catch (Exception e) {
            logger.error("Failed to render PDF page: {}", e.getMessage(), e);
            result.put("success", false);
            result.put("error", e.getMessage());
            return ResponseEntity.internalServerError().body(result);
        }
    }

    // ---- Private helpers ----

    private List<Map<String, Object>> getOcrVlmModels() {
        Map<String, Object> registry = callStagingGet("/api/staging/registry");
        if (registry == null || !(registry.get("models") instanceof Map<?, ?> models)) {
            return List.of();
        }

        List<Map<String, Object>> result = new ArrayList<>();
        for (Map.Entry<?, ?> entry : models.entrySet()) {
            if (!(entry.getValue() instanceof Map<?, ?> model)) {
                continue;
            }
            ModelType type = ModelType.fromValue(stringValue(model.get("type")));
            if (!OCR_VLM_TYPES.contains(type)) {
                continue;
            }

            Map<String, Object> response = new LinkedHashMap<>();
            String modelId = stringValue(model.get("model_id"));
            response.put("modelId", modelId != null ? modelId : String.valueOf(entry.getKey()));
            response.put("type", type.name());
            String status = stringValue(model.get("status"));
            response.put("status", status != null ? status.toUpperCase(Locale.ROOT) : null);
            response.put("path", model.get("path"));
            if (model.get("metadata") instanceof Map<?, ?> metadata) {
                response.put("description", metadata.get("description"));
                response.put("framework", metadata.get("framework"));
            }
            result.add(response);
        }
        return result;
    }

    private static String stringValue(Object value) {
        return value != null ? String.valueOf(value) : null;
    }

    private Map<String, Object> traceStep(String name, long durationMs, String detail) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("name", name);
        step.put("durationMs", durationMs);
        step.put("detail", detail);
        return step;
    }

    // ---- HTTP calls to staging service ----

    private String stagingBase() {
        String configured = stagingBaseUrlOverride;
        if (configured == null || configured.isBlank()) {
            configured = ServiceEndpointsConfigManager.shared().current().effectiveStagingUrl();
        }
        return configured.trim().replaceAll("/+$", "");
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStagingGet(String path) {
        try {
            ResponseEntity<Map> response = restTemplate.getForEntity(
                    stagingBase() + path, Map.class);
            return response.getBody();
        } catch (Exception e) {
            logger.debug("Staging GET {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStagingPost(String path, Object body) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            HttpEntity<Object> entity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(
                    stagingBase() + path, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            logger.debug("Staging POST {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStagingDelete(String path) {
        try {
            HttpHeaders headers = new HttpHeaders();
            HttpEntity<Void> entity = new HttpEntity<>(headers);
            ResponseEntity<Map> response = restTemplate.exchange(
                    stagingBase() + path, HttpMethod.DELETE, entity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            logger.debug("Staging DELETE {} failed: {}", path, e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStagingVlmGenerate(byte[] imageBytes, String filename,
                                                        String modelId, int maxTokens) {
        try {
            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.MULTIPART_FORM_DATA);

            MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
            ByteArrayResource imageResource = new ByteArrayResource(imageBytes) {
                @Override
                public String getFilename() {
                    return filename;
                }
            };
            body.add("file", new HttpEntity<>(imageResource, createFileHeaders(filename)));

            // Build query params
            StringBuilder url = new StringBuilder(stagingBase() + "/api/vlm/generate");
            url.append("?maxTokens=").append(maxTokens);
            if (modelId != null && !modelId.isBlank()) {
                url.append("&modelSetId=").append(modelId);
            }

            HttpEntity<MultiValueMap<String, Object>> requestEntity = new HttpEntity<>(body, headers);
            ResponseEntity<Map> response = restTemplate.postForEntity(url.toString(), requestEntity, Map.class);
            return response.getBody();
        } catch (Exception e) {
            logger.debug("Staging VLM generate failed: {}", e.getMessage());
            return null;
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> callStagingDoctagsParse(String doctagsText, String format) {
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("doctagsText", doctagsText);
            body.put("outputFormat", format);
            return callStagingPost("/api/vlm/doctags/parse", body);
        } catch (Exception e) {
            logger.debug("Staging doctags parse failed: {}", e.getMessage());
            return null;
        }
    }

    private HttpHeaders createFileHeaders(String filename) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_OCTET_STREAM);
        headers.setContentDispositionFormData("file", filename);
        return headers;
    }
}

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
package ai.kompile.app.llm.pipeline;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.core.io.Resource;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * On-demand LLM load/unload REST endpoints.
 *
 * <p>Resolves the SameDiff model + tokenizer for a given {@code modelId} from the
 * kompile-model-staging registry HTTP API, downloads the binary files into a local cache
 * directory, then asks {@link SameDiffLanguageModelImpl} to swap in a fresh runner.</p>
 *
 * <ul>
 *     <li>{@code POST /api/llm/load} — body: {@code { "modelId": "...", "stagingUrl": "..." (optional), "options": {...} (optional) }}</li>
 *     <li>{@code GET  /api/llm/status}</li>
 *     <li>{@code POST /api/llm/unload}</li>
 * </ul>
 */
@RestController
@RequestMapping("/api/llm")
@CrossOrigin(origins = "*")
public class LlmModelController {

    private static final Logger logger = LoggerFactory.getLogger(LlmModelController.class);

    private final SameDiffLanguageModelImpl languageModel;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final String defaultStagingUrl;
    private final Path cacheDir;

    @Autowired
    public LlmModelController(
            SameDiffLanguageModelImpl languageModel,
            RestTemplateBuilder restTemplateBuilder,
            ObjectMapper objectMapper,
            @Value("${kompile.staging.url:http://localhost:8090}") String defaultStagingUrl,
            @Value("${kompile.llm.cache.dir:${user.home}/.kompile/llm-cache}") String cacheDir) {
        this.languageModel = languageModel;
        this.restTemplate = restTemplateBuilder
                .setConnectTimeout(Duration.ofSeconds(30))
                .setReadTimeout(Duration.ofMinutes(10))
                .build();
        this.objectMapper = objectMapper;
        this.defaultStagingUrl = defaultStagingUrl;
        this.cacheDir = Paths.get(cacheDir);
    }

    @PostMapping("/load")
    public ResponseEntity<Map<String, Object>> load(@RequestBody LoadRequest request) {
        if (request == null || request.getModelId() == null || request.getModelId().isBlank()) {
            return ResponseEntity.badRequest().body(error("modelId is required"));
        }
        String modelId = request.getModelId().trim();
        String stagingUrl = (request.getStagingUrl() != null && !request.getStagingUrl().isBlank())
                ? request.getStagingUrl().trim()
                : defaultStagingUrl;
        if (stagingUrl == null || stagingUrl.isBlank()) {
            return ResponseEntity.badRequest().body(error("stagingUrl not configured (kompile.staging.url) and not provided in request"));
        }
        String baseUrl = stagingUrl.endsWith("/") ? stagingUrl.substring(0, stagingUrl.length() - 1) : stagingUrl;

        long startMs = System.currentTimeMillis();
        try {
            // 1) Resolve registry entry to figure out filenames
            String registryUrl = baseUrl + "/api/staging/registry/model/" + modelId;
            logger.info("LLM load: GET {}", registryUrl);
            String registryJson = restTemplate.getForObject(registryUrl, String.class);
            if (registryJson == null || registryJson.isBlank()) {
                return ResponseEntity.status(404).body(error("Empty response from staging registry for modelId=" + modelId));
            }
            JsonNode entry = objectMapper.readTree(registryJson);
            String modelFileName = textOr(entry.get("model_file"), "model.sdz");
            String vocabFileName = textOr(entry.get("vocab_file"), "tokenizer.json");

            // 2) Prepare local cache directory
            Files.createDirectories(cacheDir);
            Path modelDir = cacheDir.resolve(modelId);
            Files.createDirectories(modelDir);

            // 3) List all files from the staging registry and download them.
            //    This handles sharded models (model.shard0-of-N.sdnb, etc.) and tokenizers.
            String filesUrl = baseUrl + "/api/staging/registry/model/" + modelId + "/files";
            logger.info("LLM load: listing files from {}", filesUrl);
            String filesJson = restTemplate.getForObject(filesUrl, String.class);

            List<String> downloadedFiles = new ArrayList<>();
            if (filesJson != null && !filesJson.isBlank()) {
                JsonNode filesNode = objectMapper.readTree(filesJson);
                JsonNode fileList = filesNode.get("files");
                if (fileList != null && fileList.isArray()) {
                    for (JsonNode fileEntry : fileList) {
                        String fileName = fileEntry.get("name").asText();
                        Path localFile = modelDir.resolve(fileName);
                        long remoteSize = fileEntry.has("size") ? fileEntry.get("size").asLong() : -1;

                        if (Files.exists(localFile) && Files.size(localFile) > 0
                                && (remoteSize < 0 || Files.size(localFile) == remoteSize)) {
                            logger.info("LLM load: reusing cached file {}", localFile);
                        } else {
                            String fileDownloadUrl = baseUrl + "/api/staging/registry/model/" + modelId + "/download/file/" + fileName;
                            logger.info("LLM load: downloading {} -> {}", fileDownloadUrl, localFile);
                            downloadTo(fileDownloadUrl, localFile);
                        }
                        downloadedFiles.add(fileName);
                    }
                }
            }

            // If the /files endpoint wasn't available (older staging server), fall back to
            // downloading model and vocab individually via the legacy endpoints.
            if (downloadedFiles.isEmpty()) {
                logger.info("LLM load: /files endpoint returned no results, falling back to legacy download");
                Path modelPath = modelDir.resolve(modelFileName);
                Path vocabPath = modelDir.resolve(vocabFileName);
                String modelDownloadUrl = baseUrl + "/api/staging/registry/model/" + modelId + "/download/model";
                String vocabDownloadUrl = baseUrl + "/api/staging/registry/model/" + modelId + "/download/vocab";
                if (!Files.exists(modelPath) || Files.size(modelPath) == 0) {
                    downloadTo(modelDownloadUrl, modelPath);
                }
                if (!Files.exists(vocabPath) || Files.size(vocabPath) == 0) {
                    try {
                        downloadTo(vocabDownloadUrl, vocabPath);
                    } catch (Exception e) {
                        logger.warn("LLM load: vocab download failed (may not be available): {}", e.getMessage());
                    }
                }
            }

            // 4) Resolve the model base file and tokenizer file paths.
            //    For sharded models, modelFileName is the logical base (e.g. "model.sdz")
            //    which SameDiff.load() uses to discover shard files in the same directory.
            Path modelPath = modelDir.resolve(modelFileName);
            Path vocabPath = modelDir.resolve(vocabFileName);

            if (!Files.exists(vocabPath)) {
                Path altVocab = modelDir.resolve("tokenizer.json");
                if (Files.exists(altVocab)) vocabPath = altVocab;
            }

            // 5) Hand off to the language model bean
            Map<String, Object> options = request.getOptions() != null ? request.getOptions() : new LinkedHashMap<>();
            languageModel.loadModel(modelId, modelPath, vocabPath, options);

            long durationMs = System.currentTimeMillis() - startMs;
            Map<String, Object> response = new LinkedHashMap<>();
            response.put("loaded", true);
            response.put("modelId", modelId);
            response.put("modelFile", modelPath.toString());
            response.put("tokenizerFile", vocabPath.toString());
            response.put("filesDownloaded", downloadedFiles.size());
            response.put("durationMs", durationMs);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            logger.error("LLM load failed for modelId='{}'", modelId, e);
            Map<String, Object> response = error("Failed to load model: " + e.getMessage());
            response.put("modelId", modelId);
            return ResponseEntity.status(500).body(response);
        }
    }

    @GetMapping("/status")
    public ResponseEntity<Map<String, Object>> status() {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("loaded", languageModel.isLoaded());
        response.put("modelId", languageModel.getLoadedModelId());
        response.put("loadDurationMs", languageModel.getLoadDurationMs());
        response.put("stagingUrl", defaultStagingUrl);
        response.put("cacheDir", cacheDir.toString());
        return ResponseEntity.ok(response);
    }

    @PostMapping("/unload")
    public ResponseEntity<Map<String, Object>> unload() {
        String previous = languageModel.getLoadedModelId();
        languageModel.unloadModel();
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("unloaded", true);
        response.put("previousModelId", previous);
        return ResponseEntity.ok(response);
    }

    // ==================== helpers ====================

    private void downloadTo(String url, Path destination) throws Exception {
        ResponseEntity<Resource> response = restTemplate.getForEntity(url, Resource.class);
        if (response.getBody() == null) {
            throw new IllegalStateException("Empty body from " + url);
        }
        try (InputStream in = response.getBody().getInputStream()) {
            Files.copy(in, destination, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String textOr(JsonNode node, String defaultValue) {
        return (node != null && !node.isNull() && node.isTextual()) ? node.asText() : defaultValue;
    }

    private static Map<String, Object> error(String message) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("loaded", false);
        map.put("error", message);
        return map;
    }

    /** Request body for {@code POST /api/llm/load}. */
    public static class LoadRequest {
        private String modelId;
        private String stagingUrl;
        private Map<String, Object> options;

        public String getModelId() { return modelId; }
        public void setModelId(String modelId) { this.modelId = modelId; }
        public String getStagingUrl() { return stagingUrl; }
        public void setStagingUrl(String stagingUrl) { this.stagingUrl = stagingUrl; }
        public Map<String, Object> getOptions() { return options; }
        public void setOptions(Map<String, Object> options) { this.options = options; }
    }
}

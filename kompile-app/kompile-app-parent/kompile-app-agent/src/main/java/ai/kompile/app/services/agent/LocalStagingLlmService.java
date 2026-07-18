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

package ai.kompile.app.services.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Discovers project/global staged local LLMs and executes them through the staging server's
 * {@code /api/llm} endpoint. This intentionally behaves like a pseudo-agent so crawl fallback can
 * route to local models without registering them as shell CLI agents.
 */
@Service
public class LocalStagingLlmService {

    public static final String AGENT_NAME = "local-staging";
    public static final String MODEL_PREFIX = "local/";

    private static final Logger log = LoggerFactory.getLogger(LocalStagingLlmService.class);
    private static final String REGISTRY_FILENAME = "registry.json";
    private static final String LLM_GGML_TYPE = "llm_ggml";
    private static final Duration DISCOVERY_CACHE_TTL = Duration.ofSeconds(30);

    private final ObjectMapper objectMapper = JsonUtils.standardMapper();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    @Value("${kompile.staging.url:http://localhost:8090}")
    private String stagingUrl;

    private volatile List<LocalModelCandidate> cachedCandidates = List.of();
    private volatile long cacheExpiresAtMs = 0L;

    public record LocalModelCandidate(String modelId,
                                      String displayModelId,
                                      Path modelPath,
                                      int contextWindow,
                                      long estimatedMemoryBytes,
                                      Path registryPath) {
    }

    public boolean isLocalAgent(String agentName) {
        return AGENT_NAME.equals(agentName);
    }

    public List<String> listModelIds(boolean refresh) {
        return discoverCandidates(refresh).stream()
                .map(LocalModelCandidate::displayModelId)
                .toList();
    }

    public String currentModelId() {
        try {
            JsonNode status = getStatus(Duration.ofSeconds(5));
            if (status.path("loaded").asBoolean(false)) {
                String modelId = status.path("modelId").asText("").trim();
                if (!modelId.isEmpty()) {
                    return toDisplayModelId(modelId);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read local staging LLM status: {}", e.getMessage());
        }
        return null;
    }

    /**
     * Context window of the model the staging server currently has loaded, straight
     * from {@code GET /api/llm/status}'s {@code maxContextLength}. This is the live
     * serving truth (post KV-bucketing), preferred over registry metadata when a
     * model is actually loaded. Empty when staging is down, nothing is loaded, or
     * the status does not report a positive length.
     */
    public Optional<Integer> liveMaxContextLength() {
        try {
            JsonNode status = getStatus(Duration.ofSeconds(5));
            if (status.path("loaded").asBoolean(false)) {
                int maxContext = status.path("maxContextLength").asInt(0);
                if (maxContext > 0) {
                    return Optional.of(maxContext);
                }
            }
        } catch (Exception e) {
            log.debug("Could not read local staging LLM context length: {}", e.getMessage());
        }
        return Optional.empty();
    }

    /**
     * Concurrent-generate capacity the staging serving lane reports on {@code /api/llm/status}
     * ({@code maxConcurrentRequests}), or 1 when staging is down, nothing is loaded, or the field
     * is absent — a single loaded model instance serves generation serially.
     */
    public int reportedGenerationConcurrency() {
        try {
            JsonNode status = getStatus(Duration.ofSeconds(5));
            if (status.path("loaded").asBoolean(false)) {
                int concurrency = status.path("maxConcurrentRequests").asInt(0);
                if (concurrency > 0) {
                    return concurrency;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read local staging LLM concurrency: {}", e.getMessage());
        }
        return 1;
    }

    public Optional<LocalModelCandidate> resolveCandidate(String requestedModelId) {
        if (requestedModelId == null || requestedModelId.isBlank()) {
            return discoverCandidates(false).stream().findFirst();
        }
        String bare = bareModelId(requestedModelId);
        return discoverCandidates(false).stream()
                .filter(candidate -> candidate.modelId().equals(requestedModelId)
                        || candidate.modelId().equals(bare)
                        || candidate.displayModelId().equals(requestedModelId))
                .findFirst();
    }

    public String generate(String requestedModelId, String prompt, int timeoutSeconds) throws IOException, InterruptedException {
        LocalModelCandidate candidate = resolveCandidate(requestedModelId)
                .orElseThrow(() -> new IOException("No local staged LLM found for model: " + requestedModelId));
        Duration timeout = Duration.ofSeconds(Math.max(30, timeoutSeconds));
        ensureLoaded(candidate, timeout);

        ObjectNode body = objectMapper.createObjectNode();
        body.put("prompt", prompt == null ? "" : prompt);
        body.put("maxTokens", maxTokens(candidate));
        body.put("temperature", 0.0);
        body.put("topK", 1);
        body.put("topP", 1.0);
        body.put("doSample", false);
        body.put("presetName", "greedy");

        JsonNode response = postJson("/api/llm/generate", body, timeout);
        String finishReason = response.path("finishReason").asText("");
        if (finishReason.toLowerCase(Locale.ROOT).startsWith("error")) {
            throw new IOException("Local staging LLM generation failed: " + finishReason);
        }
        return response.path("generatedText").asText("");
    }

    private List<LocalModelCandidate> discoverCandidates(boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && now < cacheExpiresAtMs) {
            return cachedCandidates;
        }

        Map<String, LocalModelCandidate> byDisplayId = new LinkedHashMap<>();
        for (Path registryPath : registryPaths()) {
            if (!Files.isRegularFile(registryPath)) {
                continue;
            }
            try {
                JsonNode root = objectMapper.readTree(registryPath.toFile());
                JsonNode models = root.path("models");
                if (!models.isObject()) {
                    continue;
                }
                Path baseDir = registryPath.getParent();
                Iterator<Map.Entry<String, JsonNode>> fields = models.fields();
                while (fields.hasNext()) {
                    Map.Entry<String, JsonNode> field = fields.next();
                    toCandidate(field.getKey(), field.getValue(), baseDir, registryPath)
                            .ifPresent(candidate -> byDisplayId.putIfAbsent(candidate.displayModelId(), candidate));
                }
            } catch (Exception e) {
                log.debug("Could not inspect local model registry {}: {}", registryPath, e.getMessage());
            }
        }

        List<LocalModelCandidate> candidates = new ArrayList<>(byDisplayId.values());
        candidates.sort(Comparator
                .comparingInt(LocalModelCandidate::contextWindow).reversed()
                .thenComparing(LocalModelCandidate::modelId));
        cachedCandidates = List.copyOf(candidates);
        cacheExpiresAtMs = now + DISCOVERY_CACHE_TTL.toMillis();
        log.debug("Discovered {} local staged LLM candidate(s): {}",
                cachedCandidates.size(), cachedCandidates.stream().map(LocalModelCandidate::displayModelId).toList());
        return cachedCandidates;
    }

    private Optional<LocalModelCandidate> toCandidate(String key, JsonNode node, Path baseDir, Path registryPath) {
        String type = node.path("type").asText("").trim();
        if (!LLM_GGML_TYPE.equals(type)) {
            return Optional.empty();
        }
        String status = node.path("status").asText("active").trim();
        if (!status.isEmpty() && !"active".equalsIgnoreCase(status)) {
            return Optional.empty();
        }
        String modelId = node.path("model_id").asText(key).trim();
        String relativePath = node.path("path").asText("").trim();
        String modelFile = node.path("model_file").asText("").trim();
        if (modelId.isEmpty() || relativePath.isEmpty() || modelFile.isEmpty()) {
            return Optional.empty();
        }
        Path modelPath = baseDir.resolve(relativePath).resolve(modelFile).normalize();
        if (!Files.isRegularFile(modelPath)) {
            log.debug("Skipping local LLM '{}' from {} because model file is missing: {}",
                    modelId, registryPath, modelPath);
            return Optional.empty();
        }
        int context = readInt(node.path("metadata").path("max_sequence_length"));
        if (context <= 0) {
            context = readInt(node.path("tokenizer").path("max_length"));
        }
        if (context <= 0 && modelFile.toLowerCase(java.util.Locale.ROOT).endsWith(".gguf")) {
            // Registry entries for staged GGUF LLMs routinely carry no sequence-length metadata;
            // the model file itself declares it in the GGUF header (<arch>.context_length).
            // Without this, capability budgeting falls to a 2k-token default and the extraction
            // chain's window guard would skip the model for any real batch.
            context = ai.kompile.utils.GgufMetadataReader.readContextLength(modelPath).orElse(0);
            if (context > 0) {
                log.debug("Local LLM '{}' context window {} read from GGUF header {}",
                        modelId, context, modelPath.getFileName());
            }
        }
        long estimatedMemory = readLong(node.path("metadata").path("estimated_memory_bytes"));
        return Optional.of(new LocalModelCandidate(
                modelId,
                toDisplayModelId(modelId),
                modelPath,
                context,
                estimatedMemory,
                registryPath));
    }

    private List<Path> registryPaths() {
        LinkedHashSet<Path> paths = new LinkedHashSet<>();
        String dataDir = System.getProperty("kompile.data.dir");
        if (dataDir != null && !dataDir.isBlank()) {
            paths.add(Path.of(dataDir).resolve("data/models").resolve(REGISTRY_FILENAME).toAbsolutePath().normalize());
        }
        paths.add(KompileHome.resolvedProjectDirectory().toPath()
                .resolve("data/models").resolve(REGISTRY_FILENAME).toAbsolutePath().normalize());
        String envCache = System.getenv("KOMPILE_MODEL_CACHE_DIR");
        if (envCache != null && !envCache.isBlank()) {
            paths.add(Path.of(envCache).resolve(REGISTRY_FILENAME).toAbsolutePath().normalize());
        }
        paths.add(KompileHome.modelsDirectory().toPath().resolve(REGISTRY_FILENAME).toAbsolutePath().normalize());
        return List.copyOf(paths);
    }

    private void ensureLoaded(LocalModelCandidate candidate, Duration timeout) throws IOException, InterruptedException {
        JsonNode status = getStatus(Duration.ofSeconds(Math.min(10, Math.max(5, timeout.toSeconds()))));
        if (status.path("loaded").asBoolean(false)
                && candidate.modelId().equals(status.path("modelId").asText(""))) {
            return;
        }

        ObjectNode body = objectMapper.createObjectNode();
        body.put("modelId", candidate.modelId());
        body.put("modelPath", candidate.modelPath().toString());
        body.put("kvCacheType", "STATIC");
        JsonNode load = postJson("/api/llm/load", body, timeout);
        if (!load.path("loaded").asBoolean(false)) {
            throw new IOException("Local staging LLM load failed for " + candidate.modelId()
                    + ": " + load.path("message").asText("unknown error"));
        }
    }

    private JsonNode getStatus(Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint("/api/llm/status"))
                .timeout(timeout)
                .GET()
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Local staging status returned HTTP " + response.statusCode());
        }
        return objectMapper.readTree(response.body());
    }

    private JsonNode postJson(String path, JsonNode body, Duration timeout) throws IOException, InterruptedException {
        HttpRequest request = HttpRequest.newBuilder(endpoint(path))
                .timeout(timeout)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(body)))
                .build();
        HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() >= 400) {
            throw new IOException("Local staging " + path + " returned HTTP " + response.statusCode()
                    + ": " + response.body());
        }
        return objectMapper.readTree(response.body());
    }

    private URI endpoint(String path) {
        String base = stagingUrl == null || stagingUrl.isBlank() ? "http://localhost:8090" : stagingUrl.trim();
        while (base.endsWith("/")) {
            base = base.substring(0, base.length() - 1);
        }
        return URI.create(base + path);
    }

    private int maxTokens(LocalModelCandidate candidate) {
        int context = candidate.contextWindow() > 0 ? candidate.contextWindow() : 2048;
        return Math.max(128, Math.min(1024, context / 2));
    }

    private static String toDisplayModelId(String modelId) {
        return modelId != null && modelId.startsWith(MODEL_PREFIX) ? modelId : MODEL_PREFIX + modelId;
    }

    private static String bareModelId(String modelId) {
        return modelId != null && modelId.startsWith(MODEL_PREFIX)
                ? modelId.substring(MODEL_PREFIX.length())
                : modelId;
    }

    private static int readInt(JsonNode node) {
        return node != null && node.canConvertToInt() ? node.asInt() : 0;
    }

    private static long readLong(JsonNode node) {
        return node != null && node.canConvertToLong() ? node.asLong() : 0L;
    }
}

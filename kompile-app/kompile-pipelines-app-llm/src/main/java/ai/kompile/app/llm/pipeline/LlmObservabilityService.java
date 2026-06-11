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
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * LLM model lifecycle management and observability.
 *
 * <p>Separates model load/unload/status/DSP diagnostics from the
 * {@link ai.kompile.core.llm.LanguageModel} inference interface. This service
 * handles the operational concerns:</p>
 * <ul>
 *   <li>Model loading and unloading</li>
 *   <li>Load progress and phase tracking</li>
 *   <li>DSP plan phase, frozen count, compilation stats</li>
 *   <li>Triton kernel cache hit/miss counts</li>
 * </ul>
 *
 * <p>Operates in two contexts:</p>
 * <ul>
 *   <li><b>Direct</b> (subprocess context, {@code kompile.llm.serving.url} empty):
 *       reads from the local {@link SameDiffLanguageModelImpl}.</li>
 *   <li><b>Subprocess</b> (app-main context, {@code kompile.llm.serving.url} set):
 *       polls the serving subprocess's {@code /api/llm/status} endpoint.</li>
 * </ul>
 */
@Service
public class LlmObservabilityService {

    private static final Logger logger = LoggerFactory.getLogger(LlmObservabilityService.class);
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String servingUrl;
    private final HttpClient httpClient;

    /** Local model impl — non-null only in subprocess (direct) context. */
    private final SameDiffLanguageModelImpl directModel;

    @Autowired
    public LlmObservabilityService(
            @Autowired(required = false) SameDiffLanguageModelImpl directModel) {
        String servingUrl = "";
        this.servingUrl = (servingUrl != null && !servingUrl.isBlank()) ? servingUrl.trim() : null;
        this.directModel = directModel;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();

        if (isSubprocessMode()) {
            logger.info("LlmObservabilityService: subprocess-client mode → {}", this.servingUrl);
        } else if (directModel != null) {
            logger.info("LlmObservabilityService: direct mode (local model)");
        } else {
            logger.info("LlmObservabilityService: no model backend configured");
        }
    }

    /** True when forwarding to a subprocess via HTTP. */
    public boolean isSubprocessMode() {
        return servingUrl != null;
    }

    /** The subprocess URL, or null if direct mode. */
    public String getServingUrl() {
        return servingUrl;
    }

    // ==================== Load / Unload ====================

    /**
     * Load a model. In direct mode, delegates to the local {@link SameDiffLanguageModelImpl}.
     * In subprocess mode, forwards the load request via HTTP to the subprocess.
     */
    public void loadModel(String modelId, Path modelFile, Path tokenizerFile,
                          Map<String, Object> opts) throws Exception {
        if (isSubprocessMode()) {
            loadViaSubprocess(modelId, opts);
        } else if (directModel != null) {
            directModel.loadModel(modelId, modelFile, tokenizerFile, opts);
        } else {
            throw new IllegalStateException("No model backend available for loading");
        }
    }

    /** Unload the currently loaded model. */
    public void unloadModel() {
        if (isSubprocessMode()) {
            unloadViaSubprocess();
        } else if (directModel != null) {
            directModel.unloadModel();
        }
    }

    // ==================== Status / Observability ====================

    public boolean isLoaded() {
        if (isSubprocessMode()) return getBooleanField("loaded", false);
        return directModel != null && directModel.isLoaded();
    }

    public String getLoadedModelId() {
        if (isSubprocessMode()) return getStringField("modelId");
        return directModel != null ? directModel.getLoadedModelId() : null;
    }

    public long getLoadDurationMs() {
        if (isSubprocessMode()) return getLongField("loadDurationMs");
        return directModel != null ? directModel.getLoadDurationMs() : -1L;
    }

    public boolean isLoading() {
        if (isSubprocessMode()) return getBooleanField("loading", false);
        return directModel != null && directModel.isLoading();
    }

    public String getLoadingModelId() {
        if (isSubprocessMode()) return getStringField("loadingModelId");
        return directModel != null ? directModel.getLoadingModelId() : null;
    }

    public long getLoadElapsedMs() {
        if (isSubprocessMode()) return getLongField("loadElapsedMs");
        return directModel != null ? directModel.getLoadElapsedMs() : -1L;
    }

    public String getLoadingPhase() {
        if (isSubprocessMode()) return getStringField("loadingPhase");
        return directModel != null ? directModel.getLoadingPhase() : null;
    }

    public String getDspPlanPhase() {
        if (isSubprocessMode()) return getStringField("dspPlanPhase");
        return directModel != null ? directModel.getDspPlanPhase() : null;
    }

    public int getDspFrozenCount() {
        if (isSubprocessMode()) {
            JsonNode status = fetchStatus();
            return status != null && status.has("dspFrozenCount")
                    ? status.get("dspFrozenCount").asInt(-1) : -1;
        }
        return directModel != null ? directModel.getDspFrozenCount() : -1;
    }

    public String getDspPlanReport() {
        if (isSubprocessMode()) return getStringField("dspPlanReport");
        return directModel != null ? directModel.getDspPlanReport() : null;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> getDspCompilationStats() {
        if (isSubprocessMode()) {
            JsonNode status = fetchStatus();
            if (status != null && status.has("dspCompilationStats")
                    && !status.get("dspCompilationStats").isNull()) {
                try {
                    return MAPPER.treeToValue(status.get("dspCompilationStats"), Map.class);
                } catch (Exception ignored) {}
            }
            return null;
        }
        return directModel != null ? directModel.getDspCompilationStats() : null;
    }

    // ==================== Subprocess HTTP ====================

    private void loadViaSubprocess(String modelId, Map<String, Object> opts) {
        Objects.requireNonNull(modelId, "modelId");
        try {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("modelId", modelId);
            if (opts != null && !opts.isEmpty()) {
                body.put("options", opts);
            }
            String bodyJson = MAPPER.writeValueAsString(body);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(servingUrl + "/api/llm/load"))
                    .timeout(Duration.ofMinutes(2))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(bodyJson))
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int code = response.statusCode();
            if (code == 202) {
                logger.info("Subprocess load accepted for '{}', model loading asynchronously", modelId);
            } else if (code >= 200 && code < 300) {
                logger.info("Subprocess load completed for '{}'", modelId);
            } else {
                throw new RuntimeException("Subprocess load failed for '" + modelId
                        + "': HTTP " + code + " — " + response.body());
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException("Subprocess load failed for '" + modelId + "': " + e.getMessage(), e);
        }
    }

    private void unloadViaSubprocess() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(servingUrl + "/api/llm/unload"))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();
            httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            logger.info("Subprocess unload sent");
        } catch (Exception e) {
            logger.error("Subprocess unload failed: {}", e.getMessage());
        }
    }

    /** Fetch the full status JSON from the subprocess. Returns null on error. */
    private JsonNode fetchStatus() {
        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(servingUrl + "/api/llm/status"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() == 200) {
                return MAPPER.readTree(response.body());
            }
        } catch (Exception e) {
            logger.debug("Failed to fetch subprocess status: {}", e.getMessage());
        }
        return null;
    }

    private boolean getBooleanField(String field, boolean defaultValue) {
        JsonNode status = fetchStatus();
        return status != null && status.has(field)
                ? status.get(field).asBoolean(defaultValue) : defaultValue;
    }

    private String getStringField(String field) {
        JsonNode status = fetchStatus();
        return status != null && status.has(field) && !status.get(field).isNull()
                ? status.get(field).asText() : null;
    }

    private long getLongField(String field) {
        JsonNode status = fetchStatus();
        return status != null && status.has(field)
                ? status.get(field).asLong(-1L) : -1L;
    }
}

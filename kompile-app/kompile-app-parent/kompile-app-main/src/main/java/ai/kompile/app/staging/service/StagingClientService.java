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

package ai.kompile.app.staging.service;

import ai.kompile.app.staging.domain.StagingServiceConfig;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;

/**
 * Client service for communicating with a remote Model Staging Service.
 */
@Service
public class StagingClientService {

    private static final Logger log = LoggerFactory.getLogger(StagingClientService.class);

    private final StagingServiceConfigService configService;
    private final ObjectMapper objectMapper;

    // Connection status tracking for retry functionality
    private volatile ConnectionStatus lastConnectionStatus = ConnectionStatus.notConnected();
    private final Object statusLock = new Object();

    public StagingClientService(StagingServiceConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
    }

    /**
     * Connection status tracking for retry functionality.
     * Tracks the last connection attempt result to help users understand and retry.
     */
    public record ConnectionStatus(
            boolean connected,
            boolean attempted,
            String endpointUrl,
            String lastError,
            long lastAttemptTimeMs,
            int consecutiveFailures
    ) {
        public static ConnectionStatus notConnected() {
            return new ConnectionStatus(false, false, null, null, 0, 0);
        }

        public static ConnectionStatus success(String endpointUrl) {
            return new ConnectionStatus(true, true, endpointUrl, null, System.currentTimeMillis(), 0);
        }

        public static ConnectionStatus failure(String endpointUrl, String error, int failureCount) {
            return new ConnectionStatus(false, true, endpointUrl, error, System.currentTimeMillis(), failureCount);
        }

        public boolean canRetry() {
            return attempted && !connected;
        }

        public long timeSinceLastAttemptMs() {
            return lastAttemptTimeMs > 0 ? System.currentTimeMillis() - lastAttemptTimeMs : -1;
        }
    }

    /**
     * Get the current connection status.
     * Useful for UI to show connection state and enable retry button.
     */
    public ConnectionStatus getConnectionStatus() {
        synchronized (statusLock) {
            return lastConnectionStatus;
        }
    }

    /**
     * Retry the connection to the active staging service.
     * This is the main entry point for users to retry a failed connection.
     *
     * @return ConnectionTestResult with the retry result
     */
    public ConnectionTestResult retryConnection() {
        Optional<StagingServiceConfig> activeConfig = configService.getActiveConfig();
        if (activeConfig.isEmpty()) {
            synchronized (statusLock) {
                lastConnectionStatus = ConnectionStatus.failure(null, "No active staging service configured",
                        lastConnectionStatus.consecutiveFailures() + 1);
            }
            return ConnectionTestResult.failure("No active staging service configured");
        }

        StagingServiceConfig config = activeConfig.get();
        log.info("Retrying connection to staging service: {}", config.getEndpointUrl());

        ConnectionTestResult result = testConnection(config);

        synchronized (statusLock) {
            if (result.success()) {
                lastConnectionStatus = ConnectionStatus.success(config.getEndpointUrl());
                log.info("Connection retry successful to {}", config.getEndpointUrl());
            } else {
                lastConnectionStatus = ConnectionStatus.failure(
                        config.getEndpointUrl(),
                        result.message(),
                        lastConnectionStatus.consecutiveFailures() + 1
                );
                log.warn("Connection retry failed to {}: {}", config.getEndpointUrl(), result.message());
            }
        }

        // Update verification status in database
        configService.updateVerificationStatus(config.getId(), result.success(), result.message());

        return result;
    }

    /**
     * Clear the connection status (e.g., when switching configurations).
     */
    public void clearConnectionStatus() {
        synchronized (statusLock) {
            lastConnectionStatus = ConnectionStatus.notConnected();
        }
    }

    /**
     * Test connection to a staging service.
     */
    public ConnectionTestResult testConnection(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Parse the registry to get model count
                JsonNode registry = objectMapper.readTree(response.body());
                int modelCount = 0;
                if (registry.has("models") && registry.get("models").isObject()) {
                    modelCount = registry.get("models").size();
                }
                return ConnectionTestResult.success(modelCount, registry.path("version").asText("unknown"));
            } else {
                return ConnectionTestResult.failure("HTTP " + response.statusCode() + ": " + response.body());
            }
        } catch (Exception e) {
            log.error("Connection test failed for {}", config.getEndpointUrl(), e);
            return ConnectionTestResult.failure(e.getMessage());
        }
    }

    /**
     * Test connection using the active configuration.
     */
    public ConnectionTestResult testActiveConnection() {
        return configService.getActiveConfig()
                .map(this::testConnection)
                .orElse(ConnectionTestResult.failure("No active staging service configured"));
    }

    /**
     * Get the model registry from the staging service.
     */
    public Optional<JsonNode> getRegistry() {
        return configService.getActiveConfig()
                .flatMap(this::getRegistry);
    }

    /**
     * Get the model registry from a specific staging service.
     */
    public Optional<JsonNode> getRegistry(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to get registry from {}", config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Get the model catalog from the staging service.
     */
    public Optional<JsonNode> getCatalog() {
        return configService.getActiveConfig()
                .flatMap(this::getCatalog);
    }

    /**
     * Get the model catalog from a specific staging service.
     */
    public Optional<JsonNode> getCatalog(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/catalog";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to get catalog from {}", config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Get staging status from the staging service.
     */
    public Optional<JsonNode> getStagingStatus() {
        return configService.getActiveConfig()
                .flatMap(this::getStagingStatus);
    }

    /**
     * Get staging status from a specific staging service.
     */
    public Optional<JsonNode> getStagingStatus(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/status";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to get staging status from {}", config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Stage a model from the catalog.
     */
    public Optional<JsonNode> stageModelFromCatalog(String modelId, boolean autoPromote) {
        return configService.getActiveConfig()
                .flatMap(config -> stageModelFromCatalog(config, modelId, autoPromote));
    }

    /**
     * Stage a model from the catalog on a specific staging service.
     */
    public Optional<JsonNode> stageModelFromCatalog(StagingServiceConfig config, String modelId, boolean autoPromote) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/stage/catalog/" + modelId + "?autoPromote=" + autoPromote;

            HttpRequest request = createRequest(config, url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 202) {
                return Optional.of(objectMapper.readTree(response.body()));
            } else {
                log.error("Failed to stage model {}: HTTP {}", modelId, response.statusCode());
            }
        } catch (Exception e) {
            log.error("Failed to stage model {} on {}", modelId, config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Promote a staged model to the registry.
     */
    public boolean promoteModel(String modelId) {
        return configService.getActiveConfig()
                .map(config -> promoteModel(config, modelId))
                .orElse(false);
    }

    /**
     * Promote a staged model on a specific staging service.
     */
    public boolean promoteModel(StagingServiceConfig config, String modelId) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/promote/" + modelId;

            HttpRequest request = createRequest(config, url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Failed to promote model {} on {}", modelId, config.getEndpointUrl(), e);
            return false;
        }
    }

    /**
     * Get a specific model from the registry.
     */
    public Optional<JsonNode> getModel(String modelId) {
        return configService.getActiveConfig()
                .flatMap(config -> getModel(config, modelId));
    }

    /**
     * Get a specific model from a staging service's registry.
     */
    public Optional<JsonNode> getModel(StagingServiceConfig config, String modelId) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry/model/" + modelId;

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to get model {} from {}", modelId, config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Check if a model exists in the staging service's registry.
     */
    public Optional<JsonNode> checkModelExists(String modelId) {
        return configService.getActiveConfig()
                .flatMap(config -> checkModelExists(config, modelId));
    }

    /**
     * Check if a model exists in a specific staging service's registry.
     */
    public Optional<JsonNode> checkModelExists(StagingServiceConfig config, String modelId) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry/model/" + modelId + "/exists";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to check model exists {} from {}", modelId, config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Delete a model from the staging service's registry.
     *
     * @param modelId The model ID to delete
     * @param deleteFiles Whether to also delete model files from disk
     * @return Deletion result as JSON
     */
    public Optional<JsonNode> deleteModel(String modelId, boolean deleteFiles) {
        return configService.getActiveConfig()
                .flatMap(config -> deleteModel(config, modelId, deleteFiles));
    }

    /**
     * Delete a model from a specific staging service's registry.
     *
     * @param config The staging service configuration
     * @param modelId The model ID to delete
     * @param deleteFiles Whether to also delete model files from disk
     * @return Deletion result as JSON
     */
    public Optional<JsonNode> deleteModel(StagingServiceConfig config, String modelId, boolean deleteFiles) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry/model/" + modelId + "?deleteFiles=" + deleteFiles;

            HttpRequest request = createRequest(config, url)
                    .DELETE()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            log.error("Failed to delete model {} from {}", modelId, config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Replace a model in the staging service's registry.
     *
     * @param modelId The model ID to replace
     * @param replaceRequest The replacement request details
     * @return Replacement result as JSON
     */
    public Optional<JsonNode> replaceModel(String modelId, Map<String, Object> replaceRequest) {
        return configService.getActiveConfig()
                .flatMap(config -> replaceModel(config, modelId, replaceRequest));
    }

    /**
     * Replace a model in a specific staging service's registry.
     *
     * @param config The staging service configuration
     * @param modelId The model ID to replace
     * @param replaceRequest The replacement request details
     * @return Replacement result as JSON
     */
    public Optional<JsonNode> replaceModel(StagingServiceConfig config, String modelId, Map<String, Object> replaceRequest) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/registry/model/" + modelId + "/replace";
            String requestBody = objectMapper.writeValueAsString(replaceRequest);

            HttpRequest request = createRequest(config, url)
                    .PUT(HttpRequest.BodyPublishers.ofString(requestBody))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return Optional.of(objectMapper.readTree(response.body()));
        } catch (Exception e) {
            log.error("Failed to replace model {} on {}", modelId, config.getEndpointUrl(), e);
        }
        return Optional.empty();
    }

    /**
     * Upload an .sdz model file directly to the staging service.
     *
     * @param modelFileBytes The .sdz file content
     * @param modelFileName The original filename
     * @param vocabFileBytes Optional vocab file content (may be null)
     * @param vocabFileName Optional vocab filename
     * @param modelId The model ID
     * @param modelType The model type (dense_encoder, sparse_encoder, cross_encoder)
     * @param version Optional version string
     * @param embeddingDim Optional embedding dimension
     * @param maxSequenceLength Optional max sequence length
     * @param description Optional description
     * @param overwrite Whether to overwrite existing model
     * @return Upload result as JsonNode, or empty if failed
     */
    public Optional<JsonNode> uploadSdzModel(
            byte[] modelFileBytes,
            String modelFileName,
            byte[] vocabFileBytes,
            String vocabFileName,
            String modelId,
            String modelType,
            String version,
            Integer embeddingDim,
            Integer maxSequenceLength,
            String description,
            boolean overwrite) {
        return configService.getActiveConfig()
                .flatMap(config -> uploadSdzModel(config, modelFileBytes, modelFileName,
                        vocabFileBytes, vocabFileName, modelId, modelType, version,
                        embeddingDim, maxSequenceLength, description, overwrite));
    }

    /**
     * Upload an .sdz model file to a specific staging service.
     */
    public Optional<JsonNode> uploadSdzModel(
            StagingServiceConfig config,
            byte[] modelFileBytes,
            String modelFileName,
            byte[] vocabFileBytes,
            String vocabFileName,
            String modelId,
            String modelType,
            String version,
            Integer embeddingDim,
            Integer maxSequenceLength,
            String description,
            boolean overwrite) {
        try {
            String boundary = "----WebKitFormBoundary" + System.currentTimeMillis();
            String url = config.getApiBaseUrl() + "/upload-sdz";

            // Build multipart body
            java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();

            // Model file part
            writeMultipartFile(baos, boundary, "modelFile", modelFileName, modelFileBytes);

            // Vocab file part (optional)
            if (vocabFileBytes != null && vocabFileName != null) {
                writeMultipartFile(baos, boundary, "vocabFile", vocabFileName, vocabFileBytes);
            }

            // Form fields
            writeMultipartField(baos, boundary, "modelId", modelId);
            writeMultipartField(baos, boundary, "modelType", modelType != null ? modelType : "dense_encoder");
            if (version != null && !version.isEmpty()) {
                writeMultipartField(baos, boundary, "version", version);
            }
            if (embeddingDim != null) {
                writeMultipartField(baos, boundary, "embeddingDim", embeddingDim.toString());
            }
            if (maxSequenceLength != null) {
                writeMultipartField(baos, boundary, "maxSequenceLength", maxSequenceLength.toString());
            }
            if (description != null && !description.isEmpty()) {
                writeMultipartField(baos, boundary, "description", description);
            }
            writeMultipartField(baos, boundary, "overwrite", String.valueOf(overwrite));

            // End boundary
            baos.write(("--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));

            HttpClient client = createHttpClient(config);
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(10)) // Longer timeout for uploads
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(baos.toByteArray()))
                    .build();

            // Add API key if configured
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                request = HttpRequest.newBuilder(request, (n, v) -> true)
                        .header("X-API-Key", config.getApiKey())
                        .build();
            }

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200 || response.statusCode() == 201) {
                return Optional.of(objectMapper.readTree(response.body()));
            } else {
                log.error("Upload failed with status {}: {}", response.statusCode(), response.body());
                // Return the error response as JSON
                return Optional.of(objectMapper.readTree(response.body()));
            }
        } catch (Exception e) {
            log.error("Failed to upload .sdz model to {}: {}", config.getEndpointUrl(), e.getMessage(), e);
        }
        return Optional.empty();
    }

    private void writeMultipartFile(java.io.ByteArrayOutputStream baos, String boundary,
                                    String fieldName, String fileName, byte[] content) throws java.io.IOException {
        String header = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + fieldName + "\"; filename=\"" + fileName + "\"\r\n" +
                "Content-Type: application/octet-stream\r\n\r\n";
        baos.write(header.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        baos.write(content);
        baos.write("\r\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private void writeMultipartField(java.io.ByteArrayOutputStream baos, String boundary,
                                     String fieldName, String value) throws java.io.IOException {
        String part = "--" + boundary + "\r\n" +
                "Content-Disposition: form-data; name=\"" + fieldName + "\"\r\n\r\n" +
                value + "\r\n";
        baos.write(part.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private HttpClient createHttpClient(StagingServiceConfig config) {
        return HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()))
                .build();
    }

    private HttpRequest.Builder createRequest(StagingServiceConfig config, String url) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofMillis(config.getReadTimeoutMs()))
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .header(HttpHeaders.ACCEPT, MediaType.APPLICATION_JSON_VALUE);

        // Add API key if configured
        if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
            builder.header("X-API-Key", config.getApiKey());
        }

        return builder;
    }

    /**
     * Get active models from the staging service (one per type).
     */
    public Optional<Map<String, String>> getActiveModels() {
        return configService.getActiveConfig()
                .flatMap(this::getActiveModels);
    }

    /**
     * Get active models from a specific staging service.
     */
    public Optional<Map<String, String>> getActiveModels(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/active";

            HttpRequest request = createRequest(config, url)
                    .GET()
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                // Update connection status on success
                synchronized (statusLock) {
                    lastConnectionStatus = ConnectionStatus.success(config.getEndpointUrl());
                }

                JsonNode node = objectMapper.readTree(response.body());
                JsonNode active = node.get("active");
                if (active != null && active.isObject()) {
                    Map<String, String> result = new java.util.HashMap<>();
                    active.fieldNames().forEachRemaining(field -> {
                        result.put(field, active.get(field).asText());
                    });
                    return Optional.of(result);
                }
            } else {
                // Track non-200 responses as failures
                String errorMsg = "HTTP " + response.statusCode();
                synchronized (statusLock) {
                    lastConnectionStatus = ConnectionStatus.failure(
                            config.getEndpointUrl(),
                            errorMsg,
                            lastConnectionStatus.consecutiveFailures() + 1
                    );
                }
            }
        } catch (Exception e) {
            String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            log.error("Failed to get active models from {}: {}", config.getEndpointUrl(), errorMsg);

            // Update connection status on failure
            synchronized (statusLock) {
                lastConnectionStatus = ConnectionStatus.failure(
                        config.getEndpointUrl(),
                        errorMsg,
                        lastConnectionStatus.consecutiveFailures() + 1
                );
            }
        }
        return Optional.empty();
    }

    /**
     * Activate a model (deactivates other models of the same type).
     */
    public boolean activateModel(String modelId) {
        return configService.getActiveConfig()
                .map(config -> activateModel(config, modelId))
                .orElse(false);
    }

    /**
     * Activate a model on a specific staging service.
     */
    public boolean activateModel(StagingServiceConfig config, String modelId) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/models/" + modelId + "/activate";

            HttpRequest request = createRequest(config, url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            return response.statusCode() == 200;
        } catch (Exception e) {
            log.error("Failed to activate model {} on {}", modelId, config.getEndpointUrl(), e);
            return false;
        }
    }

    /**
     * Normalize the registry to ensure only ONE model per type is active.
     */
    public Optional<Map<String, String>> normalizeRegistry() {
        return configService.getActiveConfig()
                .flatMap(this::normalizeRegistry);
    }

    /**
     * Normalize the registry on a specific staging service.
     */
    public Optional<Map<String, String>> normalizeRegistry(StagingServiceConfig config) {
        try {
            HttpClient client = createHttpClient(config);
            String url = config.getApiBaseUrl() + "/normalize";

            HttpRequest request = createRequest(config, url)
                    .POST(HttpRequest.BodyPublishers.ofString("{}"))
                    .build();

            HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                JsonNode result = objectMapper.readTree(response.body());
                JsonNode changesNode = result.path("changes");
                if (changesNode.isObject()) {
                    @SuppressWarnings("unchecked")
                    Map<String, String> changes = objectMapper.convertValue(changesNode, Map.class);
                    return Optional.of(changes);
                }
                return Optional.of(Map.of());
            }
            return Optional.empty();
        } catch (Exception e) {
            log.error("Failed to normalize registry on {}", config.getEndpointUrl(), e);
            return Optional.empty();
        }
    }

    /**
     * Result of a connection test.
     */
    public record ConnectionTestResult(
            boolean success,
            String message,
            int modelCount,
            String version
    ) {
        public static ConnectionTestResult success(int modelCount, String version) {
            return new ConnectionTestResult(true, "Connected successfully", modelCount, version);
        }

        public static ConnectionTestResult failure(String message) {
            return new ConnectionTestResult(false, message, 0, null);
        }
    }

    // ==================== Model Download Operations ====================

    /**
     * Download a model from the remote staging service to local storage.
     * Downloads both the model file (.sdz) and vocab file to ~/.kompile/models/{modelId}/
     *
     * @param modelId The model ID to download
     * @return Download result with success status and local path
     */
    public DownloadModelResult downloadModel(String modelId) {
        return configService.getActiveConfig()
                .map(config -> downloadModel(config, modelId))
                .orElse(DownloadModelResult.failure("No active staging service configured"));
    }

    /**
     * Download a model from a specific staging service to local storage.
     *
     * @param config The staging service configuration
     * @param modelId The model ID to download
     * @return Download result with success status and local path
     */
    public DownloadModelResult downloadModel(StagingServiceConfig config, String modelId) {
        try {
            // Get model info first to verify it exists
            Optional<JsonNode> modelInfo = getModel(config, modelId);
            if (modelInfo.isEmpty()) {
                return DownloadModelResult.failure("Model not found: " + modelId);
            }

            JsonNode model = modelInfo.get();
            String modelType = model.has("type") ? model.get("type").asText() : "dense_encoder";

            // Create local directory for the model
            Path localModelsDir = getLocalModelsDir();
            Path modelDir = localModelsDir.resolve(modelId);
            Files.createDirectories(modelDir);

            log.info("Downloading model {} from {} to {}", modelId, config.getEndpointUrl(), modelDir);

            // Download model file
            Path modelFilePath = modelDir.resolve("model.sdz");
            String modelDownloadUrl = config.getApiBaseUrl() + "/registry/model/" + modelId + "/download/model";
            DownloadFileResult modelDownloadResult = downloadFile(config, modelDownloadUrl, modelFilePath);

            if (!modelDownloadResult.succeeded()) {
                String errorMsg = "Failed to download model file: " + modelDownloadResult.message();
                log.error(errorMsg);
                return DownloadModelResult.failure(errorMsg);
            }

            // Download vocab file (optional, don't fail if not present)
            Path vocabFilePath = modelDir.resolve("vocab.txt");
            String vocabDownloadUrl = config.getApiBaseUrl() + "/registry/model/" + modelId + "/download/vocab";
            DownloadFileResult vocabDownloadResult = downloadFile(config, vocabDownloadUrl, vocabFilePath);

            boolean vocabDownloaded = vocabDownloadResult.succeeded();
            if (!vocabDownloaded) {
                log.warn("Vocab file not available for model {}: {}", modelId, vocabDownloadResult.message());
            }

            // Get metadata for dimensions
            Integer embeddingDim = null;
            if (model.has("metadata")) {
                JsonNode metadata = model.get("metadata");
                if (metadata.has("embedding_dim")) {
                    embeddingDim = metadata.get("embedding_dim").asInt();
                }
            }

            log.info("Successfully downloaded model {} to {}", modelId, modelDir);

            return DownloadModelResult.success(
                    modelId,
                    modelDir,
                    modelType,
                    embeddingDim,
                    vocabDownloaded
            );

        } catch (Exception e) {
            log.error("Failed to download model {} from {}: {}", modelId, config.getEndpointUrl(), e.getMessage(), e);
            return DownloadModelResult.failure("Download failed: " + e.getMessage());
        }
    }

    /**
     * Result of a file download operation with status details.
     */
    private record DownloadFileResult(boolean succeeded, int statusCode, String message) {
        public static DownloadFileResult success() {
            return new DownloadFileResult(true, 200, "OK");
        }
        public static DownloadFileResult notFound(String url) {
            return new DownloadFileResult(false, 404, "File not found at " + url);
        }
        public static DownloadFileResult httpError(int statusCode, String url) {
            return new DownloadFileResult(false, statusCode, "HTTP " + statusCode + " from " + url);
        }
        public static DownloadFileResult error(String message) {
            return new DownloadFileResult(false, -1, message);
        }
    }

    /**
     * Download a file from a URL to a local path.
     */
    private DownloadFileResult downloadFile(StagingServiceConfig config, String url, Path destPath) {
        try {
            log.info("Downloading file from {} to {}", url, destPath);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofMillis(config.getConnectionTimeoutMs()))
                    .build();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(10)) // Long timeout for large files
                    .GET();

            // Add API key if configured
            if (config.getApiKey() != null && !config.getApiKey().isBlank()) {
                requestBuilder.header("X-API-Key", config.getApiKey());
            }

            HttpRequest request = requestBuilder.build();

            HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());

            try (InputStream bodyStream = response.body()) {
                if (response.statusCode() == 200) {
                    Files.copy(bodyStream, destPath, StandardCopyOption.REPLACE_EXISTING);
                    log.info("Successfully downloaded file to {}", destPath);
                    return DownloadFileResult.success();
                } else if (response.statusCode() == 404) {
                    log.warn("File not found (404) at {}", url);
                    return DownloadFileResult.notFound(url);
                } else {
                    log.error("Failed to download file from {}: HTTP {}", url, response.statusCode());
                    return DownloadFileResult.httpError(response.statusCode(), url);
                }
            }
        } catch (Exception e) {
            log.error("Error downloading file from {}: {}", url, e.getMessage(), e);
            return DownloadFileResult.error("Download error: " + e.getMessage());
        }
    }

    /**
     * Get the local models directory (~/.kompile/models).
     */
    private Path getLocalModelsDir() throws IOException {
        String userHome = System.getProperty("user.home");
        Path kompileDir = Paths.get(userHome, ".kompile", "models");
        Files.createDirectories(kompileDir);
        return kompileDir;
    }

    /**
     * Check if a model is already downloaded locally.
     *
     * @param modelId The model ID to check
     * @return true if the model exists locally with a model.sdz file
     */
    public boolean isModelDownloaded(String modelId) {
        try {
            Path modelDir = getLocalModelsDir().resolve(modelId);
            Path modelFile = modelDir.resolve("model.sdz");
            return Files.exists(modelFile);
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Result of downloading a model from remote staging.
     */
    public record DownloadModelResult(
            boolean success,
            String message,
            String modelId,
            Path localPath,
            String modelType,
            Integer embeddingDim,
            boolean hasVocab
    ) {
        public static DownloadModelResult success(String modelId, Path localPath, String modelType,
                                                   Integer embeddingDim, boolean hasVocab) {
            return new DownloadModelResult(
                    true,
                    "Model downloaded successfully",
                    modelId,
                    localPath,
                    modelType,
                    embeddingDim,
                    hasVocab
            );
        }

        public static DownloadModelResult failure(String message) {
            return new DownloadModelResult(false, message, null, null, null, null, false);
        }
    }
}

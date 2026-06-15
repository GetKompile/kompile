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

package ai.kompile.modelmanager;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Model manager that loads models from:
 * 1. Local registry file (~/.kompile/models/registry.json)
 * 2. Remote staging service (configured via UI or property)
 * 3. Loaded archive files
 */
public class RegistryBasedModelManager {

    private static final Logger logger = LoggerFactory.getLogger(RegistryBasedModelManager.class);

    public static final String ENV_KOMPILE_MODEL_CACHE_DIR = "KOMPILE_MODEL_CACHE_DIR";
    public static final String DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR = ".kompile" + File.separator + "models";

    private final Path baseCachePath;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    // Remote staging configuration
    private volatile String stagingUrl;
    private volatile String stagingApiKey;
    private volatile int stagingRetryPollIntervalSeconds = 30; // Default 30 seconds

    // Loaded archive
    private volatile Path loadedArchivePath;
    private volatile JsonNode archiveRegistry;

    // Cache of loaded model bundles
    private final Map<String, KompileModelManager.ModelBundle> bundleCache = new ConcurrentHashMap<>();
    private final Map<String, KompileModelManager.CrossEncoderBundle> crossEncoderCache = new ConcurrentHashMap<>();

    // Cached remote registry
    private volatile JsonNode remoteRegistry;
    private volatile long remoteRegistryFetchTime;
    private static final long REGISTRY_CACHE_TTL_MS = 60000; // 1 minute

    // Local registry cache
    private volatile JsonNode localRegistry;
    private volatile long localRegistryFetchTime;
    private static final long LOCAL_REGISTRY_CACHE_TTL_MS = 30000; // 30 seconds
    private static final int BUFFER_SIZE = 8192; // SHA-256 read buffer

    // Cached active selections
    private volatile Map<String, String> activeSelections;
    private volatile long activeSelectionsFetchTime;

    // Role constants
    public static final String ROLE_DENSE_RETRIEVAL = "dense_retrieval";
    public static final String ROLE_SPARSE_RETRIEVAL = "sparse_retrieval";
    public static final String ROLE_RERANKING = "reranking";

    public RegistryBasedModelManager() {
        String cacheDirEnv = System.getenv(ENV_KOMPILE_MODEL_CACHE_DIR);
        if (cacheDirEnv != null && !cacheDirEnv.trim().isEmpty()) {
            this.baseCachePath = Paths.get(cacheDirEnv.trim());
        } else {
            this.baseCachePath = Paths.get(System.getProperty("user.home"), DEFAULT_KOMPILE_MODEL_CACHE_SUBDIR);
        }
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    public Path getBaseCachePath() {
        return baseCachePath;
    }

    // ==================== Configuration ====================

    /**
     * Configure the remote staging service.
     */
    public void configureStagingService(String url, String apiKey) {
        configureStagingService(url, apiKey, 30);
    }

    /**
     * Configure the remote staging service with retry poll interval.
     *
     * @param url the staging service URL
     * @param apiKey the API key for authentication (may be null)
     * @param retryPollIntervalSeconds the interval in seconds to poll when service is unavailable
     */
    public void configureStagingService(String url, String apiKey, int retryPollIntervalSeconds) {
        this.stagingUrl = url;
        this.stagingApiKey = apiKey;
        this.stagingRetryPollIntervalSeconds = retryPollIntervalSeconds > 0 ? retryPollIntervalSeconds : 30;
        this.remoteRegistry = null; // Clear cache
        this.remoteRegistryFetchTime = 0;
        this.activeSelections = null; // Clear selections cache
        this.activeSelectionsFetchTime = 0;
    }

    /**
     * Get the configured staging URL.
     * Used for subprocess inheritance.
     */
    public String getStagingUrl() {
        return stagingUrl;
    }

    /**
     * Get the configured staging API key.
     * Used for subprocess inheritance.
     */
    public String getStagingApiKey() {
        return stagingApiKey;
    }

    /**
     * Get the configured retry poll interval in seconds.
     * This is the interval at which the application will poll when the staging service is unavailable.
     */
    public int getStagingRetryPollIntervalSeconds() {
        return stagingRetryPollIntervalSeconds;
    }

    /**
     * Get the loaded archive path.
     * Used for subprocess inheritance.
     */
    public Path getLoadedArchivePath() {
        return loadedArchivePath;
    }

    // ==================== Active Model Selection ====================

    /**
     * Get active selections from the staging service.
     * Returns a cached map if still valid.
     */
    public Map<String, String> getActiveSelections() {
        if (stagingUrl == null || stagingUrl.isBlank()) {
            return Map.of();
        }

        // Check cache
        long now = System.currentTimeMillis();
        if (activeSelections != null && (now - activeSelectionsFetchTime) < REGISTRY_CACHE_TTL_MS) {
            return Collections.unmodifiableMap(activeSelections);
        }

        String baseUrl = stagingUrl.endsWith("/") ? stagingUrl.substring(0, stagingUrl.length() - 1) : stagingUrl;
        String url = baseUrl + "/api/staging/selections";

        try {
            logger.debug("Fetching active selections from staging: {}", url);
            long startTime = System.currentTimeMillis();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(5)) // Reduced from 10 to 5 seconds
                    .GET();

            if (stagingApiKey != null && !stagingApiKey.isBlank()) {
                requestBuilder.header("X-API-Key", stagingApiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            long elapsed = System.currentTimeMillis() - startTime;
            logger.debug("Staging selections response: status={}, elapsed={}ms", response.statusCode(), elapsed);

            if (response.statusCode() == 200) {
                JsonNode node = objectMapper.readTree(response.body());
                JsonNode selections = node.get("selections");
                if (selections != null && selections.isObject()) {
                    Map<String, String> result = new HashMap<>();
                    selections.fieldNames().forEachRemaining(field -> {
                        JsonNode value = selections.get(field);
                        if (value != null && !value.isNull()) {
                            result.put(field, value.asText());
                        }
                    });
                    activeSelections = result;
                    activeSelectionsFetchTime = now;
                    logger.debug("Fetched {} active selections", result.size());
                    return result;
                }
            } else {
                logger.warn("Failed to fetch active selections from {}: HTTP {}", url, response.statusCode());
            }
        } catch (java.net.http.HttpTimeoutException e) {
            logger.warn("Timeout fetching active selections from {}: {}", url, e.getMessage());
        } catch (java.io.IOException e) {
            logger.warn("IO error fetching active selections from {}: {}", url, e.getMessage());
        } catch (InterruptedException e) {
            logger.warn("Interrupted while fetching active selections from {}", url);
            Thread.currentThread().interrupt();
        } catch (Exception e) {
            logger.warn("Error fetching active selections from {}: {}", url, e.getMessage());
        }

        return activeSelections != null ? Collections.unmodifiableMap(activeSelections) : Map.of();
    }

    /**
     * Get the selected model ID for a role.
     */
    public Optional<String> getSelectedModel(String role) {
        Map<String, String> selections = getActiveSelections();
        String modelId = selections.get(role);
        return (modelId != null && !modelId.isBlank()) ? Optional.of(modelId) : Optional.empty();
    }

    /**
     * Get the selected dense retrieval model ID.
     */
    public Optional<String> getSelectedDenseRetrievalModel() {
        return getSelectedModel(ROLE_DENSE_RETRIEVAL);
    }

    /**
     * Get the selected sparse retrieval model ID.
     */
    public Optional<String> getSelectedSparseRetrievalModel() {
        return getSelectedModel(ROLE_SPARSE_RETRIEVAL);
    }

    /**
     * Get the selected reranking model ID.
     */
    public Optional<String> getSelectedRerankingModel() {
        return getSelectedModel(ROLE_RERANKING);
    }

    /**
     * Load an archive file.
     */
    public void loadArchive(Path archivePath) throws IOException {
        if (!Files.exists(archivePath)) {
            throw new IOException("Archive not found: " + archivePath);
        }
        this.loadedArchivePath = archivePath;
        this.archiveRegistry = readArchiveRegistry(archivePath);
    }

    /**
     * Clear the loaded archive.
     */
    public void clearArchive() {
        this.loadedArchivePath = null;
        this.archiveRegistry = null;
        bundleCache.clear();
        crossEncoderCache.clear();
    }

    /**
     * Check if a source is configured (staging or archive).
     */
    public boolean isConfigured() {
        return (stagingUrl != null && !stagingUrl.isBlank()) || loadedArchivePath != null || getLocalRegistry() != null;
    }

    /**
     * Get the configured source type.
     */
    public String getSourceType() {
        if (loadedArchivePath != null) {
            return "archive";
        } else if (stagingUrl != null && !stagingUrl.isBlank()) {
            return "staging";
        } else if (getLocalRegistry() != null) {
            return "local";
        }
        return null;
    }

    // ==================== Model Queries ====================

    /**
     * Get a model entry by ID.
     */
    public Optional<ModelEntry> getModelEntry(String modelId) {
        logger.debug("Looking up model: {}", modelId);

        // Check archive first
        if (archiveRegistry != null) {
            JsonNode models = archiveRegistry.get("models");
            if (models != null && models.has(modelId)) {
                logger.debug("Found model {} in archive registry", modelId);
                return Optional.of(jsonToModelEntry(modelId, models.get(modelId)));
            }
        }

        // Check local registry (~/.kompile/models/registry.json)
        JsonNode local = getLocalRegistry();
        if (local != null) {
            JsonNode models = local.get("models");
            if (models != null && models.has(modelId)) {
                JsonNode modelNode = models.get(modelId);
                // Only return active models from local registry
                String status = modelNode.has("status") ? modelNode.get("status").asText() : "active";
                if ("active".equalsIgnoreCase(status)) {
                    logger.debug("Found model {} in local registry", modelId);
                    return Optional.of(jsonToModelEntry(modelId, modelNode));
                }
            }
        }

        // Check remote staging
        JsonNode registry = getRemoteRegistry();
        if (registry != null) {
            JsonNode models = registry.get("models");
            if (models != null) {
                logger.debug("Remote registry has {} models, looking for '{}'",
                        models.size(), modelId);
                if (models.has(modelId)) {
                    logger.debug("Found model {} in remote registry", modelId);
                    return Optional.of(jsonToModelEntry(modelId, models.get(modelId)));
                } else {
                    // Log available models for debugging
                    List<String> availableIds = new ArrayList<>();
                    models.fieldNames().forEachRemaining(availableIds::add);
                    logger.debug("Model '{}' not found. Available models: {}", modelId, availableIds);
                }
            } else {
                logger.debug("Remote registry has no 'models' field");
            }
        } else {
            logger.debug("No remote registry available (stagingUrl={}, cached={})",
                    stagingUrl, remoteRegistry != null);
        }

        return Optional.empty();
    }

    /**
     * List all encoder model IDs.
     */
    public List<String> listEncoderModelIds() {
        List<String> ids = new ArrayList<>();

        // From archive
        if (archiveRegistry != null) {
            JsonNode models = archiveRegistry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    JsonNode model = models.get(id);
                    String type = model.has("type") ? model.get("type").asText() : "";
                    if (type.contains("encoder") && !type.contains("cross")) {
                        ids.add(id);
                    }
                });
            }
        }

        // From local registry
        JsonNode local = getLocalRegistry();
        if (local != null) {
            JsonNode models = local.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    if (!ids.contains(id)) {
                        JsonNode model = models.get(id);
                        String type = model.has("type") ? model.get("type").asText() : "";
                        String status = model.has("status") ? model.get("status").asText() : "active";
                        if ("active".equalsIgnoreCase(status) && type.contains("encoder") && !type.contains("cross")) {
                            ids.add(id);
                        }
                    }
                });
            }
        }

        // From remote staging
        JsonNode registry = getRemoteRegistry();
        if (registry != null) {
            JsonNode models = registry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    if (!ids.contains(id)) {
                        JsonNode model = models.get(id);
                        String type = model.has("type") ? model.get("type").asText() : "";
                        if (type.contains("encoder") && !type.contains("cross")) {
                            ids.add(id);
                        }
                    }
                });
            }
        }

        return ids;
    }

    /**
     * List all cross-encoder model IDs.
     */
    public List<String> listCrossEncoderModelIds() {
        List<String> ids = new ArrayList<>();

        // From archive
        if (archiveRegistry != null) {
            JsonNode models = archiveRegistry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    JsonNode model = models.get(id);
                    String type = model.has("type") ? model.get("type").asText() : "";
                    if (type.contains("cross_encoder") || type.contains("reranker")) {
                        ids.add(id);
                    }
                });
            }
        }

        // From local registry
        JsonNode local = getLocalRegistry();
        if (local != null) {
            JsonNode models = local.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    if (!ids.contains(id)) {
                        JsonNode model = models.get(id);
                        String type = model.has("type") ? model.get("type").asText() : "";
                        String status = model.has("status") ? model.get("status").asText() : "active";
                        if ("active".equalsIgnoreCase(status) && (type.contains("cross_encoder") || type.contains("reranker"))) {
                            ids.add(id);
                        }
                    }
                });
            }
        }

        // From remote staging
        JsonNode registry = getRemoteRegistry();
        if (registry != null) {
            JsonNode models = registry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    if (!ids.contains(id)) {
                        JsonNode model = models.get(id);
                        String type = model.has("type") ? model.get("type").asText() : "";
                        if (type.contains("cross_encoder") || type.contains("reranker")) {
                            ids.add(id);
                        }
                    }
                });
            }
        }

        return ids;
    }

    /**
     * Check if an encoder model is available.
     */
    public boolean isEncoderModelAvailable(String modelId) {
        Optional<ModelEntry> entry = getModelEntry(modelId);
        if (entry.isEmpty()) return false;
        String type = entry.get().type;
        return type != null && type.contains("encoder") && !type.contains("cross");
    }

    /**
     * Check if a cross-encoder model is available.
     */
    public boolean isCrossEncoderModelAvailable(String modelId) {
        Optional<ModelEntry> entry = getModelEntry(modelId);
        if (entry.isEmpty()) return false;
        String type = entry.get().type;
        return type != null && (type.contains("cross_encoder") || type.contains("reranker"));
    }

    /**
     * Get the model type.
     */
    public String getModelType(String modelId) {
        return getModelEntry(modelId)
                .map(e -> e.metadata != null ? e.metadata.modelType : null)
                .orElse(null);
    }

    /**
     * Get the embedding dimension.
     */
    public Integer getEmbeddingDimension(String modelId) {
        return getModelEntry(modelId)
                .map(e -> e.metadata != null ? e.metadata.embeddingDim : null)
                .orElse(null);
    }

    /**
     * Get the max sequence length.
     */
    public Integer getMaxSequenceLength(String modelId) {
        return getModelEntry(modelId)
                .map(e -> e.metadata != null ? e.metadata.maxSequenceLength : 512)
                .orElse(512);
    }

    /**
     * Get models by RAG role (retrieval, reranking).
     */
    public List<ModelEntry> getModelsByRagRole(String role) {
        List<ModelEntry> result = new ArrayList<>();

        // From archive
        if (archiveRegistry != null) {
            JsonNode models = archiveRegistry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    JsonNode model = models.get(id);
                    JsonNode metadata = model.get("metadata");
                    if (metadata != null && metadata.has("rag_role")) {
                        String ragRole = metadata.get("rag_role").asText();
                        if (role.equalsIgnoreCase(ragRole)) {
                            result.add(jsonToModelEntry(id, model));
                        }
                    }
                });
            }
        }

        // From local registry
        JsonNode local = getLocalRegistry();
        if (local != null) {
            JsonNode models = local.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    if (result.stream().noneMatch(e -> id.equals(e.modelId))) {
                        JsonNode model = models.get(id);
                        String status = model.has("status") ? model.get("status").asText() : "active";
                        if ("active".equalsIgnoreCase(status)) {
                            JsonNode metadata = model.get("metadata");
                            if (metadata != null && metadata.has("rag_role")) {
                                String ragRole = metadata.get("rag_role").asText();
                                if (role.equalsIgnoreCase(ragRole)) {
                                    result.add(jsonToModelEntry(id, model));
                                }
                            }
                        }
                    }
                });
            }
        }

        // From remote staging
        JsonNode registry = getRemoteRegistry();
        if (registry != null) {
            JsonNode models = registry.get("models");
            if (models != null) {
                models.fieldNames().forEachRemaining(id -> {
                    // Skip if already added from archive or local
                    if (result.stream().noneMatch(e -> id.equals(e.modelId))) {
                        JsonNode model = models.get(id);
                        JsonNode metadata = model.get("metadata");
                        if (metadata != null && metadata.has("rag_role")) {
                            String ragRole = metadata.get("rag_role").asText();
                            if (role.equalsIgnoreCase(ragRole)) {
                                result.add(jsonToModelEntry(id, model));
                            }
                        }
                    }
                });
            }
        }

        return result;
    }

    // ==================== Model Bundle Loading ====================

    /**
     * Get an encoder model bundle.
     */
    public KompileModelManager.ModelBundle getEncoderModelBundle(String modelId) {
        // Check cache — use putIfAbsent-style to avoid TOCTOU race
        KompileModelManager.ModelBundle cached = bundleCache.get(modelId);
        if (cached != null) {
            return cached;
        }

        Optional<ModelEntry> entryOpt = getModelEntry(modelId);
        if (entryOpt.isEmpty()) {
            return null;
        }

        try {
            KompileModelManager.ModelBundle bundle = loadModelBundle(modelId, entryOpt.get());
            if (bundle != null) {
                KompileModelManager.ModelBundle existing = bundleCache.putIfAbsent(modelId, bundle);
                return existing != null ? existing : bundle;
            }
            return bundle;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Get a cross-encoder model bundle.
     */
    public KompileModelManager.CrossEncoderBundle getCrossEncoderModelBundle(String modelId) {
        // Check cache — use putIfAbsent-style to avoid TOCTOU race
        KompileModelManager.CrossEncoderBundle cached = crossEncoderCache.get(modelId);
        if (cached != null) {
            return cached;
        }

        Optional<ModelEntry> entryOpt = getModelEntry(modelId);
        if (entryOpt.isEmpty()) {
            return null;
        }

        try {
            KompileModelManager.CrossEncoderBundle bundle = loadCrossEncoderBundle(modelId, entryOpt.get());
            if (bundle != null) {
                KompileModelManager.CrossEncoderBundle existing = crossEncoderCache.putIfAbsent(modelId, bundle);
                return existing != null ? existing : bundle;
            }
            return bundle;
        } catch (IOException e) {
            return null;
        }
    }

    /**
     * Clear caches.
     */
    public void clearCaches() {
        bundleCache.clear();
        crossEncoderCache.clear();
        remoteRegistry = null;
        remoteRegistryFetchTime = 0;
        localRegistry = null;
        localRegistryFetchTime = 0;
    }

    /**
     * Refresh - clears caches to force re-fetch.
     */
    public void refresh() {
        clearCaches();
    }

    /**
     * Alias for refresh() for backward compatibility.
     */
    public void refreshRegistry() {
        refresh();
    }

    /**
     * Update the optimization status for a model.
     * If staging service is configured, updates the remote registry.
     * Otherwise, logs that optimization status is only persisted in memory until restart.
     *
     * @param modelId The model identifier
     * @param optimized Whether the model is now optimized
     * @param backupFile Path to the unoptimized backup file (null to clear)
     * @param optimizationTimeMs Time taken to optimize in ms (null if not optimized)
     */
    public void updateModelOptimizationStatus(String modelId, boolean optimized,
                                               String backupFile, Long optimizationTimeMs) {
        logger.info("Updating optimization status for model {}: optimized={}, backupFile={}",
                modelId, optimized, backupFile);

        // Update remote registry if staging is configured
        if (stagingUrl != null && !stagingUrl.isBlank()) {
            try {
                updateRemoteOptimizationStatus(modelId, optimized, backupFile, optimizationTimeMs);
            } catch (Exception e) {
                logger.warn("Failed to update optimization status in remote registry: {}", e.getMessage());
            }
        } else {
            logger.info("No staging service configured - optimization status will not persist across restarts");
        }

        // Clear cache to force reload and pick up changes
        clearCaches();
    }

    /**
     * Update optimization status in the remote staging service.
     */
    private void updateRemoteOptimizationStatus(String modelId, boolean optimized,
                                                  String backupFile, Long optimizationTimeMs) {
        try {
            String baseUrl = stagingUrl.endsWith("/") ? stagingUrl.substring(0, stagingUrl.length() - 1) : stagingUrl;
            String url = baseUrl + "/api/staging/registry/model/" + modelId + "/optimization-status";

            Map<String, Object> body = new java.util.LinkedHashMap<>();
            body.put("optimized", optimized);
            body.put("backupFile", backupFile);
            body.put("optimizationTimeMs", optimizationTimeMs);

            String bodyJson = objectMapper.writeValueAsString(body);

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(bodyJson));

            if (stagingApiKey != null && !stagingApiKey.isBlank()) {
                requestBuilder.header("Authorization", "Bearer " + stagingApiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() >= 200 && response.statusCode() < 300) {
                logger.info("Updated optimization status in remote registry for model: {}", modelId);
            } else {
                logger.warn("Failed to update optimization status in remote registry: {} - {}",
                        response.statusCode(), response.body());
            }
        } catch (Exception e) {
            logger.warn("Error updating remote optimization status: {}", e.getMessage());
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Read the local registry file (~/.kompile/models/registry.json).
     */
    private JsonNode getLocalRegistry() {
        long now = System.currentTimeMillis();
        if (localRegistry != null && (now - localRegistryFetchTime) < LOCAL_REGISTRY_CACHE_TTL_MS) {
            return localRegistry;
        }

        Path registryPath = baseCachePath.resolve("registry.json");
        if (!Files.exists(registryPath)) {
            return null;
        }

        try {
            localRegistry = objectMapper.readTree(registryPath.toFile());
            localRegistryFetchTime = now;
            logger.debug("Loaded local registry with {} models",
                    localRegistry.has("models") ? localRegistry.get("models").size() : 0);
            return localRegistry;
        } catch (IOException e) {
            logger.warn("Failed to read local registry: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode getRemoteRegistry() {
        if (stagingUrl == null || stagingUrl.isBlank()) {
            return null;
        }

        // Check cache
        long now = System.currentTimeMillis();
        if (remoteRegistry != null && (now - remoteRegistryFetchTime) < REGISTRY_CACHE_TTL_MS) {
            return remoteRegistry;
        }

        // Use /api/staging/registry to match StagingClientService
        String baseUrl = stagingUrl.endsWith("/") ? stagingUrl.substring(0, stagingUrl.length() - 1) : stagingUrl;
        String url = baseUrl + "/api/staging/registry";

        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofSeconds(10))
                    .GET();

            if (stagingApiKey != null && !stagingApiKey.isBlank()) {
                requestBuilder.header("X-API-Key", stagingApiKey);
            }

            HttpResponse<String> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() == 200) {
                remoteRegistry = objectMapper.readTree(response.body());
                remoteRegistryFetchTime = now;
                logger.debug("Successfully fetched registry from staging service: {} models",
                        remoteRegistry.has("models") ? remoteRegistry.get("models").size() : 0);
                return remoteRegistry;
            } else {
                logger.warn("Staging service returned status {}: {}", response.statusCode(), response.body());
            }
        } catch (java.net.ConnectException e) {
            logger.warn("Cannot connect to staging service at {}: {}", url, e.getMessage());
        } catch (java.net.http.HttpTimeoutException e) {
            logger.warn("Timeout connecting to staging service at {}: {}", url, e.getMessage());
        } catch (Exception e) {
            logger.warn("Error fetching registry from staging service at {}: {} - {}",
                    url, e.getClass().getSimpleName(), e.getMessage());
        }

        return remoteRegistry;
    }

    private JsonNode readArchiveRegistry(Path archivePath) throws IOException {
        try (ZipFile zip = new ZipFile(archivePath.toFile())) {
            ZipEntry registryEntry = zip.getEntry("registry.json");
            if (registryEntry == null) {
                throw new IOException("Archive does not contain registry.json");
            }
            try (InputStream is = zip.getInputStream(registryEntry)) {
                return objectMapper.readTree(is);
            }
        }
    }

    private KompileModelManager.ModelBundle loadModelBundle(String modelId, ModelEntry entry) throws IOException {
        // FIRST: Check if files already exist locally (downloaded via UI or CLI)
        KompileModelManager.ModelBundle localBundle = loadBundleFromLocalCache(modelId, entry);
        if (localBundle != null) {
            // Verify checksum matches if registry has one (detects optimized/updated models)
            if (entry.checksum != null && !entry.checksum.isBlank() && stagingUrl != null) {
                if (!verifyLocalChecksum(localBundle.getModelPath(), entry.checksum)) {
                    logger.info("Model {} checksum mismatch - remote version is different (possibly optimized). Re-downloading.", modelId);
                    // Delete old files and re-download
                    try {
                        Files.deleteIfExists(localBundle.getModelPath());
                        Files.deleteIfExists(localBundle.getVocabularyPath());
                    } catch (IOException e) {
                        logger.warn("Failed to delete old model files: {}", e.getMessage());
                    }
                    // Fall through to re-download from staging
                } else {
                    return localBundle;
                }
            } else {
                return localBundle;
            }
        }

        // If from archive, extract files
        if (loadedArchivePath != null && archiveRegistry != null) {
            return loadBundleFromArchive(modelId, entry);
        }

        // If from remote staging, download files
        if (stagingUrl != null) {
            return loadBundleFromStaging(modelId, entry);
        }

        return null;
    }

    /**
     * Verify that a local file matches the expected checksum from the registry.
     *
     * @param filePath Path to the local file
     * @param expectedChecksum Expected checksum (format: "sha256:hexstring" or just "hexstring")
     * @return true if checksum matches, false otherwise
     */
    private boolean verifyLocalChecksum(Path filePath, String expectedChecksum) {
        if (!Files.exists(filePath)) {
            return false;
        }

        try {
            String localChecksum = calculateSha256(filePath);
            // Handle both "sha256:hexstring" and plain "hexstring" formats
            String expected = expectedChecksum.startsWith("sha256:")
                    ? expectedChecksum.substring(7)
                    : expectedChecksum;
            boolean matches = localChecksum.equalsIgnoreCase(expected);
            if (!matches) {
                logger.debug("Checksum mismatch for {}: local={}, expected={}",
                        filePath.getFileName(), localChecksum, expected);
            }
            return matches;
        } catch (IOException e) {
            logger.warn("Failed to calculate checksum for {}: {}", filePath, e.getMessage());
            return false; // Assume mismatch if we can't calculate
        }
    }

    /**
     * Calculate SHA256 checksum of a file.
     */
    private String calculateSha256(Path file) throws IOException {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            try (InputStream is = Files.newInputStream(file);
                 BufferedInputStream bis = new BufferedInputStream(is, BUFFER_SIZE)) {
                byte[] buffer = new byte[BUFFER_SIZE];
                int bytesRead;
                while ((bytesRead = bis.read(buffer)) != -1) {
                    digest.update(buffer, 0, bytesRead);
                }
            }
            byte[] hash = digest.digest();
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IOException("SHA-256 algorithm not available", e);
        }
    }

    /**
     * Load model bundle from local cache if files already exist.
     * This supports models downloaded via the UI or CLI.
     */
    private KompileModelManager.ModelBundle loadBundleFromLocalCache(String modelId, ModelEntry entry) {
        Path modelDir = baseCachePath.resolve(modelId);
        // Also check entry.path (e.g. "encoders/bge-base-en-v1.5") which is the staging registry path
        if (!Files.exists(modelDir) && entry.path != null && !entry.path.isBlank()) {
            Path altDir = baseCachePath.resolve(entry.path);
            if (Files.exists(altDir)) {
                modelDir = altDir;
            }
        }
        if (!Files.exists(modelDir)) {
            return null;
        }

        String modelFile = entry.modelFile != null ? entry.modelFile : "model.sdz";
        String vocabFile = entry.vocabFile != null ? entry.vocabFile : "vocab.txt";

        Path modelPath = modelDir.resolve(modelFile);
        Path vocabPath = modelDir.resolve(vocabFile);

        // Check if model file exists (vocab is optional for some models)
        if (!Files.exists(modelPath)) {
            return null;
        }

        // If vocab doesn't exist, it might be okay for some model types
        if (!Files.exists(vocabPath)) {
            // Try common vocab file names
            Path altVocab = modelDir.resolve("vocab.txt");
            if (Files.exists(altVocab)) {
                vocabPath = altVocab;
            } else {
                altVocab = modelDir.resolve("tokenizer.json");
                if (Files.exists(altVocab)) {
                    vocabPath = altVocab;
                } else {
                    // No vocab file found - some models may still work
                    vocabPath = null;
                }
            }
        }

        // If no vocab file at all, we can't load the model
        if (vocabPath == null || !Files.exists(vocabPath)) {
            return null;
        }

        TokenizerConfig config = createTokenizerConfig(entry);
        Map<String, Object> metadata = new HashMap<>();
        if (entry.metadata != null) {
            if (entry.metadata.embeddingDim != null) metadata.put("embedding_dim", entry.metadata.embeddingDim);
            if (entry.metadata.modelType != null) metadata.put("model_type", entry.metadata.modelType);
            if (entry.metadata.encoderType != null) metadata.put("encoder_type", entry.metadata.encoderType);
        }

        return new KompileModelManager.ModelBundle(modelId, modelPath, vocabPath, metadata, config);
    }

    private KompileModelManager.ModelBundle loadBundleFromArchive(String modelId, ModelEntry entry) throws IOException {
        Path extractDir = baseCachePath.resolve(modelId);
        Files.createDirectories(extractDir);

        String modelFile = entry.modelFile != null ? entry.modelFile : "model.sdz";
        String vocabFile = entry.vocabFile != null ? entry.vocabFile : "vocab.txt";
        String basePath = entry.path != null ? entry.path : modelId;

        Path modelPath = extractDir.resolve(modelFile);
        Path vocabPath = extractDir.resolve(vocabFile);

        try (ZipFile zip = new ZipFile(loadedArchivePath.toFile())) {
            // Extract model file
            ZipEntry modelEntry = zip.getEntry(basePath + "/" + modelFile);
            if (modelEntry == null) {
                modelEntry = zip.getEntry(modelId + "/" + modelFile);
            }
            if (modelEntry != null && !Files.exists(modelPath)) {
                try (InputStream is = zip.getInputStream(modelEntry)) {
                    Files.copy(is, modelPath);
                }
            }

            // Extract vocab file
            ZipEntry vocabEntry = zip.getEntry(basePath + "/" + vocabFile);
            if (vocabEntry == null) {
                vocabEntry = zip.getEntry(modelId + "/" + vocabFile);
            }
            if (vocabEntry != null && !Files.exists(vocabPath)) {
                try (InputStream is = zip.getInputStream(vocabEntry)) {
                    Files.copy(is, vocabPath);
                }
            }
        }

        if (!Files.exists(modelPath) || !Files.exists(vocabPath)) {
            return null;
        }

        TokenizerConfig config = createTokenizerConfig(entry);
        Map<String, Object> metadata = new HashMap<>();
        if (entry.metadata != null) {
            if (entry.metadata.embeddingDim != null) metadata.put("embedding_dim", entry.metadata.embeddingDim);
            if (entry.metadata.modelType != null) metadata.put("model_type", entry.metadata.modelType);
            if (entry.metadata.encoderType != null) metadata.put("encoder_type", entry.metadata.encoderType);
        }
        return new KompileModelManager.ModelBundle(modelId, modelPath, vocabPath, metadata, config);
    }

    private KompileModelManager.ModelBundle loadBundleFromStaging(String modelId, ModelEntry entry) throws IOException {
        Path extractDir = baseCachePath.resolve(modelId);
        Files.createDirectories(extractDir);

        String modelFile = entry.modelFile != null ? entry.modelFile : "model.sdz";
        String vocabFile = entry.vocabFile != null ? entry.vocabFile : "vocab.txt";

        Path modelPath = extractDir.resolve(modelFile);
        Path vocabPath = extractDir.resolve(vocabFile);

        // Use correct endpoint path: /api/staging/registry/model/{modelId}/download/model
        String baseUrl = stagingUrl.endsWith("/") ? stagingUrl.substring(0, stagingUrl.length() - 1) : stagingUrl;

        // Download model file if not cached
        if (!Files.exists(modelPath)) {
            downloadFile(baseUrl + "/api/staging/registry/model/" + modelId + "/download/model", modelPath);
        }

        // Download vocab file if not cached
        if (!Files.exists(vocabPath)) {
            downloadFile(baseUrl + "/api/staging/registry/model/" + modelId + "/download/vocab", vocabPath);
        }

        if (!Files.exists(modelPath) || !Files.exists(vocabPath)) {
            return null;
        }

        TokenizerConfig config = createTokenizerConfig(entry);
        Map<String, Object> metadata = new HashMap<>();
        if (entry.metadata != null) {
            if (entry.metadata.embeddingDim != null) metadata.put("embedding_dim", entry.metadata.embeddingDim);
            if (entry.metadata.modelType != null) metadata.put("model_type", entry.metadata.modelType);
            if (entry.metadata.encoderType != null) metadata.put("encoder_type", entry.metadata.encoderType);
        }
        return new KompileModelManager.ModelBundle(modelId, modelPath, vocabPath, metadata, config);
    }

    private KompileModelManager.CrossEncoderBundle loadCrossEncoderBundle(String modelId, ModelEntry entry) throws IOException {
        // Similar to encoder bundle loading
        KompileModelManager.ModelBundle baseBundle = loadModelBundle(modelId, entry);
        if (baseBundle == null) {
            return null;
        }
        Map<String, Object> metadata = new HashMap<>();
        if (entry.metadata != null) {
            if (entry.metadata.embeddingDim != null) metadata.put("embedding_dim", entry.metadata.embeddingDim);
            if (entry.metadata.modelType != null) metadata.put("model_type", entry.metadata.modelType);
            if (entry.metadata.encoderType != null) metadata.put("encoder_type", entry.metadata.encoderType);
        }
        return new KompileModelManager.CrossEncoderBundle(
                modelId,
                baseBundle.getModelPath(),
                baseBundle.getVocabularyPath(),
                metadata,
                baseBundle.getTokenizerConfig()
        );
    }

    private void downloadFile(String url, Path destination) throws IOException {
        try {
            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .timeout(Duration.ofMinutes(5))
                    .GET();

            if (stagingApiKey != null && !stagingApiKey.isBlank()) {
                requestBuilder.header("X-API-Key", stagingApiKey);
            }

            HttpResponse<InputStream> response = httpClient.send(requestBuilder.build(),
                    HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() == 200) {
                try (InputStream is = response.body()) {
                    Files.copy(is, destination);
                }
            } else {
                throw new IOException("Failed to download " + url + ": HTTP " + response.statusCode());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Download interrupted", e);
        }
    }

    private TokenizerConfig createTokenizerConfig(ModelEntry entry) {
        if (entry.tokenizer != null) {
            return TokenizerConfig.builder()
                    .doLowerCase(entry.tokenizer.doLowerCase)
                    .addSpecialTokens(entry.tokenizer.addSpecialTokens)
                    .stripAccents(entry.tokenizer.stripAccents)
                    .maxSequenceLength(entry.tokenizer.maxLength > 0 ? entry.tokenizer.maxLength : 512)
                    .build();
        }
        return TokenizerConfig.defaultConfig();
    }

    private ModelEntry jsonToModelEntry(String modelId, JsonNode json) {
        ModelEntry entry = new ModelEntry();
        entry.modelId = modelId;
        entry.type = json.has("type") ? json.get("type").asText() : null;
        entry.path = json.has("path") ? json.get("path").asText() : null;
        entry.modelFile = json.has("model_file") ? json.get("model_file").asText() : "model.sdz";
        entry.vocabFile = json.has("vocab_file") ? json.get("vocab_file").asText() : "vocab.txt";
        entry.checksum = json.has("checksum") ? json.get("checksum").asText() : null;
        entry.status = json.has("status") ? json.get("status").asText() : "active";

        JsonNode metadataNode = json.get("metadata");
        if (metadataNode != null && metadataNode.isObject()) {
            ModelMetadata metadata = new ModelMetadata();
            metadata.embeddingDim = metadataNode.has("embedding_dim") ? metadataNode.get("embedding_dim").asInt() : null;
            metadata.hiddenSize = metadataNode.has("hidden_size") ? metadataNode.get("hidden_size").asInt() : null;
            metadata.numLayers = metadataNode.has("num_layers") ? metadataNode.get("num_layers").asInt() : null;
            metadata.maxSequenceLength = metadataNode.has("max_sequence_length") ? metadataNode.get("max_sequence_length").asInt() : 512;
            metadata.modelType = metadataNode.has("model_type") ? metadataNode.get("model_type").asText() : null;
            metadata.encoderType = metadataNode.has("encoder_type") ? metadataNode.get("encoder_type").asText() : null;
            metadata.framework = metadataNode.has("framework") ? metadataNode.get("framework").asText() : null;
            metadata.description = metadataNode.has("description") ? metadataNode.get("description").asText() : null;
            metadata.ragRole = metadataNode.has("rag_role") ? metadataNode.get("rag_role").asText() : null;
            entry.metadata = metadata;
        }

        JsonNode tokenizerNode = json.get("tokenizer");
        if (tokenizerNode != null && tokenizerNode.isObject()) {
            TokenizerConfigDTO tokenizer = new TokenizerConfigDTO();
            tokenizer.doLowerCase = !tokenizerNode.has("do_lower_case") || tokenizerNode.get("do_lower_case").asBoolean(true);
            tokenizer.addSpecialTokens = !tokenizerNode.has("add_special_tokens") || tokenizerNode.get("add_special_tokens").asBoolean(true);
            tokenizer.stripAccents = !tokenizerNode.has("strip_accents") || tokenizerNode.get("strip_accents").asBoolean(true);
            tokenizer.maxLength = tokenizerNode.has("max_length") ? tokenizerNode.get("max_length").asInt() : 512;
            entry.tokenizer = tokenizer;
        }

        return entry;
    }

    // ==================== Inner Classes (DTOs) ====================

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelEntry {
        @JsonProperty("model_id")
        public String modelId;

        @JsonProperty("type")
        public String type;

        @JsonProperty("path")
        public String path;

        @JsonProperty("model_file")
        public String modelFile = "model.sdz";

        @JsonProperty("vocab_file")
        public String vocabFile = "vocab.txt";

        @JsonProperty("checksum")
        public String checksum;

        @JsonProperty("status")
        public String status = "active";

        @JsonProperty("metadata")
        public ModelMetadata metadata;

        @JsonProperty("tokenizer")
        public TokenizerConfigDTO tokenizer;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ModelMetadata {
        @JsonProperty("embedding_dim")
        public Integer embeddingDim;

        @JsonProperty("hidden_size")
        public Integer hiddenSize;

        @JsonProperty("num_layers")
        public Integer numLayers;

        @JsonProperty("max_sequence_length")
        public int maxSequenceLength = 512;

        @JsonProperty("model_type")
        public String modelType;

        @JsonProperty("encoder_type")
        public String encoderType;

        @JsonProperty("framework")
        public String framework = "samediff";

        @JsonProperty("description")
        public String description;

        @JsonProperty("rag_role")
        public String ragRole;

        // Optimization fields
        @JsonProperty("optimized")
        public Boolean optimized;

        @JsonProperty("optimized_at")
        public String optimizedAt;

        @JsonProperty("optimization_time_ms")
        public Long optimizationTimeMs;

        @JsonProperty("unoptimized_backup_file")
        public String unoptimizedBackupFile;
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenizerConfigDTO {
        @JsonProperty("do_lower_case")
        public boolean doLowerCase = true;

        @JsonProperty("add_special_tokens")
        public boolean addSpecialTokens = true;

        @JsonProperty("strip_accents")
        public boolean stripAccents = true;

        @JsonProperty("max_length")
        public int maxLength = 512;
    }
}

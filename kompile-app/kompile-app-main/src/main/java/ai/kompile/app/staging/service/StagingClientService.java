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

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
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

    public StagingClientService(StagingServiceConfigService configService, ObjectMapper objectMapper) {
        this.configService = configService;
        this.objectMapper = objectMapper;
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
            log.error("Connection test failed for {}: {}", config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to get registry from {}: {}", config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to get catalog from {}: {}", config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to get staging status from {}: {}", config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to stage model {} on {}: {}", modelId, config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to promote model {} on {}: {}", modelId, config.getEndpointUrl(), e.getMessage());
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
            log.error("Failed to get model {} from {}: {}", modelId, config.getEndpointUrl(), e.getMessage());
        }
        return Optional.empty();
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
}

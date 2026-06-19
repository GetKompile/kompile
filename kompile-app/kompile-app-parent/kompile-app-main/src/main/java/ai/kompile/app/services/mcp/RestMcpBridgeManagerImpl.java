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

package ai.kompile.app.services.mcp;

import ai.kompile.cli.common.util.JsonUtils;
import ai.kompile.core.mcp.bridge.EndpointTestResult;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig;
import ai.kompile.core.mcp.bridge.RestMcpBridgeConfig.*;
import ai.kompile.core.mcp.bridge.RestMcpBridgeManager;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Stream;

/**
 * Implementation of RestMcpBridgeManager.
 * Manages REST-MCP bridges including OpenAPI discovery and runtime bridging.
 */
@Service
public class RestMcpBridgeManagerImpl implements RestMcpBridgeManager {

    private static final Logger logger = LoggerFactory.getLogger(RestMcpBridgeManagerImpl.class);

    private final Map<String, RestMcpBridgeConfig> bridges = new ConcurrentHashMap<>();
    private final Map<String, RestMcpBridgeRuntime> runningBridges = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    @Value("${kompile.mcp.bridges.config-dir:./data/mcp-bridges}")
    private String configDirectory;

    public RestMcpBridgeManagerImpl() {
        this.objectMapper = JsonUtils.standardMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    @PostConstruct
    public void init() {
        loadConfigurationsFromDisk();
    }

    @PreDestroy
    public void shutdown() {
        runningBridges.keySet().forEach(this::stopBridge);
    }

    @Override
    public RestMcpBridgeConfig createBridge(RestMcpBridgeConfig config) {
        String id = config.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            config.setId(id);
        }

        if (bridges.containsKey(id)) {
            throw new IllegalArgumentException("Bridge with ID " + id + " already exists");
        }

        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        config.setStatus(BridgeStatus.STOPPED);

        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        bridges.put(id, config);
        persistConfiguration(config);

        logger.info("Created REST-MCP bridge: {} ({})", config.getName(), id);
        return config;
    }

    @Override
    public RestMcpBridgeConfig updateBridge(String id, RestMcpBridgeConfig config) {
        if (!bridges.containsKey(id)) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        RestMcpBridgeConfig existing = bridges.get(id);
        if (existing.getStatus() == BridgeStatus.RUNNING) {
            throw new IllegalStateException("Cannot update running bridge. Stop it first.");
        }

        config.setId(id);
        config.setCreatedAt(existing.getCreatedAt());
        config.setUpdatedAt(Instant.now());

        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        bridges.put(id, config);
        persistConfiguration(config);

        logger.info("Updated REST-MCP bridge: {} ({})", config.getName(), id);
        return config;
    }

    @Override
    public void deleteBridge(String id) {
        if (!bridges.containsKey(id)) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        RestMcpBridgeConfig config = bridges.get(id);
        if (config.getStatus() == BridgeStatus.RUNNING) {
            throw new IllegalStateException("Cannot delete running bridge. Stop it first.");
        }

        bridges.remove(id);
        deleteConfigurationFile(id);

        logger.info("Deleted REST-MCP bridge: {}", id);
    }

    @Override
    public Optional<RestMcpBridgeConfig> getBridge(String id) {
        return Optional.ofNullable(bridges.get(id));
    }

    @Override
    public List<RestMcpBridgeConfig> listBridges() {
        return new ArrayList<>(bridges.values());
    }

    @Override
    public RestMcpBridgeConfig startBridge(String id) {
        RestMcpBridgeConfig config = bridges.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        if (config.getStatus() == BridgeStatus.RUNNING) {
            throw new IllegalStateException("Bridge is already running");
        }

        config.setStatus(BridgeStatus.STARTING);
        bridges.put(id, config);

        try {
            RestMcpBridgeRuntime runtime = new RestMcpBridgeRuntime(config, objectMapper, httpClient);
            runtime.start();
            runningBridges.put(id, runtime);

            config.setStatus(BridgeStatus.RUNNING);
            config.setUpdatedAt(Instant.now());
            bridges.put(id, config);
            persistConfiguration(config);

            logger.info("Started REST-MCP bridge: {}", config.getName());
        } catch (Exception e) {
            config.setStatus(BridgeStatus.ERROR);
            bridges.put(id, config);
            logger.error("Failed to start bridge: {}", config.getName(), e);
            throw new RuntimeException("Failed to start bridge: " + e.getMessage(), e);
        }

        return config;
    }

    @Override
    public RestMcpBridgeConfig stopBridge(String id) {
        RestMcpBridgeConfig config = bridges.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        if (config.getStatus() != BridgeStatus.RUNNING) {
            throw new IllegalStateException("Bridge is not running");
        }

        try {
            RestMcpBridgeRuntime runtime = runningBridges.remove(id);
            if (runtime != null) {
                runtime.stop();
            }

            config.setStatus(BridgeStatus.STOPPED);
            config.setUpdatedAt(Instant.now());
            bridges.put(id, config);
            persistConfiguration(config);

            logger.info("Stopped REST-MCP bridge: {}", config.getName());
        } catch (Exception e) {
            config.setStatus(BridgeStatus.ERROR);
            bridges.put(id, config);
            logger.error("Failed to stop bridge: {}", config.getName(), e);
            throw new RuntimeException("Failed to stop bridge: " + e.getMessage(), e);
        }

        return config;
    }

    @Override
    public BridgeStatus getBridgeStatus(String id) {
        RestMcpBridgeConfig config = bridges.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }
        return config.getStatus();
    }

    @Override
    public List<EndpointMapping> discoverEndpoints(String openApiUrl) {
        List<EndpointMapping> mappings = new ArrayList<>();

        try {
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(openApiUrl))
                    .GET()
                    .build();

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

            if (response.statusCode() != 200) {
                throw new RuntimeException("Failed to fetch OpenAPI spec: HTTP " + response.statusCode());
            }

            JsonNode spec = objectMapper.readTree(response.body());
            mappings = parseOpenApiSpec(spec);

            logger.info("Discovered {} endpoints from OpenAPI spec: {}", mappings.size(), openApiUrl);
        } catch (Exception e) {
            logger.error("Failed to discover endpoints from OpenAPI: {}", openApiUrl, e);
            throw new RuntimeException("Failed to discover endpoints: " + e.getMessage(), e);
        }

        return mappings;
    }

    @Override
    public List<EndpointMapping> probeEndpoints(String baseUrl) {
        List<EndpointMapping> mappings = new ArrayList<>();

        // Try common OpenAPI spec locations
        String[] specPaths = {"/openapi.json", "/swagger.json", "/api-docs", "/v3/api-docs", "/v2/api-docs"};

        for (String path : specPaths) {
            try {
                String specUrl = baseUrl + path;
                HttpRequest request = HttpRequest.newBuilder()
                        .uri(URI.create(specUrl))
                        .GET()
                        .timeout(Duration.ofSeconds(5))
                        .build();

                HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());

                if (response.statusCode() == 200) {
                    JsonNode spec = objectMapper.readTree(response.body());
                    if (spec.has("paths") || spec.has("openapi") || spec.has("swagger")) {
                        mappings = parseOpenApiSpec(spec);
                        logger.info("Found OpenAPI spec at {} with {} endpoints", specUrl, mappings.size());
                        return mappings;
                    }
                }
            } catch (Exception e) {
                // Continue probing
            }
        }

        logger.warn("No OpenAPI spec found at {}, returning empty mappings", baseUrl);
        return mappings;
    }

    @Override
    public EndpointTestResult testMapping(String bridgeId, String mappingId, Object testInput) {
        RestMcpBridgeConfig config = bridges.get(bridgeId);
        if (config == null) {
            return new EndpointTestResult(false, 0, null, "Bridge not found", 0);
        }

        EndpointMapping mapping = config.getMappings().stream()
                .filter(m -> m.getId().equals(mappingId))
                .findFirst()
                .orElse(null);

        if (mapping == null) {
            return new EndpointTestResult(false, 0, null, "Mapping not found", 0);
        }

        long startTime = System.currentTimeMillis();

        try {
            RestEndpoint endpoint = mapping.getRestEndpoint();
            String url = config.getRestApiConfig().getBaseUrl() + endpoint.getPath();

            HttpRequest.Builder requestBuilder = HttpRequest.newBuilder()
                    .uri(URI.create(url))
                    .header("Content-Type", endpoint.getContentType())
                    .timeout(Duration.ofMillis(config.getRestApiConfig().getTimeoutMs()));

            // Add auth headers
            addAuthHeaders(requestBuilder, config.getAuthConfig());

            // Add default headers
            if (config.getRestApiConfig().getDefaultHeaders() != null) {
                config.getRestApiConfig().getDefaultHeaders().forEach(requestBuilder::header);
            }

            String body = testInput != null ? objectMapper.writeValueAsString(testInput) : "";

            HttpRequest request;
            switch (endpoint.getMethod().toUpperCase()) {
                case "GET":
                    request = requestBuilder.GET().build();
                    break;
                case "POST":
                    request = requestBuilder.POST(HttpRequest.BodyPublishers.ofString(body)).build();
                    break;
                case "PUT":
                    request = requestBuilder.PUT(HttpRequest.BodyPublishers.ofString(body)).build();
                    break;
                case "DELETE":
                    request = requestBuilder.DELETE().build();
                    break;
                default:
                    request = requestBuilder.method(endpoint.getMethod(),
                            HttpRequest.BodyPublishers.ofString(body)).build();
            }

            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            long duration = System.currentTimeMillis() - startTime;

            Object responseBody = null;
            try {
                responseBody = objectMapper.readTree(response.body());
            } catch (Exception e) {
                responseBody = response.body();
            }

            return new EndpointTestResult(
                    response.statusCode() >= 200 && response.statusCode() < 300,
                    response.statusCode(),
                    responseBody,
                    null,
                    duration
            );

        } catch (Exception e) {
            long duration = System.currentTimeMillis() - startTime;
            return new EndpointTestResult(false, 0, null, e.getMessage(), duration);
        }
    }

    @Override
    public RestMcpBridgeConfig syncBridge(String id) {
        RestMcpBridgeConfig config = bridges.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        if (config.getRestApiConfig() != null && config.getRestApiConfig().getOpenApiUrl() != null) {
            config.setStatus(BridgeStatus.SYNCING);
            bridges.put(id, config);

            try {
                List<EndpointMapping> newMappings = discoverEndpoints(config.getRestApiConfig().getOpenApiUrl());

                // Merge with existing mappings (preserve enabled state and custom transforms)
                Map<String, EndpointMapping> existingByPath = new HashMap<>();
                for (EndpointMapping m : config.getMappings()) {
                    String key = m.getRestEndpoint().getMethod() + ":" + m.getRestEndpoint().getPath();
                    existingByPath.put(key, m);
                }

                for (EndpointMapping newMapping : newMappings) {
                    String key = newMapping.getRestEndpoint().getMethod() + ":" + newMapping.getRestEndpoint().getPath();
                    EndpointMapping existing = existingByPath.get(key);
                    if (existing != null) {
                        newMapping.setEnabled(existing.isEnabled());
                        newMapping.setRequestTransform(existing.getRequestTransform());
                        newMapping.setResponseTransform(existing.getResponseTransform());
                    }
                }

                config.setMappings(newMappings);
                config.setStatus(BridgeStatus.STOPPED);
                config.setUpdatedAt(Instant.now());
                bridges.put(id, config);
                persistConfiguration(config);

                logger.info("Synced bridge {} with {} mappings", config.getName(), newMappings.size());
            } catch (Exception e) {
                config.setStatus(BridgeStatus.ERROR);
                bridges.put(id, config);
                throw new RuntimeException("Failed to sync bridge: " + e.getMessage(), e);
            }
        }

        return config;
    }

    @Override
    public List<String> validateConfig(RestMcpBridgeConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.getName() == null || config.getName().isBlank()) {
            errors.add("Bridge name is required");
        }

        if (config.getDirection() == null) {
            errors.add("Bridge direction is required");
        }

        if (config.getDirection() == BridgeDirection.REST_TO_MCP) {
            if (config.getRestApiConfig() == null || config.getRestApiConfig().getBaseUrl() == null) {
                errors.add("REST API base URL is required for REST_TO_MCP bridges");
            }
            if (config.getMcpServerRef() == null) {
                errors.add("MCP server reference is required for REST_TO_MCP bridges");
            }
        }

        if (config.getDirection() == BridgeDirection.MCP_TO_REST) {
            if (config.getMcpServerRef() == null ||
                    (config.getMcpServerRef().getServerId() == null && config.getMcpServerRef().getServerUrl() == null)) {
                errors.add("MCP server ID or URL is required for MCP_TO_REST bridges");
            }
        }

        return errors;
    }

    @Override
    public String exportConfig(String id) {
        RestMcpBridgeConfig config = bridges.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Bridge with ID " + id + " not found");
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to export configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public RestMcpBridgeConfig importConfig(String json) {
        try {
            RestMcpBridgeConfig config = objectMapper.readValue(json, RestMcpBridgeConfig.class);
            config.setId(UUID.randomUUID().toString());
            config.setStatus(BridgeStatus.STOPPED);
            return createBridge(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to import configuration: " + e.getMessage(), e);
        }
    }

    private List<EndpointMapping> parseOpenApiSpec(JsonNode spec) {
        List<EndpointMapping> mappings = new ArrayList<>();

        JsonNode paths = spec.get("paths");
        if (paths == null) return mappings;

        paths.fields().forEachRemaining(pathEntry -> {
            String path = pathEntry.getKey();
            JsonNode pathItem = pathEntry.getValue();

            pathItem.fields().forEachRemaining(methodEntry -> {
                String method = methodEntry.getKey().toUpperCase();
                if (!Arrays.asList("GET", "POST", "PUT", "DELETE", "PATCH").contains(method)) {
                    return;
                }

                JsonNode operation = methodEntry.getValue();

                EndpointMapping mapping = EndpointMapping.builder()
                        .id(UUID.randomUUID().toString())
                        .enabled(true)
                        .restEndpoint(RestEndpoint.builder()
                                .method(method)
                                .path(path)
                                .contentType("application/json")
                                .acceptType("application/json")
                                .pathParams(extractPathParams(path))
                                .queryParams(extractQueryParams(operation))
                                .requestBodySchema(extractRequestBody(operation))
                                .build())
                        .mcpTool(McpToolMapping.builder()
                                .name(generateToolName(method, path, operation))
                                .description(extractDescription(operation))
                                .build())
                        .build();

                mappings.add(mapping);
            });
        });

        return mappings;
    }

    private List<ParameterDef> extractPathParams(String path) {
        List<ParameterDef> params = new ArrayList<>();
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("\\{([^}]+)}");
        java.util.regex.Matcher matcher = pattern.matcher(path);
        while (matcher.find()) {
            params.add(ParameterDef.builder()
                    .name(matcher.group(1))
                    .type("string")
                    .required(true)
                    .build());
        }
        return params;
    }

    private List<ParameterDef> extractQueryParams(JsonNode operation) {
        List<ParameterDef> params = new ArrayList<>();
        JsonNode parameters = operation.get("parameters");
        if (parameters != null && parameters.isArray()) {
            for (JsonNode param : parameters) {
                String in = param.has("in") ? param.get("in").asText() : "";
                if ("query".equals(in)) {
                    params.add(ParameterDef.builder()
                            .name(param.get("name").asText())
                            .type(extractParamType(param))
                            .description(param.has("description") ? param.get("description").asText() : null)
                            .required(param.has("required") && param.get("required").asBoolean())
                            .build());
                }
            }
        }
        return params;
    }

    private String extractParamType(JsonNode param) {
        if (param.has("schema")) {
            JsonNode schema = param.get("schema");
            if (schema.has("type")) {
                return schema.get("type").asText();
            }
        }
        return "string";
    }

    private Object extractRequestBody(JsonNode operation) {
        JsonNode requestBody = operation.get("requestBody");
        if (requestBody != null) {
            JsonNode content = requestBody.get("content");
            if (content != null) {
                JsonNode jsonContent = content.get("application/json");
                if (jsonContent != null && jsonContent.has("schema")) {
                    return jsonContent.get("schema");
                }
            }
        }
        return null;
    }

    private String extractDescription(JsonNode operation) {
        if (operation.has("summary")) {
            return operation.get("summary").asText();
        }
        if (operation.has("description")) {
            return operation.get("description").asText();
        }
        return "No description available";
    }

    private String generateToolName(String method, String path, JsonNode operation) {
        if (operation.has("operationId")) {
            return toSnakeCase(operation.get("operationId").asText());
        }

        // Generate from method and path
        String name = method.toLowerCase() + "_" + path
                .replaceAll("\\{[^}]+}", "")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toLowerCase();

        return name.replaceAll("_+", "_");
    }

    private String toSnakeCase(String input) {
        return input
                .replaceAll("([a-z])([A-Z])", "$1_$2")
                .replaceAll("[^a-zA-Z0-9]+", "_")
                .toLowerCase();
    }

    private void addAuthHeaders(HttpRequest.Builder builder, AuthConfig authConfig) {
        if (authConfig == null || authConfig.getType() == AuthType.NONE) {
            return;
        }

        switch (authConfig.getType()) {
            case API_KEY:
                builder.header(authConfig.getApiKeyHeader(), authConfig.getApiKey());
                break;
            case BEARER:
                builder.header("Authorization", "Bearer " + authConfig.getBearerToken());
                break;
            case BASIC:
                String credentials = authConfig.getUsername() + ":" + authConfig.getPassword();
                String encoded = Base64.getEncoder().encodeToString(credentials.getBytes());
                builder.header("Authorization", "Basic " + encoded);
                break;
            case OAUTH2:
                // Would need to implement token fetch/refresh
                logger.warn("OAuth2 authentication not fully implemented");
                break;
        }
    }

    private void loadConfigurationsFromDisk() {
        Path configPath = Paths.get(configDirectory);
        if (!Files.exists(configPath)) {
            try {
                Files.createDirectories(configPath);
            } catch (IOException e) {
                logger.warn("Failed to create config directory: {}", configPath, e);
            }
            return;
        }

        try (Stream<Path> paths = Files.list(configPath)) {
            paths.filter(p -> p.toString().endsWith(".json"))
                    .forEach(this::loadConfiguration);
        } catch (IOException e) {
            logger.error("Failed to load configurations from disk", e);
        }
    }

    private void loadConfiguration(Path path) {
        try {
            String json = Files.readString(path);
            RestMcpBridgeConfig config = objectMapper.readValue(json, RestMcpBridgeConfig.class);
            config.setStatus(BridgeStatus.STOPPED);
            bridges.put(config.getId(), config);
            logger.info("Loaded REST-MCP bridge configuration: {} from {}", config.getName(), path);
        } catch (IOException e) {
            logger.error("Failed to load configuration from: {}", path, e);
        }
    }

    private void persistConfiguration(RestMcpBridgeConfig config) {
        Path configPath = Paths.get(configDirectory, config.getId() + ".json");
        try {
            Files.createDirectories(configPath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
            Files.writeString(configPath, json);
        } catch (IOException e) {
            logger.error("Failed to persist configuration: {}", config.getId(), e);
        }
    }

    private void deleteConfigurationFile(String id) {
        Path configPath = Paths.get(configDirectory, id + ".json");
        try {
            Files.deleteIfExists(configPath);
        } catch (IOException e) {
            logger.error("Failed to delete configuration file: {}", id, e);
        }
    }
}

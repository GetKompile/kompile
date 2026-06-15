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

import ai.kompile.core.mcp.server.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Implementation of McpServerManager that provides CRUD operations
 * for MCP server configurations and manages server lifecycle.
 */
@Service
public class McpServerManagerImpl implements McpServerManager {

    private static final Logger logger = LoggerFactory.getLogger(McpServerManagerImpl.class);

    private final Map<String, McpServerConfig> serverConfigs = new ConcurrentHashMap<>();
    private final Map<String, McpServerRuntime> runningServers = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;

    @Value("${kompile.mcp.servers.config-dir:./data/mcp-servers}")
    private String configDirectory;

    public McpServerManagerImpl() {
        this.objectMapper = new ObjectMapper();
        this.objectMapper.registerModule(new JavaTimeModule());
    }

    @PostConstruct
    public void init() {
        loadConfigurationsFromDisk();
    }

    @PreDestroy
    public void shutdown() {
        // Stop all running servers on shutdown
        runningServers.keySet().forEach(this::stopServer);
    }

    @Override
    public McpServerConfig createServer(McpServerConfig config) {
        String id = config.getId();
        if (id == null || id.isBlank()) {
            id = UUID.randomUUID().toString();
            config.setId(id);
        }

        config.setCreatedAt(Instant.now());
        config.setUpdatedAt(Instant.now());
        config.setStatus(McpServerConfig.ServerStatus.STOPPED);

        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        McpServerConfig existing = serverConfigs.putIfAbsent(id, config);
        if (existing != null) {
            throw new IllegalArgumentException("Server with ID " + id + " already exists");
        }
        persistConfiguration(config);

        logger.info("Created MCP server configuration: {} ({})", config.getName(), id);
        return config;
    }

    @Override
    public McpServerConfig updateServer(String id, McpServerConfig config) {
        McpServerConfig existing = serverConfigs.get(id);
        if (existing == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }
        if (existing.getStatus() == McpServerConfig.ServerStatus.RUNNING) {
            throw new IllegalStateException("Cannot update running server. Stop it first.");
        }

        config.setId(id);
        config.setCreatedAt(existing.getCreatedAt());
        config.setUpdatedAt(Instant.now());

        List<String> errors = validateConfig(config);
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        serverConfigs.put(id, config);
        persistConfiguration(config);

        logger.info("Updated MCP server configuration: {} ({})", config.getName(), id);
        return config;
    }

    @Override
    public void deleteServer(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }
        if (config.getStatus() == McpServerConfig.ServerStatus.RUNNING) {
            throw new IllegalStateException("Cannot delete running server. Stop it first.");
        }

        serverConfigs.remove(id);
        deleteConfigurationFile(id);

        logger.info("Deleted MCP server configuration: {}", id);
    }

    @Override
    public Optional<McpServerConfig> getServer(String id) {
        return Optional.ofNullable(serverConfigs.get(id));
    }

    @Override
    public List<McpServerConfig> listServers() {
        return new ArrayList<>(serverConfigs.values());
    }

    @Override
    public McpServerConfig startServer(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }

        if (config.getStatus() == McpServerConfig.ServerStatus.RUNNING) {
            throw new IllegalStateException("Server is already running");
        }

        config.setStatus(McpServerConfig.ServerStatus.STARTING);
        serverConfigs.put(id, config);

        try {
            McpServerRuntime runtime = new McpServerRuntime(config, objectMapper);
            runtime.start();
            runningServers.put(id, runtime);

            config.setStatus(McpServerConfig.ServerStatus.RUNNING);
            config.setUpdatedAt(Instant.now());
            serverConfigs.put(id, config);
            persistConfiguration(config);

            logger.info("Started MCP server: {} on port {}", config.getName(), config.getPort());
        } catch (Exception e) {
            config.setStatus(McpServerConfig.ServerStatus.ERROR);
            serverConfigs.put(id, config);
            logger.error("Failed to start MCP server: {}", config.getName(), e);
            throw new RuntimeException("Failed to start server: " + e.getMessage(), e);
        }

        return config;
    }

    @Override
    public McpServerConfig stopServer(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }

        if (config.getStatus() != McpServerConfig.ServerStatus.RUNNING) {
            throw new IllegalStateException("Server is not running");
        }

        config.setStatus(McpServerConfig.ServerStatus.STOPPING);
        serverConfigs.put(id, config);

        try {
            McpServerRuntime runtime = runningServers.remove(id);
            if (runtime != null) {
                runtime.stop();
            }

            config.setStatus(McpServerConfig.ServerStatus.STOPPED);
            config.setUpdatedAt(Instant.now());
            serverConfigs.put(id, config);
            persistConfiguration(config);

            logger.info("Stopped MCP server: {}", config.getName());
        } catch (Exception e) {
            config.setStatus(McpServerConfig.ServerStatus.ERROR);
            serverConfigs.put(id, config);
            logger.error("Failed to stop MCP server: {}", config.getName(), e);
            throw new RuntimeException("Failed to stop server: " + e.getMessage(), e);
        }

        return config;
    }

    @Override
    public McpServerConfig restartServer(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }

        if (config.getStatus() == McpServerConfig.ServerStatus.RUNNING) {
            stopServer(id);
        }

        return startServer(id);
    }

    @Override
    public McpServerConfig.ServerStatus getServerStatus(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }
        return config.getStatus();
    }

    @Override
    public List<String> validateConfig(McpServerConfig config) {
        List<String> errors = new ArrayList<>();

        if (config.getName() == null || config.getName().isBlank()) {
            errors.add("Server name is required");
        }

        if (config.getTransportType() == null) {
            errors.add("Transport type is required");
        }

        if (config.getTransportType() != McpServerConfig.TransportType.STDIO) {
            if (config.getPort() == null || config.getPort() < 1 || config.getPort() > 65535) {
                errors.add("Valid port (1-65535) is required for HTTP-based transports");
            }

            // Check for port conflicts
            for (McpServerConfig existing : serverConfigs.values()) {
                if (!existing.getId().equals(config.getId()) &&
                        existing.getPort() != null &&
                        existing.getPort().equals(config.getPort()) &&
                        existing.getStatus() != McpServerConfig.ServerStatus.STOPPED) {
                    errors.add("Port " + config.getPort() + " is already in use by server: " + existing.getName());
                }
            }
        }

        // Validate tools
        if (config.getTools() != null) {
            Set<String> toolNames = new HashSet<>();
            for (McpToolConfig tool : config.getTools()) {
                if (tool.getName() == null || tool.getName().isBlank()) {
                    errors.add("Tool name is required");
                } else if (!toolNames.add(tool.getName())) {
                    errors.add("Duplicate tool name: " + tool.getName());
                }
            }
        }

        // Validate resources
        if (config.getResources() != null) {
            Set<String> resourceUris = new HashSet<>();
            for (McpResourceConfig resource : config.getResources()) {
                if (resource.getUri() == null || resource.getUri().isBlank()) {
                    errors.add("Resource URI is required");
                } else if (!resourceUris.add(resource.getUri())) {
                    errors.add("Duplicate resource URI: " + resource.getUri());
                }
            }
        }

        // Validate prompts
        if (config.getPrompts() != null) {
            Set<String> promptNames = new HashSet<>();
            for (McpPromptConfig prompt : config.getPrompts()) {
                if (prompt.getName() == null || prompt.getName().isBlank()) {
                    errors.add("Prompt name is required");
                } else if (!promptNames.add(prompt.getName())) {
                    errors.add("Duplicate prompt name: " + prompt.getName());
                }
            }
        }

        return errors;
    }

    @Override
    public String exportConfig(String id) {
        McpServerConfig config = serverConfigs.get(id);
        if (config == null) {
            throw new IllegalArgumentException("Server with ID " + id + " not found");
        }

        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to export configuration: " + e.getMessage(), e);
        }
    }

    @Override
    public McpServerConfig importConfig(String json) {
        try {
            McpServerConfig config = objectMapper.readValue(json, McpServerConfig.class);
            // Generate new ID for imported config
            config.setId(UUID.randomUUID().toString());
            config.setStatus(McpServerConfig.ServerStatus.STOPPED);
            return createServer(config);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to import configuration: " + e.getMessage(), e);
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
            McpServerConfig config = objectMapper.readValue(json, McpServerConfig.class);
            // Reset status on load
            config.setStatus(McpServerConfig.ServerStatus.STOPPED);
            serverConfigs.put(config.getId(), config);
            logger.info("Loaded MCP server configuration: {} from {}", config.getName(), path);
        } catch (IOException e) {
            logger.error("Failed to load configuration from: {}", path, e);
        }
    }

    private void persistConfiguration(McpServerConfig config) {
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

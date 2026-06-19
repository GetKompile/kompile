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
import ai.kompile.core.mcp.server.ExternalMcpServerConfig;
import ai.kompile.core.mcp.server.ExternalMcpServerConfig.ServerStatus;
import ai.kompile.core.mcp.server.UnifiedMcpConfig;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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

/**
 * Service for managing external MCP servers.
 * Handles loading, saving, and managing the lifecycle of external MCP servers
 * supporting both STDIO (Claude Desktop format) and REST/SSE transports.
 */
@Service
public class ExternalMcpServerManager {

    private static final Logger logger = LoggerFactory.getLogger(ExternalMcpServerManager.class);
    private static final String CONFIG_FILE_NAME = "mcp-config.json";

    private final ObjectMapper objectMapper;
    private final Map<String, StdioMcpClientRuntime> runningStdioServers = new ConcurrentHashMap<>();
    private final Map<String, RestMcpClientRuntime> runningRestServers = new ConcurrentHashMap<>();

    @Value("${kompile.mcp.config.path:./data}")
    private String configDirectory;

    private volatile UnifiedMcpConfig config;

    public ExternalMcpServerManager() {
        this.objectMapper = JsonUtils.standardMapper();
    }

    @PostConstruct
    public void init() {
        loadConfig();
    }

    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down external MCP server manager, stopping all servers...");

        // Stop all STDIO servers
        for (String serverId : new ArrayList<>(runningStdioServers.keySet())) {
            try {
                stopServer(serverId);
            } catch (Exception e) {
                logger.warn("Error stopping STDIO server {} during shutdown: {}", serverId, e.getMessage());
            }
        }

        // Stop all REST servers
        for (String serverId : new ArrayList<>(runningRestServers.keySet())) {
            try {
                stopServer(serverId);
            } catch (Exception e) {
                logger.warn("Error stopping REST server {} during shutdown: {}", serverId, e.getMessage());
            }
        }
    }

    /**
     * Loads the unified configuration from disk.
     */
    public synchronized void loadConfig() {
        Path configPath = getConfigPath();

        if (!Files.exists(configPath)) {
            logger.info("No MCP config file found at {}, creating empty config", configPath);
            this.config = UnifiedMcpConfig.empty();
            this.config.setConfigPath(configPath.toString());
            saveConfig();
            return;
        }

        try {
            String json = Files.readString(configPath);
            this.config = objectMapper.readValue(json, UnifiedMcpConfig.class);
            this.config.setConfigPath(configPath.toString());
            this.config.setLastModified(Instant.now());

            // Initialize IDs from keys and reset status
            for (Map.Entry<String, ExternalMcpServerConfig> entry : config.getMcpServers().entrySet()) {
                entry.getValue().setId(entry.getKey());
                entry.getValue().setStatus(ServerStatus.STOPPED);
            }

            logger.info("Loaded MCP config with {} servers from {}", config.getServerCount(), configPath);
        } catch (IOException e) {
            logger.error("Failed to load MCP config from {}: {}", configPath, e.getMessage());
            this.config = UnifiedMcpConfig.empty();
            this.config.setConfigPath(configPath.toString());
        }
    }

    /**
     * Saves the unified configuration to disk.
     */
    public synchronized void saveConfig() {
        Path configPath = getConfigPath();

        try {
            Files.createDirectories(configPath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config.toSerializableConfig());
            Files.writeString(configPath, json);
            config.setLastModified(Instant.now());
            logger.debug("Saved MCP config to {}", configPath);
        } catch (IOException e) {
            logger.error("Failed to save MCP config to {}: {}", configPath, e.getMessage());
            throw new RuntimeException("Failed to save configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Lists all configured external servers.
     */
    public List<ExternalMcpServerConfig> listServers() {
        return new ArrayList<>(config.getMcpServers().values());
    }

    /**
     * Gets a specific server configuration by ID.
     */
    public Optional<ExternalMcpServerConfig> getServer(String id) {
        return Optional.ofNullable(config.getServer(id));
    }

    /**
     * Adds a new server configuration.
     */
    public ExternalMcpServerConfig addServer(String id, ExternalMcpServerConfig serverConfig) {
        if (config.hasServer(id)) {
            throw new IllegalArgumentException("Server with ID '" + id + "' already exists");
        }

        serverConfig.setId(id);
        List<String> errors = serverConfig.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        config.putServer(id, serverConfig);
        saveConfig();

        logger.info("Added external MCP server: {}", id);
        return serverConfig;
    }

    /**
     * Updates an existing server configuration.
     */
    public ExternalMcpServerConfig updateServer(String id, ExternalMcpServerConfig serverConfig) {
        if (!config.hasServer(id)) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        ExternalMcpServerConfig existing = config.getServer(id);
        if (existing.getStatus() == ServerStatus.RUNNING) {
            throw new IllegalStateException("Cannot update running server. Stop it first.");
        }

        serverConfig.setId(id);
        List<String> errors = serverConfig.validate();
        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
        }

        config.putServer(id, serverConfig);
        saveConfig();

        logger.info("Updated external MCP server: {}", id);
        return serverConfig;
    }

    /**
     * Deletes a server configuration.
     */
    public void deleteServer(String id) {
        if (!config.hasServer(id)) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        ExternalMcpServerConfig existing = config.getServer(id);
        if (existing.getStatus() == ServerStatus.RUNNING) {
            throw new IllegalStateException("Cannot delete running server. Stop it first.");
        }

        config.removeServer(id);
        saveConfig();

        logger.info("Deleted external MCP server: {}", id);
    }

    /**
     * Starts a server (STDIO, REST, or SSE based on transport type).
     */
    public ExternalMcpServerConfig startServer(String id) {
        ExternalMcpServerConfig serverConfig = config.getServer(id);
        if (serverConfig == null) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        if (serverConfig.getStatus() == ServerStatus.RUNNING) {
            throw new IllegalStateException("Server is already running");
        }

        serverConfig.setStatus(ServerStatus.STARTING);

        try {
            if (serverConfig.isStdio()) {
                // STDIO transport - spawn process
                startStdioServer(id, serverConfig);
            } else {
                // REST/SSE transport - connect to remote server
                startRestServer(id, serverConfig);
            }

            serverConfig.setStatus(ServerStatus.RUNNING);
            serverConfig.setLastStarted(Instant.now());
            serverConfig.setErrorMessage(null);

        } catch (Exception e) {
            serverConfig.setStatus(ServerStatus.ERROR);
            serverConfig.setErrorMessage(e.getMessage());
            logger.error("Failed to start external MCP server {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to start server: " + e.getMessage(), e);
        }

        return serverConfig;
    }

    /**
     * Starts an STDIO-based server by spawning its process.
     */
    private void startStdioServer(String id, ExternalMcpServerConfig serverConfig) throws Exception {
        StdioMcpClientRuntime runtime = new StdioMcpClientRuntime(serverConfig);
        runtime.start();
        runningStdioServers.put(id, runtime);
        serverConfig.setPid(runtime.getPid());
        logger.info("Started STDIO MCP server: {} (PID: {})", id, runtime.getPid());
    }

    /**
     * Starts a REST/SSE-based server by connecting to the remote endpoint.
     */
    private void startRestServer(String id, ExternalMcpServerConfig serverConfig) throws Exception {
        RestMcpClientRuntime runtime = new RestMcpClientRuntime(serverConfig);
        runtime.start();
        runningRestServers.put(id, runtime);
        serverConfig.setPid(null); // REST servers don't have a PID
        logger.info("Started REST MCP server: {} connected to: {}", id, serverConfig.getUrl());
    }

    /**
     * Stops a running server (STDIO or REST/SSE).
     */
    public ExternalMcpServerConfig stopServer(String id) {
        ExternalMcpServerConfig serverConfig = config.getServer(id);
        if (serverConfig == null) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        if (serverConfig.getStatus() != ServerStatus.RUNNING &&
            serverConfig.getStatus() != ServerStatus.ERROR) {
            throw new IllegalStateException("Server is not running");
        }

        serverConfig.setStatus(ServerStatus.STOPPING);

        try {
            if (serverConfig.isStdio()) {
                // Stop STDIO server
                StdioMcpClientRuntime runtime = runningStdioServers.remove(id);
                if (runtime != null) {
                    runtime.stop();
                }
            } else {
                // Stop REST/SSE server
                RestMcpClientRuntime runtime = runningRestServers.remove(id);
                if (runtime != null) {
                    runtime.stop();
                }
            }

            serverConfig.setStatus(ServerStatus.STOPPED);
            serverConfig.setLastStopped(Instant.now());
            serverConfig.setPid(null);

            logger.info("Stopped external MCP server: {}", id);
        } catch (Exception e) {
            serverConfig.setStatus(ServerStatus.ERROR);
            serverConfig.setErrorMessage(e.getMessage());
            logger.error("Failed to stop external MCP server {}: {}", id, e.getMessage());
            throw new RuntimeException("Failed to stop server: " + e.getMessage(), e);
        }

        return serverConfig;
    }

    /**
     * Restarts a server.
     */
    public ExternalMcpServerConfig restartServer(String id) {
        ExternalMcpServerConfig serverConfig = config.getServer(id);
        if (serverConfig == null) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        if (serverConfig.getStatus() == ServerStatus.RUNNING) {
            stopServer(id);
        }

        return startServer(id);
    }

    /**
     * Gets the current status of a server.
     */
    public ServerStatus getServerStatus(String id) {
        ExternalMcpServerConfig serverConfig = config.getServer(id);
        if (serverConfig == null) {
            throw new IllegalArgumentException("Server with ID '" + id + "' not found");
        }

        if (serverConfig.isStdio()) {
            // Check if the STDIO process is still alive
            StdioMcpClientRuntime runtime = runningStdioServers.get(id);
            if (runtime != null && !runtime.isAlive()) {
                serverConfig.setStatus(ServerStatus.ERROR);
                serverConfig.setErrorMessage("Process terminated unexpectedly");
                runningStdioServers.remove(id);
            }
        } else {
            // Check if the REST connection is still alive
            RestMcpClientRuntime runtime = runningRestServers.get(id);
            if (runtime != null && !runtime.isAlive()) {
                serverConfig.setStatus(ServerStatus.ERROR);
                serverConfig.setErrorMessage(runtime.getLastError() != null ?
                        runtime.getLastError() : "Connection lost");
                runningRestServers.remove(id);
            }
        }

        return serverConfig.getStatus();
    }

    /**
     * Imports configuration from Claude Desktop format JSON.
     */
    public UnifiedMcpConfig importConfig(String json) {
        try {
            UnifiedMcpConfig imported = objectMapper.readValue(json, UnifiedMcpConfig.class);

            List<String> errors = imported.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
            }

            // Merge imported config into existing
            for (Map.Entry<String, ExternalMcpServerConfig> entry : imported.getMcpServers().entrySet()) {
                String id = entry.getKey();
                ExternalMcpServerConfig serverConfig = entry.getValue();

                // Check if server is running
                if (config.hasServer(id)) {
                    ExternalMcpServerConfig existing = config.getServer(id);
                    if (existing.getStatus() == ServerStatus.RUNNING) {
                        logger.warn("Skipping import of running server: {}", id);
                        continue;
                    }
                }

                serverConfig.setId(id);
                serverConfig.setStatus(ServerStatus.STOPPED);
                config.putServer(id, serverConfig);
            }

            saveConfig();
            logger.info("Imported MCP config with {} servers", imported.getServerCount());
            return config;

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Exports the current configuration as Claude Desktop format JSON.
     */
    public String exportConfig() {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(config.toSerializableConfig());
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to export configuration: " + e.getMessage(), e);
        }
    }

    /**
     * Replaces the entire configuration with new JSON.
     */
    public UnifiedMcpConfig replaceConfig(String json) {
        // First, stop all running STDIO servers
        for (String id : new ArrayList<>(runningStdioServers.keySet())) {
            try {
                stopServer(id);
            } catch (Exception e) {
                logger.warn("Error stopping STDIO server {} during config replacement: {}", id, e.getMessage());
            }
        }

        // Stop all running REST servers
        for (String id : new ArrayList<>(runningRestServers.keySet())) {
            try {
                stopServer(id);
            } catch (Exception e) {
                logger.warn("Error stopping REST server {} during config replacement: {}", id, e.getMessage());
            }
        }

        try {
            UnifiedMcpConfig newConfig = objectMapper.readValue(json, UnifiedMcpConfig.class);

            List<String> errors = newConfig.validate();
            if (!errors.isEmpty()) {
                throw new IllegalArgumentException("Invalid configuration: " + String.join(", ", errors));
            }

            // Set IDs and reset status
            for (Map.Entry<String, ExternalMcpServerConfig> entry : newConfig.getMcpServers().entrySet()) {
                entry.getValue().setId(entry.getKey());
                entry.getValue().setStatus(ServerStatus.STOPPED);
            }

            newConfig.setConfigPath(config.getConfigPath());
            newConfig.setLastModified(Instant.now());
            this.config = newConfig;

            saveConfig();
            logger.info("Replaced MCP config with {} servers", config.getServerCount());
            return config;

        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Validates configuration JSON without saving.
     */
    public List<String> validateConfig(String json) {
        try {
            UnifiedMcpConfig parsed = objectMapper.readValue(json, UnifiedMcpConfig.class);
            return parsed.validate();
        } catch (JsonProcessingException e) {
            return List.of("Invalid JSON: " + e.getMessage());
        }
    }

    /**
     * Gets the unified configuration.
     */
    public UnifiedMcpConfig getConfig() {
        return config;
    }

    /**
     * Gets the path to the config file.
     */
    private Path getConfigPath() {
        return Paths.get(configDirectory, CONFIG_FILE_NAME);
    }

    /**
     * Gets the configuration directory path.
     */
    public String getConfigDirectory() {
        return configDirectory;
    }
}

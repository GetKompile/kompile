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

package ai.kompile.app.mcp;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.Setter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Managed-JSON configuration service for the MCP (Model Context Protocol) server.
 * Reads from and persists to &lt;dataDir&gt;/config/mcp-server-config.json.
 * Eliminates application.properties-based configuration under the mcp.server.* prefix.
 */
@Component
@Getter
@Setter
public class McpServerProperties {

    private static final Logger log = LoggerFactory.getLogger(McpServerProperties.class);
    public static final String CONFIG_FILENAME = "mcp-server-config.json";

    @JsonIgnore
    private final ObjectMapper objectMapper;

    @JsonIgnore
    private final Path configFilePath;

    /**
     * Whether the MCP server is enabled.
     */
    private boolean enabled = true;

    /**
     * The name of the MCP server (used in server info).
     */
    private String name = "kompile-mcp-server";

    /**
     * The version of the MCP server.
     */
    private String version = "1.0.0";

    /**
     * The transport type to use: 'sse' or 'stdio'.
     */
    private String transport = "sse";

    /**
     * SSE-specific configuration.
     */
    private Sse sse = new Sse();

    /**
     * Action logging configuration.
     */
    private ActionLog actionLog = new ActionLog();

    public McpServerProperties(@Value("${kompile.data.dir:#{null}}") String dataDir) {
        this.objectMapper = new ObjectMapper();

        String effectiveDataDir = dataDir;
        if (effectiveDataDir == null || effectiveDataDir.isBlank()) {
            effectiveDataDir = System.getProperty("user.home") + "/.kompile";
        }
        this.configFilePath = Paths.get(effectiveDataDir, "config", CONFIG_FILENAME);
        log.info("McpServerProperties initialized, config path: {}", configFilePath);
    }

    /**
     * Loads persisted configuration on startup.
     * Overlays values from the JSON file onto the field defaults.
     * When the file is absent keeps the field defaults. Never throws.
     */
    @PostConstruct
    public void load() {
        if (!Files.exists(configFilePath)) {
            log.info("No persisted MCP server config found at {} - using defaults", configFilePath);
            return;
        }
        try {
            String json = Files.readString(configFilePath);
            log.info("Loading MCP server config from {} ({} bytes)", configFilePath, json.length());
            JsonNode node = objectMapper.readTree(json);

            if (node.has("enabled")) {
                this.enabled = node.get("enabled").asBoolean(this.enabled);
            }
            if (node.has("name") && !node.get("name").isNull()) {
                this.name = node.get("name").asText(this.name);
            }
            if (node.has("version") && !node.get("version").isNull()) {
                this.version = node.get("version").asText(this.version);
            }
            if (node.has("transport") && !node.get("transport").isNull()) {
                this.transport = node.get("transport").asText(this.transport);
            }
            if (node.has("sse") && !node.get("sse").isNull()) {
                JsonNode sseNode = node.get("sse");
                Sse loadedSse = new Sse();
                if (sseNode.has("endpoint") && !sseNode.get("endpoint").isNull()) {
                    loadedSse.setEndpoint(sseNode.get("endpoint").asText(loadedSse.getEndpoint()));
                }
                if (sseNode.has("messageEndpoint") && !sseNode.get("messageEndpoint").isNull()) {
                    loadedSse.setMessageEndpoint(sseNode.get("messageEndpoint").asText(loadedSse.getMessageEndpoint()));
                }
                if (sseNode.has("timeout")) {
                    loadedSse.setTimeout(sseNode.get("timeout").asLong(loadedSse.getTimeout()));
                }
                this.sse = loadedSse;
            }
            if (node.has("actionLog") && !node.get("actionLog").isNull()) {
                JsonNode alNode = node.get("actionLog");
                ActionLog loadedAl = new ActionLog();
                if (alNode.has("enabled")) {
                    loadedAl.setEnabled(alNode.get("enabled").asBoolean(loadedAl.isEnabled()));
                }
                if (alNode.has("maxEntries")) {
                    loadedAl.setMaxEntries(alNode.get("maxEntries").asInt(loadedAl.getMaxEntries()));
                }
                if (alNode.has("retentionHours")) {
                    loadedAl.setRetentionHours(alNode.get("retentionHours").asInt(loadedAl.getRetentionHours()));
                }
                this.actionLog = loadedAl;
            }
            log.info("MCP server config loaded: enabled={}, transport={}, name={}", enabled, transport, name);
        } catch (IOException e) {
            log.error("Failed to load MCP server config from {}: {} - using defaults", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * Persists current field values to configFilePath as pretty-printed JSON.
     * Creates parent directories if they do not exist. Never throws.
     */
    public void persist() {
        try {
            Path parentDir = configFilePath.getParent();
            if (!Files.exists(parentDir)) {
                Files.createDirectories(parentDir);
                log.info("Created config directory: {}", parentDir);
            }
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(this);
            Files.writeString(configFilePath, json);
            log.info("Persisted MCP server config to {}", configFilePath);
        } catch (IOException e) {
            log.error("Failed to persist MCP server config to {}: {}", configFilePath, e.getMessage(), e);
        }
    }

    /**
     * SSE transport configuration.
     */
    @Getter
    @Setter
    public static class Sse {
        /**
         * The endpoint path for SSE connections.
         */
        private String endpoint = "/mcp/sse";

        /**
         * The endpoint path for receiving messages.
         */
        private String messageEndpoint = "/mcp/message";

        /**
         * SSE connection timeout in milliseconds.
         */
        private long timeout = 300000L;
    }

    /**
     * Action logging configuration.
     */
    @Getter
    @Setter
    public static class ActionLog {
        /**
         * Whether action logging is enabled.
         */
        private boolean enabled = true;

        /**
         * Maximum number of actions to keep in the log.
         */
        private int maxEntries = 1000;

        /**
         * Hours to retain actions before cleanup.
         */
        private int retentionHours = 24;
    }
}

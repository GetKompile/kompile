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

package ai.kompile.core.mcp.server;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Unified MCP configuration in Claude Desktop format.
 * This represents the entire mcp-config.json file.
 *
 * Claude Desktop format example:
 * <pre>
 * {
 *   "mcpServers": {
 *     "filesystem": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-filesystem", "/path"],
 *       "env": {}
 *     },
 *     "github": {
 *       "command": "npx",
 *       "args": ["-y", "@modelcontextprotocol/server-github"],
 *       "env": {
 *         "GITHUB_PERSONAL_ACCESS_TOKEN": "ghp_xxx"
 *       }
 *     }
 *   }
 * }
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class UnifiedMcpConfig {

    /**
     * Map of server ID to server configuration.
     * Uses LinkedHashMap to preserve insertion order.
     */
    @JsonProperty("mcpServers")
    @Builder.Default
    private Map<String, ExternalMcpServerConfig> mcpServers = new LinkedHashMap<>();

    /**
     * Timestamp when this config was last modified.
     * Not part of Claude Desktop format, used internally.
     */
    @JsonIgnore
    private Instant lastModified;

    /**
     * File path where this config is stored.
     * Not persisted.
     */
    @JsonIgnore
    private String configPath;

    /**
     * Gets a server configuration by ID.
     * @param id The server ID/name
     * @return The server config or null if not found
     */
    public ExternalMcpServerConfig getServer(String id) {
        return mcpServers.get(id);
    }

    /**
     * Adds or updates a server configuration.
     * @param id The server ID/name
     * @param config The server configuration
     */
    public void putServer(String id, ExternalMcpServerConfig config) {
        config.setId(id);
        mcpServers.put(id, config);
        lastModified = Instant.now();
    }

    /**
     * Removes a server configuration.
     * @param id The server ID/name
     * @return The removed config or null if not found
     */
    public ExternalMcpServerConfig removeServer(String id) {
        ExternalMcpServerConfig removed = mcpServers.remove(id);
        if (removed != null) {
            lastModified = Instant.now();
        }
        return removed;
    }

    /**
     * Checks if a server with the given ID exists.
     * @param id The server ID/name
     * @return true if the server exists
     */
    public boolean hasServer(String id) {
        return mcpServers.containsKey(id);
    }

    /**
     * Gets all server IDs.
     * @return List of server IDs
     */
    public List<String> getServerIds() {
        return new ArrayList<>(mcpServers.keySet());
    }

    /**
     * Gets the number of configured servers.
     * @return Server count
     */
    public int getServerCount() {
        return mcpServers.size();
    }

    /**
     * Creates a copy suitable for serialization to Claude Desktop format.
     * Converts the internal format to the standard mcpServers format.
     */
    public UnifiedMcpConfig toSerializableConfig() {
        Map<String, ExternalMcpServerConfig> serializable = new LinkedHashMap<>();
        for (Map.Entry<String, ExternalMcpServerConfig> entry : mcpServers.entrySet()) {
            serializable.put(entry.getKey(), entry.getValue().toSerializableConfig());
        }
        return UnifiedMcpConfig.builder()
                .mcpServers(serializable)
                .build();
    }

    /**
     * Validates the entire configuration.
     * @return List of validation error messages, empty if valid.
     */
    public List<String> validate() {
        List<String> errors = new ArrayList<>();

        for (Map.Entry<String, ExternalMcpServerConfig> entry : mcpServers.entrySet()) {
            String id = entry.getKey();
            ExternalMcpServerConfig config = entry.getValue();

            // Ensure ID matches the key
            if (config.getId() == null) {
                config.setId(id);
            } else if (!config.getId().equals(id)) {
                errors.add("Server '" + id + "' has mismatched ID: " + config.getId());
            }

            // Validate individual server config
            List<String> serverErrors = config.validate();
            for (String error : serverErrors) {
                errors.add("Server '" + id + "': " + error);
            }
        }

        return errors;
    }

    /**
     * Creates an empty configuration.
     */
    public static UnifiedMcpConfig empty() {
        return UnifiedMcpConfig.builder()
                .mcpServers(new LinkedHashMap<>())
                .lastModified(Instant.now())
                .build();
    }

    /**
     * Merges another configuration into this one.
     * Servers in the other config will override servers with the same ID.
     * @param other The configuration to merge
     */
    public void merge(UnifiedMcpConfig other) {
        if (other != null && other.getMcpServers() != null) {
            for (Map.Entry<String, ExternalMcpServerConfig> entry : other.getMcpServers().entrySet()) {
                putServer(entry.getKey(), entry.getValue());
            }
        }
    }
}

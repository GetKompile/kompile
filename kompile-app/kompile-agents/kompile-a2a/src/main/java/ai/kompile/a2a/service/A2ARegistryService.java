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

package ai.kompile.a2a.service;

import ai.kompile.a2a.client.A2AClient;
import ai.kompile.a2a.client.A2AClientException;
import ai.kompile.a2a.model.A2AAgentConfig;
import ai.kompile.a2a.model.AgentCard;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for remote A2A agents.
 * <p>
 * Manages discovery, persistence, and lifecycle of connections to remote agents
 * that expose the A2A protocol. Persists configurations to
 * {@code ~/.kompile/config/a2a-agents.json}.
 */
@Service
public class A2ARegistryService {

    private static final Logger logger = LoggerFactory.getLogger(A2ARegistryService.class);

    private final ConcurrentHashMap<String, A2AAgentConfig> remoteAgents = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, A2AClient> activeClients = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper;
    private final Path configPath;

    public A2ARegistryService() {
        this.objectMapper = JsonUtils.standardMapper();

        String home = System.getProperty("user.home");
        this.configPath = Path.of(home, ".kompile", "config", "a2a-agents.json");
    }

    @PostConstruct
    public void init() {
        loadConfig();
        logger.info("A2A Registry initialized with {} remote agent(s)", remoteAgents.size());
    }

    @PreDestroy
    public void destroy() {
        activeClients.values().forEach(A2AClient::close);
        activeClients.clear();
    }

    /**
     * Discover and register a remote A2A agent by its base URL.
     * Fetches the agent card and persists the configuration.
     */
    public A2AAgentConfig discoverAgent(String baseUrl) throws A2AClientException {
        A2AClient client = new A2AClient(baseUrl);
        AgentCard card = client.resolveAgentCard();

        String id = card.getName() != null ? card.getName() : UUID.randomUUID().toString();

        // Avoid duplicate registrations
        if (remoteAgents.containsKey(id)) {
            A2AAgentConfig existing = remoteAgents.get(id);
            existing.setAgentCard(card);
            existing.setLastContactedAt(Instant.now());
            existing.setReachable(true);
            saveConfig();
            activeClients.put(id, client);
            return existing;
        }

        A2AAgentConfig config = A2AAgentConfig.builder()
                .id(id)
                .name(card.getName())
                .baseUrl(baseUrl)
                .enabled(true)
                .registeredAt(Instant.now())
                .lastContactedAt(Instant.now())
                .agentCard(card)
                .reachable(true)
                .build();

        remoteAgents.put(id, config);
        activeClients.put(id, client);
        saveConfig();

        logger.info("Discovered and registered A2A agent: {} at {}", id, baseUrl);
        return config;
    }

    /**
     * Register a remote agent manually (without discovery).
     */
    public A2AAgentConfig registerAgent(String id, String name, String baseUrl) {
        A2AAgentConfig config = A2AAgentConfig.builder()
                .id(id)
                .name(name)
                .baseUrl(baseUrl)
                .enabled(true)
                .registeredAt(Instant.now())
                .reachable(false)
                .build();

        remoteAgents.put(id, config);
        saveConfig();

        logger.info("Manually registered A2A agent: {} at {}", id, baseUrl);
        return config;
    }

    /**
     * Unregister a remote agent.
     */
    public boolean unregisterAgent(String id) {
        A2AAgentConfig removed = remoteAgents.remove(id);
        A2AClient client = activeClients.remove(id);
        if (client != null) client.close();
        if (removed != null) {
            saveConfig();
            logger.info("Unregistered A2A agent: {}", id);
        }
        return removed != null;
    }

    /**
     * Get a client for a registered remote agent.
     */
    public Optional<A2AClient> getClient(String agentId) {
        A2AClient client = activeClients.get(agentId);
        if (client != null) return Optional.of(client);

        // Try to create a client from config
        A2AAgentConfig config = remoteAgents.get(agentId);
        if (config != null && config.isEnabled()) {
            A2AClient newClient = new A2AClient(config.getBaseUrl());
            activeClients.put(agentId, newClient);
            return Optional.of(newClient);
        }

        return Optional.empty();
    }

    /**
     * Get configuration for a specific agent.
     */
    public Optional<A2AAgentConfig> getAgentConfig(String id) {
        return Optional.ofNullable(remoteAgents.get(id));
    }

    /**
     * List all registered remote agents.
     */
    public List<A2AAgentConfig> listAgents() {
        return List.copyOf(remoteAgents.values());
    }

    /**
     * List only enabled and reachable remote agents.
     */
    public List<A2AAgentConfig> listAvailableAgents() {
        return remoteAgents.values().stream()
                .filter(A2AAgentConfig::isEnabled)
                .toList();
    }

    /**
     * Ping a remote agent to check connectivity.
     */
    public boolean pingAgent(String id) {
        A2AAgentConfig config = remoteAgents.get(id);
        if (config == null) return false;

        try {
            A2AClient client = new A2AClient(config.getBaseUrl());
            client.resolveAgentCard();
            config.setReachable(true);
            config.setLastContactedAt(Instant.now());
            activeClients.put(id, client);
            saveConfig();
            return true;
        } catch (Exception e) {
            config.setReachable(false);
            saveConfig();
            logger.debug("Ping failed for A2A agent {}: {}", id, e.getMessage());
            return false;
        }
    }

    /**
     * Enable or disable a remote agent.
     */
    public boolean setEnabled(String id, boolean enabled) {
        A2AAgentConfig config = remoteAgents.get(id);
        if (config == null) return false;
        config.setEnabled(enabled);
        if (!enabled) {
            A2AClient client = activeClients.remove(id);
            if (client != null) client.close();
        }
        saveConfig();
        return true;
    }

    private void loadConfig() {
        if (!Files.exists(configPath)) return;

        try {
            String json = Files.readString(configPath);
            List<A2AAgentConfig> configs = objectMapper.readValue(json,
                    new TypeReference<List<A2AAgentConfig>>() {});
            for (A2AAgentConfig config : configs) {
                remoteAgents.put(config.getId(), config);
            }
            logger.info("Loaded {} A2A agent configurations from {}", configs.size(), configPath);
        } catch (IOException e) {
            logger.warn("Failed to load A2A agent configs: {}", e.getMessage());
        }
    }

    private void saveConfig() {
        try {
            Files.createDirectories(configPath.getParent());
            String json = objectMapper.writerWithDefaultPrettyPrinter()
                    .writeValueAsString(List.copyOf(remoteAgents.values()));
            Files.writeString(configPath, json);
        } catch (IOException e) {
            logger.error("Failed to save A2A agent configs: {}", e.getMessage());
        }
    }
}

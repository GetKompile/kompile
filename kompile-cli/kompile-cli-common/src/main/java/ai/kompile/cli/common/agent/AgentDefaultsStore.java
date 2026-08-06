/*
 * Copyright 2025 Kompile Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package ai.kompile.cli.common.agent;

import ai.kompile.cli.common.KompileHome;
import ai.kompile.cli.common.util.JsonUtils;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Locale;
import java.util.Optional;

/**
 * Shared persistence and precedence rules for external agent model/thinking defaults.
 */
public final class AgentDefaultsStore {

    public static final String CONFIG_FILE = "agent-defaults.json";
    public static final List<String> SUPPORTED_AGENTS = List.of("codex", "claude", "opencode");

    private static final ObjectMapper MAPPER = JsonUtils.newStandardMapper()
            .enable(SerializationFeature.INDENT_OUTPUT);
    private static final Object SAVE_LOCK = new Object();

    private AgentDefaultsStore() {
    }

    public static final class Selection {
        private final String model;
        private final String thinking;

        Selection(String model, String thinking) {
            this.model = clean(model);
            this.thinking = clean(thinking);
        }

        public String model() {
            return model;
        }

        public String thinking() {
            return thinking;
        }
    }

    /**
     * Resolve explicit values, then nearest project values, then user values.
     * Nulls intentionally delegate to the external CLI's native defaults.
     */
    public static Selection resolve(String agentName,
                                    Path workingDirectory,
                                    String explicitModel,
                                    String explicitThinking) {
        String agent = normalizeSupportedAgent(agentName);
        String model = clean(explicitModel);
        if (model == null && agent != null) {
            model = configuredModel(agent, workingDirectory).orElse(null);
        }

        String thinking = clean(explicitThinking);
        if (thinking == null && agent != null) {
            thinking = configuredThinking(agent, model, workingDirectory).orElse(null);
        }
        return new Selection(model, thinking);
    }

    public static Optional<String> configuredModel(String agentName, Path workingDirectory) {
        String agent = normalizeSupportedAgent(agentName);
        if (agent == null) {
            return Optional.empty();
        }
        Optional<String> project = firstProjectValue(workingDirectory, agent,
                node -> text(node.get("model")));
        if (project.isPresent()) {
            return project;
        }
        return valueFromFile(userConfigPath(), agent, node -> text(node.get("model")));
    }

    public static Optional<String> configuredThinking(String agentName,
                                                      String model,
                                                      Path workingDirectory) {
        String agent = normalizeSupportedAgent(agentName);
        if (agent == null) {
            return Optional.empty();
        }
        Optional<String> project = firstProjectValue(workingDirectory, agent,
                node -> thinkingFromAgent(node, model));
        if (project.isPresent()) {
            return project;
        }
        return valueFromFile(userConfigPath(), agent, node -> thinkingFromAgent(node, model));
    }

    public static boolean isSupportedAgent(String agentName) {
        return normalizeSupportedAgent(agentName) != null;
    }

    public static String normalizeSupportedAgent(String agentName) {
        String name = agentName == null ? "" : agentName.trim().toLowerCase(Locale.ROOT);
        return SUPPORTED_AGENTS.contains(name) ? name : null;
    }

    public static Path userConfigPath() {
        return KompileHome.configDirectory().toPath().resolve(CONFIG_FILE);
    }

    public static Path projectConfigPath(Path projectDirectory) {
        Path root = projectDirectory != null
                ? projectDirectory
                : Path.of(System.getProperty("user.dir"));
        return root.toAbsolutePath().normalize().resolve(".kompile").resolve(CONFIG_FILE);
    }

    /**
     * Persist one agent's defaults while preserving every other agent and field.
     */
    public static void save(Path configPath,
                            String agentName,
                            String model,
                            String thinking,
                            String thinkingModel) throws IOException {
        String agent = normalizeSupportedAgent(agentName);
        if (agent == null) {
            throw new IllegalArgumentException("Unsupported agent '" + agentName
                    + "'. Expected one of: " + String.join(", ", SUPPORTED_AGENTS));
        }
        if (configPath == null) {
            throw new IllegalArgumentException("configPath is required");
        }

        Path normalizedPath = configPath.toAbsolutePath().normalize();
        Path parent = normalizedPath.getParent();
        Files.createDirectories(parent);
        Path lockPath = normalizedPath.resolveSibling(normalizedPath.getFileName() + ".lock");

        synchronized (SAVE_LOCK) {
            try (FileChannel channel = FileChannel.open(
                    lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
                 FileLock ignored = channel.lock()) {
                saveLocked(normalizedPath, agent, model, thinking, thinkingModel);
            }
        }
    }

    private static void saveLocked(Path configPath,
                                   String agent,
                                   String model,
                                   String thinking,
                                   String thinkingModel) throws IOException {
        ObjectNode root;
        if (Files.isRegularFile(configPath)) {
            JsonNode existing = MAPPER.readTree(configPath.toFile());
            if (existing == null || !existing.isObject()) {
                throw new IOException("Agent defaults config must contain a JSON object: " + configPath);
            }
            root = (ObjectNode) existing;
        } else {
            root = MAPPER.createObjectNode();
        }

        ObjectNode agents = root.with("agents");
        ObjectNode agentNode = agents.with(agent);

        String selectedModel = clean(model);
        if (selectedModel != null) {
            agentNode.put("model", selectedModel);
        }

        String selectedThinking = clean(thinking);
        if (selectedThinking != null) {
            JsonNode existingThinking = agentNode.get("thinking");
            ObjectNode thinkingNode;
            if (existingThinking != null && existingThinking.isObject()) {
                thinkingNode = (ObjectNode) existingThinking;
            } else {
                thinkingNode = MAPPER.createObjectNode();
                String previousDefault = text(existingThinking).orElse(null);
                if (previousDefault != null) {
                    thinkingNode.put("default", previousDefault);
                }
                agentNode.set("thinking", thinkingNode);
            }

            String selectedThinkingModel = clean(thinkingModel);
            if (selectedThinkingModel == null) {
                thinkingNode.put("default", selectedThinking);
            } else {
                thinkingNode.with("models").put(selectedThinkingModel, selectedThinking);
            }
        }

        Path tempFile = Files.createTempFile(
                configPath.getParent(), "." + configPath.getFileName() + "-", ".tmp");
        try {
            MAPPER.writeValue(tempFile.toFile(), root);
            try {
                Files.move(tempFile, configPath,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                throw new IOException("Atomic replacement is not supported for agent defaults config: "
                        + configPath, e);
            }
        } finally {
            Files.deleteIfExists(tempFile);
        }
    }

    private static Optional<String> firstProjectValue(Path workingDirectory,
                                                      String agent,
                                                      AgentValueReader reader) {
        Path current = workingDirectory != null
                ? workingDirectory
                : Path.of(System.getProperty("user.dir"));
        current = current.toAbsolutePath().normalize();

        while (current != null) {
            Optional<String> direct = valueFromFile(
                    current.resolve(".kompile").resolve(CONFIG_FILE), agent, reader);
            if (direct.isPresent()) {
                return direct;
            }
            Optional<String> nested = valueFromFile(
                    current.resolve(".kompile").resolve("config").resolve(CONFIG_FILE), agent, reader);
            if (nested.isPresent()) {
                return nested;
            }
            current = current.getParent();
        }
        return Optional.empty();
    }

    private static Optional<String> valueFromFile(Path configPath,
                                                  String agent,
                                                  AgentValueReader reader) {
        if (configPath == null || !Files.isRegularFile(configPath)) {
            return Optional.empty();
        }
        try {
            JsonNode root = MAPPER.readTree(configPath.toFile());
            if (root == null || !root.isObject()) {
                throw invalidConfig(configPath, "root must be a JSON object", null);
            }
            JsonNode container = root;
            if (root.has("agents")) {
                container = root.get("agents");
                if (container == null || !container.isObject()) {
                    throw invalidConfig(configPath, "'agents' must be a JSON object", null);
                }
            }
            JsonNode agentNode = container.get(agent);
            if (agentNode == null) {
                return Optional.empty();
            }
            if (!agentNode.isObject()) {
                throw invalidConfig(configPath, "agent '" + agent + "' must be a JSON object", null);
            }
            return reader.read(agentNode);
        } catch (IOException e) {
            throw invalidConfig(configPath, "could not parse JSON", e);
        }
    }

    private static Optional<String> thinkingFromAgent(JsonNode agentNode, String model) {
        Optional<String> legacyMatch = thinkingFromMap(agentNode.get("thinkingByModel"), model);
        if (legacyMatch.isPresent()) {
            return legacyMatch;
        }

        JsonNode thinking = agentNode.get("thinking");
        Optional<String> scalar = text(thinking);
        if (scalar.isPresent()) {
            return scalar;
        }
        if (thinking == null || !thinking.isObject()) {
            return Optional.empty();
        }

        Optional<String> modelMatch = thinkingFromMap(thinking.path("models"), model);
        if (modelMatch.isPresent()) {
            return modelMatch;
        }
        modelMatch = thinkingFromMap(thinking, model);
        if (modelMatch.isPresent()) {
            return modelMatch;
        }
        return firstText(thinking.get("default"), thinking.get("*"));
    }

    private static Optional<String> thinkingFromMap(JsonNode node, String model) {
        if (node == null || !node.isObject()) {
            return Optional.empty();
        }
        String selectedModel = clean(model);
        return selectedModel == null ? Optional.empty() : text(node.get(selectedModel));
    }

    private static Optional<String> firstText(JsonNode... nodes) {
        for (JsonNode node : nodes) {
            Optional<String> value = text(node);
            if (value.isPresent()) {
                return value;
            }
        }
        return Optional.empty();
    }

    private static Optional<String> text(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) {
            return Optional.empty();
        }
        return Optional.ofNullable(clean(node.asText()));
    }

    private static IllegalStateException invalidConfig(Path path, String detail, Exception cause) {
        String message = "Invalid agent defaults config " + path + ": " + detail;
        return cause == null ? new IllegalStateException(message) : new IllegalStateException(message, cause);
    }

    private static String clean(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    @FunctionalInterface
    private interface AgentValueReader {
        Optional<String> read(JsonNode agentNode);
    }
}
